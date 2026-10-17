package fr.assistantvocal.core

import fr.assistantvocal.core.name.AssistantNameRules
import fr.assistantvocal.core.text.FrenchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchTextTest {
    @Test fun numbers() {
        fun n(s: String) = FrenchText.wordsToNumber(s.split(' '))
        assertEquals(7, n("sept"))
        assertEquals(21, n("vingt et un"))
        assertEquals(77, n("soixante dix sept"))
        assertEquals(80, n("quatre vingt"))
        assertEquals(99, n("quatre vingt dix neuf"))
        assertEquals(205, n("deux cent cinq"))
        assertEquals(1200, n("mille deux cents"))
        assertNull(n("bonjour"))
    }

    @Test fun replaceNumberWordsInSentence() {
        assertEquals("réveille-moi à 7 heures et quart", FrenchText.replaceNumberWords("Réveille-moi à sept heures et quart"))
        assertEquals("minuteur de 25 minutes", FrenchText.replaceNumberWords("minuteur de vingt-cinq minutes"))
        assertEquals("il est 1 heure", FrenchText.replaceNumberWords("il est une heure"))
    }

    @Test fun syllables() {
        assertEquals(2, FrenchText.syllableCount("Jarvis"))
        assertEquals(2, FrenchText.syllableCount("Orphée"))
        assertEquals(3, FrenchText.syllableCount("Balthazar"))
        assertEquals(3, FrenchText.syllableCount("Marguerite"))
        assertEquals(1, FrenchText.syllableCount("Paul"))
    }

    @Test fun keyRemovesAccentsAndPunctuation() {
        assertEquals("qu est ce que tu sais faire", FrenchText.key("Qu’est-ce que tu sais faire ?"))
    }
}

class AssistantNameTest {
    @Test fun goodName() {
        val a = AssistantNameRules.check("orphée")
        assertTrue(a.acceptable)
        assertEquals("Orphée", a.name)
        assertTrue(a.warnings.isEmpty())
    }

    @Test fun shortOrCommonNamesWarn() {
        assertTrue(AssistantNameRules.check("Paul").warnings.isNotEmpty())
        assertTrue(AssistantNameRules.check("Bonjour").warnings.any { it.contains("souvent") })
        assertTrue(AssistantNameRules.check("Lucie", familyNames = listOf("lucie")).warnings.any { it.contains("proche") })
    }

    @Test fun invalidNames() {
        assertFalse(AssistantNameRules.check("R2D2").acceptable)
        assertFalse(AssistantNameRules.check("").acceptable)
        assertFalse(AssistantNameRules.check("le grand assistant noir").acceptable)
    }

    @Test fun stripWakeName() {
        assertEquals("quelle heure est-il", AssistantNameRules.stripWakeName("Orphée, quelle heure est-il ?", "Orphée"))
        assertEquals("quelle heure est-il", AssistantNameRules.stripWakeName("hé orphee quelle heure est-il", "Orphée"))
        // petite erreur de transcription tolérée
        assertEquals("la météo", AssistantNameRules.stripWakeName("Orfée la météo", "Orphée"))
        assertNull(AssistantNameRules.stripWakeName("quelle heure est-il", "Orphée"))
    }

    @Test fun `contractions de et a devant un nom ou un numero`() {
        assertEquals("du 06 98 00 01 11", FrenchText.de("le 06 98 00 01 11"))
        assertEquals("d'un numéro masqué", FrenchText.de("un numéro masqué"))
        assertEquals("d'Anne Martin", FrenchText.de("Anne Martin"))
        assertEquals("d'Hélène", FrenchText.de("Hélène"))
        assertEquals("de Paul Martin", FrenchText.de("Paul Martin"))
        assertEquals("au 06 98 00 01 11", FrenchText.a("le 06 98 00 01 11"))
        assertEquals("à un numéro masqué", FrenchText.a("un numéro masqué"))
        assertEquals("à Anne", FrenchText.a("Anne"))
    }
}
