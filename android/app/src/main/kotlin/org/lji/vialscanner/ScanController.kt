package org.lji.vialscanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Rational
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.lji.vialcore.NormRect
import org.lji.vialcore.ScanEngine
import org.lji.vialcore.ScanState
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    /**
     * The same seven symbologies Swift asks Vision for
     * (`ScanEngine.swift`'s `barcodeRequest`), and no more. Left unrestricted,
     * ML Kit hunts every format it knows on every frame, which costs time the
     * pacing budget has to pay for and can surface a code the Swift side would
     * never have reported. `MlKitFrame.symbologyName` names exactly these.
     */
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC,
            )
            .build()
    )

    var state by mutableStateOf<ScanState>(idle())
        private set

    /** Paused between scans so a finished result is not overwritten by the next
     *  frame before the user has acted on it. */
    var running by mutableStateOf(true)
        private set

    var torchOn by mutableStateOf(false)
        private set

    // ---- Optics ----------------------------------------------------------
    // A vial label is 5-10 cm from the lens, which is the hardest distance for
    // a phone to find on its own: left alone, continuous autofocus hunts
    // between the label and the freezer rack behind it, and every frame it
    // spends hunting is a frame the sharpness gate throws away. The three
    // controls below are the same three Swift has, for the same reason.

    /** Focus held on one distance, so a slow rotation cannot make it hunt. */
    var focusLocked by mutableStateOf(false)
        private set

    /** Where the last focus request was aimed, in view fractions, for the
     *  on-screen ring. Null means nothing to draw. */
    var focusPoint by mutableStateOf<Pair<Float, Float>?>(null)
        private set

    var zoomRatio by mutableStateOf(1f)
        private set

    /** The lens stops worth offering, e.g. 0.5x / 1x / 2x. One entry means the
     *  phone has nothing to switch between and the picker should not appear. */
    var zoomOptions by mutableStateOf(listOf(1f))
        private set

    /** Where the recogniser last saw text. Drawn over the preview, and the
     *  target the tracking autofocus steers at. */
    var textBoxes by mutableStateOf<List<NormRect>>(emptyList())
        private set

    /** Off makes focus behave like any camera app: centre-weighted, no
     *  steering. Surfaced because a label that fills the frame does not need
     *  it, and steering costs a focus round-trip every 0.4 s. */
    var autoTextFocus = true

    private var minZoom = 1f
    private var maxZoom = 1f
    private var previewView: PreviewView? = null
    private var lastAutoFocus = 0L
    private var lastFocusTarget: Pair<Double, Double>? = null

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
        // A stale box from the previous vial would be drawn over the new one,
        // and would be the first thing tracking autofocus steered at.
        textBoxes = emptyList()
        lastFocusTarget = null
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
            previewView = view
            val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            camera = cam
            if (store.settings.torchOnByDefault) {
                torchOn = true
                cam.cameraControl.enableTorch(true)
            }

            // What this phone's back camera can actually do. Asking for 0.5x on
            // a phone without an ultra-wide is not an error, it silently clamps
            // to 1x, which would leave a dead button on the screen.
            val z = cam.cameraInfo.zoomState.value
            minZoom = z?.minZoomRatio ?: 1f
            maxZoom = (z?.maxZoomRatio ?: 1f).coerceAtMost(10f)
            zoomOptions = listOf(0.5f, 1f, 2f).filter { it in minZoom..maxZoom }
                .ifEmpty { listOf(1f) }

            // Ultra-wide focuses far closer than the main lens - around 2 cm
            // against 10 - which is the whole reason to prefer it here. It is a
            // preference and not the default because it is also the softer
            // sensor, and a vial held at arm's length reads better at 1x.
            val want = if (store.settings.preferUltraWideZoom) minZoom else 1f
            setZoom(want.coerceIn(minZoom, maxZoom))
        }, ContextCompat.getMainExecutor(ctx))
    }

    // ---- Focus & zoom -----------------------------------------------------

    /**
     * Aim focus and exposure at a point, given as fractions of the preview.
     *
     * `lock` is the difference between "look here now" and "look here and stop
     * deciding": CameraX cancels a focus action after a few seconds by default
     * and hands the lens back to continuous autofocus, which is right for a tap
     * and wrong for the lock button.
     */
    fun focusAt(fx: Float, fy: Float, lock: Boolean = false) {
        val view = previewView ?: return
        val cam = camera ?: return
        val pt = view.meteringPointFactory.createPoint(fx * view.width, fy * view.height)
        val action = FocusMeteringAction.Builder(
            pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).apply {
            if (lock) disableAutoCancel() else setAutoCancelDuration(4, TimeUnit.SECONDS)
        }.build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
        focusPoint = fx to fy
        focusLocked = lock
    }

    fun toggleFocusLock() {
        if (focusLocked) {
            runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
            focusLocked = false
            focusPoint = null
        } else {
            // Lock on whatever is being aimed at: the last tap if there was one,
            // otherwise the middle of the reticle.
            val (x, y) = focusPoint ?: (0.5f to 0.5f)
            focusAt(x, y, lock = true)
        }
    }

    fun setZoom(r: Float) {
        val clamped = r.coerceIn(minZoom, maxZoom)
        runCatching { camera?.cameraControl?.setZoomRatio(clamped) }
        zoomRatio = clamped
    }

    fun zoomBy(scale: Float) = setZoom(zoomRatio * scale)

    /**
     * Steer focus at the text the recogniser just found.
     *
     * Swift does this by moving `focusPointOfInterest` while the device stays in
     * `.continuousAutoFocus`: a hint about where to look, not an order to
     * refocus. CameraX has no equivalent - `startFocusAndMetering` is the only
     * way in, and it *triggers* an autofocus scan. Called on every analysed
     * frame that would be worse than doing nothing, because an AF scan
     * cancelled and restarted twenty times a second never converges, and the
     * lens would hunt harder than if it were left alone.
     *
     * So it fires only when re-aiming would actually change something: the
     * label has moved a real distance across the frame, or it has been long
     * enough that the working distance has plausibly changed. A vial held still
     * gets one scan and then silence, which is the behaviour Swift gets for
     * free.
     */
    private fun steerFocus(boxes: List<NormRect>) {
        if (!autoTextFocus || focusLocked || boxes.isEmpty()) return
        val cx = boxes.sumOf { it.minX + it.width / 2 } / boxes.size
        // The engine's boxes have their origin bottom-left, following Vision;
        // the preview's is top-left. Flipping is the whole conversion.
        val cy = 1.0 - boxes.sumOf { it.minY + it.height / 2 } / boxes.size

        val now = System.currentTimeMillis()
        val prev = lastFocusTarget
        val moved = prev == null ||
            kotlin.math.hypot(cx - prev.first, cy - prev.second) > 0.08
        if (!moved && now - lastAutoFocus < 2_000) return
        if (now - lastAutoFocus < 600) return
        lastAutoFocus = now
        lastFocusTarget = cx to cy

        val view = previewView ?: return
        val cam = camera ?: return
        val pt = view.meteringPointFactory.createPoint(
            cx.toFloat() * view.width, cy.toFloat() * view.height,
        )
        val action = FocusMeteringAction.Builder(
            pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).disableAutoCancel().build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
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
            // Only when the frame was actually read. The engine skips OCR on a
            // blurred frame, and treating that silence as "no text here" would
            // blink the overlay off and drop the focus target at exactly the
            // moment focus is worst.
            val boxes = frame.lastTextBoxes
            if (boxes.isNotEmpty()) {
                textBoxes = boxes
                steerFocus(boxes)
            }
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
