package fr.assistantvocal.core.router

import fr.assistantvocal.core.text.FrenchText
import fr.assistantvocal.core.time.FrenchTime

// ---------------------------------------------------------------------- intentions de la phase 6

/** Livres, radio, podcasts, musique, mémos. */
sealed interface MediaIntent : Intent
data object ListBooks : MediaIntent
/** [title] null : reprendre le dernier livre. */
data class OpenBook(val title: String?) : MediaIntent
data object ResumeMedia : MediaIntent
data object PauseMedia : MediaIntent
data object NextItem : MediaIntent
data object PreviousItem : MediaIntent
data class NextHeading(val chapter: Boolean) : MediaIntent
data class PreviousHeading(val chapter: Boolean) : MediaIntent
data class GoToHeading(val number: Int) : MediaIntent
data class GoToPage(val label: String) : MediaIntent
data class GoToEdge(val end: Boolean, val chapter: Boolean) : MediaIntent
data class Jump(val deltaMs: Long) : MediaIntent
data object WhereInBook : MediaIntent
data object AddBookmark : MediaIntent
data class GoToBookmark(val number: Int) : MediaIntent
data object ListBookmarks : MediaIntent
data class DeleteBookmark(val number: Int?) : MediaIntent
/** [faster] null : vitesse normale. */
data class PlaybackSpeed(val faster: Boolean?) : MediaIntent
/** [minutes] null : annuler la minuterie de sommeil. */
data class SleepTimer(val minutes: Int?) : MediaIntent
data class PlayRadio(val name: String?) : MediaIntent
data object AddFavoriteStation : MediaIntent
data object ListStations : MediaIntent
data class SubscribePodcast(val name: String) : MediaIntent
data class UnsubscribePodcast(val name: String) : MediaIntent
data object ListPodcasts : MediaIntent
data class PlayPodcast(val name: String?) : MediaIntent
data class PlayMusic(val query: String?) : MediaIntent
/** [name] null : tous les podcasts abonnés. */
data class DownloadPodcasts(val name: String?) : MediaIntent
data object ListDownloads : MediaIntent
data object DeleteDownloads : MediaIntent
data object RecordMemo : MediaIntent
data object PlayMemos : MediaIntent
data object DeleteMemo : MediaIntent

/** Notes, liste de courses, agenda, point du jour. */
sealed interface OrganizerIntent : Intent
data class AddNote(val text: String) : OrganizerIntent
data object ReadNotes : OrganizerIntent
data class DeleteNote(val number: Int?) : OrganizerIntent
data class AddToList(val items: String) : OrganizerIntent
data object ReadList : OrganizerIntent
data class RemoveFromList(val item: String) : OrganizerIntent
data object ClearList : OrganizerIntent
data class Agenda(val text: String) : OrganizerIntent
data class AddEvent(val text: String) : OrganizerIntent
data object Briefing : OrganizerIntent

/** Courriels (Gmail par IMAP). */
sealed interface MailIntent : Intent
data object ReadMail : MailIntent
data class ReadMailBody(val index: Int?) : MailIntent
data object SummarizeMail : MailIntent
data class SendMail(val target: String, val body: String?) : MailIntent

/** Reconnaît les commandes de la phase 6. [early] : celles qui doivent passer avant les minuteurs et rappels. */
class MediaRouter {
    private fun r(p: String) = Regex(p)
    private val ordinals = mapOf("premier" to 1, "premiere" to 1, "1" to 1, "un" to 1, "deuxieme" to 2, "second" to 2, "seconde" to 2, "2" to 2,
        "troisieme" to 3, "3" to 3, "quatrieme" to 4, "4" to 4, "cinquieme" to 5, "5" to 5, "dernier" to -1, "derniere" to -1)

    // Minuterie de sommeil (avant les minuteurs)
    private val sleep = r("\\b(minuterie de (?:sommeil|veille|mise en veille)|mise en veille|arrete (?:la lecture|le livre|la radio|la musique|le podcast|tout) dans|coupe (?:la radio|la musique|le livre) dans|je vais m endormir|endors moi)\\b")
    private val sleepCancel = r("\\b(annule|arrete|supprime|desactive|enleve) (?:la )?(?:minuterie de (?:sommeil|veille|mise en veille)|mise en veille)\\b")

