package fr.assistantvocal.app.triggers

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import fr.assistantvocal.app.App
import fr.assistantvocal.app.AssistantService
import fr.assistantvocal.app.R

private const val TAG = "Triggers"

/** Démarre l'écoute ; ignore l'erreur si Android refuse (l'appelant n'est pas autorisé à démarrer le service). */
internal fun requestListen(context: Context, foreground: Boolean) {
    if (!App.graph(context).settings.assistantEnabled) return
    val intent = AssistantService.intent(context, AssistantService.ACTION_LISTEN)
    try {
        if (foreground) context.startForegroundService(intent) else context.startService(intent)
    } catch (e: Exception) {
        Log.w(TAG, "Démarrage refusé, nouvel essai en premier plan", e)
        runCatching { context.startForegroundService(intent) }
    }
}

/**
 * Activité invisible : reçoit le bouton « assistant vocal » des casques Bluetooth (VOICE_COMMAND)
 * et l'action ASSIST, puis lance l'écoute.
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestListen(this, foreground = true)
        finish()
    }
}

/**
 * Service « assistant numérique par défaut ». Quand l'app est choisie comme assistant, Android la lance
 * au démarrage et l'autorise à utiliser le micro en arrière-plan : on démarre alors le service d'écoute.
 */
class AssistantVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        if (!App.graph(this).settings.assistantEnabled) return
        try {
            startForegroundService(AssistantService.intent(this, AssistantService.ACTION_ENSURE_RUNNING))
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de démarrer le service d'écoute", e)
        }
    }
}

/** Session de l'assistant : appui long sur le bouton d'accueil, geste assistant, écran verrouillé. */
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            requestListen(context, foreground = true)
            hide()
        }
    }
}

/** Au démarrage du téléphone : relance l'écoute, ou prévient s'il faut toucher l'écran une fois. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!App.graph(context).settings.assistantEnabled) return // éteint : il reste éteint
        // Android oublie les alarmes au redémarrage : on reprogramme rappels, réveils et « tout va bien ».
        runCatching { val g = App.graph(context); g.reminders.rescheduleAll(); fr.assistantvocal.app.DailyJobs.scheduleWellbeing(g) }
            .onFailure { Log.e(TAG, "Reprogrammation impossible", it) }
        try {
            context.startForegroundService(AssistantService.intent(context, AssistantService.ACTION_ENSURE_RUNNING))
        } catch (e: Exception) {
            // Android 14+ interdit le micro au démarrage sauf pour l'assistant par défaut : notification de secours.
            Log.w(TAG, "Démarrage au boot refusé", e)
            notifyTapToStart(context)
        }
    }

    private fun notifyTapToStart(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("boot", "Démarrage", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, fr.assistantvocal.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(2, NotificationCompat.Builder(context, "boot")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Touchez pour réactiver l'assistant")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}

/**
 * Service d'accessibilité minimal : uniquement pour les boutons volume (appui long = parler,
 * appui pendant que l'assistant parle = l'interrompre). Ne lit pas le contenu de l'écran.
 */
class KeyAccessibilityService : AccessibilityService() {
    private val gesture = VolumeKeyGesture()
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKeyGesture.Key.DOWN
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeKeyGesture.Key.UP
            else -> return false
        }
        val settings = App.graph(this).settings
        // Éteint : les boutons volume font leur travail normal.
        if (!settings.assistantEnabled || !settings.volumeKeyEnabled) return false
        val decision = gesture.onKey(key, event.action == KeyEvent.ACTION_DOWN, event.repeatCount > 0, AssistantService.speakingNow)
        return when (decision) {
            VolumeKeyGesture.Decision.PASS -> false
            VolumeKeyGesture.Decision.CONSUME -> true
            VolumeKeyGesture.Decision.CONSUME_AND_START_TIMER -> {
                handler.postDelayed({ if (gesture.onTimer() == VolumeKeyGesture.Decision.LISTEN) onLongPress() }, gesture.longPressDelayMs)
                true
            }
            VolumeKeyGesture.Decision.CONSUME_AND_LOWER_VOLUME -> {
                getSystemService(AudioManager::class.java)
                    ?.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                true
            }
            VolumeKeyGesture.Decision.STOP_SPEAKING -> {
                runCatching { startService(AssistantService.intent(this, AssistantService.ACTION_STOP_SPEAKING)) }
                true
            }
            VolumeKeyGesture.Decision.CONSUME_AND_START_SOS_TIMER -> {
                handler.postDelayed({ if (gesture.onSosTimer() == VolumeKeyGesture.Decision.SOS) onSosPress() }, gesture.sosDelayMs)
                true
            }
            VolumeKeyGesture.Decision.CONSUME_AND_RAISE_VOLUME -> {
                getSystemService(AudioManager::class.java)
                    ?.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                true
            }
            VolumeKeyGesture.Decision.LISTEN, VolumeKeyGesture.Decision.SOS -> true
        }
    }

    private fun onLongPress() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        val phone = App.graph(this).phone.actions
        // Pendant un appel : l'appui long raccroche.
        if (phone.state() == fr.assistantvocal.core.phone.CallState.OFFHOOK) { phone.hangUp(); return }
        requestListen(this, foreground = false)
    }

    private fun onSosPress() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 200), -1))
        runCatching { startForegroundService(AssistantService.intent(this, AssistantService.ACTION_SOS)) }
            .onFailure { runCatching { startService(AssistantService.intent(this, AssistantService.ACTION_SOS)) } }
    }
}

/**
 * Bouton du casque filaire / Bluetooth ou de l'enceinte (lecture/pause) : un appui lance l'écoute,
 * ou interrompt l'assistant s'il parle. Android envoie ce bouton à la dernière application ayant joué du son.
 */
class HeadsetButtons(context: Context, private val onPress: () -> Unit) {
    private val session = MediaSession(context, "assistant-vocal").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (ev.keyCode !in HANDLED) return false
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) onPress()
                return true
            }
        })
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                .build()
        )
    }

    var enabled: Boolean
        get() = session.isActive
        set(value) { session.isActive = value }

    fun release() = session.release()

    companion object {
        val HANDLED = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
