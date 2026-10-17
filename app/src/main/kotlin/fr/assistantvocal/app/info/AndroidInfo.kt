package fr.assistantvocal.app.info

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import fr.assistantvocal.core.info.HeadingSource
import fr.assistantvocal.core.info.Navigator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Boussole : direction du haut du téléphone (tenu à plat ou devant soi), d'après le capteur de rotation.
 * Une seule mesure, après une courte stabilisation.
 */
class AndroidHeading(private val context: Context) : HeadingSource {
    override suspend fun heading(): Float? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
        return withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine { cont ->
                var count = 0
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (++count < 5 || !cont.isActive) return // quelques mesures pour se stabiliser
                        val rot = FloatArray(9); val o = FloatArray(3)
                        SensorManager.getRotationMatrixFromVector(rot, e.values)
                        SensorManager.getOrientation(rot, o)
                        sm.unregisterListener(this)
                        cont.resume(((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }
}

/** Guidage piéton par Google Maps (navigation vocale à pied), sinon toute application de cartes. */
class GoogleMapsNavigator(private val context: Context) : Navigator {
    override fun walkTo(lat: Double, lon: Double, label: String): Boolean {
        val coords = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coords&mode=w"))
            .setPackage(MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val any = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords(${Uri.encode(label)})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (i in listOf(maps, any)) {
            if (runCatching { context.startActivity(i) }.onFailure { Log.w("Navigator", "Ouverture impossible", it) }.isSuccess) return true
        }
        return false
    }

    companion object { const val MAPS = "com.google.android.apps.maps" }
}
