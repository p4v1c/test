package fr.assistantvocal.core.router

import fr.assistantvocal.core.text.FrenchText

// ---------------------------------------------------------------------- intentions de la phase 3
// (même interface scellée qu'Intent : même paquet, même module)

data class CallContact(val target: String) : Intent
data class CallNumber(val number: String) : Intent
data class CallFavorite(val position: Int) : Intent
data object CallBack : Intent
data object AnswerCall : Intent
data object HangUp : Intent
data class Speaker(val on: Boolean) : Intent
data object MissedCalls : Intent
data class ReadMessages(val onlyLast: Boolean) : Intent
data class SendSms(val target: String, val body: String?) : Intent
data class Reply(val body: String?) : Intent
data object ReadNotifications : Intent
data class SetAlias(val target: String, val alias: String) : Intent
data class ContactNumber(val target: String) : Intent
data class SetAlarm(val text: String) : Intent
data class SetTimer(val text: String) : Intent
data object TimerStatus : Intent
data class CancelTimer(val text: String) : Intent
data class CreateReminder(val text: String) : Intent
data object ListReminders : Intent
data class DeleteReminder(val text: String) : Intent
data class MedicationTaken(val text: String) : Intent
data class MedicationQuestion(val text: String) : Intent
data object Sos : Intent
data class Vision(val request: fr.assistantvocal.core.vision.VisionRequest) : Intent
data class DocumentQuestion(val text: String) : Intent
data object OpenLookout : Intent

/**
 * Routeur des tâches (appels, SMS, rappels, SOS). Les expressions sont testées sur une forme sans accents
 * de même longueur que la phrase d'origine : les noms et messages extraits gardent leurs accents.
 */
class TaskRouter {
    private class Text(utterance: String) {
        /** Phrase d'origine en minuscules (accents conservés). */
        val raw = FrenchText.normalize(utterance).replace('’', '\'')
        /** Même phrase sans accents ni apostrophes/tirets, même longueur. */
        val k = FrenchText.stripAccentsSameLength(raw).replace('\'', ' ').replace('-', ' ')
        fun group(m: MatchResult, i: Int): String? = m.groups[i]?.range?.let { raw.substring(it).trim() }?.takeIf { it.isNotEmpty() }
    }

    private fun r(p: String) = Regex(p)
    private val info = InfoRouter()
    private val media = MediaRouter()
    private val support = SupportRouter()

