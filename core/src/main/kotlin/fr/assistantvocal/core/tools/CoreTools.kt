package fr.assistantvocal.core.tools

import fr.assistantvocal.core.assistant.DeviceStatus
import fr.assistantvocal.core.assistant.FrenchSpeech
import fr.assistantvocal.core.name.AssistantNameRules
import fr.assistantvocal.core.settings.AssistantSettings
import fr.assistantvocal.core.settings.Verbosity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Outils de base, disponibles pour l'IA (les mêmes fonctions existent aussi dans le routeur local). */
object CoreTools {

    fun all(settings: AssistantSettings, device: DeviceStatus): List<Tool> = listOf(
        DateTimeTool(device), PhoneStatusTool(device), PreferencesTool(settings), RenameTool(settings),
    )

    /** Fonctions de la phase 3, confiées aux compétences locales. */
    fun phase3(): List<Tool> = listOf(
        IntentTool(toolSpec("call_contact", "Appelle un contact du téléphone (une confirmation sera demandée).") {
            string("name", "Nom, prénom ou surnom du contact (ex. « mon fils »)")
        }) { a -> a.str("name")?.let { fr.assistantvocal.core.router.CallContact(it) } },
        IntentTool(toolSpec("send_sms", "Envoie un SMS à un contact (le texte sera relu et confirmé).") {
            string("name", "Nom du contact"); string("message", "Texte du message", required = false)
        }) { a -> a.str("name")?.let { fr.assistantvocal.core.router.SendSms(it, a.str("message")) } },
        IntentTool(toolSpec("read_sms", "Lit les nouveaux SMS reçus.")) { fr.assistantvocal.core.router.ReadMessages(false) },
        IntentTool(toolSpec("missed_calls", "Indique les appels manqués récents.")) { fr.assistantvocal.core.router.MissedCalls },
        IntentTool(toolSpec("set_alarm", "Règle un réveil.") { string("when", "Moment en français, ex. « demain à 7 heures »") })
            { a -> fr.assistantvocal.core.router.SetAlarm(a.str("when") ?: "") },
        IntentTool(toolSpec("set_timer", "Lance un minuteur.") {
            string("duration", "Durée en français, ex. « 10 minutes »"); string("label", "Pour quoi (ex. « pâtes »)", required = false)
        }) { a -> fr.assistantvocal.core.router.SetTimer("minuteur de ${a.str("duration") ?: ""}" + (a.str("label")?.let { " pour les $it" } ?: "")) },
        IntentTool(toolSpec("create_reminder", "Crée un rappel ou un rappel de médicament.") {
            string("what", "Ce qu'il faut rappeler, ex. « prendre mon Doliprane »")
            string("when", "Quand, en français, ex. « demain à 10 heures » ou « tous les jours à 8 heures »")
        }) { a -> fr.assistantvocal.core.router.CreateReminder("de ${a.str("what") ?: ""} ${a.str("when") ?: ""}") },
        IntentTool(toolSpec("list_reminders", "Liste les rappels, réveils et minuteurs prévus.")) { fr.assistantvocal.core.router.ListReminders },
        IntentTool(toolSpec("use_camera", "Utilise la caméra : décrire la scène, lire un texte, expliquer un courrier, billet, couleur, produit, médicament, chercher un objet.") {
            string("mode", "Ce qu'il faut faire", enum = listOf("scene", "read", "explain", "banknote", "color", "match_colors", "light", "product", "medication", "find"))
            string("question", "Question précise ou objet à chercher", required = false)
        }) { a ->
            val mode = when (a.str("mode")) {
                "read" -> fr.assistantvocal.core.vision.VisionMode.READ; "explain" -> fr.assistantvocal.core.vision.VisionMode.EXPLAIN
                "banknote" -> fr.assistantvocal.core.vision.VisionMode.BANKNOTE; "color" -> fr.assistantvocal.core.vision.VisionMode.COLOR
                "match_colors" -> fr.assistantvocal.core.vision.VisionMode.MATCH_COLORS; "light" -> fr.assistantvocal.core.vision.VisionMode.LIGHT
                "product" -> fr.assistantvocal.core.vision.VisionMode.PRODUCT; "medication" -> fr.assistantvocal.core.vision.VisionMode.MEDICATION
                "find" -> fr.assistantvocal.core.vision.VisionMode.FIND; else -> fr.assistantvocal.core.vision.VisionMode.SCENE
            }
            fr.assistantvocal.core.router.Vision(fr.assistantvocal.core.vision.VisionRequest(mode, a.str("question") ?: ""))
        },
        IntentTool(toolSpec("emergency_sos", "Déclenche l'alerte de secours aux proches. Seulement si la personne est en danger, blessée ou demande de l'aide.")) {
            fr.assistantvocal.core.router.Sos
        },
    )

