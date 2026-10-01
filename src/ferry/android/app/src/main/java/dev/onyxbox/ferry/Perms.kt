package dev.onyxbox.ferry

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

object Perms {
    // Targeting API 28 means Bluetooth uses the old install-time permissions: nothing to ask for.
    val bluetoothPerms: Array<String> = arrayOf()
    fun bluetooth(ctx: Context) = true

    /** Wi-Fi Direct needs fine location for apps targeting < 33. */
    fun location(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun storageWrite(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Can we write anywhere on shared storage (e.g. /Books)? Legacy storage or All-files access both work. */
    fun allFiles(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) ||
            (storageWrite(ctx) && (Build.VERSION.SDK_INT < 29 || Environment.isExternalStorageLegacy()))

    fun batteryUnrestricted(ctx: Context) =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun openAllFilesSettings(a: Activity) {
        if (Build.VERSION.SDK_INT < 30) return
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}"))
        runCatching { a.startActivity(i) }.onFailure { a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun openBatterySettings(a: Activity) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
        runCatching { a.startActivity(i) }.onFailure { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/** Tiny main-thread event bus between the service and whatever screen is open. */
object Bus {
    sealed class Ev {
        data class Status(val text: String) : Ev()
        /** fraction < 0 means indeterminate; null text clears the activity line. */
        data class Progress(val text: String?, val fraction: Float) : Ev()
        data class Toast(val text: String) : Ev()
        object HistoryChanged : Ev()
        data class TrustAsk(val reqId: Int) : Ev()
        data class PeerResult(val address: String, val ok: Boolean, val label: String) : Ev()
    }

    private val main = Handler(Looper.getMainLooper())
    private val subs = LinkedHashSet<(Ev) -> Unit>()
    @Volatile var lastStatus: String = ""
    @Volatile var lastProgress: Ev.Progress = Ev.Progress(null, 0f)

    fun sub(f: (Ev) -> Unit) = main.post { subs += f }
    fun unsub(f: (Ev) -> Unit) = main.post { subs -= f }
    fun post(e: Ev) {
        if (e is Ev.Status) lastStatus = e.text
        if (e is Ev.Progress) lastProgress = e
        main.post { subs.toList().forEach { it(e) } }
    }
}
