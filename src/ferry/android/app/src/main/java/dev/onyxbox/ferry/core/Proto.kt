package dev.onyxbox.ferry.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ferry wire protocol v2 (see PROTOCOL.md).
 * Bluetooth carries the handshake; bulk data moves over TCP (home LAN or Wi-Fi Direct) when possible.
 */
object Proto {
    const val VERSION = 2
    val SERVICE_UUID: UUID = UUID.fromString("6f0a7c2e-4b1d-4e8a-9c35-f3a1d0b7e2c9")
    const val SERVICE_NAME = "Ferry"
    private const val MAX_FRAME = 65536
    const val CHUNK = 128 * 1024
    val rng = SecureRandom()

    fun writeFrame(out: DataOutputStream, obj: JSONObject) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun readFrame(inp: DataInputStream): JSONObject {
        val len = inp.readInt()
        if (len < 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
        val buf = ByteArray(len)
        inp.readFully(buf)
        return JSONObject(String(buf, Charsets.UTF_8))
    }

    fun expect(obj: JSONObject, type: String): JSONObject {
        val t = obj.optString("t")
        if (t == "error" || t == "reject" || t == "p2p_fail") throw RemoteRefusal(obj.optString("reason", t))
        if (t != type) throw IOException("expected '$type', got '$t'")
        return obj
    }

    fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}

class RemoteRefusal(msg: String) : IOException(msg)

data class Identity(val id: String, val name: String, val os: String)

data class Endpoint(val host: String, val port: Int) {
    fun toJson(): JSONObject = JSONObject().put("ip", host).put("port", port)
    companion object {
        fun list(a: JSONArray?): List<Endpoint> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it).let { e -> Endpoint(e.getString("ip"), e.getInt("port")) } }
    }
}

enum class Route(val label: String) { LAN("Wi-Fi"), P2P("Wi-Fi Direct"), BT("Bluetooth") }

data class PeerHello(
    val id: String, val name: String, val os: String, val trustedByThem: Boolean,
    val version: Int = 1,
    val token: String? = null, val key: ByteArray? = null,
    val lan: List<Endpoint> = emptyList(), val canHostP2p: Boolean = false,
)

data class P2pInfo(val ssid: String, val pass: String, val ip: String, val port: Int)

/** A file to send. [open] is called once, when its turn comes. */
class OutgoingFile(val path: String, val size: Long, val open: () -> InputStream)

interface SendListener {
    fun onRoute(route: Route) {}
    fun onFileStart(index: Int, count: Int, path: String) {}
    fun onProgress(sentBytes: Long, totalBytes: Long) {}
    fun onFileSaved(path: String, savedAs: String) {}
}

// ------------------------------------------------------------------ AES-256-CTR stream encryption

/** Wi-Fi paths are encrypted with a per-session key handed over the (already encrypted) Bluetooth link. */
object Crypt {
    val IV_C2S = ByteArray(16).also { it[0] = 'C'.code.toByte() }
    val IV_S2C = ByteArray(16).also { it[0] = 'S'.code.toByte() }
    fun cipher(key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
}

class CtrOutputStream(out: OutputStream, private val c: Cipher) : FilterOutputStream(out) {
    private var scratch = ByteArray(Proto.CHUNK)
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (scratch.size < len) scratch = ByteArray(len)
        val n = c.update(b, off, len, scratch, 0)
        out.write(scratch, 0, n)
    }
}

class CtrInputStream(inp: InputStream, private val c: Cipher) : FilterInputStream(inp) {
    private var scratch = ByteArray(Proto.CHUNK)
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (scratch.size < len) scratch = ByteArray(len)
        val n = `in`.read(scratch, 0, len)
        if (n <= 0) return n
        c.update(scratch, 0, n, b, off)
        return n
    }
    override fun skip(n: Long) = throw UnsupportedOperationException()
    override fun markSupported() = false
}

// ------------------------------------------------------------------ client

/**
 * Client side. Starts on the Bluetooth control link; [tryAttach] moves the session onto a
 * faster TCP link. Anything not moved keeps using Bluetooth.
 */
class FerryClient(private val me: Identity, input: InputStream, output: OutputStream) {
    private var inp = DataInputStream(input.buffered(16 * 1024))
    private var out = DataOutputStream(output.buffered(16 * 1024))
    var route = Route.BT; private set
    private var peer: PeerHello? = null
    private var dataSocket: Socket? = null

