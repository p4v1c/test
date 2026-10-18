package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.info.Address
import fr.assistantvocal.core.info.Calculator
import fr.assistantvocal.core.info.CurrentWeather
import fr.assistantvocal.core.info.DailyWeather
import fr.assistantvocal.core.info.Forecast
import fr.assistantvocal.core.info.Geo
import fr.assistantvocal.core.info.GeoResult
import fr.assistantvocal.core.info.Geocoder
import fr.assistantvocal.core.info.HeadingSource
import fr.assistantvocal.core.info.Navigator
import fr.assistantvocal.core.info.NewsItem
import fr.assistantvocal.core.info.NewsSource
import fr.assistantvocal.core.info.Place
import fr.assistantvocal.core.info.PlaceCategory
import fr.assistantvocal.core.info.PlacesSource
import fr.assistantvocal.core.info.SavedPlaces
import fr.assistantvocal.core.info.SearchResult
import fr.assistantvocal.core.info.WeatherSource
import fr.assistantvocal.core.info.WeatherSpeech
import fr.assistantvocal.core.info.WebSearch
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.GeoPoint
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.router.Calculate
import fr.assistantvocal.core.router.InfoRouter
import fr.assistantvocal.core.router.Intent
import fr.assistantvocal.core.router.LocalRouter
import fr.assistantvocal.core.router.Navigate
import fr.assistantvocal.core.router.Nearby
import fr.assistantvocal.core.router.News
import fr.assistantvocal.core.router.NewsDetail
import fr.assistantvocal.core.router.NewsMore
import fr.assistantvocal.core.router.PlaceInfo
import fr.assistantvocal.core.router.ReadMessages
import fr.assistantvocal.core.router.SavePlace
import fr.assistantvocal.core.router.SearchWeb
import fr.assistantvocal.core.router.SendSms
import fr.assistantvocal.core.router.ShareLocation
import fr.assistantvocal.core.router.Vision
import fr.assistantvocal.core.router.Weather
import fr.assistantvocal.core.router.WhereAmI
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.skills.CommunicationSkill
import fr.assistantvocal.core.skills.InfoServices
import fr.assistantvocal.core.skills.InfoSkill
import fr.assistantvocal.core.skills.ReminderSkill
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.ToolRegistry
import fr.assistantvocal.core.llm.LlmPart
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Réseau simulé : adresses, lieux, météo, actualités, recherche, cartes, boussole. */
class FakeInfo : Geocoder, PlacesSource, WeatherSource, NewsSource, WebSearch, Navigator, HeadingSource {
    var address: Address? = Address("12 Rue des Lilas", "Saint-Marcel", "26320", isHouseNumber = true, distanceM = 8.0)
    val searches = mutableListOf<Pair<String, Boolean>>()
    var geoResults = listOf(GeoResult("Lyon", 45.758, 4.835, "Lyon"))
    val placeQueries = mutableListOf<Pair<PlaceCategory, Int>>()
    var places: (Int) -> List<Place> = { r ->
        if (r < 10_000) emptyList() else listOf(
            Place("Pharmacie de la Gare", PlaceCategory.PHARMACY, 44.96, 4.93, "avenue de la Gare", "Valence"),
            Place("Pharmacie du Centre", PlaceCategory.PHARMACY, 44.9413, 4.9017, "place de l'Église", "Saint-Marcel", "04 75 11 22 33"),
        )
    }
    var forecast: Forecast? = Forecast(
        CurrentWeather(14.4, 12.0, 3, 12.0, 0.0),
        listOf(
            DailyWeather(LocalDate.of(2026, 10, 17), 3, 8.2, 17.1, 10, 20.0),
            DailyWeather(LocalDate.of(2026, 10, 18), 63, 9.0, 13.6, 80, 35.0),
            DailyWeather(LocalDate.of(2026, 10, 19), 1, 4.0, 16.0, 0, 10.0),
            DailyWeather(LocalDate.of(2026, 10, 20), 2, -1.0, 12.0, 5, 10.0),
            DailyWeather(LocalDate.of(2026, 10, 21), 95, 10.0, 22.0, 70, 65.0),
            DailyWeather(LocalDate.of(2026, 10, 22), 0, 6.0, 18.0, 0, 10.0),
            DailyWeather(LocalDate.of(2026, 10, 23), 0, 6.0, 19.0, 0, 10.0),
        ),
    )
    val weatherAt = mutableListOf<Pair<Double, Double>>()
    val feeds = mutableListOf<String>()
    var items = (1..7).map { NewsItem("Titre numéro $it", "Résumé détaillé de l'information numéro $it.", "https://exemple.fr/$it") }
    var articleText: String? = "Premier paragraphe de l'article. ".repeat(20)
    var results = listOf(SearchResult("Déchetterie de Saint-Marcel", "La déchetterie est ouverte du lundi au samedi de 9 h à 12 h. Elle est fermée le dimanche.", "https://mairie.fr"))
    val walks = mutableListOf<String>()
    var mapsInstalled = true
    var compass: Float? = null

