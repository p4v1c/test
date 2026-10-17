package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.assistant.FollowUp
import fr.assistantvocal.core.phone.AppNotification
import fr.assistantvocal.core.phone.CallLogEntry
import fr.assistantvocal.core.phone.CallLogSource
import fr.assistantvocal.core.phone.CallState
import fr.assistantvocal.core.phone.CallType
import fr.assistantvocal.core.phone.Contact
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.ContactsSource
import fr.assistantvocal.core.phone.EmergencyContact
import fr.assistantvocal.core.phone.GeoPoint
import fr.assistantvocal.core.phone.LocationSource
import fr.assistantvocal.core.phone.NotificationSource
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.phone.PhoneActions
import fr.assistantvocal.core.phone.PhoneNumber
import fr.assistantvocal.core.phone.ScamDetector
import fr.assistantvocal.core.phone.SmsMessage
import fr.assistantvocal.core.phone.SmsSender
import fr.assistantvocal.core.phone.SmsSource
import fr.assistantvocal.core.reminders.AlarmScheduler
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.ReminderKind
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.safety.DeviceAlerts
import fr.assistantvocal.core.safety.RemoteCommands
import fr.assistantvocal.core.safety.SosFlow
import fr.assistantvocal.core.safety.SosUi
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.skills.CommunicationSkill
import fr.assistantvocal.core.skills.ReminderSkill
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Téléphone simulé. */
class FakePhone : PhoneActions, ContactsSource, CallLogSource, SmsSource, SmsSender, NotificationSource, AlarmScheduler, LocationSource {
    val contacts = mutableListOf(
        Contact("1", "Pierre Dupont", listOf(PhoneNumber("06 11 22 33 44", "mobile"), PhoneNumber("04 75 00 00 00", "domicile"))),
        Contact("2", "Paul Martin", listOf(PhoneNumber("0612345678", "mobile"))),
        Contact("3", "Paul Durand", listOf(PhoneNumber("0698765432", "mobile"))),
        Contact("4", "Docteur Lefèvre", listOf(PhoneNumber("0475123456", "travail"))),
        Contact("5", "Jérôme Petit", listOf(PhoneNumber("0700000001", "mobile"))),
    )
    val calls = mutableListOf<Pair<String, Boolean>>()
    val sent = mutableListOf<Pair<String, String>>()
    var sendWorks = true
    var state = CallState.IDLE
    var answered = false
    var hungUp = false
    var speaker: Boolean? = null
    val log = mutableListOf<CallLogEntry>()
    val inboxMsgs = mutableListOf<SmsMessage>()
    val notifs = mutableListOf<AppNotification>()
    val replies = mutableListOf<Pair<String, String>>()
    val scheduled = mutableMapOf<Int, Long>()
    var point: GeoPoint? = GeoPoint(44.9333, 4.8917, 12f)

    override fun all() = contacts
    override fun call(number: String, speaker: Boolean): Boolean { calls += number to speaker; return true }
    override fun answer(): Boolean { answered = true; state = CallState.OFFHOOK; return true }
    override fun hangUp(): Boolean { hungUp = true; state = CallState.IDLE; return true }
    override fun setSpeaker(on: Boolean): Boolean { speaker = on; return true }
    override fun state() = state
    override fun recent(limit: Int) = log.take(limit)
    override fun inbox(limit: Int) = inboxMsgs.take(limit)
    override suspend fun send(number: String, text: String): Boolean { if (sendWorks) sent += number to text; return sendWorks }
    override fun recent(): List<AppNotification> = notifs
    override fun reply(key: String, text: String): Boolean { replies += key to text; return true }
    override fun schedule(id: Int, atEpochMs: Long) { scheduled[id] = atEpochMs }
    override fun cancel(id: Int) { scheduled.remove(id) }
    override suspend fun current(timeoutMs: Long) = point
}

