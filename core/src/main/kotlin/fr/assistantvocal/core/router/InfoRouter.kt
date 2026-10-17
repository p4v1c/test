package fr.assistantvocal.core.router

import fr.assistantvocal.core.info.Calculator
import fr.assistantvocal.core.info.PlaceCategory
import fr.assistantvocal.core.text.FrenchText

// ---------------------------------------------------------------------- intentions de la phase 5

/** Intentions « infos et orientation » (traitées par InfoSkill, avec accès réseau). */
sealed interface InfoIntent : Intent

data object WhereAmI : InfoIntent
data class Nearby(val category: PlaceCategory) : InfoIntent
data class SavePlace(val name: String) : InfoIntent
data class ForgetPlace(val name: String) : InfoIntent
data object ListPlaces : InfoIntent
data class PlaceInfo(val name: String) : InfoIntent
data class Navigate(val target: String) : InfoIntent
data class ShareLocation(val target: String) : InfoIntent
data class Weather(val text: String, val city: String?) : InfoIntent
data class News(val topic: String?) : InfoIntent
data object NewsMore : InfoIntent
/** [index] : 1 = premier titre, -1 = dernier, null = celui dont on parle. [full] : lire l'article lui-même. */
data class NewsDetail(val index: Int?, val full: Boolean) : InfoIntent
data class SearchWeb(val query: String) : InfoIntent
data class Calculate(val text: String) : InfoIntent

/** Reconnaît les demandes d'orientation, de météo, d'actualités, de recherche et de calcul. */
class InfoRouter {
    private fun r(p: String) = Regex(p)

    private val share = r("^(?:envoie|envoyer|partage|partager|transmets|donne)(?: ma| la| mon)? (?:position|localisation|emplacement)(?: actuelle)? (?:a|au|aux|avec) (.+)$")
    private val share2 = r("^(?:dis|dire|envoie un message|envoie un sms|previens)(?: a| au)? (.+?) (?:pour (?:lui |leur )?dire )?ou je suis$")
    private val whereAmI = r("^(?:dis moi |tu peux me dire )?(?:ou (?:suis je|je suis|sommes nous|on est|est ce que je suis|est ce qu on est)|je suis ou|on est ou|quelle est mon adresse|c est quoi mon adresse|dans quelle rue (?:suis je|je suis|on est|sommes nous)|dans quelle ville (?:suis je|je suis|on est)|ma position)(?: actuellement| maintenant| exactement| en ce moment| actuelle)?$")
    private val savePlace = r("^(?:enregistre|memorise|retiens|sauvegarde|note)(?: ma position| cet endroit| ici| l endroit ou je suis| ce lieu| la position)(?: comme| sous le nom| en tant que| c est| pour)? (.+)$")
    private val forgetPlace = r("^(?:oublie|supprime|efface)(?: le lieu| l endroit| la position) (.+)$")
    private val listPlaces = r("\\b(mes lieux|quels lieux|lieux enregistres|endroits enregistres|mes endroits)\\b")
    private val placeInfo = r("^(?:a quelle distance (?:est|se trouve|suis je de|je suis de|on est de)|dans quelle direction (?:est|se trouve)|c est loin|est ce que c est loin|je suis loin de|suis je loin de)(?: d| de)? (.+?)(?: d ici)?$")
    private val whereHome = r("^ou (?:est|se trouve) (la maison|chez moi|le lieu .+)$")
    private val navigate = r("^(?:emmene moi|guide moi|conduis moi|amene moi|itineraire|l itineraire|comment aller|comment je vais|je veux aller|je voudrais aller|montre moi le chemin|lance le guidage|guidage)(?: jusqu)?(?: a| au| aux| chez| vers| pour aller a| pour aller au| pour| jusqu a)? (.+)$")
    private val nearbyTrigger = r("\\b(plus proche|plus pres|pres d ici|pres de chez moi|a cote|autour de moi|autour d ici|dans le coin|ou est|ou se trouve|ou sont|y a t il|est ce qu il y a|il y a|trouve moi|cherche|ou (?:puis je|je peux|est ce que je peux) (?:trouver|acheter|aller))\\b")

