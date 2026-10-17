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
    /** Toutes les clés (pour la sauvegarde). */
    fun keys(): Set<String> = emptySet()
}

class InMemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val map = initial.toMutableMap()
    override fun getString(key: String): String? = synchronized(map) { map[key] }
    override fun putString(key: String, value: String?) {
        synchronized(map) { if (value == null) map.remove(key) else map[key] = value }
    }
    fun snapshot(): Map<String, String> = synchronized(map) { map.toMap() }
    override fun keys(): Set<String> = synchronized(map) { map.keys.toSet() }
}

enum class Verbosity { BRIEF, NORMAL, DETAILED }

/**
 * Réglages de l'assistant. Aucune valeur sensible ici (les clés API sont dans un stockage chiffré
 * séparé côté Android). Le nom n'est jamais codé en dur ailleurs : tout passe par [name].
 */
class AssistantSettings(private val store: KeyValueStore) {

    var name: String
        get() = store.getString(K_NAME)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        set(value) {
            val v = value.trim()
            if (v != store.getString(K_NAME)) {
                // La calibration appartenait à l'ancien nom.
                store.putString(K_WAKE_ALIASES, null); store.putString(K_WAKE_THRESHOLD, null); store.putString(K_WAKE_STRICT, null)
            }
            store.putString(K_NAME, v)
        }

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

    /**
     * Interrupteur général. Éteint : plus rien ne fonctionne (écoute, annonces, rappels, secours, boutons)
     * jusqu'à ce qu'on le rallume depuis l'écran.
     */
    var assistantEnabled: Boolean
        get() = store.getBoolean(K_ENABLED, true)
        set(value) = store.putBoolean(K_ENABLED, value)

    /** Écoute du nom en permanence (mot d'activation). */
    var wakeWordEnabled: Boolean
        get() = store.getBoolean(K_WAKE_ENABLED, true)
        set(value) = store.putBoolean(K_WAKE_ENABLED, value)

    /** Variantes apprises à la calibration (comment le moteur hors ligne entend le nom). */
    var wakeAliases: List<String>
        get() = store.getString(K_WAKE_ALIASES)?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = store.putString(K_WAKE_ALIASES, value.joinToString("|"))

    var wakeThreshold: Float
        get() = store.getFloat(K_WAKE_THRESHOLD, 0.6f)
        set(value) = store.putFloat(K_WAKE_THRESHOLD, value.coerceIn(0.3f, 0.95f))

    var wakeStrict: Boolean
        get() = store.getBoolean(K_WAKE_STRICT, false)
        set(value) = store.putBoolean(K_WAKE_STRICT, value)

    val isWakeCalibrated: Boolean get() = store.getString(K_WAKE_THRESHOLD) != null

    /** Un appui sur le bouton du casque ou de l'enceinte déclenche l'écoute. */
    var headsetButtonEnabled: Boolean
        get() = store.getBoolean(K_HEADSET, true)
        set(value) = store.putBoolean(K_HEADSET, value)

    /** Appui long sur « volume bas » pour parler (nécessite le service d'accessibilité). */
    var volumeKeyEnabled: Boolean
        get() = store.getBoolean(K_VOLUME_KEY, true)
        set(value) = store.putBoolean(K_VOLUME_KEY, value)

    /** Prénom de la personne (utilisé dans les messages d'alerte envoyés aux proches). */
    var ownerName: String
        get() = store.getString(K_OWNER)?.takeIf { it.isNotBlank() } ?: "Votre proche"
        set(value) = store.putString(K_OWNER, value.trim())

    /** Dernière fois que la personne a parlé à l'assistant (pour le « je vais bien » quotidien). */
    var lastInteractionMs: Long
        get() = store.getString(K_LAST_INTERACTION)?.toLongOrNull() ?: 0L
        set(value) = store.putString(K_LAST_INTERACTION, value.toString())

    /** « Je vais bien » quotidien : heure (« 11:00 ») ou vide si désactivé. */
    var wellbeingTime: String
        get() = store.getString(K_WELLBEING) ?: ""
        set(value) = store.putString(K_WELLBEING, value.trim())

    /** Mot secret pour retrouver le téléphone par SMS (vide = désactivé). */
    var remoteSecret: String
        get() = store.getString(K_REMOTE) ?: ""
        set(value) = store.putString(K_REMOTE, value.trim())

    /** SMS reçus : « read » (lu directement), « announce » (annonce seulement), « off ». */
    var smsAnnounce: String
        get() = store.getString(K_SMS_ANNOUNCE) ?: "announce"
        set(value) = store.putString(K_SMS_ANNOUNCE, value)

    /** Annoncer les notifications des applications de messagerie (Gmail, Meet…). */
    var announceNotifications: Boolean
        get() = store.getBoolean(K_NOTIF_ANNOUNCE, true)
        set(value) = store.putBoolean(K_NOTIF_ANNOUNCE, value)

    /** Haut-parleur activé automatiquement quand on décroche à la voix. */
    var speakerOnAnswer: Boolean
        get() = store.getBoolean(K_SPEAKER, true)
        set(value) = store.putBoolean(K_SPEAKER, value)

    /** Flux RSS du journal régional (« les infos régionales »), vide = aucun. */
    var newsLocalFeed: String
        get() = store.getString(K_NEWS_LOCAL) ?: ""
        set(value) = store.putString(K_NEWS_LOCAL, value.trim())

    /** Dossier des livres (adresse donnée par le sélecteur de fichiers Android), vide = aucun. */
    var libraryUri: String
        get() = store.getString(K_LIBRARY) ?: ""
        set(value) = store.putString(K_LIBRARY, value.trim())

    /** Adresse Gmail pour lire et envoyer les mails (le mot de passe d'application est stocké chiffré à part). */
    var mailAddress: String
        get() = store.getString(K_MAIL) ?: ""
        set(value) = store.putString(K_MAIL, value.trim())

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
        private const val K_WAKE_ENABLED = "wake.enabled"
        private const val K_WAKE_ALIASES = "wake.aliases"
        private const val K_WAKE_THRESHOLD = "wake.threshold"
        private const val K_WAKE_STRICT = "wake.strict"
        private const val K_HEADSET = "trigger.headset"
        private const val K_OWNER = "owner.name"
        private const val K_LAST_INTERACTION = "owner.lastInteraction"
        private const val K_WELLBEING = "safety.wellbeingTime"
        private const val K_REMOTE = "safety.remoteSecret"
        private const val K_SMS_ANNOUNCE = "sms.announce"
        private const val K_NOTIF_ANNOUNCE = "notifications.announce"
        private const val K_SPEAKER = "calls.speakerOnAnswer"
        private const val K_VOLUME_KEY = "trigger.volumeKey"
        private const val K_ENABLED = "assistant.enabled"
        private const val K_NEWS_LOCAL = "news.localFeed"
        private const val K_LIBRARY = "media.libraryUri"
        private const val K_MAIL = "mail.address"
    }
}
