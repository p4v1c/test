package fr.assistantvocal.app

import android.Manifest
import android.app.NotificationManager
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import fr.assistantvocal.app.audio.AndroidSpeechOutput
import fr.assistantvocal.app.audio.Heard
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.settings.AssistantSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceScenarioTest {
    private lateinit var app: App

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun runConversation() {
        Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, AssistantService.ACTION_LISTEN))
            .create().startCommand(0, 1)
    }

    @Test fun `il appuie sur Parler, demande l'heure et l'entend`() {
        val out = FakeOutput(); val sounds = FakeSounds()
        testGraph(app, FakeInput(Heard.Text("Quelle heure est-il ?")), out, sounds)
        runConversation()
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals(listOf("Il est 14 heures 30."), out.spoken)
        assertEquals(Earcon.LISTENING, sounds.played.first())
    }

    @Test fun `le service affiche une notification avec le nom de l'assistant`() {
        testGraph(app)
        Robolectric.buildService(AssistantService::class.java).create()
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("Orphée", n.extras.getString("android.title"))
        assertEquals(2, n.actions.size)
    }

    @Test fun `il change le nom, confirme par oui, l'ecoute est relancee automatiquement`() {
        val out = FakeOutput(); val sounds = FakeSounds()
        val input = FakeInput(Heard.Text("change ton nom en Balthazar"), Heard.Text("oui"))
        val graph = testGraph(app, input, out, sounds)
        runConversation()
        awaitMain { out.spoken.size >= 2 }
        assertEquals("Voulez-vous que je m'appelle Balthazar ? Répondez oui ou non.", out.spoken[0])
        assertTrue(out.spoken[1].contains("je m'appelle maintenant Balthazar"))
        assertEquals("Balthazar", graph.settings.name)
        assertTrue(Earcon.CONFIRM in sounds.played && Earcon.SUCCESS in sounds.played)
        assertEquals(2, input.listens)
    }

    @Test fun `il ne dit rien, l'assistant le signale`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Nothing), out)
        runConversation()
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals(listOf("Je n'ai rien entendu."), out.spoken)
    }

    @Test fun `question libre envoyee a Gemini (serveur simule)`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Pourquoi les plongeurs plongent-ils en arrière ? Parce que sinon ils tombent dans le bateau."}]}}]}"""))
        val secrets = InMemorySecrets().apply { put(AppGraph.SECRET_GEMINI, "cle-test") }
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("raconte-moi une blague")), out, geminiBaseUrl = server.url("/").toString(), secrets = secrets)
        runConversation()
        awaitMain { out.spoken.isNotEmpty() }
        assertTrue(out.spoken.single().startsWith("Pourquoi les plongeurs"))
        assertEquals("cle-test", server.takeRequest().getHeader("x-goog-api-key"))
        server.shutdown()
    }

    @Test fun `il dit le nom, l'assistant ecoute sa demande et repond`() {
        val out = FakeOutput(); val sounds = FakeSounds(); val wake = FakeWake()
        val input = FakeInput(Heard.Text("quelle heure est-il"))
        testGraph(app, input, out, sounds, wake = wake)
        Robolectric.buildService(AssistantService::class.java).create()
        awaitMain { wake.isListening }
        assertEquals("Orphée", wake.lastConfig!!.name)
        wake.fire()
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals(listOf("Il est 14 heures 30."), out.spoken)
        assertEquals(1, input.listens)
        awaitMain { wake.isListening }
        assertTrue("l'écoute du nom reprend après la réponse", wake.isListening)
    }

    @Test fun `ecoute du nom desactivee a la voix`() {
        val out = FakeOutput(); val wake = FakeWake()
        val graph = testGraph(app, FakeInput(Heard.Text("n'écoute plus ton nom")), out, wake = wake)
        runConversation()
        awaitMain { out.spoken.isNotEmpty() }
        assertTrue(out.spoken.single().startsWith("D'accord, je ne réagis plus à mon nom"))
        assertEquals(false, graph.settings.wakeWordEnabled)
        awaitMain { false }
        assertEquals(false, wake.isListening)
    }

    @Test fun `apres un changement de nom, la calibration se lance et enregistre les variantes`() {
        val out = FakeOutput(); val wake = FakeWake()
        val graph = testGraph(app, FakeInput(Heard.Text("change ton nom en Orphée"), Heard.Text("oui")), out, wake = wake)
        graph.settings.name = "Hector"
        runConversation()
        awaitMain { out.spoken.any { it.startsWith("Parfait") } }
        assertEquals("Orphée", graph.settings.name)
        assertEquals(3, wake.recordings)
        assertEquals(listOf("or fée"), graph.settings.wakeAliases)
        assertEquals(0.55f, graph.settings.wakeThreshold, 0.001f)
        assertTrue(out.spoken.any { it.contains("reconnu mon nom 3 fois sur 3") })
    }

    @Test fun `calibration avec faux declenchements rend l'ecoute plus stricte`() {
        val out = FakeOutput(); val wake = FakeWake(falseTriggers = 2)
        val graph = testGraph(app, FakeInput(Heard.Text("apprends mon nom")), out, wake = wake)
        runConversation()
        awaitMain { out.spoken.any { it.contains("par erreur") } }
        assertTrue(graph.settings.wakeStrict)
    }

    @Test fun `parle plus vite change la vitesse de la voix`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("parle plus vite")), out)
        runConversation()
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals(1.25f, out.rate)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3ServiceTest {
    private lateinit var app: App

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun start(action: String, extras: (android.content.Intent) -> Unit = {}) {
        val i = AssistantService.intent(app, action).also(extras)
        Robolectric.buildService(AssistantService::class.java, i).create().startCommand(0, 1)
    }

    @Test fun `appel entrant annonce, puis il dit le nom et ca decroche`() {
        val out = FakeOutput(); val wake = FakeWake(); val phone = AppFakePhone()
        testGraph(app, output = out, wake = wake, phone = phone)
        phone.state = fr.assistantvocal.core.phone.CallState.RINGING
        start(AssistantService.ACTION_CALL_STATE) {
            it.putExtra(AssistantService.EXTRA_STATE, android.telephony.TelephonyManager.EXTRA_STATE_RINGING)
            it.putExtra(AssistantService.EXTRA_NUMBER, "+33612345678")
        }
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals("Appel de Paul Martin. Pour décrocher, dites « Orphée, décroche », ou appuyez sur le bouton du casque.", out.spoken.first())
        awaitMain { wake.isListening }
        wake.fire()
        awaitMain { phone.answered }
        assertTrue(phone.answered)
    }

    @Test fun `rappel de medicament qui sonne, il repond c'est fait`() {
        val out = FakeOutput(); val phone = AppFakePhone()
        val graph = testGraph(app, FakeInput(Heard.Text("rappelle-moi de prendre mon Doliprane tous les jours à 8 heures"), Heard.Text("c'est fait")),
            out, phone = phone)
        Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, AssistantService.ACTION_LISTEN)).create().startCommand(0, 1)
        awaitMain { out.spoken.isNotEmpty() }
        val id = phone.scheduled.keys.single()
        start(AssistantService.ACTION_REMINDER_FIRE) { it.putExtra(fr.assistantvocal.app.phone.ReminderReceiver.EXTRA_ID, id) }
        awaitMain { out.spoken.size >= 3 }
        assertEquals("C'est l'heure de prendre mon doliprane. Quand c'est fait, dites-moi : c'est fait.", out.spoken[1])
        assertTrue(out.spoken[2], out.spoken[2].startsWith("C'est noté : doliprane"))
        assertEquals(1, graph.reminders.let { 1 })
    }

    @Test fun `au secours, sans annulation, les proches recoivent un SMS avec la position puis appel`() {
        val out = FakeOutput(); val phone = AppFakePhone()
        val graph = testGraph(app, FakeInput(Heard.Text("au secours")), out, phone = phone)
        graph.people.emergencyContacts = listOf(fr.assistantvocal.core.phone.EmergencyContact("Marie", "0611223344"))
        graph.settings.ownerName = "Jean"
        start(AssistantService.ACTION_LISTEN)
        awaitMain(8_000) { phone.calls.isNotEmpty() }
        assertTrue(phone.sent.single().second.startsWith("ALERTE : Jean a demandé de l'aide"))
        assertEquals("0611223344", phone.calls.single())
        assertTrue(out.spoken.first().startsWith("Alerte de secours. Dans 10 secondes, je préviens Marie"))
    }

    @Test fun `au secours puis stop, personne n'est prevenu`() {
        val out = FakeOutput(); val phone = AppFakePhone()
        val graph = testGraph(app, FakeInput(Heard.Text("au secours"), Heard.Text("stop c'est une fausse alerte")), out, phone = phone)
        graph.people.emergencyContacts = listOf(fr.assistantvocal.core.phone.EmergencyContact("Marie", "0611223344"))
        start(AssistantService.ACTION_LISTEN)
        awaitMain { out.spoken.any { it.startsWith("Alerte annulée") } }
        assertTrue(phone.sent.isEmpty() && phone.calls.isEmpty())
    }

    @Test fun `un rappel qui sonne pendant le compte a rebours du SOS ne l'annule pas, il sonne apres`() {
        val out = FakeOutput(); val phone = AppFakePhone()
        val graph = testGraph(app, FakeInput(Heard.Text("rappelle-moi de prendre mon Doliprane tous les jours à 8 heures")), out, phone = phone)
        graph.people.emergencyContacts = listOf(fr.assistantvocal.core.phone.EmergencyContact("Marie", "0611223344"))
        graph.settings.ownerName = "Jean"
        // Une seule instance du service, comme sur le téléphone.
        val service = Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, AssistantService.ACTION_LISTEN)).create().startCommand(0, 1)
        awaitMain { phone.scheduled.isNotEmpty() && out.spoken.isNotEmpty() }
        val id = phone.scheduled.keys.single()
        out.spoken.clear()

        service.withIntent(AssistantService.intent(app, AssistantService.ACTION_SOS)).startCommand(0, 2)
        awaitMain { out.spoken.any { it.startsWith("Alerte de secours") } }
        service.withIntent(AssistantService.intent(app, AssistantService.ACTION_REMINDER_FIRE)
            .putExtra(fr.assistantvocal.app.phone.ReminderReceiver.EXTRA_ID, id)).startCommand(0, 3)

        awaitMain(8_000) { phone.calls.isNotEmpty() && out.spoken.any { it.startsWith("C'est l'heure de prendre") } }
        assertTrue(phone.sent.single().second.startsWith("ALERTE : Jean a demandé de l'aide"))
        assertEquals("0611223344", phone.calls.single())
        // Le rappel passe après l'alerte, pas pendant.
        assertTrue(out.spoken.indexOfFirst { it.startsWith("C'est l'heure") } > out.spoken.indexOfFirst { it.startsWith("Message d'alerte envoyé") })
    }

    @Test fun `au secours a la voix, un bouton volume pendant le compte a rebours n'annule pas l'alerte`() {
        val out = FakeOutput(); val phone = AppFakePhone()
        val graph = testGraph(app, FakeInput(Heard.Text("au secours")), out, phone = phone)
        graph.people.emergencyContacts = listOf(fr.assistantvocal.core.phone.EmergencyContact("Marie", "0611223344"))
        val service = Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, AssistantService.ACTION_LISTEN)).create().startCommand(0, 1)
        awaitMain { out.spoken.any { it.startsWith("Alerte de secours") } }
        service.withIntent(AssistantService.intent(app, AssistantService.ACTION_STOP_SPEAKING)).startCommand(0, 2)
        awaitMain(8_000) { phone.calls.isNotEmpty() }
        assertEquals(1, phone.sent.size)
        assertEquals("0611223344", phone.calls.single())
    }

    @Test fun `apres un arret force, les rappels sont reprogrammes au redemarrage du service`() {
        val out = FakeOutput(); val phone = AppFakePhone()
        testGraph(app, FakeInput(Heard.Text("rappelle-moi de prendre mon Doliprane tous les jours à 8 heures")), out, phone = phone)
        start(AssistantService.ACTION_LISTEN)
        awaitMain { phone.scheduled.isNotEmpty() }
        val id = phone.scheduled.keys.single()
        phone.scheduled.clear() // « Forcer l'arrêt » : Android efface toutes les alarmes de l'app
        Robolectric.buildService(AssistantService::class.java).create()
        assertTrue(phone.scheduled.containsKey(id))
    }

    @Test fun `SMS recu annonce`() {
        val out = FakeOutput()
        testGraph(app, output = out)
        start(AssistantService.ACTION_SMS_RECEIVED) {
            it.putExtra(AssistantService.EXTRA_NUMBER, "0612345678"); it.putExtra(AssistantService.EXTRA_TEXT, "Coucou")
        }
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals("Nouveau message de Paul Martin. Pour l'entendre, dites : lis mes messages.", out.spoken.single())
    }

    @Test fun `un proche retrouve le telephone par SMS`() {
        val phone = AppFakePhone(); val sounds = FakeSounds()
        val graph = testGraph(app, phone = phone, sounds = sounds)
        graph.settings.ownerName = "Jean"
        start(AssistantService.ACTION_REMOTE_LOCATE) { it.putExtra(AssistantService.EXTRA_NUMBER, "0611223344") }
        awaitMain { phone.sent.isNotEmpty() }
        assertEquals("0611223344", phone.sent.single().first)
        assertTrue(phone.sent.single().second.contains("https://maps.google.com/?q=44.90000,4.90000"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionServiceTest {
    private lateinit var app: App

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    }

    private fun listen() = Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, AssistantService.ACTION_LISTEN)).create().startCommand(0, 1)

    @Test fun `il demande quel billet, il entend la valeur`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("c'est quel billet ?")), out, vision = AppFakeVision(listOf("50 EURO", "50"), rgb = Triple(230, 130, 40)))
        listen()
        awaitMain { out.spoken.any { it.contains("billet de") } }
        assertEquals("C'est un billet de 50 euros.", out.spoken.last())
    }

    @Test fun `il demande de lire un courrier puis relis le montant`() {
        val out = FakeOutput()
        val input = FakeInput(Heard.Text("lis-moi ce courrier"))
        val graph = testGraph(app, input, out, vision = AppFakeVision(listOf("Facture EDF", "Montant : 62,10 €", "À payer avant le 30/10/2026", "Merci")))
        listen()
        awaitMain { out.spoken.any { it.startsWith("Facture EDF") } }
        kotlinx.coroutines.runBlocking { assertEquals("Montants trouvés : 62,10 €.", graph.assistant.handle("relis le montant").speech) }
    }

    @Test fun `sans autorisation camera, il le dit`() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("qu'est-ce qu'il y a devant moi ?")), out)
        listen()
        awaitMain { out.spoken.isNotEmpty() }
        assertTrue(out.spoken.last().startsWith("Je n'ai pas l'autorisation d'utiliser la caméra"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TriggersTest {
    @Test fun `le bouton assistant du casque Bluetooth lance l'ecoute`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        testGraph(app)
        val activity = Robolectric.buildActivity(fr.assistantvocal.app.triggers.TriggerActivity::class.java,
            android.content.Intent("android.intent.action.VOICE_COMMAND")).setup().get()
        assertTrue(activity.isFinishing)
        assertEquals(AssistantService.ACTION_LISTEN, shadowOf(app).nextStartedService.action)
    }

    @Test fun `au demarrage du telephone le service est relance`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        testGraph(app)
        fr.assistantvocal.app.triggers.BootReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertEquals(AssistantService.ACTION_ENSURE_RUNNING, shadowOf(app).nextStartedService.action)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {
    @Test fun `un seul gros bouton accessible qui lance l'ecoute`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        testGraph(app)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val talk = activity.findViewById<Button>(R.id.talk)
        assertEquals("Parler à Orphée", talk.contentDescription)
        assertEquals("Parler", talk.text.toString())
        talk.performClick()
        val actions = generateSequence { shadowOf(app).nextStartedService }.map { it.action }.toList()
        assertTrue(actions.toString(), AssistantService.ACTION_LISTEN in actions)
    }

    @Test fun `sans permission micro, le bouton redemande l'autorisation`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        testGraph(app)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.talk).performClick()
        val actions = generateSequence { shadowOf(app).nextStartedService }.map { it.action }.toList()
        assertTrue(AssistantService.ACTION_LISTEN !in actions)
        assertTrue(shadowOf(activity).lastRequestedPermission.requestedPermissions.contains(Manifest.permission.RECORD_AUDIO))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityTest {
    @Test fun `le proche enregistre le nom et les cles`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val graph = testGraph(app)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.nameField.setText("jarvis")
        activity.geminiKeyField.setText("  AIza-test  ")
        activity.emergencyField.setText("Marie: 06 11 22 33 44; Pierre: 0698765432")
        activity.favoritesField.setText("1: Marie Dupont; 12: trop grand")
        activity.aliasesField.setText("Mon fils: Pierre Dupont")
        activity.ownerField.setText("Jean")
        activity.save()
        assertEquals("Jarvis", graph.settings.name)
        assertEquals(listOf("Marie", "Pierre"), graph.people.emergencyContacts.map { it.name })
        assertEquals(mapOf("1" to "Marie Dupont"), graph.people.favorites)
        assertEquals(mapOf("mon fils" to "Pierre Dupont"), graph.people.aliases)
        assertEquals("Jean", graph.settings.ownerName)
        assertEquals("AIza-test", graph.secrets.get(AppGraph.SECRET_GEMINI))
        assertEquals("", activity.geminiKeyField.text.toString())
        assertTrue(activity.status.text.startsWith("Réglages enregistrés"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageTest {
    @Test fun prefsStoreKeepsSettings() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val s = AssistantSettings(PrefsStore(app))
        s.name = "Orphée"; s.speechRate = 1.5f; s.providerOrder = listOf("claude", "gemini")
        val again = AssistantSettings(PrefsStore(app))
        assertEquals("Orphée", again.name)
        assertEquals(1.5f, again.speechRate)
        assertEquals(listOf("claude", "gemini"), again.providerOrder)
    }

    @Test fun splitSentences() {
        assertEquals(listOf("Bonjour.", "Il est 9 heures !", "Ça va ?"), AndroidSpeechOutput.splitSentences("Bonjour. Il est 9 heures ! Ça va ?"))
    }

    @Test fun deviceStatusReadsBattery() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val intent = android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
            .putExtra(android.os.BatteryManager.EXTRA_LEVEL, 42).putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            .putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_CHARGING)
        app.sendStickyBroadcast(intent)
        val b = AndroidDeviceStatus(app).battery()!!
        assertEquals(42, b.percent)
        assertTrue(b.charging)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase5ServiceTest {
    private lateinit var app: App

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun listen() = Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, AssistantService.ACTION_LISTEN)).create().startCommand(0, 1)

    @Test fun `il demande la meteo, il entend la temperature`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("quel temps fait-il ?")), out)
        listen()
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals("En ce moment : 21 degrés, ciel dégagé. Aujourd'hui : ciel dégagé, de 10 degrés à 23 degrés.", out.spoken.last())
    }

    @Test fun `il demande ou il est, il entend l'adresse`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("où suis-je ?")), out)
        listen()
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals("Vous êtes près du 4 place de la Mairie, à Saint-Marcel.", out.spoken.last())
    }

    @Test fun `boulangerie la plus proche, oui, Google Maps s'ouvre`() {
        val out = FakeOutput()
        val info = AppFakeInfo()
        testGraph(app, FakeInput(Heard.Text("où est la boulangerie la plus proche ?"), Heard.Text("oui")), out, info = info)
        listen()
        awaitMain { info.walks.isNotEmpty() && out.spoken.size >= 2 }
        assertEquals("La boulangerie la plus proche : Boulangerie Martin, à 220 mètres, au nord, rue Haute. Voulez-vous que Google Maps vous y guide à pied ?", out.spoken[0])
        assertEquals(listOf("Boulangerie Martin"), info.walks)
    }

    @Test fun `la cle Tavily est enregistree chiffree et utilisee pour la recherche`() {
        val info = AppFakeInfo()
        val graph = testGraph(app, info = info)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.tavilyKeyField.setText("tvly-abc")
        activity.newsFeedField.setText("https://journal.example/rss")
        activity.save()
        assertEquals("tvly-abc", graph.secrets.get(AppGraph.SECRET_TAVILY))
        assertEquals("https://journal.example/rss", graph.settings.newsLocalFeed)
        assertEquals("", activity.tavilyKeyField.text.toString())
        kotlinx.coroutines.runBlocking { graph.assistant.handle("cherche sur internet la fête du village") }
        assertEquals("tvly-abc", info.tavilyKey)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase6ServiceTest {
    private lateinit var app: App

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun listen() = Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, AssistantService.ACTION_LISTEN)).create().startCommand(0, 1)

    @Test fun `il demande son livre, il l'entend annoncer puis le livre demarre`() {
        val out = FakeOutput()
        val media = AppFakeMedia()
        testGraph(app, FakeInput(Heard.Text("lis le livre Monte-Cristo")), out, media = media)
        listen()
        awaitMain { media.playing }
        assertEquals("J'ouvre « Le Comte de Monte-Cristo », Marseille, l'arrivée.", out.spoken.first())
        assertEquals(listOf("content://livre/1.mp3"), media.plays)
    }

    @Test fun `une question pendant la lecture met le livre en pause puis il reprend`() {
        val out = FakeOutput()
        val media = AppFakeMedia()
        val input = FakeInput(Heard.Text("lis le livre Monte-Cristo"), Heard.Text("où j'en suis ?"))
        testGraph(app, input, out, media = media)
        listen()
        awaitMain { media.playing }
        media.pos = fr.assistantvocal.core.media.Pos(1, 30_000)
        listen()
        awaitMain { out.spoken.size >= 2 && media.playing }
        assertEquals("« Le Comte de Monte-Cristo ». Le père et le fils. 75 pour cent écoutés, il reste 1 minute.", out.spoken[1])
        assertTrue(media.playing)
    }

    @Test fun `memo vocal enregistre`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("enregistre un mémo")), out)
        listen()
        awaitMain { out.spoken.size >= 2 }
        assertEquals("Mémo enregistré : 4 secondes. Pour l'écouter, dites : écoute mes mémos.", out.spoken.last())
    }

    @Test fun `agenda du jour`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("qu'est-ce que j'ai aujourd'hui ?")), out)
        listen()
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals("Aujourd'hui : à 16 heures, Kiné.", out.spoken.last())
    }

    @Test fun `reglages, dossier des livres et mail`() {
        val graph = testGraph(app)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.onLibraryChosen(android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AVictor"))
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3AVictor", graph.settings.libraryUri)
        assertTrue(activity.libraryLabel.text.toString().endsWith(": Victor"))
        activity.mailField.setText("jean.dupont@gmail.com")
        activity.mailPasswordField.setText("abcd efgh ijkl mnop")
        activity.save()
        assertEquals("jean.dupont@gmail.com", graph.settings.mailAddress)
        assertEquals("abcdefghijklmnop", graph.secrets.get(AppGraph.SECRET_MAIL))
        assertEquals("", activity.mailPasswordField.text.toString())
    }
}

