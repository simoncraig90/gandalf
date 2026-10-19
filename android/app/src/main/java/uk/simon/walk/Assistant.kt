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
        try {
            c.startForegroundService(Intent(c, WakeService::class.java).setAction(WakeService.ACTION_CONVERSE))
        } catch (_: Exception) {}
        hide()
    }
}
