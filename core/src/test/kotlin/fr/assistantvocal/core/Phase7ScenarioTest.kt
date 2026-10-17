package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.router.LocalRouter
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.skills.CommunicationSkill
import fr.assistantvocal.core.skills.ReminderSkill
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.skills.SupportSkill
import fr.assistantvocal.core.support.Backup
import fr.assistantvocal.core.support.Check
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7ScenarioTest {
    private val store = InMemoryStore()
    private val settings = AssistantSettings(store).apply { name = "Hector"; ownerName = "Jean" }
    private val device = FakeDevice()
    private val phone = FakePhone()
    private val dialog = Dialog { 0L }
    private val people = PeopleStore(store)
    private val comms = CommunicationSkill(ContactDirectory(phone, people), people, phone, phone, phone, phone, phone, store, device, dialog)
    private val reminderStore = ReminderStore(store)
    private val reminders = ReminderSkill(reminderStore, MedicationLog(store), phone, store, device, dialog)
    private val router = LocalRouter()
    private var checks = listOf(Check("le micro", true), Check("le mot d'activation", true), Check("l'intelligence artificielle", true))
    private var backupWhere: String? = "le dossier Sauvegardes de l'application"
    private val support = SupportSkill(store, dialog, device, { settings.name }, { router.route(it)?.intent }, { checks }, { backupWhere })
    private val skills = Skills(dialog, comms, reminders).also { it.support = support }
    private val a = Assistant(settings, device, ScriptedLlm(), ToolRegistry(CoreTools.all(settings, device) + CoreTools.phase3()),
        nowMillis = { 1_000L }, skills = skills)

    private suspend fun say(t: String) = a.handle(t).speech

    @Test fun `tutoriel complet, sans rien declencher`() = runTest {
        val start = say("tutoriel")
        assertTrue(start, start.startsWith("Bienvenue ! Je suis Hector, votre assistant.") && start.endsWith("Essayez : demandez-moi l'heure."))
        assertTrue(say("quelle heure est-il ?").startsWith("Bravo ! Il est 14 heures 30."))
        // Mauvaise réponse : un indice, puis on passe à la suite au deuxième essai.
        assertEquals("Ce n'est pas tout à fait ça. Dites juste le mot : répète.", say("bonjour"))
        assertTrue(say("répète").startsWith("Parfait."))
        assertTrue(say("appelle Paul").startsWith("Très bien. En vrai, je vous aurais demandé de confirmer"))
        assertTrue("aucun appel pendant l'exercice", phone.calls.isEmpty())
        assertTrue(say("lis mes messages").startsWith("C'est ça."))
        assertTrue(say("reprends mon livre").startsWith("Exactement."))
        assertTrue(say("rappelle-moi d'arroser les plantes demain à 9 heures").startsWith("Très bien. Pour vos médicaments"))
        assertTrue("aucun rappel créé", reminderStore.all().isEmpty())
        assertTrue(say("compris").startsWith("Merci. Dernière leçon."))
        assertTrue(say("qu'est-ce que tu sais faire ?").endsWith("Bravo, le tutoriel est terminé ! Pour le refaire, dites : tutoriel. À bientôt."))
        assertTrue(support.tutorialDone)
        assertTrue(phone.sent.isEmpty())
    }

    @Test fun `tutoriel interrompu puis repris au meme endroit`() = runTest {
        say("lance le tutoriel")
        say("quelle heure est-il")
        assertEquals("D'accord, on s'arrête là. Pour reprendre plus tard, dites : reprends le tutoriel.", say("plus tard"))
        val r = say("reprends le tutoriel")
        assertTrue(r, r.startsWith("On reprend le tutoriel. Deuxième leçon."))
    }

    @Test fun `est-ce que tout marche`() = runTest {
        assertEquals("Tout fonctionne : le micro, le mot d'activation, l'intelligence artificielle.", say("est-ce que tout marche ?"))
        checks = listOf(Check("le micro", true), Check("internet", false, "pas de connexion, vérifiez le wifi."),
            Check("les livres", false, "aucun dossier choisi dans les réglages.", important = false))
        assertEquals("Il y a 2 points à régler. Internet : pas de connexion, vérifiez le wifi. Les livres : aucun dossier choisi dans les réglages. Le reste va bien : le micro.",
            say("fais un diagnostic"))
    }

    @Test fun `sauvegarde a la voix et signalement d'un probleme`() = runTest {
        assertEquals("C'est sauvegardé. Un proche peut récupérer le fichier dans le dossier Sauvegardes de l'application.", say("sauvegarde mes réglages"))
        backupWhere = null
        assertEquals("Je n'ai pas réussi à faire la sauvegarde.", say("fais une sauvegarde"))
        say("quelle heure est-il ?")
        assertTrue(say("signale un problème").startsWith("J'ai noté le problème"))
        val text = support.journal.text { "t$it" }
        assertTrue(text, text.contains("entendu : « quelle heure est-il ? »") && text.contains("*** PROBLÈME SIGNALÉ ***") && text.contains("dernier échange : « quelle heure est-il ? »"))
    }

    @Test fun `sauvegarde et restauration, sans secrets ni donnees volatiles`() {
        people.aliases = mapOf("mon fils" to "Pierre Dupont")
        store.putString("places.saved", """[{"name":"maison","lat":44.9,"lon":4.9}]""")
        store.putString("usage.gemini.2026-10-17", "12")
        store.putString("media.libraryUri", "content://tree/Victor")
        store.putString("journal.entries", "[]")
        val json = Backup.export(store, "2026-10-17T14:30", "Hector")
        assertFalse(json.contains("usage.gemini") || json.contains("content://tree") || json.contains("journal."))
        assertTrue(json.contains("\"assistant.name\": \"Hector\""))

        // Nouveau téléphone : on restaure.
        val target = InMemoryStore(mapOf("assistant.name" to "Assistant", "tts.rate" to "2.0", "usage.gemini.2026-10-17" to "3"))
        val parsed = Backup.parse(json)!!
        assertEquals("Sauvegarde du 2026-10-17, assistant « Hector » : 1 lieux, 1 surnoms.", Backup.summary(parsed))
        Backup.restore(target, parsed)
        assertEquals("Hector", AssistantSettings(target).name)
        assertNull("une valeur absente de la sauvegarde est effacée", target.getString("tts.rate"))
        assertEquals("3", target.getString("usage.gemini.2026-10-17"))
        assertEquals(mapOf("mon fils" to "Pierre Dupont"), PeopleStore(target).aliases)

        assertNull(Backup.parse("""{"format":"autre","version":1,"valeurs":{}}"""))
        assertNull(Backup.parse("""{"format":"assistant-vocal-sauvegarde","version":99,"valeurs":{}}"""))
        assertNull(Backup.parse("pas du json"))
        val hostile = Backup.parse("""{"format":"assistant-vocal-sauvegarde","version":1,"valeurs":{"journal.entries":"x","usage.x":"1","ok.key":"v","bad key!":"v","num":3}}""")!!
        assertEquals(mapOf("ok.key" to "v"), hostile.values)
    }

    @Test fun `routeur de la phase 7`() {
        val r = LocalRouter()
        assertEquals(fr.assistantvocal.core.router.Tutorial(false), r.route("tutoriel")?.intent)
        assertEquals(fr.assistantvocal.core.router.Tutorial(true), r.route("reprends le tutoriel")?.intent)
        assertEquals(fr.assistantvocal.core.router.SelfCheck, r.route("est-ce que tout fonctionne ?")?.intent)
        assertEquals(fr.assistantvocal.core.router.ReportProblem, r.route("ça ne marche pas")?.intent)
        assertEquals(fr.assistantvocal.core.router.BackupNow, r.route("sauvegarde mes réglages")?.intent)
        assertEquals(fr.assistantvocal.core.router.Intent.Help, r.route("qu'est-ce que tu sais faire ?")?.intent)
    }
}
