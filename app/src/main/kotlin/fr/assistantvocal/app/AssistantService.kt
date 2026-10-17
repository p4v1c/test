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
import kotlinx.coroutines.withTimeoutOrNull
import fr.assistantvocal.core.phone.CallState
import fr.assistantvocal.core.safety.DeviceAlerts
import fr.assistantvocal.core.safety.SosFlow
import fr.assistantvocal.core.safety.SosUi

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
        startDeviceWatch()
        DailyJobs.scheduleWellbeing(graph)
        // Lecteur : position mémorisée et minuterie de sommeil, toutes les 10 secondes.
        scope.launch { while (true) { delay(10_000); runCatching { graph.media.tick() } } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> startConversation()
            ACTION_STOP_SPEAKING -> { interrupt(); restartWakeSoon() }
            ACTION_SHUTDOWN -> { interrupt(); wake.stop(); stopSelf() }
            ACTION_SAY -> intent.getStringExtra(EXTRA_TEXT)?.let { say(it) }
            ACTION_CALIBRATE -> runSession { calibrateName() }
            ACTION_SETTINGS_CHANGED -> {
                headset?.enabled = graph.settings.headsetButtonEnabled
                DailyJobs.scheduleWellbeing(graph)
                if (session?.isActive != true) startWake()
            }
            ACTION_REMINDER_FIRE -> intent.getIntExtra(fr.assistantvocal.app.phone.ReminderReceiver.EXTRA_ID, -1).let { id ->
                when {
                    id == DailyJobs.WELLBEING_ID || id == DailyJobs.WELLBEING_FOLLOW_UP_ID -> runSession { wellbeing() }
                    id >= 0 -> runSession { fireReminder(id) }
                }
            }
            ACTION_SOS -> runSession { sos() }
            ACTION_CALL_STATE -> onCallState(intent.getStringExtra(EXTRA_STATE), intent.getStringExtra(EXTRA_NUMBER))
            ACTION_SMS_RECEIVED -> onSms(intent.getStringExtra(EXTRA_NUMBER).orEmpty(), intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()))
            ACTION_ANNOUNCE -> intent.getStringExtra(EXTRA_TEXT)?.let { announceIfIdle(it) }
            ACTION_REMOTE_LOCATE -> intent.getStringExtra(EXTRA_NUMBER)?.let { n -> runSession { remoteLocate(n) } }
            ACTION_ENSURE_RUNNING -> startInForeground()
            null -> Unit
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                (if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else 0
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

    /** Déclencheur (nom, casque, volume, notification) : interrompt si besoin puis écoute. Pendant une sonnerie : décroche. */
    private fun onTrigger() {
        when (graph.phone.actions.state()) {
            CallState.RINGING -> { ringing = false; interrupt(); graph.phone.actions.answer() }
            CallState.OFFHOOK -> Unit // en communication : le micro appartient à l'appel
            CallState.IDLE -> startConversation()
        }
    }

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

    /** La lecture en cours (livre, radio…) se met en pause pendant l'échange, et reprend après si rien ne l'a changée. */
    private fun startConversation() = runSession {
        stoppedByUser = false
        graph.media.beforeConversation()
        try { conversation() } finally { graph.media.afterConversation(stoppedByUser) }
    }

    private var stoppedByUser = false

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
            if (reply.stopAll) { stoppedByUser = true; sounds.play(Earcon.CANCELLED); return }
            sounds.play(reply.earcon)
            applyVoiceSettings()
            requestFocus(exclusive = false)
            startWake() // pendant que l'assistant parle, « [nom] » l'interrompt
            val completed = speak(reply.speech)
            turns++
            when (reply.followUp) {
                FollowUp.CALIBRATE_NAME -> { calibrateName(); return }
                FollowUp.WAKE_WORD_CHANGED -> { refreshNotification(); return }
                FollowUp.SOS -> { sos(); return }
                FollowUp.VISION -> { reply.vision?.let { runVision(it) }; return }
                FollowUp.OPEN_LOOKOUT -> { openLookout(); return }
                FollowUp.RECORD_MEMO -> { recordMemo(); return }
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

    // ------------------------------------------------------------------ vision

    /** Séance caméra. L'écoute du nom reste active : « [nom], stop » interrompt la séance. */
    private suspend fun runVision(req: fr.assistantvocal.core.vision.VisionRequest) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            speak("Je n'ai pas l'autorisation d'utiliser la caméra. Demandez à un proche de l'activer.")
            return
        }
        startInForeground() // ajoute le type « caméra » au service si l'autorisation vient d'être donnée
        startWake()
        val ui = object : fr.assistantvocal.core.vision.VisionUi {
            override suspend fun say(text: String) { speak(text) }
            override suspend fun tick(good: Boolean) = sounds.tone(if (good) 1200.0 else 600.0, 60)
            override suspend fun tone(hz: Double, ms: Long) = sounds.tone(hz, ms)
            override fun stopRequested() = false // l'arrêt passe par l'annulation de la séance
        }
        sounds.play(Earcon.THINKING)
        val result = graph.vision.run(req, ui)
        sounds.play(Earcon.SUCCESS)
        speak(result)
    }

    /** Mémo vocal : bip, enregistrement jusqu'au silence, puis durée annoncée. */
    private suspend fun recordMemo() {
        wake.stop()
        sounds.play(Earcon.LISTENING)
        val duration = graph.mediaBackend.recordMemo()
        sounds.play(if (duration != null && duration >= 1_000) Earcon.SUCCESS else Earcon.ERROR)
        speak(graph.media.memoRecorded(duration))
    }

    private suspend fun openLookout() {
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.accessibility.reveal")
        if (intent == null) { speak("L'application Lookout n'est pas installée. Un proche peut l'installer depuis le Play Store."); return }
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { speak("Je n'arrive pas à ouvrir Lookout.") }
    }

    // ------------------------------------------------------------------ rappels qui sonnent

    /** Annonce un rappel ; après chaque annonce, écoute une réponse (« c'est fait », « stop », « rappelle-moi dans 10 minutes »). */
    private suspend fun fireReminder(id: Int) {
        val ann = graph.reminders.onFire(id) ?: return
        applyVoiceSettings()
        repeat(ann.repeats) { i ->
            wake.stop()
            if (ann.ring) sounds.alarm(if (i == 0) 6_000 else 4_000) else sounds.play(Earcon.CONFIRM)
            speak(ann.speech)
            sounds.play(Earcon.LISTENING)
            when (val heard = input.listen(preferOffline = !graph.device.isOnline())) {
                is Heard.Text -> {
                    val reply = graph.assistant.handle(heard.text)
                    if (!reply.stopAll) { sounds.play(reply.earcon); speak(reply.speech) }
                    return
                }
                else -> if (!ann.ring && i < ann.repeats - 1) delay(20_000)
            }
        }
    }

    // ------------------------------------------------------------------ appels entrants

    private var ringing = false
    private var announcedNumber: String? = null

    private fun onCallState(state: String?, number: String?) {
        when (state) {
            android.telephony.TelephonyManager.EXTRA_STATE_RINGING -> {
                ringing = true
                // Android envoie l'événement deux fois (sans puis avec numéro) : une seule annonce, avec le numéro.
                if (number == null || number == announcedNumber) return
                announcedNumber = number
                runSession {
                    applyVoiceSettings()
                    startWake() // « [nom], décroche »
                    val announce = graph.comms.announceIncomingCall(number)
                    val how = "Pour décrocher, dites « ${graph.settings.name}, décroche », ou appuyez sur le bouton du casque."
                    for (i in 0 until 3) {
                        if (!ringing) return@runSession
                        speak(if (i == 0) "$announce $how" else announce)
                        delay(6_000)
                    }
                }
            }
            android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                ringing = false
                interrupt()
                wake.stop() // le micro appartient à l'appel
            }
            android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                ringing = false
                announcedNumber = null
                restartWakeSoon()
            }
        }
    }

    // ------------------------------------------------------------------ messages et notifications entrants

    private fun busy() = session?.isActive == true || graph.phone.actions.state() != CallState.IDLE

    private fun onSms(number: String, body: String, time: Long) {
        val mode = graph.settings.smsAnnounce
        if (mode == "off" || busy()) return
        val msg = fr.assistantvocal.core.phone.SmsMessage(0, number, null, body,
            java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()))
        runSession {
            applyVoiceSettings()
            sounds.play(Earcon.CONFIRM)
            speak(graph.comms.announceIncomingSms(msg, readFull = mode == "read"))
        }
    }

    private fun announceIfIdle(text: String) {
        if (busy()) return
        runSession { applyVoiceSettings(); sounds.play(Earcon.CONFIRM); speak(fr.assistantvocal.core.assistant.FrenchSpeech.clean(text)) }
    }

    // ------------------------------------------------------------------ sécurité

    private suspend fun sos() {
        wake.stop()
        applyVoiceSettings()
        val ui = object : SosUi {
            override suspend fun say(text: String) { speak(text) }
            override suspend fun alertSound() = sounds.alarm(1_500, loud = true)
            override suspend fun cancelledWithin(ms: Long): Boolean {
                val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end) {
                    val heard = withTimeoutOrNull(end - System.currentTimeMillis()) { input.listen(preferOffline = true) } ?: return false
                    if (heard is Heard.Text && isCancel(heard.text)) return true
                    delay(200) // la reconnaissance peut rendre la main tout de suite : pas de boucle à vide
                }
                return false
            }
        }
        val outcome = SosFlow(graph.people, graph.phone.location, graph.phone.smsSender, graph.phone.actions, ui,
            { graph.settings.ownerName }, countdownMs = graph.sosCountdownMs).run()
        Log.i(TAG, "SOS : $outcome")
    }

    private fun isCancel(text: String): Boolean {
        val k = fr.assistantvocal.core.text.FrenchText.key(text)
        return fr.assistantvocal.core.dialog.YesNoParser.parse(text) == fr.assistantvocal.core.dialog.YesNo.NO ||
            Regex("\\b(stop|annule|arrete|fausse alerte|ca va|tout va bien|non)\\b").containsMatchIn(k)
    }

    /** Un proche de confiance cherche le téléphone : sonnerie forte 30 s et réponse avec la position. */
    private suspend fun remoteLocate(number: String) {
        wake.stop()
        val ring = scope.launch { sounds.alarm(30_000, loud = true) }
        val point = graph.phone.location.current(20_000)
        val text = if (point == null) "Le téléphone de ${graph.settings.ownerName} sonne. Position indisponible."
        else "Le téléphone de ${graph.settings.ownerName} sonne. Position : https://maps.google.com/?q=${"%.5f".format(java.util.Locale.US, point.lat)},${"%.5f".format(java.util.Locale.US, point.lon)}"
        graph.phone.smsSender.send(number, text)
        ring.join()
    }

    /** « Tout va bien ? » quotidien : sans réponse après 3 essais espacés de 10 minutes, un proche est prévenu. */
    private suspend fun wellbeing() {
        DailyJobs.scheduleWellbeing(graph)
        val s = graph.settings
        val startOfDay = java.time.LocalDate.now().atTime(5, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val attempts = graph.store.getInt(DailyJobs.K_ATTEMPTS, 0)
        if (attempts == 0 && s.lastInteractionMs >= startOfDay) return
        wake.stop()
        applyVoiceSettings()
        sounds.play(Earcon.CONFIRM)
        speak("Bonjour ${s.ownerName.takeIf { it != "Votre proche" } ?: ""} ! Tout va bien ? Répondez oui ou non.".replace("  ", " "))
        sounds.play(Earcon.LISTENING)
        val heard = input.listen(preferOffline = true)
        if (heard is Heard.Text) {
            graph.store.putInt(DailyJobs.K_ATTEMPTS, 0)
            s.lastInteractionMs = System.currentTimeMillis()
            if (fr.assistantvocal.core.dialog.YesNoParser.parse(heard.text) == fr.assistantvocal.core.dialog.YesNo.NO) {
                speak("Je vais prévenir vos proches.")
                sos()
            } else speak("Très bien. Bonne journée !")
            return
        }
        if (attempts + 1 >= 3) {
            graph.store.putInt(DailyJobs.K_ATTEMPTS, 0)
            val text = "Bonjour, ${s.ownerName} n'a pas répondu ce matin à son assistant vocal qui lui demandait si tout allait bien. " +
                "Pouvez-vous l'appeler ? (message automatique)"
            graph.people.emergencyContacts.forEach { graph.phone.smsSender.send(it.number, text) }
        } else {
            graph.store.putInt(DailyJobs.K_ATTEMPTS, attempts + 1)
            graph.phone.scheduler.schedule(DailyJobs.WELLBEING_FOLLOW_UP_ID, System.currentTimeMillis() + 10 * 60_000)
        }
    }

    // ------------------------------------------------------------------ batterie et réseau

    private val alerts = DeviceAlerts { System.currentTimeMillis() }
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun startDeviceWatch() {
        batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                if (level < 0 || scale <= 0) return
                val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
                alerts.onBattery(level * 100 / scale, charging)?.let { announceIfIdle(it) }
            }
        }
        registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { scope.launch { alerts.onNetwork(true)?.let { announceIfIdle(it) } } }
            override fun onLost(network: android.net.Network) { scope.launch { alerts.onNetwork(false) } }
        }
        runCatching { cm?.registerDefaultNetworkCallback(networkCallback!!) }
        scope.launch { while (isActive) { delay(10_000); alerts.tick()?.let { announceIfIdle(it) } } }
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
        runCatching { batteryReceiver?.let { unregisterReceiver(it) } }
        runCatching { networkCallback?.let { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        scope.cancel()
        wake.stop()
        headset?.release()
        input.release(); output.release(); sounds.release(); wake.release()
        runCatching { graph.media.tick(); (graph.mediaBackend.player as? fr.assistantvocal.app.media.AndroidPlayer)?.release() }
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
        const val ACTION_REMINDER_FIRE = "fr.assistantvocal.REMINDER_FIRE"
        const val ACTION_SOS = "fr.assistantvocal.SOS"
        const val ACTION_CALL_STATE = "fr.assistantvocal.CALL_STATE"
        const val ACTION_SMS_RECEIVED = "fr.assistantvocal.SMS_RECEIVED"
        const val ACTION_ANNOUNCE = "fr.assistantvocal.ANNOUNCE"
        const val ACTION_REMOTE_LOCATE = "fr.assistantvocal.REMOTE_LOCATE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_STATE = "state"
        const val EXTRA_TIME = "time"

        /** Vrai pendant que l'assistant parle (lu par le service d'accessibilité pour les boutons volume). */
        @Volatile var speakingNow: Boolean = false
            internal set

        fun intent(context: Context, action: String) = Intent(context, AssistantService::class.java).setAction(action)
    }
}
