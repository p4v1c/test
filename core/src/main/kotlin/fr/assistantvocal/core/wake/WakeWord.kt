package fr.assistantvocal.core.wake

import fr.assistantvocal.core.text.FrenchPhonetic
import fr.assistantvocal.core.text.FrenchText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.log10
import kotlin.math.sqrt

/** Décodeur de parole (Vosk sur Android, Vosk bureau dans les tests). Audio : PCM 16 bits, 16 kHz, mono. */
interface SpeechDecoder : AutoCloseable {
    /** Renvoie vrai quand un résultat final est disponible (fin d'énoncé). */
    fun acceptWaveform(pcm: ShortArray, length: Int): Boolean
    fun result(): String
    fun partialResult(): String
    fun finalResult(): String
    fun reset()
}

interface DecoderFactory {
    /** Décodeur limité à une liste de phrases (+ "[unk]"). Les mots hors vocabulaire sont ignorés par Vosk. */
    fun grammar(phrases: List<String>): SpeechDecoder
    /** Décodeur libre (tout le vocabulaire). */
    fun free(): SpeechDecoder
    fun inVocabulary(word: String): Boolean
}

data class RecognizedWord(val word: String, val conf: Double)

object VoskJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun words(result: String): List<RecognizedWord> {
        val obj = runCatching { json.parseToJsonElement(result).jsonObject }.getOrNull() ?: return emptyList()
        val arr = obj["result"] as? JsonArray
        if (arr != null) return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val w = o["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RecognizedWord(w, o["conf"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
        }
        val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: obj["partial"]?.jsonPrimitive?.contentOrNull ?: ""
        return text.split(' ').filter { it.isNotBlank() }.map { RecognizedWord(it, 1.0) }
    }
}

/** Réglages du mot d'activation, issus du nom et de la calibration. */
data class WakeConfig(
    val name: String,
    /** Transcriptions apprises pendant la calibration (« or fée »), utilisées si Vosk n'écrit pas le nom tel quel. */
    val aliases: List<String> = emptyList(),
    /** Confiance minimale de la première étape. */
    val threshold: Double = 0.7,
    /** Mode strict (après des faux déclenchements) : confiance élevée exigée en plus de la vérification. */
    val strict: Boolean = false,
)

/**
 * Détecteur de niveau sonore (VAD) : ne réveille le décodeur que quand quelqu'un parle.
 * Le bruit de fond est suivi en continu ; la parole doit dépasser ce bruit de 9 dB.
 */
class EnergyVad(private val marginDb: Double = 9.0, private val minDb: Double = 38.0, private val hangoverFrames: Int = 6) {
    var noiseFloorDb = 45.0
        private set
    private var hangover = 0

    fun levelDb(frame: ShortArray, length: Int = frame.size): Double {
        if (length == 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) { val v = frame[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / length)
        return if (rms < 1) 0.0 else 20 * log10(rms)
    }

    /** Vrai si la trame contient de la parole (ou suit de près de la parole). */
    fun isSpeech(frame: ShortArray, length: Int = frame.size): Boolean {
        val db = levelDb(frame, length)
        val loud = db > noiseFloorDb + marginDb && db > minDb
        // Le plancher suit vite le bruit qui baisse, lentement celui qui monte.
        noiseFloorDb = if (db < noiseFloorDb) noiseFloorDb * 0.7 + db * 0.3
        else if (!loud) noiseFloorDb * 0.98 + db * 0.02
        else noiseFloorDb * 0.999 + db * 0.001
        if (loud) { hangover = hangoverFrames; return true }
        if (hangover > 0) { hangover--; return true }
        return false
    }
}

/** Tampon circulaire des dernières secondes d'audio. */
class AudioRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var pos = 0
    private var filled = 0

    fun write(frame: ShortArray, length: Int = frame.size) {
        for (i in 0 until length) { data[pos] = frame[i]; pos = (pos + 1) % capacity }
        filled = minOf(capacity, filled + length)
    }

    /** Les [samples] derniers échantillons, dans l'ordre. */
    fun last(samples: Int): ShortArray {
        val n = minOf(samples, filled)
        val out = ShortArray(n)
        var start = (pos - n + capacity) % capacity
        for (i in 0 until n) { out[i] = data[start]; start = (start + 1) % capacity }
        return out
    }

