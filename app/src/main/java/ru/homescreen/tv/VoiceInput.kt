package ru.homescreen.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Voice search without leaving the launcher: Google TV's recognizer (katniss) as a service, so nothing covers the
 * screen, the remote's microphone opens by itself and the words show in the search line as they are heard. It is
 * asked by name: the TV's default recognizer is Google's speech synthesis app, which does nothing on a TV.
 * Needs RECORD_AUDIO (scripts/install.sh grants it). Main thread only.
 */
class VoiceInput(private val context: Context) {
    /** Listening now: the search line shows the jumping dots. */
    var listening by mutableStateOf(false)
        private set
    /** How loud it is, 0..1, for the dots. */
    var level by mutableFloatStateOf(0f)
        private set
    private var recognizer: SpeechRecognizer? = null

    /** Listens in [language]: [heard] gets the words so far, [done] the final ones, or null and why not. */
    fun start(language: String, heard: (String) -> Unit, done: (String?, String?) -> Unit) {
        stop()
        val r = katniss()?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            ?: if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
        if (r == null) {
            done(null, "Голосовой ввод недоступен")
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            // Roughly -2 dB in silence to 10 dB in loud speech.
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                android.util.Log.i("homescreen", "voice: error $error")
                release()
                done(null, message(error))
            }
            override fun onResults(results: Bundle?) {
                release()
                done(first(results), null)
            }
            override fun onPartialResults(partialResults: Bundle?) { first(partialResults)?.let(heard) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listening = true
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Done speaking: the final words come through [start]'s callback. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Stops without a result. */
    fun stop() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
    }

    private fun katniss(): ComponentName? =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }.firstOrNull { it.packageName == KATNISS }?.let { ComponentName(it.packageName, it.name) }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал, попробуйте ещё раз"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
        SpeechRecognizer.ERROR_AUDIO -> "Микрофон пульта не ответил"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Нет связи с распознаванием речи"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, попробуйте ещё раз"
        else -> "Голосовой ввод не сработал"
    }

    private companion object {
        const val KATNISS = "com.google.android.katniss"
    }
}
