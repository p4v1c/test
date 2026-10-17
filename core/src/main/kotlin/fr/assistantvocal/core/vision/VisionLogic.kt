package fr.assistantvocal.core.vision

import fr.assistantvocal.core.text.FrenchText
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------- données issues de la caméra

/** Rectangle en pixels de l'image analysée. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

data class TextLine(val text: String, val box: Box)

/** Résultat d'OCR (ML Kit sur Android) : lignes avec position, et taille de l'image. */
data class OcrResult(val lines: List<TextLine>, val imageWidth: Int, val imageHeight: Int) {
    val text: String get() = lines.joinToString("\n") { it.text }
    val isEmpty get() = lines.isEmpty() || text.isBlank()
}

/** Photo prise : JPEG (pour l'IA) et mesures simples calculées sur l'appareil. */
class Frame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    /** Luminance moyenne 0..255. */
    val luminance: Int,
    /** Couleur moyenne de la zone centrale (r, g, b 0..255). */
    val centerRgb: Triple<Int, Int, Int>,
)

// ---------------------------------------------------------------------- guidage de cadrage

/** Conseil de cadrage pour lire un texte, à dire à voix haute. */
data class FramingAdvice(val ok: Boolean, val speech: String)

/**
 * Indique comment déplacer le téléphone pour bien cadrer un texte : texte coupé sur un bord,
 * trop loin (texte minuscule), trop près (texte qui déborde), ou pas de texte.
 */
object FramingAdvisor {
    fun advise(ocr: OcrResult, luminance: Int? = null): FramingAdvice {
        if (luminance != null && luminance < 35) return FramingAdvice(false, "Il fait trop sombre. J'allume la lampe.")
        if (ocr.isEmpty) return FramingAdvice(false, "Je ne vois pas de texte. Posez le document à plat et tenez le téléphone à 30 centimètres au-dessus.")
        val w = ocr.imageWidth.toDouble(); val h = ocr.imageHeight.toDouble()
        val margin = 0.02
        val left = ocr.lines.minOf { it.box.left } / w
        val right = ocr.lines.maxOf { it.box.right } / w
        val top = ocr.lines.minOf { it.box.top } / h
        val bottom = ocr.lines.maxOf { it.box.bottom } / h
        val cut = buildList {
            if (left <= margin) add("à gauche")
            if (right >= 1 - margin) add("à droite")
            if (top <= margin) add("en haut")
            if (bottom >= 1 - margin) add("en bas")
        }
        val medianHeight = ocr.lines.map { it.box.height }.sorted()[ocr.lines.size / 2] / h
        val area = (right - left) * (bottom - top)
        return when {
            cut.size >= 3 -> FramingAdvice(false, "Le texte dépasse de partout : éloignez un peu le téléphone.")
            cut.isNotEmpty() -> FramingAdvice(false, "Le texte est coupé ${cut.joinToString(" et ")}. " + moveHint(cut))
            medianHeight < 0.012 || area < 0.08 -> FramingAdvice(false, "Le texte est loin ou petit : rapprochez un peu le téléphone.")
            else -> FramingAdvice(true, "C'est bon, ne bougez plus.")
        }
    }

    private fun moveHint(cut: List<String>): String = when {
        cut.contains("à gauche") && cut.contains("à droite") -> "Reculez un peu le téléphone."
        cut.contains("en haut") && cut.contains("en bas") -> "Reculez un peu le téléphone."
        cut.contains("à gauche") -> "Déplacez le téléphone un peu vers la gauche."
        cut.contains("à droite") -> "Déplacez le téléphone un peu vers la droite."
        cut.contains("en haut") -> "Déplacez le téléphone un peu vers le haut du document."
        else -> "Déplacez le téléphone un peu vers le bas du document."
    }
}

// ---------------------------------------------------------------------- couleurs

