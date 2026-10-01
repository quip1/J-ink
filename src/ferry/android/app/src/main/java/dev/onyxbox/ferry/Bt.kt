package dev.onyxbox.ferry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import dev.onyxbox.ferry.core.FerryClient
import dev.onyxbox.ferry.core.PeerHello
import dev.onyxbox.ferry.core.Proto
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("MissingPermission") // callers check Perms.bluetooth() first
object Bt {
    fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Paired devices that could plausibly run Ferry (skips headphones, mice, watches...). */
    fun candidates(ctx: Context): List<BluetoothDevice> {
        val a = adapter(ctx) ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        val skip = setOf(BluetoothClass.Device.Major.AUDIO_VIDEO, BluetoothClass.Device.Major.PERIPHERAL,
            BluetoothClass.Device.Major.IMAGING, BluetoothClass.Device.Major.WEARABLE,
            BluetoothClass.Device.Major.TOY, BluetoothClass.Device.Major.HEALTH)
        return a.bondedDevices.orEmpty().filter { d ->
            d.type != BluetoothDevice.DEVICE_TYPE_LE &&
                (d.bluetoothClass?.majorDeviceClass ?: BluetoothClass.Device.Major.UNCATEGORIZED) !in skip
        }.sortedBy { it.name ?: it.address }
    }

    fun name(d: BluetoothDevice): String = d.name ?: d.address

    /** True if the last SDP result the OS cached for this device lists Ferry. Instant, no radio. */
    fun advertisesFerry(d: BluetoothDevice): Boolean =
        d.uuids.orEmpty().any { it.uuid == Proto.SERVICE_UUID }

    /** RFCOMM connect with a hard timeout (the platform call can hang ~12 s on an absent device). */
    fun connect(ctx: Context, d: BluetoothDevice, timeoutMs: Long = 9000): BluetoothSocket {
        adapter(ctx)?.cancelDiscovery()
        val sock = d.createRfcommSocketToServiceRecord(Proto.SERVICE_UUID)
        val err = AtomicReference<Throwable?>(null)
        val t = Thread {
            try { sock.connect() } catch (e: Throwable) { err.set(e) }
        }.apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) {
            runCatching { sock.close() }
            throw IOException("${name(d)} didn't answer (is Ferry running on it and Bluetooth on?)")
        }
        err.get()?.let {
            runCatching { sock.close() }
            throw IOException("${name(d)}: ${friendly(it)}", it)
        }
        return sock
    }

    fun probe(ctx: Context, d: BluetoothDevice, prefs: Prefs): PeerHello {
        connect(ctx, d, 7000).use { s ->
            val h = FerryClient(prefs.identity, s.inputStream, s.outputStream).hello("probe")
            prefs.remember(d.address, h.id, h.name, h.os)
            return h
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("discovery failed", true) || m.contains("service", true) -> "Ferry isn't running there"
            m.contains("timeout", true) -> "no answer"
            m.contains("refused", true) -> "connection refused"
            else -> m.ifEmpty { e.javaClass.simpleName }
        }
    }
}
