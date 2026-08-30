package org.lji.vialcore

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
    /** Restrict recognition to the centre of the frame, matching the on-screen
     *  reticle the user aims with: OCR cost scales with pixel area and the outer
     *  band is floor, freezer and glove. Normalised, origin bottom-left. */
    var regionOfInterest: NormRect? = NormRect(0.15, 0.20, 0.70, 0.60)
    /** Run the barcode pass on every Nth frame. Codes stay in view for many
     *  consecutive frames, so sampling costs almost no decode opportunities. */
    var barcodeEveryNthFrame = 2
    /** Cap how many frames ever reach OCR; the sample set resolved on 4-5. */
    var maxOCRFrames = 24
    /** Minimum gap between OCR passes, so a fast shutter cannot flood the queue. */
    var minOCRInterval: Double = 0.15
    /** Frames below this fraction of the best sharpness seen are skipped. */
    var sharpnessRatio: Double = 0.45
    var earlyExitOnBarcode = true
    var earlyExitMinFrames = 4

    // ---- Patience -----------------------------------------------------------
    //
    // These are not accuracy gates. They gate *when an answer is allowed to
    // count*, so the engine waits until the user has actually presented the vial
    // rather than until the first plausible reading turns up. Without them a
    // barcode decoded on frame one ends the scan with the label never read - and
    // the answer is usually right, which is worse, because then the failures are
    // the ones nobody goes looking for.

    /** No verdict settles before this much stream time, whatever the evidence. */
    var minScanDuration: Double = 1.0
    /** A payload must decode in this many frames before it may end the scan. */
    var barcodeConfirmations = 2
    /** Distinct faces of the label required before a text verdict may settle. */
    var minDistinctViews = 2
    /** ...or, failing rotation, this many text-bearing frames that add nothing
     *  new: the visible face has given up everything it has. */
    var saturationFrames = 4
    /** The same answer must come back this many resolves running. */
    var settleConfirmations = 2
    /** Give up after this much elapsed stream time, so failure is not slower
     *  than success. */
    var maxScanDuration: Double = 4.0

    // ---- Grace --------------------------------------------------------------
    //
    // A deadline is a promise to stop guessing, not a promise to produce an
    // answer. When the cap arrives having read nothing, the matcher says
    // "new vial - nothing on file matches this label" about a vial nobody ever
    // read. Nothing was wrong with it; the camera had not seen it yet. So give
    // the time back rather than spend it on a verdict.

    var graceExtension: Double = 2.5
    var maxExtensions = 2
    /** Absolute ceiling: a scanner that never stops is worse than one that
     *  admits it failed. */
    var hardScanCeiling: Double = 12.0
    /** OCR passes added with each extension, so the extra seconds are usable. */
    var ocrFramesPerExtension = 12
    var match = MatchConfig()
}

sealed class ScanState {
    /** `progress` is readiness to answer, not frames burned. */
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

/**
 * Drives the two-tier pipeline over a stream of frames.
 *
 * Fed live: [ingest] is called for every camera frame and returns immediately,
 * so the work happens *during* the user's rotation. By the time they stop
 * turning the vial, the answer already exists.
 */
class ScanEngine(catalog: Catalog, val config: EngineConfig = EngineConfig()) {
    val matcher = Matcher(catalog, config.match)

    private var evidence = EvidenceBuffer()

    /** Everything the scan accumulated. Exposed for the offline harness and the
     *  audit view, which need what the camera read, not only what was concluded. */
    val accumulated: EvidenceBuffer get() = evidence

    private var framesSeen = 0
    private var ocrFrames = 0
    private var bestSharpness = 0.0
    private var lastOCRTime = Double.NEGATIVE_INFINITY
    private var settled: ScanResult? = null
    private var firstTimestamp: Double? = null

    /** The candidate awaiting confirmation, and how many resolves produced it. */
    private var pendingIdentity: String? = null
    private var pendingCount = 0

    /** When the scan currently intends to stop, and how often that has moved. */
    private var deadline = config.maxScanDuration
    private var ocrBudget = config.maxOCRFrames
    private var extensionsGranted = 0
    private var latestTextBoxes: List<NormRect> = emptyList()

    fun reset() {
        evidence = EvidenceBuffer()
        framesSeen = 0; ocrFrames = 0
        bestSharpness = 0.0; lastOCRTime = Double.NEGATIVE_INFINITY
        settled = null; firstTimestamp = null
        pendingIdentity = null; pendingCount = 0
        deadline = config.maxScanDuration
        ocrBudget = config.maxOCRFrames
        extensionsGranted = 0
        latestTextBoxes = emptyList()
    }

