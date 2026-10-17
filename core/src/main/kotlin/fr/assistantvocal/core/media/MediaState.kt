package fr.assistantvocal.core.media

import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.text.FrenchText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Lecteur audio (ExoPlayer sur Android, faux lecteur dans les tests). Toutes les méthodes rendent la main tout de suite. */
interface AudioPlayer {
    /** Charge et lance les pistes à partir de [start]. [live] : radio (pas de position). */
    fun play(tracks: List<Track>, start: Pos, speed: Float, live: Boolean = false)
    fun pause()
    fun resume()
    fun stop()
    fun seek(pos: Pos)
    fun setSpeed(speed: Float)
    fun isPlaying(): Boolean
    /** Position actuelle, null si rien n'est chargé. */
    fun position(): Pos?
    /** Durée de la piste si le lecteur la connaît. */
    fun durationOf(track: Int): Long?
    /** Appelé quand la dernière piste se termine. */
    var onFinished: (() -> Unit)?
}

@Serializable data class SavedPos(val track: Int, val ms: Long)
@Serializable data class Bookmark(val number: Int, val track: Int, val ms: Long)
@Serializable data class Station(val name: String, val url: String)
@Serializable data class PodcastSub(val title: String, val feedUrl: String, val author: String? = null)
@Serializable data class TextNote(val text: String, val createdMs: Long)
@Serializable data class DownloadedEpisode(
    val feed: String, val show: String, val title: String, val url: String, val localUri: String,
    val publishedMs: Long? = null, val durationMs: Long? = null,
)

/** Téléchargement des épisodes de podcast (stockage privé de l'app). */
interface PodcastDownloader {
    /** Wifi ou réseau non facturé. */
    fun unmetered(): Boolean
    /** Télécharge et renvoie l'adresse locale, ou null. */
    suspend fun download(url: String, fileName: String): String?
    fun delete(localUri: String)
    fun exists(localUri: String): Boolean
}

/** Mémoire du lecteur : positions et signets par livre, radios, podcasts, notes, liste de courses. */
class MediaState(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> load(key: String, ser: kotlinx.serialization.KSerializer<T>, default: T): T =
        store.getString(key)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: default
    private fun <T> save(key: String, ser: kotlinx.serialization.KSerializer<T>, v: T) = store.putString(key, json.encodeToString(ser, v))

    private val posSer = MapSerializer(String.serializer(), SavedPos.serializer())
    private val bmSer = MapSerializer(String.serializer(), ListSerializer(Bookmark.serializer()))
    private val durSer = MapSerializer(String.serializer(), Long.serializer())

    fun position(bookId: String): Pos? = load(K_POS, posSer, emptyMap())[bookId]?.let { Pos(it.track, it.ms) }
    fun savePosition(bookId: String, p: Pos) = save(K_POS, posSer, load(K_POS, posSer, emptyMap()) + (bookId to SavedPos(p.track, p.ms)))

    fun bookmarks(bookId: String): List<Bookmark> = load(K_BM, bmSer, emptyMap())[bookId].orEmpty()
    fun setBookmarks(bookId: String, list: List<Bookmark>) = save(K_BM, bmSer, load(K_BM, bmSer, emptyMap()) + (bookId to list))

    /** Durées apprises par le lecteur (livres MP3 sans durée connue), clé « livre|piste ». */
    fun duration(bookId: String, track: Int): Long? = load(K_DUR, durSer, emptyMap())["$bookId|$track"]
    fun setDuration(bookId: String, track: Int, ms: Long) {
        val m = load(K_DUR, durSer, emptyMap())
        if (m["$bookId|$track"] != ms) save(K_DUR, durSer, m + ("$bookId|$track" to ms))
    }

    var lastBookId: String?
        get() = store.getString(K_LAST_BOOK)
        set(v) = store.putString(K_LAST_BOOK, v)

    /** « book », « radio », « podcast », « music », « memos ». */
    var lastKind: String?
        get() = store.getString(K_LAST_KIND)
        set(v) = store.putString(K_LAST_KIND, v)

    var bookSpeed: Float
        get() = store.getFloat(K_SPEED, 1.0f)
        set(v) = store.putFloat(K_SPEED, v.coerceIn(0.5f, 3.0f))

    var lastStation: Station?
        get() = store.getString(K_STATION)?.let { runCatching { json.decodeFromString(Station.serializer(), it) }.getOrNull() }
        set(v) = store.putString(K_STATION, v?.let { json.encodeToString(Station.serializer(), it) })

    var favoriteStations: List<Station>
        get() = load(K_FAV, ListSerializer(Station.serializer()), emptyList())
        set(v) = save(K_FAV, ListSerializer(Station.serializer()), v)

    var podcasts: List<PodcastSub>
        get() = load(K_PODCASTS, ListSerializer(PodcastSub.serializer()), emptyList())
        set(v) = save(K_PODCASTS, ListSerializer(PodcastSub.serializer()), v)

    /** Dernier épisode écouté par podcast (adresse du flux → adresse de l'épisode). */
    fun lastEpisode(feed: String): String? = load(K_LAST_EP, MapSerializer(String.serializer(), String.serializer()), emptyMap())[feed]
    fun setLastEpisode(feed: String, episodeUrl: String) {
        val ser = MapSerializer(String.serializer(), String.serializer())
        save(K_LAST_EP, ser, load(K_LAST_EP, ser, emptyMap()) + (feed to episodeUrl))
    }
    var lastPodcastFeed: String?
        get() = store.getString(K_LAST_FEED)
        set(v) = store.putString(K_LAST_FEED, v)

    var notes: List<TextNote>
        get() = load(K_NOTES, ListSerializer(TextNote.serializer()), emptyList())
        set(v) = save(K_NOTES, ListSerializer(TextNote.serializer()), v)

    var downloads: List<DownloadedEpisode>
        get() = load(K_DOWNLOADS, ListSerializer(DownloadedEpisode.serializer()), emptyList())
        set(v) = save(K_DOWNLOADS, ListSerializer(DownloadedEpisode.serializer()), v)

    /** Téléchargement automatique en wifi des derniers épisodes (comme le Victor). */
    var autoDownload: Boolean
        get() = store.getBoolean(K_AUTO_DL, true)
        set(v) = store.putBoolean(K_AUTO_DL, v)

    /** Nombre d'épisodes gardés par podcast (3 par défaut, comme le Victor). */
    var keepEpisodes: Int
        get() = store.getInt(K_KEEP, 3).coerceIn(1, 10)
        set(v) = store.putInt(K_KEEP, v.coerceIn(1, 10))

    var shoppingList: List<String>
        get() = load(K_SHOP, ListSerializer(String.serializer()), emptyList())
        set(v) = save(K_SHOP, ListSerializer(String.serializer()), v)

    companion object {
        private const val K_POS = "media.positions"
        private const val K_BM = "media.bookmarks"
        private const val K_DUR = "media.durations"
        private const val K_LAST_BOOK = "media.lastBook"
        private const val K_LAST_KIND = "media.lastKind"
        private const val K_SPEED = "media.bookSpeed"
        private const val K_STATION = "media.lastStation"
        private const val K_FAV = "media.favoriteStations"
        private const val K_PODCASTS = "media.podcasts"
        private const val K_LAST_EP = "media.lastEpisode"
        private const val K_LAST_FEED = "media.lastFeed"
        private const val K_NOTES = "notes.text"
        private const val K_SHOP = "notes.shopping"
        private const val K_DOWNLOADS = "media.downloads"
        private const val K_AUTO_DL = "media.autoDownload"
        private const val K_KEEP = "media.keepEpisodes"
    }
}

