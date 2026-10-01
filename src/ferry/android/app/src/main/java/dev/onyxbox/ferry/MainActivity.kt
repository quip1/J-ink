package dev.onyxbox.ferry

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var devicesBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var shareBox: LinearLayout

    private var shareUris: List<Uri> = emptyList()
    private var pickTarget: String? = null
    private val peerLabels = HashMap<String, String>()     // address -> subtitle
    private val peerOk = HashMap<String, Boolean>()
    private val probeRunner = Executors.newFixedThreadPool(2)
    private val probeGen = AtomicInteger()
    private var probedAllThisLaunch = false

    private val onBus: (Bus.Ev) -> Unit = { e ->
        when (e) {
            is Bus.Ev.Status -> statusView.text = e.text
            is Bus.Ev.Progress -> showProgress(e)
            is Bus.Ev.HistoryChanged -> renderHistory()
            is Bus.Ev.TrustAsk -> startActivity(Intent(this, TrustActivity::class.java).putExtra(FerryService.EXTRA_REQ, e.reqId))
            is Bus.Ev.Toast -> android.widget.Toast.makeText(this, e.text, android.widget.Toast.LENGTH_LONG).show()
            is Bus.Ev.PeerResult -> { peerLabels[e.address] = e.label; peerOk[e.address] = e.ok; renderDevices() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); ui = Ui(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(32))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); isVerticalScrollBarEnabled = false; addView(root) })
        buildUi()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent) }

    override fun onResume() {
        super.onResume()
        Bus.sub(onBus)
        renderSetup()
        if (Perms.bluetooth(this) && prefs.receiving) FerryService.start(this)
        statusView.text = Bus.lastStatus.ifEmpty { if (prefs.receiving) "Starting…" else "Receiving is off" }
        showProgress(Bus.lastProgress)
        renderHistory()
        refreshDevices(probeAll = !probedAllThisLaunch)
        FerryService.asks.keys.firstOrNull()?.let { onBus(Bus.Ev.TrustAsk(it)) }
    }

    override fun onPause() { Bus.unsub(onBus); super.onPause() }
    override fun onDestroy() { probeRunner.shutdownNow(); super.onDestroy() }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        root.addView(ui.title("Ferry"))
        val nameView = ui.small("This device: ${prefs.deviceName}  ·  rename")
        nameView.setOnClickListener { rename(nameView) }
        root.addView(nameView)

        statusView = ui.body("")
        val recvBtn = ui.button(if (prefs.receiving) "Receiving: on" else "Receiving: off") {}
        recvBtn.setOnClickListener {
            prefs.receiving = !prefs.receiving
            recvBtn.text = if (prefs.receiving) "Receiving: on" else "Receiving: off"
            if (prefs.receiving) FerryService.start(this)
            else startService(Intent(this, FerryService::class.java).setAction(FerryService.ACTION_STOP))
        }
        root.addView(ui.rule())
        root.addView(statusView)
        root.addView(recvBtn)

        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(setupBox)

        shareBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(shareBox)

        progressText = ui.body("")
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.LTGRAY)
        }
        root.addView(progressText); root.addView(progressBar)

        val refresh = ui.button("Refresh") { refreshDevices(probeAll = true) }
        root.addView(ui.row(ui.heading("Devices").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }, refresh))
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox)

        root.addView(ui.heading("Recent"))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox)

        root.addView(ui.heading("Settings"))
        val boot = ui.button(if (prefs.startOnBoot) "Start at boot: on" else "Start at boot: off") {}
        boot.setOnClickListener { prefs.startOnBoot = !prefs.startOnBoot; boot.text = if (prefs.startOnBoot) "Start at boot: on" else "Start at boot: off" }
        root.addView(boot)
        root.addView(ui.button("Trusted devices…") { showTrusted() })
        root.addView(ui.small("\nReceived files are sorted by type: books → /Books, audiobooks → /Audiobooks, " +
            "fonts → /Fonts, pictures → /Pictures/Ferry, documents → /Documents, anything else → /Download/Ferry.\n\n" +
            "Speed: Bluetooth finds the device; files over 256 KB then move over your Wi-Fi if both devices are on it, " +
            "otherwise over a direct Wi-Fi link between them. Ferry switches Wi-Fi on for big transfers and back off afterwards."))
    }

    private fun renderSetup() {
        setupBox.removeAllViews()
        fun need(text: String, btn: String, act: () -> Unit) {
            setupBox.addView(ui.body(text).apply { setPadding(0, ui.dp(12), 0, 0) })
            setupBox.addView(ui.button(btn, true, act))
        }
        if (Bt.adapter(this)?.isEnabled != true) need("Bluetooth is off. Ferry switches it on by itself when needed, or do it now.", "Turn on Bluetooth") {
            Thread { Wireless(this).ensureBluetooth(); runOnUiThread { renderSetup(); refreshDevices(true) } }.start() }
        if (!Perms.allFiles(this)) {
            if (!Perms.storageWrite(this)) need("Allow storage so received books land in /Books, fonts in /Fonts, etc.", "Allow storage") {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 3) }
            else need("To drop books into /Books, fonts into /Fonts, etc., Ferry needs All-files access.", "Allow All-files access") {
                Perms.openAllFilesSettings(this) }
        }
        if (!Perms.location(this)) need("Allow Location so big files can use Wi-Fi Direct when you're not on the same Wi-Fi (Android requires it for Wi-Fi Direct; Ferry never reads your location).",
            "Allow Wi-Fi Direct") { requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 4) }
        if (!Perms.batteryUnrestricted(this)) need("Boox freezes background apps. Let Ferry run unrestricted so it can receive while you read.",
            "Keep Ferry running") { Perms.openBatterySettings(this) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        renderSetup()
        if (Perms.bluetooth(this)) { if (prefs.receiving) FerryService.start(this); refreshDevices(true) }
    }

    // ------------------------------------------------------------------ devices

    private fun refreshDevices(probeAll: Boolean) {
        if (!Perms.bluetooth(this)) { renderDevices(); return }
        val gen = probeGen.incrementAndGet()
        val known = prefs.known().associateBy { it.address }
        val cands = Bt.candidates(this)
        for (d in cands) {
            val isFerry = known.containsKey(d.address) || Bt.advertisesFerry(d)
            if (isFerry || probeAll) {
                if (peerOk[d.address] != true) peerLabels[d.address] = "checking…"
                probeRunner.execute {
                    if (gen != probeGen.get()) return@execute
                    try {
                        val h = Bt.probe(this, d, prefs)
                        Bus.post(Bus.Ev.PeerResult(d.address, true, "ready · Ferry on ${h.os}" +
                            if (h.trustedByThem) " · trusts you" else " · will ask once"))
                    } catch (e: Exception) {
                        Bus.post(Bus.Ev.PeerResult(d.address, false,
                            if (isFerry || prefs.known().any { it.address == d.address }) "not reachable right now" else "no Ferry"))
                    }
                }
            }
        }
        if (probeAll) probedAllThisLaunch = true
        renderDevices()
    }

    private fun renderDevices() {
        devicesBox.removeAllViews()
        if (!Perms.bluetooth(this)) { devicesBox.addView(ui.small("Allow Bluetooth above to see your devices.")); return }
        val cands = Bt.candidates(this)
        val known = prefs.known().associateBy { it.address }
        val ferry = cands.filter { known.containsKey(it.address) || Bt.advertisesFerry(it) || peerOk[it.address] == true }
        val others = cands - ferry.toSet()
        if (ferry.isEmpty()) devicesBox.addView(ui.small(
            if (cands.isEmpty()) "No paired devices. Pair your Boox / PC once in Bluetooth settings, then open Ferry on both."
            else "Looking for Ferry on your paired devices…"))
        for (d in ferry) {
            val name = known[d.address]?.name?.takeIf { it.isNotBlank() } ?: Bt.name(d)
            var sub = peerLabels[d.address] ?: "known"
            known[d.address]?.let { k -> if (peerOk[d.address] != true && k.seen > 0)
                sub += " · seen " + DateUtils.getRelativeTimeSpanString(k.seen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) }
            val (card, _) = ui.card(name, sub) { onDeviceTap(d, name) }
            devicesBox.addView(card)
        }
        if (others.isNotEmpty()) {
            devicesBox.addView(ui.small("\nOther paired devices (no Ferry detected): " + others.joinToString(", ") { Bt.name(it) }))
        }
    }

    private fun onDeviceTap(d: BluetoothDevice, name: String) {
        if (shareUris.isNotEmpty()) {
            FerryService.send(this, d.address, shareUris, BooleanArray(shareUris.size))
            shareUris = emptyList(); renderShare()
            if (isTaskRoot.not()) finish()
            return
        }
        AlertDialog.Builder(this).setTitle("Send to $name")
            .setItems(arrayOf("Files…", "A whole folder…")) { _, which ->
                pickTarget = d.address
                if (which == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 10)
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 11)
            }.show()
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = pickTarget ?: return
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            10 -> {
                val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
                if (uris.isNotEmpty()) FerryService.send(this, target, uris, BooleanArray(uris.size))
            }
            11 -> data.data?.let { tree ->
                runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                FerryService.send(this, target, listOf(tree), booleanArrayOf(true))
            }
        }
    }

    // ------------------------------------------------------------------ share sheet

    private fun handleShare(i: Intent?) {
        shareUris = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        renderShare()
    }

    private fun renderShare() {
        shareBox.removeAllViews()
        if (shareUris.isEmpty()) return
        shareBox.addView(ui.rule())
        shareBox.addView(ui.heading("Send ${shareUris.size} item${if (shareUris.size == 1) "" else "s"} — tap a device below"))
        shareBox.addView(ui.button("Cancel") { shareUris = emptyList(); renderShare() })
    }

    // ------------------------------------------------------------------ misc

    private fun showProgress(p: Bus.Ev.Progress) {
        val t = p.text
        if (t == null) { progressText.text = ""; progressBar.visibility = android.view.View.GONE; return }
        progressText.text = t
        progressBar.visibility = android.view.View.VISIBLE
        progressBar.isIndeterminate = p.fraction < 0
        if (p.fraction >= 0) progressBar.progress = (p.fraction * 1000).toInt()
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val h = prefs.history().take(15)
        if (h.isEmpty()) historyBox.addView(ui.small("Nothing yet."))
        for (e in h) historyBox.addView(ui.small(DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS).toString() + "  " + e.text).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) })
    }

    private fun rename(label: TextView) {
        val input = EditText(this).apply { setText(prefs.deviceName); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Name shown to your other devices").setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.deviceName = input.text.toString()
                label.text = "This device: ${prefs.deviceName}  ·  rename"
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showTrusted() {
        val t = prefs.trusted().entries.toList()
        if (t.isEmpty()) { AlertDialog.Builder(this).setMessage("No trusted devices yet. The first transfer from a device asks you; “Always” adds it here.")
            .setPositiveButton("OK", null).show(); return }
        AlertDialog.Builder(this).setTitle("Tap to forget")
            .setItems(t.map { it.value }.toTypedArray()) { _, i -> prefs.untrust(t[i].key); showTrusted() }
            .setNegativeButton("Done", null).show()
    }
}
