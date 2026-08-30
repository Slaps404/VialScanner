package org.lji.vialscanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Rational
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
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

            // Preview and analysis must see the same field, or the reticle is a
            // lie. They do not by default: the analysis stream comes back at
            // whatever aspect the sensor offers (4:3 on the emulator) while
            // PreviewView.FILL_CENTER crops that to the shape of the screen.
            // Measured on a 1080x2400 display with a 1392x1856 upright buffer,
            // the preview showed the middle 60% of the frame's width - so a
            // reticle drawn at 0.15..0.85 of the screen sat over 0.29..0.71 of
            // what the engine actually read, and the engine was free to read a
            // neighbouring vial the user had deliberately framed out.
            //
            // A ViewPort is CameraX's answer: it puts the same crop rect on
            // every use case in the group. `uprightBitmap` then has to honour
            // `proxy.cropRect`, which is where that rect arrives.
            // The provider callback usually lands after layout, but nothing
            // promises it: an unmeasured view would set a 1:1 viewport and crop
            // the analysis stream to a square. Fall back to the window.
            val vw = if (view.width > 0) view.width else ctx.resources.displayMetrics.widthPixels
            val vh = if (view.height > 0) view.height else ctx.resources.displayMetrics.heightPixels
            val viewPort = ViewPort.Builder(Rational(vw, vh), preview.targetRotation)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(analysis)
                .build()

            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
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
        var bmp = proxy.toBitmap()
        // The ViewPort's crop rect: the part of the buffer the preview is also
        // showing. Cropping to it is what makes the on-screen reticle mean the
        // same thing as `EngineConfig.regionOfInterest`.
        val r = proxy.cropRect
        if (r.width() in 1 until bmp.width || r.height() in 1 until bmp.height) {
            val cropped = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            if (cropped !== bmp) bmp.recycle()
            bmp = cropped
        }
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
