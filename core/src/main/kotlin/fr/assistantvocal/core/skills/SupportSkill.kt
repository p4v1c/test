package fr.assistantvocal.core.skills

import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.assistant.Dialog
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.assistant.FrenchSpeech
import fr.assistantvocal.core.router.BackupNow
import fr.assistantvocal.core.router.CreateReminder
import fr.assistantvocal.core.router.Intent
import fr.assistantvocal.core.router.OpenBook
import fr.assistantvocal.core.router.ReadMessages
import fr.assistantvocal.core.router.ReportProblem
import fr.assistantvocal.core.router.ResumeMedia
import fr.assistantvocal.core.router.SelfCheck
import fr.assistantvocal.core.router.SupportIntent
import fr.assistantvocal.core.router.Tutorial
import fr.assistantvocal.core.settings.KeyValueStore
import fr.assistantvocal.core.support.Check
import fr.assistantvocal.core.support.Journal
import fr.assistantvocal.core.support.JournalEntry
import fr.assistantvocal.core.text.FrenchText

/**
 * Prise en main et entretien : tutoriel vocal, « est-ce que tout marche ? », sauvegarde à la voix, « signale un problème ».
 *
 * Pendant le tutoriel, les phrases de la personne sont seulement **reconnues**, jamais exécutées (sauf l'heure) :
 * aucun appel, SMS ou rappel n'est créé.
 */
