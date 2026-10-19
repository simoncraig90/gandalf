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
    @Volatile private var gen = 0
    private var conversation: Conversation? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("walk", "Gandalf", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "walk").setContentTitle("Gandalf: listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            android.util.Log.e("Walk", "startForeground refused", e)
        }
        if (intent?.action == ACTION_CONVERSE) converse() else if (conversation == null) startWake()
        return START_STICKY
    }

    /** Open-source on-device wake word: sherpa-onnx KeywordSpotter over 16 kHz mono AudioRecord. */
    private fun startWake() {
        if (thread != null || !Prefs.wakeOn(this)) return
        listening = true
        val myGen = ++gen
        thread = Thread({
            var kws: KeywordSpotter? = null
            var stream: OnlineStream? = null
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
                var failures = 0
                while (gen == myGen) {
                    var rec: AudioRecord? = null
                    try {
                        val n = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(n, 6400))
                        rec.startRecording()
                        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("not recording")
                        val buf = ShortArray(1600)
                        while (gen == myGen) {
                            val got = rec.read(buf, 0, buf.size)
                            if (got < 0) throw IllegalStateException("read returned $got")
                            if (got == 0) continue
                            failures = 0
                            stream.acceptWaveform(FloatArray(got) { buf[it] / 32768f }, 16000)
                            while (kws.isReady(stream)) kws.decode(stream)
                            if (kws.getResult(stream).keyword.isNotEmpty()) {
                                kws.reset(stream)
                                gen++ // this thread is done; converse() tears it down
                                android.os.Handler(mainLooper).post { converse() }
                            }
                        }
                    } catch (e: Exception) {
                        failures++
                        android.util.Log.e("Walk", "mic failed (attempt $failures)", e)
                    } finally {
                        try { rec?.stop(); rec?.release() } catch (_: Exception) {}
                    }
                    if (gen == myGen) try { Thread.sleep(if (failures <= 10) 300 else 5000) } catch (_: InterruptedException) {}
                }
            } catch (e: Throwable) {
                android.util.Log.e("Walk", "wake word failed", e)
            } finally {
                try { stream?.release(); kws?.release() } catch (_: Throwable) {}
            }
        }, "wake").also { it.start() }
    }

    private fun stopWake() {
        listening = false
        gen++ // every running wake thread sees a stale generation and exits
        thread?.interrupt()
        thread = null
    }

    private fun converse() {
        if (conversation != null) return
        stopWake() // the wake-word recorder holds the mic
        val c = Conversation(this) {
            conversation = null
            if (Prefs.wakeOn(this)) startWake() else stopSelf()
        }
        conversation = c
        android.os.Handler(mainLooper).post { c.start() }
    }

    override fun onCreate() { super.onCreate(); instance = this }

    /** Called by the assistant session when it runs a conversation in-process: free the mic, then resume. */
    fun pauseSpotter() { stopWake() }
    fun resumeSpotter() { if (conversation == null) startWake() }

    override fun onDestroy() {
        instance = null
        stopWake()
        conversation?.finish()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONVERSE = "uk.simon.walk.CONVERSE"
        @Volatile var instance: WakeService? = null
    }
}
