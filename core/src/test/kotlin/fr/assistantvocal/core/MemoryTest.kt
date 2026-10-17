package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Prompts
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.settings.Verbosity
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mémoire de l'assistant (façon « gemini.md ») : ce que la personne lui demande de retenir, donné à l'IA. */
class MemoryTest {
    private val settings = AssistantSettings(InMemoryStore()).apply { name = "Hector"; ownerName = "Jean" }
    private val device = FakeDevice()
    private val llm = ScriptedLlm()
    private val a = Assistant(settings, device, llm, ToolRegistry(CoreTools.all(settings, device)), nowMillis = { 0L })
    private suspend fun say(t: String) = a.handle(t).speech

    @Test fun `il retient ce qu'on lui demande et le redit`() = runTest {
        assertEquals("C'est retenu : « j'aime le jazz ». Je m'en souviendrai.", say("souviens-toi que j'aime le jazz"))
        assertEquals("C'est retenu : « mon petit-fils s'appelle Lucas ». Je m'en souviendrai.", say("retiens que mon petit-fils s'appelle Lucas"))
        assertEquals("Vous m'avez demandé de retenir : « j'aime le jazz » ; « mon petit-fils s'appelle Lucas ».", say("qu'est-ce que tu sais sur moi ?"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun `la memoire et le prenom sont donnes a l'IA`() = runTest {
        say("souviens-toi que j'aime le jazz")
        llm.enqueue(ScriptedLlm.text("Je vous conseille Miles Davis."))
        say("quelle musique me conseilles-tu ?")
        val system = llm.requests.single().system
        assertTrue(system, system.contains("Tu parles à Jean."))
        assertTrue(system, system.contains("j'aime le jazz"))
        assertTrue(system, system.contains("Vouvoie toujours la personne")) // sur émulateur, Gemini s'était mis à tutoyer
    }

    @Test fun `oublier une information ou tout, avec confirmation`() = runTest {
        say("souviens-toi que j'aime le jazz")
        say("retiens que je suis diabétique")
        assertEquals("J'oublie « j'aime le jazz » ? Répondez oui ou non.", say("oublie que j'aime le jazz"))
        say("oui")
        assertEquals(listOf("je suis diabétique"), settings.memoryFacts)
        assertTrue(say("oublie tout ce que tu sais sur moi").endsWith("Répondez oui ou non."))
        say("oui")
        assertTrue(settings.memoryFacts.isEmpty())
        assertEquals("Vous ne m'avez encore rien demandé de retenir. Dites par exemple : souviens-toi que j'aime le jazz.", say("qu'est-ce que tu sais sur moi ?"))
    }

    @Test fun `note que reste une note, et un surnom reste un surnom`() {
        val r = fr.assistantvocal.core.router.LocalRouter()
        assertFalse(r.route("note que le code du portail est 1 2 3 4")?.intent is fr.assistantvocal.core.router.Intent.Remember)
        assertFalse(r.route("retiens que Pierre Dupont c'est mon fils")?.intent is fr.assistantvocal.core.router.Intent.Remember)
        assertTrue(r.route("n'oublie pas que je suis allergique aux arachides")?.intent is fr.assistantvocal.core.router.Intent.Remember)
    }

    @Test fun `l'IA peut retenir avec la fonction remember`() = runTest {
        val tool = ToolRegistry(CoreTools.all(settings, device))["remember"]!!
        tool.execute(buildJsonObject { put("fact", "Ma fille Marie habite à Lyon") })
        assertEquals(listOf("Ma fille Marie habite à Lyon"), settings.memoryFacts)
    }

    @Test fun `prompt sans memoire ni prenom inchange`() {
        val s = Prompts.system("Hector", Verbosity.NORMAL, device.now())
        assertFalse(s.contains("Tu parles à"))
    }
}
