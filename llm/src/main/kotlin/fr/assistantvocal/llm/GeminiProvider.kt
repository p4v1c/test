package fr.assistantvocal.llm

import fr.assistantvocal.core.llm.FailureKind
import fr.assistantvocal.core.llm.LlmMessage
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.llm.LlmProvider
import fr.assistantvocal.core.llm.LlmRequest
import fr.assistantvocal.core.llm.LlmResult
import fr.assistantvocal.core.llm.Quality
import fr.assistantvocal.core.llm.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Gemini via l'API REST `generateContent` (Google AI Studio).
 * Les parties brutes renvoyées par le modèle (avec leur `thoughtSignature`) sont gardées dans
 * [LlmPart.ToolCall.providerData] et renvoyées telles quelles, comme l'exigent les modèles Gemini 3.
 */
class GeminiProvider(
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val highModel: () -> String = model,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val thinkingLevel: String? = "minimal",
    private val client: OkHttpClient = defaultClient(),
) : LlmProvider {
    override val id = "gemini"
    override val displayName = "Gemini"

    override fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    override suspend fun generate(request: LlmRequest): LlmResult {
        val key = apiKey()
        if (key.isNullOrBlank()) return LlmResult.Failure(FailureKind.NOT_CONFIGURED, "Clé Gemini absente", id)
        val preferred = if (request.quality == Quality.HIGH) highModel() else model()
        val result = call(preferred, key, request)
        // Le modèle « haute qualité » a un quota gratuit bien plus petit : on se rabat sur le modèle standard.
        if (result is LlmResult.Failure && result.kind == FailureKind.QUOTA && preferred != model()) {
            return call(model(), key, request)
        }
        return result
    }

    private suspend fun call(modelId: String, key: String, request: LlmRequest, includeThinking: Boolean = true): LlmResult {
        val result = callOnce(modelId, key, request, includeThinking && thinkingLevel != null)
        // Si un modèle n'accepte pas le réglage de réflexion, on réessaie sans.
        if (includeThinking && thinkingLevel != null && result is LlmResult.Failure &&
            result.kind == FailureKind.BAD_REQUEST && result.message.contains("thinking", ignoreCase = true)
        ) return callOnce(modelId, key, request, includeThinking = false)
        return result
    }

    private suspend fun callOnce(modelId: String, key: String, request: LlmRequest, includeThinking: Boolean): LlmResult = withContext(Dispatchers.IO) {
        val body = buildBody(request, includeThinking)
        val http = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1beta/models/$modelId:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            client.newCall(http).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext LlmResult.Failure(classify(resp.code, text), "HTTP ${resp.code}: ${text.take(300)}", id)
                parseResponse(text)
            }
        } catch (e: IOException) {
            LlmResult.Failure(FailureKind.NETWORK, e.message ?: "réseau", id)
        }
    }

    internal fun buildBody(request: LlmRequest, includeThinking: Boolean): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", request.system) } } }
        putJsonArray("contents") { request.messages.forEach { add(toContent(it)) } }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        request.tools.forEach { t ->
                            addJsonObject {
                                put("name", t.name); put("description", t.description)
                                // Gemini refuse un objet sans propriétés : on omet alors « parameters ».
                                val props = t.parameters["properties"] as? JsonObject
                                if (props != null && props.isNotEmpty()) put("parameters", t.parameters)
                            }
                        }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxOutputTokens)
            if (includeThinking && thinkingLevel != null) putJsonObject("thinkingConfig") { put("thinkingLevel", thinkingLevel) }
        }
    }

    private fun toContent(m: LlmMessage): JsonObject = buildJsonObject {
        put("role", if (m.role == Role.USER) "user" else "model")
        putJsonArray("parts") {
            m.parts.forEach { p ->
                when (p) {
                    is LlmPart.Text -> addJsonObject { put("text", p.text) }
                    is LlmPart.Image -> addJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", p.mimeType); put("data", Base64.getEncoder().encodeToString(p.bytes))
                        }
                    }
                    is LlmPart.ToolCall -> {
                        val raw = p.providerData as? JsonObject
                        if (raw != null && p.providerId == id) add(raw) else addJsonObject {
                            putJsonObject("functionCall") { put("name", p.name); put("args", p.args) }
                        }
                    }
                    is LlmPart.ToolResult -> addJsonObject {
                        putJsonObject("functionResponse") {
                            if (!p.callId.startsWith(SYNTHETIC_ID)) put("id", p.callId)
                            put("name", p.name)
                            put("response", p.content)
                        }
                    }
                }
            }
        }
    }

    internal fun parseResponse(text: String): LlmResult {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            return LlmResult.Failure(FailureKind.SERVER, "Réponse illisible", id)
        }
        root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
            return LlmResult.Failure(FailureKind.BLOCKED, "Bloqué : $it", id)
        }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: return LlmResult.Failure(FailureKind.SERVER, "Aucune réponse", id)
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val parts = (candidate["content"] as? JsonObject)?.get("parts")?.jsonArray.orEmpty()
        val out = mutableListOf<LlmPart>()
        var n = 0
        for (el in parts) {
            val part = el.jsonObject
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) continue
            part["text"]?.jsonPrimitive?.contentOrNull?.let { out += LlmPart.Text(it) }
            part["functionCall"]?.jsonObject?.let { fc ->
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                val callId = fc["id"]?.jsonPrimitive?.contentOrNull ?: "$SYNTHETIC_ID${n++}"
                out += LlmPart.ToolCall(callId, name, (fc["args"] as? JsonObject) ?: JsonObject(emptyMap()), id, part)
            }
        }
        if (out.isEmpty()) {
            val kind = if (finish in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")) FailureKind.BLOCKED else FailureKind.SERVER
            return LlmResult.Failure(kind, "Réponse vide ($finish)", id)
        }
        return LlmResult.Reply(out, id)
    }

    private fun classify(code: Int, body: String): FailureKind = when {
        code == 429 -> FailureKind.QUOTA
        code == 401 || code == 403 -> FailureKind.AUTH
        code == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) -> FailureKind.AUTH
        code == 400 && body.contains("FAILED_PRECONDITION") -> FailureKind.AUTH
        code in 400..499 -> FailureKind.BAD_REQUEST
        else -> FailureKind.SERVER
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val SYNTHETIC_ID = "gemini-call-"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
