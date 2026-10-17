package fr.assistantvocal.llm

import fr.assistantvocal.core.info.Address
import fr.assistantvocal.core.info.CurrentWeather
import fr.assistantvocal.core.info.DailyWeather
import fr.assistantvocal.core.info.Forecast
import fr.assistantvocal.core.info.GeoResult
import fr.assistantvocal.core.info.Geocoder
import fr.assistantvocal.core.info.NewsItem
import fr.assistantvocal.core.info.NewsSource
import fr.assistantvocal.core.info.Place
import fr.assistantvocal.core.info.PlaceCategory
import fr.assistantvocal.core.info.PlacesSource
import fr.assistantvocal.core.info.SearchResult
import fr.assistantvocal.core.info.WeatherSource
import fr.assistantvocal.core.info.WebSearch
import fr.assistantvocal.core.phone.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

internal const val USER_AGENT = "AssistantVocal/0.5 (assistant vocal pour personne aveugle; usage personnel)"

/** Requête GET/POST simple ; null en cas d'erreur réseau ou HTTP. */
internal suspend fun OkHttpClient.fetch(req: Request): String? = withContext(Dispatchers.IO) {
    runCatching {
        newCall(req.newBuilder().header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()
}

private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.num(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
private fun parseJson(body: String?): JsonElement? = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

/** Nettoyage minimal de HTML : balises retirées, entités décodées, espaces simplifiés. */
object Html {
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "rsquo" to "’",
        "lsquo" to "‘", "laquo" to "«", "raquo" to "»", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "hellip" to "…",
        "ndash" to "–", "mdash" to "—", "euro" to "€", "ecirc" to "ê", "ocirc" to "ô", "icirc" to "î", "ucirc" to "û", "acirc" to "â")

    fun unescape(s: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> ENTITIES[e] ?: m.value
        }
    }

    fun text(s: String): String = unescape(s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ").trim()

    /** Paragraphes d'un article (balises <p>), sans menus ni scripts. */
    fun paragraphs(html: String): String {
        val body = Regex("(?is)<article[^>]*>(.*?)</article>").find(html)?.groupValues?.get(1) ?: html
        return Regex("(?is)<p[^>]*>(.*?)</p>").findAll(body).map { text(it.groupValues[1]) }
            .filter { it.length > 40 }.joinToString("\n").take(12_000)
    }
}

// ---------------------------------------------------------------------- adresses (IGN Géoplateforme, BAN)

/** Géocodage de la Géoplateforme IGN (Base Adresse Nationale), gratuit et sans clé. */
class IgnGeocoder(
    private val baseUrl: String = "https://data.geopf.fr/geocodage",
    private val client: OkHttpClient = GeminiProvider.defaultClient(),
) : Geocoder {
    override suspend fun reverse(lat: Double, lon: Double): Address? {
        val url = "${baseUrl.trimEnd('/')}/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", lat.toString()).addQueryParameter("lon", lon.toString()).addQueryParameter("limit", "1").build()
        return parseReverse(client.fetch(Request.Builder().url(url).build()))
    }

    override suspend fun search(query: String, near: GeoPoint?, municipality: Boolean): List<GeoResult> {
        val b = "${baseUrl.trimEnd('/')}/search".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "3")
        if (near != null) b.addQueryParameter("lat", near.lat.toString()).addQueryParameter("lon", near.lon.toString())
        if (municipality) b.addQueryParameter("type", "municipality")
        return parseSearch(client.fetch(Request.Builder().url(b.build()).build()))
    }

    companion object {
        private fun features(body: String?) = (parseJson(body) as? JsonObject)?.get("features") as? JsonArray ?: JsonArray(emptyList())

        fun parseReverse(body: String?): Address? {
            val p = features(body).firstOrNull()?.jsonObject?.get("properties") as? JsonObject ?: return null
            val name = p.str("name") ?: p.str("label") ?: return null
            return Address(name, p.str("city"), p.str("postcode"), p.str("type") == "housenumber" || p.str("housenumber") != null, p.num("distance"))
        }

        fun parseSearch(body: String?): List<GeoResult> = features(body).mapNotNull { f ->
            val o = f.jsonObject
            val p = o["properties"] as? JsonObject ?: return@mapNotNull null
            val c = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return@mapNotNull null
            val lon = c.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = c.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            GeoResult(p.str("label") ?: p.str("name") ?: return@mapNotNull null, lat, lon, p.str("city"))
        }
    }
}

// ---------------------------------------------------------------------- lieux proches (OpenStreetMap, Overpass)

