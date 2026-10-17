package fr.assistantvocal.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import fr.assistantvocal.app.vision.MlKitOcr
import fr.assistantvocal.app.vision.toFrame
import fr.assistantvocal.core.media.AudioPlayer
import fr.assistantvocal.core.media.PodcastDownloader
import fr.assistantvocal.core.media.Pos
import fr.assistantvocal.core.media.TextBooks
import fr.assistantvocal.core.media.Track
import fr.assistantvocal.llm.GeminiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale

private const val TAG = "TextBooks"

/**
 * Lecture des livres texte par la synthèse vocale, comme un lecteur audio : une « piste » = un segment de texte.
 * Deux segments sont toujours dans la file de la synthèse, pour éviter les silences entre eux.
 */
class TtsTextPlayer(private val context: Context, private val pitch: () -> Float = { 1f }) : AudioPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var startWhenReady: (() -> Unit)? = null
    private var tracks: List<Track> = emptyList()
    @Volatile private var index = 0
    @Volatile private var playing = false
    private var rate = 1f
    private var focus: AudioFocusRequest? = null
    override var onFinished: (() -> Unit)? = null

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    private fun engine(): TextToSpeech = tts ?: TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.FRANCE
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts?.setOnUtteranceProgressListener(listener)
            startWhenReady?.invoke(); startWhenReady = null
        } else Log.e(TAG, "Synthèse vocale indisponible")
    }.also { tts = it }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String) {
            val n = id.removePrefix("seg-").toIntOrNull() ?: return
            main.post {
                if (!playing) return@post
                index = n
                // Garde un segment d'avance dans la file.
                if (n + 1 < tracks.size) enqueue(n + 1, TextToSpeech.QUEUE_ADD)
            }
        }
        override fun onDone(id: String) {
            val n = id.removePrefix("seg-").toIntOrNull() ?: return
            main.post { if (playing && n == tracks.lastIndex) { playing = false; abandonFocus(); onFinished?.invoke() } }
        }
        @Deprecated("Ancienne API") override fun onError(id: String) {}
    }

    private val queued = mutableSetOf<Int>()

    private fun enqueue(n: Int, mode: Int) {
        if (mode == TextToSpeech.QUEUE_FLUSH) queued.clear()
        if (!queued.add(n)) return
        tts?.speak(tracks[n].title, mode, Bundle(), "seg-$n")
    }

    private fun speakFrom(n: Int) {
        if (tracks.isEmpty()) return
        index = n.coerceIn(0, tracks.lastIndex)
        val go = {
            requestFocus()
            tts?.setSpeechRate(rate); tts?.setPitch(pitch())
            enqueue(index, TextToSpeech.QUEUE_FLUSH)
        }
        if (ready) go() else startWhenReady = go
    }

    private fun requestFocus() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) pause() }.build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() { focus?.let { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }; focus = null }

    override fun play(tracks: List<Track>, start: Pos, speed: Float, live: Boolean) {
        playing = true
        index = start.track
        onMain { engine(); this.tracks = tracks; rate = speed; speakFrom(start.track) }
    }

    override fun pause() { playing = false; onMain { queued.clear(); tts?.stop(); abandonFocus() } }
    override fun resume() { if (tracks.isNotEmpty() || index >= 0) { playing = true; onMain { speakFrom(index) } } }
    override fun stop() { playing = false; onMain { queued.clear(); tts?.stop(); tracks = emptyList(); abandonFocus() } }
    override fun seek(pos: Pos) { index = pos.track; onMain { if (playing) speakFrom(pos.track) } }
    override fun setSpeed(speed: Float) { onMain { rate = speed; if (playing) speakFrom(index) } }
    override fun isPlaying() = playing
    override fun position(): Pos? = Pos(index, 0)
    override fun durationOf(track: Int): Long? = null

    fun release() { onMain { tts?.shutdown(); tts = null; ready = false } }
}

/**
 * Texte d'un PDF : couche texte avec PdfBox ; pour les pages scannées (sans texte), rendu de la page et lecture
 * par ML Kit (sur le téléphone, hors ligne). Le résultat est mis en cache pour rouvrir le livre instantanément.
 */
class PdfText(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheFile(key: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return File(File(context.cacheDir, "textbooks").apply { mkdirs() }, "$h.json")
    }

    suspend fun extract(uri: Uri, cacheKey: String, maxPages: Int = 400): TextBooks.Doc? = withContext(Dispatchers.IO) {
        val cache = cacheFile(cacheKey)
        runCatching { if (cache.exists()) return@withContext json.decodeFromString(TextBooks.Doc.serializer(), cache.readText()) }
        runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            val pages = mutableListOf<String>()
            var title: String? = null
            context.contentResolver.openInputStream(uri)!!.use { input ->
                PDDocument.load(input).use { doc ->
                    title = doc.documentInformation?.title
                    val stripper = PDFTextStripper()
                    for (p in 1..minOf(doc.numberOfPages, maxPages)) {
                        stripper.startPage = p; stripper.endPage = p
                        pages += stripper.getText(doc)
                    }
                }
            }
            // Pages scannées : OCR.
            val empty = pages.indices.filter { pages[it].trim().length < 20 }
            if (empty.isNotEmpty()) ocrPages(uri, empty).forEach { (i, text) -> pages[i] = text }
            val doc = TextBooks.fromPages(pages, title)
            runCatching { cache.writeText(json.encodeToString(TextBooks.Doc.serializer(), doc)) }
            doc
        }.onFailure { Log.e(TAG, "PDF illisible", it) }.getOrNull()
    }

    private suspend fun ocrPages(uri: Uri, indices: List<Int>): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        val ocr = MlKitOcr()
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return out
        pfd.use {
            PdfRenderer(it).use { renderer ->
                for (i in indices) {
                    if (i >= renderer.pageCount) continue
                    renderer.openPage(i).use { page ->
                        val scale = (2000f / maxOf(page.width, page.height)).coerceIn(1f, 4f)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out[i] = ocr.read(bmp.toFrame(2000, 90)).lines.joinToString("\n") { l -> l.text }
                        bmp.recycle()
                    }
                }
            }
        }
        return out
    }
}

/** Épisodes téléchargés dans le stockage privé de l'app, seulement en wifi sauf demande explicite. */
class AndroidPodcastDownloader(private val context: Context) : PodcastDownloader {
    private val dir get() = File(context.filesDir, "podcasts").apply { mkdirs() }
    private val client by lazy { GeminiProvider.defaultClient().newBuilder().readTimeout(java.time.Duration.ofMinutes(2)).build() }

    override fun unmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override suspend fun download(url: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val target = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val tmp = File(dir, target.name + ".part")
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "AssistantVocal/0.8").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body ?: return@use null
                val free = StatFs(dir.path).availableBytes
                if (free < 300L * 1024 * 1024 + maxOf(0L, body.contentLength())) { Log.w(TAG, "Pas assez de place"); return@use null }
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (!tmp.renameTo(target)) return@use null
                Uri.fromFile(target).toString()
            }
        }.onFailure { Log.e(TAG, "Téléchargement raté", it); tmp.delete() }.getOrNull()
    }

    override fun delete(localUri: String) { runCatching { Uri.parse(localUri).path?.let { File(it).delete() } } }
    override fun exists(localUri: String): Boolean = runCatching { Uri.parse(localUri).path?.let { File(it).exists() } ?: false }.getOrDefault(false)
}
