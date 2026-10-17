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
import fr.assistantvocal.app.support.Backups
import fr.assistantvocal.app.support.HealthChecks
import fr.assistantvocal.core.skills.SupportSkill
import fr.assistantvocal.app.media.AndroidMemos
import fr.assistantvocal.app.media.AndroidMusic
import fr.assistantvocal.app.media.AndroidPlayer
import fr.assistantvocal.app.media.SafLibrary
import fr.assistantvocal.app.organizer.AndroidCalendar
import fr.assistantvocal.app.organizer.AndroidEmailContacts
import fr.assistantvocal.app.organizer.ImapMail
import fr.assistantvocal.core.media.AudioPlayer
import fr.assistantvocal.core.media.LibrarySource
import fr.assistantvocal.core.media.MediaState
import fr.assistantvocal.core.media.MemoStore
import fr.assistantvocal.core.media.MusicSource
import fr.assistantvocal.core.media.PodcastDirectory
import fr.assistantvocal.core.media.RadioSearch
import fr.assistantvocal.core.skills.CalendarSource
import fr.assistantvocal.core.skills.EmailContacts
import fr.assistantvocal.core.skills.MailSkill
import fr.assistantvocal.core.skills.MailSource
import fr.assistantvocal.core.skills.MediaSkill
import fr.assistantvocal.core.skills.OrganizerSkill
import fr.assistantvocal.llm.Podcasts
import fr.assistantvocal.llm.RadioBrowser
import fr.assistantvocal.app.info.AndroidHeading
import fr.assistantvocal.app.info.GoogleMapsNavigator
import fr.assistantvocal.core.info.Geocoder
import fr.assistantvocal.core.info.HeadingSource
import fr.assistantvocal.core.info.Navigator
import fr.assistantvocal.core.info.NewsSource
import fr.assistantvocal.core.info.PlacesSource
import fr.assistantvocal.core.info.SavedPlaces
import fr.assistantvocal.core.info.WeatherSource
import fr.assistantvocal.core.info.WebSearch
import fr.assistantvocal.core.skills.InfoServices
import fr.assistantvocal.core.skills.InfoSkill
import fr.assistantvocal.llm.CompositeSearch
import fr.assistantvocal.llm.IgnGeocoder
import fr.assistantvocal.llm.OpenMeteo
import fr.assistantvocal.llm.OverpassPlaces
import fr.assistantvocal.llm.RssNews
import fr.assistantvocal.llm.TavilySearch
import fr.assistantvocal.llm.WikipediaSearch

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
    /** Orientation, météo, actualités, recherche (remplacés par des faux dans les tests). */
    val infoFactory: ((Context, () -> String?) -> InfoBackend)? = null,
    /** Lecteur, bibliothèque, agenda, mails (remplacés par des faux dans les tests). */
    val mediaFactory: ((Context, AppGraph) -> MediaBackend)? = null,
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
    private val infoBackend: InfoBackend = infoFactory?.invoke(context) { secrets.get(SECRET_TAVILY) }
        ?: InfoBackend.android(context) { secrets.get(SECRET_TAVILY) }
    val info = InfoSkill(
        InfoServices(phone.location, infoBackend.heading, infoBackend.geocoder, infoBackend.places, infoBackend.weather,
            infoBackend.news, infoBackend.search, infoBackend.navigator),
        SavedPlaces(store), dialog, device, comms, llm,
        canUseLlm = { !settings.privateMode && device.isOnline() && llm.isConfigured() },
        systemPrompt = { Prompts.system(settings.name, settings.verbosity, device.now()) },
        localFeed = { settings.newsLocalFeed },
    )
    val mediaBackend: MediaBackend = mediaFactory?.invoke(context, this) ?: MediaBackend.android(context, this)
    val media = MediaSkill(mediaBackend.player, mediaBackend.library, MediaState(store), mediaBackend.radios, mediaBackend.podcasts,
        mediaBackend.music, mediaBackend.memos, dialog, device,
        textPlayer = mediaBackend.textPlayer, downloader = mediaBackend.downloader)
    val mail = MailSkill(mediaBackend.mail, mediaBackend.emailContacts, dialog, device, { settings.ownerName }, llm,
        canUseLlm = { !settings.privateMode && device.isOnline() && llm.isConfigured() },
        systemPrompt = { Prompts.system(settings.name, settings.verbosity, device.now()) })
    val organizer = OrganizerSkill(MediaState(store), mediaBackend.calendar, ReminderStore(store), dialog, device,
        weatherToday = { if (device.isOnline()) runCatching { info.weather("météo aujourd'hui", null) }.getOrNull() else null },
        unreadSms = { runCatching { comms.unreadSmsCount() }.getOrDefault(0) },
        unreadMail = { mail.unreadCount() })
    val skills = Skills(dialog, comms, reminders, vision, info, media, organizer, mail)
    private val localRouter = fr.assistantvocal.core.router.LocalRouter()
    val support = SupportSkill(store, dialog, device, { settings.name }, { localRouter.route(it)?.intent },
        checks = { HealthChecks.run(context, this) },
        backupNow = { Backups.write(context, this)?.let { Backups.SPOKEN_PLACE } },
    ).also { skills.support = it }

    val tools = ToolRegistry(CoreTools.all(settings, device) + CoreTools.phase3() + CoreTools.phase5(info) + CoreTools.phase6(organizer))
    val assistant = Assistant(settings, device, llm, tools, skills = skills)

    companion object {
        const val SECRET_GEMINI = "gemini_api_key"
        const val SECRET_CLAUDE = "claude_api_key"
        const val SECRET_TAVILY = "tavily_api_key"
        const val SECRET_MAIL = "mail_app_password"
    }
}

