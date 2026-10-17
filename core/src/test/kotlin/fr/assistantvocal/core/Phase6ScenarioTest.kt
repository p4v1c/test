package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.FollowUp
import fr.assistantvocal.core.media.AudioFolderBook
import fr.assistantvocal.core.media.AudioPlayer
import fr.assistantvocal.core.media.Book
import fr.assistantvocal.core.media.BookFiles
import fr.assistantvocal.core.media.Daisy
import fr.assistantvocal.core.media.Episode
import fr.assistantvocal.core.media.LibrarySource
import fr.assistantvocal.core.media.MediaState
import fr.assistantvocal.core.media.Memo
import fr.assistantvocal.core.media.MemoStore
import fr.assistantvocal.core.media.MusicSource
import fr.assistantvocal.core.media.PodcastDirectory
import fr.assistantvocal.core.media.PodcastShow
import fr.assistantvocal.core.media.Pos
import fr.assistantvocal.core.media.RadioSearch
import fr.assistantvocal.core.media.Shelf
import fr.assistantvocal.core.media.Station
import fr.assistantvocal.core.media.TitleMatch
import fr.assistantvocal.core.media.Track
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.router.Intent
import fr.assistantvocal.core.router.ListReminders
import fr.assistantvocal.core.router.LocalRouter
import fr.assistantvocal.core.router.News
import fr.assistantvocal.core.router.NewsMore
import fr.assistantvocal.core.router.NextItem
import fr.assistantvocal.core.router.PauseMedia
import fr.assistantvocal.core.router.ReadMessages
import fr.assistantvocal.core.router.SetAlias
import fr.assistantvocal.core.router.SetTimer
import fr.assistantvocal.core.router.Speaker
import fr.assistantvocal.core.router.CreateReminder
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.skills.CalEvent
import fr.assistantvocal.core.skills.CalendarSource
import fr.assistantvocal.core.skills.CommunicationSkill
import fr.assistantvocal.core.skills.MailHeader
import fr.assistantvocal.core.skills.MailSkill
import fr.assistantvocal.core.skills.MailSource
import fr.assistantvocal.core.skills.MediaSkill
import fr.assistantvocal.core.skills.OrganizerSkill
import fr.assistantvocal.core.skills.ReminderSkill
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Lecteur simulé : garde la position et l'état. */
class FakePlayer : AudioPlayer {
    var tracks: List<Track> = emptyList()
    var pos: Pos? = null
    var playing = false
    var rate = 1f
    var live = false
    val plays = mutableListOf<String>()
    override var onFinished: (() -> Unit)? = null
    override fun play(tracks: List<Track>, start: Pos, speed: Float, live: Boolean) {
        this.tracks = tracks; pos = start; rate = speed; this.live = live; playing = true; plays += tracks.first().uri
    }
    override fun pause() { playing = false }
    override fun resume() { if (pos != null) playing = true }
    override fun stop() { playing = false; pos = null; tracks = emptyList() }
    override fun seek(pos: Pos) { this.pos = pos }
    override fun setSpeed(speed: Float) { rate = speed }
    override fun isPlaying() = playing
    override fun position() = pos
    override fun durationOf(track: Int): Long? = null
}

