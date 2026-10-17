package fr.assistantvocal.core.text

import java.text.Normalizer

/** Outils de texte pour le français parlé (sortie de la reconnaissance vocale). */
object FrenchText {

    /** Minuscules, apostrophes unifiées, ponctuation retirée, espaces simplifiés. Garde les accents. */
    fun normalize(input: String): String =
        input.lowercase()
            .replace('’', '\'')
            .replace('`', '\'')
            .replace(Regex("[^\\p{L}\\p{N}' :,.-]"), " ")
            .replace(Regex("[,.](?!\\d)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Retire les accents : « Orphée » -> « orphee ». */
    fun stripAccents(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Forme de comparaison : normalisée, sans accents, sans tirets ni apostrophes. */
    fun key(input: String): String =
        stripAccents(normalize(input)).replace(Regex("['-]"), " ").replace(Regex("\\s+"), " ").trim()

    private val UNITS = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5,
        "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
        "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16,
    )
    private val TENS = mapOf(
        "vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60,
    )

    private fun isNumberWord(w: String) =
        w in UNITS || w in TENS || w == "cent" || w == "cents" || w == "mille" || w == "et" || w == "quatre"

    /**
     * Convertit une suite de mots (sans accents, séparés par des espaces) en nombre.
     * Gère « vingt et un », « soixante-dix », « quatre-vingt-dix-sept », « deux cent cinq », « mille deux cents ».
     * Renvoie null si la suite n'est pas un nombre valide.
     */
    fun wordsToNumber(words: List<String>): Int? {
        if (words.isEmpty()) return null
        var total = 0
        var current = 0
        var sawDigit = false
        var i = 0
        while (i < words.size) {
            val w = words[i]
            when {
                w == "et" -> { if (!sawDigit) return null }
                w == "quatre" && i + 1 < words.size && (words[i + 1] == "vingt" || words[i + 1] == "vingts") -> {
                    current += 80; i++; sawDigit = true
                }
                w in UNITS -> {
                    val v = UNITS.getValue(w)
                    // « soixante dix » / « quatre vingt dix » : dix-neuf etc. s'ajoutent
                    current += v; sawDigit = true
                }
                w in TENS -> { current += TENS.getValue(w); sawDigit = true }
                w == "cent" || w == "cents" -> {
                    current = (if (current == 0) 1 else current) * 100; sawDigit = true
                }
                w == "mille" -> {
                    total += (if (current == 0) 1 else current) * 1000; current = 0; sawDigit = true
                }
                else -> return null
            }
            i++
        }
        return if (sawDigit) total + current else null
    }

    /**
     * Remplace les nombres écrits en lettres par des chiffres dans une phrase.
     * « réveille-moi à sept heures et quart » -> « réveille-moi à 7 heures et quart ».
     * Travaille sur la forme sans accents pour reconnaître « zéro », mais rend le reste intact.
     */
    fun replaceNumberWords(sentence: String): String {
        val tokens = normalize(sentence).split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val raw = tokens[i]
            val parts = stripAccents(raw).split('-')
            if (parts.all { isNumberWord(it) } && parts.first() != "et") {
                // Étend la suite tant que les mots suivants sont des nombres.
                val seq = parts.toMutableList()
                var j = i + 1
                while (j < tokens.size) {
                    val next = stripAccents(tokens[j]).split('-')
                    if (!next.all { isNumberWord(it) }) break
                    // « et quart », « et demie » : « et » suivi d'un non-nombre n'appartient pas au nombre
                    if (next == listOf("et") && (j + 1 >= tokens.size || !isNumberWord(stripAccents(tokens[j + 1]).split('-').first()))) break
                    seq += next; j++
                }
                while (seq.isNotEmpty() && seq.last() == "et") { seq.removeAt(seq.size - 1); j-- }
                val n = wordsToNumber(seq)
                if (n != null) { out += n.toString(); i = j; continue }
            }
            out += raw
            i++
        }
        return out.joinToString(" ")
    }

    /** Estimation du nombre de syllabes d'un mot français (groupes de voyelles, « e » final muet). */
    fun syllableCount(word: String): Int {
        val w = stripAccents(word.lowercase()).filter { it.isLetter() }
        if (w.isEmpty()) return 0
        val groups = Regex("[aeiouy]+").findAll(w).count()
        val lower = word.lowercase().trimEnd('s')
        // « e » final muet (Marguerite), mais pas « é » (Orphée) ni « ee » (orphee)
        val silentE = lower.endsWith("e") && !lower.endsWith("é") && !lower.endsWith("ée") &&
            w.length > 2 && w.trimEnd('s').let { it.length >= 2 && it[it.length - 2] !in "aeiouy" }
        return maxOf(1, groups - if (silentE && groups > 1) 1 else 0)
    }

    /** Distance d'édition (Levenshtein), pour la comparaison souple des noms. */
    fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
