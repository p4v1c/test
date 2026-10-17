package fr.assistantvocal.core.router

import fr.assistantvocal.core.text.FrenchText

/** Intentions reconnues localement, sans appel à l'IA. */
sealed interface Intent {
    data object AskTime : Intent
    data object AskDate : Intent
    data object AskBattery : Intent
    data object AskNetwork : Intent
    data object Stop : Intent
    data object Cancel : Intent
    data object Repeat : Intent
    data object Help : Intent
    data class HelpCategory(val category: String) : Intent
    data object WhoAreYou : Intent
    data object Greeting : Intent
    data object Thanks : Intent
    data class SpeechRate(val faster: Boolean) : Intent
    data class Volume(val louder: Boolean) : Intent
    data class SetVerbosity(val brief: Boolean) : Intent
    data class ChangeName(val newName: String) : Intent
    data class PrivateMode(val enable: Boolean) : Intent
    /** Apprendre la façon dont la personne prononce le nom (mot d'activation). */
    data object CalibrateName : Intent
    data class WakeWord(val enable: Boolean) : Intent
}

/** [confidence] entre 0 et 1. ≥ 0.8 : on agit ; entre 0.5 et 0.8 : on vérifie (IA ou question fermée). */
data class RouteMatch(val intent: Intent, val confidence: Double)

/**
 * Routeur à règles pour le français parlé. Volontairement prudent : une phrase longue qui contient
 * un mot-clé (« quelle heure commence le match ») obtient une confiance moyenne et sera vérifiée.
 */
class LocalRouter {
    private val tasks = TaskRouter()

    private data class Rule(val intent: (MatchResult, String) -> Intent?, val regex: Regex, val strong: Boolean = false)

    private fun r(pattern: String) = Regex(pattern)

    private val rules: List<Rule> = listOf(
        // Arrêt et annulation : toujours prioritaires
        Rule({ _, _ -> Intent.Stop }, r("^(stop|stoppe|arrete|arrete toi|arrete de parler|tais toi|silence|chut|ca suffit)$"), strong = true),
        Rule({ _, _ -> Intent.Cancel }, r("^(annule|annuler|laisse tomber|oublie|oublie ca|non merci|rien|c est tout)$"), strong = true),
        Rule({ _, _ -> Intent.Repeat }, r("\\b(repete|repeter|redis|redire|qu est ce que tu as dit|j ai pas compris ce que tu as dit|tu peux repeter)\\b")),

        Rule({ _, _ -> Intent.AskTime }, r("\\b(quelle heure|l heure|il est quelle heure|heure qu il est|donne moi l heure)\\b")),
        Rule({ _, _ -> Intent.AskDate }, r("\\b(quel jour|quelle date|on est le combien|nous sommes le combien|la date|quel mois|quelle annee|date d aujourd hui|on est quel jour)\\b")),
        Rule({ _, _ -> Intent.AskBattery }, r("\\b(batterie|niveau de charge|il me reste combien de charge|est ce que je dois charger)\\b")),
        Rule({ _, _ -> Intent.AskNetwork }, r("\\b(j ai internet|j ai du reseau|est ce qu il y a internet|connexion internet|le reseau|j ai la 4g|j ai la 5g|j ai le wifi|suis je connecte)\\b")),

        Rule({ _, _ -> Intent.SpeechRate(faster = true) }, r("\\b(parle|parles|lis)( un peu)? plus (vite|rapidement)\\b|\\baccelere\\b")),
        Rule({ _, _ -> Intent.SpeechRate(faster = false) }, r("\\b(parle|parles|lis)( un peu)? (moins vite|plus lentement|plus doucement)\\b|\\bralentis\\b")),
        Rule({ _, _ -> Intent.Volume(louder = true) }, r("\\b(parle|parles)( un peu)? plus fort\\b|\\b(monte|augmente) le (son|volume)\\b|^plus fort$")),
        Rule({ _, _ -> Intent.Volume(louder = false) }, r("\\b(parle|parles)( un peu)? moins fort\\b|\\b(baisse|diminue) le (son|volume)\\b|^moins fort$")),
        Rule({ _, _ -> Intent.SetVerbosity(brief = true) }, r("\\b(sois|soit|reponds?) (plus )?(bref|breve|court|courte|concis|concise)\\b|\\bplus court\\b|\\bmoins de details\\b")),
        Rule({ _, _ -> Intent.SetVerbosity(brief = false) }, r("\\b(plus de details|plus detaille|donne (moi )?plus de details|sois plus precis|reponses plus longues)\\b")),

        Rule({ _, _ -> Intent.PrivateMode(enable = false) }, r("\\b(desactive|quitte|sors du|arrete|enleve|coupe) (le )?mode prive\\b|\\bmode prive (desactive|off)\\b")),
        Rule({ _, _ -> Intent.PrivateMode(enable = true) }, r("\\b(active|passe en|mets? (toi )?en|mode) (le )?(mode )?prive\\b")),

        Rule({ _, _ -> Intent.CalibrateName }, r("\\b(teste|tester|calibre|calibrer|apprends|apprendre|entraine|reapprends) (mon|ton|le) nom\\b|\\b(regle|reglage du) mot d activation\\b")),
        Rule({ _, _ -> Intent.WakeWord(enable = false) }, r("\\b(n ecoute plus|arrete d ecouter|desactive|coupe) (ton nom|le mot d activation|l ecoute)\\b|\\bne reagis plus a ton nom\\b")),
        Rule({ _, _ -> Intent.WakeWord(enable = true) }, r("\\b(reactive|active|remets) (ton nom|le mot d activation|l ecoute)\\b|\\becoute (a nouveau|de nouveau) ton nom\\b")),

        Rule({ _, _ -> Intent.Help }, r("\\b(qu est ce que tu sais faire|que sais tu faire|que peux tu faire|qu est ce que tu peux faire|tu sais faire quoi|aide moi a t utiliser|^aide$|liste des commandes|comment ca marche)\\b")),
        Rule({ _, _ -> Intent.WhoAreYou }, r("\\b(qui es tu|qui est tu|t es qui|comment tu t appelles|comment t appelles tu|quel est ton nom|c est quoi ton nom)\\b")),
        Rule({ _, _ -> Intent.Thanks }, r("^(merci|merci beaucoup|merci bien|c est gentil|super merci)$"), strong = true),
        Rule({ _, _ -> Intent.Greeting }, r("^(bonjour|salut|bonsoir|coucou|hello)$"), strong = true),
    )

