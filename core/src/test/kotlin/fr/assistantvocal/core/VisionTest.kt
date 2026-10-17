package fr.assistantvocal.core

import fr.assistantvocal.core.assistant.Assistant
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.FollowUp
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.phone.ContactDirectory
import fr.assistantvocal.core.phone.PeopleStore
import fr.assistantvocal.core.reminders.MedicationLog
import fr.assistantvocal.core.reminders.ReminderStore
import fr.assistantvocal.core.router.DocumentQuestion
import fr.assistantvocal.core.router.LocalRouter
import fr.assistantvocal.core.router.Vision
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.InMemoryStore
import fr.assistantvocal.core.skills.CommunicationSkill
import fr.assistantvocal.core.skills.ReminderSkill
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.tools.CoreTools
import fr.assistantvocal.core.tools.ToolRegistry
import fr.assistantvocal.core.vision.BanknoteDetector
import fr.assistantvocal.core.vision.BarcodeScanner
import fr.assistantvocal.core.vision.Box
import fr.assistantvocal.core.vision.Camera
import fr.assistantvocal.core.vision.ColorNamer
import fr.assistantvocal.core.vision.ExpiryDates
import fr.assistantvocal.core.vision.FramingAdvisor
import fr.assistantvocal.core.vision.Frame
import fr.assistantvocal.core.vision.Gs1
import fr.assistantvocal.core.vision.MedicationDatabase
import fr.assistantvocal.core.vision.MedicationInfo
import fr.assistantvocal.core.vision.OcrEngine
import fr.assistantvocal.core.vision.OcrResult
import fr.assistantvocal.core.vision.Product
import fr.assistantvocal.core.vision.ProductLookup
import fr.assistantvocal.core.vision.TextLine
import fr.assistantvocal.core.vision.VisionMode
import fr.assistantvocal.core.vision.VisionRequest
import fr.assistantvocal.core.vision.VisionSkill
import fr.assistantvocal.core.vision.VisionUi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private fun ocr(vararg lines: Pair<String, Box>, w: Int = 1000, h: Int = 1000) = OcrResult(lines.map { TextLine(it.first, it.second) }, w, h)

class VisionLogicTest {
    @Test fun framing() {
        assertFalse(FramingAdvisor.advise(OcrResult(emptyList(), 100, 100)).ok)
        val cutRight = FramingAdvisor.advise(ocr("Madame, Monsieur" to Box(100, 300, 1000, 330), "Votre facture" to Box(100, 350, 700, 380)))
        assertEquals("Le texte est coupé à droite. Déplacez le téléphone un peu vers la droite.", cutRight.speech)
        val small = FramingAdvisor.advise(ocr("abc" to Box(450, 490, 520, 498)))
        assertTrue(small.speech.startsWith("Le texte est loin ou petit"))
        val good = FramingAdvisor.advise(ocr("Madame, Monsieur" to Box(100, 200, 800, 240), "Votre facture du mois" to Box(100, 260, 850, 300),
            "Montant : 45,20 €" to Box(100, 320, 600, 360), "Merci" to Box(100, 700, 300, 740)))
        assertTrue(good.ok)
        assertTrue(FramingAdvisor.advise(OcrResult(emptyList(), 100, 100), luminance = 10).speech.contains("sombre"))
    }

    @Test fun colors() {
        assertEquals("rouge", ColorNamer.name(200, 30, 30))
        assertEquals("bleu marine", ColorNamer.name(20, 30, 90))
        assertEquals("blanc", ColorNamer.name(245, 245, 245))
        assertEquals("noir", ColorNamer.name(15, 15, 18))
        assertEquals("gris", ColorNamer.name(128, 128, 128))
        assertEquals("vert", ColorNamer.name(40, 160, 60))
        assertEquals("jaune", ColorNamer.name(230, 210, 40))
        assertEquals("marron", ColorNamer.name(110, 60, 25))
        assertEquals("violet", ColorNamer.name(120, 50, 170))
        assertEquals("rose", ColorNamer.name(240, 150, 190))
    }

