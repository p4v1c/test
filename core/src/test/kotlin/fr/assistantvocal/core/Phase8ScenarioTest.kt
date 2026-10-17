package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.FollowUp
import fr.assistantvocal.core.media.Book
import fr.assistantvocal.core.media.Episode
import fr.assistantvocal.core.media.LibrarySource
import fr.assistantvocal.core.media.MediaState
import fr.assistantvocal.core.media.Memo
import fr.assistantvocal.core.media.MemoStore
import fr.assistantvocal.core.media.MusicSource
import fr.assistantvocal.core.media.PodcastDirectory
import fr.assistantvocal.core.media.PodcastDownloader
import fr.assistantvocal.core.media.PodcastShow
import fr.assistantvocal.core.media.Pos
import fr.assistantvocal.core.media.RadioSearch
import fr.assistantvocal.core.media.Shelf
import fr.assistantvocal.core.media.Station
import fr.assistantvocal.core.media.TextBooks
import fr.assistantvocal.core.media.Track
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.skills.CommunicationSkill
import fr.assistantvocal.core.skills.MediaSkill
import fr.assistantvocal.core.skills.ReminderSkill
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun zip(vararg files: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    return out.toByteArray()
}

class TextBooksTest {
    @Test fun `texte brut, paragraphes et chapitres`() {
        val doc = TextBooks.parseTxt("CHAPITRE PREMIER\n\nIl était une fois\nun vieux marin.\n\nChapitre 2 : la tempête\n\nLe vent souffla toute la nuit.")
        assertEquals(listOf("CHAPITRE PREMIER", "Il était une fois un vieux marin.", "Chapitre 2 : la tempête", "Le vent souffla toute la nuit."), doc.blocks.map { it.text })
        assertEquals(listOf(1, null, 1, null), doc.blocks.map { it.heading })
    }

    @Test fun `HTML avec titres et sauts de page`() {
        val doc = TextBooks.parseHtml("""<html><head><title>Contes</title><style>p{}</style></head><body>
            <h1>Le Chat botté</h1><p>Un meunier ne laissa pour <b>tous</b> biens&nbsp;à ses trois enfants…</p>
            <span epub:type="pagebreak" title="12"/><p>Le chat dit à son maître.</p><h2>Suite</h2><p>Fin.</p></body></html>""")
        assertEquals("Contes", doc.title)
        assertEquals(listOf("Le Chat botté", "Un meunier ne laissa pour tous biens à ses trois enfants…", "Le chat dit à son maître.", "Suite", "Fin."), doc.blocks.map { it.text })
        assertEquals(listOf(1, null, null, 2, null), doc.blocks.map { it.heading })
        assertEquals("12", doc.blocks[2].page)
    }

    @Test fun `Word DOCX avec styles de titre`() {
        val xml = """<w:document><w:body>
            <w:p><w:pPr><w:pStyle w:val="Titre1"/></w:pPr><w:r><w:t>Mes souvenirs</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Je suis né </w:t></w:r><w:r><w:t>à Valence &amp; j'y ai grandi.</w:t></w:r></w:p>
            <w:p></w:p>
            <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>L'école</w:t></w:r></w:p></w:body></w:document>"""
        val doc = TextBooks.parseDocx(zip("word/document.xml" to xml, "docProps/core.xml" to "<cp><dc:title>Souvenirs de Jean</dc:title></cp>"))
        assertEquals("Souvenirs de Jean", doc.title)
        assertEquals(listOf("Mes souvenirs" to 1, "Je suis né à Valence & j'y ai grandi." to null, "L'école" to 2), doc.blocks.map { it.text to it.heading })
    }

