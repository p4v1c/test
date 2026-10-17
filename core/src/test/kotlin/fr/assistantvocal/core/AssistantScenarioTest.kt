package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.BatteryInfo
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.llm.FailureKind
import fr.assistantvocal.core.llm.FallbackLlmProvider
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.llm.UsageCounter
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.settings.Verbosity
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.Risk
import fr.assistantvocal.core.tools.Tool
import fr.assistantvocal.core.tools.ToolOutcome
import fr.assistantvocal.core.tools.ToolRegistry
import fr.assistantvocal.core.tools.toolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Scénarios écrits du point de vue de l'utilisateur : « il dit X, il entend Y ».
 * Le texte remplace la reconnaissance vocale.
 */
class AssistantScenarioTest {
    private val store = InMemoryStore()
    private val settings = AssistantSettings(store).apply { name = "Orphée" }
    private val device = FakeDevice()
    private val llm = ScriptedLlm()

    /** Outil sensible factice qui compte ses exécutions (préfigure l'appel téléphonique). */
    private class FakeCallTool : Tool {
        var calls = 0
        override val spec = toolSpec("call_contact", "Appelle un contact") { string("name", "nom") }
        override val risk = Risk.CONFIRM
        override fun confirmationQuestion(args: JsonObject) = "Voulez-vous appeler Paul ?"
        override suspend fun execute(args: JsonObject): ToolOutcome { calls++; return ToolOutcome.ok("J'appelle Paul.") }
    }

    private val callTool = FakeCallTool()
    private fun assistant(provider: fr.assistantvocal.core.llm.LlmProvider? = llm) = Assistant(
        settings, device, provider, ToolRegistry(CoreTools.all(settings, device) + callTool), nowMillis = { 0L },
    )