    @Test fun banknotes() {
        val twenty = BanknoteDetector.detect(ocr("20 EURO" to Box(0, 0, 10, 10), "EYPΩ 20" to Box(0, 0, 10, 10)), "bleu")
        assertEquals(20, twenty.value); assertTrue(twenty.sure)
        assertEquals("C'est un billet de 20 euros.", twenty.speech)
        val doubtful = BanknoteDetector.detect(ocr("50" to Box(0, 0, 10, 10)), "bleu")
        assertFalse(doubtful.sure)
        assertNull(BanknoteDetector.detect(ocr("Bonjour" to Box(0, 0, 10, 10)), "blanc").value)
    }

    @Test fun expiryDates() {
        val today = LocalDate.of(2026, 10, 17)
        assertEquals(LocalDate.of(2026, 11, 3), ExpiryDates.find("Lot 1234\nA consommer de préférence avant le 03/11/2026", today))
        assertEquals(LocalDate.of(2027, 5, 31), ExpiryDates.find("EXP 05/2027", today))
        assertEquals("Attention, la date est dépassée : 1 octobre 2026.", ExpiryDates.speak(LocalDate.of(2026, 10, 1), today))
        assertNull(ExpiryDates.find("Prix 2,50", today))
    }

    @Test fun medicationDatamatrix() {
        val m = Gs1.parse("010340093449729917280331101234AB\u001D21XYZ")!!
        assertEquals("3400934497299", m.cip13)
        assertEquals(LocalDate.of(2028, 3, 31), m.expiry)
        assertEquals("1234AB", m.lot)
        assertEquals("3400934497299", Gs1.parse("(01)03400934497299(17)280300")!!.cip13)
        assertEquals(LocalDate.of(2028, 3, 31), Gs1.parse("(01)03400934497299(17)280300")!!.expiry)
        assertNull(Gs1.parse("3017620422003"))
    }

    @Test fun routing() {
        val r = LocalRouter()
        fun mode(s: String) = ((r.route(s)?.intent as? Vision)?.request?.mode)
        assertEquals(VisionMode.SCENE, mode("qu'est-ce qu'il y a devant moi ?"))
        assertEquals(VisionMode.READ, mode("lis-moi ce courrier"))
        assertEquals(VisionMode.EXPLAIN, mode("c'est quoi cette lettre ?"))
        assertEquals(VisionMode.BANKNOTE, mode("c'est quel billet ?"))
        assertEquals(VisionMode.COLOR, mode("c'est quelle couleur ?"))
        assertEquals(VisionMode.MATCH_COLORS, mode("est-ce que ça va ensemble ?"))
        assertEquals(VisionMode.PRODUCT, mode("c'est quoi ce produit ?"))
        assertEquals(VisionMode.MEDICATION, mode("c'est quel médicament ?"))
        assertEquals(VisionMode.LIGHT, mode("est-ce que la lumière est allumée ?"))
        assertEquals(VisionMode.NEXT_PAGE, mode("page suivante"))
        assertEquals(VisionRequest(VisionMode.FIND, "mes clés"), (r.route("où sont mes clés ?")?.intent as Vision).request)
        assertTrue(r.route("relis le montant")?.intent is DocumentQuestion)
        assertNull("pas la caméra pour une recette", mode("cherche une recette de crêpes"))
        assertTrue("les messages restent les messages", r.route("relis mes messages")?.intent !is DocumentQuestion)
    }
}

// ---------------------------------------------------------------------- séance de vision simulée

private class FakeCamera(var previews: MutableList<Frame>, var photo: Frame?) : Camera {
    var torchOn = false
    var closed = false
    override suspend fun preview(): Frame? = if (previews.size > 1) previews.removeAt(0) else previews.firstOrNull()
    override suspend fun capture(): Frame? = photo
    override fun torch(on: Boolean) { torchOn = on }
    override fun close() { closed = true }
}

private fun frame(tag: String, lum: Int = 150, rgb: Triple<Int, Int, Int> = Triple(200, 200, 200)) = Frame(tag.toByteArray(), 1000, 1000, lum, rgb)

private class TagOcr(val byTag: Map<String, OcrResult>) : OcrEngine {
    override suspend fun read(frame: Frame) = byTag[String(frame.jpeg)] ?: OcrResult(emptyList(), 1000, 1000)
}