class Phase3ScenarioTest {
    private val store = InMemoryStore()
    private val settings = AssistantSettings(store).apply { name = "Hector"; ownerName = "Jean" }
    private val device = FakeDevice(time = ZonedDateTime.of(2026, 10, 17, 14, 30, 0, 0, ZoneId.of("Europe/Paris")))
    private val phone = FakePhone()
    private val people = PeopleStore(store)
    private val dialog = Dialog { 0L }
    private val directory = ContactDirectory(phone, people)
    private val comms = CommunicationSkill(directory, people, phone, phone, phone, phone, phone, store, device, dialog)
    private val reminders = ReminderSkill(ReminderStore(store), MedicationLog(store), phone, store, device, dialog)
    private val llm = ScriptedLlm()
    private val a = Assistant(settings, device, llm, ToolRegistry(CoreTools.all(settings, device) + CoreTools.phase3()),
        nowMillis = { 0L }, skills = Skills(dialog, comms, reminders))

    private suspend fun say(t: String) = a.handle(t)

    // ------------------------------------------------------------------ appels

    @Test fun `appelle mon fils, apres avoir appris qui c'est`() = runTest {
        assertTrue(say("Pierre Dupont, c'est mon fils").speech.contains("« mon fils », c'est Pierre Dupont"))
        val q = say("appelle mon fils")
        assertEquals("Appeler Pierre Dupont sur son portable ? Répondez oui ou non.", q.speech)
        assertTrue(phone.calls.isEmpty())
        assertEquals("J'appelle Pierre Dupont sur son portable.", say("oui").speech)
        assertEquals("06 11 22 33 44", phone.calls.single().first)
    }

    @Test fun `deux Paul, l'assistant demande lequel et n'appelle pas au hasard`() = runTest {
        val q = say("appelle Paul")
        assertEquals("J'ai trouvé Paul Martin et Paul Durand. Lequel voulez-vous appeler ?", q.speech)
        val c = say("Durand")
        assertEquals("Appeler Paul Durand sur son portable ? Répondez oui ou non.", c.speech)
        say("non")
        assertTrue(phone.calls.isEmpty())
    }

    @Test fun `appel du fixe et nom approche`() = runTest {
        assertTrue(say("appelle Pierre Dupont à la maison").speech.startsWith("Appeler Pierre Dupont sur son numéro domicile"))
        say("oui")
        assertEquals("04 75 00 00 00", phone.calls.single().first)
        assertTrue(say("appelle Gérôme Petit").speech.startsWith("Appeler Jérôme Petit"))
    }

    @Test fun `contact inconnu`() = runTest {
        assertTrue(say("appelle Gaston").speech.startsWith("Je ne trouve pas « gaston »"))
    }

    @Test fun `appel d'un numero dicte`() = runTest {
        assertEquals("Appeler le 06 12 34 56 78 ? Répondez oui ou non.".replace("le 06", "Paul Martin, au 06"), say("appelle le 06 12 34 56 78").speech)
    }

    @Test fun `qui m'a appele puis rappelle-le`() = runTest {
        val t = device.time
        phone.log += CallLogEntry("0612345678", "Paul Martin", CallType.MISSED, t.minusHours(2), 0)
        phone.log += CallLogEntry("0611111111", null, CallType.MISSED, t.minusDays(1).withHour(18).withMinute(0), 0)
        val r = say("qui m'a appelé ?")
        assertEquals("Vous avez 2 appels manqués : Paul Martin, aujourd'hui à 12 heures 30, puis le 06 11 11 11 11, hier à 18 heures. Pour rappeler le dernier, dites : rappelle-le.", r.speech)
        assertEquals("Appeler Paul Martin ? Répondez oui ou non.", say("rappelle-le").speech)
    }

    @Test fun `decrocher et raccrocher a la voix`() = runTest {
        phone.state = CallState.RINGING
        say("décroche")
        assertTrue(phone.answered)
        say("raccroche")
        assertTrue(phone.hungUp)
        assertEquals("Il n'y a pas d'appel en cours.", say("raccroche").speech)
    }

    @Test fun `annonce d'un appel entrant`() {
        assertEquals("Appel de Paul Martin.", comms.announceIncomingCall("+33612345678"))
        assertTrue(comms.announceIncomingCall("0899999999").startsWith("Appel d'un numéro inconnu, le 08 99 99 99 99"))
        assertEquals("Appel d'un numéro masqué.", comms.announceIncomingCall(null))
    }

