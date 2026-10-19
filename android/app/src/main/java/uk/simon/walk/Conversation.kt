package uk.simon.walk

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.MediaPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One spoken conversation: beep, (SCO), listen, POST /say, speak reply, repeat.
 * All methods must be called on the main thread. [onFinished] runs once at the end.
 */
class Conversation(private val ctx: Context, private val onFinished: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val net = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var scoOn = false
    private var misses = 0
    private var finished = false
    private var pendingAfterTts: (() -> Unit)? = null

    fun start() {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { val f = pendingAfterTts; pendingAfterTts = null; f?.invoke() } }
                    @Deprecated("old API") override fun onError(id: String?) { onDone(id) }
                })
            }
        }
        openSco {
            // SCO first, then the beep, then ~150 ms of quiet before the recogniser opens the mic.
            try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
            main.postDelayed({ listen() }, 300)
        }
    }

    /** Use the Bluetooth headset mic when one is connected; wait up to 2 s for SCO. */
    private fun openSco(then: () -> Unit) {
        val btTypes = setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        var headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in btTypes }
        if (!headset) headset = try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) ==
                android.bluetooth.BluetoothProfile.STATE_CONNECTED
        } catch (_: SecurityException) { false }
        if (!headset) { then(); return }
        var done = false
        val go = { if (!done) { done = true; main.post(then) } }
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    go()
                }
            }
        }
        ctx.registerReceiver(rx, android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        @Suppress("DEPRECATION") run {
            audio.startBluetoothSco()
            audio.isBluetoothScoOn = true
        }
        scoOn = true
        main.postDelayed({ try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}; go() }, 2000)
    }

    private fun listen() {
        if (finished) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { fail("Speech recognition is not available."); return }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) noMatch() else { misses = 0; send(text) }
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) noMatch()
                else fail("Speech recognition error $error.")
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        })
    }

    private fun noMatch() {
        misses++
        if (misses >= 2) finish() else listen()
    }

    private fun send(text: String) {
        val url = Prefs.serverUrl(ctx) + "/say"
        val token = Prefs.token(ctx)
        net.execute {
            error = null
            var reply: String? = null
            var wav: File? = null
            var end = false
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10000
                c.readTimeout = 100000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.setRequestProperty("Accept", "audio/wav")
                c.setRequestProperty("X-Token", token)
                c.outputStream.use { it.write(text.toByteArray()) }
                val code = c.responseCode
                if (code != 200) error = "HTTP $code"
                else {
                    end = c.getHeaderField("X-End") == "1"
                    val type = c.contentType.orEmpty().lowercase()
                    if (type.startsWith("audio/")) {
                        val f = File(ctx.cacheDir, "reply.wav")
                        c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        wav = f
                    } else reply = c.inputStream.bufferedReader().readText().trim()
                }
            } catch (e: Exception) { error = "net" }
            val t = reply; val w = wav
            main.post {
                when {
                    w != null -> play(w) { if (end) finish() else listen() }
                    t != null -> speak(t) { if (end || t.startsWith("Bye", true)) finish() else listen() }
                    else -> fail("Network error: ${error ?: "unknown"}.")
                }
            }
        }
    }

    /** Play a WAV reply (Gandalf voice) on the MUSIC stream; [after] runs when it completes or fails. */
    private fun play(f: File, after: () -> Unit) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { it.release(); after() }
            mp.setOnErrorListener { p, _, _ -> p.release(); after(); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) { after() }
    }

    @Volatile private var error: String? = null

    private fun fail(msg: String) = speak(msg) { finish() }

    private fun speak(text: String, after: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { // TTS may still be initialising; retry briefly
            main.postDelayed({ if (ttsReady) speak(text, after) else after() }, 500); return
        }
        pendingAfterTts = after
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }, "walk")
    }

    fun finish() {
        if (finished) return
        finished = true
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        if (scoOn) @Suppress("DEPRECATION") run {
            audio.isBluetoothScoOn = false
            audio.stopBluetoothSco()
        }
        net.shutdown()
        onFinished()
    }
}