private class Ui : VisionUi {
    val said = mutableListOf<String>()
    var ticks = 0
    override suspend fun say(text: String) { said += text }
    override suspend fun tick(good: Boolean) { ticks++ }
    override suspend fun tone(hz: Double, ms: Long) { ticks++ }
    override fun stopRequested() = ticks > 50
}

class VisionSkillTest {
    private var now = 0L
    private val llm = ScriptedLlm()
    private val letter = ocr("ENGIE - Facture de gaz" to Box(100, 100, 800, 140), "Montant à payer : 84,30 €" to Box(100, 200, 850, 240),
        "à régler avant le 05/11/2026" to Box(100, 300, 700, 340), "Merci de votre confiance" to Box(100, 700, 600, 740))
    private val cutLetter = ocr("ENGIE - Facture de gaz" to Box(100, 100, 1000, 140))
    private var codes: List<String> = emptyList()
    private val products = object : ProductLookup {
        override suspend fun lookup(code: String) = if (code == "3017620422003") Product(code, "Nutella", "Ferrero", "400 g", listOf("lait", "noisettes", "soja"), "e") else null
    }
    private val meds = object : MedicationDatabase {
        override fun byCip13(cip: String) = if (cip == "3400934497299") MedicationInfo("DOLIPRANE 1000 mg, comprimé", "plaquette(s) thermoformée(s) PVC-aluminium de 8  comprimé(s)") else null
    }
    private val barcodes = object : BarcodeScanner { override suspend fun scan(frame: Frame) = codes }

    private fun skill(cam: FakeCamera, online: Boolean = true, ocrMap: Map<String, OcrResult> = mapOf("cut" to cutLetter, "good" to letter, "photo" to letter)) =
        VisionSkill({ cam }, TagOcr(ocrMap), barcodes, products, meds, llm, { online }, { "système" },
            { LocalDate.of(2026, 10, 17) }, { now += 500; now })

    @Test fun `lecture guidee d'un courrier puis relecture du montant`() = runTest {
        val cam = FakeCamera(mutableListOf(frame("cut"), frame("good"), frame("good")), frame("photo"))
        val ui = Ui()
        val s = skill(cam, online = false)
        val out = s.run(VisionRequest(VisionMode.READ), ui)
        assertTrue(ui.said.contains("Le texte est coupé à droite. Déplacez le téléphone un peu vers la droite."))
        assertTrue(out, out.startsWith("ENGIE - Facture de gaz. Montant à payer : 84,30 €"))
        assertTrue(cam.closed)
        assertEquals("Montants trouvés : 84,30 €.", s.documentQuestion("relis le montant"))
        assertTrue(s.documentQuestion("relis").startsWith("ENGIE"))
    }

    @Test fun `explication d'une lettre par l'IA, avec le texte lu (sans image)`() = runTest {
        llm.enqueue(ScriptedLlm.text("C'est une facture de gaz d'Engie de 84,30 euros à payer avant le 5 novembre."))
        val cam = FakeCamera(mutableListOf(frame("good")), frame("photo"))
        val out = skill(cam).run(VisionRequest(VisionMode.EXPLAIN), Ui())
        assertTrue(out.startsWith("C'est une facture de gaz d'Engie"))
        val req = llm.requests.single()
        val parts = req.messages.single().parts
        assertTrue("pas d'image envoyée : le texte suffit", parts.none { it is LlmPart.Image })
        assertTrue((parts.single() as LlmPart.Text).text.contains("Montant à payer : 84,30 €"))
    }

    @Test fun `description de scene envoie la photo a l'IA, lampe si sombre`() = runTest {
        llm.enqueue(ScriptedLlm.text("Une cuisine. Une chaise est devant vous à 1 mètre, à 12 heures."))
        val cam = FakeCamera(mutableListOf(), frame("dark", lum = 10))
        val ui = Ui()
        val out = skill(cam).run(VisionRequest(VisionMode.SCENE), ui)
        assertEquals("Une cuisine. Une chaise est devant vous à 1 mètre, à 12 heures.", out)
        assertTrue(ui.said.contains("Il fait sombre, j'allume la lampe."))
        assertTrue(llm.requests.single().messages.single().parts.first() is LlmPart.Image)
        assertFalse("lampe éteinte à la fin", cam.torchOn)
    }

