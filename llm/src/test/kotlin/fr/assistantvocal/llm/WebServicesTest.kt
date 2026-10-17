package fr.assistantvocal.llm

import fr.assistantvocal.core.info.PlaceCategory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Analyse des réponses des services gratuits (réponses réelles ou conformes à leur documentation). */
class WebServicesTest {

    // Réponse réelle de data.geopf.fr/geocodage/reverse (17/10/2026).
    private val ignReverse = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[4.950022,44.969815]},"properties":{"type":"housenumber","name":"28 Rue de la Liberté","label":"28 Rue de la Liberté 26320 Saint-Marcel-lès-Valence","street":"Rue de la Liberté","postcode":"26320","citycode":"26313","city":"Saint-Marcel-lès-Valence","oldcitycode":null,"oldcity":null,"context":"26, Drôme, Auvergne-Rhône-Alpes","importance":0.58706,"housenumber":"28","id":"26313_0066_00028","x":853727.68,"y":6431964.77,"distance":21,"score":0.9979,"_type":"address"}}]}"""
    private val ignSearch = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[4.835,45.758]},"properties":{"label":"Lyon","score":0.96,"id":"69123","type":"municipality","name":"Lyon","postcode":"69001","citycode":"69123","population":519127,"city":"Lyon","context":"69, Rhône, Auvergne-Rhône-Alpes","_type":"address"}}],"query":"Lyon"}"""

    @Test fun `IGN, adresse et commune`() {
        val a = IgnGeocoder.parseReverse(ignReverse)!!
        assertEquals("28 Rue de la Liberté", a.name)
        assertEquals("Saint-Marcel-lès-Valence", a.city)
        assertTrue(a.isHouseNumber)
        assertEquals(21.0, a.distanceM!!, 0.1)
        val r = IgnGeocoder.parseSearch(ignSearch).single()
        assertEquals("Lyon", r.label); assertEquals(45.758, r.lat, 1e-6); assertEquals(4.835, r.lon, 1e-6)
        assertNull(IgnGeocoder.parseReverse("""{"type":"FeatureCollection","features":[]}"""))
        assertNull(IgnGeocoder.parseReverse("pas du json"))
    }

    @Test fun `IGN, parametres envoyes`() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(ignSearch)); start() }
        val g = IgnGeocoder(server.url("/geocodage").toString())
        assertEquals("Lyon", g.search("Lyon", null, municipality = true).single().city)
        val req = server.takeRequest()
        assertEquals("/geocodage/search?q=Lyon&limit=3&type=municipality", req.path)
        assertTrue(req.getHeader("User-Agent")!!.startsWith("AssistantVocal/"))
        server.shutdown()
    }

    @Test fun `Overpass, requete et lieux`() {
        val q = OverpassPlaces.query(44.93, 4.89, PlaceCategory.BAKERY, 3000)
        assertEquals("""[out:json][timeout:20];(nwr(around:3000,44.93,4.89)["shop"="bakery"];nwr(around:3000,44.93,4.89)["shop"="pastry"];);out center tags 30;""", q)
        val body = """{"version":0.6,"elements":[
            {"type":"node","id":1,"lat":44.94,"lon":4.90,"tags":{"amenity":"pharmacy","name":"Pharmacie du Centre","addr:housenumber":"3","addr:street":"Place de l'Église","phone":"+33 4 75 11 22 33"}},
            {"type":"way","id":2,"center":{"lat":44.95,"lon":4.91},"tags":{"amenity":"pharmacy"}},
            {"type":"node","id":3,"tags":{"amenity":"pharmacy"}}]}"""
        val places = OverpassPlaces.parse(body, PlaceCategory.PHARMACY)
        assertEquals(2, places.size)
        assertEquals("Pharmacie du Centre", places[0].name)
        assertEquals("3 Place de l'Église", places[0].street)
        assertEquals("+33 4 75 11 22 33", places[0].phone)
        assertNull(places[1].name)
        assertEquals(44.95, places[1].lat, 1e-9)
    }

    @Test fun `Open-Meteo, actuel et sept jours`() = runTest {
        val body = """{"latitude":44.94,"longitude":4.9,"timezone":"Europe/Paris",
            "current":{"time":"2026-10-17T14:30","interval":900,"temperature_2m":14.2,"apparent_temperature":12.9,"weather_code":3,"wind_speed_10m":11.5,"precipitation":0.0},
            "daily":{"time":["2026-10-17","2026-10-18"],"weather_code":[3,61],"temperature_2m_max":[17.1,15.0],"temperature_2m_min":[8.2,9.1],
            "precipitation_probability_max":[10,80],"wind_speed_10m_max":[15.0,30.2]}}"""
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(body)); start() }
        val f = OpenMeteo(server.url("/").toString()).forecast(44.9333, 4.8917)!!
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/v1/forecast?latitude=44.9333&longitude=4.8917&current=") && path.contains("timezone=Europe%2FParis"))
        assertEquals(14.2, f.current!!.temperature, 1e-9)
        assertEquals(3, f.current!!.code)
        assertEquals(2, f.days.size)
        assertEquals(LocalDate.of(2026, 10, 18), f.days[1].date)
        assertEquals(61, f.days[1].code)
        assertEquals(80, f.days[1].rainChance)
        assertNull(OpenMeteo.parse("""{"error":true,"reason":"Latitude must be in range"}"""))
        server.shutdown()
    }

    @Test fun `RSS France Info et Atom`() {
        val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>Franceinfo - Les Titres</title>
<item><title><![CDATA[Grève SNCF : le trafic perturbé jeudi]]></title><link>https://www.francetvinfo.fr/economie/greve_1.html</link>
<description><![CDATA[<p>La circulation sera &laquo;&nbsp;très perturbée&nbsp;&raquo; jeudi.</p><img src="x.jpg"/>]]></description></item>
<item><title>Météo : des orages attendus</title><link>https://www.francetvinfo.fr/meteo/orages_2.html</link></item>
</channel></rss>"""
        val items = RssNews.parse(rss)
        assertEquals(2, items.size)
        assertEquals("Grève SNCF : le trafic perturbé jeudi", items[0].title)
        assertEquals("La circulation sera « très perturbée » jeudi.", items[0].summary)
        assertEquals("https://www.francetvinfo.fr/economie/greve_1.html", items[0].link)
        assertNull(items[1].summary)
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Titre Atom</title><link href="https://exemple.fr/a"/><summary>Résumé</summary></entry></feed>"""
        assertEquals("https://exemple.fr/a", RssNews.parse(atom).single().link)
        assertTrue(RssNews.parse("<html>pas un flux").isEmpty())
        // Une entité externe n'est jamais résolue.
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title></item></channel></rss>"""
        assertTrue(RssNews.parse(xxe).none { it.title.contains("root:") })
    }

    @Test fun `texte d'un article`() {
        val html = """<html><head><script>var a = "<p>pas ça</p>";</script></head><body><nav><p>Menu</p></nav>
            <article><h1>Titre</h1><p>Le premier paragraphe de l'article est assez long pour être gardé.</p>
            <p>Court.</p><p>Deuxième paragraphe, lui aussi assez long pour être conservé&nbsp;!</p></article></body></html>"""
        assertEquals("Le premier paragraphe de l'article est assez long pour être gardé.\nDeuxième paragraphe, lui aussi assez long pour être conservé !",
            Html.paragraphs(html))
        assertEquals("C'est l'été — 5 €", Html.unescape("C&#39;est l&#x27;&eacute;t&eacute; &mdash; 5 &euro;"))
    }

    @Test fun `Wikipedia, recherche puis resume`() = runTest {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"pages":[{"id":1,"key":"Hernie_discale","title":"Hernie discale","excerpt":"La <span class=\"searchmatch\">hernie</span> discale est…","description":"pathologie de la colonne"},{"id":2,"key":"Lombalgie","title":"Lombalgie","excerpt":"douleur","description":null}]}"""))
            enqueue(MockResponse().setBody("""{"type":"standard","title":"Hernie discale","extract":"Une hernie discale est une saillie du disque intervertébral. Elle provoque des douleurs."}"""))
            start()
        }
        val r = WikipediaSearch(server.url("/").toString()).search("hernie discale", 3)
        assertEquals("/w/rest.php/v1/search/page?q=hernie%20discale&limit=3", server.takeRequest().path)
        assertEquals("/api/rest_v1/page/summary/Hernie_discale", server.takeRequest().path)
        assertEquals("Une hernie discale est une saillie du disque intervertébral. Elle provoque des douleurs.", r[0].snippet)
        assertEquals("Lombalgie", r[1].title)
        server.shutdown()
    }

    @Test fun `Tavily avec cle, Wikipedia sinon`() = runTest {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"query":"q","answer":"La déchetterie ouvre à 9 h.","results":[{"title":"Mairie","url":"https://mairie.fr","content":"Horaires : 9 h - 12 h","score":0.9}]}"""))
            start()
        }
        var key: String? = null
        val tavily = TavilySearch({ key }, server.url("/").toString())
        val wiki = object : fr.assistantvocal.core.info.WebSearch {
            override suspend fun search(query: String, max: Int) = listOf(fr.assistantvocal.core.info.SearchResult("Wikipédia", "w", null))
        }
        val s = CompositeSearch(tavily, wiki)
        assertEquals("Wikipédia", s.search("q", 3).single().title)
        key = "tvly-test"
        val r = s.search("horaires déchetterie", 3)
        assertEquals(listOf("Réponse de synthèse", "Mairie"), r.map { it.title })
        val req = server.takeRequest()
        assertEquals("Bearer tvly-test", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"query\":\"horaires déchetterie\""))
        server.shutdown()
    }
}

/** Appels réels (lancés seulement avec LIVE_NET=1). */
class LiveWebServicesTest {
    @Test fun `IGN reel`() = runTest {
        if (System.getenv("LIVE_NET") != "1") return@runTest
        val g = IgnGeocoder()
        val a = g.reverse(44.9698, 4.9500)
        println("IGN reverse : $a")
        assertTrue(a!!.city!!.startsWith("Saint-Marcel"))
        val r = g.search("Saint-Étienne", null, municipality = true)
        println("IGN search : $r")
        assertEquals("Saint-Étienne", r.first().city)
    }
}
