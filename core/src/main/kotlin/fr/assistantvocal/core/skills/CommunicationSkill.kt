package fr.assistantvocal.core.skills

import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.phone.CallLogSource
import fr.assistantvocal.core.phone.CallState
import fr.assistantvocal.core.phone.CallType
import fr.assistantvocal.core.phone.Contact
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.ContactMatch
import fr.assistantvocal.core.phone.NotificationSource
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.phone.PhoneActions
import fr.assistantvocal.core.phone.PhoneFormat
import fr.assistantvocal.core.phone.PhoneNumber
import fr.assistantvocal.core.phone.ScamDetector
import fr.assistantvocal.core.phone.SmsMessage
import fr.assistantvocal.core.phone.SmsSender
import fr.assistantvocal.core.phone.SmsSource
import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.text.FrenchText
import fr.assistantvocal.core.time.FrenchTime
import java.time.ZonedDateTime

/** Appels, SMS et notifications. Tout ce qui sort du téléphone passe par une confirmation relue. */
class CommunicationSkill(
    val directory: ContactDirectory,
    private val people: PeopleStore,
    private val callLog: CallLogSource,
    private val sms: SmsSource,
    private val smsSender: SmsSender,
    private val phone: PhoneActions,
    private val notifications: NotificationSource,
    private val store: KeyValueStore,
    private val device: DeviceStatus,
    private val dialog: Dialog,
) {
    /** Dernière conversation lue, pour « réponds ». */
    private data class Conversation(val number: String?, val name: String, val notificationKey: String?)
    private var lastConversation: Conversation? = null

    // ------------------------------------------------------------------ appels

    fun call(target: String): AssistantReply = resolve(target, "appeler") { c, n -> confirmCall(c.name, n) }

    fun callNumber(number: String): AssistantReply {
        val name = directory.nameFor(number)
        val who = if (name != null) "$name, au ${PhoneFormat.speakable(number)}" else "le ${PhoneFormat.speakable(number)}"
        return confirmCallRaw(who, number)
    }

    fun callFavorite(position: Int): AssistantReply {
        val target = people.favorites[position.toString()]
            ?: return AssistantReply("Il n'y a pas de favori numéro $position. Un proche peut les régler dans les réglages.", Earcon.ERROR)
        return if (PhoneFormat.digits(target).length >= 4 && target.none { it.isLetter() }) callNumber(target) else call(target)
    }

    fun callBack(): AssistantReply {
        val last = callLog.recent(10).firstOrNull { it.type != CallType.OUTGOING }
            ?: return AssistantReply("Je ne trouve pas d'appel récent à rappeler.", Earcon.ERROR)
        if (last.number.isBlank()) return AssistantReply("Le dernier appel venait d'un numéro masqué, je ne peux pas rappeler.", Earcon.ERROR)
        return confirmCallRaw(directory.describe(last.number, last.name), last.number)
    }

    private fun confirmCall(name: String, n: PhoneNumber): AssistantReply {
        val where = when {
            n.label != null && !n.isMobile -> " sur son numéro ${n.label}"
            n.isMobile -> " sur son portable"
            else -> ""
        }
        return confirmCallRaw("$name$where", n.number)
    }

    private fun confirmCallRaw(who: String, number: String): AssistantReply =
        dialog.confirm("Appeler $who ? Répondez oui ou non.") {
            if (phone.call(number)) "J'appelle $who." else "L'appel n'a pas pu partir. Vérifiez que le téléphone a du réseau."
        }

    fun answer(): AssistantReply = when (phone.state()) {
        CallState.RINGING -> if (phone.answer()) AssistantReply("", Earcon.SUCCESS) else AssistantReply("Je n'arrive pas à décrocher.", Earcon.ERROR)
        else -> AssistantReply("Aucun appel en cours.")
    }

    fun hangUp(): AssistantReply = when (phone.state()) {
        CallState.RINGING, CallState.OFFHOOK -> if (phone.hangUp()) AssistantReply("Appel terminé.", Earcon.SUCCESS)
            else AssistantReply("Je n'arrive pas à raccrocher.", Earcon.ERROR)
        CallState.IDLE -> AssistantReply("Il n'y a pas d'appel en cours.")
    }

    fun speaker(on: Boolean): AssistantReply =
        if (phone.setSpeaker(on)) AssistantReply(if (on) "Haut-parleur activé." else "Haut-parleur coupé.", Earcon.SUCCESS)
        else AssistantReply("Je n'arrive pas à changer le haut-parleur.", Earcon.ERROR)

    fun missedCalls(): AssistantReply {
        val now = device.now()
        val heardUntil = store.getString(K_CALLS_HEARD)?.toLongOrNull() ?: 0L
        val recent = callLog.recent(30).filter { it.type == CallType.MISSED || it.type == CallType.REJECTED }
            .filter { it.time.isAfter(now.minusDays(2)) }
        val fresh = recent.filter { it.time.toInstant().toEpochMilli() > heardUntil }
        val list = fresh.ifEmpty { recent.take(3) }
        if (list.isEmpty()) return AssistantReply("Vous n'avez pas d'appel manqué ces deux derniers jours.")
        store.putString(K_CALLS_HEARD, now.toInstant().toEpochMilli().toString())
        val intro = when {
            fresh.isEmpty() -> "Pas de nouvel appel manqué. Les derniers étaient : "
            fresh.size == 1 -> "Vous avez un appel manqué : "
            else -> "Vous avez ${fresh.size} appels manqués : "
        }
        val items = list.take(5).joinToString(", puis ") { "${directory.describe(it.number, it.name)}, ${relative(it.time, now)}" }
        return AssistantReply("$intro$items. Pour rappeler le dernier, dites : rappelle-le.")
    }

    // ------------------------------------------------------------------ SMS

    fun readMessages(onlyLast: Boolean): AssistantReply {
        val now = device.now()
        val all = sms.inbox(30).sortedBy { it.time }
        if (all.isEmpty()) return AssistantReply("Vous n'avez aucun message.")
        if (onlyLast) return AssistantReply(readOne(all.last(), now, "Dernier message"))
        val readUntil = store.getString(K_SMS_READ)?.toLongOrNull()
            ?: now.minusDays(1).toInstant().toEpochMilli() // première utilisation : messages des dernières 24 h
        val fresh = all.filter { it.time.toInstant().toEpochMilli() > readUntil }
        if (fresh.isEmpty()) return AssistantReply("Vous n'avez pas de nouveau message. Pour réentendre le dernier, dites : lis mon dernier message.")
        return readBatch(fresh, now, first = true)
    }

    private fun readBatch(messages: List<SmsMessage>, now: ZonedDateTime, first: Boolean): AssistantReply {
        val batch = messages.take(3)
        val rest = messages.drop(3)
        val intro = if (first) (if (messages.size == 1) "Vous avez un nouveau message. " else "Vous avez ${messages.size} nouveaux messages. ") else ""
        val text = batch.mapIndexed { i, m -> readOne(m, now, if (batch.size > 1) ordinal(i) else "Message") }.joinToString(" ")
        store.putString(K_SMS_READ, batch.last().time.toInstant().toEpochMilli().toString())
        if (rest.isEmpty()) return AssistantReply("$intro$text Pour répondre, dites : réponds.")
        return dialog.confirm("$intro$text Il reste ${rest.size} message${if (rest.size > 1) "s" else ""}. Voulez-vous la suite ?", "D'accord.") {
            readBatch(rest, now, first = false).speech
        }
    }

    private fun readOne(m: SmsMessage, now: ZonedDateTime, label: String): String {
        val who = directory.describe(m.address, m.name)
        lastConversation = Conversation(m.address, who, null)
        val verdict = ScamDetector.check(m.body, senderKnown = directory.isKnown(m.address))
        return "$label, de $who, ${relative(m.time, now)} : ${m.body.trim().trimEnd('.')}. ${verdict.warning}".trim()
    }

    fun sendSms(target: String, body: String?): AssistantReply = resolve(target, "écrire à") { c, n ->
        val number = if (n.isMobile) n else c.numbers.firstOrNull { it.isMobile } ?: n
        askBodyThenConfirm(c.name, number.number, body)
    }

    /** Envoie un texte préparé par l'app (ex. position) : [spoken] décrit le contenu dans la confirmation. */
    fun sendPrepared(target: String, spoken: String, body: String): AssistantReply = resolve(target, "écrire à") { c, n ->
        val number = if (n.isMobile) n else c.numbers.firstOrNull { it.isMobile } ?: n
        dialog.confirm("Je vais envoyer $spoken à ${c.name}, avec un lien de carte. J'envoie ? Répondez oui ou non.", "D'accord, je n'envoie rien.") {
            if (smsSender.send(number.number, body)) "Message envoyé à ${c.name}."
            else "Le message n'est pas parti. Vérifiez le réseau, puis redemandez-moi."
        }
    }

    fun reply(body: String?): AssistantReply {
        if (phone.state() == CallState.RINGING) return answer()
        val conv = lastConversation ?: return AssistantReply(
            "Je ne sais pas à qui répondre. Dites par exemple : envoie un message à Paul.", Earcon.ERROR)
        if (conv.notificationKey != null) {
            val key = conv.notificationKey
            return withBody(body, "Que voulez-vous répondre ?") { text ->
                dialog.confirm("Je vais répondre à ${conv.name} : « $text ». J'envoie ? Répondez oui ou non.") {
                    if (notifications.reply(key, text)) "Réponse envoyée." else "Je n'ai pas réussi à répondre à cette notification."
                }
            }
        }
        return askBodyThenConfirm(conv.name, conv.number ?: return AssistantReply("Je ne peux pas répondre à ce message.", Earcon.ERROR), body)
    }

    private fun askBodyThenConfirm(name: String, number: String, body: String?): AssistantReply =
        withBody(body, "Que voulez-vous dire à $name ?") { text ->
            val clean = text.trim().replaceFirstChar { it.uppercase() }.let { if (it.last() in ".!?") it else "$it." }
            dialog.confirm("Je vais envoyer à $name : « $clean » J'envoie ? Répondez oui ou non.", "D'accord, je n'envoie rien.") {
                if (smsSender.send(number, clean)) "Message envoyé à $name."
                else "Le message n'est pas parti. Vérifiez le réseau, puis redemandez-moi."
            }
        }

    private fun withBody(body: String?, question: String, then: (String) -> AssistantReply): AssistantReply {
        if (!body.isNullOrBlank()) return then(body)
        return dialog.ask(question) { said ->
            if (said.isBlank()) AssistantReply("Je n'ai rien entendu, j'annule.", Earcon.CANCELLED) else then(said)
        }
    }

    // ------------------------------------------------------------------ notifications

    fun readNotifications(): AssistantReply {
        val now = device.now()
        val list = notifications.recent().sortedByDescending { it.time }.take(5)
        if (list.isEmpty()) return AssistantReply("Vous n'avez pas de notification.")
        val text = list.joinToString(" ") { n ->
            val verdict = ScamDetector.check("${n.title} ${n.text}", senderKnown = false)
            "${n.app}, ${relative(n.time, now)} : ${n.title}. ${n.text.trim().trimEnd('.')}. ${verdict.warning}".replace(Regex("\\s+"), " ").trim()
        }
        list.firstOrNull { it.canReply }?.let { lastConversation = Conversation(null, it.title, it.key) }
        return AssistantReply("Vous avez ${list.size} notification${if (list.size > 1) "s" else ""}. $text")
    }

    // ------------------------------------------------------------------ contacts

    fun setAlias(target: String, alias: String): AssistantReply = when (val m = directory.find(target)) {
        is ContactMatch.Found -> {
            people.setAlias(alias, m.contact.name)
            AssistantReply("C'est noté : « $alias », c'est ${m.contact.name}. Vous pouvez dire : appelle $alias.", Earcon.SUCCESS)
        }
        is ContactMatch.Ambiguous -> AssistantReply("J'ai plusieurs contacts pour $target : ${names(m.candidates)}. Redites-le avec le nom complet.")
        ContactMatch.None -> AssistantReply("Je ne trouve pas $target dans vos contacts.", Earcon.ERROR)
    }

    fun contactNumber(target: String): AssistantReply = resolve(target, "trouver") { c, n ->
        AssistantReply("Le numéro de ${c.name} est le ${PhoneFormat.speakable(n.number)}.")
    }

    /** Résout un nom dit à voix haute ; en cas de doute, demande lequel (jamais de choix au hasard). */
    private fun resolve(target: String, verb: String, action: (Contact, PhoneNumber) -> AssistantReply): AssistantReply =
        when (val m = directory.find(target)) {
            is ContactMatch.Found -> action(m.contact, m.number)
            ContactMatch.None -> AssistantReply("Je ne trouve pas « $target » dans vos contacts. Pouvez-vous redire le nom ?", Earcon.ERROR)
            is ContactMatch.Ambiguous -> dialog.ask("J'ai trouvé ${names(m.candidates)}. Lequel voulez-vous $verb ?") { answer ->
                val chosen = choose(answer, m.candidates)
                if (chosen == null) AssistantReply("Je n'ai pas compris lequel. Redites le nom complet.", Earcon.ERROR)
                else action(chosen, chosen.numbers.firstOrNull { it.isMobile } ?: chosen.numbers.first())
            }
        }

    private fun choose(answer: String, candidates: List<Contact>): Contact? {
        val k = FrenchText.key(answer)
        Regex("\\b(premier|premiere|1)\\b").find(k)?.let { return candidates.getOrNull(0) }
        Regex("\\b(deuxieme|second|seconde|2)\\b").find(k)?.let { return candidates.getOrNull(1) }
        Regex("\\b(troisieme|3)\\b").find(k)?.let { return candidates.getOrNull(2) }
        val words = k.split(' ').filter { it.length > 1 }
        return candidates.filter { c -> val n = FrenchText.key(c.name).split(' '); words.any { it in n } }
            .takeIf { it.size == 1 }?.first()
    }

    private fun names(cs: List<Contact>) = cs.map { it.name }.let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + " et " + it.last() }

    private fun ordinal(i: Int) = listOf("Premier message", "Deuxième message", "Troisième message").getOrElse(i) { "Message suivant" }

    // ------------------------------------------------------------------ annonces (appel, SMS entrants)

    /** « Appel de Paul Martin » ; numéro inconnu : prudence. */
    fun announceIncomingCall(number: String?): String {
        if (number.isNullOrBlank()) return "Appel d'un numéro masqué."
        val name = directory.nameFor(number)
        return if (name != null) "Appel de $name." else "Appel d'un numéro inconnu, le ${PhoneFormat.speakable(number)}. Méfiez-vous s'il s'agit de démarchage."
    }

    fun announceIncomingSms(m: SmsMessage, readFull: Boolean): String {
        val who = directory.describe(m.address, m.name)
        lastConversation = Conversation(m.address, who, null)
        if (!readFull) return "Nouveau message de $who. Pour l'entendre, dites : lis mes messages."
        store.putString(K_SMS_READ, m.time.toInstant().toEpochMilli().toString())
        val verdict = ScamDetector.check(m.body, senderKnown = directory.isKnown(m.address))
        return "Nouveau message de $who : ${m.body.trim()} ${verdict.warning}".trim()
    }

    companion object {
        private const val K_SMS_READ = "sms.readUntil"
        private const val K_CALLS_HEARD = "calls.heardUntil"

        /** « aujourd'hui à 14 heures 05 », « hier à 18 heures », « le lundi 12 à 9 heures ». */
        fun relative(t: ZonedDateTime, now: ZonedDateTime): String {
            val time = FrenchTime.speakTime(t.toLocalTime().withSecond(0).withNano(0))
            return when (t.toLocalDate()) {
                now.toLocalDate() -> "aujourd'hui à $time"
                now.toLocalDate().minusDays(1) -> "hier à $time"
                else -> "le ${t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRANCE)} ${t.dayOfMonth} à $time"
            }
        }
    }
}
