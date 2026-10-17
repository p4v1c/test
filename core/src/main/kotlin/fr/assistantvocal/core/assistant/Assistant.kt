package fr.assistantvocal.core.assistant

import fr.assistantvocal.core.dialog.ConfirmationMachine
import fr.assistantvocal.core.dialog.ConfirmationOutcome
import fr.assistantvocal.core.llm.FailureKind
import fr.assistantvocal.core.llm.LlmMessage
import fr.assistantvocal.core.llm.LlmPart
import fr.assistantvocal.core.llm.LlmProvider
import fr.assistantvocal.core.llm.LlmRequest
import fr.assistantvocal.core.llm.LlmResult
import fr.assistantvocal.core.llm.Quality
import fr.assistantvocal.core.llm.Role
import fr.assistantvocal.core.name.AssistantNameRules
import fr.assistantvocal.core.router.AnswerCall
import fr.assistantvocal.core.router.CallBack
import fr.assistantvocal.core.router.CallContact
import fr.assistantvocal.core.router.CallFavorite
import fr.assistantvocal.core.router.CallNumber
import fr.assistantvocal.core.router.CancelTimer
import fr.assistantvocal.core.router.ContactNumber
import fr.assistantvocal.core.router.CreateReminder
import fr.assistantvocal.core.router.DeleteReminder
import fr.assistantvocal.core.router.HangUp
import fr.assistantvocal.core.router.Intent
import fr.assistantvocal.core.router.ListReminders
import fr.assistantvocal.core.router.MedicationQuestion
import fr.assistantvocal.core.router.MedicationTaken
import fr.assistantvocal.core.router.MissedCalls
import fr.assistantvocal.core.router.ReadMessages
import fr.assistantvocal.core.router.ReadNotifications
import fr.assistantvocal.core.router.Reply
import fr.assistantvocal.core.router.SendSms
import fr.assistantvocal.core.router.SetAlarm
import fr.assistantvocal.core.router.SetAlias
import fr.assistantvocal.core.router.SetTimer
import fr.assistantvocal.core.router.Sos
import fr.assistantvocal.core.router.Speaker
import fr.assistantvocal.core.router.TimerStatus
import fr.assistantvocal.core.skills.Skills
import fr.assistantvocal.core.text.FrenchText
import fr.assistantvocal.core.tools.IntentTool
import fr.assistantvocal.core.router.LocalRouter
import fr.assistantvocal.core.router.RouteMatch
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.Verbosity
import fr.assistantvocal.core.tools.Risk
import fr.assistantvocal.core.tools.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime

/** Action à lancer par l'application après avoir prononcé la réponse. */
enum class FollowUp { NONE, CALIBRATE_NAME, WAKE_WORD_CHANGED, SOS, VISION, OPEN_LOOKOUT }

/** Sons courts joués avec la réponse. */
enum class Earcon { NONE, LISTENING, THINKING, SUCCESS, ERROR, CONFIRM, CANCELLED }

/**
 * Réponse de l'assistant.
 * [listenAgain] : relancer l'écoute tout de suite (question de confirmation).
 * [stopAll] : couper la parole et la musique de l'assistant.
 */
data class AssistantReply(
    val speech: String,
    val earcon: Earcon = Earcon.NONE,
    val listenAgain: Boolean = false,
    val stopAll: Boolean = false,
    val followUp: FollowUp = FollowUp.NONE,
    /** Séance caméra à lancer (avec [FollowUp.VISION]). */
    val vision: fr.assistantvocal.core.vision.VisionRequest? = null,
)

data class BatteryInfo(val percent: Int, val charging: Boolean)

/** Ce que le cœur a besoin de savoir du téléphone. Implémenté côté Android, simulé dans les tests. */
interface DeviceStatus {
    fun now(): ZonedDateTime
    fun battery(): BatteryInfo?
    fun isOnline(): Boolean

    /** Monte ou baisse le volume ; renvoie le nouveau niveau en pour cent, ou null si impossible. */
    fun adjustVolume(louder: Boolean): Int?
}

/**
 * Cerveau de l'assistant : routeur local d'abord, IA ensuite, confirmations toujours gérées par le code.
 * Ne dépend pas d'Android.
 */