    // ------------------------------------------------------------------ SMS

    @Test fun `lire les nouveaux messages avec alerte arnaque`() = runTest {
        val t = device.time
        phone.inboxMsgs += SmsMessage(1, "0612345678", null, "Je passe ce soir vers 18h", t.minusHours(1))
        phone.inboxMsgs += SmsMessage(2, "38100", null, "Votre colis est en attente, payez les frais de livraison : https://colis-fr.top/x", t.minusMinutes(10))
        val r = say("lis mes messages")
        assertTrue(r.speech, r.speech.startsWith("Vous avez 2 nouveaux messages. Premier message, de Paul Martin, aujourd'hui à 13 heures 30 : Je passe ce soir vers 18h."))
        assertTrue(r.speech.contains("Attention, ce message ressemble à une arnaque : il contient un lien, il parle d'un colis"))
        assertEquals("Vous n'avez pas de nouveau message. Pour réentendre le dernier, dites : lis mon dernier message.", say("lis mes messages").speech)
    }

    @Test fun `envoyer un SMS en une phrase, relu puis envoye`() = runTest {
        val q = say("envoie un message à Pierre Dupont pour lui dire que j'arrive dans dix minutes")
        assertEquals("Je vais envoyer à Pierre Dupont : « J'arrive dans dix minutes. » J'envoie ? Répondez oui ou non.", q.speech)
        assertTrue(phone.sent.isEmpty())
        assertEquals("Message envoyé à Pierre Dupont.", say("oui").speech)
        assertEquals("06 11 22 33 44" to "J'arrive dans dix minutes.", phone.sent.single())
    }

    @Test fun `envoyer un SMS en deux temps, et echec reseau annonce`() = runTest {
        phone.sendWorks = false
        assertEquals("Que voulez-vous dire à Paul Martin ?", say("écris à Paul Martin").speech)
        assertTrue(say("je serai en retard").speech.startsWith("Je vais envoyer à Paul Martin : « Je serai en retard. »"))
        assertEquals("Le message n'est pas parti. Vérifiez le réseau, puis redemandez-moi.", say("oui").speech)
    }

    @Test fun `repondre au dernier message lu`() = runTest {
        phone.inboxMsgs += SmsMessage(1, "0612345678", "Paul Martin", "Tu viens dimanche ?", device.time.minusHours(1))
        say("lis mes messages")
        assertTrue(say("réponds que oui avec plaisir").speech.startsWith("Je vais envoyer à Paul Martin : « Oui avec plaisir. »"))
    }

    @Test fun `annuler pendant la dictee`() = runTest {
        say("envoie un SMS à Paul Martin")
        assertEquals("D'accord, j'annule.", say("annule").speech)
        assertTrue(phone.sent.isEmpty())
    }

    @Test fun `notifications lues et reponse rapide`() = runTest {
        phone.notifs += AppNotification("k1", "Gmail", "com.google.android.gm", "La Poste", "Votre recommandé est disponible", device.time.minusMinutes(5), canReply = false)
        phone.notifs += AppNotification("k2", "Meet", "com.google.android.apps.tachyon", "Marie", "Tu es dispo ce soir ?", device.time.minusMinutes(2), canReply = true)
        val r = say("lis mes notifications")
        assertTrue(r.speech, r.speech.startsWith("Vous avez 2 notifications. Meet, aujourd'hui à 14 heures 28 : Marie. Tu es dispo ce soir ?"))
        say("réponds")
        say("oui à 20 heures")
        say("oui")
        assertEquals("k2" to "oui à 20 heures", phone.replies.single())
    }

    // ------------------------------------------------------------------ rappels