    /** [total] lets the receiver decide whether it's worth waking its Wi-Fi. */
    fun hello(purpose: String, total: Long = 0): PeerHello {
        Proto.writeFrame(out, JSONObject()
            .put("t", "hello").put("v", Proto.VERSION)
            .put("id", me.id).put("name", me.name).put("os", me.os).put("purpose", purpose).put("total", total))
        val h = Proto.expect(Proto.readFrame(inp), "hello")
        return PeerHello(h.getString("id"), h.optString("name", "?"), h.optString("os", "?"),
            h.optBoolean("trusted", false), h.optInt("v", 1),
            h.optString("token").ifEmpty { null }, h.optString("key").ifEmpty { null }?.let { Proto.unb64(it) },
            Endpoint.list(h.optJSONArray("lan")), h.optBoolean("canHost", false)).also { peer = it }
    }

    /** Ask the receiver to start a Wi-Fi Direct group we can join. */
    fun requestP2p(): P2pInfo {
        Proto.writeFrame(out, JSONObject().put("t", "p2p_req"))
        val r = Proto.expect(Proto.readFrame(inp), "p2p")
        return P2pInfo(r.getString("ssid"), r.getString("pass"), r.getString("ip"), r.getInt("port"))
    }

    /**
     * Connect to any of [endpoints] in parallel; the first one that proves it is the same session
     * (token check) wins and becomes the data link. Returns false if none worked within [timeoutMs].
     * [socketFactory] lets Android bind to a specific network (Wi-Fi Direct).
     */
    fun tryAttach(endpoints: List<Endpoint>, via: Route, timeoutMs: Long = 2500, connectTimeoutMs: Int = 2000,
                  socketFactory: () -> Socket = { Socket() }): Boolean {
        val p = peer ?: return false
        val token = p.token ?: return false
        val key = p.key ?: return false
        if (endpoints.isEmpty()) return false
        val results = LinkedBlockingQueue<Any>()
        endpoints.forEach { ep ->
            Thread {
                val s = socketFactory()
                try {
                    s.tcpNoDelay = true
                    s.sendBufferSize = 1 shl 20; s.receiveBufferSize = 1 shl 20
                    s.connect(InetSocketAddress(ep.host, ep.port), connectTimeoutMs)
                    s.soTimeout = 5000
                    val di = DataInputStream(s.getInputStream())
                    val dout = DataOutputStream(s.getOutputStream())
                    Proto.writeFrame(dout, JSONObject().put("t", "attach").put("token", token))
                    Proto.expect(Proto.readFrame(di), "attached")
                    s.soTimeout = 60_000
                    results.put(s)
                } catch (e: Exception) {
                    runCatching { s.close() }; results.put(e)
                }
            }.apply { isDaemon = true; start() }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var remaining = endpoints.size
        var winner: Socket? = null
        while (remaining > 0) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val r = results.poll(left, TimeUnit.MILLISECONDS) ?: break
            remaining--
            if (r is Socket) {
                if (winner == null) winner = r else runCatching { r.close() }
            }
        }
        // late finishers get closed by a reaper so they don't leak
        if (remaining > 0) Thread { repeat(remaining) { (results.poll(10, TimeUnit.SECONDS) as? Socket)?.let { s -> if (s !== winner) runCatching { s.close() } } } }
            .apply { isDaemon = true; start() }
        val s = winner ?: return false
        dataSocket = s
        inp = DataInputStream(CtrInputStream(s.getInputStream(), Crypt.cipher(key, Crypt.IV_S2C)).buffered(Proto.CHUNK))
        out = DataOutputStream(CtrOutputStream(s.getOutputStream(), Crypt.cipher(key, Crypt.IV_C2S)).buffered(Proto.CHUNK))
        route = via
        return true
    }

    fun close() { runCatching { dataSocket?.close() } }

    /** Sends every file; throws on refusal or failure. Returns the paths the receiver saved to. */
    fun send(files: List<OutgoingFile>, listener: SendListener? = null): List<String> {
        listener?.onRoute(route)
        val total = files.sumOf { it.size }
        val arr = JSONArray()
        files.forEach { arr.put(JSONObject().put("path", it.path).put("size", it.size)) }
        Proto.writeFrame(out, JSONObject().put("t", "offer")
            .put("count", files.size).put("total", total).put("files", arr))
        Proto.expect(Proto.readFrame(inp), "accept")

        val saved = ArrayList<String>()
        val buf = ByteArray(Proto.CHUNK)
        var sentTotal = 0L
        files.forEachIndexed { i, f ->
            listener?.onFileStart(i, files.size, f.path)
            Proto.writeFrame(out, JSONObject().put("t", "file").put("path", f.path).put("size", f.size))
            val crc = CRC32()
            var remaining = f.size
            f.open().use { src ->
                while (remaining > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw EOFException("${f.path} ended $remaining bytes early")
                    out.write(buf, 0, n)
                    crc.update(buf, 0, n)
                    remaining -= n
                    sentTotal += n
                    listener?.onProgress(sentTotal, total)
                }
            }
            Proto.writeFrame(out, JSONObject().put("t", "fend").put("crc32", crc.value))
            val r = Proto.expect(Proto.readFrame(inp), "saved")
            val savedAs = r.optString("as", f.path)
            saved += savedAs
            listener?.onFileSaved(f.path, savedAs)
        }
        Proto.writeFrame(out, JSONObject().put("t", "done"))
        runCatching { Proto.readFrame(inp) } // "bye"; don't fail a finished transfer on it
        return saved
    }
}

