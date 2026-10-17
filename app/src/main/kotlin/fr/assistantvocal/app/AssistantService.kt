package fr.assistantvocal.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fr.assistantvocal.app.audio.Heard
import fr.assistantvocal.app.audio.SpeechInput
import fr.assistantvocal.app.audio.SpeechOutput
import fr.assistantvocal.app.audio.Sounds
import fr.assistantvocal.app.triggers.HeadsetButtons
import fr.assistantvocal.app.wake.WakeEngine
import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.Earcon
import fr.assistantvocal.core.assistant.FollowUp
import fr.assistantvocal.core.text.FrenchPhonetic
import fr.assistantvocal.core.wake.WakeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service au premier plan qui possède le micro, la voix et la conversation.
 * - écoute du nom en continu (hors ligne) quand il ne fait rien d'autre ;
 * - un seul échange à la fois ; un nouveau déclenchement interrompt la réponse en cours.
 */
class AssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private lateinit var input: SpeechInput
    private lateinit var output: SpeechOutput
    private lateinit var sounds: Sounds
    private lateinit var wake: WakeEngine
    private var headset: HeadsetButtons? = null
    private var session: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    /** Texte en cours de lecture (pour ignorer l'assistant qui prononce son propre nom). */
    private var currentSpeech: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = App.graph(this)
        input = graph.speechInputFactory(this)
        output = graph.speechOutputFactory(this)
        sounds = graph.soundsFactory(this)
        wake = graph.wakeEngineFactory(this)
        startInForeground()
        headset = runCatching { HeadsetButtons(this) { onTrigger() } }.getOrNull()
        headset?.enabled = graph.settings.headsetButtonEnabled
        scope.launch {
            if (wake.ensureModel()) startWake() else Log.w(TAG, "Mot d'activation indisponible : modèle absent")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> startConversation()
            ACTION_STOP_SPEAKING -> { interrupt(); restartWakeSoon() }
            ACTION_SHUTDOWN -> { interrupt(); wake.stop(); stopSelf() }
            ACTION_SAY -> intent.getStringExtra(EXTRA_TEXT)?.let { say(it) }
            ACTION_CALIBRATE -> runSession { calibrateName() }
            ACTION_SETTINGS_CHANGED -> { headset?.enabled = graph.settings.headsetButtonEnabled; if (session?.isActive != true) startWake() }
            ACTION_ENSURE_RUNNING, null -> Unit
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Android refuse le service micro s'il est lancé depuis l'arrière-plan sans exemption.
            Log.e(TAG, "Impossible de passer au premier plan", e)
        }
    }

    private fun buildNotification(): Notification {
        val listen = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP_SPEAKING), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = if (graph.settings.wakeWordEnabled) "Dites « ${graph.settings.name} » ou touchez « Parler »"
        else getString(R.string.notification_text)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(graph.settings.name)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_mic, getString(R.string.talk), listen)
            .addAction(R.drawable.ic_mic, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // ------------------------------------------------------------------ mot d'activation

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun wakeConfig(): WakeConfig {
        val s = graph.settings
        return WakeConfig(s.name, s.wakeAliases, s.wakeThreshold.toDouble(), s.wakeStrict)
    }

    /** (Re)démarre l'écoute du nom si elle est activée et que le micro est libre. */
    private fun startWake() {
        if (!graph.settings.wakeWordEnabled || !hasMic() || !wake.isReady) { wake.stop(); return }
        val ok = wake.start(wakeConfig()) { onWakeWord() }
        if (!ok) Log.w(TAG, "Écoute du nom impossible (nom inconnu du modèle ? calibration nécessaire)")
    }

    private fun restartWakeSoon() { scope.launch { delay(300); if (session?.isActive != true) startWake() } }

    private fun onWakeWord() {
        // L'assistant a prononcé lui-même son nom : on ignore.
        if (output.isSpeaking() && FrenchPhonetic.containsName(currentSpeech.split(' '), graph.settings.name)) return
        onTrigger()
    }

    /** Déclencheur (nom, casque, volume, notification) : interrompt si besoin puis écoute. */
    private fun onTrigger() = startConversation()

    // ------------------------------------------------------------------ conversation

    /** Interrompt tout : parole, écoute, réflexion. */
    fun interrupt() {
        session?.cancel()
        output.stop()
        input.cancel()
        speakingNow = false
        abandonFocus()
    }

    private fun runSession(block: suspend () -> Unit) {
        interrupt()
        session = scope.launch {
            try { block() } finally { abandonFocus(); speakingNow = false; if (isActive) startWake() else restartWakeSoon() }
        }
    }

    private fun say(text: String) = runSession { applyVoiceSettings(); speak(text) }

    private fun startConversation() = runSession { conversation() }

    private suspend fun conversation() {
        applyVoiceSettings()
        var turns = 0
        while (true) {
            wake.stop() // le micro est réservé à la reconnaissance de la demande
            requestFocus(exclusive = true)
            sounds.play(Earcon.LISTENING)
            val heard = input.listen(preferOffline = !graph.device.isOnline())
            val reply: AssistantReply = when (heard) {
                is Heard.Nothing -> AssistantReply("Je n'ai rien entendu.", Earcon.CANCELLED)
                is Heard.Failed -> AssistantReply(heard.speech, Earcon.ERROR)
                is Heard.Text -> {
                    Log.i(TAG, "Entendu : ${heard.text}")
                    think { graph.assistant.handle(heard.text) }
                }
            }
            if (reply.stopAll) { sounds.play(Earcon.CANCELLED); return }
            sounds.play(reply.earcon)
            applyVoiceSettings()
            requestFocus(exclusive = false)
            startWake() // pendant que l'assistant parle, « [nom] » l'interrompt
            val completed = speak(reply.speech)
            turns++
            when (reply.followUp) {
                FollowUp.CALIBRATE_NAME -> { calibrateName(); return }
                FollowUp.WAKE_WORD_CHANGED -> { refreshNotification(); return }
                FollowUp.NONE -> Unit
            }
            // Relance l'écoute pour une confirmation, au maximum 4 fois d'affilée.
            if (!completed || !reply.listenAgain || heard !is Heard.Text || turns >= 4) return
        }
    }

    /**
     * Calibration du nom : 3 enregistrements après un bip, analyse, puis 15 secondes d'écoute
     * pour compter les faux déclenchements.
     */
    private suspend fun calibrateName() {
        wake.stop()
        applyVoiceSettings()
        val name = graph.settings.name
        if (!wake.ensureModel()) { speak("Je ne peux pas apprendre mon nom : le modèle de reconnaissance hors ligne est absent."); return }
        speak("Après chaque bip, dites simplement « $name ».")
        val recordings = mutableListOf<ShortArray>()
        repeat(3) {
            sounds.play(Earcon.LISTENING)
            recordings += wake.record(2_500)
        }
        sounds.play(Earcon.THINKING)
        val result = wake.calibrate(name, recordings)
        Log.i(TAG, "Calibration : $result")
        if (result.recognized == 0 && result.aliases.isEmpty()) {
            sounds.play(Earcon.ERROR)
            speak("Je n'ai pas réussi à entendre mon nom. Parlez un peu plus près du téléphone, puis dites « apprends mon nom » pour recommencer.")
            return
        }
        graph.settings.wakeAliases = result.aliases
        graph.settings.wakeThreshold = result.suggestedThreshold.toFloat()
        graph.settings.wakeStrict = false
        speak("J'ai reconnu mon nom ${result.recognized} fois sur ${result.total}. Maintenant, parlez normalement pendant " +
            "quinze secondes sans dire mon nom, ou laissez la radio allumée. Je vous préviens à la fin.")
        sounds.play(Earcon.LISTENING)
        val falses = wake.countDetections(wakeConfig(), 15_000)
        sounds.play(Earcon.SUCCESS)
        if (falses > 0) {
            graph.settings.wakeStrict = true
            speak("Je me suis déclenché $falses fois par erreur. J'ai rendu l'écoute plus stricte. " +
                "Si cela continue, il vaudra mieux me donner un nom plus rare.")
        } else {
            speak("Parfait, je ne me suis pas déclenché par erreur. Vous pouvez maintenant m'appeler en disant « $name », puis attendre le bip.")
        }
        refreshNotification()
    }

    /** Lance le traitement ; si ça dure plus de 0,8 s, joue un tic toutes les 1,5 s. */
    private suspend fun think(block: suspend () -> AssistantReply): AssistantReply {
        val ticker = scope.launch {
            delay(800)
            while (isActive) { sounds.play(Earcon.THINKING); delay(1500) }
        }
        return try { block() } finally { ticker.cancel() }
    }

    private suspend fun speak(text: String): Boolean {
        currentSpeech = text
        speakingNow = true
        return try { output.speak(text) } finally { speakingNow = false; currentSpeech = "" }
    }

    private fun applyVoiceSettings() = output.applySettings(graph.settings.speechRate, graph.settings.speechPitch)

    private fun requestFocus(exclusive: Boolean) {
        val am = getSystemService(AudioManager::class.java) ?: return
        abandonFocus()
        val req = AudioFocusRequest.Builder(
            if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        ).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        ).build()
        am.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
        focusRequest = null
    }

    override fun onDestroy() {
        scope.cancel()
        wake.stop()
        headset?.release()
        input.release(); output.release(); sounds.release(); wake.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION_ID = 1
        const val ACTION_LISTEN = "fr.assistantvocal.LISTEN"
        const val ACTION_STOP_SPEAKING = "fr.assistantvocal.STOP_SPEAKING"
        const val ACTION_SHUTDOWN = "fr.assistantvocal.SHUTDOWN"
        const val ACTION_SAY = "fr.assistantvocal.SAY"
        const val ACTION_CALIBRATE = "fr.assistantvocal.CALIBRATE"
        const val ACTION_SETTINGS_CHANGED = "fr.assistantvocal.SETTINGS_CHANGED"
        const val ACTION_ENSURE_RUNNING = "fr.assistantvocal.ENSURE_RUNNING"
        const val EXTRA_TEXT = "text"

        /** Vrai pendant que l'assistant parle (lu par le service d'accessibilité pour les boutons volume). */
        @Volatile var speakingNow: Boolean = false
            internal set

        fun intent(context: Context, action: String) = Intent(context, AssistantService::class.java).setAction(action)
    }
}
