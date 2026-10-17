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
import android.widget.Button
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
    lateinit var status: TextView; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = App.graph(this)
        val s = graph.settings
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }

        nameField = field(root, R.string.label_name, s.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        button(root, R.string.test_name) { testName() }
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
