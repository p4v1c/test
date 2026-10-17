package fr.assistantvocal.core.phone

import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.text.FrenchPhonetic
import fr.assistantvocal.core.text.FrenchText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime

// ---------------------------------------------------------------------- données du téléphone

data class PhoneNumber(val number: String, val label: String? = null) {
    val isMobile: Boolean get() = label?.contains("mobile", true) == true ||
        PhoneFormat.digits(number).let { it.startsWith("06") || it.startsWith("07") || it.startsWith("336") || it.startsWith("337") }
}

data class Contact(val id: String, val name: String, val numbers: List<PhoneNumber>)

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED }

data class CallLogEntry(val number: String, val name: String?, val type: CallType, val time: ZonedDateTime, val durationSec: Long)

data class SmsMessage(val id: Long, val address: String, val name: String?, val body: String, val time: ZonedDateTime)

data class AppNotification(
    val key: String, val app: String, val packageName: String, val title: String, val text: String,
    val time: ZonedDateTime, val canReply: Boolean,
)

enum class CallState { IDLE, RINGING, OFFHOOK }

interface ContactsSource { fun all(): List<Contact> }
interface CallLogSource { fun recent(limit: Int = 30): List<CallLogEntry> }
interface SmsSource { fun inbox(limit: Int = 30): List<SmsMessage> }

interface SmsSender {
    /** Envoie et attend la confirmation du réseau. Vrai seulement si le SMS est vraiment parti. */
    suspend fun send(number: String, text: String): Boolean
}

interface PhoneActions {
    fun call(number: String, speaker: Boolean = false): Boolean
    fun answer(): Boolean
    fun hangUp(): Boolean
    fun setSpeaker(on: Boolean): Boolean
    fun state(): CallState
}

interface NotificationSource {
    fun recent(): List<AppNotification>
    fun reply(key: String, text: String): Boolean
}

data class GeoPoint(val lat: Double, val lon: Double, val accuracyM: Float?)

interface LocationSource { suspend fun current(timeoutMs: Long = 15_000): GeoPoint? }

// ---------------------------------------------------------------------- numéros

object PhoneFormat {
    fun digits(n: String): String = n.filter { it.isDigit() || it == '+' }.replace("+33", "0").replace(Regex("^0033"), "0")

    /** « 06 12 34 56 78 », lu chiffre par paires par la synthèse vocale. */
    fun speakable(n: String): String {
        val d = digits(n)
        return if (d.length == 10 && d.startsWith("0")) d.chunked(2).joinToString(" ") else d.toCharArray().joinToString(" ")
    }

    fun same(a: String, b: String): Boolean {
        val x = digits(a); val y = digits(b)
        return x.isNotEmpty() && (x == y || (x.length >= 9 && y.length >= 9 && x.takeLast(9) == y.takeLast(9)))
    }
}

// ---------------------------------------------------------------------- annuaire (contacts + surnoms)

@Serializable
data class EmergencyContact(val name: String, val number: String)

