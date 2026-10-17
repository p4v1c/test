package fr.assistantvocal.app.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import fr.assistantvocal.core.wake.CalibrationResult
import fr.assistantvocal.core.wake.DecoderFactory
import fr.assistantvocal.core.wake.NameCalibrator
import fr.assistantvocal.core.wake.SpeechDecoder
import fr.assistantvocal.core.wake.WakeConfig
import fr.assistantvocal.core.wake.WakeDetection
import fr.assistantvocal.core.wake.WakeWordDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.coroutines.resume

/** Écoute du nom de l'assistant, entièrement sur le téléphone. Rien n'est envoyé sur internet. */
interface WakeEngine {
    /** Charge le modèle (copié depuis l'APK au premier lancement). */
    suspend fun ensureModel(): Boolean
    val isReady: Boolean

    /** Démarre l'écoute. Renvoie false si impossible (modèle absent, nom inconnu du modèle, micro occupé). */
    fun start(config: WakeConfig, onDetected: (WakeDetection) -> Unit): Boolean
    fun stop()
    val isListening: Boolean

    /** Enregistre [ms] millisecondes (calibration). L'écoute doit être arrêtée. */
    suspend fun record(ms: Int): ShortArray
    suspend fun calibrate(name: String, recordings: List<ShortArray>): CalibrationResult

    /** Écoute [ms] millisecondes et compte les déclenchements (test des faux déclenchements). */
    suspend fun countDetections(config: WakeConfig, ms: Int): Int
    fun release() {}
}

/** Accès à la fonction Vosk « mot connu ? », absente de l'API Java. */
private interface VoskVocabLib : Library {
    fun vosk_model_find_word(model: Pointer, word: String): Int
}

class VoskDecoderFactory(private val model: Model) : DecoderFactory {
    private val vocab: VoskVocabLib? by lazy { runCatching { Native.load("vosk", VoskVocabLib::class.java) }.getOrNull() }

    override fun grammar(phrases: List<String>): SpeechDecoder = VoskDecoder(Recognizer(model, RATE.toFloat(), grammarJson(phrases)))
    override fun free(): SpeechDecoder = VoskDecoder(Recognizer(model, RATE.toFloat()))
    override fun inVocabulary(word: String): Boolean =
        vocab?.let { runCatching { it.vosk_model_find_word(model.pointer, word.lowercase()) >= 0 }.getOrDefault(true) } ?: true

    companion object {
        /** JSON écrit à la main : les accents doivent rester en UTF-8 (Vosk ignore les « é »). */
        fun grammarJson(phrases: List<String>): String =
            phrases.joinToString(",", "[", "]") { "\"" + it.replace("\"", "").replace("\\", "") + "\"" }
    }
}

private class VoskDecoder(private val r: Recognizer) : SpeechDecoder {
    init { r.setWords(true) }
    override fun acceptWaveform(pcm: ShortArray, length: Int) = r.acceptWaveForm(pcm, length)
    override fun result(): String = r.result
    override fun partialResult(): String = r.partialResult
    override fun finalResult(): String = r.finalResult
    override fun reset() = r.reset()
    override fun close() = r.close()
}

private const val RATE = 16_000
private const val FRAME = 1_600 // 100 ms

class VoskWakeEngine(private val context: Context) : WakeEngine {
    @Volatile private var model: Model? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    override val isReady get() = model != null
    override val isListening get() = running

    override suspend fun ensureModel(): Boolean {
        if (model != null) return true
        val m = suspendCancellableCoroutine<Model?> { cont ->
            StorageService.unpack(context, "model-fr", "model",
                { m -> if (cont.isActive) cont.resume(m) },
                { e -> Log.e(TAG, "Modèle Vosk introuvable", e); if (cont.isActive) cont.resume(null) })
        }
        model = m
        return m != null
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4))
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        return rec
    }

    override fun start(config: WakeConfig, onDetected: (WakeDetection) -> Unit): Boolean {
        val m = model ?: return false
        stop()
        val detector = WakeWordDetector(VoskDecoderFactory(m), config).apply { log = { Log.d(TAG, it) } }
        if (!detector.isUsable) { detector.close(); Log.w(TAG, "Nom inconnu du modèle : calibration nécessaire"); return false }
        val rec = openMic() ?: run { detector.close(); return false }
        running = true
        thread = Thread({
            val buf = ShortArray(FRAME)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, FRAME)
                    if (n <= 0) continue
                    val hit = detector.process(buf, n)
                    if (hit != null && running) main.post { onDetected(hit) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Écoute interrompue", e)
            } finally {
                runCatching { rec.stop() }; rec.release(); detector.close()
            }
        }, "wake-word").apply { priority = Thread.NORM_PRIORITY; start() }
        return true
    }

    override fun stop() {
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
    }

    override suspend fun record(ms: Int): ShortArray = withContext(Dispatchers.IO) {
        val rec = openMic() ?: return@withContext ShortArray(0)
        val out = ShortArray(RATE * ms / 1000)
        try {
            rec.startRecording()
            var off = 0
            while (off < out.size) {
                val n = rec.read(out, off, minOf(FRAME, out.size - off))
                if (n <= 0) break
                off += n
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        out
    }

    override suspend fun calibrate(name: String, recordings: List<ShortArray>): CalibrationResult = withContext(Dispatchers.Default) {
        NameCalibrator(VoskDecoderFactory(model!!)).analyze(name, recordings)
    }

    override suspend fun countDetections(config: WakeConfig, ms: Int): Int = withContext(Dispatchers.IO) {
        val m = model ?: return@withContext 0
        val rec = openMic() ?: return@withContext 0
        var count = 0
        WakeWordDetector(VoskDecoderFactory(m), config).use { d ->
            val buf = ShortArray(FRAME)
            try {
                rec.startRecording()
                var total = 0
                while (total < RATE * ms / 1000) {
                    val n = rec.read(buf, 0, FRAME)
                    if (n <= 0) break
                    total += n
                    if (d.process(buf, n) != null) count++
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }
        count
    }

    override fun release() { stop(); model?.close(); model = null }

    private companion object { const val TAG = "WakeEngine" }
}
