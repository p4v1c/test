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
            "pour avoir des exemples. Vous pouvez toujours dire « stop » pour m'interrompre, et « répète » si vous n'avez pas entendu." +
            (if (topics.any { it.title == "la prise en main" }) " Pour un entraînement guidé, dites « tutoriel »." else "")
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
        val VISION = HelpTopic(
            "la caméra", listOf("camera", "vision", "lire", "voir", "billet", "couleur", "courrier"),
            listOf("qu'est-ce qu'il y a devant moi ?", "lis ce texte", "c'est quoi cette lettre ?", "relis le montant", "page suivante",
                "c'est quel billet ?", "c'est quelle couleur ?", "est-ce que ça va ensemble ?", "c'est quoi ce produit ?",
                "c'est quel médicament ?", "détecteur de lumière", "où sont mes clés ?"),
        )
        val ORIENTATION = HelpTopic(
            "l'orientation", listOf("orientation", "ou suis je", "position", "lieux", "guidage", "pharmacie", "adresse"),
            listOf("où suis-je ?", "où est la pharmacie la plus proche ?", "enregistre ici comme maison", "à quelle distance est la maison ?",
                "emmène-moi à la boulangerie", "envoie ma position à mon fils", "quels sont mes lieux ?"),
        )
        val NEWS = HelpTopic(
            "la météo, les infos et les recherches", listOf("meteo", "infos", "actualites", "nouvelles", "recherche", "internet", "calcul"),
            listOf("quel temps fait-il ?", "va-t-il pleuvoir demain ?", "la météo de la semaine", "météo à Lyon", "les infos",
                "les infos sport", "lis le deuxième", "lis l'article", "la suite", "cherche sur internet les horaires de la déchetterie",
                "combien font 12 fois 7 ?", "100 euros en francs"),
        )
        val READING = HelpTopic(
            "les livres et la radio", listOf("livres", "livre", "lecture", "radio", "podcasts", "musique", "signets", "victor"),
            listOf("quels livres ai-je ?", "lis le livre Les Misérables", "reprends mon livre", "chapitre suivant", "va au chapitre 5",
                "va à la page 42", "avance de 5 minutes", "où j'en suis ?", "pose un signet", "va au signet 2", "lis plus vite",
                "arrête la lecture dans 30 minutes", "mets France Inter", "abonne-moi au podcast Affaires sensibles",
                "écoute le podcast Affaires sensibles", "mets de la musique", "pause", "reprends"),
        )
        val NOTES = HelpTopic(
            "les mémos, notes, agenda et mails", listOf("memos", "memo", "notes", "courses", "agenda", "rendez vous", "mails", "mail", "courriels"),
            listOf("enregistre un mémo", "écoute mes mémos", "note que le code du portail est 1234", "lis mes notes",
                "ajoute du pain et du lait à la liste", "lis la liste de courses", "qu'est-ce que j'ai demain ?",
                "ajoute un rendez-vous chez le dentiste mardi à 14 heures", "fais-moi le point", "lis mes mails", "lis le premier",
                "réponds", "écris un mail à Paul"),
        )
        val MAINTENANCE = HelpTopic(
            "la prise en main", listOf("tutoriel", "prise en main", "probleme", "sauvegarde", "diagnostic", "apprendre"),
            listOf("tutoriel", "reprends le tutoriel", "est-ce que tout marche ?", "signale un problème", "sauvegarde mes réglages"),
        )
        val PHASE7 = HelpCatalog(listOf(CALLS, MESSAGES, READING, NOTES, REMINDERS, VISION, ORIENTATION, NEWS, INFOS, QUESTIONS, SAFETY, TRIGGERS, SETTINGS, MAINTENANCE))
        val PHASE6 = HelpCatalog(listOf(CALLS, MESSAGES, READING, NOTES, REMINDERS, VISION, ORIENTATION, NEWS, INFOS, QUESTIONS, SAFETY, TRIGGERS, SETTINGS))
        val PHASE5 = HelpCatalog(listOf(CALLS, MESSAGES, REMINDERS, VISION, ORIENTATION, NEWS, INFOS, QUESTIONS, SAFETY, TRIGGERS, SETTINGS))
        val PHASE1 = HelpCatalog(listOf(INFOS, QUESTIONS, SETTINGS))
        val PHASE3 = HelpCatalog(listOf(CALLS, MESSAGES, REMINDERS, INFOS, QUESTIONS, SAFETY, TRIGGERS, SETTINGS))
        val PHASE4 = HelpCatalog(listOf(CALLS, MESSAGES, REMINDERS, VISION, INFOS, QUESTIONS, SAFETY, TRIGGERS, SETTINGS))
        val PHASE2 = HelpCatalog(listOf(INFOS, QUESTIONS, TRIGGERS, SETTINGS))
    }
}
