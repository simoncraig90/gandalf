package uk.simon.walk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*

/** Foreground (microphone) service: sherpa-onnx keyword spotting, and host of the conversation loop. */
class WakeService : Service() {
    @Volatile private var listening = false
    private var thread: Thread? = null
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

    /** Open-source on-device wake word: sherpa-onnx KeywordSpotter over 16 kHz mono AudioRecord. */
    private fun startWake() {
        if (thread != null || !Prefs.wakeOn(this)) return
        listening = true
        thread = Thread({
            var kws: KeywordSpotter? = null
            var stream: OnlineStream? = null
            var rec: AudioRecord? = null
            try {
                val d = "kws/"
                val cfg = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = d + "encoder.int8.onnx", decoder = d + "decoder.int8.onnx", joiner = d + "joiner.int8.onnx"),
                        tokens = d + "tokens.txt", numThreads = 1, modelType = "zipformer2"),
                    keywordsFile = d + "keywords.txt")
                kws = KeywordSpotter(assets, cfg)
                stream = kws.createStream("")
                val n = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(n, 6400))
                rec.startRecording()
                val buf = ShortArray(1600)
                while (listening) {
                    val got = rec.read(buf, 0, buf.size)
                    if (got <= 0) continue
                    stream.acceptWaveform(FloatArray(got) { buf[it] / 32768f }, 16000)
                    while (kws.isReady(stream)) kws.decode(stream)
                    if (kws.getResult(stream).keyword.isNotEmpty()) {
                        kws.reset(stream)
                        listening = false
                        android.os.Handler(mainLooper).post { converse() }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("Walk", "wake word failed", e)
            } finally {
                try { rec?.stop(); rec?.release() } catch (_: Exception) {}
                try { stream?.release(); kws?.release() } catch (_: Throwable) {}
            }
        }, "wake").also { it.start() }
    }

    private fun stopWake() {
        listening = false
        try { thread?.join(1500) } catch (_: Exception) {}
        thread = null
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
