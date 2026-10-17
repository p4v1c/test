package fr.assistantvocal.core.safety

import fr.assistantvocal.core.phone.GeoPoint
import fr.assistantvocal.core.phone.LocationSource
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.phone.PhoneActions
import fr.assistantvocal.core.phone.PhoneFormat
import fr.assistantvocal.core.phone.SmsSender
import fr.assistantvocal.core.text.FrenchText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.Locale

/** Ce dont le déroulé SOS a besoin de l'application (voix, écoute, sons). */
interface SosUi {
    suspend fun say(text: String)
    suspend fun alertSound()
    /** Écoute pendant [ms] : renvoie vrai si la personne annule (« stop », « annule », bouton). */
    suspend fun cancelledWithin(ms: Long): Boolean
}

data class SosOutcome(val cancelled: Boolean, val smsSent: Int, val smsFailed: Int, val called: String?, val location: GeoPoint?)

/**
 * Alerte de secours :
 * 1. annonce et 10 secondes pour annuler ;
 * 2. position GPS (15 s maximum, sans bloquer l'alerte) ;
 * 3. SMS à tous les contacts d'urgence avec un lien de carte ;
 * 4. appel du premier contact, haut-parleur activé.
 * Chaque étape est annoncée à voix haute, avec son résultat réel.
 */
class SosFlow(
    private val people: PeopleStore,
    private val location: LocationSource,
    private val sms: SmsSender,
    private val phone: PhoneActions,
    private val ui: SosUi,
    private val ownerName: () -> String,
    private val countdownMs: Long = 10_000,
) {
    suspend fun run(): SosOutcome {
        val contacts = people.emergencyContacts
        if (contacts.isEmpty()) {
            ui.say("Alerte de secours. Aucun contact d'urgence n'est enregistré : j'appelle le 112, le numéro d'urgence européen. Dites stop pour annuler.")
            if (ui.cancelledWithin(countdownMs)) { ui.say("Alerte annulée."); return SosOutcome(true, 0, 0, null, null) }
            val ok = phone.call("112", speaker = true)
            if (!ok) ui.say("Je n'ai pas réussi à appeler le 112.")
            return SosOutcome(false, 0, 0, if (ok) "112" else null, null)
        }
        ui.alertSound()
        ui.say("Alerte de secours. Dans 10 secondes, je préviens ${contacts.joinToString(" et ") { it.name }}. Dites stop pour annuler.")
        if (ui.cancelledWithin(countdownMs)) {
            ui.say("Alerte annulée, je ne préviens personne.")
            return SosOutcome(true, 0, 0, null, null)
        }
        // Passé le délai d'annulation, l'alerte part jusqu'au bout, même si un bouton est touché.
        return withContext(NonCancellable) { send(contacts) }
    }

    private suspend fun send(contacts: List<fr.assistantvocal.core.phone.EmergencyContact>): SosOutcome {
        ui.say("J'envoie l'alerte.")
        val point = runCatching { location.current(15_000) }.getOrNull()
        val text = message(ownerName(), point)
        var sent = 0; var failed = 0
        for (c in contacts) if (sms.send(c.number, text)) sent++ else failed++
        val report = when {
            failed == 0 -> "Message d'alerte envoyé à ${contacts.joinToString(" et ") { it.name }}."
            sent == 0 -> "Je n'ai pas réussi à envoyer les messages d'alerte."
            else -> "Message envoyé à $sent personne${if (sent > 1) "s" else ""}, échec pour $failed."
        }
        val where = if (point == null) " Je n'ai pas pu obtenir votre position." else ""
        val first = contacts.first()
        ui.say("$report$where J'appelle ${first.name}.")
        val called = if (phone.call(first.number, speaker = true)) first.name else {
            ui.say("L'appel n'a pas pu partir.")
            null
        }
        return SosOutcome(false, sent, failed, called, point)
    }

    companion object {
        fun message(owner: String, point: GeoPoint?): String {
            val where = if (point == null) "Position indisponible." else
                "Position : https://maps.google.com/?q=${"%.5f".format(Locale.US, point.lat)},${"%.5f".format(Locale.US, point.lon)}" +
                    (point.accuracyM?.let { " (à ${it.toInt()} m près)" } ?: "")
            return "ALERTE : $owner a demandé de l'aide avec son assistant vocal. $where Appelez-le au plus vite. Si pas de réponse, appelez le 112."
        }
    }
}

/** Annonces de batterie, de chargeur et de réseau, sans répétitions inutiles. */
class DeviceAlerts(private val nowMs: () -> Long) {
    private var lastLevelAnnounced: Int? = null
    private var charging: Boolean? = null
    private var online: Boolean? = null
    private var offlineSince: Long? = null
    private var announcedOffline = false

    /** Seuils annoncés : 20 %, 10 %, 5 %. */
    fun onBattery(percent: Int, isCharging: Boolean): String? {
        val wasCharging = charging
        charging = isCharging
        if (wasCharging != null && wasCharging != isCharging) {
            lastLevelAnnounced = null
            return if (isCharging) "Le téléphone est en charge, batterie à $percent pour cent." else "Le chargeur est débranché, batterie à $percent pour cent."
        }
        if (isCharging) return null
        val threshold = listOf(5, 10, 20).firstOrNull { percent <= it } ?: run { lastLevelAnnounced = null; return null }
        if (lastLevelAnnounced != null && lastLevelAnnounced!! <= threshold) return null
        lastLevelAnnounced = threshold
        return if (threshold <= 5) "Attention, batterie presque vide : $percent pour cent. Branchez vite le chargeur."
        else "Batterie faible : $percent pour cent. Pensez à brancher le chargeur."
    }

    /** Le réseau doit être absent 30 s avant d'être annoncé (évite les coupures brèves). */
    fun onNetwork(isOnline: Boolean): String? {
        val prev = online
        online = isOnline
        if (!isOnline) {
            if (prev != false) offlineSince = nowMs()
            return null
        }
        val wasAnnounced = announcedOffline
        announcedOffline = false; offlineSince = null
        return if (wasAnnounced) "Internet est revenu." else null
    }

    /** À appeler régulièrement : annonce la perte de réseau quand elle dure. */
    fun tick(): String? {
        val since = offlineSince ?: return null
        if (online == false && !announcedOffline && nowMs() - since >= 30_000) {
            announcedOffline = true
            return "Il n'y a plus d'internet. Les appels et l'heure fonctionnent toujours ; je préviendrai quand il reviendra."
        }
        return null
    }
}

/** Commandes reçues par SMS d'un proche de confiance (« OÙ ES-TU 1234 »). */
object RemoteCommands {
    enum class Command { LOCATE }

    fun parse(body: String, sender: String, people: PeopleStore, secret: String?): Command? {
        if (secret.isNullOrBlank()) return null
        if (people.emergencyContacts.none { PhoneFormat.same(it.number, sender) }) return null
        val k = FrenchText.key(body)
        if (!k.contains(FrenchText.key(secret))) return null
        return if (Regex("\\b(ou es tu|ou est tu|localise|position|sonne)\\b").containsMatchIn(k)) Command.LOCATE else null
    }
}
