package fr.assistantvocal.app.organizer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.skills.CalEvent
import fr.assistantvocal.core.skills.CalendarSource
import fr.assistantvocal.core.skills.EmailContacts
import fr.assistantvocal.core.skills.MailHeader
import fr.assistantvocal.core.skills.MailSource
import fr.assistantvocal.core.text.FrenchText
import fr.assistantvocal.llm.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm

private const val TAG = "Organizer"
private val ZONE: ZoneId = ZoneId.of("Europe/Paris")

/** Agenda du téléphone (calendriers Google synchronisés). */
class AndroidCalendar(private val context: Context) : CalendarSource {
    private fun allowed(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun events(from: LocalDateTime, to: LocalDateTime): List<CalEvent> {
        if (!allowed(Manifest.permission.READ_CALENDAR)) throw SecurityException("agenda non autorisé")
        val begin = from.atZone(ZONE).toInstant().toEpochMilli()
        val end = to.atZone(ZONE).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, begin); android.content.ContentUris.appendId(it, end)
        }.build()
        val out = mutableListOf<CalEvent>()
        context.contentResolver.query(uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.VISIBLE),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue
                val allDay = c.getInt(3) == 1
                // Les journées entières sont en UTC.
                val zone = if (allDay) ZoneId.of("UTC") else ZONE
                out += CalEvent(c.getString(0) ?: "Sans titre",
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), zone),
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), zone), allDay, c.getString(4))
            }
        }
        return out
    }

    override fun add(title: String, start: LocalDateTime, minutes: Int): Boolean {
        if (!allowed(Manifest.permission.WRITE_CALENDAR)) return false
        val calId = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
            null, "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false
        val begin = start.atZone(ZONE).toInstant().toEpochMilli()
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, begin + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZONE.id)
        }
        return runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) != null }
            .onFailure { Log.e(TAG, "Ajout à l'agenda impossible", it) }.getOrDefault(false)
    }
}

/** Adresses mail des contacts, avec les surnoms (« mon fils »). */
class AndroidEmailContacts(private val context: Context, private val people: PeopleStore) : EmailContacts {
    override fun find(name: String): List<Pair<String, String>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val alias = people.aliases[PeopleStore.normalizeAlias(name)]
        val q = FrenchText.key(alias ?: name)
        val all = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue; val a = c.getString(1) ?: continue
                all += n to a
            }
        }
        if (name.contains('@')) return all.filter { it.second.equals(name.trim(), true) }
        val words = q.split(' ').filter { it.isNotEmpty() }
        return all.filter { (n, _) -> FrenchText.key(n).let { k -> words.all { w -> k.split(' ').any { it == w || it.startsWith(w) } } } }
    }
}

/** Texte lisible d'un courriel : partie texte de préférence, sinon HTML converti. */
object MailText {
    fun extract(p: Part): String? {
        if (p.isMimeType("text/plain")) return (p.content as? String)?.trim()
        if (p.isMimeType("text/html")) return (p.content as? String)?.let { Html.text(it) }
        if (p.isMimeType("multipart/alternative")) {
            val mp = p.content as Multipart
            val parts = (0 until mp.count).map { mp.getBodyPart(it) }
            return parts.firstOrNull { it.isMimeType("text/plain") }?.let { extract(it) } ?: parts.firstNotNullOfOrNull { extract(it) }
        }
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            return (0 until mp.count).map { mp.getBodyPart(it) }
                .filter { Part.ATTACHMENT != it.disposition }.firstNotNullOfOrNull { extract(it) }
        }
        return null
    }

    fun header(m: Message, uid: Long): MailHeader {
        val from = (m.from?.firstOrNull() as? InternetAddress)
        return MailHeader(uid.toString(), from?.personal?.takeIf { it.isNotBlank() } ?: from?.address ?: "expéditeur inconnu",
            from?.address ?: "", m.subject ?: "", (m.receivedDate ?: m.sentDate)?.time ?: 0L, (m as? MimeMessage)?.messageID)
    }
}

/** Gmail en IMAP/SMTP avec un mot de passe d'application (ne expire pas, contrairement aux jetons OAuth de test). */
class ImapMail(
    private val address: () -> String,
    private val password: () -> String?,
    private val imapHost: String = "imap.gmail.com",
    private val smtpHost: String = "smtp.gmail.com",
) : MailSource {
    override fun isConfigured() = address().contains('@') && !password().isNullOrBlank()

    private fun session(): Session = Session.getInstance(Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "20000")
        put("mail.smtp.host", smtpHost); put("mail.smtp.port", "465"); put("mail.smtp.ssl.enable", "true"); put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", "15000"); put("mail.smtp.timeout", "20000")
    })

    private suspend fun <T> inbox(write: Boolean, block: (Folder) -> T): T? = withContext(Dispatchers.IO) {
        runCatching {
            session().getStore("imaps").also { it.connect(imapHost, address(), password()) }.use { store ->
                val f = store.getFolder("INBOX")
                f.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
                try { block(f) } finally { runCatching { f.close(false) } }
            }
        }.onFailure { Log.e(TAG, "IMAP", it) }.getOrNull()
    }

    override suspend fun unread(max: Int): List<MailHeader>? = inbox(false) { f ->
        val uf = f as UIDFolder
        f.search(FlagTerm(Flags(Flags.Flag.SEEN), false)).sortedByDescending { it.receivedDate ?: it.sentDate }.take(max)
            .map { MailText.header(it, uf.getUID(it)) }
    }

    override suspend fun body(id: String): String? = inbox(false) { f ->
        (f as UIDFolder).getMessageByUID(id.toLong())?.let { MailText.extract(it) }
    }

    override suspend fun markRead(id: String) {
        inbox(true) { f -> (f as UIDFolder).getMessageByUID(id.toLong())?.setFlag(Flags.Flag.SEEN, true) }
    }

    override suspend fun send(to: String, subject: String, body: String, inReplyTo: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val s = session()
            val m = MimeMessage(s).apply {
                setFrom(InternetAddress(address())); setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject, "UTF-8"); setText(body, "UTF-8")
                if (inReplyTo != null) { setHeader("In-Reply-To", inReplyTo); setHeader("References", inReplyTo) }
            }
            s.getTransport("smtp").use { t -> t.connect(smtpHost, address(), password()); t.sendMessage(m, m.allRecipients) }
            true
        }.onFailure { Log.e(TAG, "SMTP", it) }.getOrDefault(false)
    }
}