/** Fichiers d'un livre DAISY 2.02 de test (Le Petit Prince, 4 fichiers audio). */
object DaisyFixtures {
    val ncc = """<?xml version="1.0" encoding="utf-8"?><html><head><title>Le Petit Prince</title>
<meta name="dc:title" content="Le Petit Prince"/><meta name="ncc:totalTime" content="0:35:05"/></head><body>
<h1 class="title" id="t1"><a href="s01.smil#p1">Le Petit Prince</a></h1>
<h1 id="c1"><a href="s02.smil#p1">Chapitre 1</a></h1>
<span class="page-normal" id="pg1"><a href="s02.smil#t3">1</a></span>
<h2 id="c1a"><a href="s02.smil#p5">Le dessin du serpent</a></h2>
<h1 id="c2"><a href="s03.smil#p1">Chapitre 2</a></h1>
<span class="page-normal" id="pg2"><a href="s03.smil#p1">2</a></span>
<h1 id="c3"><a href="S04.SMIL#p1">Chapitre 3 &amp; fin</a></h1>
</body></html>"""
    private fun smil(vararg clips: Triple<String, String, String>) = """<smil><body><seq>""" + clips.mapIndexed { i, (src, b, e) ->
        """<par endsync="last" id="p${i * 2 + 1}"><text src="ncc.html#x" id="t${i * 2 + 1}"/><audio src="$src" clip-begin="npt=${b}s" clip-end="npt=${e}s" id="a$i"/></par>"""
    }.joinToString("") + "</seq></body></smil>"
    val files = mapOf(
        "ncc.html" to ncc,
        "s01.smil" to smil(Triple("a01.mp3", "0.000", "5.000")),
        "s02.smil" to smil(Triple("a02.mp3", "0.000", "60.000"), Triple("a02.mp3", "60.000", "120.000"), Triple("a02.mp3", "120.000", "300.000")),
        "s03.smil" to smil(Triple("a03.mp3", "0.000", "600.000")),
        "s04.smil" to smil(Triple("a04.mp3", "0.000", "1200.000")),
    )
    fun book(): Book = Daisy.parse("daisy:petit-prince", "Petit_Prince", { n -> files.entries.firstOrNull { it.key.equals(n, true) }?.value }, { "uri://$it" }, "ncc.html")!!
}

class Phase6ScenarioTest {
    private val store = InMemoryStore()
    private val settings = AssistantSettings(store).apply { name = "Hector"; ownerName = "Jean" }
    private val device = FakeDevice()
    private var now = 1_000_000L
    private val phone = FakePhone()
    private val dialog = Dialog { 0L }
    private val people = PeopleStore(store)
    private val comms = CommunicationSkill(ContactDirectory(phone, people), people, phone, phone, phone, phone, phone, store, device, dialog)
    private val reminderStore = ReminderStore(store)
    private val reminders = ReminderSkill(reminderStore, MedicationLog(store), phone, store, device, dialog)
    private val player = FakePlayer()
    private val audioBook = AudioFolderBook.build("folder:contes", "Contes_de_Perrault", Shelf.BOOKS,
        listOf("10 Barbe bleue.mp3" to "u10", "2 Le Chat botte.mp3" to "u2", "1 Cendrillon.mp3" to "u1", "couverture.jpg" to "img"))!!
    private val library = object : LibrarySource { override suspend fun scan() = listOf(DaisyFixtures.book(), audioBook) }
    private val radioSearch = object : RadioSearch {
        override suspend fun search(name: String) = if (name.lowercase().contains("rtl")) listOf(Station("RTL", "http://rtl.example/stream")) else emptyList()
    }
    private val episodes = listOf(
        Episode("L'affaire du siècle", "https://pod.example/ep3.mp3", 1_760_000_000_000, 3_000_000),
        Episode("Le procès", "https://pod.example/ep2.mp3", 1_759_000_000_000, 2_900_000),
    )
    private val podcastDir = object : PodcastDirectory {
        override suspend fun search(term: String) = listOf(PodcastShow("Affaires sensibles", "France Inter", "https://pod.example/feed.xml"))
        override suspend fun episodes(feedUrl: String) = episodes
    }
    val externalCalls = mutableListOf<String>()
    private val musicSource = object : MusicSource {
        override suspend fun local(query: String?) = when {
            query == null -> listOf(Track("m1", "La mauvaise réputation"), Track("m2", "Les copains d'abord"))
            query.lowercase().contains("brassens") -> listOf(Track("m1", "La mauvaise réputation"), Track("m2", "Les copains d'abord"))
            else -> emptyList()
        }
        override fun playExternal(query: String): Boolean { externalCalls += "search:$query"; return true }
        override fun controlExternal(action: String): Boolean { externalCalls += action; return false }
    }
    private val memoList = mutableListOf(Memo("memo1.m4a", 1_760_600_000_000, 5_000), Memo("memo2.m4a", 1_760_700_000_000, 8_000))
    private val memoStore = object : MemoStore {
        override fun list() = memoList.toList()
        override fun delete(memo: Memo) = memoList.remove(memo)
    }
    private val events = mutableListOf(
        CalEvent("Dentiste", LocalDateTime.of(2026, 10, 18, 14, 30), LocalDateTime.of(2026, 10, 18, 15, 30), location = "Valence"),
        CalEvent("Anniversaire de Marie", LocalDateTime.of(2026, 10, 20, 0, 0), LocalDateTime.of(2026, 10, 21, 0, 0), allDay = true),
    )
    private val added = mutableListOf<Pair<String, LocalDateTime>>()
    private val calendar = object : CalendarSource {
        override fun events(from: LocalDateTime, to: LocalDateTime) = events.filter { it.start >= from && it.start < to }
        override fun add(title: String, start: LocalDateTime, minutes: Int): Boolean { added += title to start; return true }
    }
    private val sentMails = mutableListOf<Triple<String, String, String>>()
    private val read = mutableListOf<String>()
    private val mailbox = object : MailSource {
        override fun isConfigured() = true
        override suspend fun unread(max: Int) = listOf(
            MailHeader("1", "Mairie de Saint-Marcel", "mairie@stmarcel.fr", "Repas des aînés", 1_760_690_000_000, "<m1@x>"),
            MailHeader("2", "Service Client", "noreply@colis-suivi.top", "Votre colis est bloqué", 1_760_680_000_000),
        )
        override suspend fun body(id: String) = if (id == "1") "Bonjour, le repas des aînés aura lieu le 15 novembre à midi à la salle des fêtes. Merci de répondre avant le 5 novembre."
            else "Votre colis est bloqué. Payez les frais de port de 1,99 euro sur https://colis-suivi.top immédiatement."
        override suspend fun markRead(id: String) { read += id }
        override suspend fun send(to: String, subject: String, body: String, inReplyTo: String?): Boolean { sentMails += Triple(to, subject, body); return true }
    }
    private val llm = ScriptedLlm()