    fun clear() { filled = 0 }
}

data class WakeDetection(val heard: String, val confidence: Double)

/**
 * Détection du nom en deux étapes :
 * 1. décodeur Vosk restreint au nom (rapide, ne s'exécute que pendant la parole) ;
 * 2. vérification : retranscription libre des dernières secondes ; le nom (ou une transcription apprise)
 *    doit y figurer. Cette étape élimine la plupart des faux déclenchements.
 */
class WakeWordDetector(
    private val factory: DecoderFactory,
    val config: WakeConfig,
    private val sampleRate: Int = 16_000,
) : AutoCloseable {
    private val vad = EnergyVad()
    private val ring = AudioRing(sampleRate * 4)
    private val grammarPhrases: List<String> = buildGrammar()
    private val grammar: SpeechDecoder? = grammarPhrases.takeIf { it.isNotEmpty() }?.let { factory.grammar(it + "[unk]") }
    private var inSegment = false
    private var segmentSamples = 0
    private var lastPartial = ""
    private var verificationsInSegment = 0
    /** Après une détection, on ignore la suite de la phrase jusqu'au prochain silence. */
    private var suppressUntilSilence = false

    /** Phrases de la grammaire : le nom s'il est connu de Vosk, plus les transcriptions apprises. */
    private fun buildGrammar(): List<String> {
        val out = mutableListOf<String>()
        val nameWords = config.name.lowercase().split(' ', '-').filter { it.isNotBlank() }
        if (nameWords.isNotEmpty() && nameWords.all { factory.inVocabulary(it) }) out += nameWords.joinToString(" ")
        config.aliases.map { it.lowercase().trim() }
            .filter { a -> a.isNotEmpty() && a.split(' ').all { factory.inVocabulary(it) } }
            .forEach { if (it !in out) out += it }
        return out
    }

    /** Vrai si le nom peut être détecté (le nom ou une transcription apprise est dans le vocabulaire). */
    val isUsable: Boolean get() = grammar != null

    /** Traite une trame (100 ms conseillés). Renvoie une détection, ou null. */
    fun process(frame: ShortArray, length: Int = frame.size): WakeDetection? {
        val g = grammar ?: return null
        ring.write(frame, length)
        val speech = vad.isSpeech(frame, length)
        if (suppressUntilSilence) { if (!speech) suppressUntilSilence = false; return null }
        if (!speech && !inSegment) return null
        if (speech && !inSegment) {
            inSegment = true; segmentSamples = 0; lastPartial = ""
            // pré-écoute : les 300 ms précédant le début de la parole
            val pre = ring.last(length + sampleRate * 3 / 10)
            g.acceptWaveform(pre, pre.size - length)
        }
        segmentSamples += length
        val ended = g.acceptWaveform(frame, length)
        if (ended) {
            val words = VoskJson.words(g.result())
            g.reset()
            return detected(checkFinal(words)).also { if (it != null) endSegment() }
        }
        // Résultat partiel : déclenchement rapide dès que le nom apparaît, sous réserve de vérification.
        val partial = VoskJson.words(g.partialResult()).joinToString(" ") { it.word }
        if (!config.strict && partial != lastPartial) {
            lastPartial = partial
            if (verificationsInSegment < 2 && grammarPhrases.any { phraseIn(it, partial) }) {
                verificationsInSegment++
                verify()?.let { endSegment(); g.reset(); return detected(it) }
            }
        }
        if (!speech) {
            val words = VoskJson.words(g.finalResult())
            g.reset()
            endSegment()
            return checkFinal(words)?.also { suppressUntilSilence = false }
        }
        if (segmentSamples > sampleRate * 8) { g.reset(); endSegment() } // énoncé trop long : on repart à zéro
        return null
    }

    private fun checkFinal(words: List<RecognizedWord>): WakeDetection? {
        if (words.isEmpty()) return null
        val text = words.joinToString(" ") { it.word }
        val phrase = grammarPhrases.firstOrNull { phraseIn(it, text) } ?: return null
        val conf = words.filter { it.word in phrase.split(' ') }.minOfOrNull { it.conf } ?: 0.0
        val min = if (config.strict) maxOf(config.threshold, 0.9) else config.threshold
        if (conf < min) return null
        return verify()?.copy(confidence = conf)
    }

    private fun phraseIn(phrase: String, text: String) = " $text ".contains(" $phrase ")

    /** Deuxième étape : retranscription libre des dernières secondes. */
    private fun verify(): WakeDetection? {
        val audio = ring.last(minOf(segmentSamples + sampleRate / 2, sampleRate * 4))
        val words = factory.free().use { d ->
            var off = 0
            val chunk = 3200
            val out = mutableListOf<RecognizedWord>()
            while (off < audio.size) {
                val len = minOf(chunk, audio.size - off)
                val buf = audio.copyOfRange(off, off + len)
                if (d.acceptWaveform(buf, len)) out += VoskJson.words(d.result())
                off += len
            }
            out += VoskJson.words(d.finalResult())
            out.map { it.word }
        }
        val joined = words.joinToString(" ")
        val ok = FrenchPhonetic.containsName(words, config.name) ||
            config.aliases.any { a -> a.isNotBlank() && phraseIn(a.lowercase(), joined) }
        return if (ok) WakeDetection(joined, 1.0) else null
    }

    private fun detected(d: WakeDetection?): WakeDetection? { if (d != null) suppressUntilSilence = true; return d }

    private fun endSegment() { inSegment = false; segmentSamples = 0; lastPartial = ""; verificationsInSegment = 0 }

    fun reset() { grammar?.reset(); endSegment(); ring.clear() }

    override fun close() { grammar?.close() }
}