/** Commerces et services autour d'un point, via l'API Overpass d'OpenStreetMap (gratuite, usage raisonnable). */
class OverpassPlaces(
    private val endpoints: List<String> = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"),
    private val client: OkHttpClient = GeminiProvider.defaultClient(),
) : PlacesSource {
    override suspend fun nearby(lat: Double, lon: Double, category: PlaceCategory, radiusM: Int): List<Place> {
        val q = query(lat, lon, category, radiusM)
        for (e in endpoints) {
            val body = client.fetch(Request.Builder().url(e).post(FormBody.Builder().add("data", q).build()).build()) ?: continue
            return parse(body, category)
        }
        throw java.io.IOException("Overpass injoignable")
    }

    companion object {
        fun query(lat: Double, lon: Double, c: PlaceCategory, radiusM: Int): String {
            val filters = c.osm.joinToString("") { f ->
                val (k, v) = f.split('=')
                "nwr(around:$radiusM,$lat,$lon)[\"$k\"=\"$v\"];"
            }
            return "[out:json][timeout:20];($filters);out center tags 30;"
        }

        fun parse(body: String, c: PlaceCategory): List<Place> {
            val els = (parseJson(body) as? JsonObject)?.get("elements") as? JsonArray ?: return emptyList()
            return els.mapNotNull { e ->
                val o = e.jsonObject
                val center = o["center"] as? JsonObject
                val lat = o.num("lat") ?: center?.num("lat") ?: return@mapNotNull null
                val lon = o.num("lon") ?: center?.num("lon") ?: return@mapNotNull null
                val t = o["tags"] as? JsonObject ?: JsonObject(emptyMap())
                val street = listOfNotNull(t.str("addr:housenumber"), t.str("addr:street")).joinToString(" ").takeIf { it.isNotEmpty() }
                Place(t.str("name"), c, lat, lon, street, t.str("addr:city"), t.str("phone") ?: t.str("contact:phone"))
            }
        }
    }
}

// ---------------------------------------------------------------------- météo (Open-Meteo)

/** Prévisions Open-Meteo (modèles Météo-France inclus), gratuites et sans clé pour un usage non commercial. */
class OpenMeteo(
    private val baseUrl: String = "https://api.open-meteo.com",
    private val client: OkHttpClient = GeminiProvider.defaultClient(),
) : WeatherSource {
    override suspend fun forecast(lat: Double, lon: Double): Forecast? {
        val url = "${baseUrl.trimEnd('/')}/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.4f".format(java.util.Locale.ROOT, lat))
            .addQueryParameter("longitude", "%.4f".format(java.util.Locale.ROOT, lon))
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max")
            .addQueryParameter("timezone", "Europe/Paris").addQueryParameter("forecast_days", "7").build()
        return parse(client.fetch(Request.Builder().url(url).build()))
    }

    companion object {
        fun parse(body: String?): Forecast? {
            val root = parseJson(body) as? JsonObject ?: return null
            val cur = (root["current"] as? JsonObject)?.let { c ->
                val t = c.num("temperature_2m") ?: return@let null
                CurrentWeather(t, c.num("apparent_temperature"), c.num("weather_code")?.toInt() ?: -1, c.num("wind_speed_10m"), c.num("precipitation"))
            }
            val d = root["daily"] as? JsonObject
            fun arr(k: String) = (d?.get(k) as? JsonArray)?.map { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val dates = arr("time")
            val days = dates.indices.mapNotNull { i ->
                val date = dates[i]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val max = arr("temperature_2m_max").getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
                val min = arr("temperature_2m_min").getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
                DailyWeather(date, arr("weather_code").getOrNull(i)?.toDoubleOrNull()?.toInt() ?: -1, min, max,
                    arr("precipitation_probability_max").getOrNull(i)?.toDoubleOrNull()?.toInt(), arr("wind_speed_10m_max").getOrNull(i)?.toDoubleOrNull())
            }
            if (cur == null && days.isEmpty()) return null
            return Forecast(cur, days)
        }
    }
}

// ---------------------------------------------------------------------- actualités (RSS / Atom)

class RssNews(private val client: OkHttpClient = GeminiProvider.defaultClient()) : NewsSource {
    override suspend fun headlines(feedUrl: String, max: Int): List<NewsItem> {
        val xml = client.fetch(Request.Builder().url(feedUrl).build()) ?: return emptyList()
        return parse(xml).take(max)
    }

    override suspend fun article(url: String): String? =
        client.fetch(Request.Builder().url(url).build())?.let { Html.paragraphs(it) }?.takeIf { it.isNotBlank() }

