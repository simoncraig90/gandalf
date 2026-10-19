package uk.simon.walk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Minimal assistant registration: the assist gesture / earbud long-press starts the same conversation. */
class WalkInteractionService : VoiceInteractionService()

class WalkSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = WalkSession(this)
}

class WalkSession(private val c: Context) : VoiceInteractionSession(c) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        var started = false
        try {
            c.startForegroundService(Intent(c, WakeService::class.java).setAction(WakeService.ACTION_CONVERSE))
            started = true
        } catch (_: Exception) {}
        if (!started) {
            // FGS start refused (Android 14+) or service not running: converse in this process.
            WakeService.instance?.pauseSpotter()
            Conversation(c) { WakeService.instance?.resumeSpotter() }.start()
        }
        hide()
    }
}
