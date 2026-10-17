package fr.assistantvocal.core

import fr.assistantvocal.core.wake.DecoderFactory
import fr.assistantvocal.core.wake.NameCalibrator
import fr.assistantvocal.core.wake.SpeechDecoder
import fr.assistantvocal.core.wake.WakeConfig
import fr.assistantvocal.core.wake.WakeWordDetector
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test d'intégration avec le vrai moteur Vosk (version PC) et des voix de synthèse françaises.
 * Ignoré si VOSK_MODEL et WAKE_FIXTURES ne sont pas définis (voir tools/wake/README.md).
 * Il affiche des mesures (taux de détection, faux déclenchements) plutôt que d'échouer au moindre écart :
 * seuls des seuils larges sont vérifiés.
 */
class WakeIntegrationTest {
    private interface Vocab : Library { fun vosk_model_find_word(model: Pointer, word: String): Int }

    private class DesktopFactory(private val model: Model) : DecoderFactory {
        private val vocab = Native.load("vosk", Vocab::class.java)
        override fun grammar(phrases: List<String>): SpeechDecoder =
            Dec(Recognizer(model, 16000f, phrases.joinToString(",", "[", "]") { "\"$it\"" }))
        override fun free(): SpeechDecoder = Dec(Recognizer(model, 16000f))
        override fun inVocabulary(word: String) = vocab.vosk_model_find_word(model.pointer, word.lowercase()) >= 0
    }

    private class Dec(private val r: Recognizer) : SpeechDecoder {
        init { r.setWords(true) }
        override fun acceptWaveform(pcm: ShortArray, length: Int) = r.acceptWaveForm(pcm, length)
        override fun result(): String = r.result
        override fun partialResult(): String = r.partialResult
        override fun finalResult(): String = r.finalResult
        override fun reset() = r.reset()
        override fun close() = r.close()
    }

    companion object {
        private val NAMES = System.getenv("WAKE_NAMES")?.split(',') ?: listOf("orphée", "balthazar", "hector", "jarvis", "gédéon")
        fun file(name: String) = fr.assistantvocal.core.text.FrenchText.stripAccents(name)
        private var model: Model? = null
        private var fixtures: File? = null

        @BeforeClass @JvmStatic fun load() {
            val m = System.getProperty("vosk.model") ?: return
            fixtures = System.getProperty("wake.fixtures")?.let(::File)
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(m)
        }

        fun wav(f: File): ShortArray {
            val bytes = f.readBytes()
            val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray((bytes.size - 44) / 2) { bb.short }
        }
    }

    private fun detect(d: WakeWordDetector, audio: ShortArray): List<Int> {
        val hits = mutableListOf<Int>()
        val padded = ShortArray(8000) + audio + ShortArray(16000)
        var off = 0
        while (off < padded.size) {
            val len = minOf(1600, padded.size - off)
            if (d.process(padded.copyOfRange(off, off + len), len) != null) hits += off
            off += len
        }
        return hits
    }

    private fun measure(name: String, aliases: List<String> = emptyList()): Pair<Double, Int> {
        val factory = DesktopFactory(model!!)
        val dir = fixtures!!
        val positives = dir.listFiles { f -> f.name.startsWith("name_${file(name)}_") }!!.sorted()
        val negatives = dir.listFiles { f -> f.name.startsWith("neg_") }!!.sorted()
        val config = WakeConfig(name.replaceFirstChar { it.uppercase() }, aliases, threshold = 0.6)
        val tp = positives.count { f -> WakeWordDetector(factory, config).use { detect(it, wav(f)).isNotEmpty() } }
        val fp = negatives.count { f -> WakeWordDetector(factory, config).use { detect(it, wav(f)).isNotEmpty() } }
        return tp.toDouble() / positives.size to fp
    }

    @Test fun nameDetectionOnSyntheticVoices() {
        assumeTrue("modèle Vosk absent", model != null && fixtures != null)
        val factory = DesktopFactory(model!!)
        for (name in NAMES) {
            if (fixtures!!.listFiles { f -> f.name.startsWith("name_${file(name)}_") }.isNullOrEmpty()) continue
            val (rate, fp) = measure(name)
            // Calibration sur 3 enregistrements d'une seule voix (comme le ferait l'utilisateur)
            val calib = fixtures!!.listFiles { f -> f.name.startsWith("name_${file(name)}_tom_") && f.name.endsWith("_0.wav") }!!
                .sorted().map { wav(it) }
            val c = NameCalibrator(factory).analyze(name.replaceFirstChar { it.uppercase() }, calib)
            val (rate2, fp2) = measure(name, c.aliases)
            println("MESURE $name : sans calibration ${"%.0f".format(rate * 100)} % détecté, $fp faux / 108 ; " +
                "après calibration (variantes ${c.aliases}) ${"%.0f".format(rate2 * 100)} % détecté, $fp2 faux / 108")
            check(fp2 <= 5) { "trop de faux déclenchements pour $name : $fp2" }
        }
    }

    @Test fun continuousConversationStream() {
        assumeTrue("modèle Vosk absent", model != null && fixtures != null)
        val factory = DesktopFactory(model!!)
        for (name in NAMES) {
            val f = File(fixtures, "stream_${file(name)}.wav")
            if (!f.exists()) continue
            val marks = File(fixtures, "stream_${file(name)}.txt").readLines().map { it.trim().toInt() + 8000 }
            val audio = wav(f)
            val start = System.nanoTime()
            val hits = WakeWordDetector(factory, WakeConfig(name.replaceFirstChar { it.uppercase() }, threshold = 0.6)).use { d ->
                if (System.getenv("WAKE_DEBUG") != null) d.log = { println("   [$name] $it") }
                detect(d, audio)
            }
            val secs = audio.size / 16000.0
            val cpu = (System.nanoTime() - start) / 1e9
            val good = marks.count { m -> hits.any { it in m..(m + 16000 * 3) } }
            val bad = hits.count { h -> marks.none { m -> h in m..(m + 16000 * 3) } }
            if (System.getenv("WAKE_DEBUG") != null) println("   positions du nom ${marks.map { it / 16000 }} s ; détections ${hits.map { it / 16000 }} s")
            println("FLUX $name : ${"%.0f".format(secs)} s de conversation, nom dit ${marks.size} fois, détecté $good fois, " +
                "$bad faux déclenchements, calcul ${"%.1f".format(cpu)} s (${"%.0f".format(100 * cpu / secs)} % du temps réel)")
            check(bad <= 2) { "trop de faux déclenchements dans le flux $name : $bad" }
        }
    }
}