    @Test fun `scene hors ligne decrit ce qui est possible`() = runTest {
        val cam = FakeCamera(mutableListOf(), frame("photo", rgb = Triple(200, 30, 30)))
        val out = skill(cam, online = false).run(VisionRequest(VisionMode.SCENE), Ui())
        assertTrue(out, out.startsWith("Je n'ai pas internet pour décrire la scène.") && out.contains("rouge") && out.contains("Je lis : ENGIE"))
    }

    @Test fun `billet reconnu hors ligne`() = runTest {
        val cam = FakeCamera(mutableListOf(), frame("billet", rgb = Triple(40, 90, 200)))
        val out = skill(cam, online = false, ocrMap = mapOf("billet" to ocr("20" to Box(0, 0, 1, 1), "EURO" to Box(0, 0, 1, 1)))).run(VisionRequest(VisionMode.BANKNOTE), Ui())
        assertEquals("C'est un billet de 20 euros.", out)
    }

    @Test fun `photo ratee, il se sert de l'image de l'apercu`() = runTest {
        val cam = FakeCamera(mutableListOf(frame("apercu", rgb = Triple(20, 30, 90))), photo = null)
        assertEquals("C'est bleu marine.", skill(cam, online = false).run(VisionRequest(VisionMode.COLOR), Ui()))
        assertTrue(cam.closed)
    }

    @Test fun `couleur hors ligne`() = runTest {
        val cam = FakeCamera(mutableListOf(), frame("x", rgb = Triple(20, 30, 90)))
        assertEquals("C'est bleu marine.", skill(cam, online = false).run(VisionRequest(VisionMode.COLOR), Ui()))
    }

    @Test fun `produit par code-barres avec allergenes`() = runTest {
        codes = listOf("3017620422003")
        val cam = FakeCamera(mutableListOf(frame("p")), frame("p"))
        assertEquals("Nutella de Ferrero 400 g. Allergènes : lait, noisettes, soja. Nutri-Score E.", skill(cam, online = false).run(VisionRequest(VisionMode.PRODUCT), Ui()))
    }

    @Test fun `boite de medicament par le code carre, date depassee`() = runTest {
        codes = listOf("010340093449729917260331101234AB")
        val cam = FakeCamera(mutableListOf(frame("m")), frame("m"))
        val out = skill(cam, online = false).run(VisionRequest(VisionMode.MEDICATION), Ui())
        assertEquals("Doliprane 1000 mg, boîte de 8 comprimés. Attention : la date de péremption est dépassée depuis mars 2026. En cas de doute, vérifiez avec votre pharmacien.", out)
    }

    @Test fun `recherche d'objet`() = runTest {
        llm.enqueue(ScriptedLlm.text("NON"))
        llm.enqueue(ScriptedLlm.text("TROUVÉ : vos clés sont sur la table, à 2 heures, à environ 1 mètre."))
        val out = skill(FakeCamera(mutableListOf(), frame("x"))).run(VisionRequest(VisionMode.FIND, "mes clés"), Ui())
        assertEquals("vos clés sont sur la table, à 2 heures, à environ 1 mètre.", out)
    }

    @Test fun `l'assistant lance la camera pour 'lis ce texte' et repond aux questions sur le document`() = runTest {
        val store = InMemoryStore()
        val settings = AssistantSettings(store)
        val device = FakeDevice()
        val dialog = Dialog { 0L }
        val phone = FakePhone()
        val people = PeopleStore(store)
        val vision = skill(FakeCamera(mutableListOf(frame("good")), frame("photo")), online = false)
        val skills = Skills(dialog, CommunicationSkill(ContactDirectory(phone, people), people, phone, phone, phone, phone, phone, store, device, dialog),
            ReminderSkill(ReminderStore(store), MedicationLog(store), phone, store, device, dialog), vision)
        val a = Assistant(settings, device, null, ToolRegistry(CoreTools.all(settings, device)), nowMillis = { now }, skills = skills)
        val r = a.handle("lis ce texte")
        assertEquals(FollowUp.VISION, r.followUp)
        vision.run(r.vision!!, Ui())
        assertEquals("Montants trouvés : 84,30 €.", a.handle("relis le montant").speech)
    }
}
