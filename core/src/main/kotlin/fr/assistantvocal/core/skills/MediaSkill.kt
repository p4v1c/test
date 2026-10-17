package fr.assistantvocal.core.skills

import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.assistant.FollowUp
import fr.assistantvocal.core.media.AudioPlayer
import fr.assistantvocal.core.media.Book
import fr.assistantvocal.core.media.Bookmark
import fr.assistantvocal.core.media.Episode
import fr.assistantvocal.core.media.Heading
import fr.assistantvocal.core.media.LibrarySource
import fr.assistantvocal.core.media.MediaState
import fr.assistantvocal.core.media.Memo
import fr.assistantvocal.core.media.MemoStore
import fr.assistantvocal.core.media.MusicSource
import fr.assistantvocal.core.media.PodcastDirectory
import fr.assistantvocal.core.media.PodcastDownloader
import fr.assistantvocal.core.media.PodcastSub
import fr.assistantvocal.core.media.Pos
import fr.assistantvocal.core.media.RadioCatalog
import fr.assistantvocal.core.media.RadioSearch
import fr.assistantvocal.core.media.Shelf
import fr.assistantvocal.core.media.Station
import fr.assistantvocal.core.media.TitleMatch
import fr.assistantvocal.core.media.Track
import fr.assistantvocal.core.router.AddBookmark
import fr.assistantvocal.core.router.AddFavoriteStation
import fr.assistantvocal.core.router.DeleteBookmark
import fr.assistantvocal.core.router.DeleteMemo
import fr.assistantvocal.core.router.GoToBookmark
import fr.assistantvocal.core.router.GoToEdge
import fr.assistantvocal.core.router.GoToHeading
import fr.assistantvocal.core.router.GoToPage
import fr.assistantvocal.core.router.Jump
import fr.assistantvocal.core.router.ListBookmarks
import fr.assistantvocal.core.router.ListBooks
import fr.assistantvocal.core.router.ListPodcasts
import fr.assistantvocal.core.router.ListStations
import fr.assistantvocal.core.router.MediaIntent
import fr.assistantvocal.core.router.NextHeading
import fr.assistantvocal.core.router.NextItem
import fr.assistantvocal.core.router.OpenBook
import fr.assistantvocal.core.router.PauseMedia
import fr.assistantvocal.core.router.PlayMemos
import fr.assistantvocal.core.router.PlayMusic
import fr.assistantvocal.core.router.PlayPodcast
import fr.assistantvocal.core.router.PlayRadio
import fr.assistantvocal.core.router.PlaybackSpeed
import fr.assistantvocal.core.router.PreviousHeading
import fr.assistantvocal.core.router.PreviousItem
import fr.assistantvocal.core.router.RecordMemo
import fr.assistantvocal.core.router.ResumeMedia
import fr.assistantvocal.core.router.SleepTimer
import fr.assistantvocal.core.router.SubscribePodcast
import fr.assistantvocal.core.router.UnsubscribePodcast
import fr.assistantvocal.core.router.WhereInBook
import fr.assistantvocal.core.text.FrenchText
import fr.assistantvocal.core.time.FrenchTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Lecteur à la voix, sur le modèle du Victor Reader Stream : livres DAISY et audio avec reprise, chapitres, pages,
 * sauts dans le temps, signets numérotés, « où j'en suis », vitesse, minuterie de sommeil ; radio, podcasts,
 * musique, mémos vocaux.
 *
 * L'écoute de l'assistant met la lecture en pause ; elle reprend après la réponse, sauf si la commande l'a arrêtée.
 */
