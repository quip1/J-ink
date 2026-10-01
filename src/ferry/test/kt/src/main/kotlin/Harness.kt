import dev.onyxbox.ferry.core.*
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket

// server <ctlPort> <dataPort> <root> <policy> <sessions> ; client <ctlPort> <mode:probe|bt|lan|badlan> <base> files...
fun main(a: Array<String>) {
    val me = Identity("kt-id", "Kotlin Boox", "android")
    when (a[0]) {
        "server" -> {
            val root = File(a[3]); val policy = a[4]; val dataPort = a[2].toInt()
            val trusted = HashSet<String>()
            val host = object : ReceiveHost {
                override val me = me
                override fun isTrusted(peerId: String) = peerId in trusted
                override fun askTrust(peer: PeerHello, count: Int, total: Long) = if (policy == "decline") TrustDecision.DECLINE else TrustDecision.ALWAYS
                override fun rememberTrust(peer: PeerHello) { trusted += peer.id }
                override fun lanEndpoints() = listOf(Endpoint("10.255.255.1", dataPort), Endpoint("127.0.0.1", dataPort))
                override fun openSink(path: String, size: Long): IncomingSink {
                    val dest = Placement.destination(root, path)
                    val tmp = File(dest.parentFile, dest.name + ".ferrypart"); val fos = FileOutputStream(tmp)
                    return object : IncomingSink {
                        override val stream = fos
                        override fun commit(): String { fos.close(); val f = Placement.unique(dest); tmp.renameTo(f); return f.relativeTo(root).path }
                        override fun abort() { fos.close(); tmp.delete() }
                    }
                }
                override fun onFinished(peer: PeerHello, saved: List<String>, route: Route) = println("KT-SERVER via $route from ${peer.name}: $saved")
            }
            val srv = FerryServer(host)
            val data = ServerSocket(dataPort)
            Thread { while (true) { val s = data.accept(); Thread { s.use { try { srv.runData(it) } catch (e: Exception) { println("KT data err $e") } } }.start() } }.apply { isDaemon = true; start() }
            ServerSocket(a[1].toInt()).use { ss -> repeat(a[5].toInt()) { ss.accept().use { s ->
                try { srv.runControl(s.getInputStream(), s.getOutputStream()) } catch (e: Exception) { println("KT ctl err $e") } } } }
            Thread.sleep(1500)
        }
        "client" -> Socket("127.0.0.1", a[1].toInt()).use { s ->
            val mode = a[2]
            val c = FerryClient(me, s.getInputStream(), s.getOutputStream())
            val h = c.hello(if (mode == "probe") "probe" else "send")
            println("KT-CLIENT hello ${h.name} v${h.version} lan=${h.lan.size} token=${h.token != null}")
            if (mode == "probe") return
            if (mode == "lan") println("KT-CLIENT attach=" + c.tryAttach(h.lan, Route.LAN))
            if (mode == "badlan") println("KT-CLIENT attach(bad)=" + c.tryAttach(listOf(Endpoint("127.0.0.1", 1)), Route.LAN))
            val base = File(a[3])
            val files = a.drop(4).map { p -> val f = File(p); OutgoingFile(f.relativeTo(base).path, f.length()) { f.inputStream() } }
            val t0 = System.nanoTime()
            try { val r = c.send(files); val dt = (System.nanoTime() - t0) / 1e9
                println("KT-CLIENT via ${c.route} saved $r  %.1f MB/s".format(files.sumOf { it.size } / 1048576.0 / dt))
            } catch (e: RemoteRefusal) { println("KT-CLIENT refused: ${e.message}") }
            c.close()
        }
    }
}