data class CalibrationResult(
    val total: Int,
    /** Nombre d'essais où le nom a été reconnu (avec vérification). */
    val recognized: Int,
    val transcripts: List<String>,
    /** Transcriptions à apprendre comme variantes du nom. */
    val aliases: List<String>,
    val suggestedThreshold: Double,
)

/** Calibration : la personne dit le nom 3 fois ; on apprend comment Vosk l'entend et on règle le seuil. */
class NameCalibrator(private val factory: DecoderFactory) {

    fun analyze(name: String, recordings: List<ShortArray>): CalibrationResult {
        val transcripts = recordings.map { transcribe(factory.free(), it).joinToString(" ") { w -> w.word } }
        val aliases = transcripts
            .filter { it.isNotBlank() && it.split(' ').size <= 3 }
            .filter { !FrenchPhonetic.containsName(it.split(' '), name) || !factory.inVocabulary(name.lowercase()) }
            .distinct()
        val config = WakeConfig(name, aliases, threshold = 0.3)
        val confs = mutableListOf<Double>()
        val phrases = (listOf(name.lowercase()) + aliases).filter { p -> p.split(' ').all { factory.inVocabulary(it) } }
        if (phrases.isNotEmpty()) for (rec in recordings) {
            val words = factory.grammar(phrases + "[unk]").use { transcribe(it, rec) }
            words.filter { w -> phrases.any { w.word in it.split(' ') } }.minOfOrNull { it.conf }?.let { confs += it }
        }
        var recognized = 0
        for (rec in recordings) {
            WakeWordDetector(factory, config).use { d ->
                val padded = ShortArray(16_000 / 2) + rec + ShortArray(16_000)
                var hit: WakeDetection? = null
                var off = 0
                while (off < padded.size && hit == null) {
                    val len = minOf(1600, padded.size - off)
                    hit = d.process(padded.copyOfRange(off, off + len), len)
                    off += len
                }
                if (hit != null) recognized++
            }
        }
        // Seuil un peu sous la confiance la plus basse observée, borné entre 0,4 et 0,8.
        val threshold = ((confs.minOrNull() ?: 0.7) - 0.15).coerceIn(0.4, 0.8)
        return CalibrationResult(recordings.size, recognized, transcripts, aliases, threshold)
    }

    private fun transcribe(d: SpeechDecoder, audio: ShortArray): List<RecognizedWord> = d.use {
        val out = mutableListOf<RecognizedWord>()
        var off = 0
        while (off < audio.size) {
            val len = minOf(3200, audio.size - off)
            if (it.acceptWaveform(audio.copyOfRange(off, off + len), len)) out += VoskJson.words(it.result())
            off += len
        }
        out += VoskJson.words(it.finalResult())
        out
    }
}
