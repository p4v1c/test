package fr.assistantvocal.core.reminders

import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.text.FrenchText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class ReminderKind {
    /** Réveil : sonnerie d'alarme + heure annoncée, jusqu'à « stop ». */
    ALARM,
    /** Minuteur nommé (« pâtes ») : annonce répétée jusqu'à « stop ». */
    TIMER,
    /** Rappel simple : annoncé une fois (répété si pas d'écoute). */
    REMINDER,
    /** Médicament : annoncé, puis rappelé si la prise n'est pas confirmée. */
    MEDICATION,
}

/**
 * Un rappel. Ponctuel : [atEpochMs]. Récurrent : [times] (« HH:mm ») et [days] (1 = lundi … 7 = dimanche, vide = tous les jours).
 */
@Serializable
data class Reminder(
    val id: Int,
    val kind: ReminderKind,
    val label: String,
    val atEpochMs: Long? = null,
    val times: List<String> = emptyList(),
    val days: List<Int> = emptyList(),
    val createdEpochMs: Long = 0,
) {
    val isRecurring get() = times.isNotEmpty()

    fun localTimes(): List<LocalTime> = times.map { LocalTime.parse(it) }
    fun dayOfWeeks(): Set<DayOfWeek> = days.map { DayOfWeek.of(it) }.toSet()

    /** Prochaine échéance strictement après [after], ou null si passée et non récurrente. */
    fun nextAfter(after: ZonedDateTime): ZonedDateTime? {
        if (!isRecurring) {
            val at = atEpochMs?.let { Instant.ofEpochMilli(it).atZone(after.zone) } ?: return null
            return if (at.isAfter(after)) at else null
        }
        val daysSet = dayOfWeeks()
        var d: LocalDate = after.toLocalDate()
        repeat(8) {
            if (daysSet.isEmpty() || d.dayOfWeek in daysSet) {
                localTimes().sorted().map { ZonedDateTime.of(d, it, after.zone) }.firstOrNull { it.isAfter(after) }?.let { return it }
            }
            d = d.plusDays(1)
        }
        return null
    }
}

/** Planificateur système (AlarmManager sur Android). Un identifiant = un réveil système. */
interface AlarmScheduler {
    fun schedule(id: Int, atEpochMs: Long)
    fun cancel(id: Int)
}

/** Liste des rappels, persistée. */
class ReminderStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Reminder.serializer())

    fun all(): List<Reminder> = store.getString(K)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
    private fun save(list: List<Reminder>) = store.putString(K, json.encodeToString(ser, list))

    fun nextId(): Int {
        val n = store.getInt(K_SEQ, 100) + 1
        store.putInt(K_SEQ, n)
        return n
    }

    fun add(r: Reminder) = save(all().filter { it.id != r.id } + r)
    fun remove(id: Int) = save(all().filter { it.id != id })
    fun get(id: Int) = all().firstOrNull { it.id == id }

    /** Recherche par mots du libellé (« le rappel du médecin »). */
    fun search(query: String, kinds: Set<ReminderKind> = ReminderKind.entries.toSet()): List<Reminder> {
        val q = FrenchText.key(query).split(' ').filter { it.length > 2 && it !in STOP }
        val candidates = all().filter { it.kind in kinds }
        if (q.isEmpty()) return candidates
        return candidates.filter { r -> val l = FrenchText.key(r.label); q.any { l.contains(it) } }
    }

    private companion object {
        const val K = "reminders.list"
        const val K_SEQ = "reminders.seq"
        val STOP = setOf("rappel", "rappels", "minuteur", "reveil", "alarme", "les", "des", "pour", "mon", "mes", "une")
    }
}

/** Journal des prises de médicaments : « ai-je pris mon médicament ce matin ? ». */
@Serializable
data class MedicationEntry(val name: String, val epochMs: Long)

class MedicationLog(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(MedicationEntry.serializer())

    fun all(): List<MedicationEntry> = store.getString(K)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    fun record(name: String, at: Instant) {
        // On garde 60 jours.
        val keep = all().filter { it.epochMs > at.toEpochMilli() - 60L * 86_400_000 }
        store.putString(K, json.encodeToString(ser, keep + MedicationEntry(name, at.toEpochMilli())))
    }

    /** Prises du jour (fuseau local) correspondant au nom (ou toutes si [name] est vide/générique). */
    fun takenOn(day: LocalDate, zone: ZoneId, name: String?): List<MedicationEntry> {
        val key = name?.let { FrenchText.key(it) }?.takeIf { it.isNotBlank() && it !in GENERIC }
        return all().filter { Instant.ofEpochMilli(it.epochMs).atZone(zone).toLocalDate() == day }
            .filter { key == null || FrenchText.key(it.name).contains(key) || key.contains(FrenchText.key(it.name)) }
    }

    private companion object {
        const val K = "medication.log"
        val GENERIC = setOf("medicament", "medicaments", "traitement", "cachet", "cachets", "pilule", "pilules", "comprime", "comprimes")
    }
}

fun LocalDateTime.toEpochMs(zone: ZoneId): Long = atZone(zone).toInstant().toEpochMilli()
