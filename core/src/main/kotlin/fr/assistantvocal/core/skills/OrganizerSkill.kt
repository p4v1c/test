package fr.assistantvocal.core.skills

import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.assistant.FrenchSpeech
import fr.assistantvocal.core.info.WeatherSpeech
import fr.assistantvocal.core.media.MediaState
import fr.assistantvocal.core.media.TextNote
import fr.assistantvocal.core.reminders.ReminderKind
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.router.AddEvent
import fr.assistantvocal.core.router.AddNote
import fr.assistantvocal.core.router.AddToList
import fr.assistantvocal.core.router.Agenda
import fr.assistantvocal.core.router.Briefing
import fr.assistantvocal.core.router.ClearList
import fr.assistantvocal.core.router.DeleteNote
import fr.assistantvocal.core.router.OrganizerIntent
import fr.assistantvocal.core.router.ReadList
import fr.assistantvocal.core.router.ReadNotes
import fr.assistantvocal.core.router.RemoveFromList
import fr.assistantvocal.core.text.FrenchText
import fr.assistantvocal.core.time.FrenchTime
import fr.assistantvocal.core.time.When
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

data class CalEvent(val title: String, val start: LocalDateTime, val end: LocalDateTime?, val allDay: Boolean = false, val location: String? = null)

/** Agenda du téléphone (Google Agenda). */
interface CalendarSource {
    fun events(from: LocalDateTime, to: LocalDateTime): List<CalEvent>
    /** Ajoute un rendez-vous dans l'agenda principal ; faux si aucun agenda modifiable. */
    fun add(title: String, start: LocalDateTime, minutes: Int): Boolean
}

