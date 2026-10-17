package fr.assistantvocal.core.vision

import fr.assistantvocal.core.assistant.FrenchSpeech
import fr.assistantvocal.core.llm.FailureKind
import fr.assistantvocal.core.llm.LlmMessage
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.llm.LlmProvider
import fr.assistantvocal.core.llm.LlmRequest
import fr.assistantvocal.core.llm.LlmResult
import fr.assistantvocal.core.llm.Quality
import fr.assistantvocal.core.llm.Role
import fr.assistantvocal.core.text.FrenchText
import java.time.LocalDate

enum class VisionMode { SCENE, READ, EXPLAIN, BANKNOTE, COLOR, MATCH_COLORS, LIGHT, PRODUCT, MEDICATION, FIND, NEXT_PAGE }

data class VisionRequest(val mode: VisionMode, val question: String = "")

/** Caméra arrière (CameraX sur Android, images de test dans les tests). */
interface Camera {
    /** Image basse résolution pour le guidage (rapide). */
    suspend fun preview(): Frame?
    /** Photo de bonne qualité pour la lecture finale. */
    suspend fun capture(): Frame?
    fun torch(on: Boolean)
    fun close()
}

interface OcrEngine { suspend fun read(frame: Frame): OcrResult }
interface BarcodeScanner { suspend fun scan(frame: Frame): List<String> }

data class Product(
    val code: String, val name: String?, val brand: String?, val quantity: String?,
    val allergens: List<String>, val nutriscore: String?,
)

interface ProductLookup { suspend fun lookup(code: String): Product? }

/** Ce que la séance de vision demande à l'application : parler, sons courts, écouter. */
interface VisionUi {
    suspend fun say(text: String)
    /** Petit son de guidage (aigu = proche du bon cadrage). */
    suspend fun tick(good: Boolean)
    /** Tonalité continue pour le détecteur de lumière (fréquence en Hz), pendant [ms]. */
    suspend fun tone(hz: Double, ms: Long)
    /** Vrai si la personne a demandé d'arrêter (bouton, « stop »). */
    fun stopRequested(): Boolean
}

/** Le dernier document lu, pour « relis », « relis le montant », « que dois-je faire ? ». */
class DocumentMemory {
    var pages: MutableList<String> = mutableListOf()
        private set
    var readAtMs: Long = 0
        private set

    fun start(text: String, now: Long) { pages = mutableListOf(text); readAtMs = now }
    fun addPage(text: String, now: Long) { pages += text; readAtMs = now }
    fun clear() { pages.clear() }
    fun isFresh(now: Long) = pages.isNotEmpty() && now - readAtMs < 60 * 60_000
    val fullText get() = pages.joinToString("\n\n")
}

/**
 * Fonctions de vision. Hors ligne : lecture de texte (OCR), couleurs, lumière, billets (chiffre + couleur),
 * codes-barres, boîtes de médicaments (code DataMatrix + base publique). En ligne : description de scène,
 * explication de courrier, recherche d'objet, confirmation des cas douteux par l'IA.
 */
