package fr.assistantvocal.core.info

import fr.assistantvocal.core.text.FrenchText
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/** Mise en phrases de la météo (codes WMO d'Open-Meteo). */
object WeatherSpeech {

    fun describe(code: Int): String = when (code) {
        0 -> "ciel dégagé"
        1 -> "plutôt ensoleillé"
        2 -> "quelques nuages"
        3 -> "ciel couvert"
        45, 48 -> "brouillard"
        51, 53, 55 -> "bruine"
        56, 57 -> "bruine verglaçante"
        61 -> "pluie faible"
        63 -> "pluie"
        65 -> "forte pluie"
        66, 67 -> "pluie verglaçante"
        71 -> "un peu de neige"
        73 -> "neige"
        75 -> "fortes chutes de neige"
        77 -> "grésil"
        80 -> "quelques averses"
        81 -> "averses"
        82 -> "fortes averses"
        85, 86 -> "averses de neige"
        95 -> "orages"
        96, 99 -> "orages avec grêle"
        else -> "temps variable"
    }

    private fun isRain(code: Int) = code in 51..67 || code in 80..82 || code >= 95
    private fun isSnow(code: Int) = code in 71..77 || code in 85..86

    fun degrees(t: Double): String {
        val v = t.roundToInt()
        val unit = if (v in -1..1) "degré" else "degrés"
        return if (v < 0) "moins ${-v} $unit" else "$v $unit"
    }

    /** Période demandée : un ou plusieurs jours. */
    data class Period(val days: List<LocalDate>, val label: String)

    private val WEEKDAYS = mapOf(
        "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY, "jeudi" to DayOfWeek.THURSDAY,
        "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY, "dimanche" to DayOfWeek.SUNDAY,
    )

    fun period(text: String, today: LocalDate): Period {
        val k = " " + FrenchText.key(text) + " "
        return when {
            k.contains(" apres demain ") -> today.plusDays(2).let { Period(listOf(it), "après-demain") }
            k.contains(" demain ") -> Period(listOf(today.plusDays(1)), "demain")
            Regex(" (ce|le) (week end|weekend) ").containsMatchIn(k) -> {
                var sat = today
                while (sat.dayOfWeek != DayOfWeek.SATURDAY && sat.dayOfWeek != DayOfWeek.SUNDAY) sat = sat.plusDays(1)
                val days = if (sat.dayOfWeek == DayOfWeek.SUNDAY) listOf(sat) else listOf(sat, sat.plusDays(1))
                Period(days, "ce week-end")
            }
            Regex(" (cette semaine|la semaine|les prochains jours|semaine) ").containsMatchIn(k) ->
                Period((0L..6L).map { today.plusDays(it) }, "cette semaine")
            else -> {
                val day = WEEKDAYS.entries.firstOrNull { k.contains(" ${it.key} ") }
                if (day != null) {
                    var d = today
                    while (d.dayOfWeek != day.value) d = d.plusDays(1)
                    Period(listOf(d), if (d == today) "aujourd'hui" else day.key)
                } else if (Regex(" (ce soir|cette nuit) ").containsMatchIn(k)) Period(listOf(today), "ce soir")
                else Period(listOf(today), "aujourd'hui")
            }
        }
    }

