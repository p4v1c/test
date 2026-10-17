package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.BatteryInfo
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.llm.FailureKind
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.llm.LlmProvider
import fr.assistantvocal.core.llm.LlmRequest
import fr.assistantvocal.core.llm.LlmResult
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime

class FakeDevice(
    var time: ZonedDateTime = ZonedDateTime.of(2026, 10, 17, 14, 30, 0, 0, ZoneId.of("Europe/Paris")),
    var batteryInfo: BatteryInfo? = BatteryInfo(76, false),
    var online: Boolean = true,
    var volume: Int = 50,
) : DeviceStatus {
    override fun now() = time
    override fun battery() = batteryInfo
    override fun isOnline() = online
    override fun adjustVolume(louder: Boolean): Int {
        volume = (volume + if (louder) 10 else -10).coerceIn(0, 100)
        return volume
    }
}

/** Fournisseur IA scripté : renvoie les réponses dans l'ordre et garde les requêtes reçues. */
class ScriptedLlm(
    override val id: String = "fake",
    override val displayName: String = "Fausse IA",
    private val configured: Boolean = true,
    vararg responses: LlmResult,
) : LlmProvider {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<LlmRequest>()

    fun enqueue(r: LlmResult) = queue.addLast(r)
    override fun isConfigured() = configured
    override suspend fun generate(request: LlmRequest): LlmResult {
        requests += request
        return queue.removeFirstOrNull() ?: LlmResult.Failure(FailureKind.SERVER, "plus de réponses scriptées", id)
    }

    companion object {
        fun text(t: String, provider: String = "fake") = LlmResult.Reply(listOf(LlmPart.Text(t)), provider)
        fun call(name: String, args: JsonObject, id: String = "c1", provider: String = "fake") =
            LlmResult.Reply(listOf(LlmPart.ToolCall(id, name, args)), provider)
        fun fail(kind: FailureKind, provider: String = "fake") = LlmResult.Failure(kind, kind.name, provider)
    }
}
