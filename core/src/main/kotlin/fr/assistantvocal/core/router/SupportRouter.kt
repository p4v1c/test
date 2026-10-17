package fr.assistantvocal.core.router

import fr.assistantvocal.core.text.FrenchText

// ---------------------------------------------------------------------- intentions de la phase 7

/** Prise en main et entretien. */
sealed interface SupportIntent : Intent
data class Tutorial(val resume: Boolean) : SupportIntent
data object SelfCheck : SupportIntent
data object BackupNow : SupportIntent
data object ReportProblem : SupportIntent

class SupportRouter {
    private val tutorial = Regex("^(?:(?:lance|commence|fais moi|fais|refais|recommence|demarre)(?: le| un)? tutoriel|tutoriel|le tutoriel|apprends moi a t utiliser|montre moi comment tu marches|explique moi comment t utiliser|entrainement|on s entraine)$")
    private val resumeTutorial = Regex("^(?:reprends|continue|reprendre) le tutoriel$")
    private val selfCheck = Regex("^(?:est ce que tout (?:marche|fonctionne|va bien)|tout marche|tout fonctionne|verifie (?:tout|les reglages|que tout marche)|diagnostic|fais (?:un|le) diagnostic|etat de l assistant|tu marches bien)$")
    private val backup = Regex("^(?:sauvegarde|fais une sauvegarde|fais une sauvegarde de|sauvegarde moi)(?: (?:mes|les|de mes|des) (?:reglages|donnees|parametres))?$")
    private val problem = Regex("\\b(signale un probleme|il y a un probleme|ca ne marche pas|ca marche pas|tu t es trompe|tu as mal compris|signaler un probleme|c est faux)\\b")

    fun route(utterance: String): SupportIntent? {
        val k = FrenchText.key(utterance)
        return when {
            resumeTutorial.matches(k) -> Tutorial(resume = true)
            tutorial.matches(k) -> Tutorial(resume = false)
            selfCheck.matches(k) -> SelfCheck
            backup.matches(k) -> BackupNow
            problem.containsMatchIn(k) -> ReportProblem
            else -> null
        }
    }
}
