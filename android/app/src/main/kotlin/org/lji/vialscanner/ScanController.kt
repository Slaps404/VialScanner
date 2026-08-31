package org.lji.vialscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import org.lji.vialcore.ScanResult
import org.lji.vialcore.ScanState
import java.io.File
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
class ScanController(private val store: AppStore, context: Context) {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val clipRecorder = ClipRecorder(context)
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
    // Starts false: the app opens on a viewfinder, but opening a viewfinder is
    // not the same as asking a question. iOS idles until the shutter is pressed,
    // and an app that begins reading the room on launch burns battery, heats the
    // phone before the first real scan, and logs frames nobody asked for.
    var running by mutableStateOf(false)
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
    /** Diagnostic: log the real resolution chain once per bind, not per frame. */
    private var loggedChain = false

    private var lastAutoFocus = 0L
    private var lastFocusTarget: Pair<Double, Double>? = null
    private val main = Handler(Looper.getMainLooper())

    private var camera: Camera? = null
    private var engine: ScanEngine? = null
    private var engineStamp: String = ""

    // ---- Clips -------------------------------------------------------------
    // Whether this scan wants its video kept is decided once, at the shutter -
    // see `ScanScreen`'s `startNextScan`, mirroring Swift's `beginScan()`.

    private var recordingClip = false

    /** Set if binding Preview + ImageAnalysis + VideoCapture together threw on
     *  this device. ImageAnalysis is non-negotiable, so clips are what gives. */
    var clipRecordingUnsupported by mutableStateOf(false)
        private set

    /** The file `finishNow`/auto-finish just handed back, waiting on the
     *  operator's decision. Null once consumed or discarded, same shape as
     *  Swift's `CameraController.pendingClip`. */
    var pendingClip by mutableStateOf<File?>(null)
        private set

    /** Frames the last *completed* scan saw, captured off the last `Scanning`
     *  state before it turned `Finished` - by the time a result exists, the
     *  engine has already discarded them. Feeds `ClipMetadata.capture`. */
    var lastFramesSeen = 0
        private set
    var lastOcrFrames = 0
        private set

    fun consumePendingClip(): File? {
        val f = pendingClip
        pendingClip = null
        return f
    }

    fun discardPendingClip() {
        ClipRecorder.discard(pendingClip)
        pendingClip = null
    }

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

    /**
     * Start a fresh scan. `recordClip` is decided by the caller once, up front
     * - `keepClip` toggle, "always keep", or hands-free - because a decision
     * made mid-scan would mean stitching two files together for one result.
     */
    fun rescan(recordClip: Boolean = false) {
        discardPendingClip()
        currentEngine().reset()
        state = idle()
        running = true
        // A stale box from the previous vial would be drawn over the new one,
        // and would be the first thing tracking autofocus steered at.
        textBoxes = emptyList()
        lastFocusTarget = null
        lastFramesSeen = 0
        lastOcrFrames = 0
        recordingClip = recordClip && !clipRecordingUnsupported
        if (recordingClip) clipRecorder.start()
    }

    /** Abandon the scan in progress: no result, no clip. */
    fun cancelScan() {
        if (recordingClip) { clipRecorder.cancel(); recordingClip = false }
        discardPendingClip()
        state = idle()
        running = false
        textBoxes = emptyList()
    }

    fun pause() { running = false }

    fun toggleTorch() {
        val c = camera ?: return
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
    }

