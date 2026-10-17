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

/** Mot reconnu ; [start] et [end] en secondes depuis la dernière remise à zéro du décodeur (-1 si inconnu). */
data class RecognizedWord(val word: String, val conf: Double, val start: Double = -1.0, val end: Double = -1.0)

object VoskJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun words(result: String): List<RecognizedWord> {
        val obj = runCatching { json.parseToJsonElement(result).jsonObject }.getOrNull() ?: return emptyList()
        val arr = obj["result"] as? JsonArray
        if (arr != null) return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val w = o["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RecognizedWord(
                w, o["conf"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                o["start"]?.jsonPrimitive?.doubleOrNull ?: -1.0, o["end"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
            )
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
 * Bruit de fond = niveau le plus bas des 4 dernières secondes (les pauses entre les phrases) ;
 * la parole doit le dépasser de [marginDb] décibels.
 */
class EnergyVad(
    private val marginDb: Double = 9.0,
    private val minDb: Double = 38.0,
    private val hangoverFrames: Int = 5,
    historyFrames: Int = 40,
) {
    private val history = DoubleArray(historyFrames) { Double.MAX_VALUE }
    private var idx = 0
    private var hangover = 0

    val noiseFloorDb: Double get() = history.minOrNull()?.takeIf { it != Double.MAX_VALUE } ?: 0.0

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
        history[idx] = db; idx = (idx + 1) % history.size
        val loud = db > noiseFloorDb + marginDb && db > minDb
        if (loud) { hangover = hangoverFrames; return true }
        if (hangover > 0) { hangover--; return true }
        return false
    }
}

/** Tampon circulaire des dernières secondes d'audio, adressable par position absolue. */
class AudioRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    /** Nombre total d'échantillons écrits depuis la création. */
    var written = 0L
        private set

    fun write(frame: ShortArray, length: Int = frame.size) {
        for (i in 0 until length) data[((written + i) % capacity).toInt()] = frame[i]
        written += length
    }

    /** Les [samples] derniers échantillons, dans l'ordre. */
    fun last(samples: Int): ShortArray = slice(written - samples, written)

    /** Échantillons entre deux positions absolues (bornées à ce qui est encore en mémoire). */
    fun slice(from: Long, to: Long): ShortArray {
        val a = maxOf(from, written - capacity, 0L)
        val b = minOf(to, written)
        if (b <= a) return ShortArray(0)
        return ShortArray((b - a).toInt()) { i -> data[((a + i) % capacity).toInt()] }
    }

    fun clear() { written = 0 }
}

data class WakeDetection(val heard: String, val confidence: Double)

internal object Transcriber {
    fun run(d: SpeechDecoder, audio: ShortArray): List<RecognizedWord> {
        val out = mutableListOf<RecognizedWord>()
        var off = 0
        while (off < audio.size) {
            val len = minOf(3200, audio.size - off)
            if (d.acceptWaveform(audio.copyOfRange(off, off + len), len)) out += VoskJson.words(d.result())
            off += len
        }
        out += VoskJson.words(d.finalResult())
        return out
    }
}

/** 1000 mots français fréquents (liste wordfreq, CC BY-SA 4.0), utilisés comme « concurrents » du nom. */
object CommonFrenchWords {
    val words: List<String> by lazy {
        CommonFrenchWords::class.java.getResourceAsStream("/fr/assistantvocal/core/wake/mots_frequents.txt")
            ?.bufferedReader(Charsets.UTF_8)?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}

/**
 * Détection du nom en deux étapes :
 * 1. un décodeur Vosk restreint au nom écoute en continu tant que quelqu'un parle (le silence ne coûte rien) ;
 * 2. quand le nom sort avec une confiance suffisante, on revérifie le passage exact où il a été dit
 *    (Vosk donne l'horodatage de chaque mot) : le nom doit l'emporter face à 1000 mots français fréquents.
 * Mesures (voix de synthèse, conversation continue avec bruit) : voir tools/wake/README.md.
 */
class WakeWordDetector(
    private val factory: DecoderFactory,
    val config: WakeConfig,
    private val sampleRate: Int = 16_000,
) : AutoCloseable {
    private val vad = EnergyVad()
    private val ring = AudioRing(sampleRate * 10)
    private val grammarPhrases: List<String> = buildGrammar()
    private val grammar: SpeechDecoder? = grammarPhrases.takeIf { it.isNotEmpty() }?.let { factory.grammar(it + "[unk]") }

    /** Journal de diagnostic (Log.d sur Android, println dans les tests). */
    var log: ((String) -> Unit)? = null

    /** Le décodeur reçoit de l'audio (quelqu'un parle, ou vient de parler). */
    private var active = false
    /**
     * Les horodatages Vosk comptent les échantillons reçus depuis la création du décodeur (même après reset).
     * On garde donc, pour chaque reprise d'écoute, la correspondance « échantillon reçu n° X = position Y du tampon ».
     */
    private var fedTotal = 0L
    private val segments = ArrayDeque<Pair<Long, Long>>() // (fedStart, absStart)
    private var quietFrames = 0
    /** Un nom qui se termine avant cette position a déjà été signalé (anti-rebond). */
    private var mutedUntil = 0L

    private var verifierInstance: SpeechDecoder? = null
    private var verifierBuilt = false

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

    /** Vérificateur « nom contre mots fréquents », créé une seule fois (la compilation est lente). */
    private fun verifier(): SpeechDecoder? {
        if (verifierBuilt) return verifierInstance
        verifierBuilt = true
        if (grammarPhrases.isEmpty()) return null
        val nameWords = grammarPhrases.flatMap { it.split(' ') }.toSet()
        val distractors = CommonFrenchWords.words.filter { w ->
            w !in nameWords && !FrenchPhonetic.containsName(listOf(w), config.name) && factory.inVocabulary(w)
        }
        verifierInstance = factory.grammar(grammarPhrases + distractors + "[unk]")
        return verifierInstance
    }

    /** Traite une trame (100 ms conseillés). Renvoie une détection, ou null. */
    fun process(frame: ShortArray, length: Int = frame.size): WakeDetection? {
        val g = grammar ?: return null
        val frameStart = ring.written
        ring.write(frame, length)
        val speech = vad.isSpeech(frame, length)
        if (!active) {
            if (!speech) return null
            // Début de parole : on envoie aussi les 300 ms qui précèdent.
            active = true; quietFrames = 0
            val preStart = maxOf(0L, frameStart - sampleRate * 3 / 10, ring.written - length - sampleRate * 9L)
            val pre = ring.slice(preStart, frameStart)
            segments.addLast(fedTotal to frameStart - pre.size)
            while (segments.size > 20) segments.removeFirst()
            if (pre.isNotEmpty()) { g.acceptWaveform(pre, pre.size); fedTotal += pre.size }
        }
        quietFrames = if (speech) 0 else quietFrames + 1
        fedTotal += length
        var hit: WakeDetection? = null
        if (g.acceptWaveform(frame, length)) hit = check(VoskJson.words(g.result()))
        if (hit == null && quietFrames >= QUIET_FRAMES_TO_STOP) {
            // Une seconde de calme : on clôt et on remet le décodeur à zéro jusqu'à la prochaine parole.
            hit = check(VoskJson.words(g.finalResult()))
            g.reset(); active = false
        }
        return hit
    }

    private fun check(words: List<RecognizedWord>): WakeDetection? {
        if (words.isEmpty()) return null
        val min = if (config.strict) maxOf(config.threshold, 0.9) else config.threshold
        for ((i, w) in words.withIndex()) {
            // Le nom peut faire plusieurs mots (« or fée ») : on teste chaque phrase de la grammaire à cette position.
            val phrase = grammarPhrases.firstOrNull { p ->
                val pw = p.split(' ')
                pw.indices.all { k -> words.getOrNull(i + k)?.word == pw[k] }
            } ?: continue
            val span = words.subList(i, i + phrase.split(' ').size)
            val conf = span.minOf { it.conf }
            val (from, to) = if (span.first().start >= 0) {
                toAbsolute(span.first().start) to toAbsolute(span.last().end)
            } else {
                (ring.written - sampleRate * 2) to ring.written // pas d'horodatage : les 2 dernières secondes
            }
            log?.invoke("candidat « $phrase » confiance ${"%.2f".format(conf)}")
            if (conf < min || to < mutedUntil) continue
            val verified = verify(from, to) ?: continue
            mutedUntil = to + 1 // ne pas redétecter ce même passage, sans masquer la suite
            return verified.copy(confidence = conf)
        }
        return null
    }

    private fun phraseIn(phrase: String, text: String) = " $text ".contains(" $phrase ")

    /** Convertit un horodatage Vosk (secondes depuis la création du décodeur) en position du tampon audio. */
    private fun toAbsolute(seconds: Double): Long {
        val fed = (seconds * sampleRate).toLong()
        val seg = segments.lastOrNull { it.first <= fed } ?: segments.firstOrNull() ?: return ring.written
        return seg.second + (fed - seg.first)
    }

    /**
     * Deuxième étape sur le passage du nom (avec 0,8 s avant et 0,4 s après) :
     * a) le nom doit l'emporter face à 1000 mots fréquents ; sinon
     * b) s'il existe des variantes apprises, la retranscription libre doit en contenir une.
     */
    private fun verify(from: Long, to: Long): WakeDetection? {
        val audio = ring.slice(from - sampleRate * 8 / 10, to + sampleRate * 4 / 10)
        if (audio.isEmpty()) return null
        val padded = audio + ShortArray(sampleRate / 4)
        verifier()?.let { v ->
            v.reset()
            val words = Transcriber.run(v, padded).map { it.word }
            v.reset()
            val joined = words.joinToString(" ")
            log?.invoke("vérification : « $joined »")
            if (grammarPhrases.any { phraseIn(it, joined) }) return WakeDetection(joined, 1.0)
        }
        if (config.aliases.isEmpty()) return null
        val words = factory.free().use { d -> Transcriber.run(d, padded).map { it.word } }
        val joined = words.joinToString(" ")
        val ok = FrenchPhonetic.containsName(words, config.name) || config.aliases.any { a -> phraseIn(a.lowercase(), joined) }
        log?.invoke("vérification libre : « $joined » -> ${if (ok) "OK" else "rejet"}")
        return if (ok) WakeDetection(joined, 1.0) else null
    }

    fun reset() { grammar?.reset(); active = false; quietFrames = 0; mutedUntil = 0 }

    override fun close() {
        grammar?.close()
        verifierInstance?.close()
        verifierInstance = null
    }

    private companion object { const val QUIET_FRAMES_TO_STOP = 10 }
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
        val transcripts = recordings.map { rec -> factory.free().use { transcribe(it, rec) }.joinToString(" ") { w -> w.word } }
        // 1. Le nom tel quel suffit-il ? (cas le plus fréquent : le nom est dans le vocabulaire de Vosk)
        var recognized = countRecognized(WakeConfig(name, threshold = 0.3), recordings)
        var aliases = emptyList<String>()
        // 2. Sinon, on apprend comment le moteur entend ce nom. Chaque variante ajoute un risque de faux
        //    déclenchement : on écarte celles faites uniquement de mots courants (« en fait »).
        if (recognized < 2) {
            aliases = transcripts
                .filter { it.isNotBlank() && it.split(' ').size <= 3 }
                .filter { t -> !t.split(' ').all { it in CommonFrenchWords.words } }
                .distinct()
            if (aliases.isNotEmpty()) recognized = countRecognized(WakeConfig(name, aliases, threshold = 0.3), recordings)
        }
        val confs = mutableListOf<Double>()
        val phrases = (listOf(name.lowercase()) + aliases).filter { p -> p.split(' ').all { factory.inVocabulary(it) } }
        if (phrases.isNotEmpty()) for (rec in recordings) {
            val words = factory.grammar(phrases + "[unk]").use { transcribe(it, rec) }
            words.filter { w -> phrases.any { w.word in it.split(' ') } }.minOfOrNull { it.conf }?.let { confs += it }
        }
        // Seuil un peu sous la confiance la plus basse observée, borné entre 0,4 et 0,8.
        val threshold = ((confs.minOrNull() ?: 0.7) - 0.15).coerceIn(0.4, 0.8)
        return CalibrationResult(recordings.size, recognized, transcripts, aliases, threshold)
    }

    private fun countRecognized(config: WakeConfig, recordings: List<ShortArray>): Int = recordings.count { rec ->
        WakeWordDetector(factory, config).use { d ->
            val padded = ShortArray(16_000 / 2) + rec + ShortArray(16_000 * 2)
            var off = 0
            var hit: WakeDetection? = null
            while (off < padded.size && hit == null) {
                val len = minOf(1600, padded.size - off)
                hit = d.process(padded.copyOfRange(off, off + len), len)
                off += len
            }
            hit != null
        }
    }

    /** Ne ferme pas le décodeur : c'est à l'appelant de le faire (une double fermeture fait planter Vosk). */
    private fun transcribe(d: SpeechDecoder, audio: ShortArray): List<RecognizedWord> = Transcriber.run(d, audio)
}
