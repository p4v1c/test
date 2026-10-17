package fr.assistantvocal.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import androidx.test.core.app.ApplicationProvider
import fr.assistantvocal.app.audio.AndroidSpeechInput
import fr.assistantvocal.app.audio.Heard
import fr.assistantvocal.app.audio.RecognizerChooser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30]) // Android 11, sans reconnaissance sur l'appareil : comme l'émulateur Genymotion sans services Google
class SpeechInputTest {
    @Test fun `sans reconnaissance Google, il dit qu'elle manque au lieu d'accuser le micro`() {
        val app: App = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // Téléphone sans application Google : le seul service de reconnaissance est le relais de l'app elle-même.
        val own = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = app.packageName; name = "fr.assistantvocal.app.audio.ProxyRecognitionService" }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), own)
        assertNull(RecognizerChooser.find(app))

        val heard = runBlocking { AndroidSpeechInput(app).listen(preferOffline = false) }

        assertTrue(heard is Heard.Failed)
        assertEquals(
            "La reconnaissance vocale n'est pas disponible sur ce téléphone. Il faut installer l'application Google.",
            (heard as Heard.Failed).speech,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraLifecycleTest {
    @Test fun `fermer la camera avant qu'elle soit ouverte ne plante pas (nom dit pendant le detecteur de lumiere)`() {
        val app: App = ApplicationProvider.getApplicationContext()
        val cam = fr.assistantvocal.app.vision.AndroidCamera(app)
        cam.close() // séance annulée pendant l'ouverture : l'état est encore INITIALIZED
        cam.close() // deux fois de suite non plus
        assertTrue(runBlocking { cam.preview() } == null) // fermée : plus d'ouverture tardive
    }
}
