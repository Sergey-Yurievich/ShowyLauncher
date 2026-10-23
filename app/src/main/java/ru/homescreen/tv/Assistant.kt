package ru.homescreen.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The launcher as the TV's assistant, so the remote's microphone button lands in its own search. That button is not a
 * key event: the remote reports it over Bluetooth to Google's remote service, which shows a session of whatever assistant
 * is set (normally Google's, katniss). scripts/install.sh makes this one the assistant role holder.
 */
class Assistant : VoiceInteractionService()

class AssistantSessions : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)

    /**
     * Opens the search listening, and is gone. The remote's own audio stream in [onShow]'s args is not read.
     * The home screen itself is brought up, not an assistant activity: those sit above every other window, so the apps
     * and Settings it opened afterwards stayed hidden under it.
     */
    private class Session(context: Context) : VoiceInteractionSession(context) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_VOICE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
}

/**
 * Android wants an assistant to name a recognizer of its own. Voice search asks Google TV's recognizer by name
 * (VoiceInput), so this one only says no.
 */
class AssistantRecognizer : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
