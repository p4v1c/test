package fr.assistantvocal.core.assistant

import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Phrases prêtes à être lues, et nettoyage du texte avant la synthèse vocale. */
object FrenchSpeech {
    private val FR = Locale.FRANCE

    fun time(t: ZonedDateTime): String {
        val h = t.hour
        val m = t.minute
        val hour = when (h) {
            0 -> "minuit"
            12 -> "midi"
            1 -> "1 heure"
            else -> "$h heures"
        }
        return when (m) {
            0 -> "Il est $hour pile."
            else -> "Il est $hour $m."
        }
    }

    fun date(t: ZonedDateTime): String {
        val day = t.dayOfWeek.getDisplayName(TextStyle.FULL, FR)
        val month = t.month.getDisplayName(TextStyle.FULL, FR)
        val d = if (t.dayOfMonth == 1) "1er" else t.dayOfMonth.toString()
        return "Nous sommes le $day $d $month ${t.year}."
    }

    fun battery(percent: Int, charging: Boolean): String {
        val base = "La batterie est à $percent pour cent"
        return when {
            charging && percent >= 100 -> "$base, elle est pleine."
            charging -> "$base, en charge."
            percent <= 15 -> "$base. Pensez à la recharger bientôt."
            else -> "$base."
        }
    }

    private val urlRegex = Regex("https?://\\S+|www\\.\\S+")
    private val emojiRegex = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")

    /** Retire le markdown, les emojis et les liens : le texte doit sonner naturel à l'oral. */
    fun clean(text: String): String = text
        .replace(urlRegex, "un lien")
        .replace(emojiRegex, "")
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("[*_`~#>|]"), "")
        .replace(Regex("\\s*\\n+\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}
