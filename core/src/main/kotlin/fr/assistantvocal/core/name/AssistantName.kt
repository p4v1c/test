package fr.assistantvocal.core.name

import fr.assistantvocal.core.text.FrenchText

/** Résultat de la vérification d'un nom proposé pour l'assistant. */
data class NameAdvice(
    val name: String,
    val acceptable: Boolean,
    /** Avertissements à lire à voix haute, en français. Vide si le nom est idéal. */
    val warnings: List<String>,
    val syllables: Int,
)

object AssistantNameRules {

    /**
     * Mots très fréquents dans une conversation : un nom trop proche déclencherait l'assistant par erreur.
     * Liste volontairement courte et ciblée (mots courts du quotidien, prénoms très courants).
     */
    private val COMMON_WORDS = setOf(
        "oui", "non", "bonjour", "salut", "merci", "maman", "papa", "alors", "voila", "voilà", "allo", "allô",
        "dis", "ok", "okay", "super", "bien", "maison", "manger", "demain", "aujourd'hui", "telephone",
        "téléphone", "assistant", "google", "siri", "alexa", "marie", "jean", "pierre", "paul", "louis",
        "michel", "anne", "julie", "nicolas", "sophie", "chéri", "cheri", "attends", "écoute", "ecoute",
        "regarde", "radio", "télé", "tele", "musique", "minute", "seconde", "heure",
    )

    /**
     * Vérifie un nom : 2 à 4 syllabes conseillées, lettres uniquement, pas un mot courant.
     * [familyNames] : prénoms de l'entourage à éviter (contacts favoris).
     */
    fun check(rawName: String, familyNames: Collection<String> = emptyList()): NameAdvice {
        val name = rawName.trim().replace(Regex("\\s+"), " ")
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
        val warnings = mutableListOf<String>()
        if (name.isEmpty()) return NameAdvice(name, false, listOf("Le nom est vide."), 0)
        if (!name.all { it.isLetter() || it == ' ' || it == '-' || it == '\'' }) {
            return NameAdvice(name, false, listOf("Le nom ne doit contenir que des lettres."), 0)
        }
        val words = name.split(' ', '-').filter { it.isNotEmpty() }
        if (words.size > 2) {
            return NameAdvice(name, false, listOf("Le nom doit faire un ou deux mots au maximum."), 0)
        }
        val syllables = words.sumOf { FrenchText.syllableCount(it) }
        if (syllables < 2) warnings += "Ce nom est très court, je risque de me déclencher par erreur. Un nom de deux ou trois syllabes est plus sûr."
        if (syllables > 4) warnings += "Ce nom est long, il sera moins pratique à dire."
        val key = FrenchText.key(name)
        if (COMMON_WORDS.any { FrenchText.key(it) == key }) {
            warnings += "C'est un mot qu'on dit souvent, je risque de me déclencher par erreur."
        }
        if (familyNames.any { FrenchText.key(it) == key }) {
            warnings += "C'est le prénom d'un proche, je risque de me déclencher quand vous parlez de lui."
        }
        return NameAdvice(name, acceptable = true, warnings = warnings, syllables = syllables)
    }

    /**
     * Si [utterance] commence par le nom de l'assistant (éventuellement précédé de « hé », « ok », « dis »),
     * renvoie le reste de la phrase ; sinon null. Tolère une petite erreur de transcription.
     */
    fun stripWakeName(utterance: String, name: String): String? {
        val tokens = FrenchText.normalize(utterance).split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return null
        val prefixes = setOf("he", "hey", "eh", "ok", "okay", "dis", "allo", "bonjour", "salut")
        if (tokens.size > 1 && FrenchText.key(tokens[0]) in prefixes) tokens.removeAt(0)
        val nameTokens = FrenchText.key(name).split(' ')
        if (tokens.size < nameTokens.size) return null
        val head = FrenchText.key(tokens.take(nameTokens.size).joinToString(" "))
        val target = nameTokens.joinToString(" ")
        val tolerance = if (target.length >= 6) 2 else if (target.length >= 4) 1 else 0
        if (FrenchText.editDistance(head, target) > tolerance) return null
        return tokens.drop(nameTokens.size).joinToString(" ").trim()
    }
}
