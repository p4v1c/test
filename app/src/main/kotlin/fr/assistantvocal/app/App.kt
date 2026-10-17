package fr.assistantvocal.app

import android.app.Application
import android.content.Context
import fr.assistantvocal.app.audio.AndroidSpeechInput
import fr.assistantvocal.app.audio.AndroidSpeechOutput
import fr.assistantvocal.app.audio.Earcons
import fr.assistantvocal.app.audio.SpeechInput
import fr.assistantvocal.app.audio.SpeechOutput
import fr.assistantvocal.app.audio.Sounds
import fr.assistantvocal.app.wake.VoskWakeEngine
import fr.assistantvocal.app.wake.WakeEngine
import fr.assistantvocal.app.phone.AndroidAlarmScheduler
import fr.assistantvocal.app.phone.AndroidCallLog
import fr.assistantvocal.app.phone.AndroidContacts
import fr.assistantvocal.app.phone.AndroidLocation
import fr.assistantvocal.app.phone.AndroidPhoneActions
import fr.assistantvocal.app.phone.AndroidSms
import fr.assistantvocal.app.phone.AndroidSmsSender
import fr.assistantvocal.app.phone.NotificationHub
import fr.assistantvocal.app.vision.AndroidCamera
import fr.assistantvocal.app.vision.AssetMedicationDatabase
import fr.assistantvocal.app.vision.MlKitBarcodes
import fr.assistantvocal.app.vision.MlKitOcr
import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Prompts
import fr.assistantvocal.core.vision.BarcodeScanner
import fr.assistantvocal.core.vision.Camera
import fr.assistantvocal.core.vision.MedicationDatabase
import fr.assistantvocal.core.vision.OcrEngine
import fr.assistantvocal.core.vision.ProductLookup
import fr.assistantvocal.core.vision.VisionSkill
import fr.assistantvocal.llm.OpenFoodFacts
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.phone.CallLogSource
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.ContactsSource
import fr.assistantvocal.core.phone.LocationSource
import fr.assistantvocal.core.phone.NotificationSource
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.phone.PhoneActions
import fr.assistantvocal.core.phone.SmsSender
import fr.assistantvocal.core.phone.SmsSource
import fr.assistantvocal.core.reminders.AlarmScheduler
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.skills.CommunicationSkill
import fr.assistantvocal.core.skills.ReminderSkill
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.llm.FallbackLlmProvider
import fr.assistantvocal.core.llm.LlmProvider
import fr.assistantvocal.core.llm.UsageCounter
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.ToolRegistry
import fr.assistantvocal.llm.ClaudeProvider
import fr.assistantvocal.llm.GeminiProvider
import java.time.Instant

/**
 * Assemble les objets de l'application. Les tests remplacent les éléments matériels
 * (micro, haut-parleur, sons, stockage) par des faux.
 */