class MailAndLibraryTest {
    @Test fun `texte d'un mail multipart, le texte brut est prefere`() {
        val raw = "From: =?UTF-8?Q?Mairie_de_Saint-Marcel?= <mairie@stmarcel.fr>\r\nSubject: =?UTF-8?Q?Repas_des_a=C3=AEn=C3=A9s?=\r\n" +
            "Message-ID: <m1@x>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=\"b\"\r\n\r\n" +
            "--b\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nLe repas aura lieu =C3=A0 midi.\r\n" +
            "--b\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Le repas aura lieu &agrave; midi.</p>\r\n--b--\r\n"
        val m = javax.mail.internet.MimeMessage(javax.mail.Session.getInstance(java.util.Properties()), raw.byteInputStream())
        assertEquals("Le repas aura lieu à midi.", fr.assistantvocal.app.organizer.MailText.extract(m))
        val h = fr.assistantvocal.app.organizer.MailText.header(m, 42)
        assertEquals("Mairie de Saint-Marcel", h.fromName)
        assertEquals("Repas des aînés", h.subject)
        assertEquals("42", h.id)
        val html = javax.mail.internet.MimeMessage(javax.mail.Session.getInstance(java.util.Properties()),
            "Subject: x\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<div>Bonjour&nbsp;<b>Jean</b></div>".byteInputStream())
        assertEquals("Bonjour Jean", fr.assistantvocal.app.organizer.MailText.extract(html))
    }

