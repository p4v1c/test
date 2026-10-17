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

/** Téléphone simulé pour les tests Android. */
class AppFakePhone : fr.assistantvocal.core.phone.PhoneActions, fr.assistantvocal.core.phone.ContactsSource,
    fr.assistantvocal.core.phone.CallLogSource, fr.assistantvocal.core.phone.SmsSource, fr.assistantvocal.core.phone.SmsSender,
    fr.assistantvocal.core.phone.NotificationSource, fr.assistantvocal.core.reminders.AlarmScheduler, fr.assistantvocal.core.phone.LocationSource {
    var state = fr.assistantvocal.core.phone.CallState.IDLE
    var answered = false
    val calls = mutableListOf<String>()
    val sent = mutableListOf<Pair<String, String>>()
    val scheduled = mutableMapOf<Int, Long>()
    override fun all() = listOf(fr.assistantvocal.core.phone.Contact("1", "Paul Martin", listOf(fr.assistantvocal.core.phone.PhoneNumber("0612345678", "mobile"))))
    override fun call(number: String, speaker: Boolean): Boolean { calls += number; return true }
    override fun answer(): Boolean { answered = true; state = fr.assistantvocal.core.phone.CallState.OFFHOOK; return true }
    override fun hangUp(): Boolean { state = fr.assistantvocal.core.phone.CallState.IDLE; return true }
    override fun setSpeaker(on: Boolean) = true
    override fun state() = state
    override fun recent(limit: Int) = emptyList<fr.assistantvocal.core.phone.CallLogEntry>()
    override fun inbox(limit: Int) = emptyList<fr.assistantvocal.core.phone.SmsMessage>()
    override suspend fun send(number: String, text: String): Boolean { sent += number to text; return true }
    override fun recent() = emptyList<fr.assistantvocal.core.phone.AppNotification>()
    override fun reply(key: String, text: String) = false
    override fun schedule(id: Int, atEpochMs: Long) { scheduled[id] = atEpochMs }
    override fun cancel(id: Int) { scheduled.remove(id) }
    override suspend fun current(timeoutMs: Long) = fr.assistantvocal.core.phone.GeoPoint(44.9, 4.9, 10f)
    fun backend() = PhoneBackend(this, this, this, this, this, this, this, this)
}

/** Caméra simulée : chaque photo renvoie la même image ; l'OCR renvoie le texte donné. */
class AppFakeVision(var ocrText: List<String> = emptyList(), var rgb: Triple<Int, Int, Int> = Triple(200, 200, 200), var codes: List<String> = emptyList()) {
    var torch = false
    private fun frame() = fr.assistantvocal.core.vision.Frame(ByteArray(4), 1000, 1000, 150, rgb)
    fun backend() = VisionBackend(
        camera = {
            object : fr.assistantvocal.core.vision.Camera {
                override suspend fun preview() = frame()
                override suspend fun capture() = frame()
                override fun torch(on: Boolean) { torch = on }
                override fun close() {}
            }
        },
        ocr = object : fr.assistantvocal.core.vision.OcrEngine {
            override suspend fun read(frame: fr.assistantvocal.core.vision.Frame) = fr.assistantvocal.core.vision.OcrResult(
                ocrText.mapIndexed { i, t -> fr.assistantvocal.core.vision.TextLine(t, fr.assistantvocal.core.vision.Box(100, 100 + i * 60, 800, 140 + i * 60)) }, 1000, 1000)
        },
        barcodes = object : fr.assistantvocal.core.vision.BarcodeScanner {
            override suspend fun scan(frame: fr.assistantvocal.core.vision.Frame) = codes
        },
        products = object : fr.assistantvocal.core.vision.ProductLookup { override suspend fun lookup(code: String) = null },
        medications = object : fr.assistantvocal.core.vision.MedicationDatabase { override fun byCip13(cip: String) = null },
    )
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
    phone: AppFakePhone = AppFakePhone(),
    vision: AppFakeVision = AppFakeVision(),
): AppGraph = AppGraph(
    app, InMemoryStore(), secrets, device, { input }, { output }, { sounds }, { wake },
    phoneFactory = { _, _ -> phone.backend() }, visionFactory = { vision.backend() }, sosCountdownMs = 300, geminiBaseUrl = geminiBaseUrl,
).also { it.settings.name = "Orphée"; app.graph = it }

/** Fait tourner la boucle principale jusqu'à ce que [done] soit vrai (les appels réseau se font sur un autre fil). */
fun awaitMain(timeoutMs: Long = 5_000, done: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        // Avance aussi l'horloge simulée : les delay() des coroutines s'exécutent.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        if (done()) return
        Thread.sleep(20)
    }
    shadowOf(Looper.getMainLooper()).idle()
}