class AppGraph(
    val context: Context,
    val store: KeyValueStore = PrefsStore(context),
    val secrets: SecretStore = KeystoreSecretStore(context),
    val device: DeviceStatus = AndroidDeviceStatus(context),
    val speechInputFactory: (Context) -> SpeechInput = { AndroidSpeechInput(it) },
    val speechOutputFactory: (Context) -> SpeechOutput = { AndroidSpeechOutput(it) },
    val soundsFactory: (Context) -> Sounds = { Earcons(it) },
    val wakeEngineFactory: (Context) -> WakeEngine = { VoskWakeEngine(it) },
    phoneFactory: ((Context, AssistantSettings) -> PhoneBackend)? = null,
    /** Vision : caméra et analyses (remplacées par des faux dans les tests). */
    val visionFactory: ((Context) -> VisionBackend)? = null,
    /** Délai d'annulation du SOS (10 s ; raccourci dans les tests). */
    val sosCountdownMs: Long = 10_000,
    geminiBaseUrl: String = "https://generativelanguage.googleapis.com",
    claudeBaseUrl: String? = null,
) {
    val settings = AssistantSettings(store)
    val usage = UsageCounter(store, { Instant.now() }) { settings.dailyLimit(it) }

    val gemini = GeminiProvider(
        apiKey = { secrets.get(SECRET_GEMINI) },
        model = { settings.geminiModel },
        highModel = { settings.geminiHighModel },
        baseUrl = geminiBaseUrl,
    )
    val claude = ClaudeProvider(apiKey = { secrets.get(SECRET_CLAUDE) }, model = { settings.claudeModel }, baseUrl = claudeBaseUrl)

    private val providersById: Map<String, LlmProvider> = mapOf("gemini" to gemini, "claude" to claude)
    val llm = FallbackLlmProvider({ settings.providerOrder.mapNotNull { providersById[it] } }, usage)

    /** Téléphone (contacts, appels, SMS, notifications, réveils, position). Remplacé par des faux dans les tests. */
    val phone: PhoneBackend = phoneFactory?.invoke(context, settings) ?: PhoneBackend.android(context, settings)
    val people = PeopleStore(store)
    val dialog = Dialog({ System.currentTimeMillis() })
    val directory = ContactDirectory(phone.contacts, people)
    val comms = CommunicationSkill(directory, people, phone.callLog, phone.sms, phone.smsSender, phone.actions,
        phone.notifications, store, device, dialog)
    val reminders = ReminderSkill(ReminderStore(store), MedicationLog(store), phone.scheduler, store, device, dialog)
    private val visionBackend: VisionBackend = visionFactory?.invoke(context) ?: VisionBackend.android(context)
    val vision = VisionSkill(
        camera = visionBackend.camera, ocr = visionBackend.ocr, barcodes = visionBackend.barcodes,
        products = visionBackend.products, medications = visionBackend.medications, llm = llm,
        canUseLlm = { !settings.privateMode && device.isOnline() && llm.isConfigured() },
        systemPrompt = { Prompts.system(settings.name, settings.verbosity, device.now()) },
        today = { device.now().toLocalDate() }, nowMs = { System.currentTimeMillis() },
    )
    val skills = Skills(dialog, comms, reminders, vision)

    val tools = ToolRegistry(CoreTools.all(settings, device) + CoreTools.phase3())
    val assistant = Assistant(settings, device, llm, tools, skills = skills)

    companion object {
        const val SECRET_GEMINI = "gemini_api_key"
        const val SECRET_CLAUDE = "claude_api_key"
    }
}

/** Accès caméra et analyses d'image. */
class VisionBackend(
    val camera: () -> Camera,
    val ocr: OcrEngine,
    val barcodes: BarcodeScanner,
    val products: ProductLookup,
    val medications: MedicationDatabase,
) {
    companion object {
        fun android(context: Context) = VisionBackend(
            { AndroidCamera(context) }, MlKitOcr(), MlKitBarcodes(), OpenFoodFacts(), AssetMedicationDatabase(context),
        )
    }
}

/** Ensemble des accès au téléphone. */
class PhoneBackend(
    val contacts: ContactsSource,
    val callLog: CallLogSource,
    val sms: SmsSource,
    val smsSender: SmsSender,
    val actions: PhoneActions,
    val notifications: NotificationSource,
    val location: LocationSource,
    val scheduler: AlarmScheduler,
) {
    companion object {
        fun android(context: Context, settings: AssistantSettings) = PhoneBackend(
            AndroidContacts(context), AndroidCallLog(context), AndroidSms(context), AndroidSmsSender(context),
            AndroidPhoneActions(context) { settings.speakerOnAnswer }, NotificationHub, AndroidLocation(context),
            AndroidAlarmScheduler(context),
        )
    }
}

class App : Application() {
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        appContext = this
        if (!::graph.isInitialized) graph = AppGraph(this)
    }

    companion object {
        lateinit var appContext: Context
            private set

        fun graph(context: Context): AppGraph = (context.applicationContext as App).graph
    }
}