object ColorNamer {
    /** Nom français d'une couleur (r, g, b 0..255), avec nuance claire/foncée. */
    fun name(r: Int, g: Int, b: Int): String {
        val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val l = (mx + mn) / 2
        val d = mx - mn
        val s = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
        var hue = when {
            d == 0.0 -> 0.0
            mx == rf -> 60 * (((gf - bf) / d) % 6)
            mx == gf -> 60 * ((bf - rf) / d + 2)
            else -> 60 * ((rf - gf) / d + 4)
        }
        if (hue < 0) hue += 360
        if (l < 0.12) return "noir"
        if (l > 0.92 && s < 0.5) return "blanc"
        if (s < 0.15) return when { l < 0.35 -> "gris foncé"; l > 0.7 -> "gris clair"; else -> "gris" }
        val base = when {
            hue < 12 || hue >= 345 -> "rouge"
            hue < 35 -> if (l < 0.4) "marron" else "orange"
            hue < 50 -> if (l < 0.35) "marron" else if (s < 0.5 && l > 0.6) "beige" else "orange"
            hue < 68 -> if (l < 0.3) "kaki" else "jaune"
            hue < 160 -> "vert"
            hue < 195 -> "turquoise"
            hue < 250 -> "bleu"
            hue < 290 -> "violet"
            else -> if (l > 0.65) "rose" else "rose foncé"
        }
        if (base in setOf("marron", "beige", "kaki", "rose", "rose foncé")) return base
        return when {
            l < 0.3 -> "$base foncé"
            l > 0.72 -> "$base clair"
            else -> base
        }.replace("bleu foncé", "bleu marine").let { if (it == "vert foncé" && hue in 70.0..110.0) "vert kaki" else it }
    }
}

// ---------------------------------------------------------------------- lumière

object LightMeter {
    fun describe(luminance: Int): String = when {
        luminance < 20 -> "C'est très sombre, la lumière semble éteinte."
        luminance < 60 -> "Il y a peu de lumière."
        luminance < 140 -> "La lumière est moyenne."
        luminance < 210 -> "C'est bien éclairé."
        else -> "C'est très lumineux, une lampe ou une fenêtre est en face."
    }

    /** Fréquence du son de détecteur de lumière : plus c'est clair, plus c'est aigu (200 à 1500 Hz). */
    fun toneHz(luminance: Int): Double = 200.0 + luminance.coerceIn(0, 255) * (1300.0 / 255)
}

// ---------------------------------------------------------------------- billets

/**
 * Reconnaissance des billets en euros sans internet : le chiffre imprimé en grand (OCR) et la couleur
 * dominante doivent concorder. Si ce n'est pas sûr, on le dit (et l'IA peut confirmer en ligne).
 */
object BanknoteDetector {
    data class Result(val value: Int?, val sure: Boolean, val speech: String)

    private val values = listOf(500, 200, 100, 50, 20, 10, 5)
    private val colorOf = mapOf(5 to setOf("gris", "gris clair", "gris foncé", "vert clair"), 10 to setOf("rouge", "rose", "rose foncé", "rouge clair", "orange"),
        20 to setOf("bleu", "bleu clair", "bleu marine", "turquoise"), 50 to setOf("orange", "orange clair", "marron", "beige", "jaune"),
        100 to setOf("vert", "vert clair", "vert foncé", "turquoise"), 200 to setOf("jaune", "marron", "beige", "orange", "jaune clair", "kaki"),
        500 to setOf("violet", "violet clair", "violet foncé", "rose foncé"))

    fun detect(ocr: OcrResult, centerColor: String): Result {
        val text = ocr.text.uppercase()
        val looksLikeEuro = Regex("EURO|EYP|ΕΥΡΩ|€").containsMatchIn(text)
        // Les gros chiffres du billet : on compte les apparitions de chaque valeur.
        val counts = values.associateWith { v -> Regex("(?<!\\d)$v(?!\\d)").findAll(text).count() }.filterValues { it > 0 }
        val candidate = counts.maxByOrNull { it.value * 10 + it.key / 100 }?.key
        if (candidate == null) return Result(null, false,
            if (looksLikeEuro) "Je vois un billet, mais je n'arrive pas à lire sa valeur. Retournez-le ou éloignez-le un peu."
            else "Je ne reconnais pas de billet. Posez-le à plat, bien éclairé, à 20 centimètres du téléphone.")
        val colorOk = colorOf[candidate]?.contains(centerColor) == true
        val sure = colorOk && (looksLikeEuro || (counts[candidate] ?: 0) >= 2)
        return Result(candidate, sure,
            if (sure) "C'est un billet de $candidate euros."
            else "Il semble que ce soit un billet de $candidate euros, mais je n'en suis pas certain.")
    }
}

// ---------------------------------------------------------------------- dates de péremption