    override suspend fun reverse(lat: Double, lon: Double) = address
    override suspend fun search(query: String, near: GeoPoint?, municipality: Boolean): List<GeoResult> { searches += query to municipality; return geoResults }
    override suspend fun nearby(lat: Double, lon: Double, category: PlaceCategory, radiusM: Int): List<Place> { placeQueries += category to radiusM; return places(radiusM) }
    override suspend fun forecast(lat: Double, lon: Double): Forecast? { weatherAt += lat to lon; return forecast }
    override suspend fun headlines(feedUrl: String, max: Int): List<NewsItem> { feeds += feedUrl; return items }
    override suspend fun article(url: String) = articleText
    override suspend fun search(query: String, max: Int) = results
    override fun walkTo(lat: Double, lon: Double, label: String): Boolean { if (mapsInstalled) walks += label; return mapsInstalled }
    override suspend fun heading() = compass
}

class Phase5ScenarioTest {
    private val store = InMemoryStore()
    private val settings = AssistantSettings(store).apply { name = "Hector"; ownerName = "Jean" }
    private val device = FakeDevice()
    private val phone = FakePhone().apply { point = GeoPoint(44.9333, 4.8917, 12f) }
    private val net = FakeInfo()
    private val dialog = Dialog { 0L }
    private val people = PeopleStore(store)
    private val comms = CommunicationSkill(ContactDirectory(phone, people), people, phone, phone, phone, phone, phone, store, device, dialog)
    private val reminders = ReminderSkill(ReminderStore(store), MedicationLog(store), phone, store, device, dialog)
    private val llm = ScriptedLlm()
    private var aiAllowed = true
    private val info = InfoSkill(
        InfoServices(phone, net, net, net, net, net, net, net), SavedPlaces(store), dialog, device, comms,
        llm, { aiAllowed && device.online }, { "système" }, { settings.newsLocalFeed }, { 0L },
    )
    private val a = Assistant(settings, device, llm,
        ToolRegistry(CoreTools.all(settings, device) + CoreTools.phase3() + CoreTools.phase5(info)),
        nowMillis = { 0L }, skills = Skills(dialog, comms, reminders, info = info))

    private suspend fun say(t: String) = a.handle(t).speech

    // ------------------------------------------------------------------ où suis-je

    @Test fun `ou suis-je donne l'adresse`() = runTest {
        assertEquals("Vous êtes près du 12 rue des Lilas, à Saint-Marcel.", say("Où suis-je ?"))
        net.address = Address("Les Granges", "Saint-Marcel", isHouseNumber = false, distanceM = 40.0)
        assertEquals("Vous êtes au lieu-dit Les Granges, à Saint-Marcel.", say("je suis où"))
        net.address = Address("Route de Valence", "Saint-Marcel", distanceM = 400.0)
        phone.point = GeoPoint(44.9333, 4.8917, 250f)
        assertEquals("Vous êtes à environ 400 mètres de la route de Valence, à Saint-Marcel. Position approximative, à 250 mètres près.", say("quelle est mon adresse"))
    }

    @Test fun `ou suis-je sans position ni internet`() = runTest {
        phone.point = null
        assertTrue(say("où suis-je").startsWith("Je n'arrive pas à trouver votre position"))
        phone.point = GeoPoint(44.9333, 4.8917, 12f)
        device.online = false
        assertEquals("Je n'ai pas internet pour l'instant. Je ne peux pas trouver l'adresse.", say("où suis-je"))
    }

    // ------------------------------------------------------------------ lieux enregistrés