    class DateTimeTool(private val device: DeviceStatus) : Tool {
        override val spec = toolSpec("get_datetime", "Donne la date et l'heure actuelles en France.")
        override val risk = Risk.SAFE
        override suspend fun execute(args: JsonObject): ToolOutcome {
            val now = device.now()
            val speech = "${FrenchSpeech.date(now)} ${FrenchSpeech.time(now)}"
            return ToolOutcome(true, buildJsonObject { put("date_heure", now.toString()); put("phrase", speech) }, speech)
        }
    }

    class PhoneStatusTool(private val device: DeviceStatus) : Tool {
        override val spec = toolSpec("get_phone_status", "Donne le niveau de batterie et l'état de la connexion internet du téléphone.")
        override val risk = Risk.SAFE
        override suspend fun execute(args: JsonObject): ToolOutcome {
            val b = device.battery()
            val online = device.isOnline()
            return ToolOutcome(true, buildJsonObject {
                if (b != null) { put("batterie_pourcent", b.percent); put("en_charge", b.charging) }
                put("internet", online)
            })
        }
    }

    class PreferencesTool(private val settings: AssistantSettings) : Tool {
        override val spec = toolSpec(
            "set_voice_preferences",
            "Change la façon de parler de l'assistant : longueur des réponses ou vitesse de parole.",
        ) {
            string("verbosity", "Longueur des réponses", required = false, enum = listOf("brief", "normal", "detailed"))
            string("speech_rate", "Vitesse de parole", required = false, enum = listOf("slower", "faster", "normal"))
        }
        override val risk = Risk.SAFE
        override suspend fun execute(args: JsonObject): ToolOutcome {
            when (args.str("verbosity")) {
                "brief" -> settings.verbosity = Verbosity.BRIEF
                "normal" -> settings.verbosity = Verbosity.NORMAL
                "detailed" -> settings.verbosity = Verbosity.DETAILED
            }
            when (args.str("speech_rate")) {
                "slower" -> settings.speechRate -= 0.25f
                "faster" -> settings.speechRate += 0.25f
                "normal" -> settings.speechRate = 1.0f
            }
            return ToolOutcome(true, buildJsonObject {
                put("verbosity", settings.verbosity.name.lowercase()); put("speech_rate", settings.speechRate)
            })
        }
    }

    class RenameTool(private val settings: AssistantSettings) : Tool {
        override val spec = toolSpec("rename_assistant", "Change le nom de l'assistant (qui sert aussi de mot d'activation).") {
            string("new_name", "Le nouveau nom")
        }
        override val risk = Risk.CONFIRM
        override fun confirmationQuestion(args: JsonObject): String {
            val advice = AssistantNameRules.check(args.str("new_name").orEmpty())
            val warn = advice.warnings.joinToString(" ").let { if (it.isEmpty()) "" else "$it " }
            return "${warn}Voulez-vous que je m'appelle ${advice.name} ? Répondez oui ou non."
        }
        override suspend fun execute(args: JsonObject): ToolOutcome {
            val advice = AssistantNameRules.check(args.str("new_name").orEmpty())
            if (!advice.acceptable) return ToolOutcome.error(advice.warnings.joinToString(" "))
            settings.name = advice.name
            return ToolOutcome.ok("C'est fait, je m'appelle maintenant ${advice.name}.")
        }
    }
}