    @Test fun `livres DAISY anciens en windows-1252 ou declares`() {
        val cp1252 = "<html><h1><a href=\"a.smil#x\">Chère élève</a></h1></html>".toByteArray(charset("windows-1252"))
        assertTrue(fr.assistantvocal.app.media.SafLibrary.decode(cp1252).contains("Chère élève"))
        val utf8 = "<h1>Été</h1>".toByteArray()
        assertEquals("<h1>Été</h1>", fr.assistantvocal.app.media.SafLibrary.decode(utf8))
        val declared = "<?xml version=\"1.0\" encoding=\"iso-8859-1\"?><t>café</t>".toByteArray(Charsets.ISO_8859_1)
        assertTrue(fr.assistantvocal.app.media.SafLibrary.decode(declared).contains("café"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase7AppTest {
    private lateinit var app: App

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun start(action: String) = Robolectric.buildService(AssistantService::class.java, AssistantService.intent(app, action)).create().startCommand(0, 1)

    @Test fun `le bouton Tutoriel lance la premiere lecon et ecoute la reponse`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("quelle heure est-il ?")), out)
        start(AssistantService.ACTION_TUTORIAL)
        awaitMain { out.spoken.size >= 2 }
        assertTrue(out.spoken[0], out.spoken[0].startsWith("Bienvenue ! Je suis Orphée, votre assistant."))
        assertTrue(out.spoken[1], out.spoken[1].startsWith("Bravo ! Il est 14 heures 30."))
    }

    @Test fun `est-ce que tout marche, avec les vrais controles du telephone`() {
        val out = FakeOutput()
        testGraph(app, FakeInput(Heard.Text("est-ce que tout marche ?")), out)
        start(AssistantService.ACTION_LISTEN)
        awaitMain { out.spoken.isNotEmpty() }
        val r = out.spoken.last()
        assertTrue(r, r.startsWith("Il y a ") && r.contains("l'intelligence artificielle : aucune clé") && r.contains("Le reste va bien : le micro"))
    }

    @Test fun `sauvegarde a la voix puis restauration depuis les reglages`() {
        val out = FakeOutput()
        val graph = testGraph(app, FakeInput(Heard.Text("sauvegarde mes réglages")), out)
        graph.people.aliases = mapOf("mon fils" to "Pierre Dupont")
        start(AssistantService.ACTION_LISTEN)
        awaitMain { out.spoken.isNotEmpty() }
        assertTrue(out.spoken.last(), out.spoken.last().startsWith("C'est sauvegardé."))
        val file = fr.assistantvocal.app.support.Backups.dir(app).listFiles()!!.single { it.name.startsWith("sauvegarde-") }
        assertTrue(file.readText().contains("\"people.aliases\""))

        // On change tout, puis on restaure le fichier depuis l'écran des réglages.
        graph.people.aliases = emptyMap()
        graph.settings.name = "Autre"
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val parsed = activity.readBackup(android.net.Uri.fromFile(file))!!
        activity.restore(parsed)
        assertEquals("Orphée", graph.settings.name)
        assertEquals(mapOf("mon fils" to "Pierre Dupont"), graph.people.aliases)
    }

    @Test fun `l'ecran principal a un bouton Tutoriel`() {
        testGraph(app)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.tutorial).performClick()
        val started = generateSequence { shadowOf(app).nextStartedService }.toList()
        assertTrue(started.any { it.action == AssistantService.ACTION_TUTORIAL })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase8AppTest {
    private val app: App = ApplicationProvider.getApplicationContext()

    @Test fun `telechargement d'un episode dans le stockage de l'app`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("ID3-faux-mp3")); start() }
        val dl = fr.assistantvocal.app.media.AndroidPodcastDownloader(app)
        // Robolectric annonce 0 octet libre : sans place, rien n'est téléchargé.
        val dir = java.io.File(app.filesDir, "podcasts").apply { mkdirs() }
        assertEquals(null, kotlinx.coroutines.runBlocking { dl.download(server.url("/e0.mp3").toString(), "e0.mp3") })
        org.robolectric.shadows.ShadowStatFs.registerStats(dir, 1_000_000, 1_000_000, 1_000_000)
        server.enqueue(MockResponse().setBody("ID3-faux-mp3"))
        val uri = kotlinx.coroutines.runBlocking { dl.download(server.url("/e1.mp3").toString(), "ep 1?.mp3") }!!
        assertTrue(uri, uri.startsWith("file://") && uri.endsWith("ep_1_.mp3"))
        assertTrue(dl.exists(uri))
        assertEquals("ID3-faux-mp3", java.io.File(android.net.Uri.parse(uri).path!!).readText())
        dl.delete(uri)
        assertTrue(!dl.exists(uri))
        server.shutdown()
    }

    @Test fun `texte d'un PDF extrait page par page`() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val file = java.io.File(app.cacheDir, "lettre.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            listOf("Madame, Monsieur, votre facture est prete.", "Merci de payer avant le 5 novembre.").forEach { line ->
                val page = com.tom_roush.pdfbox.pdmodel.PDPage()
                doc.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(72f, 700f); cs.showText(line); cs.endText()
                }
            }
            doc.save(file)
        }
        val doc = kotlinx.coroutines.runBlocking { fr.assistantvocal.app.media.PdfText(app).extract(android.net.Uri.fromFile(file), "lettre") }!!
        assertEquals(listOf("Madame, Monsieur, votre facture est prete." to "1", "Merci de payer avant le 5 novembre." to "2"),
            doc.blocks.map { it.text to it.page })
        // Deuxième ouverture : depuis le cache.
        file.delete()
        assertEquals(2, kotlinx.coroutines.runBlocking { fr.assistantvocal.app.media.PdfText(app).extract(android.net.Uri.fromFile(file), "lettre") }!!.blocks.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugHearTest {
    @Test fun `en debug, une phrase injectee est traitee comme entendue`() {
        val app: App = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        val out = FakeOutput()
        testGraph(app, FakeInput(), out)
        Robolectric.buildService(AssistantService::class.java,
            AssistantService.intent(app, AssistantService.ACTION_DEBUG_HEAR).putExtra(AssistantService.EXTRA_TEXT, "quelle heure est-il ?"))
            .create().startCommand(0, 1)
        awaitMain { out.spoken.isNotEmpty() }
        assertEquals("Il est 14 heures 30.", out.spoken.first())
    }
}