class SupportSkill(
    private val store: KeyValueStore,
    private val dialog: Dialog,
    private val device: DeviceStatus,
    private val name: () -> String,
    /** Classe une phrase sans l'exécuter (routeur local). */
    private val recognize: (String) -> Intent?,
    private val checks: suspend () -> List<Check> = { emptyList() },
    /** Écrit une sauvegarde ; renvoie l'endroit où elle se trouve, ou null en cas d'échec. */
    private val backupNow: suspend () -> String? = { null },
    val journal: Journal = Journal(store),
) {
    suspend fun handle(intent: SupportIntent): AssistantReply = when (intent) {
        is Tutorial -> tutorial(intent.resume)
        SelfCheck -> selfCheck()
        BackupNow -> backup()
        ReportProblem -> report()
    }

    // ------------------------------------------------------------------ tutoriel

    private class Lesson(
        val intro: String,
        /** Ce qu'on attend : null = simple « compris ». */
        val expects: ((Intent?) -> Boolean)?,
        val hint: String,
        val success: (SupportSkill) -> String,
    )

    private val lessons: List<Lesson> = listOf(
        Lesson(
            "Première leçon. Pour me parler, dites mon nom, attendez le petit bip, puis parlez normalement. Pendant ce tutoriel, " +
                "je vous écoute déjà, pas besoin de dire mon nom. Essayez : demandez-moi l'heure.",
            { it == Intent.AskTime }, "Dites simplement : quelle heure est-il ?",
            { s -> "Bravo ! ${FrenchSpeech.time(s.device.now())} C'est exactement comme ça." },
        ),
        Lesson(
            "Deuxième leçon. Si je parle trop longtemps, dites « stop ». Si vous n'avez pas entendu, dites « répète ». Essayez : dites « répète ».",
            { it == Intent.Repeat }, "Dites juste le mot : répète.",
            { "Parfait. Et souvenez-vous : « stop » me fait taire tout de suite." },
        ),
        Lesson(
            "Troisième leçon : téléphoner. Dites « appelle », puis le nom de la personne, ou « mon fils », « ma fille ». " +
                "C'est un exercice : je n'appellerai personne. Essayez : appelle Paul.",
            { it is fr.assistantvocal.core.router.CallContact || it is fr.assistantvocal.core.router.CallFavorite || it is fr.assistantvocal.core.router.CallNumber },
            "Dites : appelle, puis un prénom. Par exemple : appelle Paul.",
            { "Très bien. En vrai, je vous aurais demandé de confirmer, et j'aurais attendu votre « oui » avant d'appeler. Rien ne part jamais sans votre accord." },
        ),
        Lesson(
            "Quatrième leçon : les messages. Pour écouter vos SMS, dites « lis mes messages ». Essayez.",
            { it is ReadMessages || it is fr.assistantvocal.core.router.ReadMail }, "Dites : lis mes messages.",
            { "C'est ça. Et pour répondre, il suffit de dire « réponds » juste après." },
        ),
        Lesson(
            "Cinquième leçon : vos livres, comme sur le Victor. « Reprends mon livre », « chapitre suivant », « où j'en suis », « pose un signet ». " +
                "Si vous me parlez pendant la lecture, le livre se met en pause, puis reprend. Essayez : reprends mon livre.",
            { it is OpenBook || it is ResumeMedia }, "Dites : reprends mon livre.",
            { "Exactement. Vous pouvez aussi dire « mets France Inter » pour la radio." },
        ),
        Lesson(
            "Sixième leçon : les rappels. Par exemple : « rappelle-moi d'appeler le médecin demain à 10 heures ». C'est un exercice, " +
                "je ne garderai rien. Essayez avec un rappel imaginaire.",
            { it is CreateReminder || it is fr.assistantvocal.core.router.SetAlarm || it is fr.assistantvocal.core.router.SetTimer },
            "Commencez par : rappelle-moi de, puis ce qu'il faut faire, et quand.",
            { "Très bien. Pour vos médicaments, dites par exemple : rappelle-moi de prendre mon Doliprane tous les jours à 8 heures." },
        ),
        Lesson(
            "Septième leçon, importante : en cas de problème, dites « au secours », ou gardez le bouton du haut du volume appuyé trois secondes. " +
                "J'attends dix secondes, vous pouvez dire « stop », puis je préviens vos proches avec votre position. Ne l'essayez pas maintenant. " +
                "Dites « compris » pour continuer.",
            null, "Dites : compris.",
            { "Merci." },
        ),
        Lesson(
            "Dernière leçon. Pour tout le reste, dites « qu'est-ce que tu sais faire », ou « aide pour les livres », « aide pour les appels ». Essayez.",
            { it == Intent.Help || it is Intent.HelpCategory }, "Dites : qu'est-ce que tu sais faire ?",
            { "Parfait." },
        ),
    )

    private var step: Int
        get() = store.getInt(K_STEP, 0)
        set(v) = store.putInt(K_STEP, v)

    val tutorialDone: Boolean get() = store.getBoolean(K_DONE, false)

    private fun tutorial(resume: Boolean): AssistantReply {
        if (!resume || step >= lessons.size) step = 0
        val start = if (step == 0) "Bienvenue ! Je suis ${name()}, votre assistant. Ce petit entraînement dure cinq minutes. " +
            "À tout moment, dites « arrête le tutoriel ». " else "On reprend le tutoriel. "
        return ask(start + lessons[step].intro, attempt = 0)
    }

    private val quit = Regex("\\b(arrete le tutoriel|arrete|quitte|plus tard|termine|ca suffit|stop|annule)\\b")

    private fun ask(text: String, attempt: Int): AssistantReply = dialog.ask(text) { answer -> onAnswer(answer, attempt) }

    private fun onAnswer(answer: String, attempt: Int): AssistantReply {
        val lesson = lessons[step]
        val k = FrenchText.key(answer)
        if (quit.containsMatchIn(k) && lesson.expects?.invoke(recognize(answer)) != true) {
            return AssistantReply("D'accord, on s'arrête là. Pour reprendre plus tard, dites : reprends le tutoriel.", Earcon.CANCELLED)
        }
        val ok = lesson.expects?.invoke(recognize(answer)) ?: true
        if (!ok && attempt < 1) return ask("Ce n'est pas tout à fait ça. ${lesson.hint}", attempt + 1)
        val feedback = if (ok) lesson.success(this) else "Ce n'est pas grave, on passe à la suite."
        step += 1
        if (step >= lessons.size) {
            step = 0
            store.putBoolean(K_DONE, true)
            return AssistantReply("$feedback Bravo, le tutoriel est terminé ! Pour le refaire, dites : tutoriel. À bientôt.", Earcon.SUCCESS)
        }
        return ask("$feedback ${lessons[step].intro}", 0).copy(earcon = if (ok) Earcon.SUCCESS else Earcon.CONFIRM)
    }

    // ------------------------------------------------------------------ « est-ce que tout marche ? »

    suspend fun selfCheck(): AssistantReply {
        val list = runCatching { checks() }.getOrDefault(emptyList())
        val bad = list.filter { !it.ok }
        if (bad.isEmpty()) return AssistantReply("Tout fonctionne : ${list.joinToString(", ") { it.label }}.", Earcon.SUCCESS)
        val important = bad.filter { it.important }
        val text = buildString {
            append(if (important.isEmpty()) "L'essentiel fonctionne. " else "Il y a ${bad.size} ${if (bad.size == 1) "point" else "points"} à régler. ")
            bad.sortedByDescending { it.important }.forEach { c -> append("${c.label.replaceFirstChar { it.uppercase() }} : ${c.fix} ") }
            val good = list.filter { it.ok }
            if (good.isNotEmpty()) append("Le reste va bien : ${good.joinToString(", ") { it.label }}.")
        }
        return AssistantReply(text.trim(), if (important.isEmpty()) Earcon.SUCCESS else Earcon.ERROR)
    }

    // ------------------------------------------------------------------ sauvegarde et problème

    private suspend fun backup(): AssistantReply {
        val where = runCatching { backupNow() }.getOrNull()
            ?: return AssistantReply("Je n'ai pas réussi à faire la sauvegarde.", Earcon.ERROR)
        return AssistantReply("C'est sauvegardé. Un proche peut récupérer le fichier dans $where.", Earcon.SUCCESS)
    }

    private fun report(): AssistantReply {
        val last = journal.all().lastOrNull()
        journal.add(JournalEntry(device.now().toInstant().toEpochMilli(), "signale un problème",
            "Problème signalé, dernier échange : « ${last?.heard ?: "aucun"} »", problem = true))
        return AssistantReply("J'ai noté le problème avec notre dernier échange. Un proche pourra le voir dans les réglages, " +
            "bouton « Partager le journal ». Si c'est urgent, dites « appelle » puis le nom d'un proche.", Earcon.SUCCESS)
    }

    companion object {
        private const val K_STEP = "onboarding.tutorialStep"
        private const val K_DONE = "onboarding.tutorialDone"
    }
}