    @Test fun `enregistrer la maison puis distance et direction`() = runTest {
        val q = say("enregistre ici comme maison")
        assertEquals("J'enregistre votre position actuelle, 12 Rue des Lilas, Saint-Marcel, sous le nom « maison » ? Répondez oui ou non.", q)
        assertTrue(say("oui").startsWith("C'est enregistré."))
        // Hors ligne, à la maison : il le dit sans internet.
        device.online = false
        assertEquals("Vous êtes à la maison.", say("où suis-je ?"))
        device.online = true
        // 500 mètres plus au nord.
        phone.point = GeoPoint(44.9378, 4.8917, 10f)
        assertEquals("La maison est à 500 mètres, au sud, à vol d'oiseau.", say("à quelle distance est la maison ?"))
        net.compass = 90f // téléphone tourné vers l'est : le sud est à droite
        assertEquals("La maison est à 500 mètres, à 3 heures, sur votre droite, téléphone tenu devant vous, à vol d'oiseau.", say("où est la maison"))
        assertEquals("Vos lieux enregistrés : maison.", say("quels sont mes lieux ?"))
        // Guidage direct vers un lieu enregistré.
        assertTrue(say("emmène-moi à la maison").startsWith("J'ouvre Google Maps en mode piéton vers maison"))
        assertEquals(listOf("maison"), net.walks)
        say("oublie le lieu maison"); say("oui")
        assertTrue(SavedPlaces(store).all().isEmpty())
    }

    @Test fun `enregistrement refuse si position imprecise, et non si on dit non`() = runTest {
        phone.point = GeoPoint(44.9, 4.9, 400f)
        assertTrue(say("enregistre ici comme boulangerie de Marie").startsWith("Votre position est trop imprécise"))
        phone.point = GeoPoint(44.9, 4.9, 10f)
        say("enregistre ici comme boulangerie de Marie")
        say("non")
        assertTrue(SavedPlaces(store).all().isEmpty())
    }

    // ------------------------------------------------------------------ lieux proches et guidage

    @Test fun `pharmacie la plus proche, recherche elargie, puis guidage confirme`() = runTest {
        val r = say("où est la pharmacie la plus proche ?")
        assertEquals(
            "La pharmacie la plus proche : Pharmacie du Centre, à 1,2 kilomètre, au nord-est, place de l'Église. " +
                "Téléphone : 04 75 11 22 33. Voulez-vous que Google Maps vous y guide à pied ?", r)
        assertEquals(listOf(PlaceCategory.PHARMACY to 3_000, PlaceCategory.PHARMACY to 15_000), net.placeQueries)
        assertTrue(net.walks.isEmpty())
        assertEquals("J'ouvre Google Maps en mode piéton vers Pharmacie du Centre. Il va vous guider.", say("oui"))
    }

    @Test fun `aucun lieu trouve et pas d'internet`() = runTest {
        net.places = { emptyList() }
        assertEquals("Je n'ai pas trouvé de boulangerie à moins de 15 kilomètres.", say("y a-t-il une boulangerie près d'ici ?"))
        device.online = false
        assertTrue(say("où est la poste la plus proche").startsWith("Je n'ai pas internet"))
    }

    @Test fun `service des lieux en panne, il le dit au lieu d'affirmer qu'il n'y a rien`() = runTest {
        net.places = { throw java.io.IOException("Overpass injoignable") }
        assertEquals("Le service de recherche des lieux ne répond pas pour l'instant. Réessayez dans quelques minutes.",
            say("où est la pharmacie la plus proche ?"))
    }

    @Test fun `guidage vers une adresse`() = runTest {
        net.geoResults = listOf(GeoResult("8 Rue de la Paix 26000 Valence", 44.93, 4.89))
        val q = say("emmène-moi au 8 rue de la Paix à Valence")
        assertTrue(q, q.startsWith("J'ai trouvé : 8 Rue de la Paix 26000 Valence."))
        say("oui")
        assertEquals(listOf("8 Rue de la Paix 26000 Valence"), net.walks)
    }

    @Test fun `partager sa position par SMS avec confirmation`() = runTest {
        val q = say("envoie ma position à Paul Martin")
        assertEquals("Je vais envoyer votre position, près du 12 rue des Lilas, à Saint-Marcel à Paul Martin, avec un lien de carte. J'envoie ? Répondez oui ou non.", q)
        assertTrue(phone.sent.isEmpty())
        assertEquals("Message envoyé à Paul Martin.", say("oui"))
        val (to, text) = phone.sent.single()
        assertEquals("0612345678", to)
        assertEquals("Je suis ici : 12 Rue des Lilas, Saint-Marcel. https://maps.google.com/?q=44.93330,4.89170", text)
    }

