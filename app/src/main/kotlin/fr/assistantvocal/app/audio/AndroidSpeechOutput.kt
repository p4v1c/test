package fr.assistantvocal.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Synthèse vocale Android en français. Le texte est découpé en phrases pour pouvoir être interrompu vite. */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val counter = AtomicInteger()
    @Volatile private var speaking = false

    @Volatile private var languageSet = false

    private val tts: TextToSpeech = FrenchVoice.create(context) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finish(utteranceId, false) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId, false) }
        })
    }

    private fun finish(id: String?, ok: Boolean) { id?.let { pending.remove(it)?.invoke(ok) } }

    private suspend fun ensureReady(): Boolean {
        if (!ready.await()) return false
        if (!languageSet) {
            val r = FrenchVoice.apply(tts)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Voix française absente ($r)")
            } else languageSet = true
            Log.i(TAG, "Voix : ${tts.defaultEngine} / ${runCatching { tts.voice?.name }.getOrNull()}")
        }
        return true
    }

    override suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return true
        if (!ensureReady()) return false
        val sentences = splitSentences(text)
        speaking = true
        try {
            for ((i, s) in sentences.withIndex()) {
                val id = "u${counter.incrementAndGet()}"
                val ok = suspendCancellableCoroutine { cont ->
                    pending[id] = { done -> if (cont.isActive) cont.resume(done) }
                    val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    if (tts.speak(s, mode, null, id) != TextToSpeech.SUCCESS) finish(id, false)
                    cont.invokeOnCancellation { pending.remove(id); tts.stop() }
                }
                if (!ok) return false
            }
            return true
        } finally {
            speaking = false
        }
    }

    override fun stop() {
        tts.stop()
        pending.keys.toList().forEach { finish(it, false) }
        speaking = false
    }

    override fun isSpeaking() = speaking

    override fun applySettings(rate: Float, pitch: Float) {
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
    }

    override fun release() { stop(); tts.shutdown() }

    companion object {
        private const val TAG = "SpeechOutput"

        /** Découpe en phrases (point, point d'exclamation ou d'interrogation suivis d'un espace). */
        fun splitSentences(text: String): List<String> =
            text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