    private fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "aujourd'hui"
        today.plusDays(1) -> "demain"
        else -> WEEKDAYS.entries.first { it.value == d.dayOfWeek }.key
    }

    private fun capital(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun dayLine(d: DailyWeather, today: LocalDate): String = buildString {
        append(capital(dayLabel(d.date, today))).append(" : ").append(describe(d.code))
        append(", de ").append(degrees(d.min)).append(" à ").append(degrees(d.max))
        if (d.rainChance != null && d.rainChance >= 30 && !isRain(d.code) && !isSnow(d.code)) append(", risque de pluie ${d.rainChance} pour cent")
        else if (d.rainChance != null && d.rainChance >= 30) append(", ${d.rainChance} pour cent de risque")
        append('.')
    }

    private fun advice(d: DailyWeather): List<String> = buildList {
        if (d.code >= 95) add("Attention aux orages.")
        if (d.code in listOf(56, 57, 66, 67) || (d.min <= 0 && (isRain(d.code) || isSnow(d.code)))) add("Attention, risque de verglas.")
        else if (d.min <= 0) add("Il gèlera, couvrez-vous bien.")
        if (isRain(d.code) || (d.rainChance ?: 0) >= 50) add("Prenez un parapluie si vous sortez.")
        if (d.max >= 32) add("Il fera très chaud : buvez souvent et restez au frais.")
        if ((d.windMaxKmh ?: 0.0) >= 60) add("Le vent sera fort.")
    }

    /** Réponse complète à une demande de météo. */
    fun speak(f: Forecast, request: String, today: LocalDate, placeName: String? = null): String {
        val p = period(request, today)
        val days = f.days.filter { it.date in p.days }
        if (days.isEmpty()) return "Je n'ai pas de prévision pour ${p.label}. Les prévisions vont jusqu'à sept jours."
        val k = " " + FrenchText.key(request) + " "
        val where = placeName?.let { " à $it" } ?: ""

        // Questions fermées : on répond d'abord oui ou non.
        val rainQ = Regex(" (pleuvoir|pleut|pluie|parapluie|pleuvra) ").containsMatchIn(k)
        val snowQ = Regex(" (neiger|neige|neigera) ").containsMatchIn(k)
        if (rainQ || snowQ) {
            val wet = days.filter { if (snowQ) isSnow(it.code) else isRain(it.code) || (it.rainChance ?: 0) >= 50 }
            val what = if (snowQ) "de neige" else "de pluie"
            val answer = if (wet.isEmpty()) "Non, pas $what prévue ${p.label}$where."
            else {
                val d = wet.first()
                val chance = d.rainChance?.let { " Risque de $it pour cent." } ?: ""
                val when_ = if (p.days.size > 1) dayLabel(d.date, today) else p.label
                "Oui, ${describe(d.code)} ${when_}$where.$chance" + if (!snowQ) " Prenez un parapluie." else ""
            }
            return answer
        }
        if (Regex(" (temperature|combien de degres|chaud|froid|fait il bon|fera t il bon) ").containsMatchIn(k) && p.days.size == 1) {
            val d = days.first()
            val now = if (p.label == "aujourd'hui" && f.current != null) "En ce moment, il fait ${degrees(f.current.temperature)}$where. " else ""
            return now + "${capital(p.label)}, de ${degrees(d.min)} à ${degrees(d.max)}." + advice(d).firstOrNull()?.let { " $it" }.orEmpty()
        }

        return buildString {
            if (p.label == "aujourd'hui" && f.current != null) {
                val c = f.current
                append("En ce moment$where : ").append(degrees(c.temperature))
                if (c.feelsLike != null && (c.feelsLike - c.temperature).let { it <= -3 || it >= 3 }) append(", ressenti ").append(degrees(c.feelsLike))
                append(", ").append(describe(c.code))
                if ((c.windKmh ?: 0.0) >= 30) append(", vent ${c.windKmh!!.roundToInt()} kilomètres heure")
                append(". ")
            } else if (where.isNotEmpty()) append("Météo$where. ")
            if (p.days.size > 3) {
                // Semaine : une phrase courte par jour.
                days.forEach { d ->
                    append(capital(dayLabel(d.date, today))).append(", ").append(describe(d.code))
                    append(", ").append(d.max.roundToInt()).append(" degrés. ")
                }
            } else {
                days.forEach { append(dayLine(it, today)).append(' ') }
                days.flatMap { advice(it) }.distinct().take(2).forEach { append(it).append(' ') }
            }
        }.trim()
    }
}
