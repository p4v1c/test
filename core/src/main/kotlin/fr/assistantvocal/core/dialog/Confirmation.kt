package fr.assistantvocal.core.dialog

import fr.assistantvocal.core.text.FrenchText

enum class YesNo { YES, NO, UNKNOWN }

/** Interprète une réponse à une question fermée. Prudent : dans le doute, UNKNOWN (donc pas d'action). */
object YesNoParser {
    private val YES = setOf(
        "oui", "ouais", "ouai", "oui oui", "oui merci", "oui vas y", "vas y", "d accord", "ok", "okay", "confirme",
        "je confirme", "c est ca", "exactement", "tout a fait", "affirmatif", "envoie", "envoie le", "appelle",
        "appelle le", "appelle la", "fais le", "go", "bien sur", "oui s il te plait", "oui c est bon", "c est bon",
        "parfait", "oui je confirme", "volontiers", "absolument", "carrement", "pas de probleme",
        "oui pas de probleme", "sans probleme",
    )
    private val NO = setOf(
        "non", "non merci", "nan", "annule", "annuler", "stop", "arrete", "surtout pas", "pas du tout",
        "laisse tomber", "attends", "non non", "negatif", "ne fais rien", "n envoie pas", "n appelle pas",
        "pas maintenant", "oublie", "non annule", "c est pas ca", "ce n est pas ca", "faux",
    )

    fun parse(utterance: String): YesNo {
        val k = FrenchText.key(utterance)
        if (k.isEmpty()) return YesNo.UNKNOWN
        if (k in NO) return YesNo.NO
        if (k in YES) return YesNo.YES
        val words = k.split(' ')
        // Phrases courtes commençant clairement par oui/non
        if (words.size <= 5) {
            val hasNo = words.any { it == "non" || it == "annule" || it == "pas" || it == "stop" }
            val startsYes = words.first() in setOf("oui", "ouais", "ok", "d")
            if (hasNo) return YesNo.NO
            if (startsYes && (words.first() != "d" || words.getOrNull(1) == "accord")) return YesNo.YES
        }
        return YesNo.UNKNOWN
    }
}

/**
 * Action en attente de confirmation vocale. Le code — jamais l'IA — décide de l'exécution.
 * [question] est relue à l'utilisateur ; [execute] n'est appelée qu'après un « oui » explicite.
 */
class PendingAction(
    val question: String,
    val execute: suspend () -> String,
    val cancelledMessage: String = "D'accord, j'annule.",
    val createdAtMillis: Long,
    val timeoutMillis: Long = 60_000,
) {
    var unclearAnswers = 0
        internal set
}

sealed interface ConfirmationOutcome {
    data class Executed(val speech: String) : ConfirmationOutcome
    data class Cancelled(val speech: String) : ConfirmationOutcome
    data class AskAgain(val speech: String) : ConfirmationOutcome
}

/** Gère une seule action en attente à la fois. */
class ConfirmationMachine(private val nowMillis: () -> Long) {
    var pending: PendingAction? = null
        private set

    fun propose(question: String, cancelledMessage: String = "D'accord, j'annule.", execute: suspend () -> String): PendingAction {
        val p = PendingAction(question, execute, cancelledMessage, nowMillis())
        pending = p
        return p
    }

    fun hasPending(): Boolean {
        val p = pending ?: return false
        if (nowMillis() - p.createdAtMillis > p.timeoutMillis) { pending = null; return false }
        return true
    }

    fun clear() { pending = null }

    suspend fun answer(utterance: String): ConfirmationOutcome? {
        if (!hasPending()) return null
        val p = pending!!
        return when (YesNoParser.parse(utterance)) {
            YesNo.YES -> { pending = null; ConfirmationOutcome.Executed(p.execute()) }
            YesNo.NO -> { pending = null; ConfirmationOutcome.Cancelled(p.cancelledMessage) }
            YesNo.UNKNOWN -> {
                p.unclearAnswers++
                if (p.unclearAnswers >= 2) {
                    pending = null
                    ConfirmationOutcome.Cancelled("Je n'ai pas compris, alors j'annule par sécurité.")
                } else {
                    ConfirmationOutcome.AskAgain("Je n'ai pas compris. Répondez oui ou non. ${p.question}")
                }
            }
        }
    }
}
