package fr.assistantvocal.app.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.assistantvocal.core.vision.BarcodeScanner
import fr.assistantvocal.core.vision.Box
import fr.assistantvocal.core.vision.Camera
import fr.assistantvocal.core.vision.Frame
import fr.assistantvocal.core.vision.MedicationDatabase
import fr.assistantvocal.core.vision.MedicationInfo
import fr.assistantvocal.core.vision.OcrEngine
import fr.assistantvocal.core.vision.OcrResult
import fr.assistantvocal.core.vision.TextLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private const val TAG = "Vision"

/** Image → Frame : JPEG réduit pour l'IA, luminance moyenne et couleur de la zone centrale. */
internal fun Bitmap.toFrame(maxSide: Int, quality: Int = 75): Frame {
    val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
    val b = if (scale < 1f) Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true) else this
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, quality, out)
    // Mesures sur une grille de points (rapide).
    var lum = 0L; var n = 0
    var r = 0L; var g = 0L; var bl = 0L; var c = 0
    val stepX = maxOf(1, b.width / 40); val stepY = maxOf(1, b.height / 40)
    var y = 0
    while (y < b.height) {
        var x = 0
        while (x < b.width) {
            val p = b.getPixel(x, y)
            val pr = (p shr 16) and 0xff; val pg = (p shr 8) and 0xff; val pb = p and 0xff
            lum += (299 * pr + 587 * pg + 114 * pb) / 1000; n++
            if (x in b.width / 3..2 * b.width / 3 && y in b.height / 3..2 * b.height / 3) { r += pr; g += pg; bl += pb; c++ }
            x += stepX
        }
        y += stepY
    }
    c = maxOf(c, 1)
    return Frame(out.toByteArray(), b.width, b.height, (lum / maxOf(n, 1)).toInt(), Triple((r / c).toInt(), (g / c).toInt(), (bl / c).toInt()))
}

/**
 * Caméra arrière sans aperçu à l'écran (CameraX), utilisable depuis le service :
 * flux d'analyse pour le guidage, photo pleine qualité pour la lecture, lampe.
 */
class AndroidCamera(private val context: Context) : Camera, LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val executor = Executors.newSingleThreadExecutor()
    private val latest = AtomicReference<Bitmap?>()
    private var camera: androidx.camera.core.Camera? = null
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var started = false

    private suspend fun ensureStarted(): Boolean {
        if (started) return camera != null
        started = true
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        val p = suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
            val f = ProcessCameraProvider.getInstance(context)
            f.addListener({ cont.resume(runCatching { f.get() }.getOrNull()) }, ContextCompat.getMainExecutor(context))
        } ?: return false
        provider = p
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { a ->
                a.setAnalyzer(executor) { img: ImageProxy ->
                    runCatching { latest.set(img.toBitmap().rotate(img.imageInfo.rotationDegrees)) }
                    img.close()
                }
            }
        val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        capture = cap
        registry.currentState = Lifecycle.State.STARTED
        camera = runCatching { p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, cap) }
            .onFailure { Log.e(TAG, "Caméra indisponible", it) }.getOrNull()
        return camera != null
    }

    override suspend fun preview(): Frame? {
        if (!ensureStarted()) return null
        repeat(30) {
            latest.getAndSet(null)?.let { return it.toFrame(1024, 70) }
            kotlinx.coroutines.delay(100)
        }
        return null
    }

    override suspend fun capture(): Frame? {
        if (!ensureStarted()) return null
        val cap = capture ?: return null
        val result = CompletableDeferred<Bitmap?>()
        cap.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching { image.toBitmap().rotate(image.imageInfo.rotationDegrees) }.getOrNull()
                image.close(); result.complete(bmp)
            }
            override fun onError(exception: ImageCaptureException) { Log.e(TAG, "Photo ratée", exception); result.complete(null) }
        })
        return withTimeoutOrNull(8_000) { result.await() }?.toFrame(1600, 80)
    }

    override fun torch(on: Boolean) { runCatching { camera?.cameraControl?.enableTorch(on) } }

    override fun close() {
        runCatching { camera?.cameraControl?.enableTorch(false) }
        registry.currentState = Lifecycle.State.DESTROYED
        runCatching { provider?.unbindAll() }
        executor.shutdown()
    }

    private fun Bitmap.rotate(deg: Int): Bitmap =
        if (deg == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(deg.toFloat()) }, true)
}

private fun Frame.bitmap(): Bitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

/** Lecture de texte sur le téléphone (ML Kit, alphabet latin, modèle inclus). */
class MlKitOcr : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(frame: Frame): OcrResult {
        val bmp = frame.bitmap() ?: return OcrResult(emptyList(), frame.width, frame.height)
        val text = runCatching { recognizer.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrNull()
            ?: return OcrResult(emptyList(), bmp.width, bmp.height)
        val lines = text.textBlocks.flatMap { block ->
            block.lines.map { l ->
                val r = l.boundingBox
                TextLine(l.text, if (r != null) Box(r.left, r.top, r.right, r.bottom) else Box(0, 0, 0, 0))
            }
        }.sortedWith(compareBy({ it.box.top / 20 }, { it.box.left }))
        return OcrResult(lines, bmp.width, bmp.height)
    }
}

/** Codes-barres, QR codes et DataMatrix (boîtes de médicaments), sur le téléphone. */
class MlKitBarcodes : BarcodeScanner {
    private val scanner by lazy { BarcodeScanning.getClient() }

    override suspend fun scan(frame: Frame): List<String> {
        val bmp = frame.bitmap() ?: return emptyList()
        return runCatching { scanner.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrNull()
            ?.mapNotNull { it.rawValue } ?: emptyList()
    }
}

/** Table CIP13 → médicament, construite à la compilation à partir de la base publique des médicaments. */
class AssetMedicationDatabase(private val context: Context) : MedicationDatabase {
    private val table: Map<String, MedicationInfo> by lazy {
        runCatching {
            context.assets.open("medicaments.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { l -> val c = l.split('\t'); if (c.size >= 3) c[0] to MedicationInfo(c[1], c[2]) else null }.toMap()
            }
        }.getOrElse { Log.w(TAG, "Table des médicaments absente", it); emptyMap() }
    }

    override fun byCip13(cip: String): MedicationInfo? = table[cip]
}
