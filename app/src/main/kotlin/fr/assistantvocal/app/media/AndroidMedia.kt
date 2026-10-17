package fr.assistantvocal.app.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import fr.assistantvocal.app.phone.AssistantNotificationListener
import fr.assistantvocal.core.media.AudioFolderBook
import fr.assistantvocal.core.media.AudioPlayer
import fr.assistantvocal.core.media.Book
import fr.assistantvocal.core.media.BookFiles
import fr.assistantvocal.core.media.Daisy
import fr.assistantvocal.core.media.LibrarySource
import fr.assistantvocal.core.media.Memo
import fr.assistantvocal.core.media.MemoStore
import fr.assistantvocal.core.media.MusicSource
import fr.assistantvocal.core.media.Pos
import fr.assistantvocal.core.media.Shelf
import fr.assistantvocal.core.media.Track
import fr.assistantvocal.core.text.FrenchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Media"

/**
 * Lecteur Media3 ExoPlayer. Peut être appelé depuis n'importe quel fil : les commandes sont passées au fil principal,
 * l'état (lecture, position) est relevé toutes les demi-secondes.
 */
class AndroidPlayer(private val context: Context) : AudioPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var exo: ExoPlayer? = null
    @Volatile private var playing = false
    @Volatile private var lastPos: Pos? = null
    private val durations = ConcurrentHashMap<Int, Long>()
    override var onFinished: (() -> Unit)? = null

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) { playing = false; onFinished?.invoke() }
        }
        override fun onPlayerError(error: PlaybackException) { Log.e(TAG, "Lecture impossible", error); playing = false }
    }

    private val poll = object : Runnable {
        override fun run() {
            exo?.let { p ->
                if (p.mediaItemCount > 0) {
                    lastPos = Pos(p.currentMediaItemIndex, p.currentPosition.coerceAtLeast(0))
                    if (p.duration != C.TIME_UNSET && p.duration > 0) durations[p.currentMediaItemIndex] = p.duration
                }
            }
            main.postDelayed(this, 500)
        }
    }

    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true) // casque débranché : pause
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { p -> p.addListener(listener); exo = p; main.post(poll) }

    override fun play(tracks: List<Track>, start: Pos, speed: Float, live: Boolean) {
        durations.clear()
        lastPos = start; playing = true
        onMain {
            val p = player()
            p.setMediaItems(tracks.map { MediaItem.fromUri(it.uri) }, start.track.coerceIn(0, tracks.lastIndex), if (live) C.TIME_UNSET else start.ms)
            p.playbackParameters = PlaybackParameters(if (live) 1f else speed)
            p.prepare(); p.play()
        }
    }

    override fun pause() { playing = false; onMain { exo?.pause() } }
    override fun resume() { if (lastPos != null) { playing = true; onMain { exo?.play() } } }
    override fun stop() { playing = false; lastPos = null; onMain { exo?.stop(); exo?.clearMediaItems() } }
    override fun seek(pos: Pos) { lastPos = pos; onMain { exo?.seekTo(pos.track, pos.ms) } }
    override fun setSpeed(speed: Float) { onMain { exo?.playbackParameters = PlaybackParameters(speed) } }
    override fun isPlaying() = playing
    override fun position() = lastPos
    override fun durationOf(track: Int): Long? = durations[track]

    fun release() { onMain { main.removeCallbacks(poll); exo?.release(); exo = null } }
}

/**
 * Bibliothèque dans un dossier choisi par un proche (sélecteur de fichiers Android), par exemple le contenu de la carte
 * du Victor copié sur le téléphone : $VRDTB (DAISY), $VROtherBooks (livres audio), $VRMusic, $VRPodcasts, $VRNotes.
 * Un dossier sans cette organisation marche aussi : chaque sous-dossier avec ncc.html ou .ncx est un livre DAISY,
 * chaque sous-dossier de fichiers audio est un livre audio.
 */
