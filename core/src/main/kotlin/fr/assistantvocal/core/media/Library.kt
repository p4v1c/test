package fr.assistantvocal.core.media

import fr.assistantvocal.core.text.FrenchText

/** Étagères, comme sur le Victor Reader Stream. */
enum class Shelf(val label: String, val victorFolder: String?) {
    DAISY("livres DAISY", "\$VRDTB"),
    BOOKS("livres audio", "\$VROtherBooks"),
    MUSIC("musique", "\$VRMusic"),
    PODCASTS("podcasts enregistrés", "\$VRPodcasts"),
    NOTES("notes du Victor", "\$VRNotes"),
    TEXT("livres texte", "\$VRText"),
}

/** Un fichier audio du livre ; [durationMs] connue d'après le DAISY, sinon fournie par le lecteur. */
data class Track(val uri: String, val title: String, val durationMs: Long? = null)

/** Titre de chapitre ou de section : [level] 1 = chapitre. Position = piste + décalage. */
data class Heading(val title: String, val level: Int, val track: Int, val ms: Long)

data class PageMark(val label: String, val track: Int, val ms: Long)

data class Book(
    val id: String,
    val title: String,
    val shelf: Shelf,
    val tracks: List<Track>,
    val headings: List<Heading> = emptyList(),
    val pages: List<PageMark> = emptyList(),
    val author: String? = null,
) {
    val totalMs: Long? get() = if (tracks.all { it.durationMs != null }) tracks.sumOf { it.durationMs!! } else null
}

/** Position de lecture. */
data class Pos(val track: Int, val ms: Long)

/** Accès aux livres (dossier du Victor copié sur le téléphone, ou autre dossier choisi). */
interface LibrarySource {
    /** Liste les livres ; les pistes peuvent être vides tant que le livre n'est pas ouvert. */
    suspend fun scan(): List<Book>

    /** Charge le livre complet (pistes, chapitres, pages) avant de le lire. */
    suspend fun load(book: Book): Book = book
}

/** Lecture d'un fichier texte d'un livre DAISY (par nom relatif au dossier du livre, insensible à la casse). */
fun interface BookFiles { fun read(name: String): String? }

/**
 * Lecture des livres DAISY 2.02 (ncc.html + .smil) et DAISY 3 / NISO (.ncx + .smil), sans dépendance Android.
 * Le livre devient une suite de fichiers audio, avec chapitres et pages repérés par (piste, décalage).
 */
object Daisy {
    /** Référence vers « fichier.smil#id ». */
    private data class Ref(val smil: String, val id: String?)
    private data class NavEntry(val title: String, val level: Int, val ref: Ref)

    /** « npt=12.345s », « 0:01:02.5 », « 00:00:12.300 », « 12.5s », « 450ms », « 1.5min », « 1h ». */
    fun clockMs(v: String?): Long? {
        if (v == null) return null
        val s = v.trim().removePrefix("npt=")
        Regex("^(\\d+(?:\\.\\d+)?)(h|min|s|ms)?$").find(s)?.let { m ->
            val n = m.groupValues[1].toDouble()
            return (when (m.groupValues[2]) { "h" -> n * 3_600_000; "min" -> n * 60_000; "ms" -> n; else -> n * 1000 }).toLong()
        }
        val parts = s.split(':')
        if (parts.size in 2..3 && parts.all { it.isNotEmpty() }) {
            val nums = parts.map { it.toDoubleOrNull() ?: return null }
            val sec = if (nums.size == 3) nums[0] * 3600 + nums[1] * 60 + nums[2] else nums[0] * 60 + nums[1]
            return (sec * 1000).toLong()
        }
        return null
    }