    private fun media() = MediaSkill(player, library, MediaState(store), radioSearch, podcastDir, musicSource, memoStore, dialog, device, nowMs = { now })
    private val mediaSkill = media()
    private val organizer = OrganizerSkill(MediaState(store), calendar, reminderStore, dialog, device,
        weatherToday = { "Aujourd'hui : ciel dégagé, de 8 degrés à 17 degrés." }, unreadSms = { 2 }, unreadMail = { 3 })
    private val mail = MailSkill(mailbox, { q -> if (q.contains("paul", true) || q == "paul@martin.fr") listOf("Paul Martin" to "paul@martin.fr") else emptyList() },
        dialog, device, { settings.ownerName }, llm, { true }, { "système" })
    private val a = Assistant(settings, device, llm, ToolRegistry(CoreTools.all(settings, device) + CoreTools.phase6(organizer)),
        nowMillis = { 0L }, skills = Skills(dialog, comms, reminders, media = mediaSkill, organizer = organizer, mail = mail))

    /** Un échange complet, comme dans le service : pause avant d'écouter, reprise ou démarrage après la réponse. */
    private suspend fun say(t: String): String {
        mediaSkill.beforeConversation()
        val r = a.handle(t)
        mediaSkill.afterConversation(stopped = r.stopAll)
        return r.speech
    }

    // ------------------------------------------------------------------ livres

    @Test fun `bibliotheque, ouverture d'un livre DAISY puis navigation par chapitres et pages`() = runTest {
        val list = say("quels livres ai-je ?")
        assertTrue(list, list.startsWith("Vous avez 2 éléments : 1 livre DAISY, 1 livre audio.") && list.contains("« Le Petit Prince »"))
        assertEquals("J'ouvre « Le Petit Prince ».", say("lis le livre Petit Prince"))
        assertEquals("uri://a01.mp3", player.tracks.first().uri)
        assertTrue(player.playing)
        assertEquals("Chapitre 1", say("chapitre suivant"))
        assertEquals(Pos(1, 0), player.pos)
        assertTrue("la lecture reprend après la commande", player.playing)
        assertEquals("Le dessin du serpent", say("section suivante"))
        assertEquals(Pos(1, 120_000), player.pos)
        assertEquals("Page 1.", say("va à la page un"))
        assertEquals(Pos(1, 60_000), player.pos)
        assertEquals("Chapitre 3 & fin", say("va au chapitre 3"))
        assertEquals(Pos(3, 0), player.pos)
        player.pos = Pos(3, 2_000)
        assertEquals("Chapitre 2", say("chapitre précédent"))
        assertEquals("J'avance de 5 minutes.", say("avance de 5 minutes"))
        assertEquals(Pos(2, 300_000), player.pos)
        assertEquals("« Le Petit Prince ». Chapitre 2. Page 2. 28 pour cent écoutés, il reste 25 minutes.", say("où j'en suis ?"))
        assertEquals("Je recule de 10 minutes.", say("recule de 10 minutes"))
        assertEquals(Pos(1, 0), player.pos)
        assertEquals("Ce livre a 3 chapitres.", say("va au chapitre 9"))
    }

