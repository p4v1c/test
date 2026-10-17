package fr.assistantvocal.core.phone

import fr.assistantvocal.core.text.FrenchText

/**
 * Repère les messages qui ressemblent à une arnaque (SMS, notifications) avec des règles locales :
 * liens, faux colis, CPF, Ameli, amendes, « j'ai changé de numéro », demandes d'argent ou de code.
 * Ne bloque rien : ajoute seulement un avertissement à la lecture.
 */
object ScamDetector {
    data class Verdict(val suspicious: Boolean, val reasons: List<String>) {
        /** Avertissement à lire après le message, ou chaîne vide. */
        val warning: String
            get() = if (!suspicious) "" else
                "Attention, ce message ressemble à une arnaque : ${reasons.joinToString(", ")}. " +
                    "Ne cliquez sur aucun lien, ne rappelez pas et ne donnez aucun code. En cas de doute, demandez à un proche."
    }

    private val themes = listOf(
        Regex("\\b(colis|livraison|livreur|frais de (port|douane|reexpedition)|chronopost|colissimo|dhl|ups|mondial relay)\\b") to "il parle d'un colis",
        Regex("\\b(cpf|compte (personnel de )?formation|droits? a la formation)\\b") to "il parle du compte formation",
        Regex("\\b(ameli|carte vitale|assurance maladie|mutuelle)\\b") to "il parle de l'assurance maladie",
        Regex("\\b(amende|antai|contravention|infraction|majoration)\\b") to "il parle d'une amende",
        Regex("\\b(impots?|remboursement|trop percu|caf|retraite)\\b") to "il promet un remboursement",
        Regex("\\b(compte (a ete )?(suspendu|bloque|desactive)|activite inhabituelle|mettre a jour vos informations|confirmer vos (informations|coordonnees))\\b") to "il parle d'un compte bloqué",
        Regex("\\b(code (de )?(verification|confirmation|securite)|ne (le )?communiquez)\\b") to "il demande un code",
        Regex("\\b(change de numero|nouveau numero|mon telephone est (casse|tombe))\\b") to "quelqu'un prétend avoir changé de numéro",
        Regex("\\b(virement|western union|mandat cash|carte (cadeau|pcs|transcash|neosurf)|coupon|bitcoin|crypto)\\b") to "il demande de l'argent",
        Regex("\\b(gagne|gagnant|felicitations|cadeau offert|tirage au sort|loterie)\\b") to "il annonce un gain",
        Regex("\\b(urgent|immediatement|sous 24 ?h|dernier (rappel|avis)|derniere relance)\\b") to "il est très pressant",
    )
    private val link = Regex("(https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|fr|info|xyz|top|site|online|link|ly|cc|me|app|vip|shop)\\b)")

    fun check(text: String, senderKnown: Boolean): Verdict {
        val k = FrenchText.stripAccents(text.lowercase())
        val reasons = themes.filter { it.first.containsMatchIn(k) }.map { it.second }.toMutableList()
        val hasLink = link.containsMatchIn(k)
        if (hasLink) reasons.add(0, "il contient un lien")
        val score = reasons.size + (if (hasLink) 1 else 0) + (if (!senderKnown) 1 else 0)
        // Un proche connu qui parle d'un colis n'est pas suspect ; un inconnu avec lien et thème l'est.
        val suspicious = score >= 3 || (hasLink && !senderKnown && reasons.size >= 2) ||
            reasons.any { it.contains("changé de numéro") || it.contains("argent") || it.contains("code") } && !senderKnown
        return Verdict(suspicious, reasons.take(3))
    }
}