class SafLibrary(private val context: Context, private val treeUri: () -> String?) : LibrarySource {
    override suspend fun scan(): List<Book> = withContext(Dispatchers.IO) {
        val root = treeUri()?.takeIf { it.isNotBlank() }?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) } ?: return@withContext emptyList()
        val out = mutableListOf<Book>()
        runCatching {
            for (child in root.listFiles()) {
                val name = child.name ?: continue
                val shelf = Shelf.entries.firstOrNull { it.victorFolder.equals(name, ignoreCase = true) }
                when {
                    shelf == Shelf.NOTES -> folderBook(child, Shelf.NOTES)?.let { out += it.copy(title = "Notes du Victor") }
                    shelf == Shelf.PODCASTS || shelf == Shelf.MUSIC -> scanFolder(child, shelf, out, depth = 0, singleFilesAreBooks = shelf == Shelf.PODCASTS)
                    shelf != null -> scanFolder(child, shelf, out, depth = 0, singleFilesAreBooks = true)
                    child.isDirectory && !name.startsWith("$") -> classify(child, Shelf.BOOKS)?.let { out += it } ?: scanFolder(child, Shelf.BOOKS, out, 1, true)
                }
            }
        }.onFailure { Log.e(TAG, "Lecture du dossier des livres impossible", it) }
        out
    }

    private fun scanFolder(dir: DocumentFile, shelf: Shelf, out: MutableList<Book>, depth: Int, singleFilesAreBooks: Boolean) {
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                classify(f, shelf)?.let { out += it } ?: if (depth < 2) scanFolder(f, shelf, out, depth + 1, singleFilesAreBooks) else Unit
            } else if (singleFilesAreBooks && AudioFolderBook.AUDIO.matches(name)) {
                AudioFolderBook.build(f.uri.toString(), name, shelf, listOf(name to f.uri.toString()))?.let { out += it }
            }
        }
    }

    /** Livre DAISY (titre seul, chargé à l'ouverture) ou dossier audio ; null sinon. */
    private fun classify(dir: DocumentFile, shelf: Shelf): Book? {
        val files = dir.listFiles()
        val nav = files.firstOrNull { it.name.equals("ncc.html", true) || it.name.equals("ncc.htm", true) }
            ?: files.firstOrNull { it.name?.endsWith(".ncx", true) == true }
        if (nav != null) {
            val text = readText(nav) ?: return null
            val title = Daisy.quickTitle(text, nav.name!!.endsWith(".ncx", true)) ?: Daisy.cleanTitle(dir.name ?: "Livre")
            return Book("daisy:" + dir.uri, title, if (shelf == Shelf.BOOKS) Shelf.DAISY else shelf, emptyList())
        }
        return folderBook(dir, shelf, files)
    }

    private fun folderBook(dir: DocumentFile, shelf: Shelf, files: Array<DocumentFile> = dir.listFiles()): Book? =
        AudioFolderBook.build(dir.uri.toString(), dir.name ?: "Livre", shelf, files.filter { it.isFile }.mapNotNull { f -> f.name?.let { it to f.uri.toString() } })

    override suspend fun load(book: Book): Book = withContext(Dispatchers.IO) {
        if (!book.id.startsWith("daisy:") || book.tracks.isNotEmpty()) return@withContext book
        val folder = findDir(book.id.removePrefix("daisy:")) ?: return@withContext book
        val files = folder.listFiles().associateBy { it.name?.lowercase() ?: "" }
        val nav = files["ncc.html"] ?: files["ncc.htm"] ?: files.values.firstOrNull { it.name?.endsWith(".ncx", true) == true } ?: return@withContext book
        Daisy.parse(book.id, folder.name ?: book.title, BookFiles { n -> files[n.lowercase()]?.let { readText(it) } },
            { n -> files[n.lowercase()]?.uri?.toString() ?: n }, nav.name!!)?.copy(title = book.title, shelf = book.shelf) ?: book
    }

    /** Retrouve un sous-dossier à partir de son adresse (DocumentFile ne sait pas ouvrir un sous-dossier directement). */
    private fun findDir(uri: String): DocumentFile? {
        val root = treeUri()?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) } ?: return null
        fun walk(d: DocumentFile, depth: Int): DocumentFile? {
            if (d.uri.toString() == uri) return d
            if (depth > 3) return null
            return d.listFiles().filter { it.isDirectory }.firstNotNullOfOrNull { walk(it, depth + 1) }
        }
        return walk(root, 0)
    }

    private fun readText(f: DocumentFile): String? = runCatching {
        context.contentResolver.openInputStream(f.uri)?.use { decode(it.readBytes()) }
    }.getOrNull()

    companion object {
        /** Les vieux livres DAISY sont souvent en windows-1252 : encodage déclaré, sinon UTF-8 strict, sinon windows-1252. */
        fun decode(bytes: ByteArray): String {
            val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            val declared = Regex("(?i)(?:encoding|charset)\\s*=\\s*[\"']?([\\w-]+)").find(head)?.groupValues?.get(1)
            declared?.let { n -> runCatching { return String(bytes, Charset.forName(n)) } }
            return runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrElse { String(bytes, Charset.forName("windows-1252")) }
        }
    }
}