    // Lecture
    private val listBooks = r("^(?:quels (?:livres|sont mes livres)(?: ai je| j ai)?|mes livres|ma bibliotheque|la bibliotheque|liste (?:des|de mes) livres|qu est ce que j ai comme livres|quels livres)$")
    private val openBook = r("^(?:lis|lire|ecoute|ecouter|mets|mettre|ouvre|ouvrir|joue|lance|reprends|continue)(?: moi)? (?:le livre texte|le livre|mon livre|le roman|l audiolivre|le livre audio|l audio livre|le document|le pdf|le fichier|le texte)(?: de| d)? ?(.*)$")
    private val resumeBook = r("^(?:reprends|continue|relance|reprendre|continuer)(?: la lecture| ma lecture| mon livre| le livre| la lecture du livre| l ecoute)?$|^(?:lecture|play)$")
    private val pause = r("^(?:pause|mets en pause|mets pause|pause la lecture|arrete la lecture|stop la lecture|arrete le livre|arrete la radio|coupe la radio|eteins la radio|arrete la musique|coupe la musique|stoppe la musique|arrete le podcast|coupe le son de la radio)$")
    private val next = r("^(?:suivant|suivante|piste suivante|morceau suivant|chanson suivante|titre suivant|fichier suivant|episode suivant|memo suivant|radio suivante|station suivante|change de radio|autre radio|passe au suivant|passe a la suivante)$")
    private val previous = r("^(?:precedent|precedente|piste precedente|morceau precedent|chanson precedente|titre precedent|fichier precedent|episode precedent|memo precedent|radio precedente|reviens au precedent)$")
    private val nextHeading = r("^(?:(?:passe |va |aller )?(?:au |a la )?)?(chapitre|section|partie) suivante?$|^(?:passe|va|aller) (?:au|a la) (chapitre|section|partie) suivante?$")
    private val prevHeading = r("^(?:(?:reviens |retourne |va |aller )?(?:au |a la )?)?(chapitre|section|partie) precedente?$|^(?:reviens|retourne|va) (?:au|a la) (chapitre|section|partie) precedente?$")
    private val goHeading = r("^(?:va|aller|vas|retourne|passe|lis|mets)?(?: moi)? ?(?:au|a la|le|la)? ?(chapitre|section|partie) (?:numero )?(\\d+)$")
    private val goPage = r("^(?:va|aller|vas|retourne|passe)?(?: a la)? ?page (?:numero )?(?!suivante|precedente|d accueil)(\\w+)$")
    private val edge = r("^(?:va |retourne |reviens |aller )?(?:au|a la) (debut|fin) (?:du|de la) (livre|chapitre|piste|fichier|section)$|^(?:recommence|reprends) (?:le|la) (livre|chapitre) (?:depuis le debut|du debut|au debut)$")
    private val jump = r("^(avance|avancer|saute|recule|reculer|reviens en arriere|retourne en arriere|retour en arriere|reviens)(?: de)? (\\d+|une|un|une demi|quelques) ?(secondes?|minutes?|heures?|demi heure)?(?: en arriere)?$")
    private val jumpLittle = r("^(avance|recule|reviens en arriere|retour en arriere) un peu$|^(avance|recule) encore$")
    private val whereInBook = r("^(?:ou j en suis|ou en suis je|on en est ou|ou on en est|quel chapitre|c est quel chapitre|on est a quel chapitre|quel livre|c est quel livre|combien (?:de temps )?(?:il )?reste(?: t il)? (?:dans le|du|au) (?:livre|chapitre)|quelle page|on est a quelle page|qu est ce que j ecoute|c est quoi cette (?:radio|chanson|emission)|quelle radio)$")
    private val addBm = r("^(?:pose|mets|ajoute|place|cree|fais)(?: un)? signet(?: ici)?$|^(?:marque (?:la page|cet endroit|ici|l endroit)|signet)$")
    private val goBm = r("^(?:va|aller|retourne|reviens|vas)(?: au)? signet (?:numero )?(\\d+)$")
    private val listBm = r("^(?:liste des signets|mes signets|les signets|quels signets|quels sont (?:mes|les) signets)$")
    private val delBm = r("^(?:supprime|efface|enleve|retire) (?:le )?signet (?:numero )?(\\d+)$")
    private val delAllBm = r("^(?:supprime|efface|enleve|retire) (?:tous )?(?:les|mes) signets(?: du livre)?$")
    private val speed = r("^(?:lecture plus (?:rapide|lente)|vitesse (?:de lecture )?(?:plus rapide|plus lente|normale)|(?:accelere|ralentis) la lecture|vitesse normale|lecture (?:a )?vitesse normale|remets la vitesse normale)$")