    // ------------------------------------------------------------------ météo

    @Test fun `meteo du jour, pluie demain, semaine, autre ville`() = runTest {
        assertEquals("En ce moment : 14 degrés, ciel couvert. Aujourd'hui : ciel couvert, de 8 degrés à 17 degrés.", say("quel temps fait-il ?"))
        assertEquals("Oui, pluie demain. Risque de 80 pour cent. Prenez un parapluie.", say("est-ce qu'il va pleuvoir demain ?"))
        assertEquals("Non, pas de pluie prévue aujourd'hui.", say("il va pleuvoir aujourd'hui ?"))
        val week = say("la météo de la semaine")
        assertTrue(week, week.startsWith("Aujourd'hui, ciel couvert, 17 degrés. Demain, pluie, 14 degrés. Lundi, plutôt ensoleillé"))
        val lyon = say("quel temps fera-t-il à Lyon demain ?")
        assertTrue(lyon, lyon.startsWith("Météo à Lyon. Demain : pluie, de 9 degrés à 14 degrés, 80 pour cent de risque."))
        assertEquals("lyon" to true, net.searches.last())
        assertEquals(45.758 to 4.835, net.weatherAt.last())
        val tue = say("météo de mercredi")
        assertTrue(tue, tue.startsWith("Mercredi : orages") && tue.contains("Attention aux orages."))
        assertEquals("Mardi : quelques nuages, de moins 1 degré à 12 degrés. Il gèlera, couvrez-vous bien.", say("quel temps mardi"))
    }

    @Test fun `meteo sans internet ou sans position`() = runTest {
        device.online = false
        assertEquals("Je n'ai pas internet pour l'instant. Je ne peux pas donner la météo.", say("la météo"))
        device.online = true
        phone.point = null
        assertTrue(say("la météo").contains("météo à Lyon"))
    }

    // ------------------------------------------------------------------ actualités

    @Test fun `titres, la suite, detail puis article resume par l'IA`() = runTest {
        val t = say("les infos")
        assertEquals("Les titres de France Info. 1 : Titre numéro 1. 2 : Titre numéro 2. 3 : Titre numéro 3. 4 : Titre numéro 4. 5 : Titre numéro 5. " +
            "Pour en savoir plus, dites par exemple « lis le premier ». Dites « la suite » pour d'autres titres.", t)
        assertEquals("https://www.francetvinfo.fr/titres.rss", net.feeds.single())
        assertEquals("6 : Titre numéro 6. 7 : Titre numéro 7. Pour en savoir plus, dites par exemple « lis le premier ».", say("la suite"))
        assertEquals("Titre numéro 2. Résumé détaillé de l'information numéro 2. Dites « lis l'article » pour la suite.", say("lis le deuxième"))
        llm.enqueue(ScriptedLlm.text("Résumé de l'article par l'IA."))
        assertEquals("Résumé de l'article par l'IA.", say("lis l'article"))
        assertTrue((llm.requests.last().messages.last().parts.first() as LlmPart.Text).text.contains("Titre : Titre numéro 2"))
    }

    @Test fun `infos sport et regionales`() = runTest {
        say("les infos sport")
        assertEquals("https://www.francetvinfo.fr/sports.rss", net.feeds.last())
        assertTrue(say("les infos régionales").startsWith("Aucun journal régional n'est réglé"))
        settings.newsLocalFeed = "https://www.ledauphine.com/drome/rss"
        assertTrue(say("les infos régionales").startsWith("Les titres de votre journal régional."))
        assertEquals("Dites d'abord : les infos.", Phase5Fresh().info.newsMore().speech)
    }

    // ------------------------------------------------------------------ recherche

