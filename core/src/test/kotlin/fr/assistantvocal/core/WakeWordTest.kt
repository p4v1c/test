package fr.assistantvocal.core

import fr.assistantvocal.core.text.FrenchPhonetic
import fr.assistantvocal.core.wake.DecoderFactory
import fr.assistantvocal.core.wake.EnergyVad
import fr.assistantvocal.core.wake.SpeechDecoder
import fr.assistantvocal.core.wake.VoskJson
import fr.assistantvocal.core.wake.WakeConfig
import fr.assistantvocal.core.wake.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchPhoneticTest {
    @Test fun homophones() {
        assertEquals(FrenchPhonetic.key("Orphée"), FrenchPhonetic.key("orfée"))
        assertTrue(FrenchPhonetic.containsName(listOf("or", "fée"), "Orphée"))
        assertTrue(FrenchPhonetic.containsName(listOf("dis", "orphée", "quelle", "heure"), "Orphée"))
        assertFalse(FrenchPhonetic.containsName(listOf("morphée"), "Orphée"))
        assertFalse(FrenchPhonetic.containsName(listOf("orfèvre"), "Orphée"))
        assertTrue(FrenchPhonetic.containsName(listOf("baltazar"), "Balthazar"))
        assertFalse(FrenchPhonetic.containsName(listOf("balzac"), "Balthazar"))
    }
}

class VoskJsonTest {
    @Test fun parsesResultsAndPartials() {
        val w = VoskJson.words("""{"result":[{"conf":0.92,"end":1.0,"start":0.5,"word":"orphée"}],"text":"orphée"}""")
        assertEquals("orphée", w.single().word)
        assertEquals(0.92, w.single().conf, 1e-9)
        assertEquals(listOf("bonjour", "toi"), VoskJson.words("""{"partial":"bonjour toi"}""").map { it.word })
        assertTrue(VoskJson.words("pas du json").isEmpty())
    }
}

/**
 * Décodeur simulé : une trame dont les échantillons valent 3001 représente « le nom prononcé »,
 * 3000 une autre parole, 0 le silence.
 */
private class FakeFactory(private val known: Set<String> = setOf("orphée")) : DecoderFactory {
    var freeDecodes = 0
    var freeSaysName = true
    /** Le vérificateur « nom contre mots fréquents » reconnaît-il le nom ? */
    var distractorSaysName = true

    inner class Dec(private val grammar: Boolean, private val distractors: Boolean = false) : SpeechDecoder {
        private var sawName = false
        private var sawSpeech = false
        override fun acceptWaveform(pcm: ShortArray, length: Int): Boolean {
            for (i in 0 until length) { if (pcm[i] == 3001.toShort()) sawName = true; if (pcm[i] >= 3000) sawSpeech = true }
            return false
        }
        private fun text() = when {
            grammar && sawName && (!distractors || distractorSaysName) -> "orphée"
            grammar && sawSpeech -> "[unk]"
            !grammar && sawName && freeSaysName -> "orphée quelle heure"
            !grammar && sawSpeech -> "bonjour"
            else -> ""
        }
        override fun result() = """{"text":"${text()}"}"""
        override fun partialResult() = """{"partial":"${text()}"}"""
        override fun finalResult(): String {
            val t = text()
            val words = t.split(' ').filter { it.isNotBlank() && it != "[unk]" }
            return """{"result":[${words.joinToString(",") { """{"conf":0.9,"word":"$it"}""" }}],"text":"$t"}"""
        }
        override fun reset() { sawName = false; sawSpeech = false }
        override fun close() { if (!grammar) freeDecodes++ }
    }

    override fun grammar(phrases: List<String>): SpeechDecoder = Dec(true, distractors = phrases.size > 5)
    override fun free(): SpeechDecoder = Dec(false)
    override fun inVocabulary(word: String) = word in known
}

class WakeWordDetectorTest {
    private fun frame(v: Int) = ShortArray(1600) { v.toShort() }
    private fun silence(n: Int) = List(n) { frame(0) }
    private fun run(d: WakeWordDetector, frames: List<ShortArray>) = frames.mapNotNull { d.process(it) }

    @Test fun detectsNameAfterVerification() {
        val f = FakeFactory()
        val d = WakeWordDetector(f, WakeConfig("Orphée"))
        val hits = run(d, silence(20) + List(5) { frame(3001) } + silence(25))
        assertEquals(1, hits.size)
        assertTrue(hits.single().heard.contains("orphée"))
    }

    @Test fun otherSpeechNeverTriggersAndSilenceCostsNothing() {
        val f = FakeFactory()
        val d = WakeWordDetector(f, WakeConfig("Orphée"))
        assertTrue(run(d, silence(50)).isEmpty())
        assertEquals("aucune vérification pendant le silence", 0, f.freeDecodes)
        assertTrue(run(d, List(10) { frame(3000) } + silence(25)).isEmpty())
    }

    @Test fun verificationRejectsWhenNameLosesAgainstCommonWords() {
        val f = FakeFactory(known = setOf("orphée", "de", "la", "le", "et", "les", "est")).apply { freeSaysName = false; distractorSaysName = false }
        val d = WakeWordDetector(f, WakeConfig("Orphée"))
        assertTrue(run(d, silence(20) + List(5) { frame(3001) } + silence(25)).isEmpty())
    }

    @Test fun learnedAliasPassesVerification() {
        val f = FakeFactory(known = setOf("orphée", "bonjour")).apply { freeSaysName = false; distractorSaysName = false }
        // La calibration a appris que le moteur libre entend « bonjour » pour ce nom (cas artificiel).
        val d = WakeWordDetector(f, WakeConfig("Orphée", aliases = listOf("bonjour")))
        assertNotNull(run(d, silence(20) + List(5) { frame(3001) } + silence(25)).firstOrNull())
    }

    @Test fun unknownNameIsNotUsableUntilCalibrated() {
        val d = WakeWordDetector(FakeFactory(known = emptySet()), WakeConfig("Zorglub"))
        assertFalse(d.isUsable)
        assertNull(d.process(frame(3001)))
    }

    @Test fun vadTracksBackgroundNoise() {
        val vad = EnergyVad()
        repeat(30) { vad.isSpeech(ShortArray(1600) { ((it % 7) * 40).toShort() }) }
        assertFalse(vad.isSpeech(ShortArray(1600) { ((it % 7) * 40).toShort() }))
        assertTrue(vad.isSpeech(ShortArray(1600) { (if (it % 2 == 0) 6000 else -6000).toShort() }))
    }
}
