import Foundation
import CoreGraphics
import Vision
import CoreImage
import CoreVideo

public struct EngineConfig: Sendable {
    // Recognition stays paced; elapsed time is the single collection budget.
    public var regionOfInterest: CGRect? = CGRect(x: 0.15, y: 0.20, width: 0.70, height: 0.60)
    public var barcodeEveryNthFrame = 2
    public var minOCRInterval = 0.15
    public var emptyOCRFramesBeforeBackoff = 3
    public var ocrBackoffFactor = 1.6
    public var maxOCRInterval = 0.6
    public var backoffOnlyBeforeFirstText = true
    public var sharpnessRatio = 0.45
    public var sharpnessWindow = 8
    public var earlyExitOnBarcode = true
    public var earlyExitMinFrames = 4
    public var minScanDuration = 1.0
    public var barcodeConfirmations = 2
    public var settleConfirmations = 2
    public var maxScanDuration = 9.0
    public var hardScanCeiling = 20.0
    public var match = MatchConfig()
    public init() {}
}

public enum ScanState: Sendable {
    /// Progress is elapsed collection time, not label coverage.
    case scanning(framesSeen: Int, ocrFrames: Int, views: Int, progress: Double, hint: String, textBoxes: [CGRect] = [])
    case finished(ScanResult)
}

/// Runs recognition synchronously on the caller's background queue.
public final class ScanEngine {
    public let matcher: Matcher
    public let config: EngineConfig
    private let ciContext: CIContext

    private var evidence = EvidenceBuffer()
    /// Everything the scan accumulated. Exposed for the offline harness and the
    /// in-app audit view, which both need to show what the camera actually read
    /// rather than only what the matcher concluded from it.
    public var accumulated: EvidenceBuffer { evidence }
    private var framesSeen = 0
    private var ocrFrames = 0
    private var recentSharpness: [Double] = []
    private var lastOCRTime = -Double.infinity
    private var settled: ScanResult?
    private var firstTimestamp: Double?
    /// The candidate awaiting confirmation, and how many resolves have produced it.
    private var pendingIdentity: String?
    private var pendingCount = 0
    /// When the scan currently intends to stop, and how often that has moved.
    private var deadline: Double { max(0, min(config.maxScanDuration, config.hardScanCeiling)) }
    private let textRequest: VNRecognizeTextRequest
    private let barcodeRequest: VNDetectBarcodesRequest
    private var latestTextBoxes: [CGRect] = []
    /// Consecutive OCR passes that came back with no usable text.
    private var emptyOCRRun = 0

    // ---- Offline capture hooks ------------------------------------------
    //
    // The only way to check that a second implementation of this pipeline (the
    // Kotlin/ML Kit port) agrees with this one is to feed both the *same*
    // observations. Re-running OCR on the Android side would compare two OCR
    // engines, not two pipelines, and every disagreement would be unattributable.
    // These fire exactly where the evidence buffer is fed, so a recording of
    // them replays into an identical buffer.
    /// Which standard produced the verdict the scan ended on. A replay has to
    /// resolve the same way to be comparing like with like.
    public private(set) var lastStandard: EvidenceStandard = .best
    public var onBarcode: ((BarcodePayload) -> Void)?
    public var onOCRFrame: (([TextObservation]) -> Void)?

    public init(catalog: Catalog, config: EngineConfig = EngineConfig()) {
        self.config = config
        self.matcher = Matcher(catalog: catalog, config: config.match)
        self.ciContext = CIContext(options: [.cacheIntermediates: false])

        let t = VNRecognizeTextRequest()
        // `.fast` still ranks the right vial first, but collapses the score into
        // the noise band, which destroys the hit/no-hit threshold. Not an option.
        t.recognitionLevel = .accurate
        t.usesLanguageCorrection = false
        t.recognitionLanguages = ["en-US"]
        if let roi = config.regionOfInterest { t.regionOfInterest = roi }
        self.textRequest = t

        let b = VNDetectBarcodesRequest()
        b.symbologies = [.dataMatrix, .qr, .code128, .code39, .ean13, .pdf417, .aztec]
        if let roi = config.regionOfInterest { b.regionOfInterest = roi }
        self.barcodeRequest = b

    }

    public func reset() {
        evidence = EvidenceBuffer()
        framesSeen = 0; ocrFrames = 0
        recentSharpness.removeAll(); lastOCRTime = -.infinity
        settled = nil; firstTimestamp = nil
        pendingIdentity = nil; pendingCount = 0
        latestTextBoxes = []
        emptyOCRRun = 0
    }