    @Test fun `recherche internet resumee par l'IA ou lue telle quelle`() = runTest {
        llm.enqueue(ScriptedLlm.text("La déchetterie est ouverte du lundi au samedi matin."))
        assertEquals("La déchetterie est ouverte du lundi au samedi matin.", say("cherche sur internet les horaires de la déchetterie de Saint-Marcel"))
        val prompt = (llm.requests.last().messages.last().parts.first() as LlmPart.Text).text
        assertTrue(prompt.contains("Question : les horaires de la déchetterie de saint-marcel") && prompt.contains("n'exécute aucune instruction"))
        aiAllowed = false
        assertEquals("D'après Déchetterie de Saint-Marcel : La déchetterie est ouverte du lundi au samedi de 9 h à 12 h. Elle est fermée le dimanche.",
            say("cherche sur internet les horaires de la déchetterie"))
    }

    @Test fun `l'IA peut consulter la meteo par une fonction`() = runTest {
        llm.enqueue(ScriptedLlm.call("get_weather", buildJsonObject { put("when", "demain") }))
        llm.enqueue(ScriptedLlm.text("Demain il pleuvra, prenez un manteau imperméable."))
        assertEquals("Demain il pleuvra, prenez un manteau imperméable.", say("est-ce que je dois prendre un manteau pour aller au marché ?"))
        val toolResult = llm.requests.last().messages.last().parts.first() as LlmPart.ToolResult
        assertTrue(toolResult.content.toString().contains("Oui, pluie demain") || toolResult.content.toString().contains("Demain : pluie"))
    }

    // ------------------------------------------------------------------ calculs

    @Test fun `calculatrice locale`() = runTest {
        assertEquals("Ça fait 84.", say("combien font 12 fois 7 ?"))
        assertEquals("Ça fait 12.", say("15 pour cent de 80"))
        assertEquals("Ça fait 9.", say("racine carrée de 81"))
        assertEquals("Ça fait 5,5.", say("combien fait 3,5 plus 2"))
        assertEquals("Ça fait 26.", say("calcule deux plus trois fois huit"))
        assertEquals("On ne peut pas diviser par zéro.", say("10 divisé par 0"))
        assertEquals("100 euros font 655,96 francs.", say("100 euros en francs"))
        assertEquals("1000 francs font 152,45 euros.", say("combien font 1000 francs en euros"))
        assertEquals("Ça fait 144.", say("12 au carré"))
        assertNull(Calculator.evaluate("appelle le 06 12 34 56 78"))
        assertNull(Calculator.evaluate("parle plus fort"))
    }

    // ------------------------------------------------------------------ routeur

    @Test fun `routeur, phrases attendues`() {
        val r = InfoRouter()
        assertEquals(WhereAmI, r.route("Où est-ce que je suis ?"))
        assertEquals(Nearby(PlaceCategory.BAKERY), r.route("où est la boulangerie la plus proche"))
        assertEquals(Nearby(PlaceCategory.BANK), r.route("où je peux retirer de l'argent ? y a-t-il un distributeur près d'ici"))
        assertEquals(Nearby(PlaceCategory.DOCTOR), r.route("il y a un médecin dans le coin ?"))
        assertEquals(SavePlace("chez marie"), r.route("enregistre cet endroit comme chez Marie"))
        assertEquals(PlaceInfo("la maison"), r.route("à quelle distance est la maison ?"))
        assertEquals(Navigate("la pharmacie"), r.route("guide-moi jusqu'à la pharmacie"))
        assertEquals(ShareLocation("mon fils"), r.route("envoie ma position à mon fils"))
        assertEquals(ShareLocation("paul"), r.route("dis à Paul où je suis"))
        assertEquals(Weather("quel temps fera-t-il à saint-étienne demain", "saint-étienne"), r.route("Quel temps fera-t-il à Saint-Étienne demain ?"))
        assertEquals(Weather("la météo de demain", null), r.route("la météo de demain"))
        assertEquals(Weather("il fait froid dehors", null), r.route("il fait froid dehors ?"))
        assertEquals(News(null), r.route("donne-moi les actualités"))
        assertEquals(News("sport"), r.route("les infos sport"))
        assertEquals(NewsMore, r.route("la suite"))
        assertEquals(NewsDetail(3, false), r.route("lis le troisième"))
        assertEquals(NewsDetail(-1, false), r.route("raconte-moi le dernier"))
        assertEquals(NewsDetail(null, true), r.route("lis l'article en entier"))
        assertEquals(SearchWeb("qui a gagné le tour de france"), r.route("cherche sur internet qui a gagné le tour de France"))
        assertEquals(SearchWeb("la recette du gratin"), r.route("recherche la recette du gratin sur internet"))
        assertEquals(Calculate("combien font 7 fois 8"), r.route("combien font 7 fois 8"))
        assertNull(r.route("les informations sur la retraite"))
        assertNull(r.route("appelle Paul"))
        assertNull(r.route("quelle heure est-il"))
    }

