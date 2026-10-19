package uk.simon.walk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import java.io.File

/** Foreground (microphone) service: Porcupine wake word, and host of the conversation loop. */
class WakeService : Service() {
    private var porcupine: PorcupineManager? = null
    private var conversation: Conversation? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("walk", "Walk", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "walk").setContentTitle("Walk: listening for hey Claude")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (intent?.action == ACTION_CONVERSE) converse() else if (conversation == null) startWake()
        return START_STICKY
    }

    private fun startWake() {
        if (porcupine != null || !Prefs.wakeOn(this)) return
        val key = Prefs.accessKey(this)
        if (key.isEmpty()) return
        try {
            val b = PorcupineManager.Builder().setAccessKey(key)
            if (assets.list("")?.contains("hey_claude_android.ppn") == true) {
                val f = File(filesDir, "hey_claude_android.ppn")
                assets.open("hey_claude_android.ppn").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                b.setKeywordPath(f.absolutePath)
            } else {
                b.setKeyword(Porcupine.BuiltInKeyword.COMPUTER)
            }
            porcupine = b.build(this) { converse() }
            porcupine?.start()
        } catch (e: Exception) {
            porcupine = null
        }
    }

    private fun stopWake() {
        try { porcupine?.stop(); porcupine?.delete() } catch (_: Exception) {}
        porcupine = null
    }

    private fun converse() {
        if (conversation != null) return
        stopWake() // Porcupine holds the mic
        val c = Conversation(this) {
            conversation = null
            if (Prefs.wakeOn(this)) startWake() else stopSelf()
        }
        conversation = c
        android.os.Handler(mainLooper).post { c.start() }
    }

    override fun onDestroy() {
        stopWake()
        conversation?.finish()
        super.onDestroy()
    }

    companion object { const val ACTION_CONVERSE = "uk.simon.walk.CONVERSE" }
}