    @discardableResult
    public func ingest(_ frame: VialFrame) -> ScanState {
        if let s = settled { return .finished(s) }
        framesSeen += 1
        if firstTimestamp == nil { firstTimestamp = frame.timestamp }
        let elapsed = frame.timestamp - (firstTimestamp ?? frame.timestamp)
        if elapsed >= deadline { return .finished(finish()) }

        // ---- Tier 1: barcode, full resolution, sampled ----------------------
        if config.barcodeEveryNthFrame > 0,
           (framesSeen - 1) % config.barcodeEveryNthFrame == 0 {
            let handler = frame.fullResHandler()
            try? handler.perform([barcodeRequest])
            var decoded = Set<String>()
            for obs in barcodeRequest.results ?? [] {
                guard let payload = obs.payloadStringValue, !payload.isEmpty, decoded.insert(payload).inserted else { continue }
                let bc = BarcodePayload(raw: payload, symbology: obs.symbology.rawValue)
                evidence.addBarcode(bc)
                onBarcode?(bc)
            }
            // A decoded barcode is the strongest evidence this engine can get,
            // but a single decode is a single glimpse — and a DataMatrix on a
            // tube in the rack behind the one being held decodes just as
            // cleanly. Wait for the same payload twice, and never cut the scan
            // short before the user has had their second to present the vial.
            if config.earlyExitOnBarcode, elapsed >= config.minScanDuration,
               evidence.hasConfirmedBarcode(minFrames: config.barcodeConfirmations) {
                let r = matcher.resolve(evidence, standard: .corroborated)
                lastStandard = .corroborated
                if catalogued(r) {
                    settled = r
                    return .finished(r)
                }
            }
        }

        // ---- Tier 2: OCR, half-res gray, sharpest frames only ----------------
        //
        // Pacing is checked *before* sharpness, because measuring sharpness
        // costs ~4 ms and there is no reason to pay it on a frame that could
        // not be OCR'd anyway.
        let paced = frame.timestamp - lastOCRTime >= ocrInterval
        var sharpEnough = true
        if paced {
            let sharp = frame.sharpness
            recentSharpness.append(sharp)
            while recentSharpness.count > config.sharpnessWindow { recentSharpness.removeFirst() }
            let bar = recentSharpness.max() ?? 0
            sharpEnough = bar == 0 || sharp >= bar * config.sharpnessRatio
        }

        if sharpEnough, paced, let handler = frame.halfResHandler() {
            lastOCRTime = frame.timestamp
            try? handler.perform([textRequest])
            let roi = config.regionOfInterest ?? CGRect(x: 0, y: 0, width: 1, height: 1)
            let obs = (textRequest.results ?? []).compactMap { o -> TextObservation? in
                guard let c = o.topCandidates(1).first, c.confidence > 0.3 else { return nil }
                let box = CGRect(
                    x: roi.minX + o.boundingBox.minX * roi.width,
                    y: roi.minY + o.boundingBox.minY * roi.height,
                    width: o.boundingBox.width * roi.width,
                    height: o.boundingBox.height * roi.height
                )
                return TextObservation(text: c.string, confidence: c.confidence, boundingBox: box)
            }
            latestTextBoxes = obs.compactMap(\.boundingBox)
            if obs.isEmpty { emptyOCRRun += 1 }
            else { emptyOCRRun = 0; ocrFrames += 1 }
            evidence.addFrame(obs)
            onOCRFrame?(obs)

            if ocrFrames >= config.earlyExitMinFrames,
               elapsed >= config.minScanDuration {
                let r = matcher.resolve(evidence, standard: .corroborated)
                lastStandard = .corroborated
                if catalogued(r) {
                    if confirmed(r) {
                        settled = r
                        return .finished(r)
                    }
                } else {
                    pendingIdentity = nil
                    pendingCount = 0
                }
            }
        }

        return .scanning(framesSeen: framesSeen, ocrFrames: ocrFrames,
                         views: evidence.distinctViews,
                         progress: progress(elapsed: elapsed),
                         hint: hint(paced: paced, sharpEnough: sharpEnough),
                         textBoxes: latestTextBoxes)
    }

    /// `minOCRInterval`, widened geometrically after a run of empty passes.
    private var ocrInterval: Double {
        if config.backoffOnlyBeforeFirstText, ocrFrames > 0 { return config.minOCRInterval }
        let over = emptyOCRRun - config.emptyOCRFramesBeforeBackoff + 1
        guard over > 0, config.ocrBackoffFactor > 1 else { return config.minOCRInterval }
        return min(config.maxOCRInterval,
                   config.minOCRInterval * pow(config.ocrBackoffFactor, Double(over)))
    }

    /// Early exit means the inventory row is resolved, not just the product.
    private func catalogued(_ result: ScanResult) -> Bool { result.canAutoConfirm }

    /// A verdict is only allowed to end the scan once the same verdict has come
    /// back `settleConfirmations` resolves running.
    private func confirmed(_ r: ScanResult) -> Bool {
        let id = [r.verdict.rawValue,
                  r.definition?.catalog ?? "-",
                  r.vial?.abNo ?? "-",
                  r.lotNotOnRecord ? "lot-off-record" : "-"].joined(separator: "|")
        if pendingIdentity == id { pendingCount += 1 } else { pendingIdentity = id; pendingCount = 1 }
        return pendingCount >= config.settleConfirmations
    }

    /// Elapsed collection time, not a claim that the label is complete.
    private func progress(elapsed: Double) -> Double {
        min(1, max(0, elapsed / max(0.01, deadline)))
    }

    private func hint(paced: Bool, sharpEnough: Bool) -> String {
        if paced, !sharpEnough { return "Hold steady" }
        if evidence.allTokens.isEmpty { return "Line the label up" }
        return "Turn to show the rest, or tap Done"
    }

    /// Force a verdict from whatever evidence exists (user stopped rotating).
    public func finish() -> ScanResult {
        if let s = settled { return s }
        let r = matcher.resolve(evidence)
        lastStandard = .best
        settled = r
        return r
    }

    public func makeFrame(pixelBuffer: CVPixelBuffer, timestamp: Double) -> VialFrame {
        VialFrame(pixelBuffer: pixelBuffer, timestamp: timestamp, context: ciContext)
    }
    public func makeFrame(cgImage: CGImage, timestamp: Double) -> VialFrame {
        VialFrame(cgImage: cgImage, timestamp: timestamp, context: ciContext)
    }
}
