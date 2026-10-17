package fr.assistantvocal.app.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import fr.assistantvocal.app.MainActivity
import fr.assistantvocal.core.phone.CallLogEntry
import fr.assistantvocal.core.phone.CallLogSource
import fr.assistantvocal.core.phone.CallState
import fr.assistantvocal.core.phone.CallType
import fr.assistantvocal.core.phone.Contact
import fr.assistantvocal.core.phone.ContactsSource
import fr.assistantvocal.core.phone.GeoPoint
import fr.assistantvocal.core.phone.LocationSource
import fr.assistantvocal.core.phone.PhoneActions
import fr.assistantvocal.core.phone.PhoneNumber
import fr.assistantvocal.core.phone.SmsMessage
import fr.assistantvocal.core.phone.SmsSender
import fr.assistantvocal.core.phone.SmsSource
import fr.assistantvocal.core.reminders.AlarmScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "AndroidPhone"

private fun Context.has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
private fun millisToZoned(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

/** Contacts du téléphone (mis en cache 60 s). */
class AndroidContacts(private val context: Context) : ContactsSource {
    @Volatile private var cache: Pair<Long, List<Contact>>? = null

    override fun all(): List<Contact> {
        cache?.let { (t, list) -> if (System.currentTimeMillis() - t < 60_000) return list }
        if (!context.has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val byId = LinkedHashMap<String, Pair<String, MutableList<PhoneNumber>>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val label = when (c.getInt(3)) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "domicile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK, ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "travail"
                    else -> null
                }
                byId.getOrPut(id) { name to mutableListOf() }.second += PhoneNumber(number, label)
            }
        }
        val list = byId.map { (id, v) -> Contact(id, v.first, v.second.distinctBy { it.number.filter(Char::isDigit) }) }
        cache = System.currentTimeMillis() to list
        return list
    }
}

class AndroidCallLog(private val context: Context) : CallLogSource {
    override fun recent(limit: Int): List<CallLogEntry> {
        if (!context.has(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<CallLogEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val type = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                    CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                    CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                    CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> CallType.REJECTED
                    else -> CallType.INCOMING
                }
                out += CallLogEntry(c.getString(0) ?: "", c.getString(1)?.takeIf { it.isNotBlank() }, type, millisToZoned(c.getLong(3)), c.getLong(4))
            }
        }
        return out
    }
}

/** SMS reçus (lecture seule : l'app n'est pas l'application SMS par défaut). */
class AndroidSms(private val context: Context) : SmsSource {
    override fun inbox(limit: Int): List<SmsMessage> {
        if (!context.has(Manifest.permission.READ_SMS)) return emptyList()
        val out = mutableListOf<SmsMessage>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += SmsMessage(c.getLong(0), c.getString(1) ?: "", null, c.getString(2) ?: "", millisToZoned(c.getLong(3)))
            }
        }
        return out
    }
}

/** Envoi de SMS avec attente de l'accusé d'envoi du réseau (« vraiment parti »). */
class AndroidSmsSender(private val context: Context) : SmsSender {
    private val seq = AtomicInteger()

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override suspend fun send(number: String, text: String): Boolean {
        if (!context.has(Manifest.permission.SEND_SMS)) return false
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        val action = "fr.assistantvocal.SMS_SENT.${seq.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>()
        var remaining = parts.size
        var allOk = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode != android.app.Activity.RESULT_OK) allOk = false
                if (--remaining <= 0) done.complete(allOk)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, IntentFilter(action))
        return try {
            val sent = ArrayList(parts.indices.map {
                PendingIntent.getBroadcast(context, it, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            })
            withContext(Dispatchers.IO) { sms.sendMultipartTextMessage(number, null, parts, sent, null) }
            withTimeoutOrNull(45_000) { done.await() } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Envoi SMS impossible", e); false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}

/** Appels : passer, décrocher, raccrocher, haut-parleur. Fonctionne sans être l'application Téléphone. */
class AndroidPhoneActions(private val context: Context, private val speakerOnAnswer: () -> Boolean) : PhoneActions {
    private val telecom get() = context.getSystemService(TelecomManager::class.java)

    @SuppressLint("MissingPermission")
    override fun call(number: String, speaker: Boolean): Boolean {
        if (!context.has(Manifest.permission.CALL_PHONE)) return false
        return runCatching {
            val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, speaker) }
            telecom.placeCall(Uri.fromParts("tel", number.filter { it.isDigit() || it == '+' }, null), extras)
            true
        }.getOrElse { Log.e(TAG, "Appel impossible", it); false }
    }

    @SuppressLint("MissingPermission")
    override fun answer(): Boolean {
        if (!context.has(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching {
            telecom.acceptRingingCall()
            if (speakerOnAnswer()) android.os.Handler(context.mainLooper).postDelayed({ setSpeaker(true) }, 1200)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun hangUp(): Boolean {
        if (!context.has(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        @Suppress("DEPRECATION")
        return runCatching { telecom.endCall() }.getOrDefault(false)
    }

    override fun setSpeaker(on: Boolean): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val spk = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return false
                    am.setCommunicationDevice(spk)
                } else { am.clearCommunicationDevice(); true }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
                true
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun state(): CallState {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return CallState.IDLE
        val s = runCatching {
            if (Build.VERSION.SDK_INT >= 31) tm.callStateForSubscription else @Suppress("DEPRECATION") tm.callState
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
        return when (s) {
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
            else -> CallState.IDLE
        }
    }
}

/** Position GPS (ou réseau), 15 s maximum, avec repli sur la dernière position connue. */
class AndroidLocation(private val context: Context) : LocationSource {
    @SuppressLint("MissingPermission")
    override suspend fun current(timeoutMs: Long): GeoPoint? {
        if (!context.has(Manifest.permission.ACCESS_FINE_LOCATION) && !context.has(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                val result = CompletableDeferred<Location?>()
                val cancel = CancellationSignal()
                runCatching { lm.getCurrentLocation(p, cancel, context.mainExecutor) { result.complete(it) } }
                val loc = withTimeoutOrNull(timeoutMs / providers.size.coerceAtLeast(1)) { result.await() }
                if (loc == null) cancel.cancel() else return GeoPoint(loc.latitude, loc.longitude, loc.accuracy)
            }
        }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return GeoPoint(last.latitude, last.longitude, last.accuracy)
    }
}

/** Réveils de l'app : AlarmManager.setAlarmClock (fiable même en veille profonde). */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val am get() = context.getSystemService(AlarmManager::class.java)

    private fun pending(id: Int, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, id, Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_ID, id),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun schedule(id: Int, atEpochMs: Long) {
        val pi = pending(id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(atEpochMs, show), pi) }
            .onFailure {
                Log.w(TAG, "Alarme exacte refusée, alarme approchée", it)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
            }
    }

    override fun cancel(id: Int) {
        pending(id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }
}
