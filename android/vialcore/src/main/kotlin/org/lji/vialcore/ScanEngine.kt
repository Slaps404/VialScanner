package org.lji.vialcore

import kotlin.math.pow

/**
 * One camera frame, as far as the engine is concerned.
 *
 * The Swift engine owns its `VNRecognizeTextRequest` and `VNDetectBarcodesRequest`
 * directly, which is why `ScanEngine.swift` has to import Vision and drags the
 * whole engine onto Apple platforms. Here the two expensive passes are behind
 * this interface instead, so `vialcore` stays plain Kotlin/JVM: the Android app
 * implements it with ML Kit over a CameraX `ImageProxy`, and the offline test
 * harness implements it by replaying recorded observations. Neither one changes
 * a line of the pacing logic below.
 *
 * Coordinates: [recognizeText] returns boxes in Vision's convention - normalised
 * to the *full* frame, origin bottom-left. ML Kit reports pixels with origin
 * top-left, so the adapter does that conversion once, in one place, rather than
 * leaving every consumer of a box to remember which way up it is.
 */
interface VialFrame {
    /** Stream time in seconds. Only differences matter, not the epoch. */
    val timestamp: Double

    /**
     * Variance-of-Laplacian style focus measure. Compared only against other
     * frames from the same stream, so the absolute scale is free.
     */
    val sharpness: Double

    /** Full-resolution barcode pass. Downscaling collapses the decode rate. */
    fun detectBarcodes(roi: NormRect?): List<BarcodePayload>

    /** Text pass. Half-resolution grayscale is enough and is ~2x faster. */
    fun recognizeText(roi: NormRect?): List<TextObservation>
}

class EngineConfig {
    // Recognition stays paced; elapsed time is the single collection budget.
    var regionOfInterest: NormRect? = NormRect(0.15, 0.20, 0.70, 0.60)
    var barcodeEveryNthFrame = 2
    var minOCRInterval = 0.15
    var emptyOCRFramesBeforeBackoff = 3
    var ocrBackoffFactor = 1.6
    var maxOCRInterval = 0.6
    var backoffOnlyBeforeFirstText = true
    var sharpnessRatio = 0.45
    var sharpnessWindow = 8
    var earlyExitOnBarcode = true
    var earlyExitMinFrames = 4
    var minScanDuration = 1.0
    var barcodeConfirmations = 2
    var settleConfirmations = 2
    var maxScanDuration = 9.0
    var hardScanCeiling = 20.0
    var match = MatchConfig()
}

sealed class ScanState {
    /** Progress is elapsed collection time, not label coverage. */
    data class Scanning(
        val framesSeen: Int,
        val ocrFrames: Int,
        val views: Int,
        val progress: Double,
        val hint: String,
        val textBoxes: List<NormRect> = emptyList(),
    ) : ScanState()

    data class Finished(val result: ScanResult) : ScanState()
}

/** Runs recognition synchronously on the caller's background queue. */
class ScanEngine(catalog: Catalog, val config: EngineConfig = EngineConfig()) {
    val matcher = Matcher(catalog, config.match)

    private var evidence = EvidenceBuffer()

    /** Everything the scan accumulated. Exposed for the offline harness and the
     *  audit view, which need what the camera read, not only what was concluded. */
    val accumulated: EvidenceBuffer get() = evidence

    private var framesSeen = 0
    private var ocrFrames = 0
    private val recentSharpness = ArrayDeque<Double>()
    private var lastOCRTime = Double.NEGATIVE_INFINITY
    private var settled: ScanResult? = null
    private var firstTimestamp: Double? = null

    /** The candidate awaiting confirmation, and how many resolves produced it. */
    private var pendingIdentity: String? = null
    private var pendingCount = 0

    /** When the scan currently intends to stop, and how often that has moved. */
    private val deadline: Double get() = minOf(config.maxScanDuration, config.hardScanCeiling).coerceAtLeast(0.0)
    private var latestTextBoxes: List<NormRect> = emptyList()

    /** Consecutive OCR passes that came back with no usable text. */
    private var emptyOCRRun = 0

    fun reset() {
        evidence = EvidenceBuffer()
        framesSeen = 0; ocrFrames = 0
        recentSharpness.clear(); lastOCRTime = Double.NEGATIVE_INFINITY
        settled = null; firstTimestamp = null
        pendingIdentity = null; pendingCount = 0
        latestTextBoxes = emptyList()
        emptyOCRRun = 0
    }

