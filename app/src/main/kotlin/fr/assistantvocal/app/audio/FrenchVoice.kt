package fr.assistantvocal.app.audio

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Choix de la synthèse vocale : la voix doit sonner humaine.
 * Moteur Google (voix naturelles hors ligne) si le moteur par défaut est un moteur robotique connu ;
 * sinon le moteur choisi dans Android (un proche a pu installer une voix payante).
 */
object FrenchVoice {
    const val GOOGLE = "com.google.android.tts"
    private val ROBOTIC = setOf("com.reecedunn.espeak", "com.svox.pico", "com.googlecode.eyesfree.espeak")

    /** Moteur à imposer, ou null pour garder celui d'Android. */
    fun engine(default: String?, installed: List<String>): String? =
        if (GOOGLE in installed && GOOGLE != default && (default == null || default in ROBOTIC)) GOOGLE else null

    /** Meilleure voix française installée et utilisable sans internet (France d'abord). */
    fun best(voices: Collection<Voice>): Voice? = voices
        .filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired }
        .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
        .maxWithOrNull(compareBy<Voice>({ it.locale.country == "FR" }, { it.quality }, { -it.latency }))

    /** Crée la synthèse avec le bon moteur. */
    fun create(context: Context, onInit: TextToSpeech.OnInitListener): TextToSpeech {
        val ctx = context.applicationContext
        val default = Settings.Secure.getString(ctx.contentResolver, "tts_default_synth")
        val installed = ctx.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }
        val engine = engine(default, installed)
        return if (engine != null) TextToSpeech(ctx, onInit, engine) else TextToSpeech(ctx, onInit)
    }

    /** Français, avec la meilleure voix disponible. Renvoie le résultat de setLanguage. */
    fun apply(tts: TextToSpeech): Int {
        val r = tts.setLanguage(Locale.FRANCE)
        runCatching { best(tts.voices.orEmpty()) }.getOrNull()?.let { tts.voice = it }
        return r
    }
}