    private fun attr(tag: String, name: String): String? =
        Regex("(?i)\\s$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(tag)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    private fun ref(href: String): Ref = Ref(href.substringBefore('#').substringAfterLast('/'), href.substringAfter('#', "").ifEmpty { null })

    private fun text(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&nbsp;", " ")
        .replace("&#39;", "'").replace("&quot;", "\"").replace("&apos;", "'").replace(Regex("\\s+"), " ").trim()

    /** DAISY 2.02 : titres h1…h6 et pages de ncc.html. */
    private fun parseNcc(ncc: String): Triple<String?, List<NavEntry>, List<Pair<String, Ref>>> {
        val title = Regex("(?is)<meta[^>]+name=\"dc:title\"[^>]*>").find(ncc)?.value?.let { attr(it, "content") }
            ?: Regex("(?is)<title>(.*?)</title>").find(ncc)?.groupValues?.get(1)?.let { text(it) }
        val nav = mutableListOf<NavEntry>()
        val pages = mutableListOf<Pair<String, Ref>>()
        Regex("(?is)<(h[1-6]|span)([^>]*)>(.*?)</\\1>").findAll(ncc).forEach { m ->
            val a = Regex("(?is)<a[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>").find(m.groupValues[3]) ?: return@forEach
            val r = ref(a.groupValues[1])
            val label = text(a.groupValues[2])
            if (m.groupValues[1].lowercase() == "span") {
                if ((attr("<x${m.groupValues[2]}>", "class") ?: "").startsWith("page")) pages += label to r
            } else nav += NavEntry(label, m.groupValues[1].substring(1).toInt(), r)
        }
        return Triple(title, nav, pages)
    }

    /** DAISY 3 : navMap (imbrication = niveau) et pageList de navigation.ncx. */
    private fun parseNcx(ncx: String): Triple<String?, List<NavEntry>, List<Pair<String, Ref>>> {
        val title = Regex("(?is)<docTitle>.*?<text>(.*?)</text>").find(ncx)?.groupValues?.get(1)?.let { text(it) }
        val nav = mutableListOf<NavEntry>()
        val navMap = Regex("(?is)<navMap[^>]*>(.*)</navMap>").find(ncx)?.groupValues?.get(1) ?: ""
        var level = 0
        var pendingLabel: String? = null
        Regex("(?is)<navPoint\\b[^>]*>|</navPoint>|<navLabel>\\s*<text>(.*?)</text>|<content\\b[^>]*>").findAll(navMap).forEach { m ->
            val v = m.value
            when {
                v.startsWith("</navPoint", true) -> level--
                v.startsWith("<navPoint", true) -> level++
                v.startsWith("<navLabel", true) -> pendingLabel = text(m.groupValues[1])
                v.startsWith("<content", true) -> {
                    val src = attr(v, "src") ?: return@forEach
                    if (pendingLabel != null) nav += NavEntry(pendingLabel!!, level.coerceIn(1, 6), ref(src))
                    pendingLabel = null
                }
            }
        }
        val pages = Regex("(?is)<pageTarget\\b.*?<text>(.*?)</text>.*?<content[^>]*src=\"([^\"]+)\"").findAll(ncx)
            .map { text(it.groupValues[1]) to ref(it.groupValues[2]) }.toList()
        return Triple(title, nav, pages)
    }

    /** Titre seul (lecture rapide pour la liste des livres). */
    fun quickTitle(content: String, ncx: Boolean): String? = (if (ncx) parseNcx(content) else parseNcc(content)).first?.takeIf { it.isNotBlank() }

    /** Clips audio d'un fichier SMIL, avec l'identifiant de l'élément englobant le plus proche. */
    private data class Clip(val ids: List<String>, val src: String, val begin: Long, val end: Long?)

    private fun parseSmil(smil: String): List<Clip> {
        val clips = mutableListOf<Clip>()
        val seenIds = mutableListOf<String>()
        Regex("(?is)<(par|seq|text|audio)\\b[^>]*>").findAll(smil).forEach { m ->
            val tag = m.value
            attr(tag, "id")?.let { seenIds += it }
            if (m.groupValues[1].equals("audio", true)) {
                val src = attr(tag, "src") ?: return@forEach
                val begin = clockMs(attr(tag, "clip-begin") ?: attr(tag, "clipBegin")) ?: 0L
                val end = clockMs(attr(tag, "clip-end") ?: attr(tag, "clipEnd"))
                clips += Clip(seenIds.toList(), src.substringAfterLast('/'), begin, end)
                seenIds.clear()
            }
        }
        return clips
    }

    /**
     * Construit le livre. [files] lit les fichiers du dossier ; [uriOf] donne l'adresse lisible d'un fichier audio.
     * Renvoie null si le dossier n'est pas un livre DAISY avec audio.
     */
    fun parse(id: String, folderName: String, files: BookFiles, uriOf: (String) -> String, nccOrNcx: String, shelf: Shelf = Shelf.DAISY): Book? {
        val content = files.read(nccOrNcx) ?: return null
        val (title, nav, pageRefs) = if (nccOrNcx.endsWith(".ncx", true)) parseNcx(content) else parseNcc(content)
        if (nav.isEmpty()) return null
        // Fichiers SMIL dans l'ordre de lecture (ordre d'apparition dans la navigation).
        val smilOrder = (nav.map { it.ref.smil } + pageRefs.map { it.second.smil }).distinct()
        val smils = smilOrder.associateWith { name -> files.read(name)?.let { parseSmil(it) } ?: emptyList() }
        val trackNames = mutableListOf<String>()
        val durations = mutableMapOf<String, Long>()
        smilOrder.forEach { s ->
            smils[s].orEmpty().forEach { c ->
                if (c.src !in trackNames) trackNames += c.src
                c.end?.let { e -> durations[c.src] = maxOf(durations[c.src] ?: 0L, e) }
            }
        }
        if (trackNames.isEmpty()) return null // livre texte seul : pas pris en charge
        fun locate(r: Ref): Pos? {
            val clips = smils[r.smil].orEmpty()
            val clip = (if (r.id == null) clips.firstOrNull() else clips.firstOrNull { r.id in it.ids }
                ?: clips.firstOrNull()) ?: return null
            return Pos(trackNames.indexOf(clip.src), clip.begin)
        }
        val headings = nav.mapNotNull { n -> locate(n.ref)?.let { Heading(n.title, n.level, it.track, it.ms) } }
        val pages = pageRefs.mapNotNull { (label, r) -> locate(r)?.let { PageMark(label, it.track, it.ms) } }
        val tracks = trackNames.map { Track(uriOf(it), it, durations[it]) }
        return Book(id, title?.takeIf { it.isNotBlank() } ?: cleanTitle(folderName), shelf, tracks, headings, pages)
    }

    /** « 01_Les_Miserables-Tome1 » → « Les Miserables Tome1 ». */
    fun cleanTitle(name: String): String =
        name.substringBeforeLast('.').replace(Regex("[_]+"), " ").replace(Regex("^\\d+\\s*[-. ]\\s*"), "")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { name }
}

/** Livre fait d'un dossier de fichiers audio (MP3…) : un fichier = un chapitre. */
object AudioFolderBook {
    val AUDIO = Regex("(?i).*\\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|wma)$")

    /** Tri naturel : « 2 » avant « 10 ». */
    val natural = Comparator<String> { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    fun build(id: String, title: String, shelf: Shelf, files: List<Pair<String, String>>): Book? {
        val audio = files.filter { AUDIO.matches(it.first) }.sortedWith(compareBy(natural) { it.first })
        if (audio.isEmpty()) return null
        val tracks = audio.map { (name, uri) -> Track(uri, Daisy.cleanTitle(name)) }
        val headings = if (tracks.size > 1) tracks.mapIndexed { i, t -> Heading(t.title, 1, i, 0) } else emptyList()
        return Book(id, Daisy.cleanTitle(title), shelf, tracks, headings)
    }
}

/** Recherche souple d'un titre dit à la voix parmi les livres. */
object TitleMatch {
    private val STOP = setOf("le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "livre", "roman", "tome", "et", "a", "au")

    private fun words(s: String) = FrenchText.key(s).split(' ').filter { it.isNotEmpty() && it !in STOP }

    fun score(query: String, title: String): Double {
        val q = words(query); val t = words(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        val hit = q.count { w -> t.any { it == w || (w.length >= 5 && FrenchText.editDistance(it, w) <= 1) || (w.length >= 4 && it.startsWith(w)) } }
        return hit.toDouble() / q.size * (0.7 + 0.3 * hit / t.size.coerceAtLeast(hit))
    }

    fun <T> best(query: String, items: List<T>, title: (T) -> String): T? =
        items.map { it to score(query, title(it)) }.filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
}