    // Radio
    private val radio = r("^(?:mets|met|mettre|allume|ecoute|ecouter|lance|joue|branche|passe)(?: moi)?(?: la| une)? radio(?: (.+))?$|^(?:la )?radio$")
    private val namedRadio = r("^(?:mets|met|mettre|allume|ecoute|ecouter|lance|joue|branche)(?: moi)? (france inter|france info|franceinfo|france culture|france musique|fip|mouv|france bleu.*|ici .+|rtl.*|europe 1|europe un|rmc.*|nostalgie|cherie fm|radio classique|rfm|fun radio|skyrock|nrj|rire et chansons|radio notre dame|sud radio|radio .+|bfm.*)$")
    private val favStation = r("^(?:ajoute|mets|enregistre) (?:cette|la) radio (?:a|aux|dans) (?:mes )?favori(?:te)?s?$")
    private val listStations = r("^(?:mes radios|quelles radios|mes radios favorites|liste des radios)$")

    // Podcasts
    private val subscribe = r("^(?:abonne moi|abonne toi|abonnes moi|ajoute|inscris moi)(?: au| a| le)? podcast (.+)$|^(?:abonne moi|abonne toi) a (.+)$")
    private val unsubscribe = r("^(?:desabonne moi|desabonne toi|supprime|enleve|retire)(?: du| le)? podcast (.+)$|^(?:desabonne moi|desabonne toi) (?:de|du|d) (.+)$")
    private val listPodcasts = r("^(?:mes podcasts|quels podcasts|liste des podcasts|mes abonnements|quels sont mes podcasts)$")
    private val podcast = r("^(?:ecoute|ecouter|mets|lance|joue|lis)(?: moi)?(?: le)?(?: dernier| nouvel| nouveau)? (?:episode|podcast)(?: du podcast| de l emission| du| de| d)? ?(.*)$")

    private val download = r("^(?:telecharge|telecharger|telecharges|recupere|mets a jour)(?: moi)?(?: les| mes)?(?: derniers?| nouveaux?)?(?: episodes?)?(?: (?:des|de mes|du|de|d))? ?(?:podcasts?|episodes?)(?: (.+))?$")
    private val listDownloads = r("^(?:mes podcasts telecharges|quels podcasts (?:sont )?telecharges|(?:mes |les )?episodes telecharges|qu est ce que j ai de telecharge|quels episodes (?:sont |ai je )?telecharges)$")
    private val deleteDownloads = r("^(?:supprime|efface|vide|enleve) (?:les|mes|tous les) (?:podcasts|episodes) telecharges$")

    // Musique
    private val music = r("^(?:mets|met|joue|lance|ecoute|ecouter|mettre)(?: moi)?(?: de la| un peu de| la| ma)? musique(?: (?:de|d) (.+))?$")
    private val song = r("^(?:mets|met|joue|lance|ecoute)(?: moi)? (?:une chanson de|la chanson|des chansons de|l album|un album de|du|des) (.+)$")

    // Mémos vocaux
    private val recordMemo = r("\\b(?:enregistre|fais|prends|cree|laisse|enregistrer)(?: moi)?(?: un| une)? (?:memo|note vocale|message vocal pour moi|memo vocal)\\b|^(?:memo|note vocale|nouveau memo|nouvelle note vocale)$")
    private val playMemos = r("^(?:ecoute|ecouter|lis|joue|mets|repete)(?: moi)? (?:mes|les) (?:memos|notes vocales|memos vocaux)$|^(?:mes memos|mes notes vocales)$")
    private val deleteMemo = r("^(?:supprime|efface|jette) (?:ce|le|mon) (?:memo|note vocale|memo vocal)$")

    // Notes écrites et liste de courses
    private val addList = r("^(?:ajoute|rajoute|mets|met|note|inscris)(?: moi)? (.+?) (?:a|sur|dans|en) (?:la|ma) liste(?: de courses| des courses| de commissions)?$|^(?:il faut acheter|il faut racheter|il faudra acheter|j ai besoin d acheter|il faut que j achete) (.+)$")
    private val readList = r("^(?:lis|relis|quelle est|qu y a t il sur|qu est ce qu il y a sur|dis moi|donne moi)(?: moi)? (?:la|ma) liste(?: de courses| des courses| de commissions)?$|^(?:ma liste de courses|la liste de courses|ma liste)$")
    private val removeList = r("^(?:enleve|retire|supprime|barre|efface|raye) (.+?) (?:de|sur|dans) (?:la|ma) liste(?: de courses| des courses)?$")
    private val clearList = r("^(?:vide|efface|supprime|remets a zero) (?:la|ma|toute la) liste(?: de courses| des courses)?$")
    private val addNote = r("^(?:note|notes|prends note|ecris|retiens|souviens toi|n oublie pas)(?: bien)? (?:que|qu) (.+)$|^(?:note|prends note de) (.+)$")
    private val readNotes = r("^(?:lis|relis|quelles sont|dis moi)(?: moi)? (?:mes|les) notes(?: ecrites)?$|^mes notes$")
    private val deleteNote = r("^(?:supprime|efface) (?:la )?note (?:numero )?(\\d+)$|^(?:supprime|efface) (?:toutes )?(?:mes|les) notes$")

