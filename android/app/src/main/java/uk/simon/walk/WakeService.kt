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

/** Foreground (microphone) service: openWakeWord (ONNX Runtime) wake-word spotting, and host of the conversation loop. */
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

    /** Open-source on-device wake word: openWakeWord "hey gandalf" over 16 kHz mono AudioRecord. */
    private fun startWake() {
        if (thread != null || !Prefs.wakeOn(this)) return
        listening = true
        val myGen = ++gen
        thread = Thread({
            var det: OwwDetector? = null
            try {
                det = OwwDetector(this)
                selfTest(det)
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
                        var reads = 0; var peak = 0.0; var active = 0; var maxScore = 0f; var hits = 0
                        var floor = 100.0; var quiet = HANG + 1
                        val preroll = ArrayDeque<FloatArray>()
                        while (gen == myGen) {
                            val got = rec.read(buf, 0, buf.size)
                            if (got < 0) throw IllegalStateException("read returned $got")
                            if (got == 0) continue
                            failures = 0
                            // diagnostics: loudest 100 ms block every 5 s, and whether Android is silencing us
                            var sum = 0.0; for (i in 0 until got) sum += buf[i].toDouble() * buf[i]
                            val rms = Math.sqrt(sum / got)
                            peak = maxOf(peak, rms)
                            clips.feed(buf, got, rms)
                            if (++reads % 50 == 0) {
                                val silenced = if (android.os.Build.VERSION.SDK_INT >= 29)
                                    rec.activeRecordingConfiguration?.isClientSilenced else null
                                android.util.Log.i("Walk", "wake level peak=${peak.toInt()} floor=${floor.toInt()} active=$active/50 score=${"%.2f".format(maxScore)} silenced=$silenced")
                                peak = 0.0; active = 0; maxScore = 0f
                            }
                            // Silence skip (battery): the model only runs while there is sound above the room's
                            // noise floor, plus 0.5 s of pre-roll so the start of "hey" is not lost.
                            val block = FloatArray(got) { buf[it].toFloat() }
                            val loud = rms > maxOf(floor * 2.5, 150.0)
                            if (!loud && quiet >= 0) floor = floor * 0.98 + rms * 0.02
                            if (loud) {
                                if (quiet >= HANG) for (b in preroll) det.feed(b, 0, b.size)
                                preroll.clear(); quiet = 0
                            } else if (quiet < HANG) quiet++
                            else {
                                if (quiet == HANG) { det.reset(); hits = 0; quiet++ }
                                preroll.addLast(block); if (preroll.size > 5) preroll.removeFirst()
                                continue
                            }
                            active++
                            val sc = det.feed(block, 0, got)
                            if (sc >= 0f) {
                                maxScore = maxOf(maxScore, sc)
                                hits = if (sc >= OwwDetector.THRESHOLD) hits + 1 else 0
                            }
                            if (hits >= OwwDetector.PATIENCE) {
                                android.util.Log.i("Walk", "wake word score=${"%.2f".format(sc)}")
                                det.reset(); hits = 0
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
                det?.close()
            }
        }, "wake").also { it.start() }
    }

    /** DEBUG: if files/selftest.wav (16 kHz mono 16-bit) exists, runs it through the detector with the live 100 ms
     *  framing and logs the per-chunk scores, so the on-device pipeline can be checked against the Mac. */
    private fun selfTest(det: OwwDetector) {
        val f = java.io.File(filesDir, "selftest.wav"); if (!f.exists()) return
        try {
            val b = f.readBytes(); val n = (b.size - 44) / 2
            val x = FloatArray(n) { ((b[44 + 2 * it].toInt() and 255) or (b[45 + 2 * it].toInt() shl 8)).toShort().toFloat() }
            det.reset(); val sb = StringBuilder(); var top = 0f
            var i = 0; while (i < n) { val k = minOf(1600, n - i); val s = det.feed(x, i, k); if (s >= 0f) { sb.append("%.2f ".format(s)); top = maxOf(top, s) }; i += k }
            android.util.Log.i("Walk", "selftest top=${"%.3f".format(top)} scores: $sb")
            det.reset()
        } catch (e: Exception) { android.util.Log.e("Walk", "selftest failed", e) }
    }

    /** DEBUG (20 Oct, wake-word tuning): keeps the last 4 s of wake audio; 2 s after speech starts, saves it to
     *  files/clips/clip_NN.wav (max 40). Pull with adb run-as. Remove once the thresholds are tuned. */
    private val clips = ClipSaver()
    private inner class ClipSaver {
        val ring = ShortArray(16000 * 4); var pos = 0; var countdown = -1; var quietUntil = 0L; var n = 0
        fun feed(b: ShortArray, got: Int, rms: Double) {
            for (i in 0 until got) { ring[pos] = b[i]; pos = (pos + 1) % ring.size }
            val now = System.currentTimeMillis()
            if (countdown < 0 && rms > 800 && now > quietUntil) countdown = 20
            if (countdown > 0 && --countdown == 0) { countdown = -1; quietUntil = now + 2000; save() }
        }
        fun save() {
            if (n >= 40) return
            val dir = java.io.File(filesDir, "clips").apply { mkdirs() }
            val data = ShortArray(ring.size) { ring[(pos + it) % ring.size] }
            val f = java.io.File(dir, "clip_%02d.wav".format(n++))
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(f))).use { o ->
                fun i32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
                fun i16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
                o.writeBytes("RIFF"); i32(36 + data.size * 2); o.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1)
                i32(16000); i32(32000); i16(2); i16(16); o.writeBytes("data"); i32(data.size * 2)
                for (v in data) i16(v.toInt())
            }
            android.util.Log.i("Walk", "saved ${f.name}")
        }
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
        const val HANG = 15 // 1.5 s of quiet before the model stops
        @Volatile var instance: WakeService? = null
    }
}