    fun ingest(frame: VialFrame): ScanState {
        settled?.let { return ScanState.Finished(it) }
        framesSeen += 1
        if (firstTimestamp == null) firstTimestamp = frame.timestamp
        val elapsed = frame.timestamp - (firstTimestamp ?: frame.timestamp)
        if (elapsed >= deadline) return ScanState.Finished(finish())

        // ---- Tier 1: barcode, full resolution, sampled ----------------------
        if (config.barcodeEveryNthFrame > 0 &&
            (framesSeen - 1) % config.barcodeEveryNthFrame == 0
        ) {
            for (b in frame.detectBarcodes(config.regionOfInterest).distinctBy { it.raw }) {
                if (b.raw.isEmpty()) continue
                evidence.addBarcode(b)
            }
            // A decoded barcode is the strongest evidence available, but one
            // decode is one glimpse, and a DataMatrix on a tube in the rack
            // behind the one being held decodes just as cleanly. Wait for the
            // same payload twice, and never cut the scan short before the user
            // has had their second to present the vial.
            if (config.earlyExitOnBarcode && elapsed >= config.minScanDuration &&
                evidence.hasConfirmedBarcode(config.barcodeConfirmations)
            ) {
                val r = matcher.resolve(evidence, EvidenceStandard.CORROBORATED)
                if (catalogued(r)) {
                    settled = r
                    return ScanState.Finished(r)
                }

            }
        }

        // ---- Tier 2: OCR, half-res gray, sharpest frames only ---------------
        //
        // Pacing is checked *before* sharpness, because measuring sharpness
        // costs real milliseconds and there is no reason to pay it on a frame
        // that could not be OCR'd anyway.
        val paced = frame.timestamp - lastOCRTime >= ocrInterval
        var sharpEnough = true
        if (paced) {
            val sharp = frame.sharpness
            recentSharpness.addLast(sharp)
            while (recentSharpness.size > config.sharpnessWindow) recentSharpness.removeFirst()
            val bar = recentSharpness.max()
            sharpEnough = bar == 0.0 || sharp >= bar * config.sharpnessRatio
        }

        if (sharpEnough && paced) {
            lastOCRTime = frame.timestamp
            val obs = frame.recognizeText(config.regionOfInterest)
                .filter { it.confidence > 0.3f }
            latestTextBoxes = obs.mapNotNull { it.boundingBox }
            if (obs.isEmpty()) emptyOCRRun += 1
            else { emptyOCRRun = 0; ocrFrames += 1 }
            evidence.addFrame(obs)

            if (ocrFrames >= config.earlyExitMinFrames &&
                elapsed >= config.minScanDuration
            ) {
                val r = matcher.resolve(evidence, EvidenceStandard.CORROBORATED)
                if (catalogued(r)) {
                    if (confirmed(r)) {
                        settled = r
                        return ScanState.Finished(r)
                    }
                } else {
                    pendingIdentity = null
                    pendingCount = 0
                }
            }
        }

        return ScanState.Scanning(
            framesSeen = framesSeen, ocrFrames = ocrFrames,
            views = evidence.distinctViews,
            progress = progress(elapsed),
            hint = hint(paced, sharpEnough),
            textBoxes = latestTextBoxes,
        )
    }

    /** `minOCRInterval`, widened geometrically after a run of empty passes. */
    private val ocrInterval: Double
        get() {
            if (config.backoffOnlyBeforeFirstText && ocrFrames > 0) return config.minOCRInterval
            val over = emptyOCRRun - config.emptyOCRFramesBeforeBackoff + 1
            if (over <= 0 || config.ocrBackoffFactor <= 1.0) return config.minOCRInterval
            return minOf(
                config.maxOCRInterval,
                config.minOCRInterval * config.ocrBackoffFactor.pow(over),
            )
        }

    /** Early exit means the inventory row is resolved, not just the product. */
    private fun catalogued(result: ScanResult): Boolean = result.canAutoConfirm

    /** A verdict may only end the scan once it has come back `settleConfirmations`
     *  resolves running: one that flickers between two definitions is not an
     *  answer, it is whichever was on top when the pacing allowed a resolve. */
    private fun confirmed(r: ScanResult): Boolean {
        val id = listOf(
            r.verdict.name,
            r.definition?.catalog ?: "-",
            r.vial?.abNo ?: "-",
            if (r.lotNotOnRecord) "lot-off-record" else "-",
        ).joinToString("|")
        if (pendingIdentity == id) pendingCount += 1 else { pendingIdentity = id; pendingCount = 1 }
        return pendingCount >= config.settleConfirmations
    }

    /** Elapsed collection time, not a claim that the label is complete. */
    private fun progress(elapsed: Double): Double = (elapsed / maxOf(0.01, deadline)).coerceIn(0.0, 1.0)

    private fun hint(paced: Boolean, sharpEnough: Boolean): String {
        if (paced && !sharpEnough) return "Hold steady"
        if (evidence.allTokens.isEmpty()) return "Line the label up"
        return "Turn to show the rest, or tap Done"
    }

    /** Force a verdict from whatever evidence exists (user stopped rotating). */
    fun finish(): ScanResult {
        settled?.let { return it }
        val r = matcher.resolve(evidence)
        settled = r
        return r
    }
}