class Assistant(
    private val settings: AssistantSettings,
    private val device: DeviceStatus,
    private val llm: LlmProvider?,
    private val tools: ToolRegistry,
    private val help: HelpCatalog = HelpCatalog.PHASE4,
    private val router: LocalRouter = LocalRouter(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** Appels, SMS, rappels… (absents dans certains tests). */
    private val skills: Skills? = null,
    val dialog: Dialog = skills?.dialog ?: Dialog(nowMillis),
) {
    val confirmations: ConfirmationMachine get() = dialog.confirmations
    private val history = ArrayDeque<LlmMessage>()

    /** Dernière phrase prononcée, pour « répète ». */
    var lastSpeech: String = ""
        private set

    private val name get() = settings.name

    /** Positionné par une action confirmée (ex. renommage) pour déclencher une suite (calibration). */
    private var pendingFollowUp = FollowUp.NONE

    suspend fun handle(utterance: String, image: ByteArray? = null, quality: Quality = Quality.STANDARD): AssistantReply {
        val text = utterance.trim()
        val reply = when {
            text.isEmpty() && image == null -> AssistantReply("Je n'ai rien entendu. Vous pouvez répéter ?", Earcon.ERROR, listenAgain = false)
            else -> process(text, image, quality)
        }
        if (reply.speech.isNotBlank() && !isRepeat(reply)) lastSpeech = reply.speech
        return reply
    }

    private fun isRepeat(reply: AssistantReply) = reply.speech == lastSpeech

    private suspend fun process(text: String, image: ByteArray?, quality: Quality): AssistantReply {
        settings.lastInteractionMs = nowMillis()
        // 0. Une question ouverte est en attente (« Que voulez-vous lui dire ? ») : la phrase est la réponse.
        dialog.takePending()?.let { q ->
            val k = FrenchText.key(text)
            if (k in setOf("stop", "annule", "laisse tomber", "rien", "oublie", "arrete", "non rien")) {
                return remember(text, AssistantReply("D'accord, j'annule.", Earcon.CANCELLED))
            }
            return remember(text, q.handler(text))
        }
        // 1. Une confirmation est en attente : la phrase est une réponse oui/non.
        if (confirmations.hasPending()) {
            when (val outcome = confirmations.answer(text)) {
                is ConfirmationOutcome.Executed -> {
                    val follow = pendingFollowUp.also { pendingFollowUp = FollowUp.NONE }
                    return remember(text, AssistantReply(outcome.speech, Earcon.SUCCESS, followUp = follow))
                }
                is ConfirmationOutcome.Cancelled -> return remember(text, AssistantReply(outcome.speech, Earcon.CANCELLED))
                is ConfirmationOutcome.AskAgain -> return AssistantReply(outcome.speech, Earcon.CONFIRM, listenAgain = true)
                null -> Unit
            }
        }

        // 2. Routeur local (sauf si une image accompagne la demande : c'est pour l'IA).
        val match = if (image == null) router.route(text) else null
        if (match != null && match.confidence >= 0.8) return remember(text, handleIntent(match.intent))

        // 3. IA si possible.
        val llmBlocker = llmBlocker()
        if (llmBlocker == null) return askLlm(text, image, quality, match)

        // 4. Pas d'IA : on vérifie l'intention moyenne par une question fermée, sinon on explique.
        if (match != null) {
            val question = clarifyQuestion(match)
            confirmations.propose(question, cancelledMessage = "D'accord. Pouvez-vous reformuler ?") { handleIntent(match.intent).speech }
            return AssistantReply(question, Earcon.CONFIRM, listenAgain = true)
        }
        return AssistantReply(llmBlocker, Earcon.ERROR)
    }

    /** Raison pour laquelle l'IA n'est pas utilisable, ou null si elle l'est. */
    private fun llmBlocker(): String? = when {
        settings.privateMode ->
            "Je suis en mode privé, je ne réponds qu'aux commandes simples comme l'heure ou la batterie. " +
                "Dites « quitte le mode privé » pour que je puisse répondre aux questions."
        llm == null || !llm.isConfigured() ->
            "Je n'ai pas compris, et aucune intelligence artificielle n'est configurée pour m'aider. " +
                "Demandez à un proche d'ajouter une clé dans les réglages."
        !device.isOnline() ->
            "Je n'ai pas internet pour l'instant, je ne peux pas répondre à cette question. " +
                "Je peux quand même vous donner l'heure, la date ou la batterie."
        else -> null
    }

    private fun clarifyQuestion(match: RouteMatch): String = when (val i = match.intent) {
        Intent.AskTime -> "Voulez-vous savoir l'heure ?"
        Intent.AskDate -> "Voulez-vous savoir la date ?"
        Intent.AskBattery -> "Voulez-vous connaître le niveau de batterie ?"
        is Intent.ChangeName -> "Voulez-vous que je m'appelle ${i.newName} ?"
        else -> "Je ne suis pas sûr d'avoir compris. Voulez-vous que je fasse : ${describe(i)} ?"
    }

    private fun describe(i: Intent): String = when (i) {
        Intent.AskNetwork -> "vérifier internet"
        is Intent.SpeechRate -> if (i.faster) "parler plus vite" else "parler moins vite"
        is Intent.Volume -> if (i.louder) "monter le volume" else "baisser le volume"
        is Intent.SetVerbosity -> if (i.brief) "répondre plus brièvement" else "donner plus de détails"
        is Intent.PrivateMode -> if (i.enable) "activer le mode privé" else "quitter le mode privé"
        else -> "cette action"
    }

    // ---------------------------------------------------------------- intentions locales

    internal suspend fun handleIntent(intent: Intent): AssistantReply = when (intent) {
        Intent.AskTime -> AssistantReply(FrenchSpeech.time(device.now()))
        Intent.AskDate -> AssistantReply(FrenchSpeech.date(device.now()))
        Intent.AskBattery -> device.battery()?.let { AssistantReply(FrenchSpeech.battery(it.percent, it.charging)) }
            ?: AssistantReply("Je n'arrive pas à lire le niveau de batterie.", Earcon.ERROR)
        Intent.AskNetwork -> AssistantReply(
            if (device.isOnline()) "Oui, vous êtes connecté à internet." else "Non, il n'y a pas de connexion internet pour l'instant."
        )
        Intent.Stop -> { dialog.clear(); AssistantReply("", Earcon.CANCELLED, stopAll = true) }
        Intent.Cancel -> { dialog.clear(); AssistantReply("D'accord.", Earcon.CANCELLED) }
        Intent.Repeat -> AssistantReply(lastSpeech.ifBlank { "Je n'ai encore rien dit." })
        Intent.Help -> AssistantReply(help.overview(name))
        is Intent.HelpCategory -> AssistantReply(help.topic(intent.category) ?: help.overview(name))
        Intent.WhoAreYou -> AssistantReply("Je suis $name, votre assistant vocal. Que puis-je faire pour vous ?", listenAgain = true)
        Intent.Greeting -> AssistantReply("Bonjour ! Je suis $name, que puis-je faire ?", listenAgain = true)
        Intent.Thanks -> AssistantReply("Avec plaisir.")
        is Intent.SpeechRate -> {
            val before = settings.speechRate
            settings.speechRate = before + if (intent.faster) 0.25f else -0.25f
            when {
                settings.speechRate == before && intent.faster -> AssistantReply("Je parle déjà à la vitesse maximale.")
                settings.speechRate == before -> AssistantReply("Je parle déjà à la vitesse minimale.")
                intent.faster -> AssistantReply("D'accord, je parle plus vite. C'est mieux comme ça ?", Earcon.SUCCESS)
                else -> AssistantReply("D'accord, je parle moins vite. C'est mieux comme ça ?", Earcon.SUCCESS)
            }
        }
        is Intent.Volume -> {
            val level = device.adjustVolume(intent.louder)
            when {
                level == null -> AssistantReply("Je n'arrive pas à changer le volume.", Earcon.ERROR)
                level >= 100 && intent.louder -> AssistantReply("Le volume est au maximum.")
                else -> AssistantReply("Volume à $level pour cent.", Earcon.SUCCESS)
            }
        }
        is Intent.SetVerbosity -> {
            settings.verbosity = if (intent.brief) {
                if (settings.verbosity == Verbosity.DETAILED) Verbosity.NORMAL else Verbosity.BRIEF
            } else {
                if (settings.verbosity == Verbosity.BRIEF) Verbosity.NORMAL else Verbosity.DETAILED
            }
            AssistantReply(
                when (settings.verbosity) {
                    Verbosity.BRIEF -> "D'accord, je serai bref."
                    Verbosity.NORMAL -> "D'accord, je reviens à des réponses normales."
                    Verbosity.DETAILED -> "D'accord, je donnerai plus de détails."
                },
                Earcon.SUCCESS,
            )
        }
        is Intent.PrivateMode -> {
            settings.privateMode = intent.enable
            AssistantReply(
                if (intent.enable) "Mode privé activé. Je n'envoie plus rien à l'intelligence artificielle, je ne réponds qu'aux commandes simples."
                else "Mode privé désactivé. Je peux de nouveau répondre à vos questions.",
                Earcon.SUCCESS,
            )
        }
        is Intent.ChangeName -> proposeRename(intent.newName)
        Sos -> AssistantReply("", Earcon.NONE, followUp = FollowUp.SOS)
        is fr.assistantvocal.core.router.Vision -> AssistantReply("", Earcon.NONE, followUp = FollowUp.VISION, vision = intent.request)
        fr.assistantvocal.core.router.OpenLookout -> AssistantReply("J'ouvre Lookout.", followUp = FollowUp.OPEN_LOOKOUT)
        is fr.assistantvocal.core.router.DocumentQuestion -> {
            val v = skills?.vision
            when {
                v != null && v.documents.isFresh(nowMillis()) -> AssistantReply(v.documentQuestion(intent.text))
                // Pas de document récent : « que dois-je faire » concerne sans doute une lettre à lire.
                Regex("\\b(que dois je faire|qu est ce que je dois faire)\\b").containsMatchIn(FrenchText.key(intent.text)) ->
                    AssistantReply("", followUp = FollowUp.VISION, vision = fr.assistantvocal.core.vision.VisionRequest(fr.assistantvocal.core.vision.VisionMode.EXPLAIN))
                llmBlocker() == null -> askLlm(intent.text, null, Quality.STANDARD, null)
                else -> AssistantReply("Je n'ai pas de document récent en mémoire. Dites : lis ce texte.", Earcon.ERROR)
            }
        }
        is CallContact, is CallNumber, is CallFavorite, CallBack, AnswerCall, HangUp, is Speaker, MissedCalls,
        is ReadMessages, is SendSms, is Reply, ReadNotifications, is SetAlias, is ContactNumber,
        is SetAlarm, is SetTimer, TimerStatus, is CancelTimer, is CreateReminder, ListReminders, is DeleteReminder,
        is MedicationTaken, is MedicationQuestion -> skills?.handle(intent)
            ?: AssistantReply("Cette fonction n'est pas encore disponible.", Earcon.ERROR)
        Intent.CalibrateName -> AssistantReply(
            "D'accord. Après chaque bip, dites simplement mon nom, $name. Je vais vous le demander trois fois.",
            followUp = FollowUp.CALIBRATE_NAME,
        )
        is Intent.WakeWord -> {
            settings.wakeWordEnabled = intent.enable
            AssistantReply(
                if (intent.enable) "D'accord, je réagis de nouveau quand vous dites $name."
                else "D'accord, je ne réagis plus à mon nom. Pour me parler, appuyez sur le grand bouton, sur le bouton du casque, ou gardez le bouton volume bas appuyé.",
                Earcon.SUCCESS, followUp = FollowUp.WAKE_WORD_CHANGED,
            )
        }
    }

    /** Changement de nom : vérifie, prévient des risques, puis demande confirmation. */
    fun proposeRename(newName: String): AssistantReply {
        val advice = AssistantNameRules.check(newName)
        if (!advice.acceptable) return AssistantReply(advice.warnings.joinToString(" ") + " Je garde mon nom actuel.", Earcon.ERROR)
        val warning = advice.warnings.joinToString(" ").let { if (it.isEmpty()) "" else "$it " }
        val question = "${warning}Voulez-vous que je m'appelle ${advice.name} ? Répondez oui ou non."
        confirmations.propose(question, cancelledMessage = "D'accord, je garde le nom $name.") {
            settings.name = advice.name
            pendingFollowUp = FollowUp.CALIBRATE_NAME
            "C'est fait, je m'appelle maintenant ${advice.name}. Pour que je reconnaisse bien ce nom, " +
                "dites-le après chacun des trois bips qui suivent."
        }
        return AssistantReply(question, Earcon.CONFIRM, listenAgain = true)
    }

    // ---------------------------------------------------------------- IA

    private suspend fun askLlm(text: String, image: ByteArray?, quality: Quality, hint: RouteMatch?): AssistantReply {
        val llm = llm!!
        val userParts = buildList<LlmPart> {
            if (image != null) add(LlmPart.Image(image))
            add(LlmPart.Text(text.ifEmpty { "Décris cette image." }))
        }
        val turn = mutableListOf(LlmMessage(Role.USER, userParts))
        val notices = mutableListOf<String>()

        repeat(MAX_TOOL_ROUNDS) {
            val request = LlmRequest(
                system = Prompts.system(name, settings.verbosity, device.now()),
                messages = history.toList() + turn,
                tools = tools.specs(),
                maxOutputTokens = if (settings.verbosity == Verbosity.DETAILED) 2048 else 1024,
                quality = quality,
            )
            when (val result = llm.generate(request)) {
                is LlmResult.Failure -> {
                    result.notice?.let { notices += it }
                    return AssistantReply(join(notices, failureSpeech(result.kind)), Earcon.ERROR)
                }
                is LlmResult.Reply -> {
                    result.notice?.let { if (it !in notices) notices += it }
                    val calls = result.toolCalls
                    if (calls.isEmpty()) {
                        val answer = FrenchSpeech.clean(result.text).ifEmpty { "Je n'ai pas de réponse à cette question." }
                        rememberLlm(text, answer)
                        return AssistantReply(join(notices, answer))
                    }
                    // Une fonction « intention » (appeler, écrire, rappeler…) est confiée aux compétences locales,
                    // qui posent elles-mêmes les questions et confirmations nécessaires.
                    calls.firstNotNullOfOrNull { c -> (tools[c.name] as? IntentTool)?.toIntent(c.args) }?.let { intent ->
                        val reply = handleIntent(intent)
                        rememberLlm(text, reply.speech)
                        return reply.copy(speech = join(notices, reply.speech))
                    }
                    // Un appel sensible arrête la boucle : le code demande confirmation, l'IA n'exécute rien.
                    val sensitive = calls.firstOrNull { tools[it.name]?.risk?.let { r -> r != Risk.SAFE } == true }
                    if (sensitive != null) {
                        val tool = tools[sensitive.name]!!
                        val question = tool.confirmationQuestion(sensitive.args)
                        confirmations.propose(question) {
                            val outcome = runCatching { tool.execute(sensitive.args) }.getOrElse {
                                fr.assistantvocal.core.tools.ToolOutcome.error("Cela n'a pas fonctionné.")
                            }
                            outcome.speech ?: if (outcome.success) "C'est fait." else "Cela n'a pas fonctionné."
                        }
                        return AssistantReply(join(notices, question), Earcon.CONFIRM, listenAgain = true)
                    }
                    turn += LlmMessage(Role.ASSISTANT, result.parts)
                    val results = calls.map { call ->
                        val tool = tools[call.name]
                        if (tool == null) {
                            LlmPart.ToolResult(call.id, call.name, buildJsonObject { put("erreur", "fonction inconnue") }, isError = true)
                        } else {
                            val outcome = runCatching { tool.execute(call.args) }.getOrElse {
                                fr.assistantvocal.core.tools.ToolOutcome.error("erreur interne : ${it.message}")
                            }
                            LlmPart.ToolResult(call.id, call.name, outcome.data, isError = !outcome.success)
                        }
                    }
                    turn += LlmMessage(Role.USER, results)
                }
            }
        }
        return AssistantReply(join(notices, "Je n'ai pas réussi à terminer cette demande."), Earcon.ERROR)
    }

    private fun failureSpeech(kind: FailureKind): String = when (kind) {
        FailureKind.QUOTA -> "J'ai atteint ma limite de questions pour aujourd'hui. Les commandes simples marchent toujours, et je pourrai répondre de nouveau demain matin."
        FailureKind.NETWORK -> "Je n'arrive pas à joindre internet. Réessayez dans un moment."
        FailureKind.AUTH -> "La clé de l'intelligence artificielle ne fonctionne pas. Demandez à un proche de vérifier les réglages."
        FailureKind.BLOCKED -> "Je ne peux pas répondre à cette demande."
        FailureKind.NOT_CONFIGURED -> "Aucune intelligence artificielle n'est configurée. Demandez à un proche d'ajouter une clé dans les réglages."
        FailureKind.SERVER, FailureKind.BAD_REQUEST -> "Le service ne répond pas correctement. Réessayez dans un moment."
    }

    private fun join(notices: List<String>, speech: String) = (notices + speech).joinToString(" ")

    // ---------------------------------------------------------------- historique (6 derniers échanges)

    private fun remember(userText: String, reply: AssistantReply): AssistantReply {
        if (reply.speech.isNotBlank() && userText.isNotBlank()) rememberLlm(userText, reply.speech)
        return reply
    }

    private fun rememberLlm(userText: String, answer: String) {
        history.addLast(LlmMessage.user(userText))
        history.addLast(LlmMessage.assistant(answer))
        while (history.size > MAX_HISTORY_EXCHANGES * 2) history.removeFirst()
    }

    fun historySize(): Int = history.size / 2

    fun clearHistory() = history.clear()

    companion object {
        const val MAX_HISTORY_EXCHANGES = 6
        const val MAX_TOOL_ROUNDS = 4
    }
}