    companion object {
        fun parse(xml: String): List<NewsItem> {
            val f = DocumentBuilderFactory.newInstance()
            // Pas d'entités externes (sécurité) ; ignoré si le parseur ne gère pas l'option.
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            f.isNamespaceAware = false
            val doc = runCatching { f.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray())) }.getOrNull() ?: return emptyList()
            fun org.w3c.dom.Element.child(name: String): org.w3c.dom.Element? {
                val nl = getElementsByTagName(name)
                return (0 until nl.length).map { nl.item(it) as org.w3c.dom.Element }.firstOrNull()
            }
            val items = doc.getElementsByTagName("item").let { nl -> (0 until nl.length).map { nl.item(it) as org.w3c.dom.Element } }
                .ifEmpty { doc.getElementsByTagName("entry").let { nl -> (0 until nl.length).map { nl.item(it) as org.w3c.dom.Element } } }
            return items.mapNotNull { e ->
                val title = e.child("title")?.textContent?.let { Html.text(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val summary = (e.child("description") ?: e.child("summary") ?: e.child("content"))?.textContent?.let { Html.text(it) }?.takeIf { it.isNotEmpty() }
                val link = e.child("link")?.let { l -> l.textContent.trim().ifEmpty { l.getAttribute("href") } }?.takeIf { it.startsWith("http") }
                NewsItem(title, summary, link)
            }
        }
    }
}

// ---------------------------------------------------------------------- recherche

/** Wikipédia en français (gratuit, sans clé). */
class WikipediaSearch(
    private val baseUrl: String = "https://fr.wikipedia.org",
    private val client: OkHttpClient = GeminiProvider.defaultClient(),
) : WebSearch {
    override suspend fun search(query: String, max: Int): List<SearchResult> {
        val url = "${baseUrl.trimEnd('/')}/w/rest.php/v1/search/page".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", max.toString()).build()
        val pages = parseSearch(client.fetch(Request.Builder().url(url).build()))
        if (pages.isEmpty()) return emptyList()
        // Résumé complet de la première page.
        val first = pages.first()
        val sumUrl = "${baseUrl.trimEnd('/')}/api/rest_v1/page/summary/".toHttpUrl().newBuilder().addPathSegment(first.title.replace(' ', '_')).build()
        val extract = parseSummary(client.fetch(Request.Builder().url(sumUrl).build()))
        return listOf(first.copy(snippet = extract ?: first.snippet)) + pages.drop(1)
    }

    companion object {
        fun parseSearch(body: String?): List<SearchResult> =
            ((parseJson(body) as? JsonObject)?.get("pages") as? JsonArray).orEmpty().mapNotNull { p ->
                val o = p.jsonObject
                val title = o.str("title") ?: return@mapNotNull null
                val snippet = listOfNotNull(o.str("description"), o.str("excerpt")?.let { Html.text(it) }).joinToString(". ")
                SearchResult(title, snippet, "https://fr.wikipedia.org/wiki/" + (o.str("key") ?: title.replace(' ', '_')))
            }

        fun parseSummary(body: String?): String? = (parseJson(body) as? JsonObject)?.str("extract")
    }
}

/** Tavily (recherche web, clé gratuite ~1000 recherches par mois). */
class TavilySearch(
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://api.tavily.com",
    private val client: OkHttpClient = GeminiProvider.defaultClient(),
) : WebSearch {
    fun isConfigured() = !apiKey().isNullOrBlank()

    override suspend fun search(query: String, max: Int): List<SearchResult> {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val body = buildJsonObject {
            put("query", query); put("max_results", max); put("search_depth", "basic"); put("include_answer", true)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/search").header("Authorization", "Bearer $key").post(body).build()
        return parse(client.fetch(req))
    }

    companion object {
        fun parse(body: String?): List<SearchResult> {
            val root = parseJson(body) as? JsonObject ?: return emptyList()
            val answer = root.str("answer")?.let { SearchResult("Réponse de synthèse", it, null) }
            val results = (root["results"] as? JsonArray).orEmpty().mapNotNull { r ->
                val o = r.jsonObject
                SearchResult(o.str("title") ?: return@mapNotNull null, o.str("content") ?: "", o.str("url"))
            }
            return listOfNotNull(answer) + results
        }
    }
}

/** Tavily si une clé est réglée, sinon Wikipédia ; Wikipédia aussi si Tavily ne renvoie rien. */
class CompositeSearch(private val tavily: TavilySearch, private val wikipedia: WebSearch) : WebSearch {
    override suspend fun search(query: String, max: Int): List<SearchResult> {
        if (tavily.isConfigured()) tavily.search(query, max).takeIf { it.isNotEmpty() }?.let { return it }
        return wikipedia.search(query, max)
    }
}