    // Agenda et point du jour
    private val agenda = r("\\b(?:mon agenda|mes rendez vous|mon calendrier|quels rendez vous|j ai (?:un|des) rendez vous|qu est ce que j ai (?:de prevu|aujourd hui|demain|cette semaine|ce week end)|ai je (?:quelque chose|des rendez vous|un rendez vous)|j ai quoi (?:de prevu|aujourd hui|demain)|mon programme)\\b")
    private val addEvent = r("^(?:ajoute|note|mets|cree|prends|inscris|enregistre)(?: moi)?(?: un| le)? (?:rendez vous|rdv)(?: dans (?:mon|l) agenda)? (.+)$|^(?:j ai|j aurai) (?:un )?rendez vous (.+)$")
    private val briefing = r("^(?:fais moi le point|fais le point|le point du jour|mon briefing|le briefing|resume de la journee|programme du jour|qu est ce qui m attend aujourd hui|le point)$")

    // Courriels
    private val readMail = r("\\b(?:lis|lire|relis|lit)(?: moi)? (?:mes|les) (?:nouveaux )?(?:mails|e mails|emails|courriels|mels|messages electroniques)\\b|\\bj ai (?:des|un|de) (?:nouveaux? )?(?:mails?|e mails?|emails?|courriels?)\\b|^(?:mes mails|mes emails|mes e mails|mes courriels|ma boite mail|ma messagerie)$")
    private val readMailBody = r("^(?:lis le|lis la|lis le mail|lis ce mail|lis le courriel|ouvre le mail|ouvre le|lis le message)$")
    private val summarize = r("^(?:resume le|resume la|resume le mail|resume ce mail|resume moi le mail|fais moi un resume|resume)$")
    private val sendMail = r("^(?:ecris|envoie|envoyer|ecrire|redige)(?: un)? (?:mail|e mail|email|courriel|mel) (?:a|au|pour) (.+)$")
    private val bodySplit = r(" (?:pour (?:lui |leur )?(?:dire|demander)|(?:lui|leur) dire|en (?:lui )?disant|disant) (?:que |qu |si )?| : ")

    /** Commandes à tester avant les minuteurs, rappels et messages. */
    fun routeEarly(utterance: String): Intent? {
        val (raw, k) = prepare(utterance)
        if (sleepCancel.containsMatchIn(k)) return SleepTimer(null)
        if (sleep.containsMatchIn(k)) {
            val minutes = FrenchTime.parseDuration(utterance)?.toMinutes()?.toInt() ?: 30
            return SleepTimer(minutes.coerceIn(1, 600))
        }
        if (recordMemo.containsMatchIn(k)) return RecordMemo
        if (readMail.containsMatchIn(k)) return ReadMail
        sendMail.find(k)?.let { m ->
            val start = m.groups[1]!!.range.first
            val rest = k.substring(start)
            val split = bodySplit.find(rest)
            return if (split == null) SendMail(raw.substring(start).trim(), null)
            else SendMail(raw.substring(start, start + split.range.first).trim(), raw.substring(start + split.range.last + 1).trim().ifEmpty { null })
        }
        if (agenda.containsMatchIn(k)) return Agenda(raw)
        addEvent.find(k)?.let { m -> return AddEvent(grp(raw, m) ?: raw) }
        if (briefing.matches(k)) return Briefing
        return null
    }