class VisionSkill(
    private val camera: () -> Camera,
    private val ocr: OcrEngine,
    private val barcodes: BarcodeScanner,
    private val products: ProductLookup,
    private val medications: MedicationDatabase,
    private val llm: LlmProvider?,
    private val canUseLlm: () -> Boolean,
    private val systemPrompt: () -> String,
    private val today: () -> LocalDate,
    private val nowMs: () -> Long,
    val documents: DocumentMemory = DocumentMemory(),
    private val guidanceSeconds: Int = 20,
) {
    suspend fun run(req: VisionRequest, ui: VisionUi): String {
        val cam = camera()
        try {
            return when (req.mode) {
                VisionMode.SCENE -> scene(cam, ui, req.question)
                VisionMode.READ -> read(cam, ui, explain = false, nextPage = false)
                VisionMode.EXPLAIN -> read(cam, ui, explain = true, nextPage = false)
                VisionMode.NEXT_PAGE -> read(cam, ui, explain = false, nextPage = true)
                VisionMode.BANKNOTE -> banknote(cam, ui)
                VisionMode.COLOR -> color(cam, ui, match = false)
                VisionMode.MATCH_COLORS -> color(cam, ui, match = true)
                VisionMode.LIGHT -> light(cam, ui)
                VisionMode.PRODUCT -> product(cam, ui)
                VisionMode.MEDICATION -> medication(cam, ui)
                VisionMode.FIND -> find(cam, ui, req.question)
            }
        } finally {
            cam.torch(false)
            cam.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Photo en haute qualité ; si elle échoue (erreur de la caméra), l'image de l'aperçu suffit souvent. */
    private suspend fun shot(cam: Camera): Frame? = cam.capture() ?: cam.preview()

    private suspend fun photo(cam: Camera, ui: VisionUi): Frame? {
        var f = shot(cam) ?: return null
        if (f.luminance < 35) {
            ui.say("Il fait sombre, j'allume la lampe.")
            cam.torch(true)
            f = shot(cam) ?: return null
        }
        return f
    }

    private suspend fun askAi(prompt: String, image: Frame?, quality: Quality = Quality.STANDARD): String? {
        if (llm == null || !canUseLlm()) return null
        val parts = buildList<LlmPart> { if (image != null) add(LlmPart.Image(image.jpeg)); add(LlmPart.Text(prompt)) }
        return when (val r = llm.generate(LlmRequest(systemPrompt(), listOf(LlmMessage(Role.USER, parts)), quality = quality, maxOutputTokens = 1500))) {
            is LlmResult.Reply -> FrenchSpeech.clean(r.text).ifBlank { null }?.let { r.notice?.let { n -> "$n $it" } ?: it }
            is LlmResult.Failure -> if (r.kind == FailureKind.BLOCKED) "Je ne peux pas décrire cette image." else null
        }
    }

    private val offlineNote = "Sans internet, je ne peux pas faire plus."

    // ------------------------------------------------------------------ scène

    private suspend fun scene(cam: Camera, ui: VisionUi, question: String): String {
        ui.say("Je regarde.")
        val f = photo(cam, ui) ?: return "Je n'arrive pas à utiliser la caméra."
        val q = question.ifBlank { "Décris ce qui est devant moi." }
        return askAi(q, f) ?: run {
            // Repli hors ligne : texte visible et couleur dominante.
            val text = ocr.read(f)
            val color = ColorNamer.name(f.centerRgb.first, f.centerRgb.second, f.centerRgb.third)
            "Je n'ai pas internet pour décrire la scène. ${LightMeter.describe(f.luminance)} Au centre, la couleur dominante est $color." +
                if (!text.isEmpty) " Je lis : ${text.text.lines().take(3).joinToString(". ")}." else ""
        }
    }

    // ------------------------------------------------------------------ lecture

    /** Guide le cadrage avec la voix jusqu'à une image nette du texte, puis prend la photo. */
    private suspend fun guidedCapture(cam: Camera, ui: VisionUi): Pair<Frame, OcrResult>? {
        val end = nowMs() + guidanceSeconds * 1000L
        var lastAdvice = ""
        var okCount = 0
        var torchOn = false
        while (nowMs() < end && !ui.stopRequested()) {
            val p = cam.preview() ?: break
            if (p.luminance < 35 && !torchOn) { cam.torch(true); torchOn = true; ui.say("Il fait sombre, j'allume la lampe."); continue }
            val r = ocr.read(p)
            val advice = FramingAdvisor.advise(r)
            if (advice.ok) {
                ui.tick(true)
                if (++okCount >= 2) break
            } else {
                okCount = 0
                ui.tick(false)
                if (advice.speech != lastAdvice) { ui.say(advice.speech); lastAdvice = advice.speech }
            }
        }
        if (ui.stopRequested()) return null
        val f = shot(cam) ?: return null
        return f to ocr.read(f)
    }

    private suspend fun read(cam: Camera, ui: VisionUi, explain: Boolean, nextPage: Boolean): String {
        ui.say(if (nextPage) "Posez la page suivante. Je vous guide." else "Tenez le téléphone au-dessus du texte. Je vous guide.")
        val (frame, result) = guidedCapture(cam, ui) ?: return "D'accord, j'arrête."
        var text = result.text.trim()
        if (text.length < 15 && llm != null && canUseLlm()) {
            // OCR faible (écriture manuscrite, écran) : l'IA lit l'image.
            askAi("Transcris exactement le texte visible sur cette image, dans l'ordre de lecture. Si rien n'est lisible, dis-le.", frame, Quality.HIGH)?.let { text = it }
        }
        if (text.isBlank()) return "Je n'ai pas réussi à lire de texte. Réessayez avec plus de lumière, document bien à plat."
        if (nextPage && documents.pages.isNotEmpty()) documents.addPage(text, nowMs()) else documents.start(text, nowMs())
        val pageInfo = if (documents.pages.size > 1) "Page ${documents.pages.size}. " else ""
        if (!explain) {
            val expiry = ExpiryDates.find(text, today())
            return "$pageInfo${text.replace("\n", ". ")}".let { it + (expiry?.let { d -> " " + ExpiryDates.speak(d, today()) } ?: "") } +
                " Vous pouvez dire : relis, page suivante, ou c'est quoi ce document."
        }
        val summary = askAi(
            "Voici le texte d'un courrier ou document lu par une personne aveugle. Dis en une phrase de quoi il s'agit et qui l'envoie, " +
                "puis ce qu'il faut faire, avec les montants et les dates limites s'il y en a. Signale s'il ressemble à une arnaque.\n\nTEXTE :\n$text",
            null,
        )
        return summary?.let { "$pageInfo$it Dites « relis » pour entendre le texte complet." }
            ?: "$pageInfo$offlineNote Voici le texte : ${text.replace("\n", ". ")}"
    }

    /** Questions sur le dernier document lu (« relis le montant », « quelle est la date limite ? »). */
    suspend fun documentQuestion(question: String): String {
        if (!documents.isFresh(nowMs())) return "Je n'ai pas de document récent en mémoire. Dites : lis ce texte, en tenant le téléphone au-dessus."
        val q = FrenchText.key(question)
        val text = documents.fullText
        if (Regex("^(relis|relire|relis le|relis tout|relis le texte|relis le document|relis la lettre)$").matches(q)) return text.replace("\n", ". ")
        if (Regex("\\b(montant|somme|combien|prix|payer|total)\\b").containsMatchIn(q)) {
            val amounts = Regex("\\d[\\d  .]*,\\d{2}\\s?(?:€|eur(?:os)?)|\\d+(?:[.,]\\d{2})?\\s?€", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trim() }.toList()
            if (amounts.isNotEmpty() && (llm == null || !canUseLlm())) return "Montants trouvés : ${amounts.distinct().joinToString(", ")}."
        }
        if (Regex("\\b(date|quand|avant le|limite|echeance)\\b").containsMatchIn(q) && (llm == null || !canUseLlm())) {
            val dates = Regex("\\b\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}\\b").findAll(text).map { it.value }.toList()
            if (dates.isNotEmpty()) return "Dates trouvées : ${dates.distinct().joinToString(", ")}."
        }
        return askAi("Voici un document lu par une personne aveugle :\n$text\n\nRéponds à sa question de façon courte : $question", null)
            ?: "$offlineNote Dites « relis » pour réentendre le texte."
    }

    // ------------------------------------------------------------------ billets, couleurs, lumière

    private suspend fun banknote(cam: Camera, ui: VisionUi): String {
        ui.say("Posez le billet à plat, je regarde.")
        val f = photo(cam, ui) ?: return "Je n'arrive pas à utiliser la caméra."
        val color = ColorNamer.name(f.centerRgb.first, f.centerRgb.second, f.centerRgb.third)
        val local = BanknoteDetector.detect(ocr.read(f), color)
        if (local.sure) return local.speech
        val ai = askAi("Est-ce un billet de banque en euros ? Si oui, donne uniquement sa valeur, en une phrase courte. Si ce n'est pas un billet, dis-le.", f, Quality.HIGH)
        return ai ?: local.speech
    }

    private suspend fun color(cam: Camera, ui: VisionUi, match: Boolean): String {
        val f = photo(cam, ui) ?: return "Je n'arrive pas à utiliser la caméra."
        val (r, g, b) = f.centerRgb
        val local = "C'est ${ColorNamer.name(r, g, b)}."
        if (!match) return local
        return askAi("Une personne aveugle montre des vêtements. Nomme les couleurs et motifs visibles, puis dis simplement si elles vont bien ensemble et pourquoi.", f)
            ?: "$local $offlineNote"
    }

    private suspend fun light(cam: Camera, ui: VisionUi): String {
        ui.say("Détecteur de lumière : plus le son est aigu, plus c'est lumineux. Dites stop ou appuyez sur un bouton pour arrêter.")
        val end = nowMs() + 60_000
        var last = 0
        while (nowMs() < end && !ui.stopRequested()) {
            val p = cam.preview() ?: break
            last = p.luminance
            ui.tone(LightMeter.toneHz(p.luminance), 250)
        }
        return LightMeter.describe(last)
    }

    // ------------------------------------------------------------------ produits et médicaments

    private suspend fun scanCodes(cam: Camera, ui: VisionUi, seconds: Int): List<String> {
        val end = nowMs() + seconds * 1000L
        var said = false
        while (nowMs() < end && !ui.stopRequested()) {
            val p = cam.preview() ?: break
            val codes = barcodes.scan(p)
            if (codes.isNotEmpty()) { ui.tick(true); return codes }
            ui.tick(false)
            if (!said && nowMs() > end - seconds * 500L) {
                ui.say("Je ne trouve pas le code. Tournez lentement le produit devant le téléphone.")
                said = true
            }
        }
        return emptyList()
    }

    private suspend fun product(cam: Camera, ui: VisionUi): String {
        ui.say("Tournez lentement le produit devant le téléphone, je cherche le code-barres.")
        val codes = scanCodes(cam, ui, 25)
        val f = shot(cam)
        val code = codes.firstOrNull { it.all(Char::isDigit) && it.length in 8..14 }
        val gs1 = codes.firstNotNullOfOrNull { Gs1.parse(it) }
        if (gs1?.cip13 != null) return describeMedication(gs1, f)
        val expiryFromText = f?.let { ExpiryDates.find(ocr.read(it).text, today()) }
        val expiry = expiryFromText?.let { " " + ExpiryDates.speak(it, today()) } ?: ""
        if (code == null) {
            if (codes.isNotEmpty()) return "J'ai lu un code : ${codes.first().take(80)}."
            val ai = f?.let { askAi("Quel est ce produit ? Donne le nom, la marque et la date de péremption si elle est visible. Réponse courte.", it) }
            return (ai ?: "Je n'ai pas trouvé de code-barres.") + expiry
        }
        val p = products.lookup(code)
            ?: return (f?.let { askAi("Quel est ce produit ? Réponse courte avec nom et marque.", it) } ?: "Je ne connais pas ce produit (code $code).") + expiry
        val parts = buildList {
            add(listOfNotNull(p.name, p.brand?.let { "de $it" }, p.quantity).joinToString(" ").ifBlank { "Produit $code" } + ".")
            if (p.allergens.isNotEmpty()) add("Allergènes : ${p.allergens.joinToString(", ")}.")
            p.nutriscore?.takeIf { it.length == 1 }?.let { add("Nutri-Score ${it.uppercase()}.") }
        }
        return parts.joinToString(" ") + expiry
    }

    private suspend fun medication(cam: Camera, ui: VisionUi): String {
        ui.say("Tournez lentement la boîte devant le téléphone. Je cherche le petit code carré.")
        val codes = scanCodes(cam, ui, 25)
        val gs1 = codes.firstNotNullOfOrNull { Gs1.parse(it) }
        val f = shot(cam)
        if (gs1 != null) return describeMedication(gs1, f)
        // Pas de code : on lit la boîte.
        val text = f?.let { ocr.read(it).text }.orEmpty()
        val ai = f?.let {
            askAi("Voici une boîte de médicament. Donne son nom, le dosage, et la posologie si elle est écrite sur la boîte ou l'étiquette de la pharmacie. " +
                "Texte lu par OCR : $text", it, Quality.HIGH)
        }
        val body = ai ?: if (text.isNotBlank()) "Je lis sur la boîte : ${text.lines().take(4).joinToString(". ")}." else "Je n'arrive pas à lire la boîte."
        return "$body En cas de doute, vérifiez avec votre pharmacien."
    }

    private suspend fun describeMedication(m: Gs1.Medication, f: Frame?): String {
        val info = m.cip13?.let { medications.byCip13(it) }
        val name = info?.let { "${shortName(it.name)}, ${it.spokenPresentation}." } ?: "Médicament de code ${m.cip13 ?: "inconnu"}."
        val exp = m.expiry?.let { d ->
            if (d.isBefore(today())) " Attention : la date de péremption est dépassée depuis ${month(d)}." else " Périmé à la fin de ${month(d)}."
        } ?: ""
        val posology = f?.let { askAi("Sur cette boîte de médicament, y a-t-il une posologie écrite (étiquette de la pharmacie, mention manuscrite) ? Si oui, cite-la en une phrase ; sinon réponds seulement : aucune.", it) }
            ?.takeIf { !FrenchText.key(it).startsWith("aucune") }?.let { " Posologie écrite : $it" } ?: ""
        return "$name$exp$posology En cas de doute, vérifiez avec votre pharmacien."
    }

    private fun shortName(n: String) = n.substringBefore(",").lowercase().replaceFirstChar { it.uppercase() }
    private fun month(d: LocalDate) = d.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.FRANCE))

    // ------------------------------------------------------------------ recherche d'objet

    private suspend fun find(cam: Camera, ui: VisionUi, what: String): String {
        if (llm == null || !canUseLlm()) return "Pour chercher un objet, j'ai besoin d'internet."
        ui.say("Tournez lentement sur vous-même avec le téléphone devant vous. Je cherche $what.")
        repeat(8) {
            if (ui.stopRequested()) return "D'accord, j'arrête de chercher."
            val f = shot(cam) ?: return "Je n'arrive pas à utiliser la caméra."
            val answer = askAi(
                "Cherche « $what » sur cette image. S'il est visible, réponds en commençant par TROUVÉ, puis sa position avec des repères " +
                    "d'horloge et une distance approximative. Sinon, réponds seulement : NON.", f,
            ) ?: return "Je n'arrive plus à joindre l'intelligence artificielle."
            if (FrenchText.key(answer).startsWith("trouve")) return answer.replace(Regex("^(?iu)trouvé\\s*[:,.]*\\s*"), "")
            ui.tick(false)
        }
        return "Je n'ai pas trouvé $what. Essayez dans une autre pièce, ou demandez à un proche."
    }
}