    @Test fun `reveil, minuteur nomme et liste`() = runTest {
        assertEquals("Réveil réglé demain à 7 heures.", say("réveille-moi demain à 7 heures").speech)
        assertEquals("Minuteur des pâtes de 10 minutes, c'est parti.", say("mets un minuteur de 10 minutes pour les pâtes").speech)
        assertEquals("Le minuteur des pâtes : il reste 10 minutes.", say("combien de temps reste-t-il sur le minuteur ?").speech)
        // La phrase du README, sans le mot « minuteur ».
        assertEquals("Le minuteur des pâtes : il reste 10 minutes.", say("combien de temps reste-t-il ?").speech)
        assertEquals("Le minuteur des pâtes : il reste 10 minutes.", say("il reste combien de temps").speech)
        assertEquals(2, phone.scheduled.size)
        val list = say("quels sont mes rappels ?").speech
        assertTrue(list, list.startsWith("Vous avez 2 rappels : Minuteur des pâtes, aujourd'hui à 14 heures 40. Réveil, demain à 7 heures."))
    }

    @Test fun `rappel ponctuel puis annonce au bon moment`() = runTest {
        val r = say("rappelle-moi d'appeler le médecin demain à 10 heures")
        assertEquals("D'accord, je vous rappellerai demain à 10 heures : appeler le médecin.", r.speech)
        val (id, at) = phone.scheduled.entries.single().toPair()
        assertEquals(ZonedDateTime.of(2026, 10, 18, 10, 0, 0, 0, device.time.zone).toInstant().toEpochMilli(), at)
        device.time = device.time.plusDays(1).withHour(10).withMinute(0)
        val fire = reminders.onFire(id)!!
        assertEquals("Rappel : Appeler le médecin.", fire.speech)
        assertNull("rappel ponctuel supprimé", reminders.onFire(id))
    }

    @Test fun `medicament quotidien, rappel, confirmation et journal`() = runTest {
        val r = say("rappelle-moi de prendre mon Doliprane tous les jours à 8 heures et à 20 heures")
        assertEquals("D'accord, je vous rappellerai tous les jours à 8 heures et à 20 heures : prendre mon doliprane. Quand vous l'aurez pris, dites-moi simplement : c'est fait.", r.speech)
        val id = phone.scheduled.keys.single()
        device.time = device.time.withHour(20).withMinute(0)
        val fire = reminders.onFire(id)!!
        assertEquals(ReminderKind.MEDICATION, fire.kind)
        assertTrue("relance programmée", ReminderSkill.followUpId(id) in phone.scheduled)
        assertTrue("prochaine prise programmée", phone.scheduled[id]!! > device.time.toInstant().toEpochMilli())
        device.time = device.time.withMinute(5)
        assertEquals("C'est noté : doliprane, à 20 heures 5.", say("c'est fait").speech)
        assertFalse(ReminderSkill.followUpId(id) in phone.scheduled)
        assertEquals("Oui, vous avez pris doliprane aujourd'hui à 20 heures 5.", say("est-ce que j'ai pris mon Doliprane ?").speech)
    }

    @Test fun `question medicament sans prise notee`() = runTest {
        assertTrue(say("ai-je pris mon médicament ce matin ?").speech.startsWith("Je n'ai rien noté aujourd'hui"))
    }

    @Test fun `reprogrammation apres redemarrage`() = runTest {
        say("rappelle-moi d'appeler le médecin demain à 10 heures")
        phone.scheduled.clear()
        reminders.rescheduleAll()
        assertEquals(1, phone.scheduled.size)
    }

    // ------------------------------------------------------------------ IA -> compétences

    @Test fun `l'IA demande un appel, la competence locale confirme`() = runTest {
        llm.enqueue(ScriptedLlm.call("call_contact", buildJsonObject { put("name", "Paul Martin") }))
        val r = say("j'aimerais bien parler à Paul Martin tout à l'heure si possible maintenant")
        assertEquals("Appeler Paul Martin sur son portable ? Répondez oui ou non.", r.speech)
        assertTrue(phone.calls.isEmpty())
    }

    // ------------------------------------------------------------------ sécurité

    @Test fun `au secours declenche le deroule SOS`() = runTest {
        assertEquals(FollowUp.SOS, say("au secours").followUp)
        assertEquals(FollowUp.SOS, say("Hector je suis tombé").followUp)
    }