    private val sos = r("\\b(au secours|a l aide|aidez moi|aide moi vite|sos|je suis tombe|je suis tombee|je me suis fait mal|appelle les secours|j ai besoin d aide)\\b")
    private val reminder = r("^(?:rappelle moi|rappelles moi|fais moi penser a|n oublie pas de me rappeler|tu peux me rappeler) (.+)$")
    private val alarm = r("\\b(reveille moi|reveilles moi|mets? (moi )?un reveil|regle (un|le|mon) reveil|programme (un|le) reveil|mets (moi )?une alarme)\\b")
    private val timer = r("\\b(minuteur|minuterie|compte a rebours|chrono)\\b")
    private val timerStatus = r("\\b(combien de temps|reste t il|il reste combien|ou en est)\\b")
    private val cancelWord = r("\\b(annule|annuler|arrete|supprime|efface|enleve|stoppe)\\b")
    private val listReminders = r("\\b(quels sont mes rappels|mes rappels|liste (des|de mes|mes) rappels|qu est ce que j ai (de prevu|comme rappels?)|mes reveils|quels reveils)\\b")
    private val deleteReminder = r("\\b(?:supprime|annule|efface|enleve|retire)(?: le| mon| les| tous les| mes)? (?:rappel|reveil|alarme)s?\\b(.*)$")
    private val medTaken = r("^(?:j ai (?:bien )?pris(?: mon| mes| ma| le| la| les)?(?: (.+))?|c est fait|je l ai pris|je les ai pris|voila c est fait|c est pris)$")
    private val medQuestion = r("\\b(?:ai je|est ce que j ai|j ai bien|ai je bien)(?: bien)? pris(.*)$")
    private val missed = r("\\b(qui m a appele|qui a appele|appels? manques?|j ai (eu )?des appels|quelqu un m a appele|journal d appels?|qui m a telephone)\\b")
    private val callBack = r("^(?:rappelle|rappeler)(?: le| la| lui| les| le dernier| la derniere| la personne| celui| celle| ce numero| le numero)?(?: qui m a appele)?$")
    private val answer = r("^(?:decroche|reponds au telephone|reponds a l appel|prends l appel|accepte l appel|decroche le telephone)$")
    private val hangUp = r("^(?:raccroche|refuse|refuse l appel|rejette|rejette l appel|ne reponds pas|termine l appel|coupe l appel)$")
    private val speakerOff = r("\\b(enleve|coupe|desactive|eteins|retire|arrete) (le )?haut parleur\\b")
    private val speakerOn = r("\\b((mets|active|allume)( le| en)? )?haut parleur\\b")
    private val readLast = r("\\b(?:lis|relis|lit|lire)(?: moi)? (?:le|mon) dernier (?:message|sms|texto)\\b|\\brelis (?:le|ce) message\\b")
    private val readMessages = r("\\b(?:lis|lire|lit|relis)(?: moi)? (?:mes|les) (?:nouveaux )?(?:messages|sms|textos?)\\b|\\bj ai (?:des|un|de) (?:nouveaux? )?(?:messages?|sms)\\b|\\bnouveaux messages\\b|\\bmes messages\\b")
    private val notifications = r("\\b(?:lis|lire)(?: moi)? (?:mes|les) notifications?\\b|\\bquoi de neuf\\b|\\bj ai des notifications\\b|\\bmes notifications\\b")
    private val reply = r("^(?:reponds|repondre|reponds lui|reponds y|reponds leur)(?: (?:que|qu|pour dire que|pour lui dire que|lui dire que|en disant que|en disant))?(?:\\s+(.+))?$")
    private val sendVerb = r("^(?:envoie|envoyer|ecris|ecrire|envoie moi|texte|envois)(?: un| une)?(?: petit)?(?: message| sms| texto| mot)?(?: a| au| aux) (.+)$")
    private val bodySplit = r(" (?:pour (?:lui |leur )?(?:dire|demander)|pour (?:lui |leur )?dire|(?:lui|leur) dire|en (?:lui )?disant|disant) (?:que |qu |si )?| (?:que|qu) | : ")
    private val call = r("^(?:appelle|appeler|appelles|telephone a|telephoner a|passe un coup de fil a|passe un coup de telephone a|compose|joins|contacte|tu peux appeler)(?: moi)? (.+)$")
    private val favorite = r("^(?:le )?(?:numero|favori|contact)? ?(\\d)$")
    private val alias1 = r("^(.+?) (?:c est|est) (mon|ma|mes) (.+)$")
    private val alias2 = r("^(mon|ma) (.+?) (?:s appelle|c est) (.+)$")
    private val alias3 = r("^(?:enregistre|retiens que|souviens toi que) (.+?) (?:comme|c est|est) (mon|ma) (.+)$")
    // Vision
    private val visionScene = r("\\b(qu est ce qu il y a (devant|autour de) moi|qu y a t il devant moi|decris (moi )?(ce que|ce qui|la scene|l image|la piece|ce qu il y a|ca|autour)|qu est ce que (je tiens|tu vois)|que vois tu|regarde (ca|devant moi|ce que je tiens)|c est quoi ca|qu est ce que c est que ca|decris)\\b")
    private val visionExplain = r("\\b(c est quoi (ce|cette) (lettre|courrier|document|papier|facture|ordonnance)|explique (moi )?(ce|cette|le|la) (lettre|courrier|document|papier|facture|ordonnance)|de quoi parle (ce|cette) (lettre|courrier|document)|que dit (ce|cette) (lettre|courrier|document))\\b")
    private val visionRead = r("\\b(lis (moi )?(ce|cette|ca|le|la|l)( texte| lettre| courrier| document| papier| etiquette| page| ecran| affiche| panneau| ordonnance)?$|qu est ce qui est ecrit|lis ce qui est ecrit|lire un document|lis un document|lis le courrier)")
    private val visionNextPage = r("^(page suivante|la page suivante|autre page|lis la page suivante|la suite du document|j ai une autre page)$")
    private val documentQuestion = r("^(relis|relire)\\b(?! (mes|le dernier|mon dernier|les) (messages?|sms))|\\b(quel est le montant|c est combien a payer|combien (je dois|dois je) payer|quelle est la date limite|a payer avant|date d echeance|que dois je faire|qu est ce que je dois faire)\\b")
    private val visionBanknote = r("\\b(quel billet|ce billet|billet de combien|c est combien ce billet|reconnais (ce|le|mon) billet|combien d argent)\\b")
    private val visionMatch = r("\\b(ca va ensemble|vont ensemble|c est assorti|sont assortis|est ce que ca va avec)\\b")
    private val visionColor = r("\\b(quelle couleur|c est quelle couleur|de quelle couleur|detecteur de couleur)\\b")
    private val visionLight = r("\\b(detecteur de lumiere|la lumiere est (allumee|eteinte)|la lampe est (allumee|eteinte)|est ce qu il y a de la lumiere|il fait jour)\\b")
    private val visionMedication = r("\\b(quel medicament|c est quoi ce medicament|ce medicament|cette boite de medicaments?|lis la boite|quelle est cette boite|boite de medicament)\\b")
    private val visionProduct = r("\\b(c est quoi ce produit|quel est ce produit|ce produit|code barres?|scanne|date de peremption|c est perime|est ce perime)\\b")
    private val visionFind = r("\\b(?:ou sont|ou est|aide moi a trouver|cherche|trouve moi|retrouve|je ne trouve (?:pas|plus)) ((?:mes|mon|ma) .+)$")
    private val lookout = r("\\b(ouvre|lance|demarre) lookout\\b")

