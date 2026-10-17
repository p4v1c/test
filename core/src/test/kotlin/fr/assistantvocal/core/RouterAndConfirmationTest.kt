package fr.assistantvocal.core

import fr.assistantvocal.core.dialog.ConfirmationMachine
import fr.assistantvocal.core.dialog.ConfirmationOutcome
import fr.assistantvocal.core.dialog.YesNo
import fr.assistantvocal.core.dialog.YesNoParser
import fr.assistantvocal.core.router.Intent
import fr.assistantvocal.core.router.LocalRouter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRouterTest {
    private val router = LocalRouter()
    private fun intent(s: String) = router.route(s)?.intent

    @Test fun basicIntents() {
        assertEquals(Intent.AskTime, intent("Quelle heure est-il ?"))
        assertEquals(Intent.AskTime, intent("il est quelle heure"))
        assertEquals(Intent.AskDate, intent("On est quel jour aujourd'hui ?"))
        assertEquals(Intent.AskBattery, intent("Combien il reste de batterie ?"))
        assertEquals(Intent.AskNetwork, intent("est-ce que j'ai internet"))
        assertEquals(Intent.Stop, intent("Stop"))
        assertEquals(Intent.Stop, intent("tais-toi"))
        assertEquals(Intent.Cancel, intent("laisse tomber"))
        assertEquals(Intent.Repeat, intent("tu peux répéter ?"))
        assertEquals(Intent.Help, intent("Qu'est-ce que tu sais faire ?"))
        assertEquals(Intent.WhoAreYou, intent("comment tu t'appelles"))
        assertEquals(Intent.Thanks, intent("merci beaucoup"))
    }

    @Test fun settingsIntents() {
        assertEquals(Intent.SpeechRate(true), intent("parle plus vite"))
        assertEquals(Intent.SpeechRate(false), intent("Parle moins vite s'il te plaît"))
        assertEquals(Intent.Volume(true), intent("parle plus fort"))
        assertEquals(Intent.Volume(false), intent("baisse le volume"))
        assertEquals(Intent.SetVerbosity(true), intent("sois plus bref"))
        assertEquals(Intent.SetVerbosity(false), intent("donne-moi plus de détails"))
        assertEquals(Intent.PrivateMode(true), intent("active le mode privé"))
        assertEquals(Intent.PrivateMode(false), intent("quitte le mode privé"))
    }

    @Test fun changeNameKeepsAccents() {
        assertEquals(Intent.ChangeName("Orphée"), intent("Change ton nom en Orphée"))
        assertEquals(Intent.ChangeName("Balthazar"), intent("je vais t'appeler balthazar"))
    }

    @Test fun helpCategory() {
        assertEquals(Intent.HelpCategory("reglages"), intent("aide pour les réglages"))
    }

    @Test fun longSentencesGetLowerConfidence() {
        val m = router.route("à quelle heure commence le match de rugby de ce soir à la télé")!!
        assertTrue(m.confidence < 0.8)
    }

    @Test fun unknownReturnsNull() {
        assertNull(router.route("raconte-moi l'histoire de Victor Hugo"))
        assertNull(router.route("   "))
    }
}

class ConfirmationTest {
    @Test fun yesNoParsing() {
        assertEquals(YesNo.YES, YesNoParser.parse("Oui"))
        assertEquals(YesNo.YES, YesNoParser.parse("oui vas-y"))
        assertEquals(YesNo.YES, YesNoParser.parse("d'accord"))
        assertEquals(YesNo.YES, YesNoParser.parse("ouais c'est bon"))
        assertEquals(YesNo.NO, YesNoParser.parse("non"))
        assertEquals(YesNo.NO, YesNoParser.parse("non surtout pas"))
        assertEquals(YesNo.NO, YesNoParser.parse("stop"))
        assertEquals(YesNo.NO, YesNoParser.parse("oui mais non"))
        assertEquals(YesNo.UNKNOWN, YesNoParser.parse("quelle heure est-il"))
        assertEquals(YesNo.UNKNOWN, YesNoParser.parse(""))
    }

    @Test fun executesOnlyAfterYes() = runTest {
        var executed = 0
        val m = ConfirmationMachine { 0L }
        m.propose("Appeler Paul ?") { executed++; "Appel en cours." }
        val first = m.answer("hein ?")
        assertTrue(first is ConfirmationOutcome.AskAgain)
        assertEquals(0, executed)
        val second = m.answer("oui")
        assertEquals(ConfirmationOutcome.Executed("Appel en cours."), second)
        assertEquals(1, executed)
    }

    @Test fun twoUnclearAnswersCancelForSafety() = runTest {
        var executed = false
        val m = ConfirmationMachine { 0L }
        m.propose("Envoyer ?") { executed = true; "Envoyé." }
        m.answer("euh")
        val out = m.answer("je sais pas")
        assertTrue(out is ConfirmationOutcome.Cancelled)
        assertEquals(false, executed)
        assertEquals(false, m.hasPending())
    }

    @Test fun expiresAfterTimeout() = runTest {
        var now = 0L
        val m = ConfirmationMachine { now }
        m.propose("Envoyer ?") { "Envoyé." }
        now = 61_000
        assertNull(m.answer("oui"))
    }

    @Test fun `combien de temps reste-t-il avant Noel reste une question libre`() {
        assertEquals(null, fr.assistantvocal.core.router.TaskRouter().route("combien de temps reste-t-il avant Noël ?"))
    }
}
