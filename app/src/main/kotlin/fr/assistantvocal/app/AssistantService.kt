package fr.assistantvocal.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import fr.assistantvocal.core.assistant.AssistantReply
import fr.assistantvocal.core.assistant.Earcon
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
 * Un seul échange à la fois ; un nouvel appui sur « Parler » interrompt la réponse en cours.
 */
class AssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private lateinit var input: SpeechInput
    private lateinit var output: SpeechOutput
    private lateinit var sounds: Sounds
    private var session: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = App.graph(this)
        input = graph.speechInputFactory(this)
        output = graph.speechOutputFactory(this)
        sounds = graph.soundsFactory(this)
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> startConversation()
            ACTION_STOP_SPEAKING -> interrupt()
            ACTION_SHUTDOWN -> { interrupt(); stopSelf() }
            ACTION_SAY -> intent.getStringExtra(EXTRA_TEXT)?.let { say(it) }
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(graph.settings.name)
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_mic, getString(R.string.talk), listen)
            .addAction(R.drawable.ic_mic, getString(R.string.notification_stop), stop)
            .build()
    }

    /** Interrompt tout : parole, écoute, réflexion. */
    fun interrupt() {
        session?.cancel()
        output.stop()
        input.cancel()
        abandonFocus()
    }

    private fun say(text: String) {
        interrupt()
        session = scope.launch { applyVoiceSettings(); speak(text) }
    }

    private fun startConversation() {
        // Appui pendant que l'assistant parle : on coupe et on écoute (comme « stop » puis nouvelle question).
        interrupt()
        session = scope.launch {
            try {
                conversation()
            } finally {
                abandonFocus()
            }
        }
    }

    private suspend fun conversation() {
        applyVoiceSettings()
        var turns = 0
        while (true) {
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
            val completed = speak(reply.speech)
            turns++
            // Relance l'écoute pour une confirmation, au maximum 4 fois d'affilée.
            if (!completed || !reply.listenAgain || heard !is Heard.Text || turns >= 4) return
        }
    }

    /** Lance le traitement ; si ça dure plus de 0,8 s, joue un tic toutes les 1,5 s. */
    private suspend fun think(block: suspend () -> AssistantReply): AssistantReply {
        val ticker = scope.launch {
            delay(800)
            while (isActive) { sounds.play(Earcon.THINKING); delay(1500) }
        }
        return try { block() } finally { ticker.cancel() }
    }

    private suspend fun speak(text: String): Boolean = output.speak(text)

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
        input.release(); output.release(); sounds.release()
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
        const val EXTRA_TEXT = "text"

        fun intent(context: Context, action: String) = Intent(context, AssistantService::class.java).setAction(action)
    }
}