    private val contactNumber = r("\\b(?:quel est le numero|donne moi le numero|c est quoi le numero|le numero de telephone) (?:de|d) (.+)$")

    private fun vision(mode: fr.assistantvocal.core.vision.VisionMode, q: String = "") =
        Vision(fr.assistantvocal.core.vision.VisionRequest(mode, q))

    fun route(utterance: String): Intent? {
        val t = Text(utterance)
        val k = t.k.trim()
        if (k.isEmpty()) return null

        if (sos.containsMatchIn(k)) return Sos
        support.route(utterance)?.let { return it }
        media.routeEarly(utterance)?.let { return it }

        reminder.find(k)?.let { m -> return CreateReminder(t.group(m, 1)!!) }
        if (alarm.containsMatchIn(k)) return SetAlarm(t.raw)
        if (timer.containsMatchIn(k)) {
            return when {
                timerStatus.containsMatchIn(k) -> TimerStatus
                cancelWord.containsMatchIn(k) -> CancelTimer(t.raw)
                else -> SetTimer(t.raw)
            }
        }
        if (Regex("^previens moi dans ").containsMatchIn(k)) return SetTimer(t.raw)
        if (listReminders.containsMatchIn(k)) return ListReminders
        deleteReminder.find(k)?.let { m -> return DeleteReminder(t.group(m, 1) ?: "") }
        medQuestion.find(k)?.let { m -> return MedicationQuestion(t.group(m, 1) ?: "") }
        medTaken.find(k)?.let { m -> return MedicationTaken(t.group(m, 1) ?: "") }

        if (missed.containsMatchIn(k)) return MissedCalls
        if (answer.matches(k)) return AnswerCall
        if (hangUp.matches(k)) return HangUp
        if (speakerOff.containsMatchIn(k)) return Speaker(false)
        if (speakerOn.containsMatchIn(k) && k.split(' ').size <= 5) return Speaker(true)
        if (callBack.matches(k)) return CallBack

        if (readLast.containsMatchIn(k)) return ReadMessages(onlyLast = true)
        if (readMessages.containsMatchIn(k)) return ReadMessages(onlyLast = false)
        if (notifications.containsMatchIn(k)) return ReadNotifications
        reply.find(k)?.let { m -> return Reply(t.group(m, 1)) }

        // Livres, radio, podcasts, musique, mémos, notes
        media.route(utterance)?.let { return it }

        // Orientation, météo, actualités, recherche, calculs (avant « envoie… à » et « où est… »)
        info.route(utterance)?.let { return it }

        sendVerb.find(k)?.let { m ->
            val start = m.groups[1]!!.range.first
            val rest = k.substring(start)
            val split = bodySplit.find(rest)
            return if (split == null) SendSms(t.raw.substring(start).trim(), null)
            else SendSms(
                t.raw.substring(start, start + split.range.first).trim(),
                t.raw.substring(start + split.range.last + 1).trim().takeIf { it.isNotEmpty() },
            )
        }

        contactNumber.find(k)?.let { m -> return ContactNumber(t.group(m, 1)!!) }

        // Vision (après les messages : « relis mes messages » est déjà traité)
        if (lookout.containsMatchIn(k)) return OpenLookout
        if (visionNextPage.matches(k)) return vision(fr.assistantvocal.core.vision.VisionMode.NEXT_PAGE)
        if (documentQuestion.containsMatchIn(k)) return DocumentQuestion(t.raw)
        if (visionExplain.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.EXPLAIN)
        if (visionMedication.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.MEDICATION)
        if (visionBanknote.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.BANKNOTE)
        if (visionMatch.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.MATCH_COLORS)
        if (visionColor.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.COLOR)
        if (visionLight.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.LIGHT)
        if (visionProduct.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.PRODUCT)
        if (visionRead.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.READ)
        if (visionScene.containsMatchIn(k)) return vision(fr.assistantvocal.core.vision.VisionMode.SCENE, t.raw)
        visionFind.find(k)?.let { m ->
            val what = t.group(m, 1)!!
            if (!Regex("\\b(pharmacie|boulangerie|arret|gare|rue|mairie|poste|medecin|supermarche|magasin)\\b").containsMatchIn(FrenchText.stripAccents(what)))
                return Vision(fr.assistantvocal.core.vision.VisionRequest(fr.assistantvocal.core.vision.VisionMode.FIND, what))
        }

        call.find(k)?.let { m ->
            val target = t.group(m, 1)!!
            val tk = FrenchText.stripAccents(target)
            favorite.find(tk)?.let { f -> return CallFavorite(f.groupValues[1].toInt()) }
            val digits = target.filter { it.isDigit() }
            if (digits.length >= 2 && digits.length >= target.count { it.isLetter() }) return CallNumber(digits)
            Regex("\\b(les secours|les pompiers|le samu|les urgences)\\b").find(tk)?.let { return CallNumber("112") }
            return CallContact(target)
        }

        alias3.find(k)?.let { m -> return SetAlias(t.group(m, 1)!!, t.group(m, 2) + " " + t.group(m, 3)) }
        alias2.find(k)?.let { m -> return SetAlias(t.group(m, 3)!!, t.group(m, 1) + " " + t.group(m, 2)) }
        alias1.find(k)?.let { m ->
            val who = t.group(m, 1)!!
            // « il est mon ami », « c'est ma fille » : pas un prénom, on ignore.
            if (who.split(' ').size <= 3 && !Regex("^(il|elle|ce|c|ca|cela|lui|celui|celle)\\b").containsMatchIn(FrenchText.stripAccents(who)))
                return SetAlias(who, t.group(m, 2) + " " + t.group(m, 3))
        }
        return null
    }
}
