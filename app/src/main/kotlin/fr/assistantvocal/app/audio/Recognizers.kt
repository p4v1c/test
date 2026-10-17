package fr.assistantvocal.app.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Choisit le service de reconnaissance vocale à utiliser, en excluant celui de notre application.
 * Quand l'app devient « assistant par défaut », Android peut faire de son service de reconnaissance
 * la reconnaissance par défaut du téléphone : sans ce choix explicite, l'app s'appellerait elle-même.
 */
object RecognizerChooser {
    private val PREFERRED = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.tts",
        "com.google.android.as",
    )

    fun find(context: Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return PREFERRED.firstNotNullOfOrNull { p -> services.firstOrNull { it.packageName == p } } ?: services.firstOrNull()
    }
}

/**
 * Service de reconnaissance exigé par Android pour qu'une app puisse être « assistant par défaut ».
 * Il ne reconnaît rien lui-même : il relaie vers la reconnaissance Google, pour que la dictée vocale
 * des autres applications continue de fonctionner normalement.
 */
class ProxyRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        delegate?.destroy()
        val target = RecognizerChooser.find(this)
        if (target == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); return }
        val rec = SpeechRecognizer.createSpeechRecognizer(this, target)
        delegate = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = runCatching { listener.readyForSpeech(params ?: Bundle()) }.let {}
            override fun onBeginningOfSpeech() = runCatching { listener.beginningOfSpeech() }.let {}
            override fun onRmsChanged(rmsdB: Float) = runCatching { listener.rmsChanged(rmsdB) }.let {}
            override fun onBufferReceived(buffer: ByteArray?) = runCatching { listener.bufferReceived(buffer ?: ByteArray(0)) }.let {}
            override fun onEndOfSpeech() = runCatching { listener.endOfSpeech() }.let {}
            override fun onError(error: Int) = runCatching { listener.error(error) }.let {}
            override fun onResults(results: Bundle?) = runCatching { listener.results(results ?: Bundle()) }.let {}
            override fun onPartialResults(partialResults: Bundle?) = runCatching { listener.partialResults(partialResults ?: Bundle()) }.let {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) { delegate?.stopListening() }
    override fun onCancel(listener: Callback) { delegate?.cancel() }
    override fun onDestroy() { delegate?.destroy(); delegate = null; super.onDestroy() }
}