object ExpiryDates {
    private val full = Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b")
    private val monthYear = Regex("\\b(\\d{1,2})[./-](\\d{4}|\\d{2})\\b")
    private val keywords = Regex("(?i)(a consommer|à consommer|dlc|ddm|dluo|exp|peremption|péremption|best before|avant le|jusqu)")

    /** Trouve une date de péremption probable dans un texte lu (priorité aux dates précédées d'un mot-clé). */
    fun find(text: String, today: LocalDate): LocalDate? {
        val candidates = mutableListOf<Pair<LocalDate, Boolean>>()
        for (line in text.lines()) {
            val keyed = keywords.containsMatchIn(FrenchText.stripAccents(line))
            full.findAll(line).forEach { m ->
                val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
                var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
                runCatching { LocalDate.of(y, mo, d) }.getOrNull()?.let { candidates += it to keyed }
            }
            if (candidates.isEmpty()) monthYear.findAll(line).forEach { m ->
                val mo = m.groupValues[1].toInt(); var y = m.groupValues[2].toInt(); if (y < 100) y += 2000
                if (mo in 1..12 && y in 2000..2100) candidates += YearMonth.of(y, mo).atEndOfMonth() to keyed
            }
        }
        val plausible = candidates.filter { it.first.year in today.year - 3..today.year + 10 }
        return (plausible.firstOrNull { it.second } ?: plausible.maxByOrNull { it.first })?.first
    }

    fun speak(date: LocalDate, today: LocalDate): String {
        val fr = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.FRANCE)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        return when {
            days < 0 -> "Attention, la date est dépassée : ${date.format(fr)}."
            days == 0L -> "La date limite est aujourd'hui."
            days <= 3 -> "La date limite est très proche : ${date.format(fr)}."
            else -> "À consommer avant le ${date.format(fr)}."
        }
    }
}

// ---------------------------------------------------------------------- code des boîtes de médicaments

/**
 * Lecture du code DataMatrix GS1 des boîtes de médicaments françaises :
 * (01) code produit dont le code CIP13, (17) date de péremption AAMMJJ, (10) numéro de lot.
 */
object Gs1 {
    data class Medication(val cip13: String?, val expiry: LocalDate?, val lot: String?)

    private const val GS = '\u001D'

    fun parse(raw: String): Medication? {
        var s = raw.removePrefix("]d2").removePrefix("]C1").trim()
        // Certains lecteurs rendent les séparateurs sous forme de texte « (01) ».
        s = s.replace("(", "").replace(")", "")
        var cip: String? = null; var exp: LocalDate? = null; var lot: String? = null
        var i = 0
        while (i + 2 <= s.length) {
            when (s.substring(i, i + 2)) {
                "01" -> { val gtin = s.substring(i + 2, minOf(i + 16, s.length)); cip = gtin.takeLast(13).takeIf { it.startsWith("34009") }; i += 16 }
                "17" -> {
                    val d = s.substring(i + 2, minOf(i + 8, s.length))
                    exp = runCatching {
                        val y = 2000 + d.substring(0, 2).toInt(); val m = d.substring(2, 4).toInt(); val day = d.substring(4, 6).toInt()
                        if (day == 0) YearMonth.of(y, m).atEndOfMonth() else LocalDate.of(y, m, day)
                    }.getOrNull()
                    i += 8
                }
                "10", "21" -> {
                    val end = s.indexOf(GS, i + 2).let { if (it < 0) s.length else it }
                    if (s.substring(i, i + 2) == "10") lot = s.substring(i + 2, end)
                    i = end + 1
                }
                else -> i++
            }
        }
        return if (cip == null && exp == null) null else Medication(cip, exp, lot)
    }
}

/** Base des médicaments (base publique française) : code CIP13 -> nom et présentation. */
interface MedicationDatabase {
    fun byCip13(cip: String): MedicationInfo?
}

data class MedicationInfo(val name: String, val presentation: String) {
    /** « plaquette(s) PVC-aluminium de 8 comprimé(s) » -> « boîte de 8 comprimés ». */
    val spokenPresentation: String
        get() {
            val m = Regex("de (\\d+)\\s+([\\p{L}-]+)(?:\\(s\\))?").findAll(presentation).lastOrNull()
            if (m != null) {
                val n = m.groupValues[1].toInt(); val unit = m.groupValues[2]
                return "boîte de $n ${if (n > 1 && !unit.endsWith("s")) unit + "s" else unit}"
            }
            return presentation.replace("(s)", "").replace(Regex("\\s+"), " ").trim()
        }
}
