package uk.simon.walk

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Android only lists an assistant app that also declares a RecognitionService. Gandalf does its own
 *  listening, so this one just declines. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
