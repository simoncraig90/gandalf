package uk.simon.walk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android 14+ forbids starting a microphone foreground service from BOOT_COMPLETED, so post a
 * notification; tapping it opens MainActivity, which starts the service from a visible Activity.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Prefs.wakeOn(context)) return
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("walk_boot", "Gandalf start", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_LISTENING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(2, Notification.Builder(context, "walk_boot").setContentTitle("Gandalf: tap to start listening")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) { android.util.Log.e("Walk", "boot notification failed", e) }
    }
}