    @Test fun `signets numerotes et reprise apres redemarrage`() = runTest {
        say("lis le livre petit prince")
        player.pos = Pos(2, 90_000)
        assertEquals("Signet 1 posé, Chapitre 2.", say("pose un signet"))
        player.pos = Pos(3, 10_000)
        assertEquals("Signet 2 posé, Chapitre 3 & fin.", say("pose un signet"))
        assertEquals("2 signets : signet 1, Chapitre 2 ; signet 2, Chapitre 3 & fin. Dites par exemple : va au signet 1.", say("liste des signets"))
        assertEquals("Signet 1.", say("va au signet 1"))
        assertEquals(Pos(2, 90_000), player.pos)
        assertEquals("Signet 2 supprimé.", say("supprime le signet 2"))
        mediaSkill.tick()
        // Nouvelle session de l'application : la position est retrouvée.
        val fresh = media()
        val a2 = Assistant(settings, device, llm, ToolRegistry(), nowMillis = { 0L }, skills = Skills(dialog, comms, reminders, media = fresh))
        val p2 = FakePlayer()
        player.stop()
        assertEquals("Je reprends « Le Petit Prince », Chapitre 2.", a2.handle("reprends mon livre").speech)
        fresh.afterConversation()
        assertEquals(Pos(2, 90_000), player.pos)
        assertTrue(p2.plays.isEmpty())
    }

    @Test fun `pause, reprise, stop et conversation pendant la lecture`() = runTest {
        say("lis le livre petit prince")
        assertTrue(player.playing)
        assertEquals("Pause.", say("pause"))
        assertFalse(player.playing)
        say("quelle heure est-il ?")
        assertFalse("une question ne relance pas une lecture en pause", player.playing)
        assertEquals("", say("reprends"))
        assertTrue(player.playing)
        say("quelle heure est-il ?")
        assertTrue("après une question, la lecture reprend", player.playing)
        say("stop")
        assertFalse("« stop » arrête la lecture", player.playing)
    }

    @Test fun `vitesse de lecture et minuterie de sommeil`() = runTest {
        say("lis le livre petit prince")
        assertEquals("Vitesse de lecture : 1,25.", say("lis plus vite"))
        assertEquals(1.25f, player.rate)
        assertEquals(1.0f, settings.speechRate)
        say("parle plus vite")
        assertEquals(1.25f, settings.speechRate)
        assertEquals("Vitesse normale.", say("vitesse normale"))
        assertEquals("D'accord, j'arrête la lecture dans 20 minutes.", say("arrête la lecture dans 20 minutes"))
        assertTrue(player.playing)
        now += 19 * 60_000; assertFalse(mediaSkill.tick())
        now += 2 * 60_000; assertTrue(mediaSkill.tick())
        assertFalse(player.playing)
    }

    @Test fun `livre fait de fichiers MP3 tries dans l'ordre naturel`() = runTest {
        assertEquals(listOf("Cendrillon", "Le Chat botte", "Barbe bleue"), audioBook.tracks.map { it.title })
        assertEquals("Contes de Perrault", audioBook.title)
        say("écoute le livre contes de Perrault")
        assertEquals("Le Chat botte", say("suivant"))
        assertEquals("« Contes de Perrault ». Le Chat botte. Fichier 2 sur 3.", say("où en suis-je ?"))
    }

    // ------------------------------------------------------------------ radio, podcasts, musique

