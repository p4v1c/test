package fr.assistantvocal.core.assistant

import fr.assistantvocal.core.text.FrenchText

/** Une catégorie d'aide : [keywords] servent à reconnaître « aide pour les appels ». */
data class HelpTopic(val title: String, val keywords: List<String>, val examples: List<String>)

/** Liste des fonctions disponibles, lue à voix haute. Les phases suivantes ajoutent des catégories. */
class HelpCatalog(private val topics: List<HelpTopic>) {

    fun overview(name: String): String {
        val titles = topics.map { it.title }
        val list = when (titles.size) {
            0 -> ""
            1 -> titles[0]
            else -> titles.dropLast(1).joinToString(", ") + " et " + titles.last()
        }
        return "Je peux vous aider pour : $list. Dites par exemple « $name, aide pour ${topics.firstOrNull()?.keywords?.firstOrNull() ?: "les réglages"} » " +
            "pour avoir des exemples. Vous pouvez toujours dire « stop » pour m'interrompre, et « répète » si vous n'avez pas entendu."
    }

    fun topic(query: String): String? {
        val q = FrenchText.key(query)
        val topic = topics.firstOrNull { t -> t.keywords.any { k -> q.contains(FrenchText.key(k)) } } ?: return null
        return "Pour ${topic.title}, vous pouvez dire : " + topic.examples.joinToString(", ou ") { "« $it »" } + "."
    }

    companion object {
        val INFOS = HelpTopic(
            "l'heure, la date et la batterie", listOf("heure", "date", "batterie", "infos", "informations"),
            listOf("quelle heure est-il ?", "quel jour sommes-nous ?", "combien de batterie ?", "est-ce que j'ai internet ?"),
        )
        val SETTINGS = HelpTopic(
            "les réglages de ma voix", listOf("reglages", "voix", "parametres", "vitesse"),
            listOf("parle plus vite", "parle moins vite", "parle plus fort", "sois plus bref", "donne plus de détails",
                "change ton nom en Orphée", "apprends mon nom", "n'écoute plus ton nom", "active le mode privé"),
        )
        val QUESTIONS = HelpTopic(
            "les questions de tous les jours", listOf("questions", "question", "culture", "recettes"),
            listOf("comment faire une pâte à crêpes ?", "c'est quoi une hernie discale ?", "raconte-moi une histoire courte"),
        )
        val TRIGGERS = HelpTopic(
            "les façons de m'appeler", listOf("appeler", "declencher", "activer", "bouton"),
            listOf("dites mon nom puis attendez le bip", "appuyez sur le bouton du casque",
                "gardez le bouton volume bas appuyé une seconde", "appuyez sur le grand bouton de l'écran"),
        )
        val CALLS = HelpTopic(
            "les appels", listOf("appels", "appel", "telephone", "telephoner"),
            listOf("appelle mon fils", "appelle Paul Martin", "qui m'a appelé ?", "rappelle-le", "décroche", "raccroche",
                "mets le haut-parleur", "Pierre, c'est mon fils"),
        )
        val MESSAGES = HelpTopic(
            "les messages", listOf("messages", "sms", "texto", "notifications", "ecrire"),
            listOf("lis mes messages", "lis mon dernier message", "envoie un message à Marie pour lui dire que j'arrive",
                "réponds", "lis mes notifications"),
        )
        val REMINDERS = HelpTopic(
            "les réveils, minuteurs et rappels", listOf("rappels", "rappel", "reveil", "minuteur", "medicaments", "medicament"),
            listOf("réveille-moi demain à 7 heures", "minuteur de 10 minutes pour les pâtes", "combien de temps reste-t-il ?",
                "rappelle-moi d'appeler le médecin demain à 10 heures", "rappelle-moi de prendre mon Doliprane tous les jours à 8 heures",
                "j'ai pris mon Doliprane", "ai-je pris mon médicament ce matin ?", "quels sont mes rappels ?"),
        )
        val SAFETY = HelpTopic(
            "la sécurité", listOf("securite", "secours", "urgence", "sos"),
            listOf("au secours : j'alerte vos proches après 10 secondes, dites stop pour annuler", "je suis tombé"),
        )
        val PHASE1 = HelpCatalog(listOf(INFOS, QUESTIONS, SETTINGS))
        val PHASE3 = HelpCatalog(listOf(CALLS, MESSAGES, REMINDERS, INFOS, QUESTIONS, SAFETY, TRIGGERS, SETTINGS))
        val PHASE2 = HelpCatalog(listOf(INFOS, QUESTIONS, TRIGGERS, SETTINGS))
    }
}