    private class ScriptUi(private val cancel: Boolean) : SosUi {
        val said = mutableListOf<String>()
        override suspend fun say(text: String) { said += text }
        override suspend fun alertSound() {}
        override suspend fun cancelledWithin(ms: Long) = cancel
    }

    @Test fun `SOS annule pendant les 10 secondes, personne n'est prevenu`() = runTest {
        people.emergencyContacts = listOf(EmergencyContact("Marie", "0611223344"))
        val ui = ScriptUi(cancel = true)
        val out = SosFlow(people, phone, phone, phone, ui, { "Jean" }).run()
        assertTrue(out.cancelled)
        assertTrue(phone.sent.isEmpty() && phone.calls.isEmpty())
        assertEquals("Alerte annulée, je ne préviens personne.", ui.said.last())
    }

    @Test fun `SOS, SMS avec position a tous les proches puis appel du premier`() = runTest {
        people.emergencyContacts = listOf(EmergencyContact("Marie", "0611223344"), EmergencyContact("Pierre", "0611223355"))
        val ui = ScriptUi(cancel = false)
        val out = SosFlow(people, phone, phone, phone, ui, { "Jean" }).run()
        assertEquals(2, out.smsSent)
        assertTrue(phone.sent[0].second.contains("ALERTE : Jean a demandé de l'aide"))
        assertTrue(phone.sent[0].second.contains("https://maps.google.com/?q=44.93330,4.89170"))
        assertEquals("0611223344" to true, phone.calls.single())
        assertEquals("Message d'alerte envoyé à Marie et Pierre. J'appelle Marie.", ui.said.last())
    }

    @Test fun `SOS sans contact d'urgence, appel du 112`() = runTest {
        val out = SosFlow(people, phone, phone, phone, ScriptUi(false), { "Jean" }).run()
        assertEquals("112", out.called)
    }

    @Test fun `alertes batterie et reseau sans repetition`() {
        var now = 0L
        val alerts = DeviceAlerts { now }
        assertNull(alerts.onBattery(50, false))
        assertTrue(alerts.onBattery(20, false)!!.startsWith("Batterie faible : 20"))
        assertNull(alerts.onBattery(19, false))
        assertTrue(alerts.onBattery(9, false)!!.startsWith("Batterie faible : 9"))
        assertTrue(alerts.onBattery(9, true)!!.startsWith("Le téléphone est en charge"))
        assertNull(alerts.onNetwork(true))
        assertNull(alerts.onNetwork(false))
        now = 10_000; assertNull(alerts.tick())
        now = 40_000; assertTrue(alerts.tick()!!.startsWith("Il n'y a plus d'internet"))
        assertNull(alerts.tick())
        assertEquals("Internet est revenu.", alerts.onNetwork(true))
    }

    @Test fun `retrouver le telephone par SMS d'un proche avec le mot secret`() {
        people.emergencyContacts = listOf(EmergencyContact("Marie", "0611223344"))
        assertEquals(RemoteCommands.Command.LOCATE, RemoteCommands.parse("Où es-tu tournesol ?", "+33611223344", people, "tournesol"))
        assertNull("inconnu", RemoteCommands.parse("Où es-tu tournesol ?", "0699999999", people, "tournesol"))
        assertNull("sans le mot secret", RemoteCommands.parse("Où es-tu ?", "0611223344", people, "tournesol"))
        assertNull("désactivé", RemoteCommands.parse("Où es-tu tournesol ?", "0611223344", people, ""))
    }

    @Test fun `detecteur d'arnaque`() {
        assertTrue(ScamDetector.check("Bonjour papa, j'ai changé de numéro, peux-tu m'envoyer un virement ?", false).suspicious)
        assertTrue(ScamDetector.check("Vos droits CPF expirent, activez-les sur cpf-droits.info", false).suspicious)
        assertFalse(ScamDetector.check("Je t'ai déposé le colis chez la voisine", true).suspicious)
        assertFalse(ScamDetector.check("On mange à quelle heure dimanche ?", false).suspicious)
    }

    @Test fun `le nouveau nom d'un alias s'ajoute au routeur`() = runTest {
        assertEquals(Earcon.ERROR, say("Gaston c'est mon voisin").earcon)
    }
}
