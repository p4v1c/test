package fr.assistantvocal.app.triggers

/**
 * Logique des boutons volume (sans dépendance Android, testable) :
 * - pendant que l'assistant parle : n'importe quel bouton volume l'interrompt ;
 * - appui long (≥ [longPressMs]) sur « volume bas » : l'assistant écoute ;
 * - appui court sur « volume bas » : le volume baisse normalement ;
 * - appui de 3 secondes sur « volume haut » : alerte SOS (annulable 10 s) ; appui court : le volume monte.
 */
class VolumeKeyGesture(private val longPressMs: Long = 800, private val sosPressMs: Long = 3_000) {

    enum class Key { UP, DOWN }

    enum class Decision {
        /** Laisser passer l'événement au système. */
        PASS,
        /** Consommer l'événement sans rien faire de plus. */
        CONSUME,
        /** Consommer et programmer la vérification d'appui long dans [longPressMs]. */
        CONSUME_AND_START_TIMER,
        /** Consommer, puis baisser le volume (c'était un appui court). */
        CONSUME_AND_LOWER_VOLUME,
        /** Consommer et interrompre la parole de l'assistant. */
        STOP_SPEAKING,
        /** Lancer l'écoute (appui long détecté). */
        LISTEN,
        /** Consommer et programmer la vérification d'appui SOS dans [sosPressMs]. */
        CONSUME_AND_START_SOS_TIMER,
        /** Consommer, puis monter le volume (appui court sur volume haut). */
        CONSUME_AND_RAISE_VOLUME,
        /** Déclencher l'alerte SOS. */
        SOS,
    }

    private var upHeld = false
    private var sosTriggered = false
    private var downHeld = false
    private var triggered = false
    private var swallowUntilUp: Key? = null

    fun onKey(key: Key, pressed: Boolean, repeat: Boolean, assistantSpeaking: Boolean): Decision {
        // Fin d'un appui déjà traité (interruption) : on avale le relâchement.
        if (!pressed && swallowUntilUp == key) { swallowUntilUp = null; return Decision.CONSUME }
        if (pressed && !repeat && assistantSpeaking && !(key == Key.DOWN && downHeld)) {
            swallowUntilUp = key
            cancel()
            return Decision.STOP_SPEAKING
        }
        if (key == Key.UP) {
            if (swallowUntilUp == Key.UP) return Decision.CONSUME
            return when {
                pressed && repeat -> if (upHeld) Decision.CONSUME else Decision.PASS
                pressed -> { upHeld = true; sosTriggered = false; Decision.CONSUME_AND_START_SOS_TIMER }
                else -> {
                    val wasHeld = upHeld; val wasSos = sosTriggered
                    upHeld = false; sosTriggered = false
                    when { !wasHeld -> Decision.PASS; wasSos -> Decision.CONSUME; else -> Decision.CONSUME_AND_RAISE_VOLUME }
                }
            }
        }
        // Volume bas
        return when {
            pressed && repeat -> if (downHeld) Decision.CONSUME else Decision.PASS
            pressed -> { downHeld = true; triggered = false; Decision.CONSUME_AND_START_TIMER }
            else -> {
                val wasHeld = downHeld
                val wasTriggered = triggered
                cancel()
                when {
                    !wasHeld -> Decision.PASS
                    wasTriggered -> Decision.CONSUME
                    else -> Decision.CONSUME_AND_LOWER_VOLUME
                }
            }
        }
    }

    /** Appelé [longPressMs] après l'appui : renvoie LISTEN si le bouton est toujours enfoncé. */
    fun onTimer(): Decision? {
        if (!downHeld || triggered) return null
        triggered = true
        return Decision.LISTEN
    }

    /** Appelé [sosPressMs] après l'appui sur volume haut : SOS si toujours enfoncé. */
    fun onSosTimer(): Decision? {
        if (!upHeld || sosTriggered) return null
        sosTriggered = true
        return Decision.SOS
    }

    val longPressDelayMs get() = longPressMs
    val sosDelayMs get() = sosPressMs

    private fun cancel() { downHeld = false; triggered = false; upHeld = false; sosTriggered = false }
}