/** Notes écrites, liste de courses, agenda et « point du jour ». */
class OrganizerSkill(
    private val state: MediaState,
    private val calendar: CalendarSource?,
    private val reminders: ReminderStore,
    private val dialog: Dialog,
    private val device: DeviceStatus,
    /** Météo du jour pour le point du jour (null si indisponible). */
    private val weatherToday: suspend () -> String? = { null },
    private val unreadSms: () -> Int = { 0 },
    private val unreadMail: suspend () -> Int? = { null },
) {
    suspend fun handle(intent: OrganizerIntent): AssistantReply = when (intent) {
        is AddNote -> addNote(intent.text)
        ReadNotes -> readNotes()
        is DeleteNote -> deleteNote(intent.number)
        is AddToList -> addToList(intent.items)
        ReadList -> readList()
        is RemoveFromList -> removeFromList(intent.item)
        ClearList -> clearList()
        is Agenda -> AssistantReply(agenda(intent.text))
        is AddEvent -> addEvent(intent.text)
        Briefing -> AssistantReply(briefing())
    }

    private fun nowMs() = device.now().toInstant().toEpochMilli()
    private fun capital(s: String) = s.trim().replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------------------ notes écrites

    private fun addNote(text: String): AssistantReply {
        val t = capital(text).trimEnd('.')
        state.notes = state.notes + TextNote(t, nowMs())
        return AssistantReply("C'est noté : $t. Pour relire, dites : lis mes notes.", Earcon.SUCCESS)
    }

    private fun readNotes(): AssistantReply {
        val notes = state.notes
        if (notes.isEmpty()) return AssistantReply("Vous n'avez aucune note. Dites par exemple : note que le code du portail est 1 2 3 4.")
        val now = device.now().toLocalDateTime()
        val text = notes.mapIndexed { i, n ->
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(n.createdMs), device.now().zone)
            "Note ${i + 1}, ${FrenchTime.speakDateTime(dt, now).substringBefore(" à ")} : ${n.text}."
        }.joinToString(" ")
        return AssistantReply(text)
    }

    private fun deleteNote(n: Int?): AssistantReply {
        val notes = state.notes
        if (notes.isEmpty()) return AssistantReply("Vous n'avez aucune note.")
        if (n == null) return dialog.confirm("Je supprime vos ${notes.size} notes ? Répondez oui ou non.") { state.notes = emptyList(); "Toutes les notes sont supprimées." }
        val note = notes.getOrNull(n - 1) ?: return AssistantReply("Il n'y a pas de note $n.", Earcon.ERROR)
        return dialog.confirm("Je supprime la note $n : ${note.text} ? Répondez oui ou non.") { state.notes = notes - note; "Note supprimée." }
    }

    // ------------------------------------------------------------------ liste de courses

    private fun splitItems(text: String): List<String> =
        text.split(Regex(",|\\bet\\b|\\bpuis\\b|\\bainsi que\\b")).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }

    private fun addToList(items: String): AssistantReply {
        val list = state.shoppingList
        val new = splitItems(items).filter { i -> list.none { FrenchText.key(it) == FrenchText.key(i) } }
        if (new.isEmpty()) return AssistantReply("C'est déjà sur la liste.")
        state.shoppingList = list + new
        return AssistantReply("J'ajoute ${join(new)} à la liste de courses.", Earcon.SUCCESS)
    }

    private fun readList(): AssistantReply {
        val list = state.shoppingList
        if (list.isEmpty()) return AssistantReply("La liste de courses est vide. Dites par exemple : ajoute du pain à la liste.")
        return AssistantReply("Sur la liste de courses, ${list.size} ${if (list.size == 1) "article" else "articles"} : ${join(list)}.")
    }

    private fun removeFromList(item: String): AssistantReply {
        val list = state.shoppingList
        val k = FrenchText.key(item).replace(Regex("^(le|la|les|l|du|de la|des|de|un|une) "), "")
        val hit = list.firstOrNull { FrenchText.key(it).contains(k) } ?: return AssistantReply("Je ne trouve pas « $item » sur la liste.", Earcon.ERROR)
        state.shoppingList = list - hit
        return AssistantReply("J'enlève $hit de la liste.")
    }

    private fun clearList(): AssistantReply {
        if (state.shoppingList.isEmpty()) return AssistantReply("La liste est déjà vide.")
        return dialog.confirm("Je vide la liste de courses ? Répondez oui ou non.") { state.shoppingList = emptyList(); "La liste de courses est vide." }
    }

    private fun join(l: List<String>) = if (l.size <= 1) l.joinToString() else l.dropLast(1).joinToString(", ") + " et " + l.last()

    // ------------------------------------------------------------------ agenda

    private fun dayName(d: LocalDate, today: LocalDate) = when (d) {
        today -> "aujourd'hui"
        today.plusDays(1) -> "demain"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE) + " " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)
    }

    /** Rappels et réveils prévus un jour donné (sans les minuteurs). */
    private fun remindersOn(day: LocalDate): List<Pair<java.time.LocalTime, String>> {
        val zone = device.now().zone
        return reminders.all().filter { it.kind != ReminderKind.TIMER }.flatMap { r ->
            val at = r.atEpochMs
            if (at != null) {
                val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
                if (dt.toLocalDate() == day) listOf(dt.toLocalTime() to r.label) else emptyList()
            } else if (r.days.isEmpty() || day.dayOfWeek in r.dayOfWeeks()) r.localTimes().map { it to r.label } else emptyList()
        }.sortedBy { it.first }
    }

    fun agenda(text: String): String {
        val today = device.now().toLocalDate()
        val period = WeatherSpeech.period(text, today)
        val days = period.days
        if (calendar == null) return "Je n'ai pas accès à l'agenda. Un proche doit autoriser l'accès au calendrier."
        val events = runCatching {
            calendar!!.events(days.first().atStartOfDay(), days.last().plusDays(1).atStartOfDay())
        }.getOrElse { return "Je n'arrive pas à lire l'agenda." }.sortedBy { it.start }
        val out = StringBuilder()
        for (d in days) {
            val ev = events.filter { it.start.toLocalDate() == d || (it.allDay && !d.isBefore(it.start.toLocalDate()) && it.end?.toLocalDate()?.isAfter(d) == true) }
            val rem = remindersOn(d)
            if (ev.isEmpty() && rem.isEmpty()) { if (days.size == 1) out.append("Rien de prévu ${dayName(d, today)}. "); continue }
            out.append(capital(dayName(d, today))).append(" : ")
            out.append((ev.map { e ->
                (if (e.allDay) "toute la journée, " else "à ${FrenchTime.speakTime(e.start.toLocalTime())}, ") + e.title.trimEnd('.') + (e.location?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
            } + rem.map { (t, l) -> "rappel à ${FrenchTime.speakTime(t)} : $l" }).joinToString(" ; ")).append(". ")
        }
        return out.toString().trim().ifEmpty { "Rien de prévu ${period.label}." }
    }

    private fun addEvent(text: String): AssistantReply {
        val cal = calendar ?: return AssistantReply("Je n'ai pas accès à l'agenda.", Earcon.ERROR)
        val now = device.now().toLocalDateTime()
        val parsed = FrenchTime.parseWhen(text, now)
        val at = (parsed?.whenever as? When.At)?.dateTime
            ?: return dialog.ask("Pour quel jour et à quelle heure ?") { answer -> addEvent("$text $answer") }
        val title = capital(parsed.rest.ifBlank { "Rendez-vous" }.replace(Regex("^(?i)(pour|de|d')\\s+"), "")
            .let { if (Regex("(?i)rendez").containsMatchIn(it)) it else "Rendez-vous $it" })
        return dialog.confirm("J'ajoute à l'agenda : $title, ${FrenchTime.speakDateTime(at, now)} ? Répondez oui ou non.") {
            if (cal.add(title, at, 60)) "C'est dans l'agenda. Si vous voulez un rappel, dites par exemple : rappelle-moi la veille." else "Je n'ai pas réussi à l'ajouter : aucun agenda modifiable sur le téléphone."
        }
    }

    // ------------------------------------------------------------------ point du jour

    suspend fun briefing(): String {
        val now = device.now()
        val hello = when (now.hour) { in 5..11 -> "Bonjour."; in 12..17 -> "Bon après-midi."; else -> "Bonsoir." }
        return buildString {
            append(hello).append(' ').append(FrenchSpeech.date(now)).append(' ').append(FrenchSpeech.time(now)).append(' ')
            weatherToday()?.let { append(it).append(' ') }
            if (calendar != null) append(agenda("aujourd'hui")).append(' ')
            val sms = unreadSms()
            val mail = runCatching { unreadMail() }.getOrNull()
            val msgs = listOfNotNull(
                sms.takeIf { it > 0 }?.let { "$it ${if (it == 1) "SMS" else "SMS"}" },
                mail?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "mail" else "mails"}" },
            )
            append(if (msgs.isEmpty()) "Pas de nouveau message." else "Vous avez ${msgs.joinToString(" et ")} non lus.")
            if (state.shoppingList.isNotEmpty()) append(" Il y a ${state.shoppingList.size} articles sur la liste de courses.")
        }.trim()
    }
}
