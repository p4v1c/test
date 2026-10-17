package fr.assistantvocal.app.audio

import fr.assistantvocal.core.assistant.Earcon

/** Ce que la reconnaissance vocale a entendu. */
sealed interface Heard {
    data class Text(val text: String, val alternatives: List<String> = emptyList()) : Heard
    /** Silence ou rien de compréhensible. */
    data object Nothing : Heard
    data class Failed(val speech: String) : Heard
}

interface SpeechInput {
    suspend fun listen(preferOffline: Boolean): Heard
    fun cancel()
    fun release() {}
}

interface SpeechOutput {
    /** Parle et attend la fin. Renvoie false si interrompu. */
    suspend fun speak(text: String): Boolean
    fun stop()
    fun isSpeaking(): Boolean
    fun applySettings(rate: Float, pitch: Float)
    fun release() {}
}

interface Sounds {
    /** Joue l'earcon (et la vibration associée) et attend la fin du son. */
    suspend fun play(earcon: Earcon)
    fun release() {}
}
