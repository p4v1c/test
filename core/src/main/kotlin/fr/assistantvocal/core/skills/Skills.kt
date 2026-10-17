package fr.assistantvocal.core.skills

import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.router.AnswerCall
import fr.assistantvocal.core.router.CallBack
import fr.assistantvocal.core.router.CallContact
import fr.assistantvocal.core.router.CallFavorite
import fr.assistantvocal.core.router.CallNumber
import fr.assistantvocal.core.router.CancelTimer
import fr.assistantvocal.core.router.ContactNumber
import fr.assistantvocal.core.router.CreateReminder
import fr.assistantvocal.core.router.DeleteReminder
import fr.assistantvocal.core.router.HangUp
import fr.assistantvocal.core.router.Intent
import fr.assistantvocal.core.router.ListReminders
import fr.assistantvocal.core.router.MedicationQuestion
import fr.assistantvocal.core.router.MedicationTaken
import fr.assistantvocal.core.router.MissedCalls
import fr.assistantvocal.core.router.ReadMessages
import fr.assistantvocal.core.router.ReadNotifications
import fr.assistantvocal.core.router.Reply
import fr.assistantvocal.core.router.SendSms
import fr.assistantvocal.core.router.SetAlarm
import fr.assistantvocal.core.router.SetAlias
import fr.assistantvocal.core.router.SetTimer
import fr.assistantvocal.core.router.Speaker
import fr.assistantvocal.core.router.TimerStatus

/** Regroupe les compétences (partagent le même [dialog] que l'assistant). */
class Skills(
    val dialog: Dialog,
    val comms: CommunicationSkill,
    val reminders: ReminderSkill,
    val vision: fr.assistantvocal.core.vision.VisionSkill? = null,
    val info: InfoSkill? = null,
) {
    fun handle(intent: Intent): AssistantReply? = when (intent) {
        is CallContact -> comms.call(intent.target)
        is CallNumber -> comms.callNumber(intent.number)
        is CallFavorite -> comms.callFavorite(intent.position)
        CallBack -> comms.callBack()
        AnswerCall -> comms.answer()
        HangUp -> comms.hangUp()
        is Speaker -> comms.speaker(intent.on)
        MissedCalls -> comms.missedCalls()
        is ReadMessages -> comms.readMessages(intent.onlyLast)
        is SendSms -> comms.sendSms(intent.target, intent.body)
        is Reply -> comms.reply(intent.body)
        ReadNotifications -> comms.readNotifications()
        is SetAlias -> comms.setAlias(intent.target, intent.alias)
        is ContactNumber -> comms.contactNumber(intent.target)
        is SetAlarm -> reminders.setAlarm(intent.text)
        is SetTimer -> reminders.setTimer(intent.text)
        TimerStatus -> reminders.timerStatus()
        is CancelTimer -> reminders.cancelTimer(intent.text)
        is CreateReminder -> reminders.createReminder(intent.text)
        ListReminders -> reminders.list()
        is DeleteReminder -> reminders.delete(intent.text)
        is MedicationTaken -> reminders.medicationTaken(intent.text)
        is MedicationQuestion -> reminders.medicationQuestion(intent.text)
        else -> null
    }
}
