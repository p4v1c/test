package fr.assistantvocal.app

import android.os.Looper
import fr.assistantvocal.app.audio.Heard
import fr.assistantvocal.app.audio.SpeechInput
import fr.assistantvocal.app.audio.SpeechOutput
import fr.assistantvocal.app.audio.Sounds
import fr.assistantvocal.core.assistant.BatteryInfo
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.app.wake.WakeEngine
import fr.assistantvocal.core.wake.CalibrationResult
import fr.assistantvocal.core.wake.WakeConfig
import fr.assistantvocal.core.wake.WakeDetection
import org.robolectric.Shadows.shadowOf
import java.time.ZoneId
import java.time.ZonedDateTime

/** Remplace le micro : chaque écoute renvoie la phrase suivante de la liste. */
class FakeInput(vararg heard: Heard) : SpeechInput {
    private val queue = ArrayDeque(heard.toList())
    var listens = 0
    override suspend fun listen(preferOffline: Boolean): Heard { listens++; return queue.removeFirstOrNull() ?: Heard.Nothing }
    override fun cancel() {}
}

/** Remplace le haut-parleur : enregistre ce qui est dit. */
class FakeOutput : SpeechOutput {
    val spoken = mutableListOf<String>()
    var rate = 1f
    override suspend fun speak(text: String): Boolean { if (text.isNotBlank()) spoken += text; return true }
    override fun stop() {}
    override fun isSpeaking() = false
    override fun applySettings(rate: Float, pitch: Float) { this.rate = rate }
}

class FakeSounds : Sounds {
    val played = mutableListOf<Earcon>()
    override suspend fun play(earcon: Earcon) { if (earcon != Earcon.NONE) played += earcon }
}

/** Remplace l'écoute du nom : le test déclenche la détection à la main. */
class FakeWake(
    var calibration: CalibrationResult = CalibrationResult(3, 3, listOf("orphée", "orphée", "or fée"), listOf("or fée"), 0.55),
    var falseTriggers: Int = 0,
) : WakeEngine {
    var listener: ((WakeDetection) -> Unit)? = null
    var lastConfig: WakeConfig? = null
    var recordings = 0
    var starts = 0
    override val isReady = true
    override var isListening = false
    override suspend fun ensureModel() = true
    override fun start(config: WakeConfig, onDetected: (WakeDetection) -> Unit): Boolean {
        lastConfig = config; listener = onDetected; isListening = true; starts++; return true
    }
    override fun stop() { isListening = false }
    override suspend fun record(ms: Int): ShortArray { recordings++; return ShortArray(16) }
    override suspend fun calibrate(name: String, recordings: List<ShortArray>) = calibration
    override suspend fun countDetections(config: WakeConfig, ms: Int) = falseTriggers
    fun fire() { if (isListening) listener?.invoke(WakeDetection("orphée", 0.9)) }
}

class FakeDevice(var online: Boolean = true) : DeviceStatus {
    override fun now(): ZonedDateTime = ZonedDateTime.of(2026, 10, 17, 14, 30, 0, 0, ZoneId.of("Europe/Paris"))
    override fun battery() = BatteryInfo(80, false)
    override fun isOnline() = online
    override fun adjustVolume(louder: Boolean) = 60
}

fun testGraph(
    app: App,
    input: SpeechInput = FakeInput(),
    output: SpeechOutput = FakeOutput(),
    sounds: Sounds = FakeSounds(),
    device: DeviceStatus = FakeDevice(),
    geminiBaseUrl: String = "http://127.0.0.1:1",
    secrets: SecretStore = InMemorySecrets(),
    wake: WakeEngine = FakeWake(),
): AppGraph = AppGraph(
    app, InMemoryStore(), secrets, device, { input }, { output }, { sounds }, { wake }, geminiBaseUrl = geminiBaseUrl,
).also { it.settings.name = "Orphée"; app.graph = it }

/** Fait tourner la boucle principale jusqu'à ce que [done] soit vrai (les appels réseau se font sur un autre fil). */
fun awaitMain(timeoutMs: Long = 5_000, done: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        if (done()) return
        Thread.sleep(20)
    }
    shadowOf(Looper.getMainLooper()).idle()
}