    @Test fun `radios`() = runTest {
        assertEquals("France Inter.", say("mets France Inter"))
        assertTrue(player.live)
        assertEquals("https://icecast.radiofrance.fr/franceinter-midfi.mp3", player.plays.last())
        assertEquals("RTL.", say("mets RTL"))
        assertEquals("RTL est dans vos radios favorites.", say("ajoute cette radio à mes favoris"))
        assertEquals("Vous écoutez RTL.", say("quelle radio ?"))
        assertEquals("France Info.", say("mets France Info"))
        assertEquals("France Culture.", say("radio suivante"))
        assertEquals("France Culture.", say("mets la radio"))
        device.online = false
        assertEquals("Je n'ai pas internet : la radio ne peut pas marcher.", say("mets la radio"))
    }

    @Test fun `podcasts avec abonnement confirme et reprise`() = runTest {
        assertEquals("Je vous abonne au podcast « Affaires sensibles », de France Inter ? Répondez oui ou non.", say("abonne-moi au podcast affaires sensibles"))
        assertTrue(say("oui").startsWith("C'est fait."))
        assertEquals("Vous êtes abonné à 1 podcast : Affaires sensibles.", say("mes podcasts"))
        val r = say("écoute le podcast affaires sensibles")
        assertTrue(r, r.startsWith("« Affaires sensibles » : L'affaire du siècle, du "))
        assertEquals("https://pod.example/ep3.mp3", player.plays.last())
        assertTrue(say("épisode suivant").startsWith("« Affaires sensibles » : Le procès"))
    }

    @Test fun `musique locale ou application de musique`() = runTest {
        assertEquals("Je mets 2 morceaux : brassens.", say("mets de la musique de Brassens"))
        assertEquals("Les copains d'abord", say("morceau suivant"))
        assertEquals("Je demande à votre application de musique de jouer jazz manouche.", say("mets du jazz manouche"))
        assertEquals(listOf("search:jazz manouche"), externalCalls)
    }

    // ------------------------------------------------------------------ mémos

    @Test fun `memos vocaux`() = runTest {
        val r = a.handle("enregistre un mémo")
        assertEquals(FollowUp.RECORD_MEMO, r.followUp)
        assertEquals("Mémo enregistré : 12 secondes. Pour l'écouter, dites : écoute mes mémos.", mediaSkill.memoRecorded(12_000))
        assertEquals("Je n'ai rien entendu, le mémo n'est pas gardé.", mediaSkill.memoRecorded(300))
        val list = say("écoute mes mémos")
        assertTrue(list, list.startsWith("Vous avez 2 mémos, le plus récent d'abord. Mémo 1, "))
        assertEquals("memo2.m4a", player.plays.last())
        assertTrue(say("mémo suivant").startsWith("Mémo 2, "))
        assertTrue(say("supprime ce mémo").startsWith("Je supprime le mémo 2, "))
        assertEquals("Mémo supprimé.", say("oui"))
        assertEquals(listOf("memo2.m4a"), memoList.map { it.uri })
    }

    // ------------------------------------------------------------------ notes, liste, agenda, point du jour

    @Test fun `notes et liste de courses`() = runTest {
        assertEquals("J'ajoute du pain et du lait à la liste de courses.", say("ajoute du pain et du lait à la liste de courses"))
        assertEquals("J'ajoute des œufs à la liste de courses.", say("il faut acheter des œufs"))
        assertEquals("Sur la liste de courses, 3 articles : du pain, du lait et des œufs.", say("lis ma liste de courses"))
        assertEquals("J'enlève du pain de la liste.", say("enlève le pain de la liste"))
        assertEquals("Je vide la liste de courses ? Répondez oui ou non.", say("vide la liste"))
        say("oui")
        assertTrue(MediaState(store).shoppingList.isEmpty())
        assertEquals("C'est noté : Le code du portail est 1234. Pour relire, dites : lis mes notes.", say("note que le code du portail est 1234"))
        assertEquals("Note 1, aujourd'hui : Le code du portail est 1234.", say("lis mes notes"))
    }

