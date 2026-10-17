package fr.assistantvocal.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.role.RoleManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import fr.assistantvocal.core.llm.LlmMessage
import fr.assistantvocal.core.llm.LlmRequest
import fr.assistantvocal.core.llm.LlmResult
import fr.assistantvocal.core.name.AssistantNameRules
import fr.assistantvocal.core.phone.PeopleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Réglages pour le proche qui installe l'application : nom, clés API, modèles, limites, vitesse.
 * Écran simple, entièrement lisible avec TalkBack (chaque champ a son étiquette).
 */
class SettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var graph: AppGraph
    private val ids = generateSequence(1000) { it + 1 }.iterator()

    lateinit var nameField: EditText; private set
    lateinit var geminiKeyField: EditText; private set
    lateinit var claudeKeyField: EditText; private set
    private lateinit var geminiModelField: EditText
    private lateinit var geminiHighModelField: EditText
    private lateinit var claudeModelField: EditText
    private lateinit var geminiLimitField: EditText
    private lateinit var claudeLimitField: EditText
    private lateinit var rateField: EditText
    private lateinit var orderGeminiFirst: RadioButton
    lateinit var wakeBox: CheckBox; private set
    private lateinit var headsetBox: CheckBox
    private lateinit var volumeBox: CheckBox
    lateinit var triggersStatus: TextView; private set
    lateinit var ownerField: EditText; private set
    lateinit var emergencyField: EditText; private set
    lateinit var favoritesField: EditText; private set
    lateinit var aliasesField: EditText; private set
    private lateinit var wellbeingField: EditText
    private lateinit var remoteField: EditText
    private lateinit var smsRead: RadioButton
    private lateinit var smsAnnounce: RadioButton
    private lateinit var notifBox: CheckBox
    private lateinit var speakerBox: CheckBox
    lateinit var status: TextView; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = App.graph(this)
        val s = graph.settings
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }

        nameField = field(root, R.string.label_name, s.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        button(root, R.string.test_name) { testName() }

        // Déclencheurs
        wakeBox = check(root, R.string.wake_enabled, s.wakeWordEnabled)
        headsetBox = check(root, R.string.headset_enabled, s.headsetButtonEnabled)
        volumeBox = check(root, R.string.volume_enabled, s.volumeKeyEnabled)
        button(root, R.string.calibrate) { save(); startForegroundService(AssistantService.intent(this, AssistantService.ACTION_CALIBRATE)) }
        button(root, R.string.default_assistant) { openDefaultAssistantSettings() }
        button(root, R.string.accessibility_settings) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        triggersStatus = TextView(this).apply { textSize = 16f; setPadding(0, 8, 0, 8) }
        root.addView(triggersStatus)

        // Proches et sécurité
        ownerField = field(root, R.string.label_owner, if (s.ownerName == "Votre proche") "" else s.ownerName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        emergencyField = field(root, R.string.label_emergency, PeopleStore.formatEmergency(graph.people.emergencyContacts), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        favoritesField = field(root, R.string.label_favorites, graph.people.favorites.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}: ${it.value}" })
        aliasesField = field(root, R.string.label_aliases, graph.people.aliases.entries.joinToString("; ") { "${it.key}: ${it.value}" })
        wellbeingField = field(root, R.string.label_wellbeing, s.wellbeingTime, InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        remoteField = field(root, R.string.label_remote, s.remoteSecret)
        root.addView(label(R.string.label_sms_mode))
        val smsGroup = RadioGroup(this)
        smsRead = RadioButton(this).apply { id = ids.next(); setText(R.string.sms_read) }
        smsAnnounce = RadioButton(this).apply { id = ids.next(); setText(R.string.sms_announce) }
        val smsOff = RadioButton(this).apply { id = ids.next(); setText(R.string.sms_off) }
        listOf(smsRead, smsAnnounce, smsOff).forEach { smsGroup.addView(it) }
        smsGroup.check(when (s.smsAnnounce) { "read" -> smsRead.id; "off" -> smsOff.id; else -> smsAnnounce.id })
        root.addView(smsGroup)
        notifBox = check(root, R.string.notif_announce, s.announceNotifications)
        speakerBox = check(root, R.string.speaker_on_answer, s.speakerOnAnswer)
        button(root, R.string.notification_access) { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        geminiKeyField = secretField(root, R.string.label_gemini_key, AppGraph.SECRET_GEMINI)
        geminiModelField = field(root, R.string.label_gemini_model, s.geminiModel)
        geminiHighModelField = field(root, R.string.label_gemini_high_model, s.geminiHighModel)
        claudeKeyField = secretField(root, R.string.label_claude_key, AppGraph.SECRET_CLAUDE)
        claudeModelField = field(root, R.string.label_claude_model, s.claudeModel)

        root.addView(label(R.string.label_order))
        val group = RadioGroup(this)
        orderGeminiFirst = RadioButton(this).apply { id = ids.next(); setText(R.string.order_gemini_first) }
        val claudeFirst = RadioButton(this).apply { id = ids.next(); setText(R.string.order_claude_first) }
        group.addView(orderGeminiFirst); group.addView(claudeFirst)
        group.check(if (s.providerOrder.firstOrNull() == "claude") claudeFirst.id else orderGeminiFirst.id)
        root.addView(group)

        geminiLimitField = field(root, R.string.label_gemini_limit, s.dailyLimit("gemini").toString(), InputType.TYPE_CLASS_NUMBER)
        claudeLimitField = field(root, R.string.label_claude_limit, s.dailyLimit("claude").toString(), InputType.TYPE_CLASS_NUMBER)
        rateField = field(root, R.string.label_rate, s.speechRate.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        button(root, R.string.save) { save() }
        button(root, R.string.test_ai) { testAi() }
        button(root, R.string.battery_optimization) { askBatteryExemption() }
        status = TextView(this).apply {
            textSize = 18f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        triggersStatus.text = triggersReport()
    }

    /** État des déclencheurs, lisible par le proche (et par TalkBack). */
    fun triggersReport(): String {
        val s = graph.settings
        val assistant = runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
        val am = getSystemService(AccessibilityManager::class.java)
        val keys = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo.serviceInfo.packageName == packageName } == true
        return listOf(
            (if (assistant) "✔ " else "✘ ") + "Assistant par défaut" + (if (assistant) "" else " : à activer pour l'écoute au démarrage et écran éteint"),
            (if (keys) "✔ " else "✘ ") + "Boutons volume" + (if (keys) "" else " : service d'accessibilité à activer"),
            (if (s.isWakeCalibrated) "✔ " else "• ") + "Nom " + (if (s.isWakeCalibrated) "appris" else "pas encore appris : touchez « Apprendre la prononciation du nom »"),
            (if (graph.people.emergencyContacts.isNotEmpty()) "✔ " else "✘ ") + "Contacts d'urgence" +
                (if (graph.people.emergencyContacts.isNotEmpty()) " : " + graph.people.emergencyContacts.joinToString { it.name } else " : à remplir pour le SOS"),
            (if (android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true) "✔ " else "✘ ") +
                "Lecture des notifications",
        ).joinToString("\n")
    }

    private fun openDefaultAssistantSettings() {
        val candidates = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        candidates.firstOrNull { runCatching { startActivity(it) }.isSuccess }
    }

    /** « 1: Pierre Dupont; 2: 0612345678 » -> map. */
    private fun pairs(text: String): Map<String, String> = text.split(';', '\n').mapNotNull { part ->
        val i = part.indexOf(':')
        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

    private fun check(root: LinearLayout, textRes: Int, checked: Boolean): CheckBox =
        CheckBox(this).apply { setText(textRes); isChecked = checked; textSize = 18f; minHeight = 96 }.also { root.addView(it) }

    private fun label(res: Int) = TextView(this).apply { setText(res); textSize = 18f; setPadding(0, 24, 0, 4) }

    private fun field(root: LinearLayout, labelRes: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val edit = EditText(this).apply { id = ids.next(); setText(value); inputType = type; textSize = 18f }
        root.addView(label(labelRes).apply { labelFor = edit.id })
        root.addView(edit)
        return edit
    }

    private fun secretField(root: LinearLayout, labelRes: Int, secretName: String): EditText {
        val edit = field(root, labelRes, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        edit.setHint(if (graph.secrets.has(secretName)) R.string.key_saved_hint else R.string.key_empty_hint)
        return edit
    }

    private fun button(root: LinearLayout, textRes: Int, onClick: () -> Unit) {
        root.addView(Button(this).apply { setText(textRes); isAllCaps = false; textSize = 18f; setOnClickListener { onClick() } })
    }

    private fun report(message: String) {
        status.text = message
    }

    private fun speak(text: String) {
        startForegroundService(AssistantService.intent(this, AssistantService.ACTION_SAY).putExtra(AssistantService.EXTRA_TEXT, text))
    }

    private fun testName() {
        val advice = AssistantNameRules.check(nameField.text.toString())
        val msg = if (!advice.acceptable) advice.warnings.joinToString(" ")
        else (advice.warnings + "Je m'appellerai ${advice.name}. Nom de ${advice.syllables} syllabes.").joinToString(" ")
        report(msg)
        if (advice.acceptable) speak("Bonjour, je m'appelle ${advice.name}.")
    }

    fun save() {
        val s = graph.settings
        val advice = AssistantNameRules.check(nameField.text.toString())
        if (advice.acceptable) s.name = advice.name
        geminiKeyField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { graph.secrets.put(AppGraph.SECRET_GEMINI, it) }
        claudeKeyField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { graph.secrets.put(AppGraph.SECRET_CLAUDE, it) }
        geminiKeyField.setText(""); claudeKeyField.setText("")
        if (graph.secrets.has(AppGraph.SECRET_GEMINI)) geminiKeyField.setHint(R.string.key_saved_hint)
        if (graph.secrets.has(AppGraph.SECRET_CLAUDE)) claudeKeyField.setHint(R.string.key_saved_hint)
        s.geminiModel = geminiModelField.text.toString()
        s.geminiHighModel = geminiHighModelField.text.toString()
        s.claudeModel = claudeModelField.text.toString()
        s.providerOrder = if (orderGeminiFirst.isChecked) listOf("gemini", "claude") else listOf("claude", "gemini")
        geminiLimitField.text.toString().toIntOrNull()?.let { s.setDailyLimit("gemini", it) }
        claudeLimitField.text.toString().toIntOrNull()?.let { s.setDailyLimit("claude", it) }
        rateField.text.toString().replace(',', '.').toFloatOrNull()?.let { s.speechRate = it }
        s.ownerName = ownerField.text.toString()
        graph.people.emergencyContacts = PeopleStore.parseEmergency(emergencyField.text.toString())
        graph.people.favorites = pairs(favoritesField.text.toString()).filterKeys { it.toIntOrNull() in 1..9 }
        graph.people.aliases = pairs(aliasesField.text.toString()).mapKeys { PeopleStore.normalizeAlias(it.key) }
        s.wellbeingTime = wellbeingField.text.toString().replace('h', ':').let { t ->
            runCatching { java.time.LocalTime.parse(if (t.length == 4) "0$t" else t).toString() }.getOrDefault("")
        }
        s.remoteSecret = remoteField.text.toString()
        s.smsAnnounce = when { smsRead.isChecked -> "read"; smsAnnounce.isChecked -> "announce"; else -> "off" }
        s.announceNotifications = notifBox.isChecked
        s.speakerOnAnswer = speakerBox.isChecked
        s.wakeWordEnabled = wakeBox.isChecked
        s.headsetButtonEnabled = headsetBox.isChecked
        s.volumeKeyEnabled = volumeBox.isChecked
        runCatching { startForegroundService(AssistantService.intent(this, AssistantService.ACTION_SETTINGS_CHANGED)) }
        triggersStatus.text = triggersReport()
        val warn = if (!advice.acceptable) " Nom refusé : " + advice.warnings.joinToString(" ") else ""
        report(getString(R.string.saved) + warn)
    }

    private fun testAi() {
        save()
        report("Test en cours…")
        scope.launch {
            val r = graph.llm.generate(LlmRequest("Réponds en une phrase courte, en français.", listOf(LlmMessage.user("Dis bonjour."))))
            val msg = when (r) {
                is LlmResult.Reply -> listOfNotNull(r.notice, "Réponse de ${r.providerId} : ${r.text}").joinToString(" ")
                is LlmResult.Failure -> listOfNotNull(r.notice, "Échec (${r.kind}) : ${r.message.take(200)}").joinToString(" ")
            }
            report(msg)
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { report("L'application est déjà autorisée en arrière-plan."); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
