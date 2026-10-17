package fr.assistantvocal.core.info

import fr.assistantvocal.core.phone.GeoPoint
import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.text.FrenchText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------- adresses et lieux

/** Adresse trouvée autour d'un point (géocodage inverse). [distanceM] : distance entre le point et l'adresse. */
data class Address(
    val name: String,
    val city: String?,
    val postcode: String? = null,
    val isHouseNumber: Boolean = false,
    val distanceM: Double? = null,
)

/** Résultat d'une recherche d'adresse ou de commune. */
data class GeoResult(val label: String, val lat: Double, val lon: Double, val city: String? = null)

interface Geocoder {
    suspend fun reverse(lat: Double, lon: Double): Address?

    /** Recherche d'adresse (ou de commune si [municipality]) ; [near] privilégie les résultats proches. */
    suspend fun search(query: String, near: GeoPoint? = null, municipality: Boolean = false): List<GeoResult>
}

/**
 * Catégories de lieux reconnues à la voix. [osm] : filtres OpenStreetMap « clé=valeur ».
 * [feminine] sert à dire « la plus proche » ou « le plus proche ».
 */
enum class PlaceCategory(val label: String, val feminine: Boolean, val words: List<String>, val osm: List<String>) {
    PHARMACY("pharmacie", true, listOf("pharmacie", "pharmacies"), listOf("amenity=pharmacy")),
    BAKERY("boulangerie", true, listOf("boulangerie", "boulangeries", "boulanger", "patisserie"), listOf("shop=bakery", "shop=pastry")),
    DOCTOR("cabinet médical", false, listOf("medecin", "medecins", "docteur", "generaliste", "cabinet medical", "maison de sante"), listOf("amenity=doctors", "healthcare=doctor", "amenity=clinic")),
    HOSPITAL("hôpital", false, listOf("hopital", "urgences", "clinique"), listOf("amenity=hospital")),
    DENTIST("dentiste", false, listOf("dentiste"), listOf("amenity=dentist")),
    PHYSIO("kinésithérapeute", false, listOf("kine", "kinesitherapeute", "kinesi"), listOf("healthcare=physiotherapist")),
    POST("poste", true, listOf("poste", "bureau de poste", "la poste"), listOf("amenity=post_office")),
    TOWN_HALL("mairie", true, listOf("mairie"), listOf("amenity=townhall")),
    SHOP("épicerie ou supérette", true, listOf("supermarche", "epicerie", "superette", "magasin", "commerce", "courses", "intermarche", "carrefour", "leclerc", "super u", "casino", "spar", "proxi"), listOf("shop=supermarket", "shop=convenience")),
    BUTCHER("boucherie", true, listOf("boucherie", "boucher", "charcuterie"), listOf("shop=butcher")),
    BANK("distributeur de billets", false, listOf("distributeur", "banque", "retirer de l argent", "dab", "distributeur de billets"), listOf("amenity=atm", "amenity=bank")),
    TOBACCO("bureau de tabac", false, listOf("tabac", "bureau de tabac", "buraliste", "presse", "journaux"), listOf("shop=tobacco", "shop=newsagent")),
    CAFE("café", false, listOf("cafe", "bar", "bistrot"), listOf("amenity=cafe", "amenity=bar", "amenity=pub")),
    RESTAURANT("restaurant", false, listOf("restaurant", "restaurants"), listOf("amenity=restaurant")),
    CHURCH("église", true, listOf("eglise", "chapelle"), listOf("amenity=place_of_worship")),
    BUS_STOP("arrêt de bus", false, listOf("arret de bus", "arret de car", "arret", "abribus", "car"), listOf("highway=bus_stop")),
    STATION("gare", true, listOf("gare", "train"), listOf("railway=station", "railway=halt")),
    FUEL("station-service", true, listOf("station service", "station essence", "essence", "carburant"), listOf("amenity=fuel")),
    HAIRDRESSER("coiffeur", false, listOf("coiffeur", "coiffeuse"), listOf("shop=hairdresser")),
    CEMETERY("cimetière", false, listOf("cimetiere"), listOf("landuse=cemetery", "amenity=grave_yard")),
    ;

    val closest get() = if (feminine) "la $label la plus proche" else "le $label le plus proche"

    companion object {
        /** Catégorie citée dans la phrase (mots sans accents), la plus longue expression l'emporte. */
        fun find(text: String): PlaceCategory? {
            val k = " " + FrenchText.key(text) + " "
            return entries.flatMap { c -> c.words.map { w -> c to w } }
                .filter { (_, w) -> k.contains(" $w ") }
                .maxByOrNull { (_, w) -> w.length }?.first
        }
    }
}

data class Place(
    val name: String?,
    val category: PlaceCategory,
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val city: String? = null,
    val phone: String? = null,
)

interface PlacesSource {
    suspend fun nearby(lat: Double, lon: Double, category: PlaceCategory, radiusM: Int): List<Place>
}

/** Boussole du téléphone : direction vers laquelle pointe le haut du téléphone, en degrés depuis le nord. */
interface HeadingSource { suspend fun heading(): Float? }

/** Guidage piéton par une application de cartes (Google Maps). */
interface Navigator { fun walkTo(lat: Double, lon: Double, label: String): Boolean }