    private val weather = r("\\b(meteo|quel temps|temps fait il|temps va t il faire|temps fera t il|temps il fait|temps il va faire|va t il pleuvoir|il va pleuvoir|est ce qu il va pleuvoir|il pleut|pleuvoir|pleuvra|va t il neiger|il va neiger|neiger|parapluie|temperature (?:exterieure|dehors|aujourd hui|demain)|quelle temperature|combien de degres|il fait (?:chaud|froid|beau) dehors|fait il (?:chaud|froid|beau)|il va faire (?:chaud|froid|beau)|va t il faire (?:chaud|froid|beau)|fera t il (?:chaud|froid|beau)|previsions)\\b")
    private val weatherCity = r("\\b(?:a|au|sur|pour|de) ((?!quelle|combien|la maison|demain|apres demain|aujourd|ce |cette |la semaine|l heure|la region|ma region|chez moi|ici|maintenant|midi|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|matin|apres midi)[a-z].*)$")
    private val dayWords = r("\\s+(?:demain|apres demain|aujourd hui|ce soir|cette nuit|ce week end|ce weekend|cette semaine|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|matin|apres midi)(?:\\s.*)?$")

    private val news = r("^(?:(?:donne|lis|dis|mets|raconte|lance)(?: moi)? |quelles sont |c est quoi |je veux |j aimerais |je voudrais )?(?:les |le |un |des )?(infos|informations|actualites|actus|nouvelles|titres|journal|flash info|flash|dernieres nouvelles|dernieres infos|derniers titres)(?: .*)?$|\\b(?:quoi de neuf dans le monde|qu est ce qui se passe (?:dans le monde|en france|aujourd hui))\\b")
    private val newsMore = r("^(?:la suite|d autres titres|les titres suivants|autres titres|la suite des (?:infos|titres|nouvelles|informations)|titres suivants|les suivants)$")
    private val newsFull = r("^(?:(?:lis|lire|resume|resumer|raconte)(?: moi)? (?:tout )?l article(?: en entier| complet| entier)?|en savoir plus|la suite de l article|dis m en plus|raconte m en plus|developpe)$")
    private val newsDetail = r("^(?:lis|lire|raconte|detaille|explique|developpe|plus de details sur|parle moi)(?: moi)?(?: de| du| sur)? (?:le |la |l )?(?:article |titre |info |information |nouvelle )?(?:numero )?([a-z0-9]+)(?: article| titre| info| information| nouvelle)?$")
    private val ordinals = mapOf(
        "premier" to 1, "premiere" to 1, "1" to 1, "1er" to 1, "un" to 1, "une" to 1,
        "deuxieme" to 2, "second" to 2, "seconde" to 2, "2" to 2, "deux" to 2,
        "troisieme" to 3, "3" to 3, "trois" to 3, "quatrieme" to 4, "4" to 4, "quatre" to 4,
        "cinquieme" to 5, "5" to 5, "cinq" to 5, "dernier" to -1, "derniere" to -1,
    )

    private val search1 = r("^(?:cherche|recherche|regarde|trouve)(?: moi)?(?: sur| dans)? (?:internet|google|le web|wikipedia)(?: ce qu est| ce que c est| qui est| des informations sur| des infos sur| sur| si)? (.+)$")
    private val search2 = r("^(?:cherche|recherche|regarde)(?: moi)? (.+?) (?:sur|dans) (?:internet|google|le web|wikipedia)$")
    private val search3 = r("^(?:que dit|qu est ce que dit) wikipedia (?:sur|de|a propos de|a propos d) (.+)$")

    private val calcHint = r("(\\d.*\\b(plus|moins|fois|x|divise|multiplie|sur|pour cent|pourcent|au carre|au cube)\\b.*\\d|\\d\\s*[-+*/x×÷%]\\s*\\d|racine|\\d.*\\bau (carre|cube)\\b|\\d.*(euros?|francs) en (francs|euros?)|^(combien (font|fait)|calcule))")

