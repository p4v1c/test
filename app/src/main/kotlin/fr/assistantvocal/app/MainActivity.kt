package fr.assistantvocal.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

/**
 * Écran principal : un seul gros bouton « Parler ». Compatible TalkBack : un double tap sur le bouton
 * lance l'écoute ; aucun geste personnalisé n'entre en conflit avec ceux de TalkBack.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val name = App.graph(this).settings.name
        findViewById<Button>(R.id.talk).apply {
            contentDescription = getString(R.string.talk_description, name)
            setOnClickListener { talk() }
        }
        findViewById<Button>(R.id.tutorial).setOnClickListener {
            if (hasMic()) startForegroundService(AssistantService.intent(this, AssistantService.ACTION_TUTORIAL)) else requestNeededPermissions()
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Switch>(R.id.power).setOnClickListener { v -> onPowerToggled((v as Switch).isChecked) }
        refresh()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // L'activité est visible : Android autorise ici le démarrage du service micro.
        if (hasMic() && App.graph(this).settings.assistantEnabled) {
            startForegroundService(AssistantService.intent(this, AssistantService.ACTION_ENSURE_RUNNING))
        }
    }

    /** Éteindre coupe tout, même le secours : on demande confirmation. Rallumer est immédiat. */
    private fun onPowerToggled(on: Boolean) {
        val settings = App.graph(this).settings
        if (on) {
            settings.assistantEnabled = true
            refresh()
            if (hasMic()) startForegroundService(AssistantService.intent(this, AssistantService.ACTION_POWER_ON))
            return
        }
        findViewById<Switch>(R.id.power).isChecked = true // reste allumé tant que ce n'est pas confirmé
        AlertDialog.Builder(this)
            .setTitle(R.string.power_off_confirm_title)
            .setMessage(R.string.power_off_confirm_text)
            .setPositiveButton(R.string.power_off_confirm_yes) { _, _ ->
                settings.assistantEnabled = false
                refresh()
                startForegroundService(AssistantService.intent(this, AssistantService.ACTION_POWER_OFF))
            }
            .setNegativeButton(R.string.power_off_confirm_no, null)
            .show()
    }

    /** Met l'écran à jour : nom, état, interrupteur, grand bouton. */
    private fun refresh() {
        val s = App.graph(this).settings
        val on = s.assistantEnabled
        findViewById<TextView>(R.id.assistant_name).text = s.name
        findViewById<Switch>(R.id.power).apply {
            isChecked = on
            contentDescription = getString(if (on) R.string.power_on_a11y else R.string.power_off_a11y)
        }
        findViewById<TextView>(R.id.power_detail).setText(if (on) R.string.power_on_detail else R.string.power_off_detail)
        findViewById<TextView>(R.id.status).apply {
            text = when {
                !on -> getString(R.string.status_off)
                !hasMic() -> getString(R.string.status_no_mic)
                s.wakeWordEnabled -> getString(R.string.status_listening, s.name)
                else -> getString(R.string.status_ready)
            }
            compoundDrawableTintList = ColorStateList.valueOf(getColor(if (on && hasMic()) R.color.on else R.color.off))
        }
        findViewById<TextView>(R.id.hint).text = if (on) getString(R.string.hint_on, s.name) else getString(R.string.hint_off)
        findViewById<Button>(R.id.talk).apply {
            isEnabled = on
            contentDescription = getString(R.string.talk_description, s.name)
        }
        findViewById<Button>(R.id.tutorial).isEnabled = on
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun talk() {
        if (!hasMic()) {
            requestNeededPermissions()
            return
        }
        startForegroundService(AssistantService.intent(this, AssistantService.ACTION_LISTEN))
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            addAll(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        refresh()
        if (hasMic() && App.graph(this).settings.assistantEnabled) {
            startForegroundService(AssistantService.intent(this, AssistantService.ACTION_SAY)
                .putExtra(AssistantService.EXTRA_TEXT, welcome()))
        } else {
            findViewById<Button>(R.id.talk).announceForAccessibility(getString(R.string.mic_permission_needed))
        }
    }

    private fun welcome(): String {
        val s = App.graph(this).settings
        return "Bonjour, je suis ${s.name}. Pour me parler, dites « ${s.name} » et attendez le bip, " +
            "ou appuyez sur le grand bouton au milieu de l'écran. Pour apprendre à m'utiliser, dites « tutoriel », " +
            "ou appuyez sur le bouton Tutoriel, en bas de l'écran."
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
    }
}
