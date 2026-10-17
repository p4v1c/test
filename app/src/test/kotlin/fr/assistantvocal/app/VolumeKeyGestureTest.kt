package fr.assistantvocal.app

import fr.assistantvocal.app.triggers.VolumeKeyGesture
import fr.assistantvocal.app.triggers.VolumeKeyGesture.Decision
import fr.assistantvocal.app.triggers.VolumeKeyGesture.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeKeyGestureTest {
    private val g = VolumeKeyGesture()

    @Test fun longPressDownListens() {
        assertEquals(Decision.CONSUME_AND_START_TIMER, g.onKey(Key.DOWN, pressed = true, repeat = false, assistantSpeaking = false))
        assertEquals(Decision.CONSUME, g.onKey(Key.DOWN, pressed = true, repeat = true, assistantSpeaking = false))
        assertEquals(Decision.LISTEN, g.onTimer())
        assertNull("une seule écoute par appui", g.onTimer())
        assertEquals(Decision.CONSUME, g.onKey(Key.DOWN, pressed = false, repeat = false, assistantSpeaking = false))
    }

    @Test fun shortPressDownStillLowersVolume() {
        g.onKey(Key.DOWN, pressed = true, repeat = false, assistantSpeaking = false)
        assertEquals(Decision.CONSUME_AND_LOWER_VOLUME, g.onKey(Key.DOWN, pressed = false, repeat = false, assistantSpeaking = false))
        assertNull("relâché avant le délai : pas d'écoute", g.onTimer())
    }

    @Test fun volumeUpIsUntouched() {
        assertEquals(Decision.PASS, g.onKey(Key.UP, pressed = true, repeat = false, assistantSpeaking = false))
        assertEquals(Decision.PASS, g.onKey(Key.UP, pressed = false, repeat = false, assistantSpeaking = false))
    }

    @Test fun anyVolumeKeyInterruptsSpeech() {
        assertEquals(Decision.STOP_SPEAKING, g.onKey(Key.UP, pressed = true, repeat = false, assistantSpeaking = true))
        assertEquals(Decision.CONSUME, g.onKey(Key.UP, pressed = false, repeat = false, assistantSpeaking = false))
        assertEquals(Decision.STOP_SPEAKING, g.onKey(Key.DOWN, pressed = true, repeat = false, assistantSpeaking = true))
        assertEquals(Decision.CONSUME, g.onKey(Key.DOWN, pressed = false, repeat = false, assistantSpeaking = false))
    }
}