    fun route(utterance: String): InfoIntent? {
        val raw = FrenchText.normalize(utterance).replace('’', '\'')
        val k = FrenchText.stripAccentsSameLength(raw).replace('\'', ' ').replace('-', ' ').trim()
        val rawT = raw.trim()
        fun grp(m: MatchResult, i: Int) = m.groups[i]?.range?.let { rawT.substring(it).trim() }?.takeIf { it.isNotEmpty() }
        if (k.isEmpty()) return null

        if (calcHint.containsMatchIn(FrenchText.key(FrenchText.replaceNumberWords(Calculator.protectPercent(utterance)))) || Regex("\\d").containsMatchIn(k) && Regex("[-+*/×÷%]").containsMatchIn(utterance)) {
            if (Calculator.evaluate(utterance) != null) return Calculate(utterance)
        }

        share.find(k)?.let { return ShareLocation(grp(it, 1)!!) }
        share2.find(k)?.let { return ShareLocation(grp(it, 1)!!) }
        if (whereAmI.matches(k)) return WhereAmI
        savePlace.find(k)?.let { return SavePlace(grp(it, 1)!!) }
        forgetPlace.find(k)?.let { return ForgetPlace(grp(it, 1)!!) }
        if (listPlaces.containsMatchIn(k)) return ListPlaces
        placeInfo.find(k)?.let { return PlaceInfo(grp(it, 1)!!) }
        whereHome.find(k)?.let { return PlaceInfo(grp(it, 1)!!.removePrefix("le lieu ").trim()) }
        navigate.find(k)?.let { return Navigate(grp(it, 1)!!) }

        if (weather.containsMatchIn(k)) {
            val city = weatherCity.find(k)?.let { m ->
                val start = m.groups[1]!!.range.first
                val rest = k.substring(start).replace(dayWords, "")
                rawT.substring(start, start + rest.length).trim().takeIf { it.isNotEmpty() && FrenchText.key(it) !in setOf("maison", "la maison", "dehors", "ici") }
            }
            return Weather(rawT, city)
        }

        if (newsMore.matches(k)) return NewsMore
        if (newsFull.matches(k)) return NewsDetail(null, full = true)
        newsDetail.find(k)?.let { m -> ordinals[m.groupValues[1]]?.let { return NewsDetail(it, full = false) } }
        news.find(k)?.let { m ->
            // « les informations sur la retraite » est une question, pas les actualités.
            val rest = m.groups[1]?.let { k.substring(it.range.last + 1) } ?: ""
            if (!(Regex("^ (sur|de|du|des|concernant|a propos|pour)\\b").containsMatchIn(rest) && topicOf(rest) == null)) return News(topicOf(k))
        }

        (search1.find(k) ?: search2.find(k) ?: search3.find(k))?.let { return SearchWeb(grp(it, 1)!!) }

        // Lieux proches : une catégorie connue + un mot comme « plus proche », « où est », « il y a ».
        PlaceCategory.find(k)?.let { c -> if (nearbyTrigger.containsMatchIn(k)) return Nearby(c) }
        return null
    }

    private fun topicOf(k: String): String? {
        val topics = mapOf(
            "sport" to "sport", "sports" to "sport", "foot" to "sport", "football" to "sport", "rugby" to "sport",
            "economie" to "économie", "economiques" to "économie", "sante" to "santé", "monde" to "monde", "international" to "monde",
            "internationales" to "monde", "politique" to "politique", "politiques" to "politique", "culture" to "culture",
            "culturelles" to "culture", "sciences" to "sciences", "science" to "sciences", "region" to "région",
            "regionales" to "région", "locales" to "région", "regionale" to "région", "locale" to "région", "departement" to "région",
        )
        return k.split(' ').firstNotNullOfOrNull { topics[it] }
    }
}