    private val changeName = Regex(
        "^(?:change|modifie|changer) (?:ton|de) nom (?:en|pour|par) (.+)$|" +
            "^(?:appelle toi|tu t appelleras|tu t appelles maintenant|je vais t appeler|ton nouveau nom (?:est|sera)|desormais tu t appelles) (.+)$"
    )

    private val helpCategory = Regex("\\b(?:aide|qu est ce que tu sais faire|que sais tu faire) (?:pour|avec|sur|en|dans) (?:les |la |le |l )?(.+)$")

    fun route(utterance: String): RouteMatch? {
        val key = FrenchText.key(utterance)
        if (key.isEmpty()) return null
        val wordCount = key.split(' ').size

        // Le changement de nom garde la casse et les accents d'origine du nouveau nom.
        changeName.find(key)?.let { m ->
            val keyName = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val original = recoverOriginal(utterance, keyName)
            if (original.isNotBlank()) return RouteMatch(Intent.ChangeName(original), 0.95)
        }
        helpCategory.find(key)?.let { m -> return RouteMatch(Intent.HelpCategory(m.groupValues[1].trim()), 0.9) }
        tasks.route(utterance)?.let { return RouteMatch(it, 0.93) }

        for (rule in rules) {
            val m = rule.regex.find(key) ?: continue
            val intent = rule.intent(m, key) ?: continue
            val confidence = when {
                rule.strong -> 0.97
                wordCount <= 6 -> 0.9
                wordCount <= 9 -> 0.7
                else -> 0.55
            }
            return RouteMatch(intent, confidence)
        }
        return null
    }

    /** Retrouve dans la phrase d'origine (avec accents) le segment correspondant au nom extrait. */
    private fun recoverOriginal(utterance: String, keyName: String): String {
        val words = FrenchText.normalize(utterance).split(' ').filter { it.isNotEmpty() }
        val n = keyName.split(' ').size
        val tail = words.takeLast(n).joinToString(" ")
        val cleaned = if (FrenchText.key(tail) == keyName) tail else keyName
        return cleaned.trim().trimEnd('.', '!', '?')
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }
}