    @Test fun `agenda, ajout de rendez-vous confirme et point du jour`() = runTest {
        assertEquals("Demain : à 14 heures 30, Dentiste, Valence.", say("qu'est-ce que j'ai demain ?"))
        val week = say("mes rendez-vous de la semaine")
        assertTrue(week, week.contains("Demain : à 14 heures 30, Dentiste") && week.contains("toute la journée, Anniversaire de Marie"))
        val q = say("ajoute un rendez-vous chez le kiné mardi à 10 heures")
        assertEquals("J'ajoute à l'agenda : Rendez-vous chez le kiné, le mardi 20 octobre à 10 heures ? Répondez oui ou non.", q)
        assertTrue(added.isEmpty())
        assertTrue(say("oui").startsWith("C'est dans l'agenda."))
        assertEquals("Rendez-vous chez le kiné" to LocalDateTime.of(2026, 10, 20, 10, 0), added.single())
        val b = say("fais-moi le point")
        assertTrue(b, b.contains("Aujourd'hui : ciel dégagé") && b.contains("Rien de prévu aujourd'hui.") && b.endsWith("Vous avez 2 SMS et 3 mails non lus."))
    }

    // ------------------------------------------------------------------ mails

    @Test fun `lire ses mails, ouvrir le premier, repondre avec confirmation`() = runTest {
        val l = say("lis mes mails")
        assertTrue(l, l.startsWith("Vous avez 2 nouveaux mails. 1 : de Mairie de Saint-Marcel, « Repas des aînés », "))
        val m = say("lis le premier")
        assertTrue(m, m.startsWith("Mail de Mairie de Saint-Marcel, objet « Repas des aînés ». Bonjour, le repas des aînés"))
        assertEquals(listOf("1"), read)
        assertEquals("Que voulez-vous répondre à Mairie de Saint-Marcel ?", say("réponds"))
        assertEquals("Je réponds à Mairie de Saint-Marcel : « Je viendrai avec plaisir » J'envoie ? Répondez oui ou non.", say("je viendrai avec plaisir"))
        assertTrue(sentMails.isEmpty())
        assertEquals("Réponse envoyée à Mairie de Saint-Marcel.", say("oui"))
        val (to, subject, body) = sentMails.single()
        assertEquals("mairie@stmarcel.fr", to); assertEquals("Re: Repas des aînés", subject)
        assertTrue(body.startsWith("Je viendrai avec plaisir.\n\nJean"))
    }

    @Test fun `mail d'arnaque signale et mail a un contact`() = runTest {
        say("lis mes mails")
        val m = say("lis le deuxième")
        assertTrue(m, m.startsWith("Attention, ce message ressemble à une arnaque"))
        assertEquals("J'envoie un mail à Paul Martin : « J'arrive samedi » J'envoie ? Répondez oui ou non.", say("écris un mail à Paul pour lui dire que j'arrive samedi"))
        say("oui")
        assertEquals(Triple("paul@martin.fr", "Message de Jean", "J'arrive samedi.\n\nJean\n(message dicté à mon assistant vocal)"), sentMails.single())
        assertTrue(say("écris un mail à Gaston").startsWith("Je ne trouve pas d'adresse mail"))
    }

    // ------------------------------------------------------------------ routeur et analyse DAISY

    @Test fun `routeur, pas de confusion avec les fonctions existantes`() {
        val r = LocalRouter()
        fun i(t: String): Intent? = r.route(t)?.intent
        assertEquals(ListReminders, i("qu'est-ce que j'ai comme rappels ?"))
        assertTrue(i("minuteur de 10 minutes pour les pâtes") is SetTimer)
        assertTrue(i("rappelle-moi d'écouter la radio demain à 8 heures") is CreateReminder)
        assertTrue(i("retiens que Pierre Dupont c'est mon fils") is SetAlias)
        assertEquals(ReadMessages(false), i("lis mes messages"))
        assertEquals(News(null), i("les infos"))
        assertEquals(NewsMore, i("la suite"))
        assertEquals(NextItem, i("suivant"))
        assertEquals(PauseMedia, i("pause"))
        assertTrue(i("il faut que j'appelle le médecin") !is fr.assistantvocal.core.router.AddToList)
        assertEquals(Speaker(true), i("mets le haut-parleur"))
        assertEquals(fr.assistantvocal.core.router.SleepTimer(45), i("minuterie de sommeil 45 minutes"))
        assertEquals(fr.assistantvocal.core.router.Jump(-30_000), i("reviens en arrière un peu"))
        assertEquals(fr.assistantvocal.core.router.PlayRadio("france bleu drôme ardèche"), i("mets France Bleu Drôme Ardèche"))
        assertEquals(fr.assistantvocal.core.router.Agenda("qu'est-ce que j'ai de prévu demain"), i("qu'est-ce que j'ai de prévu demain ?"))
    }

