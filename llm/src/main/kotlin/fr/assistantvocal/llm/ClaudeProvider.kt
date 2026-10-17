package fr.assistantvocal.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import fr.assistantvocal.core.llm.FailureKind
import fr.assistantvocal.core.llm.LlmMessage
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.llm.LlmProvider
import fr.assistantvocal.core.llm.LlmRequest
import fr.assistantvocal.core.llm.LlmResult
import fr.assistantvocal.core.llm.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.util.Base64

/** Claude via le SDK Java officiel d'Anthropic. Modèle par défaut : Claude Haiku (réglable). */
class ClaudeProvider(
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val baseUrl: String? = null,
) : LlmProvider {
    override val id = "claude"
    override val displayName = "Claude"

    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    override fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    @Synchronized
    private fun client(key: String): AnthropicClient {
        cachedClient?.takeIf { cachedKey == key }?.let { return it }
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .maxRetries(0) // pas de nouvelle tentative : on bascule tout de suite sur l'autre fournisseur
            .timeout(Duration.ofSeconds(45))
        if (baseUrl != null) builder.baseUrl(baseUrl)
        return builder.build().also { cachedClient = it; cachedKey = key }
    }

    override suspend fun generate(request: LlmRequest): LlmResult {
        val key = apiKey()
        if (key.isNullOrBlank()) return LlmResult.Failure(FailureKind.NOT_CONFIGURED, "Clé Claude absente", id)
        return withContext(Dispatchers.IO) {
            try {
                parse(client(key).messages().create(buildParams(request)))
            } catch (e: RateLimitException) {
                LlmResult.Failure(FailureKind.QUOTA, e.message ?: "429", id)
            } catch (e: UnauthorizedException) {
                LlmResult.Failure(FailureKind.AUTH, e.message ?: "401", id)
            } catch (e: PermissionDeniedException) {
                LlmResult.Failure(FailureKind.AUTH, e.message ?: "403", id)
            } catch (e: BadRequestException) {
                // Crédit épuisé = 400 « credit balance is too low » : traité comme un quota.
                val kind = if (e.message?.contains("credit", ignoreCase = true) == true) FailureKind.QUOTA else FailureKind.BAD_REQUEST
                LlmResult.Failure(kind, e.message ?: "400", id)
            } catch (e: InternalServerException) {
                LlmResult.Failure(FailureKind.SERVER, e.message ?: "5xx", id)
            } catch (e: AnthropicServiceException) {
                val kind = if (e.statusCode() == 529) FailureKind.SERVER else FailureKind.BAD_REQUEST
                LlmResult.Failure(kind, e.message ?: "HTTP ${e.statusCode()}", id)
            } catch (e: AnthropicIoException) {
                LlmResult.Failure(FailureKind.NETWORK, e.message ?: "réseau", id)
            }
        }
    }

    internal fun buildParams(request: LlmRequest): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model())
            .maxTokens(request.maxOutputTokens.toLong())
            .system(request.system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        request.messages.forEach { b.addMessage(toParam(it)) }
        request.tools.forEach { t ->
            val props = (t.parameters["properties"] as? JsonObject).orEmpty()
            val required = (t.parameters["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
            val propsBuilder = Tool.InputSchema.Properties.builder()
            props.forEach { (name, schema) -> propsBuilder.putAdditionalProperty(name, toJsonValue(schema)) }
            b.addTool(
                Tool.builder()
                    .name(t.name)
                    .description(t.description)
                    .inputSchema(Tool.InputSchema.builder().properties(propsBuilder.build()).required(required).build())
                    .build()
            )
        }
        return b.build()
    }

    private fun toParam(m: LlmMessage): MessageParam {
        val blocks = m.parts.map { p ->
            when (p) {
                is LlmPart.Text -> ContentBlockParam.ofText(TextBlockParam.builder().text(p.text).build())
                is LlmPart.Image -> ContentBlockParam.ofImage(
                    ImageBlockParam.builder().source(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(p.mimeType))
                            .data(Base64.getEncoder().encodeToString(p.bytes))
                            .build()
                    ).build()
                )
                is LlmPart.ToolCall -> {
                    val input = ToolUseBlockParam.Input.builder()
                    p.args.forEach { (k, v) -> input.putAdditionalProperty(k, toJsonValue(v)) }
                    ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(p.id).name(p.name).input(input.build()).build())
                }
                is LlmPart.ToolResult -> ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder().toolUseId(p.callId).content(p.content.toString()).isError(p.isError).build()
                )
            }
        }
        return MessageParam.builder()
            .role(if (m.role == Role.USER) MessageParam.Role.USER else MessageParam.Role.ASSISTANT)
            .contentOfBlockParams(blocks)
            .build()
    }

    internal fun parse(message: Message): LlmResult {
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            return LlmResult.Failure(FailureKind.BLOCKED, "Refus", id)
        }
        val parts = mutableListOf<LlmPart>()
        for (block in message.content()) {
            block.text().ifPresent { parts += LlmPart.Text(it.text()) }
            block.toolUse().ifPresent { tu ->
                val args = fromJsonValue(tu._input()) as? JsonObject ?: JsonObject(emptyMap())
                parts += LlmPart.ToolCall(tu.id(), tu.name(), args, id)
            }
        }
        if (parts.isEmpty()) return LlmResult.Failure(FailureKind.SERVER, "Réponse vide", id)
        return LlmResult.Reply(parts, id)
    }

    companion object {
        /** kotlinx JSON -> JsonValue du SDK. */
        fun toJsonValue(e: JsonElement): JsonValue = JsonValue.from(toPlain(e))

        private fun toPlain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { toPlain(it.value) }
            is JsonArray -> e.map { toPlain(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.doubleOrNull
            }
        }

        /** JsonValue du SDK -> kotlinx JSON. */
        fun fromJsonValue(v: JsonValue): JsonElement = fromPlain(v.convert(Any::class.java))

        private fun fromPlain(o: Any?): JsonElement = when (o) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(o.entries.associate { it.key.toString() to fromPlain(it.value) })
            is List<*> -> JsonArray(o.map { fromPlain(it) })
            is Boolean -> JsonPrimitive(o)
            is Number -> JsonPrimitive(o)
            else -> JsonPrimitive(o.toString())
        }
    }
}