// ---------------------------------------------------------------------- météo

data class CurrentWeather(val temperature: Double, val feelsLike: Double?, val code: Int, val windKmh: Double?, val precipitationMm: Double?)
data class DailyWeather(val date: LocalDate, val code: Int, val min: Double, val max: Double, val rainChance: Int?, val windMaxKmh: Double?)
data class Forecast(val current: CurrentWeather?, val days: List<DailyWeather>)

interface WeatherSource { suspend fun forecast(lat: Double, lon: Double): Forecast? }

// ---------------------------------------------------------------------- actualités et recherche

data class NewsItem(val title: String, val summary: String?, val link: String?)

interface NewsSource {
    suspend fun headlines(feedUrl: String, max: Int = 20): List<NewsItem>

    /** Texte principal d'un article (paragraphes), ou null. */
    suspend fun article(url: String): String?
}

data class SearchResult(val title: String, val snippet: String, val url: String?)

interface WebSearch {
    suspend fun search(query: String, max: Int = 3): List<SearchResult>
}

// ---------------------------------------------------------------------- calculs géographiques

object Geo {
    private const val EARTH_M = 6_371_000.0

    fun distanceM(a: GeoPoint, lat: Double, lon: Double): Double {
        val dLat = Math.toRadians(lat - a.lat)
        val dLon = Math.toRadians(lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Cap de [a] vers le point, en degrés depuis le nord (0..360). */
    fun bearing(a: GeoPoint, lat: Double, lon: Double): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(lat)
        val dl = Math.toRadians(lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** « 350 mètres », « 1,2 kilomètre », « 15 kilomètres ». */
    fun speakDistance(m: Double): String = when {
        m < 20 -> "quelques mètres"
        m < 1000 -> "${((m / 10).roundToInt() * 10)} mètres"
        m < 10_000 -> {
            val km = (m / 100).roundToInt() / 10.0
            val s = if (km == km.toInt().toDouble()) km.toInt().toString() else km.toString().replace('.', ',')
            "$s kilomètre" + if (km >= 2) "s" else ""
        }
        else -> "${(m / 1000).roundToInt()} kilomètres"
    }

    private val CARDINALS = listOf("au nord", "au nord-est", "à l'est", "au sud-est", "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest")

    fun cardinal(bearing: Double): String = CARDINALS[(((bearing + 22.5) % 360) / 45).toInt()]

    /** Direction relative en heures d'horloge (12 h = devant), d'après la boussole du téléphone. */
    fun clock(bearing: Double, heading: Double): String {
        val rel = ((bearing - heading) % 360 + 360) % 360
        val h = ((rel / 30).roundToInt() % 12).let { if (it == 0) 12 else it }
        return when (h) {
            12 -> "droit devant vous"
            3 -> "à 3 heures, sur votre droite"
            9 -> "à 9 heures, sur votre gauche"
            6 -> "à 6 heures, derrière vous"
            1 -> "à 1 heure"
            else -> "à $h heures"
        }
    }

    /** Direction parlée : en heures d'horloge si la boussole est disponible, sinon en points cardinaux. */
    fun direction(from: GeoPoint, lat: Double, lon: Double, heading: Float?): String {
        val b = bearing(from, lat, lon)
        return if (heading != null) clock(b, heading.toDouble()) + ", téléphone tenu devant vous" else cardinal(b)
    }

    fun mapsLink(lat: Double, lon: Double) = "https://maps.google.com/?q=%.5f,%.5f".format(java.util.Locale.ROOT, lat, lon)
}

// ---------------------------------------------------------------------- lieux enregistrés

@Serializable
data class SavedPlace(val name: String, val lat: Double, val lon: Double, val address: String? = null)

/** Lieux enregistrés à la voix (« enregistre ici comme maison »), stockés sur le téléphone. */
class SavedPlaces(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(SavedPlace.serializer())

    fun all(): List<SavedPlace> = store.getString(KEY)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    fun save(place: SavedPlace) {
        val k = keyOf(place.name)
        store.putString(KEY, json.encodeToString(ser, all().filter { keyOf(it.name) != k } + place.copy(name = clean(place.name))))
    }

    fun remove(name: String): Boolean {
        val before = all()
        val after = before.filter { keyOf(it.name) != keyOf(name) }
        store.putString(KEY, json.encodeToString(ser, after))
        return after.size != before.size
    }

    fun find(name: String): SavedPlace? = all().firstOrNull { keyOf(it.name) == keyOf(name) }

    /** Lieu enregistré à moins de [maxM] mètres. */
    fun near(p: GeoPoint, maxM: Double = 60.0): SavedPlace? =
        all().map { it to Geo.distanceM(p, it.lat, it.lon) }.filter { it.second <= maxM }.minByOrNull { it.second }?.first

    companion object {
        private const val KEY = "places.saved"
        private val ARTICLE = Regex("^(?:la |le |les |l |l'|chez |ma |mon |mes )+")

        fun clean(name: String) = name.trim().trimEnd('.', '!', '?').replace(ARTICLE, "").trim()
        fun keyOf(name: String) = FrenchText.key(name).replace(ARTICLE, "").trim()
    }
}
