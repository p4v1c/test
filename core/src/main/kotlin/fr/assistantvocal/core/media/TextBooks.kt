package fr.assistantvocal.core.media

import fr.assistantvocal.core.text.FrenchText
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Livres en texte (étagère Texte du Victor) : TXT, Markdown, HTML, DOCX, EPUB (sans DRM) ; le PDF est extrait côté Android.
 * Le texte devient une suite de « segments » (environ 400 caractères, coupés entre deux phrases) lus par la synthèse
 * vocale ; chapitres et pages pointent vers un segment.
 */
object TextBooks {
    /** Un paragraphe du document. [heading] : niveau de titre (1 = chapitre) ; [page] : numéro de page qui commence ici. */
    @kotlinx.serialization.Serializable
    data class Block(val text: String, val heading: Int? = null, val page: String? = null)

    @kotlinx.serialization.Serializable
    data class Doc(val title: String?, val blocks: List<Block>)

    /**
     * Texte d'un PDF, page par page : les lignes sont recollées en paragraphes (fin de phrase suivie d'une majuscule),
     * les mots coupés en fin de ligne sont réparés, et chaque page commence par son numéro.
     */
    fun fromPages(pages: List<String>, title: String? = null): Doc {
        val blocks = mutableListOf<Block>()
        pages.forEachIndexed { i, page ->
            val lines = page.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val paras = mutableListOf<StringBuilder>()
            for (line in lines) {
                val cur = paras.lastOrNull()
                val startsNew = cur == null || (Regex("[.!?:»…]$").containsMatchIn(cur) && (line.first().isUpperCase() || line.first() in "-–—•«"))
                when {
                    startsNew -> paras += StringBuilder(line)
                    cur!!.endsWith("-") && line.first().isLowerCase() -> { cur.setLength(cur.length - 1); cur.append(line) }
                    else -> cur.append(' ').append(line)
                }
            }
            paras.forEachIndexed { j, p ->
                val text = p.toString()
                val heading = if (text.length <= 70 && CHAPTER.containsMatchIn(text)) 1 else null
                blocks += Block(text, heading, if (j == 0) (i + 1).toString() else null)
            }
            if (paras.isEmpty()) blocks += Block("Page ${i + 1} sans texte.", null, (i + 1).toString())
        }
        return Doc(title, blocks)
    }

    val TEXT_FILE = Regex("(?i).*\\.(txt|text|md|html?|xhtml|docx|epub|pdf)$")

    const val SEGMENT = 400

    // ------------------------------------------------------------------ texte brut

    private val CHAPTER = Regex("^(?i)(chapitre|chap\\.|partie|livre|tome|prologue|épilogue|epilogue|préface|preface|introduction|conclusion|acte|scène)\\b.{0,60}$")

    fun parseTxt(text: String): Doc {
        val norm = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u000c', '\n')
        val hasBlankLines = Regex("\n\\s*\n").containsMatchIn(norm)
        val paras = (if (hasBlankLines) norm.split(Regex("\n\\s*\n")) else norm.split('\n'))
            .map { it.replace(Regex("\\s*\n\\s*"), " ").replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }
        val blocks = paras.map { p ->
            val isHeading = p.length <= 70 && (CHAPTER.containsMatchIn(p) || (p.length in 3..50 && p == p.uppercase() && p.any { it.isLetter() } && !p.endsWith('.')))
            Block(p.removePrefix("#").removePrefix("#").removePrefix("#").trim(), if (isHeading || p.startsWith("#")) 1 else null)
        }
        return Doc(null, blocks)
    }

    // ------------------------------------------------------------------ HTML / XHTML