    @Test fun `analyse DAISY 2 et 3, horloges SMIL, titres`() {
        val b = DaisyFixtures.book()
        assertEquals("Le Petit Prince", b.title)
        assertEquals(listOf("uri://a01.mp3", "uri://a02.mp3", "uri://a03.mp3", "uri://a04.mp3"), b.tracks.map { it.uri })
        assertEquals(2_105_000L, b.totalMs)
        assertEquals(listOf("1", "2"), b.pages.map { it.label })
        assertEquals(2, b.headings.first { it.title == "Le dessin du serpent" }.level)

        val ncx = """<ncx><docTitle><text>Vingt mille lieues</text></docTitle><navMap>
            <navPoint id="n1" playOrder="1"><navLabel><text>Première partie</text></navLabel><content src="p1.smil#a"/>
              <navPoint id="n2" playOrder="2"><navLabel><text>Un écueil fuyant</text></navLabel><content src="p1.smil#b"/></navPoint>
            </navPoint>
            <navPoint id="n3" playOrder="3"><navLabel><text>Deuxième partie</text></navLabel><content src="p2.smil#c"/></navPoint>
            </navMap><pageList><pageTarget id="pg1" type="normal"><navLabel><text>12</text></navLabel><content src="p1.smil#b"/></pageTarget></pageList></ncx>"""
        val smil1 = """<smil><body><seq><par id="a"><audio src="audio/p1.mp3" clipBegin="0:00:00.000" clipEnd="0:00:10.500"/></par>
            <par id="b"><text id="tb"/><audio src="audio/p1.mp3" clipBegin="0:00:10.500" clipEnd="0:02:00"/></par></seq></body></smil>"""
        val smil2 = """<smil><body><seq><par id="c"><audio src="p2.mp3" clipBegin="12.5s" clipEnd="3000ms"/></par></seq></body></smil>"""
        val files = mapOf("navigation.ncx" to ncx, "p1.smil" to smil1, "p2.smil" to smil2)
        val d3 = Daisy.parse("d3", "vingt_mille", BookFiles { files[it] }, { it }, "navigation.ncx")!!
        assertEquals("Vingt mille lieues", d3.title)
        assertEquals(listOf("Première partie" to 1, "Un écueil fuyant" to 2, "Deuxième partie" to 1), d3.headings.map { it.title to it.level })
        assertEquals(10_500L, d3.headings[1].ms)
        assertEquals(12_500L, d3.headings[2].ms)
        assertEquals(listOf("p1.mp3", "p2.mp3"), d3.tracks.map { it.uri })
        assertEquals("12", d3.pages.single().label)
        assertEquals(3_723_500L, Daisy.clockMs("npt=1:02:03.5"))
        assertEquals(90_000L, Daisy.clockMs("1.5min"))
        assertNull(Daisy.clockMs("bizarre"))
        assertNull(Daisy.parse("x", "x", BookFiles { null }, { it }, "ncc.html"))

        assertEquals("Le Petit Prince", TitleMatch.best("petit prince", listOf("Les Misérables", "Le Petit Prince", "Le Prince de Machiavel")) { it })
        assertEquals("Les Misérables", TitleMatch.best("les miserable", listOf("Les Misérables", "Le Petit Prince")) { it })
        assertNull(TitleMatch.best("harry potter", listOf("Les Misérables", "Le Petit Prince")) { it })
    }
}

class ReplyTopicTest {
    @Test fun `apres un mail, un SMS annonce reprend la main pour repondre`() = runTest {
        val store = InMemoryStore()
        val device = FakeDevice()
        val phone = FakePhone()
        val dialog = Dialog { 0L }
        val people = PeopleStore(store)
        val comms = CommunicationSkill(ContactDirectory(phone, people), people, phone, phone, phone, phone, phone, store, device, dialog)
        dialog.lastTopic = "mail"
        comms.announceIncomingSms(fr.assistantvocal.core.phone.SmsMessage(9, "0612345678", null, "Tu viens ?", device.time), readFull = true)
        assertEquals("sms", dialog.lastTopic)
    }
}