/** Surnoms (« mon fils » -> Pierre), favoris numérotés, contacts d'urgence. Stockés en JSON. */
class PeopleStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), String.serializer())

    /** alias normalisé -> nom du contact (ou numéro). */
    var aliases: Map<String, String>
        get() = store.getString(K_ALIASES)?.let { runCatching { json.decodeFromString(mapSer, it) }.getOrNull() } ?: emptyMap()
        set(v) = store.putString(K_ALIASES, json.encodeToString(mapSer, v))

    fun setAlias(alias: String, target: String) { aliases = aliases + (normalizeAlias(alias) to target) }

    /** Favoris : position (1..9) -> nom du contact ou numéro. */
    var favorites: Map<String, String>
        get() = store.getString(K_FAV)?.let { runCatching { json.decodeFromString(mapSer, it) }.getOrNull() } ?: emptyMap()
        set(v) = store.putString(K_FAV, json.encodeToString(mapSer, v))

    var emergencyContacts: List<EmergencyContact>
        get() = store.getString(K_EMERGENCY)?.let { runCatching { json.decodeFromString(ListSerializer(EmergencyContact.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = store.putString(K_EMERGENCY, json.encodeToString(ListSerializer(EmergencyContact.serializer()), v))

    companion object {
        private const val K_ALIASES = "people.aliases"
        private const val K_FAV = "people.favorites"
        private const val K_EMERGENCY = "people.emergency"

        /** « Mon fils », « à mon fils », « le docteur » -> « mon fils », « docteur ». */
        fun normalizeAlias(s: String): String = FrenchText.key(s).removePrefix("a ").removePrefix("au ")
            .replace(Regex("^(le|la|l|les) "), "").trim()

        /** Format texte pour l'écran de réglages : « Marie: 0612345678; Pierre: 0698765432 ». */
        fun parseEmergency(text: String): List<EmergencyContact> = text.split(';', '\n').mapNotNull { part ->
            val i = part.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val name = part.substring(0, i).trim(); val num = part.substring(i + 1).trim()
            if (name.isEmpty() || PhoneFormat.digits(num).length < 4) null else EmergencyContact(name, num)
        }

        fun formatEmergency(list: List<EmergencyContact>) = list.joinToString("; ") { "${it.name}: ${it.number}" }
    }
}

sealed interface ContactMatch {
    data class Found(val contact: Contact, val number: PhoneNumber, val viaAlias: String? = null) : ContactMatch
    data class Ambiguous(val candidates: List<Contact>) : ContactMatch
    data object None : ContactMatch
}

/**
 * Retrouve un contact à partir de ce qui a été dit : nom complet, prénom, nom de famille, surnom
 * (« mon fils »), prononciation approchée (« Jérôme » / « Gérôme »). Ne devine jamais entre deux
 * personnes proches : renvoie [ContactMatch.Ambiguous].
 */
class ContactDirectory(private val source: ContactsSource, private val people: PeopleStore) {

    fun all(): List<Contact> = source.all()

    fun find(spoken: String): ContactMatch {
        val query = PeopleStore.normalizeAlias(spoken)
        if (query.isEmpty()) return ContactMatch.None
        val wantsFixed = Regex("\\b(fixe|maison|domicile)\\b").containsMatchIn(query)
        val wantsWork = Regex("\\b(travail|bureau|boulot)\\b").containsMatchIn(query)
        val cleaned = query.replace(Regex("\\b(sur (son|le) )?(portable|mobile|fixe|maison|domicile|travail|bureau|boulot)\\b"), "")
            .replace(Regex("\\b(chez|a la|au)\\s*$"), "").trim()
        val contacts = source.all()

        // 1. Surnom
        people.aliases[cleaned]?.let { target ->
            val c = contacts.firstOrNull { FrenchText.key(it.name) == FrenchText.key(target) }
                ?: if (PhoneFormat.digits(target).length >= 4) Contact("alias", target, listOf(PhoneNumber(target))) else null
            if (c != null) return ContactMatch.Found(c, pickNumber(c, wantsFixed, wantsWork), viaAlias = cleaned)
        }

        // 2. Score par contact
        val scored = contacts.map { it to score(cleaned, it) }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (scored.isEmpty()) return ContactMatch.None
        val best = scored.first()
        val close = scored.filter { it.second >= best.second - 0.05 }
        if (close.size > 1) return ContactMatch.Ambiguous(close.take(3).map { it.first })
        if (best.second < 0.6) return ContactMatch.None
        return ContactMatch.Found(best.first, pickNumber(best.first, wantsFixed, wantsWork))
    }

    private fun score(query: String, c: Contact): Double {
        if (c.numbers.isEmpty()) return 0.0
        val name = FrenchText.key(c.name)
        if (name == query) return 1.0
        val qTokens = query.split(' ').filter { it.length > 1 }
        val nTokens = name.split(' ')
        if (qTokens.isEmpty()) return 0.0
        if (qTokens.all { it in nTokens }) return if (qTokens.size >= 2) 0.97 else if (nTokens.first() == qTokens[0]) 0.9 else 0.85
        val qKey = qTokens.joinToString("") { FrenchPhonetic.key(it) }
        if (nTokens.any { FrenchPhonetic.key(it) == qKey } || nTokens.joinToString("") { FrenchPhonetic.key(it) } == qKey) return 0.8
        val dist = nTokens.minOf { FrenchText.editDistance(it, qTokens.joinToString(" ")) }
        return if (qTokens.size == 1 && qTokens[0].length >= 5 && dist <= 1) 0.65 else 0.0
    }

    private fun pickNumber(c: Contact, fixed: Boolean, work: Boolean): PhoneNumber {
        if (work) c.numbers.firstOrNull { it.label?.contains("travail", true) == true || it.label?.contains("work", true) == true }?.let { return it }
        if (fixed) c.numbers.firstOrNull { !it.isMobile }?.let { return it }
        return c.numbers.firstOrNull { it.isMobile } ?: c.numbers.first()
    }

    /** Nom à afficher pour un numéro (journal d'appels, SMS). */
    fun nameFor(number: String): String? = source.all().firstOrNull { c -> c.numbers.any { PhoneFormat.same(it.number, number) } }?.name

    /** « Paul Martin » ou « le 06 12 34 56 78 » pour un numéro inconnu. */
    fun describe(number: String, knownName: String? = null): String {
        if (number.isBlank() || number.startsWith("-")) return "un numéro masqué"
        return knownName ?: nameFor(number) ?: "le ${PhoneFormat.speakable(number)}"
    }

    fun isKnown(number: String) = nameFor(number) != null
}
