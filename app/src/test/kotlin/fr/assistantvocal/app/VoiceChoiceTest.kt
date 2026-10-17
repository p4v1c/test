package fr.assistantvocal.app

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import fr.assistantvocal.app.audio.FrenchVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceChoiceTest {
    private fun voice(name: String, locale: Locale, quality: Int, network: Boolean = false, installed: Boolean = true) =
        Voice(name, locale, quality, Voice.LATENCY_NORMAL, network,
            if (installed) emptySet() else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))

    @Test fun `la voix Google est preferee a une voix robotique par defaut`() {
        val installed = listOf("com.reecedunn.espeak", FrenchVoice.GOOGLE)
        assertEquals(FrenchVoice.GOOGLE, FrenchVoice.engine("com.reecedunn.espeak", installed))
        assertEquals(FrenchVoice.GOOGLE, FrenchVoice.engine("com.svox.pico", installed + "com.svox.pico"))
        assertEquals(FrenchVoice.GOOGLE, FrenchVoice.engine(null, installed))
    }

    @Test fun `un moteur choisi par un proche est respecte, et sans Google on garde le defaut`() {
        assertNull(FrenchVoice.engine("com.acapela.tts", listOf("com.acapela.tts", FrenchVoice.GOOGLE)))
        assertNull(FrenchVoice.engine("com.reecedunn.espeak", listOf("com.reecedunn.espeak")))
    }

    @Test fun `meilleure voix francaise installee et sans internet`() {
        val voices = listOf(
            voice("en-us-x-best", Locale.US, Voice.QUALITY_VERY_HIGH),
            voice("fr-fr-x-network", Locale.FRANCE, Voice.QUALITY_VERY_HIGH, network = true),
            voice("fr-fr-x-absente", Locale.FRANCE, Voice.QUALITY_VERY_HIGH, installed = false),
            voice("fr-ca-x-local", Locale.CANADA_FRENCH, Voice.QUALITY_VERY_HIGH),
            voice("fr-fr-x-basse", Locale.FRANCE, Voice.QUALITY_LOW),
            voice("fr-fr-x-local", Locale.FRANCE, Voice.QUALITY_HIGH),
        )
        assertEquals("fr-fr-x-local", FrenchVoice.best(voices)?.name)
    }

    @Test fun `a defaut de voix de France, une autre voix francaise`() {
        val voices = listOf(voice("en", Locale.US, Voice.QUALITY_HIGH), voice("fr-ca", Locale.CANADA_FRENCH, Voice.QUALITY_NORMAL))
        assertEquals("fr-ca", FrenchVoice.best(voices)?.name)
        assertNull(FrenchVoice.best(listOf(voice("en", Locale.US, Voice.QUALITY_HIGH))))
    }
}
