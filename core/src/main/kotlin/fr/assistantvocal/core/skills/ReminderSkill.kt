package fr.assistantvocal.core.skills

import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.reminders.AlarmScheduler
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.Reminder
import fr.assistantvocal.core.reminders.ReminderKind
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.text.FrenchText
import fr.assistantvocal.core.time.FrenchTime
import fr.assistantvocal.core.time.When
import java.time.Duration
import java.time.ZonedDateTime

/** Ce que l'application doit faire quand un rappel sonne. */
data class FireAnnouncement(
    val reminderId: Int,
    val speech: String,
    val kind: ReminderKind,
    /** Faire sonner l'alarme (réveil, minuteur) en plus de la voix. */
    val ring: Boolean,
    /** Nombre de répétitions de l'annonce sans réponse. */
    val repeats: Int,
)

/** Réveils, minuteurs, rappels et médicaments. Tout est géré par l'app (annonces parlées), pas par l'horloge système. */
class ReminderSkill(
    private val reminders: ReminderStore,
    private val medications: MedicationLog,
    private val scheduler: AlarmScheduler,
    private val store: KeyValueStore,
    private val device: DeviceStatus,
    private val dialog: Dialog,
) {
    private fun now(): ZonedDateTime = device.now()

    // ------------------------------------------------------------------ création

    fun setAlarm(text: String): AssistantReply {
        val parsed = FrenchTime.parseWhen(text, now().toLocalDateTime())
            ?: return dialog.ask("À quelle heure voulez-vous être réveillé ?") { setAlarm("à $it") }
        val r = build(ReminderKind.ALARM, "réveil", parsed.whenever)
        add(r)
        return AssistantReply("Réveil réglé ${describe(r)}.", Earcon.SUCCESS)
    }

    fun setTimer(text: String): AssistantReply {
        val d = FrenchTime.parseDuration(text)
            ?: return dialog.ask("Pour combien de temps ?") { setTimer("$text $it") }
        val label = timerLabel(text)
        val r = Reminder(reminders.nextId(), ReminderKind.TIMER, label,
            atEpochMs = now().plus(d).toInstant().toEpochMilli(), createdEpochMs = now().toInstant().toEpochMilli())
        add(r)
        val what = if (label == "minuteur") "Minuteur" else "Minuteur $label"
        return AssistantReply("$what de ${FrenchTime.speakDuration(d)}, c'est parti.", Earcon.SUCCESS)
    }

    /** « minuteur de 10 minutes pour les pâtes » -> « des pâtes » ; sans précision -> « minuteur ». */
    private fun timerLabel(text: String): String {
        val raw = FrenchText.normalize(text)
        val m = Regex("\\b(?:pour|des|du|de la|pour les|pour le|pour la)\\s+([\\p{L} ]+)$").find(raw)
            ?: Regex("\\bminuteur\\s+([\\p{L}]+)").find(raw)?.takeIf { FrenchTime.parseDuration(it.groupValues[1]) == null && it.groupValues[1] !in setOf("de", "pour", "dans") }
        val what = m?.groupValues?.get(1)?.trim()?.removePrefix("les ")?.removePrefix("le ")?.removePrefix("la ")
        return if (what.isNullOrBlank() || FrenchTime.parseDuration(what) != null) "minuteur" else "des $what".replace("des de ", "des ")
    }

    fun createReminder(text: String): AssistantReply {
        val parsed = FrenchTime.parseWhen(text, now().toLocalDateTime())
        val lastFired = store.getString(K_LAST_FIRED)
        if (parsed == null) {
            return dialog.ask("Quand voulez-vous que je vous le rappelle ?") { createReminder("$text $it") }
        }
        var label = cleanLabel(parsed.rest)
        if (label.isBlank()) label = lastFired ?: return dialog.ask("De quoi voulez-vous que je vous fasse penser ?") { what ->
            createReminder("$text de $what")
        }
        val kind = if (isMedication(label)) ReminderKind.MEDICATION else ReminderKind.REMINDER
        val r = build(kind, label, parsed.whenever)
        add(r)
        val extra = if (kind == ReminderKind.MEDICATION) " Quand vous l'aurez pris, dites-moi simplement : c'est fait." else ""
        return AssistantReply("D'accord, je vous rappellerai ${describe(r)} : $label.$extra", Earcon.SUCCESS)
    }

    private fun build(kind: ReminderKind, label: String, w: When): Reminder = when (w) {
        is When.At -> Reminder(reminders.nextId(), kind, label,
            atEpochMs = w.dateTime.atZone(now().zone).toInstant().toEpochMilli(), createdEpochMs = now().toInstant().toEpochMilli())
        is When.Recurring -> Reminder(reminders.nextId(), kind, label,
            times = w.times.map { it.toString() }, days = w.days.map { it.value }.sorted(), createdEpochMs = now().toInstant().toEpochMilli())
    }

    private fun add(r: Reminder) {
        reminders.add(r)
        r.nextAfter(now())?.let { scheduler.schedule(r.id, it.toInstant().toEpochMilli()) }
    }

    /** « de prendre mon Doliprane » -> « prendre mon Doliprane ». */
    private fun cleanLabel(rest: String): String = rest.trim()
        .replace(Regex("^(de |d |d'|que |qu |qu'|pour |a |à )"), "")
        .replace(Regex("^(je dois |il faut que je |faut que je )"), "")
        .trim()

    private fun isMedication(label: String): Boolean {
        val k = FrenchText.key(label)
        return Regex("\\b(medicaments?|cachets?|comprimes?|pilules?|gouttes|sirop|insuline|traitement|gelules?|piqure|collyre|pommade|doliprane|dafalgan|efferalgan|kardegic|levothyrox|aspirine|metformine)\\b").containsMatchIn(k) ||
            Regex("^prendre (mon|ma|mes|le|la|les) ").containsMatchIn(k)
    }

    private fun describe(r: Reminder): String {
        if (r.isRecurring) return FrenchTime.speakRecurrence(When.Recurring(r.localTimes(), r.dayOfWeeks()))
        val at = r.nextAfter(now().minusSeconds(1)) ?: return "bientôt"
        return FrenchTime.speakDateTime(at.toLocalDateTime(), now().toLocalDateTime())
    }

    // ------------------------------------------------------------------ consultation et suppression

    fun list(): AssistantReply {
        val now = now()
        val upcoming = reminders.all().mapNotNull { r -> r.nextAfter(now)?.let { r to it } }.sortedBy { it.second }
        if (upcoming.isEmpty()) return AssistantReply("Vous n'avez aucun rappel ni réveil prévu.")
        val text = upcoming.take(6).joinToString(". ") { (r, at) ->
            val what = when (r.kind) { ReminderKind.ALARM -> "Réveil"; ReminderKind.TIMER -> "Minuteur ${r.label.takeIf { it != "minuteur" } ?: ""}".trim(); else -> r.label.replaceFirstChar { it.uppercase() } }
            val whenText = if (r.isRecurring) FrenchTime.speakRecurrence(When.Recurring(r.localTimes(), r.dayOfWeeks()))
            else FrenchTime.speakDateTime(at.toLocalDateTime(), now.toLocalDateTime())
            "$what, $whenText"
        }
        return AssistantReply("Vous avez ${upcoming.size} rappel${if (upcoming.size > 1) "s" else ""} : $text.")
    }

    fun timerStatus(): AssistantReply {
        val now = now()
        val timers = reminders.all().filter { it.kind == ReminderKind.TIMER }.mapNotNull { t -> t.nextAfter(now)?.let { t to it } }
        if (timers.isEmpty()) return AssistantReply("Aucun minuteur en cours.")
        return AssistantReply(timers.joinToString(". ") { (t, at) ->
            val left = Duration.between(now, at).withNanos(0)
            val name = if (t.label == "minuteur") "Le minuteur" else "Le minuteur ${t.label}"
            "$name : il reste ${FrenchTime.speakDuration(left)}"
        } + ".")
    }

    fun cancelTimer(text: String): AssistantReply {
        val timers = reminders.all().filter { it.kind == ReminderKind.TIMER && it.nextAfter(now()) != null }
        if (timers.isEmpty()) return AssistantReply("Aucun minuteur en cours.")
        val matching = if (timers.size == 1) timers else reminders.search(text, setOf(ReminderKind.TIMER)).ifEmpty { timers }
        if (matching.size > 1) return AssistantReply("Il y a ${matching.size} minuteurs : ${matching.joinToString(" et ") { it.label }}. Lequel voulez-vous annuler ?")
        val t = matching.single()
        remove(t.id)
        return AssistantReply("Minuteur ${t.label.takeIf { it != "minuteur" } ?: ""} annulé.".replace("  ", " "), Earcon.SUCCESS)
    }

    fun delete(text: String): AssistantReply {
        val kinds = if (Regex("\\b(reveil|alarme)").containsMatchIn(FrenchText.key(text))) setOf(ReminderKind.ALARM) else ReminderKind.entries.toSet() - ReminderKind.TIMER
        val found = reminders.search(text, kinds)
        if (found.isEmpty()) return AssistantReply("Je ne trouve pas ce rappel. Dites : quels sont mes rappels, pour les entendre.", Earcon.ERROR)
        if (found.size > 1 && text.isNotBlank()) return AssistantReply("Plusieurs rappels correspondent : ${found.take(4).joinToString(", ") { it.label }}. Précisez lequel.")
        if (found.size > 1) return AssistantReply("Vous avez ${found.size} rappels. Dites par exemple : supprime le rappel du médecin.")
        val r = found.single()
        return dialog.confirm("Supprimer le rappel « ${r.label} », ${describe(r)} ? Répondez oui ou non.") {
            remove(r.id); "Rappel supprimé."
        }
    }

    private fun remove(id: Int) {
        reminders.remove(id); scheduler.cancel(id); scheduler.cancel(followUpId(id))
    }

    // ------------------------------------------------------------------ médicaments

    fun medicationTaken(text: String): AssistantReply {
        val pending = store.getString(K_MED_PENDING)?.toIntOrNull()?.let { reminders.get(it) }
        val k = FrenchText.key(text)
        val name = when {
            k.isNotBlank() && k !in setOf("medicament", "medicaments", "traitement") -> text.trim()
            pending != null -> medicationName(pending.label)
            else -> "médicament"
        }
        if (pending == null && k.isBlank()) return AssistantReply("D'accord.")
        medications.record(name, now().toInstant())
        if (pending != null) {
            store.putString(K_MED_PENDING, null)
            scheduler.cancel(followUpId(pending.id))
        }
        return AssistantReply("C'est noté : $name, à ${FrenchTime.speakTime(now().toLocalTime().withSecond(0).withNano(0))}.", Earcon.SUCCESS)
    }

    fun medicationQuestion(text: String): AssistantReply {
        val now = now()
        val name = FrenchText.normalize(text).replace(Regex("\\b(mon|ma|mes|le|la|les|ce matin|ce soir|aujourd'hui|aujourd hui|cet apres-midi)\\b"), " ").trim()
        val taken = medications.takenOn(now.toLocalDate(), now.zone, name.ifBlank { null })
        val what = name.ifBlank { "votre médicament" }
        if (taken.isEmpty()) {
            val scheduled = reminders.all().filter { it.kind == ReminderKind.MEDICATION }
                .filter { name.isBlank() || FrenchText.key(it.label).contains(FrenchText.key(name)) }
            val hint = scheduled.firstOrNull()?.let { " Votre rappel est prévu ${describe(it)}." } ?: ""
            return AssistantReply("Je n'ai rien noté aujourd'hui pour $what.$hint Si vous l'avez pris, dites : j'ai pris $what.")
        }
        val times = taken.joinToString(" et à ") {
            FrenchTime.speakTime(java.time.Instant.ofEpochMilli(it.epochMs).atZone(now.zone).toLocalTime().withSecond(0).withNano(0))
        }
        return AssistantReply("Oui, vous avez pris ${taken.last().name} aujourd'hui à $times.")
    }

    private fun medicationName(label: String) = label.replace(Regex("^prendre (mon |ma |mes |le |la |les )?"), "").ifBlank { "médicament" }

    // ------------------------------------------------------------------ déclenchement

    /** Appelé quand le planificateur sonne. Renvoie l'annonce, et reprogramme la prochaine occurrence. */
    fun onFire(id: Int): FireAnnouncement? {
        val now = now()
        if (id >= FOLLOW_UP_OFFSET) {
            val r = reminders.get(id - FOLLOW_UP_OFFSET) ?: return null
            if (store.getString(K_MED_PENDING)?.toIntOrNull() != r.id) return null
            val count = store.getInt(K_MED_FOLLOWUPS, 0) + 1
            store.putInt(K_MED_FOLLOWUPS, count)
            if (count < 3) scheduler.schedule(followUpId(r.id), now.plusMinutes(20).toInstant().toEpochMilli())
            return FireAnnouncement(r.id, "Je vous rappelle de ${r.label}. Avez-vous pu le faire ? Dites : c'est fait.", r.kind, false, 1)
        }
        val r = reminders.get(id) ?: return null
        // Prochaine occurrence ou suppression
        if (r.isRecurring) r.nextAfter(now.plusSeconds(30))?.let { scheduler.schedule(r.id, it.toInstant().toEpochMilli()) }
        else reminders.remove(r.id)
        store.putString(K_LAST_FIRED, r.label)
        return when (r.kind) {
            ReminderKind.ALARM -> FireAnnouncement(r.id, "Il est ${FrenchTime.speakTime(now.toLocalTime().withSecond(0).withNano(0))}. C'est l'heure de votre réveil. Dites stop pour l'arrêter.", r.kind, true, 10)
            ReminderKind.TIMER -> FireAnnouncement(r.id, if (r.label == "minuteur") "Le minuteur est terminé." else "Le minuteur ${r.label} est terminé.", r.kind, true, 6)
            ReminderKind.REMINDER -> FireAnnouncement(r.id, "Rappel : ${r.label.replaceFirstChar { it.uppercase() }}.", r.kind, false, 2)
            ReminderKind.MEDICATION -> {
                store.putString(K_MED_PENDING, r.id.toString())
                store.putInt(K_MED_FOLLOWUPS, 0)
                scheduler.schedule(followUpId(r.id), now.plusMinutes(20).toInstant().toEpochMilli())
                FireAnnouncement(r.id, "C'est l'heure de ${r.label}. Quand c'est fait, dites-moi : c'est fait.", r.kind, false, 2)
            }
        }
    }

    /** Après un redémarrage, Android oublie les alarmes : on les reprogramme toutes. */
    fun rescheduleAll() {
        val now = now()
        for (r in reminders.all()) {
            val next = r.nextAfter(now)
            if (next == null) {
                // Rappel ponctuel manqué pendant que le téléphone était éteint : on le donne tout de suite.
                if (!r.isRecurring && r.kind != ReminderKind.TIMER) scheduler.schedule(r.id, now.plusSeconds(30).toInstant().toEpochMilli())
                else if (r.kind == ReminderKind.TIMER) reminders.remove(r.id)
            } else scheduler.schedule(r.id, next.toInstant().toEpochMilli())
        }
    }

    companion object {
        const val FOLLOW_UP_OFFSET = 1_000_000
        fun followUpId(id: Int) = id + FOLLOW_UP_OFFSET
        private const val K_MED_PENDING = "medication.pendingReminder"
        private const val K_MED_FOLLOWUPS = "medication.followUps"
        private const val K_LAST_FIRED = "reminders.lastFiredLabel"
    }
}