/** Mémos vocaux : fichiers AAC dans le stockage privé de l'app, durée dans le nom. */
class AndroidMemos(private val context: Context) : MemoStore {
    private val dir get() = File(context.filesDir, "memos").apply { mkdirs() }

    override fun list(): List<Memo> = dir.listFiles().orEmpty().mapNotNull { f ->
        val m = Regex("memo_(\\d+)_(\\d+)\\.m4a").matchEntire(f.name) ?: return@mapNotNull null
        Memo(Uri.fromFile(f).toString(), m.groupValues[1].toLong(), m.groupValues[2].toLong())
    }

    override fun delete(memo: Memo): Boolean = runCatching { File(Uri.parse(memo.uri).path!!).delete() }.getOrDefault(false)

    /**
     * Enregistre jusqu'à ce que la personne se taise 2,5 s (ou 5 minutes au plus). Renvoie la durée en ms,
     * 0 si rien n'a été dit (fichier supprimé), null en cas d'erreur.
     */
    suspend fun record(): Long? = withContext(Dispatchers.Main) {
        val started = System.currentTimeMillis()
        val tmp = File(dir, "memo_tmp.m4a")
        val rec = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder())
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44_100); rec.setAudioEncodingBitRate(64_000); rec.setAudioChannels(1)
            rec.setOutputFile(tmp.absolutePath)
            rec.prepare(); rec.start()
            var spoke = false
            var lastVoice = System.currentTimeMillis()
            while (true) {
                delay(100)
                val now = System.currentTimeMillis()
                val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)
                if (amp > 1_500) { spoke = true; lastVoice = now }
                if (!spoke && now - started > 6_000) break // rien dit
                if (spoke && now - lastVoice > 2_500) break // silence après la parole
                if (now - started > 5 * 60_000) break
            }
            runCatching { rec.stop() }
            val duration = (lastVoice - started).coerceAtLeast(0) + 500
            if (!spoke) { tmp.delete(); return@withContext 0L }
            tmp.renameTo(File(dir, "memo_${started}_$duration.m4a"))
            duration
        } catch (e: Exception) {
            Log.e(TAG, "Enregistrement du mémo impossible", e); tmp.delete(); null
        } finally {
            rec.release()
        }
    }
}

/** Musique du téléphone (MediaStore) et applications de musique installées. */
class AndroidMusic(private val context: Context) : MusicSource {
    override suspend fun local(query: String?): List<Track> = withContext(Dispatchers.IO) {
        val words = query?.let { FrenchText.key(it).split(' ').filter { w -> w.length > 1 && w !in setOf("de", "du", "des", "la", "le", "les") } }.orEmpty()
        val out = mutableListOf<Track>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.ARTIST}, ${MediaStore.Audio.Media.ALBUM}, ${MediaStore.Audio.Media.TRACK}",
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1) ?: continue
                    val hay = FrenchText.key("$title ${c.getString(2) ?: ""} ${c.getString(3) ?: ""}")
                    if (words.isNotEmpty() && words.any { it !in hay }) continue
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                    out += Track(uri.toString(), title, c.getLong(4).takeIf { it > 0 })
                }
            }
        }.onFailure { Log.w(TAG, "Musique locale illisible", it) }
        out
    }

    override fun playExternal(query: String): Boolean {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ytm = Intent(i).setPackage(YT_MUSIC)
        return listOf(ytm, i).any { runCatching { context.startActivity(it) }.isSuccess }
    }

    /** Commande une autre application en cours de lecture (nécessite l'accès aux notifications). */
    override fun controlExternal(action: String): Boolean = runCatching {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val c = msm.getActiveSessions(ComponentName(context, AssistantNotificationListener::class.java))
            .firstOrNull { it.packageName != context.packageName } ?: return false
        val t = c.transportControls
        when (action) { "pause" -> t.pause(); "play" -> t.play(); "next" -> t.skipToNext(); "previous" -> t.skipToPrevious() }
        true
    }.getOrDefault(false)

    companion object { const val YT_MUSIC = "com.google.android.apps.youtube.music" }
}
