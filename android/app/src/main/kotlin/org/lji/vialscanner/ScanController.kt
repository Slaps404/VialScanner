package org.lji.vialscanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.lji.vialcore.ScanEngine
import org.lji.vialcore.ScanState
import java.util.concurrent.Executors

/**
 * Camera plumbing and the live scan, kept apart from the screens.
 *
 * The engine is rebuilt whenever the catalog or the engine knobs change, because
 * a `ScanEngine` captures both at construction: a scanner still matching against
 * the catalog as it was before an import is the kind of wrong that looks like a
 * bad read.
 */
class ScanController(private val store: AppStore) {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scanner = BarcodeScanning.getClient()

    var state by mutableStateOf<ScanState>(idle())
        private set

    /** Paused between scans so a finished result is not overwritten by the next
     *  frame before the user has acted on it. */
    var running by mutableStateOf(true)
        private set

    var torchOn by mutableStateOf(false)
        private set

    private var camera: Camera? = null
    private var engine: ScanEngine? = null
    private var engineStamp: String = ""

    private fun idle() = ScanState.Scanning(0, 0, 0, 0.0, "Line the label up")

    private fun currentEngine(): ScanEngine {
        // Cheap identity for "the inputs the engine was built from": catalog size
        // plus the knobs. Rebuilding on every frame would throw away the evidence
        // buffer mid-rotation.
        val stamp = "${store.catalog.definitions.size}/${store.catalog.items.size}/${store.settings}"
        val e = engine
        if (e != null && stamp == engineStamp) return e
        val fresh = ScanEngine(store.catalog, store.settings.engineConfig())
        engine = fresh
        engineStamp = stamp
        return fresh
    }

    fun rescan() {
        currentEngine().reset()
        state = idle()
        running = true
    }

    fun pause() { running = false }

    fun toggleTorch() {
        val c = camera ?: return
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
    }

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        val ctx = view.context
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                // Cap the analysis stream at 720p. Label text is legible well
                // below sensor resolution, and every extra pixel is paid four
                // times over per frame: rotate, downscale for sharpness, crop
                // for OCR, crop for barcodes.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                // Drop frames rather than queue them: a stale frame answers a
                // question about a vial the user has already turned past.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            if (store.settings.torchOnByDefault) {
                torchOn = true
                camera?.cameraControl?.enableTorch(true)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (!running || state is ScanState.Finished) return
            val frame = MlKitFrame(
                bitmap = uprightBitmap(proxy),
                timestamp = proxy.imageInfo.timestamp / 1_000_000_000.0,
                recognizer = recognizer,
                scanner = scanner,
            )
            state = currentEngine().ingest(frame)
        } finally {
            proxy.close()
        }
    }

    /** Force a verdict from whatever has been read (the user stopped rotating). */
    fun finishNow() {
        state = ScanState.Finished(currentEngine().finish())
    }

    /** Rotate into display orientation: the region of interest is expressed
     *  relative to what the user is aiming with, not to the sensor. */
    private fun uprightBitmap(proxy: ImageProxy): Bitmap {
        val bmp = proxy.toBitmap()
        val deg = proxy.imageInfo.rotationDegrees
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        // createBitmap copies, so the source is dead weight at ~3.5 MB a frame.
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    fun shutdown() {
        analysisExecutor.shutdown()
        recognizer.close()
        scanner.close()
    }
}