// ------------------------------------------------------------------ server

enum class TrustDecision { ONCE, ALWAYS, DECLINE }

/** One handshake's worth of state, waiting for its data link. */
class Session(val peer: PeerHello, val trusted: Boolean, val address: String) {
    val token: String = Proto.b64(Proto.randomBytes(18))
    val key: ByteArray = Proto.randomBytes(32)
    val created = System.currentTimeMillis()
    @Volatile var usedP2p = false
}

object Sessions {
    private val map = ConcurrentHashMap<String, Session>()
    fun put(s: Session) {
        val cutoff = System.currentTimeMillis() - 120_000
        map.values.removeIf { it.created < cutoff }
        map[s.token] = s
    }
    fun take(token: String): Session? = map.remove(token)
    fun drop(s: Session) { map.remove(s.token) }
}

/** Everything platform-specific about receiving. */
interface ReceiveHost {
    val me: Identity
    fun isTrusted(peerId: String): Boolean
    /** Blocks until the user answers (implementations should time out). */
    fun askTrust(peer: PeerHello, count: Int, total: Long): TrustDecision
    fun rememberTrust(peer: PeerHello)
    fun openSink(path: String, size: Long): IncomingSink
    /** Called before answering a send hello; a good moment to wake Wi-Fi for a big transfer. */
    fun prepare(peer: PeerHello, total: Long) {}
    /** Addresses where our TCP data listener can be reached on local networks. */
    fun lanEndpoints(): List<Endpoint> = emptyList()
    fun canHostP2p(): Boolean = false
    /** Start (or reuse) a Wi-Fi Direct group; blocks until it's up. Throw with a human reason on failure. */
    fun startP2p(session: Session): P2pInfo = throw IOException("Wi-Fi Direct not supported")
    /** Called once a session's transfer is over, whichever route it took (release Wi-Fi Direct here). */
    fun onSessionEnd(session: Session) {}
    fun onStart(peer: PeerHello, count: Int, total: Long, route: Route) {}
    fun onProgress(peer: PeerHello, received: Long, total: Long, path: String) {}
    fun onFinished(peer: PeerHello, saved: List<String>, route: Route) {}
}

interface IncomingSink {
    val stream: OutputStream
    fun commit(): String
    fun abort()
}

class FerryServer(private val host: ReceiveHost) {

    /** Bluetooth side: handshake, optional Wi-Fi Direct setup, or the whole transfer as a fallback. */
    fun runControl(input: InputStream, output: OutputStream, address: String = "") {
        val inp = DataInputStream(input.buffered(16 * 1024))
        val out = DataOutputStream(output.buffered(16 * 1024))
        val h = Proto.expect(Proto.readFrame(inp), "hello")
        val peer = PeerHello(h.getString("id"), h.optString("name", "?"), h.optString("os", "?"), false, h.optInt("v", 1))
        val trusted = host.isTrusted(peer.id)
        val reply = JSONObject().put("t", "hello").put("v", Proto.VERSION)
            .put("id", host.me.id).put("name", host.me.name).put("os", host.me.os).put("trusted", trusted)
        if (h.optString("purpose") == "probe") { Proto.writeFrame(out, reply); return }

        val session = Session(peer, trusted, address)
        if (peer.version >= 2) {
            runCatching { host.prepare(peer, h.optLong("total")) }
            Sessions.put(session)
            val lan = JSONArray(); host.lanEndpoints().forEach { lan.put(it.toJson()) }
            reply.put("token", session.token).put("key", Proto.b64(session.key)).put("lan", lan).put("canHost", host.canHostP2p())
        }
        Proto.writeFrame(out, reply)
        try {
            while (true) {
                val f = try { Proto.readFrame(inp) } catch (e: EOFException) { return } // client moved to Wi-Fi (or gave up)
                when (f.optString("t")) {
                    "p2p_req" -> try {
                        val info = host.startP2p(session)
                        session.usedP2p = true
                        Proto.writeFrame(out, JSONObject().put("t", "p2p").put("ssid", info.ssid).put("pass", info.pass)
                            .put("ip", info.ip).put("port", info.port))
                    } catch (e: Exception) {
                        Proto.writeFrame(out, JSONObject().put("t", "p2p_fail").put("reason", e.message ?: "Wi-Fi Direct failed"))
                    }
                    "offer" -> {
                        Sessions.drop(session)
                        try { transfer(session, f, inp, out, Route.BT) } finally { host.onSessionEnd(session) }
                        return
                    }
                    else -> throw IOException("unexpected frame ${f.optString("t")}")
                }
            }
        } catch (e: IOException) {
            // Control link dropped. If the session never attached anywhere, release resources.
            if (Sessions.take(session.token) != null) host.onSessionEnd(session)
            if (e !is EOFException) throw e
        }
    }