    /**
     * A 1920-long-edge target, used for both Preview and ImageAnalysis so the
     * StreamSharing parent and the analysis stream agree on how much detail to
     * carry. CLOSEST_HIGHER_THEN_LOWER rather than an exact size: no sensor is
     * obliged to offer 1920x1080, and taking the next size up beats silently
     * falling back to something far smaller.
     */
    private fun hdSelector() = ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(1920, 1080),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            )
        )
        .build()

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        val ctx = view.context
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            // Preview needs an explicit resolution for the same reason analysis
            // does. Preview and VideoCapture cannot have separate streams on
            // this hardware, so CameraX puts them behind one StreamSharing
            // parent and sizes that parent from Preview. Left at its default,
            // Preview asked small, the parent came back 1280x960, and the
            // saved clip was a 628x1280 crop of it - unreadable on playback
            // even though the recorder had asked for FHD.
            val preview = Preview.Builder()
                .setResolutionSelector(hdSelector())
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                // Ask for a 1920-long-edge stream, matching Swift's
                // `.hd1920x1080` preset.
                //
                // 720p was the wrong call and the measurement says so. On a
                // Pixel 5a it resolved to a 1280x960 buffer, the ViewPort then
                // cropped it to 1280x628, and after the engine's 0.15 ROI inset
                // ML Kit was reading a 440x781 image. iOS hands Vision roughly
                // 756x1344 for the same vial. Character height scales with the
                // short edge, so label text that lands 18 px tall on an iPhone
                // landed near 10 px here, under what ML Kit reads reliably. The
                // symptom reads as blur; the cause is that the pixels were
                // never there.
                //
                // The extra cost is real - the per-frame bitmap goes from about
                // 3 MB to 7 MB, and `uprightBitmap` copies it once to rotate -
                // but STRATEGY_KEEP_ONLY_LATEST drops what the analyser cannot
                // keep up with, and the engine only needs `maxOCRFrames` (24)
                // good frames across `maxScanSeconds` (4). Roughly 6 fps is
                // enough; there is a lot of room above that.
                .setResolutionSelector(hdSelector())
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
                .addUseCase(clipRecorder.useCase)
                .build()

            provider.unbindAll()
            previewView = view
            // Preview + ImageAnalysis + VideoCapture is three use cases on one
            // camera; CameraX silently trims a combination the hardware cannot
            // run rather than throwing, and ImageAnalysis is what the whole app
            // is for. If bindToLifecycle instead throws (a device that will not
            // run the trio at all), fall back to the two the engine cannot do
            // without and drop clip recording for this session.
            val cam = try {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            } catch (e: IllegalArgumentException) {
                clipRecordingUnsupported = true
                val fallback = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, fallback)
            }
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
            // Swift can hardcode 0.5x because every iPhone with a wide lens
            // puts it exactly there. Android cannot: the Pixel 5a's back camera
            // reports a range of 0.615..7.0, so a hardcoded 0.5 is filtered out
            // as unreachable and the phone silently loses the one lens that can
            // focus close enough to read a vial. Take the widest stop the sensor
            // actually offers instead.
            zoomOptions = buildList {
                if (minZoom < 0.9f) add(minZoom)
                add(1f)
                if (maxZoom >= 2f) add(2f)
            }

            // Ultra-wide focuses far closer than the main lens - around 2 cm
            // against 10 - which is the whole reason to prefer it here. It is a
            // preference and not the default because it is also the softer
            // sensor, and a vial held at arm's length reads better at 1x.
            val want = if (store.settings.preferUltraWideZoom) minZoom else 1f
            setZoom(want.coerceIn(minZoom, maxZoom))

            // Aim focus and exposure at the middle of the frame straight away.
            //
            // `steerFocus` can only aim at text the recogniser has already
            // found, which is a circular dependency: a label too soft to read
            // produces no boxes, no boxes means no steering, and default
            // centre-weighted continuous autofocus is free to settle on the
            // bench behind the vial - which is exactly what the debug clips
            // show, background sharp and label soft. Seeding the region at the
            // reticle centre breaks the circle. Swift gets this for free in
            // `resetFocus`, which parks the point of interest at 0.5, 0.5.
            main.post { aimAt(0.5f, 0.5f, lock = false) }
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
        aimAt(fx, fy, lock)
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
        // Both gates got looser when the action stopped locking. A held scan
        // now stays under continuous autofocus between nudges, so a nudge is no
        // longer the only thing keeping focus alive - and every nudge still
        // costs a real lens sweep. 0.08 of the frame is inside hand tremor at
        // arm's length, so at the old 600 ms floor a steady hand re-triggered a
        // sweep roughly twice a second and the lens never settled.
        val moved = prev == null ||
            kotlin.math.hypot(cx - prev.first, cy - prev.second) > 0.15
        if (!moved && now - lastAutoFocus < 2_500) return
        if (now - lastAutoFocus < 1_200) return
        lastAutoFocus = now
        lastFocusTarget = cx to cy

        // `PreviewView.getMeteringPointFactory` asserts the main thread, and
        // this runs on CameraX's analysis executor, so reading it here throws
        // IllegalStateException and takes the app down on the first frame that
        // finds text. Hop threads; the point is stale by a frame either way,
        // which does not matter for something already throttled to seconds.
        // Unlocked, deliberately. `disableAutoCancel` was the original choice
        // and it is the wrong one: it turns a metering request into a one-shot
        // scan whose result is then *held*, so the first scan that converges on
        // the wrong plane stays wrong. On a cluttered bench the wrong plane is
        // the common case - a floor or a desk has far more contrast than a
        // small clear label, so it is what the AF algorithm grabs. Letting the
        // action auto-cancel hands the lens back to continuous autofocus, which
        // is the state Swift never leaves.
        main.post { aimAt(cx.toFloat(), cy.toFloat(), lock = false) }
    }

    /** Build a metering point from view fractions and send it. Main thread only. */
    private fun aimAt(fx: Float, fy: Float, lock: Boolean) {
        val view = previewView ?: return
        val cam = camera ?: return
        if (view.width == 0 || view.height == 0) return
        val pt = view.meteringPointFactory.createPoint(fx * view.width, fy * view.height)
        val action = FocusMeteringAction.Builder(
            pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).apply {
            if (lock) disableAutoCancel() else setAutoCancelDuration(4, TimeUnit.SECONDS)
        }.build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (!running || state is ScanState.Finished) return
            val bmp = uprightBitmap(proxy)
            if (!loggedChain) {
                loggedChain = true
                val r = proxy.cropRect
                Log.i(
                    "VialOptics",
                    "buffer=${proxy.width}x${proxy.height} " +
                        "crop=${r.width()}x${r.height()} " +
                        "rot=${proxy.imageInfo.rotationDegrees} " +
                        "ocrBitmap=${bmp.width}x${bmp.height} " +
                        "roiInset=${store.settings.roiInset}",
                )
            }
            val frame = MlKitFrame(
                bitmap = bmp,
                timestamp = proxy.imageInfo.timestamp / 1_000_000_000.0,
                recognizer = recognizer,
                scanner = scanner,
            )
            val s = currentEngine().ingest(frame)
            if (s is ScanState.Scanning) {
                lastFramesSeen = s.framesSeen
                lastOcrFrames = s.ocrFrames
            }
            if (s is ScanState.Finished) deliverFinished(s.result) else state = s
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
        deliverFinished(currentEngine().finish())
    }

    /**
     * A scan just ended, one way or another. If it was recording, the clip has
     * to be finalised before the result card appears - `Recording.stop()`
     * completes asynchronously, and a card offering to keep a clip that is not
     * on disk yet is a promise the app cannot keep.
     */
    private fun deliverFinished(result: ScanResult) {
        if (!recordingClip) {
            state = ScanState.Finished(result)
            return
        }
        recordingClip = false
        clipRecorder.finish { file ->
            pendingClip = file
            state = ScanState.Finished(result)
        }
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
        if (recordingClip) clipRecorder.cancel()
        discardPendingClip()
        analysisExecutor.shutdown()
        recognizer.close()
        scanner.close()
    }
}