/** Radios de Radio France (flux officiels) ; les autres sont cherchées dans Radio Browser. */
object RadioCatalog {
    private const val RF = "https://icecast.radiofrance.fr"
    val BUILT_IN = listOf(
        Station("France Inter", "$RF/franceinter-midfi.mp3"),
        Station("France Info", "$RF/franceinfo-midfi.mp3"),
        Station("France Culture", "$RF/franceculture-midfi.mp3"),
        Station("France Musique", "$RF/francemusique-midfi.mp3"),
        Station("FIP", "$RF/fip-midfi.mp3"),
        Station("Mouv'", "$RF/mouv-midfi.mp3"),
    )
    private val ALIASES = mapOf(
        "inter" to "France Inter", "franceinter" to "France Inter", "info" to "France Info", "franceinfo" to "France Info",
        "culture" to "France Culture", "musique" to "France Musique", "fip" to "FIP", "mouv" to "Mouv'",
    )

    fun find(name: String): Station? {
        val k = FrenchText.key(name).removePrefix("la radio ").removePrefix("radio ").trim()
        BUILT_IN.firstOrNull { FrenchText.key(it.name) == k }?.let { return it }
        val short = k.removePrefix("france ").replace(" ", "")
        return ALIASES[short]?.let { n -> BUILT_IN.first { it.name == n } }?.takeIf { k.startsWith("france") || short in setOf("fip", "mouv") }
    }
}

/** Recherche de radios (Radio Browser). */
interface RadioSearch { suspend fun search(name: String): List<Station> }

data class PodcastShow(val title: String, val author: String?, val feedUrl: String)
data class Episode(val title: String, val url: String, val publishedMs: Long?, val durationMs: Long?)

interface PodcastDirectory {
    suspend fun search(term: String): List<PodcastShow>
    suspend fun episodes(feedUrl: String): List<Episode>
}

/** Musique : fichiers du téléphone, et applications de musique installées. */
interface MusicSource {
    /** Morceaux locaux correspondant à [query] (artiste, titre, album), ou tous si null. */
    suspend fun local(query: String?): List<Track>
    /** Demande à une application de musique (YouTube Music, Spotify…) de jouer [query]. */
    fun playExternal(query: String): Boolean
    /** Commande la lecture d'une autre application : « pause », « play », « next », « previous ». Vrai si une app a répondu. */
    fun controlExternal(action: String): Boolean
}

data class Memo(val uri: String, val createdMs: Long, val durationMs: Long?)

/** Mémos vocaux enregistrés par l'assistant. */
interface MemoStore {
    fun list(): List<Memo>
    fun delete(memo: Memo): Boolean
}
