package dev.onyxbox.ferry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import dev.onyxbox.ferry.core.Endpoint
import dev.onyxbox.ferry.core.P2pInfo
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Radios and fast links.
 *
 * Ferry targets API 28 on purpose: apps targeting 28 may still switch Wi-Fi and Bluetooth on
 * themselves (Android 10+ only blocks that for apps targeting 29/33+), which is what lets a transfer
 * wake the radios it needs without asking.
 */
@SuppressLint("MissingPermission")
class Wireless(private val ctx: Context) {
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val p2p: WifiP2pManager? = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? by lazy { p2p?.initialize(ctx, Looper.getMainLooper(), null) }

    // ------------------------------------------------------------------ radios

    val wifiOn get() = wifi.isWifiEnabled

    /** Turn Wi-Fi on if it's off and wait until it's usable. Returns whether Wi-Fi is on. */
    @Suppress("DEPRECATION")
    fun ensureWifi(timeoutMs: Long = 6000): Boolean {
        if (wifi.isWifiEnabled) return true
        runCatching { wifi.setWifiEnabled(true) }
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            if (wifi.isWifiEnabled) { SystemClock.sleep(1500); return true } // give the driver a moment
            SystemClock.sleep(200)
        }
        return wifi.isWifiEnabled
    }

    // Wi-Fi we switched on ourselves goes back off when the last transfer using it is done.
    private val wifiUsers = HashSet<Any>()
    @Volatile private var weEnabledWifi = false

    fun ensureWifiFor(user: Any): Boolean {
        synchronized(wifiUsers) { wifiUsers += user }
        if (wifi.isWifiEnabled) return true
        val ok = ensureWifi()
        if (ok) weEnabledWifi = true
        return ok
    }

    @Suppress("DEPRECATION")
    fun releaseWifiFor(user: Any) {
        val last = synchronized(wifiUsers) { wifiUsers.remove(user) && wifiUsers.isEmpty() }
        if (last && weEnabledWifi) {
            weEnabledWifi = false
            Thread { SystemClock.sleep(5000); synchronized(wifiUsers) { if (wifiUsers.isEmpty()) runCatching { wifi.setWifiEnabled(false) } } }.start()
        }
    }

    /** Turn Bluetooth on if it's off (allowed because we target API 28). */
    @Suppress("DEPRECATION")
    fun ensureBluetooth(timeoutMs: Long = 6000): Boolean {
        val a = Bt.adapter(ctx) ?: return false
        if (a.isEnabled) return true
        runCatching { a.enable() }
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            if (a.state == BluetoothAdapter.STATE_ON) return true
            SystemClock.sleep(200)
        }
        return a.isEnabled
    }

    // ------------------------------------------------------------------ LAN

    /** IPv4 addresses on Wi-Fi / Ethernet interfaces, excluding cellular and Wi-Fi Direct groups. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { n -> listOf("rmnet", "ccmni", "p2p", "dummy", "tun", "ifb").any { n.name.startsWith(it) } }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
            .map { it.hostAddress!! }
    }.getOrDefault(emptyList())

    fun lanEndpoints(port: Int) = lanAddresses().map { Endpoint(it, port) }

    // ------------------------------------------------------------------ Wi-Fi Direct

    val p2pSupported get() = p2p != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
        Build.VERSION.SDK_INT >= 29 && Perms.location(ctx)

    private val hostLock = Any()
    @Volatile private var hosted: P2pInfo? = null
    @Volatile private var hostUsers = 0

    /** Create (or reuse) a Wi-Fi Direct group that other devices — including PCs — can join like a normal hotspot. */
    fun hostGroup(dataPort: Int): P2pInfo = synchronized(hostLock) {
        hosted?.let { hostUsers++; return it }
        if (!p2pSupported) throw IOException(if (!Perms.location(ctx)) "needs Location permission for Wi-Fi Direct" else "Wi-Fi Direct not supported")
        if (!ensureWifi()) throw IOException("couldn't turn Wi-Fi on")
        val m = p2p!!; val ch = channel!!
        call { l -> m.removeGroup(ch, l) }                 // clear anything stale; failure is fine
        val ssid = "DIRECT-Fy-" + randomAlnum(6)
        val pass = randomAlnum(16)
        var lastErr = "unknown"
        var ok = false
        for (band in listOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)) {
            val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
                .enablePersistentMode(false).setGroupOperatingBand(band).build()
            val err = call { l -> m.createGroup(ch, cfg, l) }
            if (err == null) { ok = true; break } else lastErr = err
        }
        if (!ok) throw IOException("couldn't start Wi-Fi Direct ($lastErr)")
        // wait for the group to actually exist and get our address on it
        val until = SystemClock.uptimeMillis() + 10_000
        var group: WifiP2pGroup? = null
        while (SystemClock.uptimeMillis() < until) {
            group = groupInfo()
            if (group != null && group.isGroupOwner) break
            SystemClock.sleep(300)
        }
        if (group == null) { call { l -> m.removeGroup(ch, l) }; throw IOException("Wi-Fi Direct group didn't come up") }
        val ip = ifaceIpv4(group.`interface`) ?: "192.168.49.1"
        P2pInfo(group.networkName ?: ssid, group.passphrase ?: pass, ip, dataPort).also { hosted = it; hostUsers = 1 }
    }

    fun releaseGroup() {
        synchronized(hostLock) {
            if (hosted == null) return
            if (--hostUsers > 0) return
            hosted = null
        }
        val m = p2p ?: return; val ch = channel ?: return
        call { l -> m.removeGroup(ch, l) }
    }

    /** Join another Android device's group directly (no pairing prompt: we already have its passphrase). */
    fun joinGroup(info: P2pInfo, timeoutMs: Long = 20_000) {
        if (!p2pSupported) throw IOException("Wi-Fi Direct not available here")
        if (!ensureWifi()) throw IOException("couldn't turn Wi-Fi on")
        val m = p2p!!; val ch = channel!!
        val cfg = WifiP2pConfig.Builder().setNetworkName(info.ssid).setPassphrase(info.pass).enablePersistentMode(false).build()
        var err: String? = null
        for (attempt in 0 until 3) {
            err = call { l -> m.connect(ch, cfg, l) }
            if (err == null) break
            SystemClock.sleep(1500)   // BUSY right after the group appears is common
        }
        if (err != null) throw IOException("couldn't join Wi-Fi Direct ($err)")
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            val info2 = connInfo()
            if (info2?.groupFormed == true && info2.groupOwnerAddress != null) return
            SystemClock.sleep(400)
        }
        leaveGroup()
        throw IOException("Wi-Fi Direct join timed out")
    }

    fun leaveGroup() {
        val m = p2p ?: return; val ch = channel ?: return
        call { l -> m.removeGroup(ch, l) }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a WifiP2pManager action and waits for its listener. Returns null on success, else a reason. */
    private fun call(action: (WifiP2pManager.ActionListener) -> Unit): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>("timeout")
        action(object : WifiP2pManager.ActionListener {
            override fun onSuccess() { result.set(null); latch.countDown() }
            override fun onFailure(reason: Int) {
                result.set(when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
                    WifiP2pManager.BUSY -> "busy"
                    WifiP2pManager.ERROR -> "error"
                    else -> "code $reason"
                }); latch.countDown()
            }
        })
        latch.await(8, TimeUnit.SECONDS)
        return result.get()
    }

    private fun groupInfo(): WifiP2pGroup? {
        val m = p2p ?: return null; val ch = channel ?: return null
        val latch = CountDownLatch(1); val r = AtomicReference<WifiP2pGroup?>()
        m.requestGroupInfo(ch) { g -> r.set(g); latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        return r.get()
    }

    private fun connInfo(): WifiP2pInfo? {
        val m = p2p ?: return null; val ch = channel ?: return null
        val latch = CountDownLatch(1); val r = AtomicReference<WifiP2pInfo?>()
        m.requestConnectionInfo(ch) { i -> r.set(i); latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        return r.get()
    }

    private fun ifaceIpv4(name: String?): String? = name?.let {
        runCatching { NetworkInterface.getByName(it)?.inetAddresses?.toList()?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress }.getOrNull()
    }

    private fun randomAlnum(n: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        return (1..n).map { chars[dev.onyxbox.ferry.core.Proto.rng.nextInt(chars.length)] }.joinToString("")
    }
}