    fun route(utterance: String): Intent? {
        val (raw, k) = prepare(utterance)
        if (k.isEmpty()) return null
        if (listBooks.matches(k)) return ListBooks
        openBook.find(k)?.let { m -> return OpenBook(grp(raw, m)?.takeIf { it.isNotBlank() }) }
        if (pause.matches(k)) return PauseMedia
        if (resumeBook.matches(k)) return ResumeMedia
        nextHeading.find(k)?.let { m -> return NextHeading((m.groupValues[1] + m.groupValues[2]) != "section") }
        prevHeading.find(k)?.let { m -> return PreviousHeading((m.groupValues[1] + m.groupValues[2]) != "section") }
        if (next.matches(k)) return NextItem
        if (previous.matches(k)) return PreviousItem
        goHeading.find(k)?.let { m -> return GoToHeading(m.groupValues[2].toInt()) }
        goPage.find(k)?.let { m -> return GoToPage(m.groupValues[1]) }
        edge.find(k)?.let { m ->
            val what = m.groupValues[2].ifEmpty { m.groupValues[3] }
            return GoToEdge(end = m.groupValues[1] == "fin", chapter = what != "livre")
        }
        jumpLittle.find(k)?.let { m -> return Jump(if ((m.groupValues[1] + m.groupValues[2]).startsWith("avance")) 30_000 else -30_000) }
        jump.find(k)?.let { m ->
            val n = when (m.groupValues[2]) { "une", "un" -> 1.0; "une demi" -> 0.5; "quelques" -> 15.0; else -> m.groupValues[2].toDouble() }
            val unit = m.groupValues[3]
            val ms = when {
                unit.startsWith("heure") -> n * 3_600_000
                unit == "demi heure" -> 1_800_000.0
                unit.startsWith("minute") -> n * 60_000
                unit.startsWith("seconde") -> n * 1000
                else -> n * 1000 * (if (n <= 10) 60 else 1) // « avance de 5 » = 5 minutes ; « de 30 » = 30 secondes
            }.toLong()
            return Jump(if (m.groupValues[1].startsWith("avance") || m.groupValues[1] == "saute") ms else -ms)
        }
        if (whereInBook.matches(k)) return WhereInBook
        if (addBm.matches(k)) return AddBookmark
        goBm.find(k)?.let { return GoToBookmark(it.groupValues[1].toInt()) }
        if (listBm.matches(k)) return ListBookmarks
        delBm.find(k)?.let { return DeleteBookmark(it.groupValues[1].toInt()) }
        if (delAllBm.matches(k)) return DeleteBookmark(null)
        if (speed.matches(k)) return PlaybackSpeed(when {
            Regex("normale").containsMatchIn(k) -> null
            Regex("rapide|accelere").containsMatchIn(k) -> true
            else -> false
        })

        if (favStation.matches(k)) return AddFavoriteStation
        if (listStations.matches(k)) return ListStations
        namedRadio.find(k)?.let { m -> return PlayRadio(grp(raw, m)) }
        radio.find(k)?.let { m -> return PlayRadio(grp(raw, m)) }

        if (listDownloads.matches(k)) return ListDownloads
        if (deleteDownloads.matches(k)) return DeleteDownloads
        download.find(k)?.let { m -> return DownloadPodcasts(grp(raw, m)?.takeIf { it.isNotBlank() }) }
        subscribe.find(k)?.let { m -> return SubscribePodcast(grp(raw, m)!!) }
        unsubscribe.find(k)?.let { m -> return UnsubscribePodcast(grp(raw, m)!!) }
        if (listPodcasts.matches(k)) return ListPodcasts
        podcast.find(k)?.let { m -> return PlayPodcast(grp(raw, m)?.takeIf { it.isNotBlank() }) }

        music.find(k)?.let { m -> return PlayMusic(grp(raw, m)) }
        song.find(k)?.let { m -> return PlayMusic(grp(raw, m)) }

        if (playMemos.matches(k)) return PlayMemos
        if (deleteMemo.matches(k)) return DeleteMemo

        if (clearList.matches(k)) return ClearList
        if (readList.matches(k)) return ReadList
        removeList.find(k)?.let { m -> return RemoveFromList(grp(raw, m)!!) }
        addList.find(k)?.let { m -> return AddToList(grp(raw, m)!!) }
        if (readNotes.matches(k)) return ReadNotes
        deleteNote.find(k)?.let { m -> return DeleteNote(m.groupValues[1].toIntOrNull()) }
        addNote.find(k)?.let { m ->
            val text = grp(raw, m)!!
            // « retiens que Pierre c'est mon fils » est un surnom, traité ailleurs.
            if (!Regex("\\b(c est|est) (mon|ma|mes) ").containsMatchIn(FrenchText.key(text))) return AddNote(text)
        }

        if (readMailBody.matches(k)) return ReadMailBody(null)
        if (summarize.matches(k)) return SummarizeMail
        return null
    }

    fun ordinal(word: String): Int? = ordinals[word]

    private fun prepare(utterance: String): Pair<String, String> {
        val raw = FrenchText.normalize(utterance).replace('’', '\'').trim()
        val k = FrenchText.stripAccentsSameLength(raw).replace('\'', ' ').replace('-', ' ')
        return raw to k
    }

    /** Premier groupe capturé non vide, repris dans la phrase d'origine (accents conservés). */
    private fun grp(raw: String, m: MatchResult): String? =
        (1 until m.groups.size).firstNotNullOfOrNull { i -> m.groups[i]?.range?.let { raw.substring(it).trim() }?.takeIf { it.isNotEmpty() } }
}
