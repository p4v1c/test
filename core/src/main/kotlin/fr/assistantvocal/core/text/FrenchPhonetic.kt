package fr.assistantvocal.core.text

/**
 * Clé phonétique française simplifiée : deux mots qui se prononcent pareil donnent la même clé.
 * « Orphée », « orfée », « or fée » -> « orfe ». Sert à vérifier que le nom de l'assistant a vraiment été dit.
 */
object FrenchPhonetic {
    private val rules: List<Pair<Regex, String>> = listOf(
        "ph" to "f", "qu" to "k", "gu(?=[eiy])" to "g", "ch" to "S", "eaux?" to "o", "aux?" to "o", "ou" to "U",
        "ai" to "e", "ei" to "e", "er$" to "e", "ez$" to "e", "et$" to "e", "th" to "t", "y" to "i",
        "c(?=[eiy])" to "s", "ç" to "s", "c" to "k", "g(?=[eiy])" to "j", "z" to "s", "x" to "ks", "h" to "",
        "ee" to "e", "e$" to "", "s$" to "", "t$" to "", "d$" to "", "(.)\\1" to "$1",
    ).map { (a, b) -> Regex(a) to b }

    fun key(word: String): String {
        var s = FrenchText.stripAccents(word.lowercase()).filter { it.isLetter() }
        for ((re, rep) in rules) s = re.replace(s, rep)
        return s
    }

    /**
     * Vrai si la suite de mots contient le nom, en un mot ou en deux/trois mots consécutifs
     * (« or fée » pour « Orphée »).
     */
    fun containsName(words: List<String>, name: String): Boolean {
        val target = name.split(' ', '-').filter { it.isNotBlank() }.joinToString("") { key(it) }
        if (target.isEmpty()) return false
        val keys = words.map { key(it) }
        for (i in keys.indices) {
            var acc = ""
            for (j in i until minOf(i + 3, keys.size)) {
                acc += keys[j]
                if (acc == target) return true
                if (acc.length > target.length) break
            }
        }
        return false
    }
}
