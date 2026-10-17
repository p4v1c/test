package fr.assistantvocal.app.phone

import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
import fr.assistantvocal.app.App
import fr.assistantvocal.app.AssistantService
import fr.assistantvocal.core.phone.AppNotification
import fr.assistantvocal.core.phone.NotificationSource
import fr.assistantvocal.core.safety.RemoteCommands
import java.time.Instant
import java.time.ZoneId

private const val TAG = "Receivers"

private fun Context.toService(intent: Intent) {
    runCatching { startForegroundService(intent) }.onFailure { e ->
        runCatching { startService(intent) }.onFailure { Log.w(TAG, "Service injoignable", e) }
    }
}

/** Un rappel (réveil, minuteur, médicament…) sonne. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        context.toService(AssistantService.intent(context, AssistantService.ACTION_REMINDER_FIRE).putExtra(EXTRA_ID, id))
    }

    companion object { const val EXTRA_ID = "reminder_id" }
}

/** SMS reçu : annonce, ou commande à distance d'un proche (« où es-tu » + mot secret). */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (parts.isEmpty()) return
        val sender = parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val graph = App.graph(context)
        val command = RemoteCommands.parse(body, sender, graph.people, graph.settings.remoteSecret)
        val action = if (command == RemoteCommands.Command.LOCATE) AssistantService.ACTION_REMOTE_LOCATE else AssistantService.ACTION_SMS_RECEIVED
        context.toService(AssistantService.intent(context, action)
            .putExtra(AssistantService.EXTRA_NUMBER, sender)
            .putExtra(AssistantService.EXTRA_TEXT, body)
            .putExtra(AssistantService.EXTRA_TIME, parts[0].timestampMillis))
    }
}

/** État des appels (sonnerie avec le numéro, décroché, raccroché). */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        context.toService(AssistantService.intent(context, AssistantService.ACTION_CALL_STATE)
            .putExtra(AssistantService.EXTRA_STATE, state)
            .putExtra(AssistantService.EXTRA_NUMBER, number))
    }
}

/**
 * Notifications des autres applications (Gmail, Meet, Messages…). Gardées en mémoire pour
 * « lis mes notifications » ; les nouvelles notifications de messagerie sont annoncées.
 */
class AssistantNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = toAppNotification(sbn) ?: return
        NotificationHub.post(n, sbn)
        val settings = App.graph(this).settings
        if (settings.announceNotifications && NotificationHub.isMessaging(sbn)) {
            toService(AssistantService.intent(this, AssistantService.ACTION_ANNOUNCE)
                .putExtra(AssistantService.EXTRA_TEXT, "Notification de ${n.app} : ${n.title}. ${n.text}"))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationHub.remove(sbn.key)

    private fun toAppNotification(sbn: StatusBarNotification): AppNotification? {
        if (sbn.packageName == packageName || sbn.isOngoing) return null
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // Les SMS sont lus par l'app elle-même (texte complet) : on ignore la notification en double.
        if (sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this)) return null
        val extras: Bundle = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val canReply = n.actions?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true
        return AppNotification(sbn.key, app, sbn.packageName, title, text.take(600),
            Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()), canReply)
    }
}

/** Mémoire des notifications récentes, et réponse rapide via les actions des notifications. */
object NotificationHub : NotificationSource {
    private val items = LinkedHashMap<String, Pair<AppNotification, StatusBarNotification>>()

    @Synchronized fun post(n: AppNotification, sbn: StatusBarNotification) {
        items.remove(n.key); items[n.key] = n to sbn
        while (items.size > 30) items.remove(items.keys.first())
    }

    @Synchronized fun remove(key: String) { items.remove(key) }

    @Synchronized override fun recent(): List<AppNotification> = items.values.map { it.first }

    fun isMessaging(sbn: StatusBarNotification): Boolean {
        val cat = sbn.notification.category
        return cat == Notification.CATEGORY_MESSAGE || cat == Notification.CATEGORY_EMAIL || cat == Notification.CATEGORY_CALL ||
            sbn.packageName in setOf("com.google.android.gm", "com.google.android.apps.tachyon", "org.thoughtcrime.securesms")
    }

    override fun reply(key: String, text: String): Boolean {
        val sbn = synchronized(this) { items[key]?.second } ?: return false
        val action = sbn.notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return false
        return runCatching {
            val intent = Intent()
            val results = Bundle().apply { action.remoteInputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            action.actionIntent.send(App.appContext, 0, intent)
            true
        }.getOrDefault(false)
    }
}
