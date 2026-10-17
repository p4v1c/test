package fr.assistantvocal.core.info

import fr.assistantvocal.core.text.FrenchText
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Calculatrice parlée, sans IA : « combien font 12 fois 7 », « 15 pour cent de 80 », « racine carrée de 81 »,
 * « 100 euros en francs ». Renvoie null si la phrase n'est pas un calcul complet.
 */
object Calculator {
    private const val FRANC = 6.55957

    private sealed interface Tok
    private data class Num(val v: Double) : Tok
    private data class Op(val s: String) : Tok

    private val PHRASES = listOf(
        "multiplie par" to " * ", "multiplier par" to " * ", "multiplies par" to " * ", "fois" to " * ", "x" to " * ", "×" to " * ",
        "divise par" to " / ", "diviser par" to " / ", "divises par" to " / ", "sur" to " / ", "÷" to " / ",
        "plus" to " + ", "moins" to " - ",
        "pour cent de" to " %of ", "pourcent de" to " %of ", "% de" to " %of ", "% des" to " %of ", "pour cent des" to " %of ",
        "au carre" to " ^2 ", "au cube" to " ^3 ",
        "racine carree de" to " sqrt ", "racine carree" to " sqrt ", "la racine de" to " sqrt ", "racine de" to " sqrt ",
    )
    private val FILLER = setOf(
        "combien", "font", "fait", "ca", "c", "est", "calcule", "calcul", "le", "resultat", "de", "egal", "egale", "a",
        "donne", "moi", "dis", "et", "quel", "quelle", "que", "s", "il", "te", "plait", "vous", "plaît", "the",
    )

    fun evaluate(text: String): String? {
        val prepared = prepare(text) ?: return null
        francs(prepared)?.let { return it }
        val tokens = tokenize(prepared) ?: return null
        if (tokens.none { it is Op }) return null
        val p = Parser(tokens)
        val v = runCatching { p.expr() }.getOrNull() ?: return null
        if (!p.done()) return null
        if (v.isNaN()) return "Ce calcul n'est pas possible."
        if (v.isInfinite()) return "On ne peut pas diviser par zéro."
        return "Ça fait ${format(v)}."
    }

    private fun prepare(text: String): String? {
        var s = FrenchText.stripAccents(FrenchText.replaceNumberWords(protectPercent(text))).lowercase()
            .replace('’', ' ').replace('\'', ' ').replace('?', ' ')
        // « 3,5 » → « 3.5 » ; « 1 000 » n'est pas géré (la reconnaissance vocale écrit « 1000 »)
        s = s.replace(Regex("(\\d),(\\d)"), "$1.$2")
        s = s.replace(Regex("(\\d)\\s*%"), "$1 %")
        s = s.replace("+", " + ").replace("*", " * ").replace("/", " / ")
        s = s.replace(Regex("(?<=\\d)\\s*-\\s*(?=\\d)"), " - ")
        for ((from, to) in PHRASES) s = s.replace(Regex("(?<![\\p{L}])${Regex.escape(from)}(?![\\p{L}])"), to)
        return s.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /** « pour cent » ne doit pas devenir « pour 100 ». */
    fun protectPercent(text: String) = text.replace(Regex("(?i)\\s*(%|pour\\s*cent\\b)"), " pourcent")

    private fun francs(s: String): String? {
        Regex("(\\d+(?:\\.\\d+)?) ?(?:euros?|€) (?:en|en anciens) francs").find(s)?.let { m ->
            val v = m.groupValues[1].toDouble()
            return "${format(v)} euros font ${format(v * FRANC)} francs."
        }
        Regex("(\\d+(?:\\.\\d+)?) ?(?:anciens )?francs (?:en|en euro|en euros) ?(?:euros?)?").find(s)?.let { m ->
            val v = m.groupValues[1].toDouble()
            return "${format(v)} francs font ${format(v / FRANC)} euros."
        }
        return null
    }

    private fun tokenize(s: String): List<Tok>? {
        val words = s.split(' ')
        val out = mutableListOf<Tok>()
        var started = false
        for (w in words) {
            when {
                w.toDoubleOrNull() != null -> { out += Num(w.toDouble()); started = true }
                w in setOf("+", "-", "*", "/", "%of", "^2", "^3", "sqrt", "(", ")") -> { out += Op(w); started = true }
                w in FILLER && !started -> Unit // « combien font », « calcule »…
                w in setOf("euros", "euro", "€") && started -> Unit
                else -> return null
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private class Parser(private val t: List<Tok>) {
        private var i = 0
        fun done() = i == t.size
        private fun peek(): Tok? = t.getOrNull(i)
        private fun isOp(s: String) = (peek() as? Op)?.s == s

        fun expr(): Double {
            var v = term()
            while (isOp("+") || isOp("-")) {
                val op = (t[i++] as Op).s
                v = if (op == "+") v + term() else v - term()
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (isOp("*") || isOp("/") || isOp("%of")) {
                v = when ((t[i++] as Op).s) {
                    "*" -> v * factor()
                    "/" -> v / factor()
                    else -> v / 100.0 * factor()
                }
            }
            return v
        }

        private fun factor(): Double {
            if (isOp("sqrt")) { i++; return sqrt(factor()) }
            if (isOp("-")) { i++; return -factor() }
            val n = peek() as? Num ?: error("nombre attendu")
            i++
            var v = n.v
            while (isOp("^2") || isOp("^3")) { v = if ((t[i++] as Op).s == "^2") v * v else v * v * v }
            return v
        }
    }

    fun format(v: Double): String {
        if (abs(v - v.roundToLong()) < 1e-9 && abs(v) < 1e15) return v.roundToLong().toString()
        val decimals = if (abs(v) >= 1) 2 else 4
        return "%.${decimals}f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.').replace('.', ',')
    }
}