    /** TCP side (LAN or Wi-Fi Direct): attach to a session made over Bluetooth, then transfer encrypted. */
    fun runData(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        val rawIn = DataInputStream(socket.getInputStream())
        val rawOut = DataOutputStream(socket.getOutputStream())
        val a = Proto.expect(Proto.readFrame(rawIn), "attach")
        val session = Sessions.take(a.optString("token"))
        if (session == null) {
            Proto.writeFrame(rawOut, JSONObject().put("t", "error").put("reason", "unknown session")); return
        }
        try {
            Proto.writeFrame(rawOut, JSONObject().put("t", "attached"))
            socket.soTimeout = 120_000  // a trust prompt can take up to 60 s
            val inp = DataInputStream(CtrInputStream(socket.getInputStream(), Crypt.cipher(session.key, Crypt.IV_C2S)).buffered(Proto.CHUNK))
            val out = DataOutputStream(CtrOutputStream(socket.getOutputStream(), Crypt.cipher(session.key, Crypt.IV_S2C)).buffered(Proto.CHUNK))
            val offer = Proto.readFrame(inp)
            transfer(session, offer, inp, out, if (session.usedP2p) Route.P2P else Route.LAN)
        } finally {
            host.onSessionEnd(session)
        }
    }

    private fun transfer(session: Session, offer: JSONObject, inp: DataInputStream, out: DataOutputStream, route: Route) {
        val peer = session.peer
        Proto.expect(offer, "offer")
        val count = offer.optInt("count")
        val total = offer.optLong("total")
        if (!session.trusted && !host.isTrusted(peer.id)) {
            when (host.askTrust(peer, count, total)) {
                TrustDecision.DECLINE -> {
                    Proto.writeFrame(out, JSONObject().put("t", "reject").put("reason", "declined on ${host.me.name}"))
                    return
                }
                TrustDecision.ALWAYS -> host.rememberTrust(peer)
                TrustDecision.ONCE -> {}
            }
        }
        Proto.writeFrame(out, JSONObject().put("t", "accept"))
        host.onStart(peer, count, total, route)

        val saved = ArrayList<String>()
        val buf = ByteArray(Proto.CHUNK)
        var got = 0L
        while (true) {
            val f = Proto.readFrame(inp)
            when (f.optString("t")) {
                "done" -> {
                    Proto.writeFrame(out, JSONObject().put("t", "bye"))
                    host.onFinished(peer, saved, route)
                    return
                }
                "file" -> {
                    val path = f.getString("path")
                    val size = f.getLong("size")
                    var sink: IncomingSink? = null
                    var openError: String? = null
                    try { sink = host.openSink(path, size) } catch (e: Exception) { openError = e.message ?: "cannot write" }
                    val crc = CRC32()
                    var remaining = size
                    try {
                        while (remaining > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw EOFException("connection lost during $path")
                            sink?.stream?.write(buf, 0, n)
                            crc.update(buf, 0, n)
                            remaining -= n
                            got += n
                            host.onProgress(peer, got, total, path)
                        }
                        sink?.stream?.close()
                    } catch (e: Exception) {
                        sink?.abort(); throw e
                    }
                    val end = Proto.expect(Proto.readFrame(inp), "fend")
                    when {
                        sink == null -> Proto.writeFrame(out, JSONObject().put("t", "error").put("reason", openError))
                        end.getLong("crc32") != crc.value -> {
                            sink.abort()
                            Proto.writeFrame(out, JSONObject().put("t", "error").put("reason", "checksum mismatch on $path"))
                        }
                        else -> {
                            val where = sink.commit()
                            saved += where
                            Proto.writeFrame(out, JSONObject().put("t", "saved").put("as", where))
                        }
                    }
                }
                else -> throw IOException("unexpected frame ${f.optString("t")}")
            }
        }
    }
}
