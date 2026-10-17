package fr.assistantvocal.llm

import fr.assistantvocal.core.llm.FailureKind
import fr.assistantvocal.core.llm.FallbackLlmProvider
import fr.assistantvocal.core.llm.LlmMessage
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.llm.LlmRequest
import fr.assistantvocal.core.llm.LlmResult
import fr.assistantvocal.core.llm.Quality
import fr.assistantvocal.core.llm.Role
import fr.assistantvocal.core.llm.UsageCounter
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.tools.toolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

private val weatherTool = toolSpec("get_weather", "Météo") { string("city", "Ville") }

private fun request(vararg messages: LlmMessage, quality: Quality = Quality.STANDARD) =
    LlmRequest("Tu es Orphée.", messages.toList(), listOf(weatherTool), quality = quality)

class GeminiProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var gemini: GeminiProvider

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        gemini = GeminiProvider({ "test-key" }, { "gemini-3.5-flash-lite" }, { "gemini-3.8-flash" }, server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun textAnswerAndRequestShape() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":"Bonjour !"}]},"finishReason":"STOP"}]}"""))
        val r = gemini.generate(request(LlmMessage.user("Salut"))) as LlmResult.Reply
        assertEquals("Bonjour !", r.text)

        val sent = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", sent.path)
        assertEquals("test-key", sent.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("Tu es Orphée.", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("get_weather", body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("minimal", body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test fun functionCallKeepsThoughtSignatureForNextTurn() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"get_weather","args":{"city":"Lyon"}},"thoughtSignature":"SIG123"}
            ]},"finishReason":"STOP"}]}""".trimIndent()))
        val r = gemini.generate(request(LlmMessage.user("Météo à Lyon ?"))) as LlmResult.Reply
        val call = r.toolCalls.single()
        assertEquals("get_weather", call.name)
        assertEquals("Lyon", call.args["city"]!!.jsonPrimitive.content)
        server.takeRequest()

        // Tour suivant : la partie brute (avec signature) est renvoyée, puis la réponse de fonction.
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Il fait beau."}]}}]}"""))
        val result = LlmPart.ToolResult(call.id, call.name, buildJsonObject { put("temp", 18) })
        gemini.generate(request(LlmMessage.user("Météo à Lyon ?"), LlmMessage(Role.ASSISTANT, r.parts), LlmMessage(Role.USER, listOf(result))))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val contents = body["contents"]!!.jsonArray
        val modelPart = contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("SIG123", modelPart["thoughtSignature"]!!.jsonPrimitive.content)
        val fr = contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get_weather", fr["name"]!!.jsonPrimitive.content)
        assertEquals("18", fr["response"]!!.jsonObject["temp"].toString())
    }

    @Test fun errorsAreClassified() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""))
        assertEquals(FailureKind.QUOTA, (gemini.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""))
        assertEquals(FailureKind.AUTH, (gemini.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(FailureKind.SERVER, (gemini.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(FailureKind.BLOCKED, (gemini.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
    }

    @Test fun networkErrorIsNetwork() = runTest {
        server.shutdown()
        assertEquals(FailureKind.NETWORK, (gemini.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
    }

    @Test fun highQualityFallsBackToStandardModelOnQuota() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))
        val r = gemini.generate(request(LlmMessage.user("lis ce document"), quality = Quality.HIGH))
        assertTrue(r is LlmResult.Reply)
        assertTrue(server.takeRequest().path!!.contains("gemini-3.8-flash"))
        assertTrue(server.takeRequest().path!!.contains("gemini-3.5-flash-lite"))
    }

    @Test fun retriesWithoutThinkingWhenRejected() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"thinking_level is not supported"}}"""))
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))
        assertTrue(gemini.generate(request(LlmMessage.user("a"))) is LlmResult.Reply)
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(second["generationConfig"]!!.jsonObject["thinkingConfig"] == null)
    }

    @Test fun imageIsSentInline() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Une tasse."}]}}]}"""))
        gemini.generate(request(LlmMessage(Role.USER, listOf(LlmPart.Image(byteArrayOf(1, 2, 3)), LlmPart.Text("Qu'est-ce que c'est ?")))))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val inline = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQID", inline["data"]!!.jsonPrimitive.content)
    }
}

class ClaudeProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var claude: ClaudeProvider

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        claude = ClaudeProvider({ "sk-test" }, { "claude-haiku-5-5" }, server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    private fun message(content: String, stop: String = "end_turn") = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody("""{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":$content,
            "stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""")

    @Test fun textAnswerAndRequestShape() = runTest {
        server.enqueue(message("""[{"type":"text","text":"Bonjour !"}]"""))
        val r = claude.generate(request(LlmMessage.user("Salut"))) as LlmResult.Reply
        assertEquals("Bonjour !", r.text)
        val sent = server.takeRequest()
        assertEquals("/v1/messages", sent.path)
        assertEquals("sk-test", sent.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("Tu es Orphée.", body["system"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)
        assertEquals("string", tool["input_schema"]!!.jsonObject["properties"]!!.jsonObject["city"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun toolUseRoundTrip() = runTest {
        server.enqueue(message("""[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Lyon"}}]""", "tool_use"))
        val r = claude.generate(request(LlmMessage.user("Météo ?"))) as LlmResult.Reply
        val call = r.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("Lyon", call.args["city"]!!.jsonPrimitive.content)
        server.takeRequest()

        server.enqueue(message("""[{"type":"text","text":"Il fait beau."}]"""))
        val result = LlmPart.ToolResult("toolu_1", "get_weather", buildJsonObject { put("temp", 18) })
        val final = claude.generate(request(LlmMessage.user("Météo ?"), LlmMessage(Role.ASSISTANT, r.parts), LlmMessage(Role.USER, listOf(result))))
        assertEquals("Il fait beau.", (final as LlmResult.Reply).text)
        val msgs = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals("tool_use", msgs[1].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        val tr = msgs[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", tr["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", tr["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test fun errorsAreClassified() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""))
        assertEquals(FailureKind.QUOTA, (claude.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"bad key"}}"""))
        assertEquals(FailureKind.AUTH, (claude.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
        server.enqueue(message("""[]""", "refusal"))
        assertEquals(FailureKind.BLOCKED, (claude.generate(request(LlmMessage.user("a"))) as LlmResult.Failure).kind)
    }
}

class RealFallbackTest {
    @Test fun gemini429SwitchesToClaude() = runTest {
        val gServer = MockWebServer().apply { start() }
        val cServer = MockWebServer().apply { start() }
        gServer.enqueue(MockResponse().setResponseCode(429))
        cServer.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(
            """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"Salut !"}],
            "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""))
        val gemini = GeminiProvider({ "k" }, { "gemini-3.5-flash-lite" }, baseUrl = gServer.url("/").toString())
        val claude = ClaudeProvider({ "k" }, { "claude-haiku-5-5" }, cServer.url("/").toString().trimEnd('/'))
        val fallback = FallbackLlmProvider({ listOf(gemini, claude) }, UsageCounter(InMemoryStore(), { Instant.EPOCH }) { 100 })
        val r = fallback.generate(LlmRequest("sys", listOf(LlmMessage.user("Salut")))) as LlmResult.Reply
        assertEquals("claude", r.providerId)
        assertEquals("Gemini a atteint sa limite, je passe à Claude.", r.notice)
        gServer.shutdown(); cServer.shutdown()
    }

    @Test fun jsonConversionRoundTrip() {
        val obj = buildJsonObject { put("a", 1); put("b", "x"); put("c", true) }
        assertEquals(obj, ClaudeProvider.fromJsonValue(ClaudeProvider.toJsonValue(obj)) as JsonObject)
    }
}

class OpenFoodFactsTest {
    @Test fun parsesProduct() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name_fr":"Pâte à tartiner","brands":"Ferrero, Nutella","quantity":"400 g",
            "allergens_tags":["en:milk","en:nuts","en:soybeans"],"nutriscore_grade":"e"}}"""))
        server.enqueue(MockResponse().setBody("""{"status":0,"status_verbose":"product not found"}"""))
        val off = OpenFoodFacts(server.url("/").toString())
        val p = off.lookup("3017620422003")!!
        assertEquals("Pâte à tartiner", p.name)
        assertEquals("Ferrero", p.brand)
        assertEquals(listOf("lait", "fruits à coque", "soja"), p.allergens)
        assertEquals("e", p.nutriscore)
        assertTrue(server.takeRequest().path!!.startsWith("/api/v2/product/3017620422003.json"))
        assertEquals(null, off.lookup("123"))
        server.shutdown()
    }
}