class MediaSkill(
    private val audioPlayer: AudioPlayer,
    private val library: LibrarySource,
    val state: MediaState,
    private val radios: RadioSearch,
    private val podcasts: PodcastDirectory,
    private val music: MusicSource,
    private val memos: MemoStore,
    private val dialog: Dialog,
    private val device: DeviceStatus,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Lecture des livres en texte par la synthèse vocale (étagère Texte). */
    private val textPlayer: AudioPlayer? = null,
    /** Téléchargement des podcasts pour l'écoute sans internet. */
    private val downloader: PodcastDownloader? = null,
) {
    enum class Kind { BOOK, TEXT, RADIO, PODCAST, MUSIC, MEMOS }

    private data class Session(
        val kind: Kind,
        val book: Book? = null,
        val station: Station? = null,
        val show: String? = null,
        val feed: String? = null,
        val episodes: List<Episode> = emptyList(),
        val episodeIndex: Int = 0,
        val memoList: List<Memo> = emptyList(),
    )

    private enum class After { KEEP, PLAY, PAUSE }

    private var current: Session? = null
    private var books: List<Book>? = null
    private var pendingStart: (() -> Unit)? = null
    private var after = After.KEEP
    private var wasPlaying = false
    private var sleepAtMs: Long? = null

    /** Lecteur de la séance en cours : synthèse vocale pour un livre texte, audio sinon. */
    private val player: AudioPlayer get() = if (current?.kind == Kind.TEXT) textPlayer ?: audioPlayer else audioPlayer

    init {
        audioPlayer.onFinished = { onFinished() }
        textPlayer?.onFinished = { onFinished() }
    }

    // ------------------------------------------------------------------ cycle avec la conversation

    /** Avant d'écouter la personne : la lecture se met en pause. */
    fun beforeConversation() {
        after = After.KEEP
        wasPlaying = player.isPlaying()
        if (wasPlaying) { player.pause(); savePosition() }
    }

    /** Après la réponse : démarrage demandé, reprise, ou pause. [stopped] : la personne a dit « stop ». */
    fun afterConversation(stopped: Boolean = false) {
        val start = pendingStart
        pendingStart = null
        when {
            stopped -> Unit
            start != null -> start()
            after == After.PLAY -> player.resume()
            after == After.KEEP && wasPlaying -> player.resume()
        }
        after = After.KEEP
        wasPlaying = false
    }

    /** Une lecture est en cours ou vient d'être interrompue par la conversation. */
    fun isActive(): Boolean = current != null && (player.isPlaying() || wasPlaying)

    /** Appelé régulièrement : mémorise la position, applique la minuterie de sommeil. Vrai si la lecture vient d'être arrêtée. */
    fun tick(): Boolean {
        savePosition()
        val at = sleepAtMs ?: return false
        if (nowMs() < at) return false
        sleepAtMs = null
        if (player.isPlaying()) { player.pause(); savePosition(); return true }
        return false
    }

    private fun savePosition() {
        val s = current ?: return
        val book = s.book ?: return
        if (s.kind == Kind.RADIO) return
        val p = player.position() ?: return
        state.savePosition(book.id, p)
        player.durationOf(p.track)?.takeIf { it > 0 && book.tracks.getOrNull(p.track)?.durationMs == null }?.let { state.setDuration(book.id, p.track, it) }
    }

    private fun onFinished() {
        val s = current ?: return
        if ((s.kind == Kind.BOOK || s.kind == Kind.TEXT) && s.book != null) state.savePosition(s.book.id, Pos(0, 0)) // la prochaine fois, depuis le début
    }

    // ------------------------------------------------------------------ commandes

    suspend fun handle(intent: MediaIntent): AssistantReply = when (intent) {
        ListBooks -> listBooks()
        is OpenBook -> openBook(intent.title)
        ResumeMedia -> resume()
        PauseMedia -> pause()
        NextItem -> step(+1)
        PreviousItem -> step(-1)
        is NextHeading -> heading(+1, intent.chapter)
        is PreviousHeading -> heading(-1, intent.chapter)
        is GoToHeading -> goToHeading(intent.number)
        is GoToPage -> goToPage(intent.label)
        is GoToEdge -> goToEdge(intent.end, intent.chapter)
        is Jump -> jump(intent.deltaMs)
        WhereInBook -> AssistantReply(whereAmI())
        AddBookmark -> addBookmark()
        is GoToBookmark -> goToBookmark(intent.number)
        ListBookmarks -> listBookmarks()
        is DeleteBookmark -> deleteBookmark(intent.number)
        is PlaybackSpeed -> speed(intent.faster)
        is SleepTimer -> sleep(intent.minutes)
        is PlayRadio -> radio(intent.name)
        AddFavoriteStation -> addFavorite()
        ListStations -> listStations()
        is SubscribePodcast -> subscribe(intent.name)
        is UnsubscribePodcast -> unsubscribe(intent.name)
        ListPodcasts -> listPodcasts()
        is PlayPodcast -> playPodcast(intent.name)
        is PlayMusic -> playMusic(intent.query)
        is fr.assistantvocal.core.router.DownloadPodcasts -> askDownload(intent.name)
        fr.assistantvocal.core.router.ListDownloads -> listDownloads()
        fr.assistantvocal.core.router.DeleteDownloads -> deleteDownloads()
        RecordMemo -> AssistantReply("Je vous écoute pour votre mémo. Parlez après le bip, je m'arrête quand vous vous taisez.", followUp = FollowUp.RECORD_MEMO)
        PlayMemos -> playMemos()
        DeleteMemo -> deleteMemo()
    }

    private fun err(text: String) = AssistantReply(text, Earcon.ERROR)

    private fun start(session: Session, start: Pos, live: Boolean = false) {
        savePosition()
        audioPlayer.stop(); textPlayer?.stop()
        current = session
        state.lastKind = session.kind.name.lowercase()
        val speed = if (session.kind in setOf(Kind.BOOK, Kind.TEXT, Kind.PODCAST, Kind.MEMOS)) state.bookSpeed else 1f
        val tracks = session.book?.tracks ?: listOf(Track(session.station!!.url, session.station.name))
        pendingStart = { player.play(tracks, start, speed, live) }
        after = After.KEEP
    }

    // ------------------------------------------------------------------ bibliothèque

    suspend fun allBooks(refresh: Boolean = false): List<Book> {
        if (refresh || books == null) books = runCatching { library.scan() }.getOrDefault(emptyList())
        return books!!
    }

    private val noBooks = "Je ne trouve aucun livre. Un proche doit copier les livres sur le téléphone, par exemple le contenu " +
        "de la carte du Victor, puis choisir ce dossier dans les réglages."

    private suspend fun listBooks(): AssistantReply {
        val all = allBooks(refresh = true)
        if (all.isEmpty()) return err(noBooks)
        val byShelf = all.groupBy { it.shelf }.entries.joinToString(", ") { (s, l) -> "${l.size} ${if (l.size == 1) s.label.replace("livres", "livre") else s.label}" }
        val recent = state.lastBookId?.let { id -> all.firstOrNull { it.id == id } }
        val titles = all.filter { it.shelf != Shelf.NOTES }.sortedBy { it.title.lowercase() }
        val list = titles.take(10).joinToString(", ") { "« ${it.title} »" } + if (titles.size > 10) ", et ${titles.size - 10} autres" else ""
        return AssistantReply(
            "Vous avez ${all.size} ${if (all.size == 1) "élément" else "éléments"} : $byShelf. " +
                (recent?.let { "Dernier livre écouté : « ${it.title} ». " } ?: "") +
                "$list. Pour en écouter un, dites : lis le livre, puis le titre.",
        )
    }

    private suspend fun openBook(title: String?): AssistantReply {
        val all = allBooks()
        if (all.isEmpty()) return err(noBooks)
        val found = if (title == null) {
            state.lastBookId?.let { id -> all.firstOrNull { it.id == id } }
                ?: return err("Je ne sais pas quel livre reprendre. Dites : lis le livre, puis le titre, ou : quels livres ai-je ?")
        } else {
            TitleMatch.best(title, all.filter { it.shelf != Shelf.MUSIC && it.shelf != Shelf.NOTES }) { it.title }
                ?: TitleMatch.best(title, allBooks(refresh = true)) { it.title }
                ?: return err("Je ne trouve pas de livre « $title ». Dites : quels livres ai-je, pour la liste.")
        }
        val full = runCatching { library.load(found) }.getOrNull() ?: return err("Je n'arrive pas à ouvrir « ${found.title} ».")
        if (full.tracks.isEmpty()) return err("« ${found.title} » ne contient rien que je puisse lire.")
        return startBook(full)
    }

    private fun startBook(book: Book): AssistantReply {
        state.lastBookId = book.id
        val saved = state.position(book.id)
        val pos = saved ?: Pos(0, 0)
        start(Session(if (book.shelf == Shelf.TEXT) Kind.TEXT else Kind.BOOK, book), pos)
        val where = headingAt(book, pos)?.takeIf { FrenchText.key(it.title) != FrenchText.key(book.title) }?.let { ", ${it.title}" } ?: ""
        return AssistantReply(if (saved != null && (saved.track > 0 || saved.ms > 5_000)) "Je reprends « ${book.title} »$where." else "J'ouvre « ${book.title} »$where.")
    }

    private suspend fun resume(): AssistantReply {
        val s = current
        if (s != null && player.position() != null) {
            after = After.PLAY
            return AssistantReply("")
        }
        return when (state.lastKind) {
            "radio" -> radio(null)
            "podcast" -> playPodcast(null)
            "book", null -> if (state.lastBookId != null) openBook(null)
                else if (music.controlExternal("play")) AssistantReply("") else err("Il n'y a rien à reprendre. Dites par exemple : quels livres ai-je, ou : mets la radio.")
            else -> if (music.controlExternal("play")) AssistantReply("") else err("Il n'y a rien à reprendre.")
        }
    }

    private fun pause(): AssistantReply {
        if (current != null) {
            after = After.PAUSE
            wasPlaying = false
            player.pause(); savePosition()
            return AssistantReply("Pause.")
        }
        return if (music.controlExternal("pause")) AssistantReply("") else AssistantReply("Rien n'est en cours de lecture.")
    }

    // ------------------------------------------------------------------ positions et chapitres

    private fun dur(book: Book, track: Int): Long? =
        book.tracks.getOrNull(track)?.durationMs ?: state.duration(book.id, track) ?: player.durationOf(track).takeIf { (current?.book?.id) == book.id }

    private fun pos(): Pos = player.position() ?: current?.book?.let { state.position(it.id) } ?: Pos(0, 0)

    private fun cmp(a: Pos, b: Pos) = if (a.track != b.track) a.track.compareTo(b.track) else a.ms.compareTo(b.ms)
    private fun Heading.pos() = Pos(track, ms)

    /** Niveau des « chapitres » : le niveau le plus haut qui contient au moins 3 titres. */
    private fun chapters(book: Book): List<Heading> {
        // Le premier titre qui répète le titre du livre n'est pas un chapitre.
        val hs = book.headings.dropWhile { FrenchText.key(it.title) == FrenchText.key(book.title) }
        val levels = hs.map { it.level }.distinct().sorted()
        val level = levels.firstOrNull { l -> hs.count { it.level == l } >= 3 } ?: levels.lastOrNull() ?: return emptyList()
        return hs.filter { it.level <= level }
    }

    private fun headingAt(book: Book, p: Pos, list: List<Heading> = book.headings): Heading? = list.lastOrNull { cmp(it.pos(), p) <= 0 }

    private fun go(p: Pos, speech: String): AssistantReply {
        player.seek(p)
        current?.book?.let { state.savePosition(it.id, p) }
        after = After.PLAY
        return AssistantReply(speech)
    }

    private fun bookSession(): Session? = current?.takeIf { it.book != null && it.kind != Kind.RADIO }

    private val noBook = "Aucun livre n'est ouvert. Dites : reprends mon livre, ou : lis le livre, puis le titre."

    private fun heading(dir: Int, chapter: Boolean): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        val list = if (chapter) chapters(book) else book.headings
        if (list.isEmpty()) return err("Ce livre n'a pas de chapitres.")
        val p = pos()
        val i = list.indexOfLast { cmp(it.pos(), p) <= 0 }
        val target = if (dir > 0) i + 1 else {
            val cur = list.getOrNull(i)
            // Plus de 5 secondes dans le chapitre : on revient à son début.
            if (cur != null && cur.track == p.track && p.ms - cur.ms > 5_000) i else i - 1
        }
        if (dir < 0 && target < 0) return go(Pos(0, 0), "Début du livre.")
        val h = list.getOrNull(target) ?: return AssistantReply("C'est le dernier chapitre.")
        return go(h.pos(), h.title)
    }

    private fun goToHeading(n: Int): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        val list = chapters(book)
        val h = list.getOrNull(n - 1) ?: return err("Ce livre a ${list.size} chapitres.")
        return go(h.pos(), h.title)
    }

    private fun goToPage(label: String): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        if (book.pages.isEmpty()) return err("Ce livre n'a pas de numéros de page.")
        val want = FrenchText.key(FrenchText.replaceNumberWords(label))
        val p = book.pages.firstOrNull { FrenchText.key(it.label) == want } ?: return err("Je ne trouve pas la page $label.")
        return go(Pos(p.track, p.ms), "Page ${p.label}.")
    }

    private fun goToEdge(end: Boolean, chapter: Boolean): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        if (!chapter) return if (end) go(Pos(book.tracks.lastIndex, 0), "Dernière partie du livre.") else go(Pos(0, 0), "Début du livre.")
        val list = chapters(book).ifEmpty { book.headings }
        val p = pos()
        val i = list.indexOfLast { cmp(it.pos(), p) <= 0 }
        val target = if (end) list.getOrNull(i + 1)?.pos()?.let { Pos(it.track, (it.ms - 10_000).coerceAtLeast(0)) } else list.getOrNull(i)?.pos()
        return go(target ?: Pos(p.track, 0), if (end) "Fin du chapitre." else "Début du chapitre.")
    }

    private fun jump(delta: Long): AssistantReply {
        val book = bookSession()?.book ?: return err("Il n'y a pas de lecture où avancer ou reculer.")
        if (current?.kind == Kind.TEXT) {
            // Livre texte : un segment (environ 400 caractères) dure à peu près 25 secondes.
            val n = (kotlin.math.abs(delta) / 25_000).coerceAtLeast(1).toInt() * (if (delta > 0) 1 else -1)
            val t = (pos().track + n).coerceIn(0, book.tracks.lastIndex)
            val amount = FrenchTime.speakDuration(Duration.ofMillis(kotlin.math.abs(delta)))
            return go(Pos(t, 0), if (delta > 0) "J'avance d'environ $amount." else "Je recule d'environ $amount.")
        }
        var p = pos()
        var ms = p.ms + delta
        var track = p.track
        while (ms < 0 && track > 0) { track--; ms += dur(book, track) ?: return go(Pos(track + 1, 0), "Début du fichier.") }
        while (true) {
            val d = dur(book, track) ?: break
            if (ms < d || track == book.tracks.lastIndex) { if (ms >= d) ms = (d - 1_000).coerceAtLeast(0); break }
            ms -= d; track++
        }
        p = Pos(track, ms.coerceAtLeast(0))
        val amount = FrenchTime.speakDuration(Duration.ofMillis(kotlin.math.abs(delta)))
        return go(p, if (delta > 0) "J'avance de $amount." else "Je recule de $amount.")
    }

    // ------------------------------------------------------------------ où suis-je

    private fun zone() = ZoneId.of("Europe/Paris")

    private fun whenSpoken(ms: Long): String =
        FrenchTime.speakDateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()), device.now().toLocalDateTime())

    fun whereAmI(): String {
        val s = current ?: return "Rien n'est en cours de lecture."
        return when (s.kind) {
            Kind.RADIO -> "Vous écoutez ${s.station?.name}."
            Kind.MEMOS -> {
                val i = pos().track
                "Mémo ${i + 1} sur ${s.memoList.size}, enregistré ${s.memoList.getOrNull(i)?.let { whenSpoken(it.createdMs) } ?: ""}."
            }
            Kind.MUSIC -> "Musique : ${s.book?.tracks?.getOrNull(pos().track)?.title ?: ""}, morceau ${pos().track + 1} sur ${s.book?.tracks?.size}."
            Kind.TEXT -> {
                val book = s.book ?: return ""
                val p = pos()
                buildString {
                    append("« ${book.title} ». ")
                    headingAt(book, p)?.takeIf { FrenchText.key(it.title) != FrenchText.key(book.title) }?.let { append(it.title.trimEnd('.')).append(". ") }
                    book.pages.lastOrNull { it.track <= p.track }?.let { append("Page ${it.label}. ") }
                    val total = book.tracks.sumOf { it.title.length }.coerceAtLeast(1)
                    val done = book.tracks.take(p.track).sumOf { it.title.length }
                    append("${(done.toLong() * 100 / total).toInt()} pour cent lus.")
                }.trim()
            }
            Kind.PODCAST, Kind.BOOK -> {
                val book = s.book ?: return ""
                val p = pos()
                buildString {
                    if (s.kind == Kind.PODCAST) append("Podcast « ${s.show} », épisode « ${book.title} ». ")
                    else append("« ${book.title} ». ")
                    headingAt(book, p)?.takeIf { FrenchText.key(it.title) != FrenchText.key(book.title) }?.let { append(it.title.trimEnd('.')).append(". ") }
                    book.pages.lastOrNull { cmp(Pos(it.track, it.ms), p) <= 0 }?.let { append("Page ${it.label}. ") }
                    val durations = book.tracks.indices.map { dur(book, it) }
                    if (durations.all { it != null }) {
                        val total = durations.sumOf { it!! }
                        val elapsed = durations.take(p.track).sumOf { it!! } + p.ms
                        if (total > 0) {
                            append("${(elapsed * 100 / total).toInt()} pour cent écoutés, il reste ")
                            append(FrenchTime.speakDuration(Duration.ofMillis(((total - elapsed) / 60_000 * 60_000).coerceAtLeast(60_000)))).append('.')
                        }
                    } else if (book.tracks.size > 1) append("Fichier ${p.track + 1} sur ${book.tracks.size}.")
                }.trim()
            }
        }
    }

    // ------------------------------------------------------------------ signets

    private fun addBookmark(): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        val p = pos()
        val list = state.bookmarks(book.id)
        val n = (list.maxOfOrNull { it.number } ?: 0) + 1
        state.setBookmarks(book.id, list + Bookmark(n, p.track, p.ms))
        val where = headingAt(book, p)?.let { ", ${it.title.trimEnd('.')}" } ?: ""
        return AssistantReply("Signet $n posé$where.", Earcon.SUCCESS)
    }

    private fun goToBookmark(n: Int): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        val b = state.bookmarks(book.id).firstOrNull { it.number == n } ?: return err("Il n'y a pas de signet $n dans ce livre.")
        return go(Pos(b.track, b.ms), "Signet $n.")
    }

    private fun listBookmarks(): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        val list = state.bookmarks(book.id)
        if (list.isEmpty()) return AssistantReply("Aucun signet dans ce livre. Dites : pose un signet.")
        val items = list.joinToString(" ; ") { b -> "signet ${b.number}" + (headingAt(book, Pos(b.track, b.ms))?.let { ", ${it.title.trimEnd('.')}" } ?: "") }
        return AssistantReply("${list.size} ${if (list.size == 1) "signet" else "signets"} : $items. Dites par exemple : va au signet ${list.first().number}.")
    }

    private fun deleteBookmark(n: Int?): AssistantReply {
        val book = bookSession()?.book ?: return err(noBook)
        val list = state.bookmarks(book.id)
        if (n == null) {
            if (list.isEmpty()) return AssistantReply("Il n'y a aucun signet dans ce livre.")
            return dialog.confirm("Je supprime les ${list.size} signets de « ${book.title} » ? Répondez oui ou non.") {
                state.setBookmarks(book.id, emptyList()); "Tous les signets sont supprimés."
            }
        }
        if (list.none { it.number == n }) return err("Il n'y a pas de signet $n.")
        state.setBookmarks(book.id, list.filter { it.number != n })
        return AssistantReply("Signet $n supprimé.")
    }

    // ------------------------------------------------------------------ vitesse et sommeil

    private fun speed(faster: Boolean?): AssistantReply {
        val s = current
        if (s == null || s.kind == Kind.RADIO || s.kind == Kind.MUSIC) return err("La vitesse se règle pendant un livre, un podcast ou un mémo.")
        val before = state.bookSpeed
        state.bookSpeed = when (faster) { null -> 1f; true -> before + 0.25f; false -> before - 0.25f }
        player.setSpeed(state.bookSpeed)
        after = After.PLAY
        val v = Regex("\\.?0+$").replace("%.2f".format(Locale.ROOT, state.bookSpeed), "").replace('.', ',')
        return AssistantReply(when {
            state.bookSpeed == before && faster == true -> "C'est déjà la vitesse maximale."
            state.bookSpeed == before && faster == false -> "C'est déjà la vitesse minimale."
            state.bookSpeed == 1f -> "Vitesse normale."
            else -> "Vitesse de lecture : $v."
        })
    }

    private fun sleep(minutes: Int?): AssistantReply {
        if (minutes == null) { sleepAtMs = null; return AssistantReply("Minuterie de sommeil annulée.") }
        sleepAtMs = nowMs() + minutes * 60_000L
        return AssistantReply("D'accord, j'arrête la lecture dans ${FrenchTime.speakDuration(Duration.ofMinutes(minutes.toLong()))}.", Earcon.SUCCESS)
    }

    // ------------------------------------------------------------------ radio

    private suspend fun findStation(name: String): Station? {
        RadioCatalog.find(name)?.let { return it }
        state.favoriteStations.firstOrNull { FrenchText.key(it.name) == FrenchText.key(name) }?.let { return it }
        return runCatching { radios.search(name) }.getOrNull().orEmpty().firstOrNull()
    }

    private suspend fun radio(name: String?): AssistantReply {
        if (!device.isOnline()) return err("Je n'ai pas internet : la radio ne peut pas marcher.")
        val station = if (name == null) state.lastStation ?: RadioCatalog.BUILT_IN.first()
            else findStation(name) ?: return err("Je ne trouve pas la radio « $name ».")
        return playStation(station)
    }

    private fun playStation(station: Station): AssistantReply {
        state.lastStation = station
        start(Session(Kind.RADIO, station = station), Pos(0, 0), live = true)
        return AssistantReply("${station.name}.")
    }

    private fun addFavorite(): AssistantReply {
        val st = current?.station ?: return err("Aucune radio n'est en cours.")
        if (state.favoriteStations.none { it.url == st.url }) state.favoriteStations = state.favoriteStations + st
        after = After.KEEP
        return AssistantReply("${st.name} est dans vos radios favorites.", Earcon.SUCCESS)
    }

    private fun listStations(): AssistantReply {
        val favs = state.favoriteStations
        val list = favs.ifEmpty { RadioCatalog.BUILT_IN }
        return AssistantReply((if (favs.isEmpty()) "Vous n'avez pas encore de radios favorites. Je connais : " else "Vos radios favorites : ") +
            list.joinToString(", ") { it.name } + ". Dites par exemple : mets ${list.first().name}.")
    }

    // ------------------------------------------------------------------ suivant / précédent

    private suspend fun step(dir: Int): AssistantReply {
        val s = current ?: return if (music.controlExternal(if (dir > 0) "next" else "previous")) AssistantReply("") else err("Rien n'est en cours de lecture.")
        return when (s.kind) {
            Kind.BOOK, Kind.TEXT -> heading(dir, chapter = false)
            Kind.RADIO -> {
                val list = state.favoriteStations.takeIf { it.size >= 2 } ?: RadioCatalog.BUILT_IN
                val i = list.indexOfFirst { it.url == s.station?.url }
                playStation(list[((i + dir) % list.size + list.size) % list.size])
            }
            Kind.MUSIC -> {
                val t = pos().track + dir
                val book = s.book!!
                if (t !in book.tracks.indices) AssistantReply(if (dir > 0) "C'était le dernier morceau." else "C'est le premier morceau.")
                else go(Pos(t, 0), book.tracks[t].title)
            }
            Kind.MEMOS -> {
                val t = pos().track + dir
                if (t !in s.memoList.indices) AssistantReply(if (dir > 0) "C'était le dernier mémo." else "C'est le premier mémo.")
                else go(Pos(t, 0), "Mémo ${t + 1}, ${whenSpoken(s.memoList[t].createdMs)}.")
            }
            Kind.PODCAST -> {
                val i = s.episodeIndex + dir
                val ep = s.episodes.getOrNull(i) ?: return AssistantReply(if (dir > 0) "Il n'y a pas d'épisode plus ancien." else "C'est l'épisode le plus récent.")
                playEpisode(s.show ?: "", s.feed ?: "", s.episodes, i)
            }
        }
    }

    // ------------------------------------------------------------------ podcasts

    private suspend fun subscribe(name: String): AssistantReply {
        if (!device.isOnline()) return err("Je n'ai pas internet pour chercher ce podcast.")
        val show = runCatching { podcasts.search(name) }.getOrNull().orEmpty().firstOrNull()
            ?: return err("Je ne trouve pas de podcast « $name ».")
        if (state.podcasts.any { it.feedUrl == show.feedUrl }) return AssistantReply("Vous êtes déjà abonné à « ${show.title} ».")
        val by = show.author?.let { ", de $it" } ?: ""
        return dialog.confirm("Je vous abonne au podcast « ${show.title} »$by ? Répondez oui ou non.") {
            state.podcasts = state.podcasts + PodcastSub(show.title, show.feedUrl, show.author)
            "C'est fait. Pour l'écouter, dites : écoute le podcast ${show.title}."
        }
    }

    private fun unsubscribe(name: String): AssistantReply {
        val sub = TitleMatch.best(name, state.podcasts) { it.title } ?: return err("Vous n'êtes pas abonné à « $name ».")
        return dialog.confirm("Je vous désabonne de « ${sub.title} » ? Répondez oui ou non.") {
            state.podcasts = state.podcasts.filter { it.feedUrl != sub.feedUrl }; "Désabonné de « ${sub.title} »."
        }
    }

    private fun listPodcasts(): AssistantReply {
        val subs = state.podcasts
        if (subs.isEmpty()) return AssistantReply("Vous n'êtes abonné à aucun podcast. Dites par exemple : abonne-moi au podcast Affaires sensibles.")
        return AssistantReply("Vous êtes abonné à ${subs.size} ${if (subs.size == 1) "podcast" else "podcasts"} : " + subs.joinToString(", ") { it.title } + ".")
    }

    private suspend fun playPodcast(name: String?): AssistantReply {
        if (!device.isOnline()) return playOffline(name)
        val subs = state.podcasts
        val newest = name != null && Regex("\\bdernier\\b").containsMatchIn(FrenchText.key(name))
        val query = name?.let { FrenchText.key(it).replace(Regex("^(?:dernier |nouvel |nouveau )"), "") }
        var (title, feed) = when {
            query.isNullOrBlank() -> (subs.firstOrNull { it.feedUrl == state.lastPodcastFeed } ?: subs.singleOrNull())
                ?.let { it.title to it.feedUrl } ?: return err(if (subs.isEmpty()) "Vous n'êtes abonné à aucun podcast." else "Quel podcast ? " + subs.joinToString(", ") { it.title } + ".")
            else -> TitleMatch.best(query, subs) { it.title }?.let { it.title to it.feedUrl }
                ?: runCatching { podcasts.search(query) }.getOrNull().orEmpty().firstOrNull()?.let { it.title to it.feedUrl }
                ?: return err("Je ne trouve pas le podcast « $name ».")
        }
        val episodes = runCatching { podcasts.episodes(feed) }.getOrNull().orEmpty()
        if (episodes.isEmpty()) return err("Je n'arrive pas à récupérer les épisodes de « $title ».")
        // Reprend l'épisode commencé, sinon le plus récent.
        val lastUrl = state.lastEpisode(feed)
        val i = if (!newest && lastUrl != null) episodes.indexOfFirst { it.url == lastUrl }.takeIf { it >= 0 } ?: 0 else 0
        val notSubscribed = if (subs.none { it.feedUrl == feed }) " Vous n'y êtes pas abonné ; dites « abonne-moi au podcast $title » pour le garder." else ""
        return playEpisode(title, feed, episodes, i).let { it.copy(speech = it.speech + notSubscribed) }
    }

    /** Sans internet : les épisodes téléchargés. */
    private fun playOffline(name: String?): AssistantReply {
        val local = state.downloads.filter { downloader?.exists(it.localUri) == true }
        if (local.isEmpty()) return err("Je n'ai pas internet, et aucun épisode n'est téléchargé. En wifi, dites : télécharge les podcasts.")
        val query = name?.let { FrenchText.key(it).replace(Regex("^(?:dernier |nouvel |nouveau )"), "") }?.takeIf { it.isNotBlank() }
        val feed = when {
            query != null -> TitleMatch.best(query, local) { it.show }?.feed ?: return err("Aucun épisode de « $name » n'est téléchargé.")
            else -> local.firstOrNull { it.feed == state.lastPodcastFeed }?.feed ?: local.maxByOrNull { it.publishedMs ?: 0 }!!.feed
        }
        val eps = local.filter { it.feed == feed }.sortedByDescending { it.publishedMs ?: 0 }
        val episodes = eps.map { Episode(it.title, it.url, it.publishedMs, it.durationMs) }
        val lastUrl = state.lastEpisode(feed)
        val i = episodes.indexOfFirst { it.url == lastUrl }.takeIf { it >= 0 } ?: 0
        return playEpisode(eps.first().show, feed, episodes, i).let { it.copy(speech = "Épisode téléchargé. " + it.speech) }
    }

    /** Fichier téléchargé de l'épisode s'il existe. */
    private fun localFor(url: String): String? =
        state.downloads.firstOrNull { it.url == url }?.localUri?.takeIf { downloader?.exists(it) == true }

    private fun playEpisode(show: String, feed: String, episodes: List<Episode>, i: Int): AssistantReply {
        val ep = episodes[i]
        state.lastPodcastFeed = feed
        state.setLastEpisode(feed, ep.url)
        val book = Book(ep.url, ep.title, Shelf.PODCASTS, listOf(Track(localFor(ep.url) ?: ep.url, ep.title, ep.durationMs)))
        val saved = state.position(ep.url)
        start(Session(Kind.PODCAST, book, show = show, feed = feed, episodes = episodes, episodeIndex = i), saved ?: Pos(0, 0))
        val date = ep.publishedMs?.let { ms ->
            val d = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()).toLocalDate()
            ", du ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)}"
        } ?: ""
        return AssistantReply("« $show » : ${ep.title.trimEnd('.')}$date." + if (saved != null && saved.ms > 30_000) " Je reprends où vous en étiez." else "")
    }

    // ------------------------------------------------------------------ téléchargements de podcasts

    /** Podcast demandé pour le prochain téléchargement lancé par l'application (null = tous). */
    var downloadFilter: String? = null
        private set

    private fun askDownload(name: String?): AssistantReply {
        if (downloader == null) return err("Le téléchargement n'est pas disponible.")
        if (state.podcasts.isEmpty()) return err("Vous n'êtes abonné à aucun podcast. Dites par exemple : abonne-moi au podcast Affaires sensibles.")
        if (name != null && TitleMatch.best(name, state.podcasts) { it.title } == null) return err("Vous n'êtes pas abonné à « $name ».")
        if (!device.isOnline()) return err("Je n'ai pas internet pour télécharger.")
        if (!downloader.unmetered()) return AssistantReply("Vous n'êtes pas en wifi : pour ne pas consommer vos données mobiles, je téléchargerai dès que le téléphone sera en wifi.")
        downloadFilter = name
        val what = name?.let { "les derniers épisodes de « ${TitleMatch.best(it, state.podcasts) { s -> s.title }!!.title} »" } ?: "les derniers épisodes de vos podcasts"
        return AssistantReply("Je télécharge $what. Je vous préviens quand c'est fini.", followUp = FollowUp.DOWNLOAD_PODCASTS)
    }

    /**
     * Garde les [MediaState.keepEpisodes] derniers épisodes de chaque abonnement, plus l'épisode commencé.
     * [force] : même hors wifi (demande explicite). Renvoie le nombre d'épisodes nouvellement téléchargés.
     */
    suspend fun syncDownloads(force: Boolean = false, filter: String? = downloadFilter): Int {
        val dl = downloader ?: return 0
        downloadFilter = null
        if (!device.isOnline() || (!force && (!state.autoDownload || !dl.unmetered()))) return 0
        val subs = filter?.let { f -> listOfNotNull(TitleMatch.best(f, state.podcasts) { it.title }) } ?: state.podcasts
        var added = 0
        for (sub in subs) {
            val episodes = runCatching { podcasts.episodes(sub.feedUrl) }.getOrNull() ?: continue
            val keep = episodes.take(state.keepEpisodes)
            val inProgress = state.lastEpisode(sub.feedUrl)?.takeIf { (state.position(it)?.ms ?: 0) > 0 }
            for (ep in keep) {
                if (state.downloads.any { it.url == ep.url && dl.exists(it.localUri) }) continue
                val name = "ep_" + Integer.toHexString(ep.url.hashCode()) + "_" + (ep.publishedMs ?: 0) + ".mp3"
                val local = runCatching { dl.download(ep.url, name) }.getOrNull() ?: continue
                state.downloads = state.downloads.filter { it.url != ep.url } +
                    fr.assistantvocal.core.media.DownloadedEpisode(sub.feedUrl, sub.title, ep.title, ep.url, local, ep.publishedMs, ep.durationMs)
                added++
            }
            // Les plus anciens partent, sauf l'épisode en cours d'écoute.
            val keepUrls = keep.map { it.url }.toSet() + listOfNotNull(inProgress)
            val (stay, drop) = state.downloads.partition { it.feed != sub.feedUrl || it.url in keepUrls }
            drop.forEach { dl.delete(it.localUri) }
            state.downloads = stay
        }
        // Désabonné : on supprime ses fichiers.
        val (mine, orphans) = state.downloads.partition { d -> state.podcasts.any { it.feedUrl == d.feed } }
        orphans.forEach { dl.delete(it.localUri) }
        state.downloads = mine
        return added
    }

    /** Annonce de fin de téléchargement. */
    fun downloadDone(added: Int): String = when (added) {
        0 -> "Vos podcasts sont à jour : pas de nouvel épisode à télécharger."
        1 -> "Téléchargement terminé : 1 nouvel épisode, disponible même sans internet."
        else -> "Téléchargement terminé : $added nouveaux épisodes, disponibles même sans internet."
    }

    private fun listDownloads(): AssistantReply {
        val list = state.downloads.filter { downloader?.exists(it.localUri) == true }
        if (list.isEmpty()) return AssistantReply("Aucun épisode n'est téléchargé." +
            if (state.autoDownload && state.podcasts.isNotEmpty()) " Ils se téléchargent tout seuls en wifi." else "")
        val byShow = list.groupBy { it.show }.entries.joinToString(" ; ") { (show, eps) -> "« $show », ${eps.size} ${if (eps.size == 1) "épisode" else "épisodes"}" }
        return AssistantReply("${list.size} ${if (list.size == 1) "épisode téléchargé" else "épisodes téléchargés"} : $byShow.")
    }

    private fun deleteDownloads(): AssistantReply {
        val list = state.downloads
        if (list.isEmpty()) return AssistantReply("Aucun épisode n'est téléchargé.")
        return dialog.confirm("Je supprime les ${list.size} épisodes téléchargés ? Répondez oui ou non.") {
            list.forEach { downloader?.delete(it.localUri) }
            state.downloads = emptyList()
            "Les épisodes téléchargés sont supprimés." + if (state.autoDownload) " Les prochains se téléchargeront en wifi." else ""
        }
    }

    // ------------------------------------------------------------------ musique

    private suspend fun playMusic(query: String?): AssistantReply {
        val local = runCatching { music.local(query) }.getOrDefault(emptyList())
        if (local.isNotEmpty()) {
            val tracks = if (query == null) local.shuffled() else local
            start(Session(Kind.MUSIC, Book("music:${query ?: "tout"}", query ?: "Musique", Shelf.MUSIC, tracks)), Pos(0, 0))
            return AssistantReply(if (query == null) "Je mets votre musique, au hasard." else "Je mets ${local.size} ${if (local.size == 1) "morceau" else "morceaux"} : $query.")
        }
        if (query != null && device.isOnline() && music.playExternal(query)) {
            current = null
            return AssistantReply("Je demande à votre application de musique de jouer $query.")
        }
        return err(if (query == null) "Je ne trouve pas de musique sur le téléphone." else "Je ne trouve pas « $query » sur le téléphone.")
    }

    // ------------------------------------------------------------------ mémos

    private fun playMemos(): AssistantReply {
        val list = memos.list().sortedByDescending { it.createdMs }
        if (list.isEmpty()) return AssistantReply("Vous n'avez aucun mémo. Dites : enregistre un mémo.")
        val tracks = list.mapIndexed { i, m -> Track(m.uri, "Mémo ${i + 1}", m.durationMs) }
        start(Session(Kind.MEMOS, Book("memos", "Mémos", Shelf.NOTES, tracks, tracks.mapIndexed { i, t -> Heading(t.title, 1, i, 0) }), memoList = list), Pos(0, 0))
        val n = list.size
        return AssistantReply("${if (n == 1) "Vous avez 1 mémo" else "Vous avez $n mémos, le plus récent d'abord"}. Mémo 1, ${whenSpoken(list[0].createdMs)}.")
    }

    private fun deleteMemo(): AssistantReply {
        val s = current?.takeIf { it.kind == Kind.MEMOS } ?: return err("Dites d'abord : écoute mes mémos, puis : supprime ce mémo pendant qu'il est lu.")
        val i = pos().track
        val memo = s.memoList.getOrNull(i) ?: return err("Je ne sais pas quel mémo supprimer.")
        after = After.PAUSE
        return dialog.confirm("Je supprime le mémo ${i + 1}, ${whenSpoken(memo.createdMs)} ? Répondez oui ou non.") {
            player.stop(); current = null
            if (memos.delete(memo)) "Mémo supprimé." else "Je n'ai pas réussi à le supprimer."
        }
    }

    /** Annonce après l'enregistrement d'un mémo par l'application. */
    fun memoRecorded(durationMs: Long?): String = when {
        durationMs == null -> "Je n'ai pas pu enregistrer le mémo."
        durationMs < 1_000 -> "Je n'ai rien entendu, le mémo n'est pas gardé."
        else -> "Mémo enregistré : ${FrenchTime.speakDuration(Duration.ofMillis(durationMs))}. Pour l'écouter, dites : écoute mes mémos."
    }

    // ------------------------------------------------------------------ pour le point du jour et les tests

    fun currentKind(): Kind? = current?.kind
    fun currentTitle(): String? = current?.book?.title ?: current?.station?.name
    fun currentPos(): Pos = pos()
}
