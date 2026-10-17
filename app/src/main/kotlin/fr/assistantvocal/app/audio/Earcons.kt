package fr.assistantvocal.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import fr.assistantvocal.core.assistant.Earcon
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Sons courts et vibrations, un par état. Générés par le code (pas de fichiers audio) :
 * - écoute : deux notes montantes, vibration courte
 * - réflexion : un tic doux
 * - succès : deux notes montantes aiguës
 * - erreur : deux notes descendantes graves, double vibration
 * - confirmation demandée : trois notes « question »
 * - annulé : une note grave
 */
class Earcons(private val context: Context) : Sounds {

    private data class Note(val freq: Double, val ms: Int)

    private val melodies = mapOf(
        Earcon.LISTENING to listOf(Note(660.0, 90), Note(990.0, 120)),
        Earcon.THINKING to listOf(Note(520.0, 40)),
        Earcon.SUCCESS to listOf(Note(880.0, 90), Note(1320.0, 140)),
        Earcon.ERROR to listOf(Note(440.0, 160), Note(311.0, 260)),
        Earcon.CONFIRM to listOf(Note(660.0, 90), Note(880.0, 90), Note(1100.0, 160)),
        Earcon.CANCELLED to listOf(Note(392.0, 200)),
    )

    private val vibrations = mapOf(
        Earcon.LISTENING to longArrayOf(0, 40),
        Earcon.SUCCESS to longArrayOf(0, 30, 60, 30),
        Earcon.ERROR to longArrayOf(0, 120, 80, 120),
        Earcon.CONFIRM to longArrayOf(0, 60, 60, 60, 60, 60),
        Earcon.CANCELLED to longArrayOf(0, 200),
    )

    private val cache = HashMap<Earcon, ShortArray>()

    override suspend fun play(earcon: Earcon) {
        if (earcon == Earcon.NONE) return
        vibrate(earcon)
        val pcm = cache.getOrPut(earcon) { synth(melodies.getValue(earcon)) }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / RATE + 30)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun synth(notes: List<Note>): ShortArray {
        val total = notes.sumOf { it.ms * RATE / 1000 + GAP }
        val out = ShortArray(total)
        var pos = 0
        for (n in notes) {
            val len = n.ms * RATE / 1000
            val fade = min(len / 4, RATE / 200)
            for (i in 0 until len) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > len - fade -> (len - i).toDouble() / fade
                    else -> 1.0
                }
                out[pos + i] = (sin(2 * PI * n.freq * i / RATE) * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += len + GAP
        }
        return out
    }

    private fun vibrate(earcon: Earcon) {
        val pattern = vibrations[earcon] ?: return
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private companion object {
        const val RATE = 22050
        const val GAP = RATE / 50 // 20 ms de silence entre les notes
    }
}
