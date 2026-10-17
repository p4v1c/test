package fr.assistantvocal.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button

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
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        findViewById<Button>(R.id.talk).contentDescription = getString(R.string.talk_description, App.graph(this).settings.name)
        // L'activité est visible : Android autorise ici le démarrage du service micro.
        if (hasMic()) startForegroundService(AssistantService.intent(this, AssistantService.ACTION_ENSURE_RUNNING))
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
                Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION))
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasMic()) {
            startForegroundService(AssistantService.intent(this, AssistantService.ACTION_SAY)
                .putExtra(AssistantService.EXTRA_TEXT, welcome()))
        } else {
            findViewById<Button>(R.id.talk).announceForAccessibility(getString(R.string.mic_permission_needed))
        }
    }

    private fun welcome(): String {
        val s = App.graph(this).settings
        return "Bonjour, je suis ${s.name}. Pour me parler, dites « ${s.name} » et attendez le bip, " +
            "ou appuyez sur le grand bouton au milieu de l'écran."
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
    }
}