    @Test fun `il demande l'heure, il entend l'heure sans appel a l'IA`() = runTest {
        val a = assistant()
        assertEquals("Il est 14 heures 30.", a.handle("Quelle heure est-il ?").speech)
        assertEquals("Nous sommes le samedi 17 octobre 2026.", a.handle("on est quel jour").speech)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun `il demande ce que l'assistant sait faire, il entend la liste sans passer par l'IA`() = runTest {
        val a = assistant()
        assertTrue(a.handle("Qu'est-ce que tu sais faire ?").speech.startsWith("Je peux vous aider pour"))
        assertTrue(a.handle("qu'est-ce que tu peux faire").speech.startsWith("Je peux vous aider pour"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun `il demande la batterie en charge`() = runTest {
        device.batteryInfo = BatteryInfo(42, true)
        assertEquals("La batterie est à 42 pour cent, en charge.", assistant().handle("batterie").speech)
    }

    @Test fun `il demande qui es-tu, l'assistant se presente avec son nom`() = runTest {
        val r = assistant().handle("Qui es-tu ?")
        assertEquals("Je suis Orphée, votre assistant vocal. Que puis-je faire pour vous ?", r.speech)
        assertTrue(r.listenAgain)
    }

    @Test fun `il pose une question libre, la reponse IA est nettoyee pour l'oral`() = runTest {
        llm.enqueue(ScriptedLlm.text("**Paris** est la capitale 🇫🇷 :\n- belle ville\n- voir https://fr.wikipedia.org"))
        val r = assistant().handle("quelle est la capitale de la France")
        assertEquals("Paris est la capitale : belle ville voir un lien", r.speech)
        val sys = llm.requests.single().system
        assertTrue(sys.startsWith("Tu es Orphée, l'assistant vocal d'une personne aveugle."))
        assertFalse(sys.contains("[NOM]"))
    }

    @Test fun `l'IA demande un appel, rien ne se passe sans oui`() = runTest {
        val a = assistant()
        llm.enqueue(ScriptedLlm.call("call_contact", buildJsonObject { put("name", "Paul") }))
        val q = a.handle("j'aimerais bien parler à Paul")
        assertEquals(Earcon.CONFIRM, q.earcon)
        assertTrue(q.listenAgain)
        assertEquals("Voulez-vous appeler Paul ?", q.speech)
        assertEquals(0, callTool.calls)

        val no = a.handle("non")
        assertEquals("D'accord, j'annule.", no.speech)
        assertEquals(0, callTool.calls)
    }

    @Test fun `l'IA demande un appel, il dit oui, l'appel part`() = runTest {
        val a = assistant()
        llm.enqueue(ScriptedLlm.call("call_contact", buildJsonObject { put("name", "Paul") }))
        a.handle("j'aimerais bien parler à Paul")
        val yes = a.handle("oui vas-y")
        assertEquals("J'appelle Paul.", yes.speech)
        assertEquals(Earcon.SUCCESS, yes.earcon)
        assertEquals(1, callTool.calls)
    }

    @Test fun `outil sans risque execute puis reponse finale de l'IA`() = runTest {
        llm.enqueue(ScriptedLlm.call("get_phone_status", JsonObject(emptyMap())))
        llm.enqueue(ScriptedLlm.text("Votre batterie est à 76 pour cent."))
        val r = assistant().handle("est-ce que mon téléphone va tenir la journée ?")
        assertEquals("Votre batterie est à 76 pour cent.", r.speech)
        // second appel : contient le résultat de l'outil
        val second = llm.requests[1].messages.last().parts.single() as LlmPart.ToolResult
        assertEquals("get_phone_status", second.name)
        assertEquals("76", second.content["batterie_pourcent"].toString())
    }

    @Test fun `il change le nom a la voix avec confirmation`() = runTest {
        val a = assistant()
        val q = a.handle("change ton nom en Balthazar")
        assertEquals("Voulez-vous que je m'appelle Balthazar ? Répondez oui ou non.", q.speech)
        assertEquals("Orphée", settings.name)
        val ok = a.handle("oui")
        assertEquals("Balthazar", settings.name)
        assertTrue(ok.speech.contains("je m'appelle maintenant Balthazar"))
        assertEquals("Je suis Balthazar, votre assistant vocal. Que puis-je faire pour vous ?", a.handle("qui es-tu").speech)
    }

    @Test fun `hors ligne, une question libre explique ce qui reste possible`() = runTest {
        device.online = false
        val r = assistant().handle("raconte-moi une blague")
        assertTrue(r.speech.startsWith("Je n'ai pas internet"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun `mode prive, rien n'est envoye a l'IA`() = runTest {
        val a = assistant()
        a.handle("active le mode privé")
        val r = a.handle("raconte-moi une blague")
        assertTrue(r.speech.startsWith("Je suis en mode privé"))
        assertTrue(llm.requests.isEmpty())
        assertEquals("Il est 14 heures 30.", a.handle("quelle heure est-il").speech)
    }

    @Test fun `sans IA, une demande ambigue est verifiee par une question`() = runTest {
        device.online = false
        val a = assistant()
        val q = a.handle("dis-moi à quelle heure on mange ce soir avec les enfants")
        assertEquals("Voulez-vous savoir l'heure ?", q.speech)
        assertEquals("Il est 14 heures 30.", a.handle("oui").speech)
    }

    @Test fun `repete redit la derniere phrase`() = runTest {
        val a = assistant()
        a.handle("quelle heure est-il")
        assertEquals("Il est 14 heures 30.", a.handle("répète").speech)
        assertEquals("Il est 14 heures 30.", a.handle("répète").speech)
    }

    @Test fun `stop coupe tout et annule une confirmation`() = runTest {
        val a = assistant()
        a.handle("change ton nom en Balthazar")
        val r = a.handle("stop")
        assertTrue(r.stopAll || r.earcon == Earcon.CANCELLED)
        assertFalse(a.confirmations.hasPending())
        assertEquals("Orphée", settings.name)
    }

    @Test fun `vitesse et verbosite reglables a la voix`() = runTest {
        val a = assistant()
        a.handle("parle plus vite")
        assertEquals(1.25f, settings.speechRate)
        a.handle("sois plus bref")
        assertEquals(Verbosity.BRIEF, settings.verbosity)
        llm.enqueue(ScriptedLlm.text("ok"))
        a.handle("raconte une histoire")
        assertTrue(llm.requests.single().system.contains("une ou deux phrases"))
    }

    @Test fun `historique limite a 6 echanges`() = runTest {
        val a = assistant()
        repeat(10) { a.handle("quelle heure est-il") }
        assertEquals(6, a.historySize())
    }

    @Test fun `quota Gemini epuise, bascule annoncee vers Claude`() = runTest {
        val gemini = ScriptedLlm("gemini", "Gemini", true, ScriptedLlm.fail(FailureKind.QUOTA, "gemini"))
        val claude = ScriptedLlm("claude", "Claude", true, ScriptedLlm.text("Bonjour !", "claude"))
        val usage = UsageCounter(store, { Instant.parse("2026-10-17T12:00:00Z") }) { 100 }
        val fallback = FallbackLlmProvider({ listOf(gemini, claude) }, usage)
        val r = assistant(fallback).handle("raconte-moi une blague")
        assertEquals("Gemini a atteint sa limite, je passe à Claude. Bonjour !", r.speech)
        // Le lendemain matin on ne retente pas Gemini avant la remise à zéro : il est marqué épuisé
        assertTrue(usage.isExhausted("gemini"))
    }

    @Test fun `aucune IA disponible, message clair`() = runTest {
        val gemini = ScriptedLlm("gemini", "Gemini", true, ScriptedLlm.fail(FailureKind.NETWORK, "gemini"))
        val claude = ScriptedLlm("claude", "Claude", false)
        val usage = UsageCounter(store, { Instant.EPOCH }) { 100 }
        val r = assistant(FallbackLlmProvider({ listOf(gemini, claude) }, usage)).handle("raconte une blague")
        assertEquals("Je n'arrive pas à joindre internet. Réessayez dans un moment.", r.speech)
        assertEquals(Earcon.ERROR, r.earcon)
    }
}

class UsageCounterTest {
    @Test fun warnsAt80PercentAndResetsNextDay() {
        var now = Instant.parse("2026-10-17T12:00:00Z")
        val store = InMemoryStore()
        val c = UsageCounter(store, { now }) { 10 }
        val notices = (1..10).map { c.increment("gemini", "Gemini") }
        assertTrue(notices[7]!!.contains("8 requêtes Gemini sur 10"))
        assertTrue(notices[9]!!.contains("limite du jour"))
        assertTrue(c.isExhausted("gemini"))
        // Remise à zéro Gemini : minuit heure du Pacifique = 7 h ou 8 h UTC
        now = Instant.parse("2026-10-18T08:00:00Z")
        assertFalse(c.isExhausted("gemini"))
    }
}
