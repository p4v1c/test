package fr.assistantvocal.core.tools

import fr.assistantvocal.core.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class Risk {
    /** Lecture d'information : exécution directe. */
    SAFE,

    /** Sort du téléphone ou change un réglage important : relecture + « oui ». */
    CONFIRM,

    /** Urgence : compte à rebours annulable. */
    CRITICAL,
}

/** Résultat d'un outil : [data] repart vers l'IA, [speech] peut être lu directement. */
data class ToolOutcome(val success: Boolean, val data: JsonObject, val speech: String? = null) {
    companion object {
        fun ok(speech: String, data: JsonObject = buildJsonObject { put("resultat", speech) }) = ToolOutcome(true, data, speech)
        fun error(speech: String) = ToolOutcome(false, buildJsonObject { put("erreur", speech) }, speech)
    }
}

interface Tool {
    val spec: ToolSpec
    val risk: Risk

    /** Phrase de confirmation lue avant exécution (outils CONFIRM / CRITICAL). */
    fun confirmationQuestion(args: JsonObject): String = "Je confirme ?"

    suspend fun execute(args: JsonObject): ToolOutcome
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = LinkedHashMap<String, Tool>()

    init { tools.forEach { register(it) } }

    fun register(tool: Tool) { byName[tool.spec.name] = tool }
    operator fun get(name: String): Tool? = byName[name]
    fun specs(): List<ToolSpec> = byName.values.map { it.spec }
}

/** Petit constructeur de schémas JSON pour déclarer les paramètres des outils. */
fun toolSpec(name: String, description: String, params: SchemaBuilder.() -> Unit = {}): ToolSpec {
    val b = SchemaBuilder().apply(params)
    return ToolSpec(name, description, b.build())
}

class SchemaBuilder {
    private val props = mutableListOf<Pair<String, JsonObjectBuilder.() -> Unit>>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) {
        props += name to {
            put("type", "string"); put("description", description)
            if (enum != null) put("enum", buildJsonArray { enum.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        if (required) this.required += name
    }

    fun integer(name: String, description: String, required: Boolean = true) {
        props += name to { put("type", "integer"); put("description", description) }
        if (required) this.required += name
    }

    fun boolean(name: String, description: String, required: Boolean = true) {
        props += name to { put("type", "boolean"); put("description", description) }
        if (required) this.required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (n, f) -> putJsonObject(n) { f() } } }
        put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }
}

fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
