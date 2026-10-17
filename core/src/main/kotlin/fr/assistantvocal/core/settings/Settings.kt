package fr.assistantvocal.core.settings

/** Stockage clé/valeur minimal (SharedPreferences sur Android, mémoire dans les tests). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String, default: Int): Int = getString(key)?.toIntOrNull() ?: default
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun getFloat(key: String, default: Float): Float = getString(key)?.toFloatOrNull() ?: default
    fun putFloat(key: String, value: Float) = putString(key, value.toString())
    fun getBoolean(key: String, default: Boolean): Boolean = getString(key)?.toBooleanStrictOrNull() ?: default
    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
}

class InMemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val map = initial.toMutableMap()
    override fun getString(key: String): String? = synchronized(map) { map[key] }
    override fun putString(key: String, value: String?) {
        synchronized(map) { if (value == null) map.remove(key) else map[key] = value }
    }
    fun snapshot(): Map<String, String> = synchronized(map) { map.toMap() }
}

enum class Verbosity { BRIEF, NORMAL, DETAILED }

/**
 * Réglages de l'assistant. Aucune valeur sensible ici (les clés API sont dans un stockage chiffré
 * séparé côté Android). Le nom n'est jamais codé en dur ailleurs : tout passe par [name].
 */
class AssistantSettings(private val store: KeyValueStore) {

    var name: String
        get() = store.getString(K_NAME)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        set(value) = store.putString(K_NAME, value.trim())

    /** Vrai tant qu'aucun nom n'a été choisi : l'assistant le proposera au premier lancement. */
    val isNameChosen: Boolean get() = !store.getString(K_NAME).isNullOrBlank()

    var verbosity: Verbosity
        get() = store.getString(K_VERBOSITY)?.let { runCatching { Verbosity.valueOf(it) }.getOrNull() } ?: Verbosity.NORMAL
        set(value) = store.putString(K_VERBOSITY, value.name)

    var privateMode: Boolean
        get() = store.getBoolean(K_PRIVATE, false)
        set(value) = store.putBoolean(K_PRIVATE, value)

    /** Vitesse de parole, 1.0 = normal. Bornée entre 0.5 et 3.0. */
    var speechRate: Float
        get() = store.getFloat(K_RATE, 1.0f)
        set(value) = store.putFloat(K_RATE, value.coerceIn(MIN_RATE, MAX_RATE))

    var speechPitch: Float
        get() = store.getFloat(K_PITCH, 1.0f)
        set(value) = store.putFloat(K_PITCH, value.coerceIn(0.5f, 2.0f))

    /** Ordre des fournisseurs IA, ex. "gemini,claude". */
    var providerOrder: List<String>
        get() = (store.getString(K_PROVIDERS) ?: "gemini,claude").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = store.putString(K_PROVIDERS, value.joinToString(","))

    var geminiModel: String
        get() = store.getString(K_GEMINI_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_GEMINI_MODEL
        set(value) = store.putString(K_GEMINI_MODEL, value.trim())

    var geminiHighModel: String
        get() = store.getString(K_GEMINI_HIGH_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_GEMINI_HIGH_MODEL
        set(value) = store.putString(K_GEMINI_HIGH_MODEL, value.trim())

    var claudeModel: String
        get() = store.getString(K_CLAUDE_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLAUDE_MODEL
        set(value) = store.putString(K_CLAUDE_MODEL, value.trim())

    /** Limite quotidienne locale par fournisseur (prudence : un peu sous le quota gratuit). */
    fun dailyLimit(providerId: String): Int = store.getInt("$K_LIMIT.$providerId", DEFAULT_LIMITS[providerId] ?: 200)
    fun setDailyLimit(providerId: String, limit: Int) = store.putInt("$K_LIMIT.$providerId", limit)

    companion object {
        const val DEFAULT_NAME = "Assistant"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_GEMINI_HIGH_MODEL = "gemini-3.8-flash"
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 3.0f
        val DEFAULT_LIMITS = mapOf("gemini" to 450, "claude" to 300)

        private const val K_NAME = "assistant.name"
        private const val K_VERBOSITY = "assistant.verbosity"
        private const val K_PRIVATE = "assistant.private"
        private const val K_RATE = "tts.rate"
        private const val K_PITCH = "tts.pitch"
        private const val K_PROVIDERS = "llm.order"
        private const val K_GEMINI_MODEL = "llm.gemini.model"
        private const val K_GEMINI_HIGH_MODEL = "llm.gemini.highModel"
        private const val K_CLAUDE_MODEL = "llm.claude.model"
        private const val K_LIMIT = "llm.limit"
    }
}
