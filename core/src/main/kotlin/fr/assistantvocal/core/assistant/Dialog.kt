package fr.assistantvocal.core.assistant

import fr.assistantvocal.core.dialog.ConfirmationMachine

/** Question ouverte en attente (« Que voulez-vous lui dire ? ») : la prochaine phrase lui est destinée. */
class PendingQuestion(
    val question: String,
    val createdAtMillis: Long,
    val handler: suspend (String) -> AssistantReply,
)

/** État de la conversation partagé par les compétences : questions ouvertes et confirmations oui/non. */
class Dialog(private val nowMillis: () -> Long) {
    val confirmations = ConfirmationMachine(nowMillis)

    /** Dernier sujet lu (« sms », « mail », « news ») : décide à quoi s'appliquent « réponds » ou « lis le premier ». */
    var lastTopic: String? = null

    var pending: PendingQuestion? = null
        private set

    /** Pose une question ouverte ; la réponse sera passée à [handler]. */
    fun ask(question: String, handler: suspend (String) -> AssistantReply): AssistantReply {
        confirmations.clear()
        pending = PendingQuestion(question, nowMillis(), handler)
        return AssistantReply(question, Earcon.CONFIRM, listenAgain = true)
    }

    /** Demande un « oui » avant [execute] (relecture de l'action). */
    fun confirm(question: String, cancelled: String = "D'accord, j'annule.", execute: suspend () -> String): AssistantReply {
        pending = null
        confirmations.propose(question, cancelled, execute)
        return AssistantReply(question, Earcon.CONFIRM, listenAgain = true)
    }

    /** Récupère (et efface) la question en attente si elle n'a pas expiré (2 minutes). */
    fun takePending(): PendingQuestion? {
        val p = pending ?: return null
        pending = null
        return if (nowMillis() - p.createdAtMillis <= 120_000) p else null
    }

    fun clear() { pending = null; confirmations.clear() }
}
