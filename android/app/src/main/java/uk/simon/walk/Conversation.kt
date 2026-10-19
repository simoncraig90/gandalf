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
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
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
        openSco { listen() }
    }

    /** Use the Bluetooth headset mic when one is connected; wait up to 2 s for SCO. */
    private fun openSco(then: () -> Unit) {
        val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } || audio.isBluetoothScoAvailableOffCall && audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
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
            val reply: String? = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10000
                c.readTimeout = 100000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.setRequestProperty("X-Token", token)
                c.outputStream.use { it.write(text.toByteArray()) }
                val code = c.responseCode
                if (code == 200) c.inputStream.bufferedReader().readText().trim()
                else { error = "HTTP $code"; null }
            } catch (e: Exception) { error = e.javaClass.simpleName; null }
            main.post {
                if (reply == null) fail("Network error: ${error ?: "unknown"}.")
                else speak(reply) { if (reply.startsWith("Bye", true)) finish() else listen() }
            }
        }
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
