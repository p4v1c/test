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
        activity.save()
        assertEquals("Jarvis", graph.settings.name)
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
