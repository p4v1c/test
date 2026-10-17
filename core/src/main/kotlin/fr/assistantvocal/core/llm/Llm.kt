package fr.assistantvocal.core.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class Role { USER, ASSISTANT }

sealed interface LlmPart {
    data class Text(val text: String) : LlmPart

    /** Image déjà compressée (JPEG). */
    class Image(val bytes: ByteArray, val mimeType: String = "image/jpeg") : LlmPart

    /**
     * Appel de fonction demandé par le modèle. [providerData] garde la partie brute du fournisseur
     * (ex. signature de pensée Gemini) pour la renvoyer telle quelle au tour suivant.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val args: JsonObject,
        val providerId: String? = null,
        val providerData: JsonElement? = null,
    ) : LlmPart

    data class ToolResult(val callId: String, val name: String, val content: JsonObject, val isError: Boolean = false) : LlmPart
}

data class LlmMessage(val role: Role, val parts: List<LlmPart>) {
    companion object {
        fun user(text: String) = LlmMessage(Role.USER, listOf(LlmPart.Text(text)))
        fun assistant(text: String) = LlmMessage(Role.ASSISTANT, listOf(LlmPart.Text(text)))
    }
}

/** Déclaration d'une fonction appelable par l'IA. [parameters] est un schéma JSON (type object). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

enum class Quality { STANDARD, HIGH }

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxOutputTokens: Int = 1024,
    val quality: Quality = Quality.STANDARD,
)

enum class FailureKind {
    /** Quota ou limite de débit (HTTP 429), ou limite locale atteinte. */
    QUOTA,
    NETWORK,
    AUTH,
    SERVER,
    /** Refus / blocage de sécurité du fournisseur. */
    BLOCKED,
    BAD_REQUEST,
    NOT_CONFIGURED,
}

sealed interface LlmResult {
    /** [notice] : message à annoncer (ex. bascule de fournisseur). */
    data class Reply(val parts: List<LlmPart>, val providerId: String, val notice: String? = null) : LlmResult {
        val text: String get() = parts.filterIsInstance<LlmPart.Text>().joinToString(" ") { it.text }.trim()
        val toolCalls: List<LlmPart.ToolCall> get() = parts.filterIsInstance<LlmPart.ToolCall>()
    }

    data class Failure(val kind: FailureKind, val message: String, val providerId: String? = null, val notice: String? = null) : LlmResult
}

interface LlmProvider {
    /** Identifiant stable : "gemini", "claude"… */
    val id: String

    /** Nom lu à voix haute : « Gemini », « Claude ». */
    val displayName: String

    fun isConfigured(): Boolean

    suspend fun generate(request: LlmRequest): LlmResult
}