    private fun unescape(s: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: " "
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: " "
            else -> mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "rsquo" to "’", "lsquo" to "‘",
                "laquo" to "«", "raquo" to "»", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "hellip" to "…", "ndash" to "–",
                "mdash" to "—", "ecirc" to "ê", "ocirc" to "ô", "icirc" to "î", "ucirc" to "û", "acirc" to "â", "oelig" to "œ", "euro" to "€")[e] ?: " "
        }
    }

    private fun clean(s: String) = unescape(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun parseHtml(html: String): Doc {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { clean(it) }?.takeIf { it.isNotEmpty() }
        val body = Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        val noScript = body.replace(Regex("(?is)<(script|style|nav|head)[^>]*>.*?</\\1>"), " ")
        val blocks = mutableListOf<Block>()
        // Coupe aux balises de bloc ; garde le niveau des titres.
        val pageMark = Regex("(?is)<[^>]+(?:epub:type=\"pagebreak\"|class=\"page[^\"]*\")[^>]*(?:title=\"([^\"]+)\"|id=\"p(?:age)?[-_]?(\\d+)\")[^>]*>")
        var pendingPage: String? = null
        Regex("(?is)(<h([1-6])[^>]*>(.*?)</h\\2>)|(<(?:p|li|div|blockquote|td|dd|dt)[^>]*>(.*?)</(?:p|li|div|blockquote|td|dd|dt)>)|(<br\\s*/?>)|(<[^>]+(?:pagebreak|class=\"page)[^>]*>)")
            .findAll(noScript).forEach { m ->
                when {
                    m.groups[1] != null -> clean(m.groupValues[3]).takeIf { it.isNotEmpty() }?.let { blocks += Block(it, m.groupValues[2].toInt(), pendingPage); pendingPage = null }
                    m.groups[4] != null -> {
                        val inner = m.groupValues[5]
                        pageMark.find(inner)?.let { pm -> pendingPage = pm.groupValues[1].ifEmpty { pm.groupValues[2] }.ifEmpty { null } ?: pendingPage }
                        clean(inner).takeIf { it.isNotEmpty() }?.let { blocks += Block(it, null, pendingPage); pendingPage = null }
                    }
                    m.groups[7] != null -> pageMark.find(m.value)?.let { pm -> pendingPage = pm.groupValues[1].ifEmpty { pm.groupValues[2] }.ifEmpty { null } }
                }
            }
        if (blocks.isEmpty()) return parseTxt(clean(noScript)).copy(title = title)
        // Les <div> imbriqués répètent le texte : on retire les doublons consécutifs.
        val dedup = blocks.fold(mutableListOf<Block>()) { acc, b -> if (acc.lastOrNull()?.text != b.text) acc += b; acc }
        return Doc(title, dedup)
    }

    // ------------------------------------------------------------------ DOCX

    private fun unzip(bytes: ByteArray, want: (String) -> Boolean): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var total = 0L
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && want(e.name)) {
                    val data = z.readBytes()
                    total += data.size
                    if (total > 200_000_000) error("archive trop grosse") // protection contre les archives piégées
                    out[e.name] = data
                }
            }
        }
        return out
    }

    fun parseDocx(bytes: ByteArray): Doc {
        val files = unzip(bytes) { it == "word/document.xml" || it == "docProps/core.xml" }
        val xml = files["word/document.xml"]?.decodeToString() ?: return Doc(null, emptyList())
        val title = files["docProps/core.xml"]?.decodeToString()?.let { Regex("(?s)<dc:title>(.*?)</dc:title>").find(it)?.groupValues?.get(1) }
            ?.let { clean(it) }?.takeIf { it.isNotEmpty() }
        val blocks = Regex("(?s)<w:p[ >].*?</w:p>").findAll(xml).mapNotNull { m ->
            val p = m.value
            val text = Regex("(?s)<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>").findAll(p)
                .joinToString("") { if (it.value.startsWith("<w:t")) unescape(it.groupValues[1]) else " " }.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return@mapNotNull null
            val style = Regex("<w:pStyle w:val=\"([^\"]+)\"").find(p)?.groupValues?.get(1)?.lowercase() ?: ""
            val level = Regex("(?:heading|titre|title)\\s?(\\d)?").find(style)?.let { it.groupValues[1].toIntOrNull() ?: 1 }
            Block(text, level)
        }.toList()
        return Doc(title, blocks)
    }

    // ------------------------------------------------------------------ EPUB

    fun parseEpub(bytes: ByteArray): Doc {
        val files = unzip(bytes) { n -> n.endsWith(".opf") || n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") || n == "META-INF/container.xml" || n == "META-INF/encryption.xml" }
        if (files.keys.any { it.contains("encryption", true) }) return Doc(null, emptyList())
        val opfPath = files["META-INF/container.xml"]?.decodeToString()?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: files.keys.firstOrNull { it.endsWith(".opf") } ?: return Doc(null, emptyList())
        val opf = files[opfPath]?.decodeToString() ?: return Doc(null, emptyList())
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val title = Regex("(?s)<dc:title[^>]*>(.*?)</dc:title>").find(opf)?.groupValues?.get(1)?.let { clean(it) }
        val manifest = Regex("(?s)<item\\b[^>]*>").findAll(opf).mapNotNull { m ->
            val id = Regex("\\bid=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val href = Regex("\\bhref=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
            id to href
        }.toMap()
        val spine = Regex("(?s)<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf).mapNotNull { manifest[it.groupValues[1]] }.toList()
        val blocks = spine.flatMap { href ->
            val path = normalizePath(base + java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            files[path]?.decodeToString()?.let { parseHtml(it).blocks } ?: emptyList()
        }
        return Doc(title, blocks)
    }

    private fun normalizePath(p: String): String {
        val out = ArrayDeque<String>()
        p.split('/').forEach { s -> when (s) { "", "." -> Unit; ".." -> out.removeLastOrNull(); else -> out.addLast(s) } }
        return out.joinToString("/")
    }

    // ------------------------------------------------------------------ découpage en segments

    /** Coupe un paragraphe en morceaux d'environ [SEGMENT] caractères, entre deux phrases. */
    fun split(text: String, max: Int = SEGMENT): List<String> {
        if (text.length <= max) return listOf(text)
        val sentences = Regex("(?<=[.!?…»])\\s+").split(text)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > max) { out += cur.toString(); cur.clear() }
            if (s.length > max * 2) { // phrase immense (PDF sans ponctuation) : coupe aux espaces
                s.chunked(max).forEach { c -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }; out += c }
                continue
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Construit le livre : une piste par segment (uri « text:n », titre = texte à lire). */
    fun toBook(id: String, fallbackTitle: String, doc: Doc, shelf: Shelf = Shelf.TEXT): Book {
        val tracks = mutableListOf<Track>()
        val headings = mutableListOf<Heading>()
        val pages = mutableListOf<PageMark>()
        for (b in doc.blocks) {
            val first = tracks.size
            b.page?.let { pages += PageMark(it, first, 0) }
            if (b.heading != null) headings += Heading(b.text.take(120), b.heading, first, 0)
            split(b.text).forEach { tracks += Track("text:${tracks.size}", it) }
        }
        val title = doc.title?.takeIf { it.isNotBlank() && FrenchText.key(it) !in setOf("untitled", "sans titre", "document") } ?: Daisy.cleanTitle(fallbackTitle)
        return Book(id, title, shelf, tracks, headings, pages)
    }

    /** Analyse selon l'extension (hors PDF). */
    fun parse(name: String, bytes: ByteArray, decode: (ByteArray) -> String): Doc? = when (name.substringAfterLast('.').lowercase()) {
        "txt", "text", "md" -> parseTxt(decode(bytes))
        "htm", "html", "xhtml" -> parseHtml(decode(bytes))
        "docx" -> runCatching { parseDocx(bytes) }.getOrNull()
        "epub" -> runCatching { parseEpub(bytes) }.getOrNull()
        else -> null
    }
}
