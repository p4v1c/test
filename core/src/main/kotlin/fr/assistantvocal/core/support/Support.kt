package fr.assistantvocal.core.support

import fr.assistantvocal.core.settings.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Sauvegarde des réglages et des données de l'assistant dans un fichier JSON lisible, pour changer de téléphone
 * ou réparer une erreur. Les clés API et mots de passe n'y sont jamais (stockage chiffré à part).
 */
object Backup {
    const val FORMAT = "assistant-vocal-sauvegarde"
    const val VERSION = 1
    private const val MAX_BYTES = 5_000_000

    /** Données liées à ce téléphone ou à l'instant présent : jamais sauvegardées ni restaurées. */
    private val VOLATILE = listOf(
        "usage.", "owner.lastInteraction", "calls.heardUntil", "sms.readUntil", "medication.pendingReminder",
        "medication.followUps", "reminders.lastFiredLabel", "wellbeing.attempts", "media.libraryUri", "journal.", "backup.",
        "onboarding.", "assistant.enabled",
    )
    private val KEY = Regex("^[A-Za-z0-9._-]{1,80}$")

    fun isSaved(key: String) = KEY.matches(key) && VOLATILE.none { key.startsWith(it) }

    fun export(store: KeyValueStore, createdAt: String, assistantName: String): String {
        val obj = buildJsonObject {
            put("format", FORMAT); put("version", VERSION); put("creee_le", createdAt); put("assistant", assistantName)
            putJsonObject("valeurs") {
                store.keys().filter { isSaved(it) }.sorted().forEach { k -> store.getString(k)?.let { put(k, it) } }
            }
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }

    data class Parsed(val values: Map<String, String>, val createdAt: String?, val assistantName: String?)

    /** Vérifie le fichier ; null s'il n'est pas une sauvegarde de l'assistant. */
    fun parse(text: String): Parsed? {
        if (text.length > MAX_BYTES) return null
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return null
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: return null
        if (version > VERSION) return null
        val values = (root["valeurs"] as? JsonObject ?: return null).mapNotNull { (k, v) ->
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (isSaved(k)) k to s else null
        }.toMap()
        return Parsed(values, root["creee_le"]?.jsonPrimitive?.contentOrNull, root["assistant"]?.jsonPrimitive?.contentOrNull)
    }

    /** Remplace les données sauvegardables par celles du fichier (les données volatiles restent). */
    fun restore(store: KeyValueStore, parsed: Parsed) {
        store.keys().filter { isSaved(it) && it !in parsed.values }.forEach { store.putString(it, null) }
        parsed.values.forEach { (k, v) -> store.putString(k, v) }
    }

    /** Résumé lisible : « 3 rappels, 2 lieux, 5 surnoms… ». */
    fun summary(parsed: Parsed): String {
        fun count(key: String): Int = parsed.values[key]?.let { v ->
            runCatching {
                when (val e = Json.parseToJsonElement(v)) {
                    is kotlinx.serialization.json.JsonArray -> e.size
                    is JsonObject -> e.size
                    else -> 0
                }
            }.getOrDefault(0)
        } ?: 0
        val parts = listOf(
            count("reminders.list") to "rappels", count("places.saved") to "lieux", count("people.aliases") to "surnoms",
            count("people.emergency") to "contacts d'urgence", count("media.bookmarks") to "livres avec signets",
            count("media.podcasts") to "podcasts", count("notes.text") to "notes",
        ).filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }
        return "Sauvegarde" + (parsed.createdAt?.let { " du ${it.take(10)}" } ?: "") +
            (parsed.assistantName?.let { ", assistant « $it »" } ?: "") + (if (parts.isNotEmpty()) " : $parts." else ".")
    }
}

@Serializable
data class JournalEntry(val atMs: Long, val heard: String, val answer: String, val problem: Boolean = false)

/**
 * Journal des derniers échanges, gardé sur le téléphone (jamais envoyé) : un proche peut le partager pour comprendre
 * un problème signalé à la voix (« signale un problème »).
 */
class Journal(private val store: KeyValueStore, private val max: Int = 60) {
    private val ser = ListSerializer(JournalEntry.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<JournalEntry> = store.getString(KEY)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    fun add(e: JournalEntry) = store.putString(KEY, json.encodeToString(ser, (all() + e).takeLast(max)))

    fun clear() = store.putString(KEY, null)

    /** Texte à partager : un échange par ligne, les problèmes signalés bien visibles. */
    fun text(format: (Long) -> String): String = all().joinToString("\n") { e ->
        (if (e.problem) "*** PROBLÈME SIGNALÉ *** " else "") + "${format(e.atMs)} | entendu : « ${e.heard} » | réponse : « ${e.answer} »"
    }

    companion object { const val KEY = "journal.entries" }
}

/** Un point vérifié par « est-ce que tout marche ? ». [fix] : ce qu'il faut faire, dit à voix haute. */
data class Check(val label: String, val ok: Boolean, val fix: String = "", val important: Boolean = true)