/** Lecteur et organisation (phase 6). */
class MediaBackend(
    val player: AudioPlayer,
    val library: LibrarySource,
    val radios: RadioSearch,
    val podcasts: PodcastDirectory,
    val music: MusicSource,
    val memos: MemoStore,
    /** Enregistre un mémo ; renvoie sa durée (0 = rien dit, null = erreur). */
    val recordMemo: suspend () -> Long?,
    val calendar: CalendarSource?,
    val mail: MailSource,
    val emailContacts: EmailContacts,
    /** Lecture des livres texte par la synthèse vocale. */
    val textPlayer: AudioPlayer? = null,
    val downloader: fr.assistantvocal.core.media.PodcastDownloader? = null,
) {
    companion object {
        fun android(context: Context, g: AppGraph): MediaBackend {
            val memos = AndroidMemos(context)
            return MediaBackend(
                AndroidPlayer(context), SafLibrary(context) { g.settings.libraryUri }, RadioBrowser(), Podcasts(), AndroidMusic(context),
                memos, { memos.record() }, AndroidCalendar(context),
                ImapMail({ g.settings.mailAddress }, { g.secrets.get(AppGraph.SECRET_MAIL) }),
                AndroidEmailContacts(context, g.people),
                fr.assistantvocal.app.media.TtsTextPlayer(context) { g.settings.speechPitch },
                fr.assistantvocal.app.media.AndroidPodcastDownloader(context),
            )
        }
    }
}

/** Services réseau et capteurs de la phase 5. */
class InfoBackend(
    val geocoder: Geocoder,
    val places: PlacesSource,
    val weather: WeatherSource,
    val news: NewsSource,
    val search: WebSearch,
    val heading: HeadingSource?,
    val navigator: Navigator,
) {
    companion object {
        fun android(context: Context, tavilyKey: () -> String?) = InfoBackend(
            IgnGeocoder(), OverpassPlaces(), OpenMeteo(), RssNews(), CompositeSearch(TavilySearch(tavilyKey), WikipediaSearch()),
            AndroidHeading(context), GoogleMapsNavigator(context),
        )
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
