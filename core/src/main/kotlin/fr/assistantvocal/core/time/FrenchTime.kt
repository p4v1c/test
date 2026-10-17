package fr.assistantvocal.core.time

import fr.assistantvocal.core.text.FrenchText
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Moment demandé : une date précise, ou une récurrence (jours vides = tous les jours). */
sealed interface When {
    data class At(val dateTime: LocalDateTime) : When
    data class Recurring(val times: List<LocalTime>, val days: Set<DayOfWeek>) : When
}

/** Résultat d'analyse : le moment, et le reste de la phrase (ce qu'il faut rappeler). */
data class ParsedWhen(val whenever: When, val rest: String)

/**
 * Compréhension des heures, dates, durées et récurrences dites en français.
 * Travaille sur un texte en minuscules sans accents, nombres en chiffres.
 */
object FrenchTime {
    private val FR = Locale.FRANCE

    /** Prépare la phrase : minuscules, sans accents, nombres en chiffres, « 7h30 » -> « 7 h 30 ». */
    fun prepare(text: String): String = FrenchText.stripAccentsSameLength(prepareAccented(text))

    /** Même chose en gardant les accents (même longueur que [prepare], pour retrouver le texte d'origine). */
    fun prepareAccented(text: String): String {
        var s = FrenchText.replaceNumberWords(text)
        s = s.replace(Regex("(\\d)\\s*h\\s*(\\d)"), "$1 h $2").replace(Regex("(\\d)h\\b"), "$1 h")
            .replace(Regex("(\\d):(\\d\\d)"), "$1 h $2")
            .replace("demi-heure", "demi heure").replace("quart d'heure", "quart d heure")
            .replace("’", "'")
            .replace(Regex("[-']"), " ").replace(Regex("\\s+"), " ").trim()
        return s
    }

    // ------------------------------------------------------------------ durées

    private val durationRegex = Regex(
        "(?:(\\d+) (?:heures?|h)\\b(?: (?:et )?(\\d+)(?: minutes?| min| mn)?\\b)?(?: (et demie|et quart))?)" +
            "|(?:(\\d+) (?:minutes?|min|mn)\\b(?: (?:et )?(\\d+) secondes?)?)" +
            "|(?:(\\d+) secondes?\\b)" +
            "|(?:1 (demi heure))|(?:1 (quart d heure))|(?:3 (quarts d heure))"
    )

    /** « 10 minutes », « une heure et demie », « 1 h 30 », « un quart d'heure ». */
    fun parseDuration(text: String): Duration? = findDuration(prepare(text))?.first

    /** Renvoie la durée et la plage de texte reconnue. */
    internal fun findDuration(s: String): Pair<Duration, IntRange>? {
        val m = durationRegex.find(s) ?: return null
        val g = m.groupValues
        val d = when {
            g[1].isNotEmpty() -> Duration.ofHours(g[1].toLong())
                .plusMinutes(g[2].toLongOrNull() ?: 0)
                .plusMinutes(when (g[3]) { "et demie" -> 30; "et quart" -> 15; else -> 0 })
            g[4].isNotEmpty() -> Duration.ofMinutes(g[4].toLong()).plusSeconds(g[5].toLongOrNull() ?: 0)
            g[6].isNotEmpty() -> Duration.ofSeconds(g[6].toLong())
            g[7].isNotEmpty() -> Duration.ofMinutes(30)
            g[8].isNotEmpty() -> Duration.ofMinutes(15)
            g[9].isNotEmpty() -> Duration.ofMinutes(45)
            else -> return null
        }
        return if (d.isZero) null else d to m.range
    }

    // ------------------------------------------------------------------ heures

    private val timeRegex = Regex(
        "\\b(?:(\\d{1,2}) h(?:eures?)?(?: (\\d{1,2}))?(?! ?(?:minutes?|min|mn|secondes?))|(midi|minuit))" +
            "(?: (et quart|et demie|moins le quart|moins (\\d{1,2})))?" +
            "(?: (du matin|de l apres midi|du soir|de l apres-midi|dans l apres midi))?"
    )

