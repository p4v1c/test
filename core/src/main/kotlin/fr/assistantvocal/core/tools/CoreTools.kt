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
