package fr.assistantvocal.app

import android.app.Application
import android.content.Context
import fr.assistantvocal.app.audio.AndroidSpeechInput
import fr.assistantvocal.app.audio.AndroidSpeechOutput
import fr.assistantvocal.app.audio.Earcons
import fr.assistantvocal.app.audio.SpeechInput
import fr.assistantvocal.app.audio.SpeechOutput
import fr.assistantvocal.app.audio.Sounds
import fr.assistantvocal.core.assistant.Assistant
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

    val tools = ToolRegistry(CoreTools.all(settings, device))
    val assistant = Assistant(settings, device, llm, tools)

    companion object {
        const val SECRET_GEMINI = "gemini_api_key"
        const val SECRET_CLAUDE = "claude_api_key"
    }
}

class App : Application() {
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        if (!::graph.isInitialized) graph = AppGraph(this)
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as App).graph
    }
}
