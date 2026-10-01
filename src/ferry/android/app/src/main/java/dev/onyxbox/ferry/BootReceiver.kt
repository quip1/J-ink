package dev.onyxbox.ferry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (p.startOnBoot && p.receiving && Perms.bluetooth(ctx)) runCatching { FerryService.start(ctx) }
    }
}
