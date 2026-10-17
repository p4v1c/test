package fr.assistantvocal.core

import fr.assistantvocal.core.time.FrenchTime
import fr.assistantvocal.core.time.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class FrenchTimeTest {
    // samedi 17 octobre 2026, 14 h 30
    private val now = LocalDateTime.of(2026, 10, 17, 14, 30)

    private fun at(text: String) = (FrenchTime.parseWhen(text, now)!!.whenever as When.At).dateTime
    private fun rest(text: String) = FrenchTime.parseWhen(text, now)!!.rest

    @Test fun durations() {
        assertEquals(Duration.ofMinutes(10), FrenchTime.parseDuration("dix minutes"))
        assertEquals(Duration.ofMinutes(90), FrenchTime.parseDuration("une heure et demie"))
        assertEquals(Duration.ofMinutes(90), FrenchTime.parseDuration("1h30"))
        assertEquals(Duration.ofMinutes(135), FrenchTime.parseDuration("2 heures 15"))
        assertEquals(Duration.ofMinutes(15), FrenchTime.parseDuration("un quart d'heure"))
        assertEquals(Duration.ofMinutes(30), FrenchTime.parseDuration("une demi-heure"))
        assertEquals(Duration.ofSeconds(30), FrenchTime.parseDuration("trente secondes"))
        assertNull(FrenchTime.parseDuration("les pâtes"))
    }

    @Test fun timesOfDay() {
        assertEquals(LocalTime.of(7, 0), FrenchTime.parseTime("à sept heures"))
        assertEquals(LocalTime.of(7, 15), FrenchTime.parseTime("sept heures et quart"))
        assertEquals(LocalTime.of(7, 45), FrenchTime.parseTime("huit heures moins le quart"))
        assertEquals(LocalTime.of(20, 0), FrenchTime.parseTime("huit heures du soir"))
        assertEquals(LocalTime.of(14, 30), FrenchTime.parseTime("14h30"))
        assertEquals(LocalTime.of(12, 30), FrenchTime.parseTime("midi et demie"))
    }

    @Test fun absoluteMoments() {
        assertEquals(LocalDateTime.of(2026, 10, 18, 8, 0), at("demain à 8 heures"))
        assertEquals(LocalDateTime.of(2026, 10, 17, 14, 40), at("dans dix minutes"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 14, 30), at("lundi à 14h30"))
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 0), at("le 25 octobre à 9 heures"))
        // heure déjà passée : 15 h aujourd'hui pour « 3 heures » dit à 14 h 30
        assertEquals(LocalDateTime.of(2026, 10, 17, 15, 0), at("à 3 heures"))
        // 10 h est passé et 22 h aussi tard : demain 10 h
        assertEquals(LocalDateTime.of(2026, 10, 18, 10, 0), at("à 10 heures"))
        assertEquals(LocalDateTime.of(2026, 10, 17, 20, 0), at("ce soir à 8 heures"))
    }

    @Test fun recurring() {
        val r = FrenchTime.parseWhen("de prendre mon Doliprane tous les jours à 8 heures et à 20 heures", now)!!
        assertEquals(When.Recurring(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), emptySet()), r.whenever)
        assertEquals("de prendre mon doliprane", r.rest)
        val w = FrenchTime.parseWhen("tous les lundis à 9h sortir les poubelles", now)!!.whenever as When.Recurring
        assertEquals(setOf(DayOfWeek.MONDAY), w.days)
        val s = FrenchTime.parseWhen("en semaine à 7 heures", now)!!.whenever as When.Recurring
        assertEquals(5, s.days.size)
    }

    @Test fun restOfSentence() {
        assertEquals("d appeler le médecin", rest("d'appeler le médecin demain à 10 heures"))
        assertEquals("de sortir le gâteau", rest("dans 40 minutes de sortir le gâteau"))
    }

    @Test fun speaking() {
        assertEquals("demain à 8 heures", FrenchTime.speakDateTime(LocalDateTime.of(2026, 10, 18, 8, 0), now))
        assertEquals("le mardi 20 octobre à 14 heures 30", FrenchTime.speakDateTime(LocalDateTime.of(2026, 10, 20, 14, 30), now))
        assertEquals("1 heure et 30 minutes", FrenchTime.speakDuration(Duration.ofMinutes(90)))
        assertEquals("tous les jours à 8 heures et à 20 heures",
            FrenchTime.speakRecurrence(When.Recurring(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), emptySet())))
    }
}
