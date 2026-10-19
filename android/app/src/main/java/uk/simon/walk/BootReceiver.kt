package uk.simon.walk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.wakeOn(context)) {
            try { context.startForegroundService(Intent(context, WakeService::class.java)) } catch (_: Exception) {}
        }
    }
}
