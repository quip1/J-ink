package dev.onyxbox.ferry

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.onyxbox.ferry.core.Endpoint
import dev.onyxbox.ferry.core.FerryClient
import dev.onyxbox.ferry.core.FerryServer
import dev.onyxbox.ferry.core.IncomingSink
import dev.onyxbox.ferry.core.OutgoingFile
import dev.onyxbox.ferry.core.PeerHello
import dev.onyxbox.ferry.core.Placement
import dev.onyxbox.ferry.core.Proto
import dev.onyxbox.ferry.core.ReceiveHost
import dev.onyxbox.ferry.core.Route
import dev.onyxbox.ferry.core.Session
import dev.onyxbox.ferry.core.P2pInfo
import dev.onyxbox.ferry.core.SendListener
import dev.onyxbox.ferry.core.TrustDecision
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Always-on part of Ferry: listens for incoming RFCOMM connections and runs outgoing sends,
 * so neither dies when the screen changes. Runs as a connectedDevice foreground service.
 */
@SuppressLint("MissingPermission")
class FerryService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_SEND = "send"
        const val ACTION_TRUST = "trust"
        const val ACTION_STOP = "stop"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_TREES = "trees"       // BooleanArray: item i is a folder (tree uri)
        const val EXTRA_REQ = "req"
        const val EXTRA_DECISION = "decision"

        private const val CH_STATUS = "status"
        private const val CH_XFER = "xfer"
        private const val CH_ASK = "ask"
        private const val N_STATUS = 1
        private const val N_XFER = 2
        private const val N_DONE = 3
        private const val N_ASK_BASE = 100

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, FerryService::class.java).setAction(ACTION_START))
        }

        /** Queue a send. URIs travel in ClipData so the read grant follows them to the service. */
        fun send(ctx: Context, address: String, uris: List<Uri>, trees: BooleanArray) {
            val i = Intent(ctx, FerryService::class.java).setAction(ACTION_SEND)
                .putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_TREES, trees)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri("files", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
            ctx.startForegroundService(i)
        }

        // Pending trust questions, answered from TrustActivity or notification buttons.
        class Ask(val id: Int, val peer: PeerHello, val count: Int, val total: Long) {
            val latch = CountDownLatch(1)
            @Volatile var answer = TrustDecision.DECLINE
        }
        val asks = java.util.concurrent.ConcurrentHashMap<Int, Ask>()
        private val askIds = AtomicInteger(N_ASK_BASE)

        fun answer(ctx: Context, id: Int, d: TrustDecision) {
            asks.remove(id)?.let { it.answer = d; it.latch.countDown() }
            (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var nm: NotificationManager
    private val sendQueue = Executors.newSingleThreadExecutor()
    private val connPool = Executors.newCachedThreadPool()
    private lateinit var wireless: Wireless
    @Volatile private var dataServer: ServerSocket? = null
    private val dataPort get() = dataServer?.localPort ?: 0
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var stopping = false

    private val btState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> startListening()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopListening("Bluetooth is off")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wireless = Wireless(this)
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Ready to receive", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_XFER, "Transfers", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "New device requests", NotificationManager.IMPORTANCE_HIGH))
        goForeground("Starting…")
        registerReceiver(btState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { prefs.receiving = false; stopListening("Receiving is off"); if (!busySending) stopSelf() }
            ACTION_TRUST -> answer(this, intent.getIntExtra(EXTRA_REQ, 0),
                TrustDecision.valueOf(intent.getStringExtra(EXTRA_DECISION) ?: "DECLINE"))
            ACTION_SEND -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                val clip = intent.clipData ?: return START_STICKY
                val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                val trees = intent.getBooleanArrayExtra(EXTRA_TREES) ?: BooleanArray(uris.size)
                pendingSends.incrementAndGet()
                sendQueue.execute { try { runSend(addr, uris, trees) } finally { pendingSends.decrementAndGet() } }
            }
        }
        if (prefs.receiving) startListening() else if (!busySending && intent?.action != ACTION_SEND) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        stopListening("Stopped")
        runCatching { unregisterReceiver(btState) }
        sendQueue.shutdownNow(); connPool.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ receiving

    @Synchronized private fun startListening() {
        if (!prefs.receiving || acceptThread?.isAlive == true) return
        startDataListener()
        val a = Bt.adapter(this)
        if (a == null) { status("No Bluetooth on this device"); return }
        if (!a.isEnabled) {
            status("Turning Bluetooth on…")
            // The BT state receiver calls startListening() again once it's on.
            Thread { if (!wireless.ensureBluetooth()) status("Bluetooth is off") }.start()
            return
        }
        val ss = try {
            a.listenUsingRfcommWithServiceRecord(Proto.SERVICE_NAME, Proto.SERVICE_UUID)
        } catch (e: IOException) { status("Couldn't listen: ${e.message}"); return }
        server = ss
        status("Ready to receive as “${prefs.deviceName}”")
        acceptThread = Thread({
            while (!stopping) {
                val sock = try { ss.accept() } catch (e: IOException) { break }
                connPool.execute {
                    sock.use {
                        try { FerryServer(AndroidHost(it.remoteDevice.address)).runControl(it.inputStream, it.outputStream, it.remoteDevice.address) }
                        catch (e: Exception) { if (e !is java.io.EOFException) failed("Receive failed: ${e.message}") }
                    }
                }
            }
        }, "ferry-accept").apply { isDaemon = true; start() }
    }

    /** Plain TCP listener for the Wi-Fi routes; every connection must present a Bluetooth-issued token. */
    private fun startDataListener() {
        if (dataServer != null) return
        val ss = try { ServerSocket(0).apply { receiveBufferSize = 1 shl 20 } } catch (e: IOException) { return }
        dataServer = ss
        Thread({
            while (!stopping) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                connPool.execute {
                    s.use {
                        try { FerryServer(AndroidHost("")).runData(it) }
                        catch (e: Exception) { if (e !is java.io.EOFException) failed("Receive failed: ${e.message}") }
                    }
                }
            }
        }, "ferry-data").apply { isDaemon = true; start() }
    }

    @Synchronized private fun stopListening(why: String) {
        runCatching { dataServer?.close() }
        dataServer = null
        runCatching { server?.close() }
        server = null
        acceptThread = null
        status(why)
    }

    private inner class AndroidHost(private val address: String) : ReceiveHost {
        override val me get() = prefs.identity
        private val root: File = Environment.getExternalStorageDirectory()
        private var lastUi = 0L

        override fun isTrusted(peerId: String) = prefs.isTrusted(peerId)

        override fun prepare(peer: PeerHello, total: Long) {
            if (address.isNotEmpty()) prefs.remember(address, peer.id, peer.name, peer.os)
            if (total >= BIG) { status("${peer.name} is sending ${fmtSize(total)} — waking Wi-Fi"); wireless.ensureWifiFor(this) }
        }
        override fun lanEndpoints() = if (dataPort > 0) wireless.lanEndpoints(dataPort) else emptyList()
        override fun canHostP2p() = dataPort > 0 && wireless.p2pSupported
        override fun startP2p(session: Session): P2pInfo {
            progress("Opening Wi-Fi Direct for ${session.peer.name}", -1f, "")
            return wireless.hostGroup(dataPort)
        }
        override fun onSessionEnd(session: Session) {
            if (session.usedP2p) Thread { SystemClock.sleep(3000); wireless.releaseGroup() }.start()
            wireless.releaseWifiFor(this)
            startedAt = 0
        }
        private var startedAt = 0L
        override fun rememberTrust(peer: PeerHello) = prefs.trust(peer.id, peer.name)

        override fun askTrust(peer: PeerHello, count: Int, total: Long): TrustDecision {
            val ask = Ask(askIds.incrementAndGet(), peer, count, total)
            asks[ask.id] = ask
            fun act(label: String, d: TrustDecision) = Notification.Action.Builder(null, label,
                PendingIntent.getService(this@FerryService, ask.id * 4 + d.ordinal,
                    Intent(this@FerryService, FerryService::class.java).setAction(ACTION_TRUST)
                        .putExtra(EXTRA_REQ, ask.id).putExtra(EXTRA_DECISION, d.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
            val open = PendingIntent.getActivity(this@FerryService, ask.id,
                Intent(this@FerryService, TrustActivity::class.java).putExtra(EXTRA_REQ, ask.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ask.id, Notification.Builder(this@FerryService, CH_ASK)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle("${peer.name} wants to send $count file${if (count == 1) "" else "s"}")
                .setContentText("${fmtSize(total)} · first time from this device")
                .setContentIntent(open).setFullScreenIntent(open, true).setAutoCancel(true)
                .addAction(act("Decline", TrustDecision.DECLINE))
                .addAction(act("Once", TrustDecision.ONCE))
                .addAction(act("Always", TrustDecision.ALWAYS)).build())
            Bus.post(Bus.Ev.TrustAsk(ask.id))
            if (!ask.latch.await(60, TimeUnit.SECONDS)) { asks.remove(ask.id); nm.cancel(ask.id) }
            return ask.answer
        }

        override fun openSink(path: String, size: Long): IncomingSink {
            if (!Perms.allFiles(this@FerryService)) throw IOException("${prefs.deviceName} needs All-files access (open Ferry there)")
            val dest = Placement.destination(root, path)
            if (root.usableSpace in 0 until size + (16 shl 20)) throw IOException("not enough space on ${prefs.deviceName}")
            val tmp = File(dest.parentFile, dest.name + ".ferrypart")
            val fos = FileOutputStream(tmp)
            return object : IncomingSink {
                override val stream: OutputStream = fos
                override fun commit(): String {
                    runCatching { fos.close() }
                    val final = Placement.unique(dest)
                    if (!tmp.renameTo(final)) throw IOException("rename failed for ${final.name}")
                    MediaScannerConnection.scanFile(this@FerryService, arrayOf(final.absolutePath), null, null)
                    return final.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                }
                override fun abort() { runCatching { fos.close() }; tmp.delete() }
            }
        }

        private var route = Route.BT
        override fun onStart(peer: PeerHello, count: Int, total: Long, route: Route) {
            this.route = route; startedAt = SystemClock.uptimeMillis()
            progress("Receiving $count from ${peer.name} via ${route.label}", 0f, "0 / ${fmtSize(total)}")
        }

        override fun onProgress(peer: PeerHello, received: Long, total: Long, path: String) {
            val now = SystemClock.uptimeMillis()
            if (now - lastUi < 1200 && received < total) return   // e-ink: don't repaint constantly
            lastUi = now
            progress("From ${peer.name}: ${path.substringAfterLast('/')}",
                if (total > 0) received.toFloat() / total else -1f,
                "${fmtSize(received)} / ${fmtSize(total)} · ${route.label} ${rate(received, startedAt)}")
        }

        override fun onFinished(peer: PeerHello, saved: List<String>, route: Route) {
            doneProgress()
            val summary = summarize(saved)
            prefs.log("⇩ ${saved.size} from ${peer.name} via ${route.label} → $summary"); Bus.post(Bus.Ev.HistoryChanged)
            notifyDone("Received ${saved.size} from ${peer.name}", summary)
        }
    }

    // ------------------------------------------------------------------ sending

    private val pendingSends = AtomicInteger(0)
    private val busySending get() = pendingSends.get() > 0

    private fun runSend(address: String, uris: List<Uri>, trees: BooleanArray) {
        val temps = ArrayList<File>()
        val token = Any()
        var joinedP2p = false
        var client: FerryClient? = null
        var label = address
        try {
            if (!wireless.ensureBluetooth()) throw IOException("Bluetooth is off and couldn't be turned on")
            val dev = Bt.adapter(this)?.getRemoteDevice(address) ?: throw IOException("Bluetooth unavailable")
            label = Bt.name(dev)
            progress("Preparing files for $label", -1f, "")
            val files = ArrayList<OutgoingFile>()
            uris.forEachIndexed { i, u -> if (trees.getOrElse(i) { false }) expandTree(u, files) else files += single(u, temps) }
            if (files.isEmpty()) throw IOException("nothing to send")
            val total = files.sumOf { it.size }
            if (total >= BIG) { progress("Waking Wi-Fi", -1f, "${files.size} file(s), ${fmtSize(total)}"); wireless.ensureWifiFor(token) }

            progress("Connecting to $label", -1f, "${files.size} file(s), ${fmtSize(total)}")
            val bt = Bt.connect(this, dev, 12000)
            val c = FerryClient(prefs.identity, bt.inputStream, bt.outputStream).also { client = it }
            val h = try { c.hello("send", total) } catch (e: Exception) { runCatching { bt.close() }; throw e }
            prefs.remember(address, h.id, h.name, h.os)

            // ---- pick the fastest route: same network → Wi-Fi Direct → stay on Bluetooth
            if (h.version >= 2 && total >= SMALL) {
                progress("Finding the fastest route to ${h.name}", -1f, "trying Wi-Fi")
                var ok = c.tryAttach(h.lan, Route.LAN)
                if (!ok && total >= BIG && h.canHostP2p && wireless.p2pSupported) {
                    progress("Setting up Wi-Fi Direct with ${h.name}", -1f, "a few seconds…")
                    try {
                        val info = c.requestP2p()
                        wireless.joinGroup(info); joinedP2p = true
                        val until = SystemClock.uptimeMillis() + 20_000
                        while (!ok && SystemClock.uptimeMillis() < until)
                            ok = c.tryAttach(listOf(Endpoint(info.ip, info.port)), Route.P2P, 3500, 3000)
                    } catch (e: Exception) {
                        prefs.log("· Wi-Fi Direct with ${h.name} unavailable (${e.message}); using Bluetooth")
                        if (joinedP2p) { wireless.leaveGroup(); joinedP2p = false }
                    }
                }
                if (ok) runCatching { bt.close() }   // Bluetooth's job is done
            }
            if (c.route == Route.BT && total >= BIG) Bus.post(Bus.Ev.Toast("No Wi-Fi route to ${h.name} — using Bluetooth (slow)"))

            if (!h.trustedByThem) progress("Waiting for ${h.name} to accept…", -1f, "First time — tap Once or Always there")
            var lastUi = 0L
            val started = SystemClock.uptimeMillis()
            val saved = try {
                c.send(files, object : SendListener {
                    var current = ""
                    override fun onFileStart(index: Int, count: Int, path: String) { current = "${index + 1}/$count ${path.substringAfterLast('/')}" }
                    override fun onProgress(sentBytes: Long, totalBytes: Long) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastUi < 1200 && sentBytes < totalBytes) return
                        lastUi = now
                        progress("To ${h.name}: $current", if (totalBytes > 0) sentBytes.toFloat() / totalBytes else -1f,
                            "${fmtSize(sentBytes)} / ${fmtSize(totalBytes)} · ${c.route.label} ${rate(sentBytes, started)}")
                    }
                })
            } finally { runCatching { bt.close() } }
            doneProgress()
            val secs = (SystemClock.uptimeMillis() - started) / 1000.0
            val summary = summarize(saved)
            prefs.log("⇧ ${saved.size} to ${h.name} via ${c.route.label} (${fmtSize(total)} in ${"%.0f".format(secs)} s) → $summary")
            Bus.post(Bus.Ev.HistoryChanged)
            notifyDone("Sent ${saved.size} to ${h.name}", "via ${c.route.label} · $summary")
        } catch (e: Exception) {
            doneProgress()
            failed("Send to $label failed: ${e.message}")
        } finally {
            client?.close()
            if (joinedP2p) wireless.leaveGroup()
            wireless.releaseWifiFor(token)
            temps.forEach { it.delete() }
            if (!prefs.receiving && pendingSends.get() <= 1) stopSelf()
        }
    }

    /** One picked/shared document. Streams directly when the size is known, else stages to cache. */
    private fun single(u: Uri, temps: MutableList<File>): OutgoingFile {
        var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        if (u.scheme == "file") {
            val f = File(u.path!!); return OutgoingFile(f.name, f.length()) { f.inputStream() }
        }
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) {
            val t = File.createTempFile("ferry", null, cacheDir); temps += t
            contentResolver.openInputStream(u)!!.use { src -> t.outputStream().use { src.copyTo(it) } }
            return OutgoingFile(name, t.length()) { t.inputStream() }
        }
        return OutgoingFile(name, size) { contentResolver.openInputStream(u) ?: throw IOException("can't open $name") }
    }

    /** Walk a picked folder; paths keep the folder name so structure survives on the other side. */
    private fun expandTree(tree: Uri, out: MutableList<OutgoingFile>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun walk(docId: String, prefix: String) {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val nm = c.getString(1); val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$prefix$nm/")
                    else {
                        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out += OutgoingFile("$prefix$nm", c.getLong(3)) {
                            contentResolver.openInputStream(doc) ?: throw IOException("can't open $nm")
                        }
                    }
                }
            }
        }
        val rootName = contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null } ?: "Folder"
        walk(rootId, "$rootName/")
    }

    // ------------------------------------------------------------------ notifications / UI

    private fun goForeground(text: String) = startForeground(N_STATUS, statusNotification(text))

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_STATUS).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ferry").setContentText(text).setContentIntent(open).setOngoing(true).build()
    }

    private fun status(text: String) {
        nm.notify(N_STATUS, statusNotification(text))
        Bus.post(Bus.Ev.Status(text))
    }

    private fun progress(title: String, fraction: Float, detail: String) {
        Bus.post(Bus.Ev.Progress("$title\n$detail", fraction))
        val b = Notification.Builder(this, CH_XFER).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(detail).setOngoing(true).setOnlyAlertOnce(true)
        if (fraction < 0) b.setProgress(0, 0, true) else b.setProgress(1000, (fraction * 1000).toInt(), false)
        nm.notify(N_XFER, b.build())
    }

    private fun doneProgress() { nm.cancel(N_XFER); Bus.post(Bus.Ev.Progress(null, 0f)) }

    private fun notifyDone(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(N_DONE, Notification.Builder(this, CH_XFER).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun failed(text: String) {
        prefs.log("✕ $text"); Bus.post(Bus.Ev.HistoryChanged)
        notifyDone("Ferry", text)
    }
}

/** Below this, Wi-Fi setup costs more than it saves; above BIG it's worth waking radios and Wi-Fi Direct. */
const val SMALL = 256L * 1024
const val BIG = 4L * 1024 * 1024

fun rate(bytes: Long, since: Long): String {
    val s = (SystemClock.uptimeMillis() - since) / 1000.0
    if (since == 0L || s < 1) return ""
    val bps = bytes / s
    return if (bps >= 1048576) "%.1f MB/s".format(bps / 1048576) else "%.0f KB/s".format(bps / 1024)
}

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

/** "Books/a.epub, Books/b.epub" → "Books (2)" style summary of where things landed. */
fun summarize(saved: List<String>): String {
    if (saved.isEmpty()) return "nothing saved"
    if (saved.size == 1) return saved[0]
    return saved.groupingBy { p -> p.split('/', '\\').dropLast(1).take(2).joinToString("/").ifEmpty { "." } }
        .eachCount().entries.joinToString(", ") { "${it.key} (${it.value})" }
}