    fun ingest(frame: VialFrame): ScanState {
        settled?.let { return ScanState.Finished(it) }
        framesSeen += 1
        if (firstTimestamp == null) firstTimestamp = frame.timestamp
        val elapsed = frame.timestamp - (firstTimestamp ?: frame.timestamp)

        // ---- Tier 1: barcode, full resolution, sampled ----------------------
        if (config.barcodeEveryNthFrame > 0 &&
            (framesSeen - 1) % config.barcodeEveryNthFrame == 0
        ) {
            for (b in frame.detectBarcodes(config.regionOfInterest)) {
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
                if (r.verdict == Verdict.CERTAIN) {
                    settled = r
                    return ScanState.Finished(r)
                }
                // The code named a product not on file. That verdict is
                // definitive, but the label still has to be read: those fields
                // pre-fill the receive form, and stopping on the decode alone
                // hands the user an empty card for a vial they must now type in.
                if (r.verdict == Verdict.NOT_FOUND && coverageSatisfied) {
                    settled = r
                    return ScanState.Finished(r)
                }
            }
        }

        // ---- Tier 2: OCR, half-res gray, sharpest frames only ---------------
        //
        // Pacing and budget are checked *before* sharpness, because measuring
        // sharpness costs real milliseconds and there is no reason to pay it on
        // a frame that could not be OCR'd anyway.
        val paced = frame.timestamp - lastOCRTime >= config.minOCRInterval
        val budget = ocrFrames < ocrBudget
        var sharpEnough = true
        if (paced && budget) {
            val sharp = frame.sharpness
            // Decay the running best: one exceptionally crisp frame early on
            // would otherwise raise the bar for the rest of the rotation and
            // starve the OCR tier of frames.
            bestSharpness = maxOf(sharp, bestSharpness * 0.98)
            sharpEnough = bestSharpness == 0.0 || sharp >= bestSharpness * config.sharpnessRatio
        }

        if (sharpEnough && paced && budget) {
            lastOCRTime = frame.timestamp
            ocrFrames += 1
            val obs = frame.recognizeText(config.regionOfInterest)
                .filter { it.confidence > 0.3f }
            latestTextBoxes = obs.mapNotNull { it.boundingBox }
            evidence.addFrame(obs)

            if (ocrFrames >= config.earlyExitMinFrames &&
                elapsed >= config.minScanDuration &&
                coverageSatisfied
            ) {
                val r = matcher.resolve(evidence, EvidenceStandard.CORROBORATED)
                if ((r.verdict == Verdict.CERTAIN || r.verdict == Verdict.CONFIDENT) && confirmed(r)) {
                    settled = r
                    return ScanState.Finished(r)
                }
            }
        }

        if (elapsed >= deadline) {
            val r = matcher.resolve(evidence)
            // Only stop on the deadline if there is something to stop *with*.
            if (r.evidenceLevel == EvidenceLevel.SUFFICIENT ||
                extensionsGranted >= config.maxExtensions ||
                elapsed + config.graceExtension > config.hardScanCeiling
            ) {
                settled = r
                return ScanState.Finished(r)
            }
            extensionsGranted += 1
            deadline = elapsed + config.graceExtension
            ocrBudget += config.ocrFramesPerExtension
        }

        return ScanState.Scanning(
            framesSeen = framesSeen, ocrFrames = ocrFrames,
            views = evidence.distinctViews,
            progress = progress(elapsed),
            hint = hint(paced, budget, sharpEnough),
            textBoxes = latestTextBoxes,
        )
    }

    /** Has the user shown more than one face of the label - or run the visible
     *  face dry? */
    private val coverageSatisfied: Boolean
        get() = evidence.distinctViews >= config.minDistinctViews ||
            evidence.framesSinceNewToken >= config.saturationFrames

    /** A verdict may only end the scan once it has come back `settleConfirmations`
     *  resolves running: one that flickers between two definitions is not an
     *  answer, it is whichever was on top when the budget allowed a resolve. */
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

    /** Readiness to answer, averaged over the three things actually being waited
     *  on. Deliberately not a frame counter: a bar that fills at a fixed rate
     *  tells the user nothing about whether rotating more would help. */
    private fun progress(elapsed: Double): Double {
        val time = minOf(1.0, elapsed / maxOf(0.01, config.minScanDuration))
        val passes = minOf(1.0, ocrFrames.toDouble() / maxOf(1, config.earlyExitMinFrames))
        val coverage = if (coverageSatisfied) 1.0
        else minOf(1.0, evidence.distinctViews.toDouble() / maxOf(1, config.minDistinctViews))
        // Without this term the bar sits full through every granted extension,
        // telling the user the scan is done while it waits for the one thing it
        // still does not have.
        val read = when (currentLevel) {
            EvidenceLevel.NONE -> 0.0
            EvidenceLevel.THIN -> 0.5
            EvidenceLevel.SUFFICIENT -> 1.0
        }
        return (time + passes + coverage + read) / 4
    }

    /** Evidence level as it stands, without running a full resolve. */
    private val currentLevel: EvidenceLevel
        get() {
            val fields = LinkedHashMap<LabelField, String>()
            for (f in LabelField.entries) {
                evidence.vote(f, 2)?.let { fields[f] = it.first }
            }
            return Matcher.evidenceLevel(evidence, fields)
        }

    private fun hint(paced: Boolean, budget: Boolean, sharpEnough: Boolean): String {
        if (paced && budget && !sharpEnough) return "Hold steady"
        if (evidence.frameCount == 0) return "Line the label up"
        // Past the first deadline the generic hints stop being useful: the scan
        // is in extra time precisely because something specific is missing.
        if (extensionsGranted > 0) {
            return if (evidence.allTokens.isEmpty()) "Nothing readable yet - move closer, slower"
            else "Still need the catalog number - keep turning"
        }
        if (!coverageSatisfied) return "Keep rotating"
        if (evidence.vote(LabelField.CATALOG, 2) == null) return "Looking for the catalog number"
        return "Reading label..."
    }

    /** Force a verdict from whatever evidence exists (user stopped rotating). */
    fun finish(): ScanResult {
        settled?.let { return it }
        val r = matcher.resolve(evidence)
        settled = r
        return r
    }
}
