package fr.assistantvocal.app.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reconnaissance vocale Android (Google sur les Pixel), en français.
 * Hors ligne, utilise la reconnaissance sur l'appareil quand elle est disponible (Android 12+).
 * Doit être appelée depuis le thread principal.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(preferOffline: Boolean): Heard {
        // isRecognitionAvailable() compte aussi notre propre relais (ProxyRecognitionService) : il ne suffit pas.
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (RecognizerChooser.find(context) == null && !onDevice) {
            return Heard.Failed("La reconnaissance vocale n'est pas disponible sur ce téléphone. Il faut installer l'application Google.")
        }
        val first = withTimeoutOrNull(25_000) { listenOnce(preferOffline) } ?: Heard.Nothing.also { cancel() }
        // Erreur réseau : on retente une fois hors ligne.
        if (first is Heard.Failed && first.speech == NETWORK && !preferOffline) {
            return withTimeoutOrNull(25_000) { listenOnce(true) } ?: Heard.Nothing.also { cancel() }
        }
        return if (first is Heard.Failed && first.speech == NETWORK) {
            Heard.Failed("Je n'arrive pas à comprendre sans internet. Vérifiez la connexion ou réessayez.")
        } else first
    }

    private suspend fun listenOnce(offline: Boolean): Heard = suspendCancellableCoroutine { cont ->
        cancel()
        val target = RecognizerChooser.find(context)
        val onDevice = (offline || target == null) && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val rec = when {
            onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> target?.let { SpeechRecognizer.createSpeechRecognizer(context, it) } ?: SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                if (cont.isActive) cont.resume(if (list.isEmpty()) Heard.Nothing else Heard.Text(list.first(), list.drop(1)))
            }

            override fun onError(error: Int) {
                Log.i(TAG, "Erreur de reconnaissance $error")
                val heard = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Nothing
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Heard.Failed(NETWORK)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Heard.Failed("Je n'ai pas l'autorisation d'utiliser le micro.")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        Heard.Failed("Le français n'est pas installé pour la reconnaissance vocale. Demandez à un proche de l'installer.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Heard.Failed("Le micro est occupé. Réessayez dans un instant.")
                    else -> Heard.Failed("Je n'ai pas réussi à écouter. Réessayez.")
                }
                if (cont.isActive) cont.resume(heard)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent(offline))
        cont.invokeOnCancellation { cancel() }
    }

    private fun intent(offline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Laisser le temps de parler lentement.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    override fun cancel() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    override fun release() = cancel()

    private companion object {
        const val TAG = "SpeechInput"
        const val NETWORK = "__network__"
    }
}