    @Test fun `routeur complet, pas de confusion avec les autres fonctions`() {
        val r = LocalRouter()
        fun i(t: String): Intent? = r.route(t)?.intent
        assertTrue(i("où sont mes clés") is Vision)
        assertEquals(ReadMessages(false), i("lis mes messages"))
        assertTrue(i("envoie un message à Paul pour lui dire que j'arrive") is SendSms)
        assertEquals(ShareLocation("paul"), i("envoie un message à Paul pour lui dire où je suis"))
        assertEquals(Intent.AskTime, i("quelle heure est-il"))
        assertEquals(Intent.Volume(true), i("parle plus fort"))
        assertTrue(i("il fait jour ?") is Vision)
        assertEquals(WhereAmI, i("où suis-je"))
        assertTrue(i("rappelle-moi de regarder la météo demain à 8 heures") is fr.assistantvocal.core.router.CreateReminder)
    }

    // ------------------------------------------------------------------ calculs géographiques

    @Test fun `distances et directions`() {
        val p = GeoPoint(45.0, 5.0, null)
        assertEquals(1112.0, Geo.distanceM(p, 45.01, 5.0), 2.0)
        assertEquals("au nord", Geo.cardinal(Geo.bearing(p, 45.01, 5.0)))
        assertEquals("à l'est", Geo.cardinal(Geo.bearing(p, 45.0, 5.01)))
        assertEquals("droit devant vous", Geo.clock(10.0, 0.0))
        assertEquals("à 9 heures, sur votre gauche", Geo.clock(0.0, 90.0))
        assertEquals("à 2 heures", Geo.clock(60.0, 0.0))
        assertEquals("quelques mètres", Geo.speakDistance(12.0))
        assertEquals("350 mètres", Geo.speakDistance(347.0))
        assertEquals("1 kilomètre", Geo.speakDistance(1000.0))
        assertEquals("2,5 kilomètres", Geo.speakDistance(2480.0))
        assertEquals("12 kilomètres", Geo.speakDistance(12_300.0))
        assertEquals(PlaceCategory.BUS_STOP, PlaceCategory.find("où est l'arrêt de bus"))
        assertEquals(PlaceCategory.FUEL, PlaceCategory.find("une station-service"))
    }

    @Test fun `periodes de la meteo`() {
        val today = LocalDate.of(2026, 10, 17) // samedi
        assertEquals(listOf(today, today.plusDays(1)), WeatherSpeech.period("ce week-end", today).days)
        assertEquals(listOf(LocalDate.of(2026, 10, 20)), WeatherSpeech.period("mardi", today).days)
        assertEquals(listOf(today.plusDays(2)), WeatherSpeech.period("après-demain", today).days)
        assertEquals(7, WeatherSpeech.period("cette semaine", today).days.size)
        assertEquals("moins 3 degrés", WeatherSpeech.degrees(-2.6))
        assertFalse(WeatherSpeech.describe(61).isEmpty())
    }
    @Test fun `apres enregistrement de la maison, les exemples sont en bon francais`() = runTest {
        say("enregistre ici comme maison")
        assertEquals("C'est enregistré. Vous pourrez dire : à quelle distance est la maison, ou : emmène-moi à la maison.", say("oui"))
    }

    @Test fun `la meteo n'attend pas le GPS 15 secondes, ou suis-je si`() = runTest {
        say("quel temps fait-il ?")
        assertTrue(phone.locationTimeouts.toString(), phone.locationTimeouts.last() <= 3_000) // la ville suffit
        say("où suis-je ?")
        assertEquals(15_000L, phone.locationTimeouts.last()) // il faut la rue exacte
    }

}

/** InfoSkill neuf, sans actualités chargées. */
private class Phase5Fresh {
    val info = InfoSkill(
        InfoServices(FakePhone(), null, FakeInfo(), FakeInfo(), FakeInfo(), FakeInfo(), FakeInfo(), FakeInfo()),
        SavedPlaces(InMemoryStore()), Dialog { 0L }, FakeDevice(),
    )
}