    /** Heure du jour, avec la plage reconnue. [pmHint] : « ce soir » a été dit. */
    internal fun findTime(s: String, pmHint: Boolean = false): Pair<LocalTime, IntRange>? {
        val m = timeRegex.find(s) ?: return null
        val g = m.groupValues
        var h = when (g[3]) { "midi" -> 12; "minuit" -> 0; else -> g[1].toIntOrNull() ?: return null }
        var min = g[2].toIntOrNull() ?: 0
        when {
            g[4] == "et quart" -> min = 15
            g[4] == "et demie" -> min = 30
            g[4] == "moins le quart" -> { h -= 1; min = 45 }
            g[4].startsWith("moins") && g[5].isNotEmpty() -> { h -= 1; min = 60 - g[5].toInt() }
        }
        val period = g[6]
        if ((period.contains("soir") || period.contains("apres") || pmHint) && h in 1..11) h += 12
        if (h < 0) h += 24
        if (h > 23 || min > 59) return null
        return LocalTime.of(h, min) to m.range
    }

    fun parseTime(text: String): LocalTime? = findTime(prepare(text))?.first

    // ------------------------------------------------------------------ jours

    private val days = mapOf(
        "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY,
    )
    private val months = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout",
        "septembre", "octobre", "novembre", "decembre")

    /**
     * Analyse « demain à 8 h », « dans 10 minutes », « lundi à 14 h 30 », « le 25 octobre à 9 h »,
     * « tous les jours à 8 h et à 20 h », « tous les lundis à 9 h », « en semaine à 7 h ».
     * Une heure sans jour déjà passée aujourd'hui est prise demain ; « à 3 h » l'après-midi devient 15 h.
     */
    fun parseWhen(text: String, now: LocalDateTime): ParsedWhen? {
        val accented = prepareAccented(text)
        val s = FrenchText.stripAccentsSameLength(accented)
        val removed = mutableListOf<IntRange>()

        // Récurrence
        val recurDays = mutableSetOf<DayOfWeek>()
        var recurring = false
        Regex("\\b(tous les jours|chaque jour|tous les matins|chaque matin|tous les soirs|chaque soir|quotidiennement)\\b").find(s)?.let {
            recurring = true; removed += it.range
        }
        Regex("\\b(en semaine|du lundi au vendredi)\\b").find(s)?.let {
            recurring = true; removed += it.range
            recurDays += listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        }
        Regex("\\b(?:tous les|chaque|le) (lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)s?((?: (?:et|,) (?:le )?(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)s?)*)")
            .find(s)?.let { m ->
                val all = (m.groupValues[1] + " " + m.groupValues[2]).split(' ').map { it.trimEnd('s') }.mapNotNull { days[it] }
                if (m.value.startsWith("tous") || m.value.startsWith("chaque") || m.groupValues[2].isNotBlank() || m.value.endsWith("s")) {
                    recurring = true; recurDays += all; removed += m.range
                }
            }
        val evening = Regex("\\b(ce soir|tous les soirs|chaque soir)\\b").containsMatchIn(s)

        // Toutes les heures citées (« à 8 h et à 20 h »)
        val times = mutableListOf<LocalTime>()
        var searchFrom = 0
        while (true) {
            val sub = s.substring(searchFrom)
            val (t, r) = findTime(sub, evening) ?: break
            times += t
            removed += IntRange(r.first + searchFrom, r.last + searchFrom)
            searchFrom += r.last + 1
        }
        if (recurring) {
            if (times.isEmpty()) {
                when {
                    Regex("\\bmatins?\\b").containsMatchIn(s) -> times += LocalTime.of(8, 0)
                    Regex("\\bsoirs?\\b").containsMatchIn(s) -> times += LocalTime.of(20, 0)
                    else -> return null
                }
            }
            return ParsedWhen(When.Recurring(times.sorted(), recurDays), rest(accented, removed))
        }

        // Relatif : « dans 10 minutes »
        Regex("\\bdans ").findAll(s).forEach { m ->
            val after = s.substring(m.range.last + 1)
            findDuration(after)?.let { (d, r) ->
                if (r.first == 0) {
                    removed += IntRange(m.range.first, m.range.last + 1 + r.last)
                    return ParsedWhen(When.At(now.plus(d).withNano(0)), rest(accented, removed))
                }
            }
        }

        // Jour
        var date: LocalDate? = null
        Regex("\\b(aujourd hui|ce soir|ce matin|cet apres midi|apres demain|demain)\\b").find(s)?.let { m ->
            date = when (m.value) {
                "apres demain" -> now.toLocalDate().plusDays(2)
                "demain" -> now.toLocalDate().plusDays(1)
                else -> now.toLocalDate()
            }
            removed += m.range
        }
        if (date == null) Regex("\\b(?:le )?(\\d{1,2})(?:er)? (${months.joinToString("|")})\\b").find(s)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = months.indexOf(m.groupValues[2]) + 1
            runCatching { LocalDate.of(now.year, mo, d) }.getOrNull()?.let { candidate ->
                date = if (candidate.isBefore(now.toLocalDate())) candidate.plusYears(1) else candidate
                removed += m.range
            }
        }
        if (date == null) Regex("\\b(?:le |ce )?(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?: prochain)?\\b").find(s)?.let { m ->
            date = now.toLocalDate().with(TemporalAdjusters.next(days.getValue(m.groupValues[1])))
            removed += m.range
        }

        val time = times.firstOrNull()
        if (date == null && time == null) return null
        var dt = (date ?: now.toLocalDate()).atTime(time ?: LocalTime.of(9, 0))
        if (date == null && time != null && !dt.isAfter(now)) {
            // « à 3 h » dit à 14 h : on comprend 15 h (seulement de 1 h à 7 h) ; sinon demain.
            val pm = dt.plusHours(12)
            dt = if (time.hour in 1..7 && pm.isAfter(now) && pm.toLocalDate() == now.toLocalDate()) pm else dt.plusDays(1)
        }
        return ParsedWhen(When.At(dt), rest(accented, removed))
    }

    private fun rest(s: String, removed: List<IntRange>): String {
        val keep = StringBuilder()
        for (i in s.indices) if (removed.none { i in it }) keep.append(s[i])
        return keep.toString()
            .replace(Regex("(\\s+(à|a|et à|et a|et|le|pour|vers))+\\s*$"), "")
            .replace(Regex("^((à|a|et|vers)\\s+)+"), "")
            .replace(Regex("\\s+(à|a|et à|et a|vers)(?=\\s|$)"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    // ------------------------------------------------------------------ phrases

    fun speakTime(t: LocalTime): String = when {
        t.hour == 12 && t.minute == 0 -> "midi"
        t.hour == 0 && t.minute == 0 -> "minuit"
        t.minute == 0 -> "${t.hour} heures"
        else -> "${t.hour} heures ${t.minute}"
    }.replace(Regex("^1 heures"), "1 heure")

    /** « aujourd'hui à 15 heures », « demain à 8 heures », « lundi 20 octobre à 9 heures ». */
    fun speakDateTime(dt: LocalDateTime, now: LocalDateTime): String {
        val day = when (dt.toLocalDate()) {
            now.toLocalDate() -> "aujourd'hui"
            now.toLocalDate().plusDays(1) -> "demain"
            now.toLocalDate().plusDays(2) -> "après-demain"
            else -> "le " + dt.dayOfWeek.getDisplayName(TextStyle.FULL, FR) + " " + dt.dayOfMonth + " " +
                dt.month.getDisplayName(TextStyle.FULL, FR)
        }
        return "$day à ${speakTime(dt.toLocalTime())}"
    }

    fun speakDuration(d: Duration): String {
        val h = d.toHours(); val m = d.toMinutesPart(); val s = d.toSecondsPart()
        val parts = buildList {
            if (h > 0) add(if (h == 1L) "1 heure" else "$h heures")
            if (m > 0) add(if (m == 1) "1 minute" else "$m minutes")
            if (s > 0 && h == 0L) add(if (s == 1) "1 seconde" else "$s secondes")
        }
        return parts.joinToString(" et ").ifEmpty { "moins d'une seconde" }
    }

    fun speakRecurrence(r: When.Recurring): String {
        val days = when {
            r.days.isEmpty() || r.days.size == 7 -> "tous les jours"
            r.days == setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "en semaine"
            else -> "chaque " + r.days.sorted().joinToString(" et ") { it.getDisplayName(TextStyle.FULL, FR) }
        }
        return "$days à " + r.times.joinToString(" et à ") { speakTime(it) }
    }
}