    @Test fun `EPUB dans l'ordre de lecture, et refus des EPUB proteges`() {
        val opf = """<package><metadata><dc:title>Le Tour du monde</dc:title></metadata><manifest>
            <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
            </manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        val epub = zip(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to opf,
            "OEBPS/Text/ch1.xhtml" to "<html><body><h1>Chapitre 1</h1><p>Phileas Fogg habitait Londres.</p></body></html>",
            "OEBPS/Text/ch2.xhtml" to "<html><body><h1>Chapitre 2</h1><p>Passepartout arriva.</p></body></html>",
        )
        val doc = TextBooks.parseEpub(epub)
        assertEquals("Le Tour du monde", doc.title)
        assertEquals(listOf("Chapitre 1", "Phileas Fogg habitait Londres.", "Chapitre 2", "Passepartout arriva."), doc.blocks.map { it.text })
        val drm = zip("META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"c.opf\"/></rootfiles></container>",
            "META-INF/encryption.xml" to "<encryption/>", "c.opf" to opf)
        assertTrue(TextBooks.parseEpub(drm).blocks.isEmpty())
    }

    @Test fun `decoupage en segments entre deux phrases`() {
        val sentence = "Ceci est une phrase de longueur moyenne pour le test. "
        val parts = TextBooks.split(sentence.repeat(20).trim())
        assertTrue(parts.all { it.length <= TextBooks.SEGMENT })
        assertTrue(parts.all { it.endsWith(".") })
        assertEquals(sentence.repeat(20).trim(), parts.joinToString(" "))
        val book = TextBooks.toBook("t1", "lettre_banque.pdf", TextBooks.Doc(null, listOf(
            TextBooks.Block("Relevé", 1, "1"), TextBooks.Block(sentence.repeat(10).trim(), null), TextBooks.Block("Page deux", null, "2"))))
        assertEquals("lettre banque", book.title)
        assertEquals(listOf("1" to 0, "2" to book.tracks.lastIndex), book.pages.map { it.label to it.track })
        assertEquals(Shelf.TEXT, book.shelf)
    }
}

class Phase8ScenarioTest {
    private val store = InMemoryStore()
    private val settings = AssistantSettings(store).apply { name = "Hector" }
    private val device = FakeDevice()
    private val phone = FakePhone()
    private val dialog = Dialog { 0L }
    private val people = PeopleStore(store)
    private val comms = CommunicationSkill(ContactDirectory(phone, people), people, phone, phone, phone, phone, phone, store, device, dialog)
    private val reminders = ReminderSkill(ReminderStore(store), MedicationLog(store), phone, store, device, dialog)
    private val audio = FakePlayer()
    private val voice = FakePlayer()

    private val longText = (1..30).joinToString("\n\n") { i ->
        (if (i % 10 == 1) "Chapitre ${i / 10 + 1}\n\n" else "") + "Paragraphe $i. " + "Une phrase pour remplir le texte de cette lettre. ".repeat(6)
    }
    private val textStub = Book("text:banque", "Lettre de la banque", Shelf.TEXT, emptyList())
    private var loads = 0
    private val library = object : LibrarySource {
        override suspend fun scan() = listOf(textStub)
        override suspend fun load(book: Book): Book {
            loads++
            val doc = TextBooks.parseTxt(longText)
            val withPages = doc.copy(blocks = doc.blocks.mapIndexed { i, b -> if (i == 15) b.copy(page = "2") else if (i == 0) b.copy(page = "1") else b })
            return TextBooks.toBook(book.id, "Lettre de la banque", withPages).copy(title = book.title)
        }
    }
    private var feedEpisodes = listOf(
        Episode("Épisode 5", "https://pod/e5.mp3", 5_000, 1_000_000), Episode("Épisode 4", "https://pod/e4.mp3", 4_000, null),
        Episode("Épisode 3", "https://pod/e3.mp3", 3_000, null), Episode("Épisode 2", "https://pod/e2.mp3", 2_000, null),
    )
    private val podcasts = object : PodcastDirectory {
        override suspend fun search(term: String) = listOf(PodcastShow("Affaires sensibles", "France Inter", "https://pod/feed.xml"))
        override suspend fun episodes(feedUrl: String) = feedEpisodes
    }
    private val files = mutableSetOf<String>()
    private var wifi = true
    private val downloader = object : PodcastDownloader {
        override fun unmetered() = wifi
        override suspend fun download(url: String, fileName: String): String { files += "file://$fileName"; return "file://$fileName" }
        override fun delete(localUri: String) { files -= localUri }
        override fun exists(localUri: String) = localUri in files
    }
    private val noRadio = object : RadioSearch { override suspend fun search(name: String) = emptyList<Station>() }
    private val noMusic = object : MusicSource {
        override suspend fun local(query: String?) = emptyList<Track>()
        override fun playExternal(query: String) = false
        override fun controlExternal(action: String) = false
    }
    private val noMemos = object : MemoStore { override fun list() = emptyList<Memo>(); override fun delete(memo: Memo) = false }
    private val media = MediaSkill(audio, library, MediaState(store), noRadio, podcasts, noMusic, noMemos, dialog, device,
        textPlayer = voice, downloader = downloader)
    private val a = Assistant(settings, device, ScriptedLlm(), ToolRegistry(), nowMillis = { 0L }, skills = Skills(dialog, comms, reminders, media = media))

    private suspend fun say(t: String): String {
        media.beforeConversation()
        val r = a.handle(t)
        media.afterConversation(r.stopAll)
        return r.speech
    }

    // ------------------------------------------------------------------ livres texte

    @Test fun `lire un document texte a voix haute avec chapitres, pages, sauts et signets`() = runTest {
        assertEquals("J'ouvre « Lettre de la banque », Chapitre 1.", say("lis le document lettre de la banque"))
        assertEquals(1, loads)
        assertTrue("la synthèse vocale lit, pas le lecteur audio", voice.playing && !audio.playing)
        assertEquals("text:0", voice.plays.single())
        assertEquals("Chapitre 2", say("chapitre suivant"))
        val ch2 = voice.pos!!.track
        assertTrue(voice.tracks[ch2].title.startsWith("Chapitre 2"))
        assertEquals("Page 2.", say("va à la page 2"))
        assertEquals("J'avance d'environ 5 minutes.", say("avance de 5 minutes"))
        val where = say("où j'en suis ?")
        assertTrue(where, Regex("^« Lettre de la banque ». Chapitre 3. Page 2. \\d+ pour cent lus.$").matches(where))
        assertEquals("Signet 1 posé, Chapitre 3.", say("pose un signet"))
        assertEquals("Vitesse de lecture : 1,25.", say("lis plus vite"))
        assertEquals(1.25f, voice.rate)
        say("quelle heure est-il ?")
        assertTrue("la lecture reprend après une question", voice.playing)
        say("stop")
        assertFalse(voice.playing)
    }

    @Test fun `passer d'un livre texte a la radio arrete la synthese`() = runTest {
        say("lis le document lettre de la banque")
        say("mets France Inter")
        assertFalse(voice.playing)
        assertTrue(audio.playing && audio.live)
    }

    // ------------------------------------------------------------------ podcasts téléchargés

    @Test fun `telechargement en wifi, ecoute sans internet, rotation des episodes`() = runTest {
        say("abonne-moi au podcast affaires sensibles"); say("oui")
        val r = a.handle("télécharge les podcasts")
        assertEquals(FollowUp.DOWNLOAD_PODCASTS, r.followUp)
        assertEquals(3, media.syncDownloads(force = true))
        assertEquals(3, files.size)
        assertEquals("Téléchargement terminé : 3 nouveaux épisodes, disponibles même sans internet.", media.downloadDone(3))
        assertEquals("3 épisodes téléchargés : « Affaires sensibles », 3 épisodes.", say("mes podcasts téléchargés"))

        device.online = false
        val off = say("écoute le podcast affaires sensibles")
        assertTrue(off, off.startsWith("Épisode téléchargé. « Affaires sensibles » : Épisode 5"))
        assertTrue(audio.plays.last().startsWith("file://"))
        audio.pos = Pos(0, 120_000); media.tick()

        // Le lendemain, un nouvel épisode : le plus ancien part, l'épisode commencé reste.
        device.online = true
        say("épisode suivant") // on commence l'épisode 4
        audio.pos = Pos(0, 60_000); media.tick()
        feedEpisodes = listOf(Episode("Épisode 7", "https://pod/e7.mp3", 7_000, null), Episode("Épisode 6", "https://pod/e6.mp3", 6_000, null)) + feedEpisodes
        assertEquals(2, media.syncDownloads())
        val kept = MediaState(store).downloads.map { it.title }.toSet()
        assertEquals(setOf("Épisode 7", "Épisode 6", "Épisode 5", "Épisode 4"), kept)
        assertEquals(4, files.size)
    }

    @Test fun `pas de telechargement automatique hors wifi`() = runTest {
        say("abonne-moi au podcast affaires sensibles"); say("oui")
        wifi = false
        assertEquals("Vous n'êtes pas en wifi : pour ne pas consommer vos données mobiles, je téléchargerai dès que le téléphone sera en wifi.",
            say("télécharge les podcasts"))
        assertEquals(0, media.syncDownloads())
        assertTrue(files.isEmpty())
        device.online = false
        assertTrue(say("écoute le podcast affaires sensibles").startsWith("Je n'ai pas internet, et aucun épisode n'est téléchargé."))
    }

    @Test fun `suppression des episodes telecharges, et desabonnement`() = runTest {
        say("abonne-moi au podcast affaires sensibles"); say("oui")
        media.syncDownloads(force = true)
        assertEquals("Je supprime les 3 épisodes téléchargés ? Répondez oui ou non.", say("supprime les épisodes téléchargés"))
        assertTrue(say("oui").startsWith("Les épisodes téléchargés sont supprimés."))
        assertTrue(files.isEmpty())
        media.syncDownloads(force = true)
        say("désabonne-moi du podcast affaires sensibles"); say("oui")
        media.syncDownloads(force = true)
        assertTrue("les fichiers d'un podcast quitté sont effacés", files.isEmpty())
    }

    @Test fun `routeur de la phase 8`() {
        val r = fr.assistantvocal.core.router.LocalRouter()
        assertEquals(fr.assistantvocal.core.router.DownloadPodcasts(null), r.route("télécharge les podcasts")?.intent)
        assertEquals(fr.assistantvocal.core.router.DownloadPodcasts("affaires sensibles"), r.route("télécharge les derniers épisodes du podcast affaires sensibles")?.intent)
        assertEquals(fr.assistantvocal.core.router.ListDownloads, r.route("quels épisodes sont téléchargés ?")?.intent)
        assertEquals(fr.assistantvocal.core.router.OpenBook("ma lettre"), r.route("lis le pdf ma lettre")?.intent)
        assertEquals(fr.assistantvocal.core.router.OpenBook("les misérables"), r.route("lis le livre texte les misérables")?.intent)
    }
}

class PdfPagesTest {
    @Test fun `pages de PDF recollees en paragraphes`() {
        val doc = TextBooks.fromPages(listOf(
            "Madame, Monsieur,\nNous vous informons que votre prélè-\nvement du mois aura lieu le 5.\nCordialement.\n",
            "",
            "Chapitre 2\nSuite du texte sur\nplusieurs lignes.",
        ))
        assertEquals(listOf(
            "Madame, Monsieur, Nous vous informons que votre prélèvement du mois aura lieu le 5." to "1",
            "Cordialement." to null,
            "Page 2 sans texte." to "2",
            "Chapitre 2 Suite du texte sur plusieurs lignes." to "3",
        ), doc.blocks.map { it.text to it.page })
    }
}
