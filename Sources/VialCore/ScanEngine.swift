import Foundation
import CoreGraphics
import Vision
import CoreImage
import CoreVideo

public struct EngineConfig: Sendable {
    /// Restrict recognition to the centre of the frame, matching the on-screen
    /// reticle the user aims with. This is the single biggest saving available:
    /// OCR cost scales with pixel area, and the outer band of the frame is floor,
    /// freezer and glove. Measured 70 ms -> 33 ms with no loss of text lines.
    /// Normalised, origin bottom-left; a centred rect is orientation-invariant.
    public var regionOfInterest: CGRect? = CGRect(x: 0.15, y: 0.20, width: 0.70, height: 0.60)
    /// Run the barcode pass on every Nth frame. It must stay at full resolution
    /// (decode rate collapses when downscaled) and so costs ~17 ms, which is too
    /// much to spend 30 times a second alongside OCR. Codes stay in view for
    /// many consecutive frames, so sampling costs almost no decode opportunities.
    public var barcodeEveryNthFrame = 2
    /// OCR is the expensive tier (~69 ms at half-res). Cap how many frames ever
    /// reach it; the sample set resolved reliably on 4–5.
    public var maxOCRFrames = 24
    /// Minimum gap between OCR passes, so a fast shutter cannot flood the queue.
    public var minOCRInterval: Double = 0.15
    /// Frames below this fraction of the best sharpness seen are skipped.
    public var sharpnessRatio: Double = 0.45
    /// Stop as soon as the result is certain (barcode) — no reason to keep going.
    public var earlyExitOnBarcode = true
    /// Stop once text evidence is decisive and enough frames have contributed.
    public var earlyExitMinFrames = 4

    // ---- Patience -------------------------------------------------------
    //
    // Every gate below exists because the engine was reaching a verdict before
    // the user had finished showing it the vial: a barcode decoded on the first
    // frame ended the scan with the label never read at all, and a catalog
    // number glimpsed once in four frames was enough to name a vial. The answer
    // was often right, which is worse rather than better — it means the failures
    // are the ones nobody is looking for.
    //
    // These are not accuracy gates. They are gates on *when an answer is
    // allowed to count*, and their job is to make the engine wait until the
    // user has actually presented the vial rather than until the first plausible
    // reading turns up.

    /// No verdict settles before this much stream time, whatever the evidence.
    /// One second is roughly the shortest interval in which a person can turn a
    /// vial far enough to show a second face of the label.
    public var minScanDuration: Double = 1.0
    /// A barcode payload must decode in this many separate frames before it may
    /// end the scan.
    public var barcodeConfirmations = 2
    /// Distinct faces of the label required before a text verdict may settle.
    /// This is what "they actually rotated it" means to the engine.
    public var minDistinctViews = 2
    /// ...or, failing rotation, this many consecutive text-bearing frames that
    /// add nothing new. The user is holding it still and the visible face has
    /// given up everything it has: more waiting will not buy more evidence.
    public var saturationFrames = 4
    /// The same answer must come back this many resolves running. A verdict that
    /// flickers between two definitions across consecutive frames is not an
    /// answer, and this is what stops the scan from ending on whichever of them
    /// happened to be on top when the frame budget allowed a resolve.
    public var settleConfirmations = 2
    /// Give up after this much elapsed stream time. Without it, failure is slower
    /// than success — a hit resolves in a median of 4 OCR passes, while a miss
    /// would spend the entire 24-pass budget before admitting defeat.
    public var maxScanDuration: Double = 4.0

    // ---- Grace ----------------------------------------------------------
    //
    // A deadline is a promise to stop guessing. It is not a promise to produce
    // an answer, and the two get confused when the scan reaches the cap having
    // read nothing at all: the matcher returns `.notFound`, the card announces
    // "new vial — nothing on file matches this label" about a vial nobody ever
    // read, and in hands-free that gets parked, with a clip, into a queue where
    // a human has to work out that there was never anything to review.
    //
    // Nothing was wrong with the vial. The camera just had not seen it yet. So
    // when the deadline arrives without enough to answer with, give the time
    // back instead of spending it on a verdict.

    /// Extra stream time granted when the deadline arrives on thin evidence.
    public var graceExtension: Double = 2.5
    /// How many times the deadline may be pushed back.
    public var maxExtensions = 2
    /// Absolute ceiling. Past this the scan ends whatever it has, because a
    /// scanner that never stops is worse than one that admits it failed.
    public var hardScanCeiling: Double = 12.0
    /// OCR passes added to the budget with each extension. Without this the
    /// extra seconds would be spent on frames the budget no longer allows.
    public var ocrFramesPerExtension = 12
    public var match = MatchConfig()
    public init() {}
}

public enum ScanState: Sendable {
    /// `progress` is readiness to answer, not frames burned: it blends elapsed
    /// time, OCR passes and rotation coverage, so the bar on screen fills as the
    /// scan actually approaches a verdict rather than at a fixed rate.
    case scanning(framesSeen: Int, ocrFrames: Int, views: Int, progress: Double, hint: String)
    case finished(ScanResult)
}

/// Drives the two-tier pipeline over a stream of frames.
///
/// Designed to be fed live: `ingest` is called for every camera frame and returns
/// immediately, so the work happens *during* the user's rotation. By the time they
/// stop turning the vial, the answer already exists.
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
    private var bestSharpness = 0.0
    private var lastOCRTime = -Double.infinity
    private var settled: ScanResult?
    private var firstTimestamp: Double?
    /// The candidate awaiting confirmation, and how many resolves have produced it.
    private var pendingIdentity: String?
    private var pendingCount = 0
    /// When the scan currently intends to stop, and how often that has moved.
    private var deadline = 0.0
    private var ocrBudget = 0
    private var extensionsGranted = 0

    private let textRequest: VNRecognizeTextRequest
    private let barcodeRequest: VNDetectBarcodesRequest

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

        // A fresh engine must be in the same state `reset()` leaves one in; the
        // offline harness builds one per video and ingests without resetting.
        self.deadline = config.maxScanDuration
        self.ocrBudget = config.maxOCRFrames
    }

    public func reset() {
        evidence = EvidenceBuffer()
        framesSeen = 0; ocrFrames = 0
        bestSharpness = 0; lastOCRTime = -.infinity
        settled = nil; firstTimestamp = nil
        pendingIdentity = nil; pendingCount = 0
        deadline = config.maxScanDuration
        ocrBudget = config.maxOCRFrames
        extensionsGranted = 0
    }

    @discardableResult
    public func ingest(_ frame: VialFrame) -> ScanState {
        if let s = settled { return .finished(s) }
        framesSeen += 1
        if firstTimestamp == nil { firstTimestamp = frame.timestamp }
        let elapsed = frame.timestamp - (firstTimestamp ?? frame.timestamp)

        // ---- Tier 1: barcode, full resolution, sampled ----------------------
        if config.barcodeEveryNthFrame > 0,
           (framesSeen - 1) % config.barcodeEveryNthFrame == 0 {
            let handler = frame.fullResHandler()
            try? handler.perform([barcodeRequest])
            for obs in barcodeRequest.results ?? [] {
                guard let payload = obs.payloadStringValue, !payload.isEmpty else { continue }
                evidence.addBarcode(BarcodePayload(raw: payload, symbology: obs.symbology.rawValue))
            }
            // A decoded barcode is the strongest evidence this engine can get,
            // but a single decode is a single glimpse — and a DataMatrix on a
            // tube in the rack behind the one being held decodes just as
            // cleanly. Wait for the same payload twice, and never cut the scan
            // short before the user has had their second to present the vial.
            if config.earlyExitOnBarcode, elapsed >= config.minScanDuration,
               evidence.hasConfirmedBarcode(minFrames: config.barcodeConfirmations) {
                let r = matcher.resolve(evidence, standard: .corroborated)
                switch r.verdict {
                case .certain:
                    settled = r
                    return .finished(r)
                case .notFound where coverageSatisfied:
                    // The code named a product that is not on file. That verdict
                    // is definitive, but the label still has to be read: those
                    // fields are what pre-fills the receive form, and stopping
                    // on the decode alone hands the user an empty card for a
                    // vial they now have to type in by hand.
                    settled = r
                    return .finished(r)
                default:
                    break
                }
            }
        }

        // ---- Tier 2: OCR, half-res gray, sharpest frames only ----------------
        //
        // Pacing and budget are checked *before* sharpness, because measuring
        // sharpness costs ~4 ms and there is no reason to pay it on a frame that
        // could not be OCR'd anyway.
        let paced = frame.timestamp - lastOCRTime >= config.minOCRInterval
        let budget = ocrFrames < ocrBudget
        var sharpEnough = true
        if paced, budget {
            let sharp = frame.sharpness
            // Decay the running best. Scans now last long enough that one
            // exceptionally crisp frame early on would otherwise raise the bar
            // for the rest of the rotation and starve the OCR tier of frames.
            bestSharpness = max(sharp, bestSharpness * 0.98)
            sharpEnough = bestSharpness == 0 || sharp >= bestSharpness * config.sharpnessRatio
        }

        if sharpEnough, paced, budget, let handler = frame.halfResHandler() {
            lastOCRTime = frame.timestamp
            ocrFrames += 1
            try? handler.perform([textRequest])
            let obs = (textRequest.results ?? []).compactMap { o -> TextObservation? in
                guard let c = o.topCandidates(1).first, c.confidence > 0.3 else { return nil }
                return TextObservation(text: c.string, confidence: c.confidence)
            }
            evidence.addFrame(obs)

            if ocrFrames >= config.earlyExitMinFrames,
               elapsed >= config.minScanDuration,
               coverageSatisfied {
                let r = matcher.resolve(evidence, standard: .corroborated)
                if r.verdict == .certain || r.verdict == .confident, confirmed(r) {
                    settled = r
                    return .finished(r)
                }
            }
        }

        if elapsed >= deadline {
            let r = matcher.resolve(evidence)
            // Only stop on the deadline if there is something to stop *with*.
            if r.evidenceLevel == .sufficient
                || extensionsGranted >= config.maxExtensions
                || elapsed + config.graceExtension > config.hardScanCeiling {
                settled = r
                return .finished(r)
            }
            extensionsGranted += 1
            deadline = elapsed + config.graceExtension
            ocrBudget += config.ocrFramesPerExtension
        }

        return .scanning(framesSeen: framesSeen, ocrFrames: ocrFrames,
                         views: evidence.distinctViews,
                         progress: progress(elapsed: elapsed),
                         hint: hint(paced: paced, budget: budget, sharpEnough: sharpEnough))
    }

    /// Has the user shown the engine more than one face of the label — or run
    /// the visible face dry?
    private var coverageSatisfied: Bool {
        evidence.distinctViews >= config.minDistinctViews
            || evidence.framesSinceNewToken >= config.saturationFrames
    }

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

    /// Readiness to answer, averaged over the three things actually being
    /// waited on. Deliberately not a frame counter: the old bar filled at a
    /// fixed rate and told the user nothing about whether rotating more would
    /// help.
    private func progress(elapsed: Double) -> Double {
        let time = min(1, elapsed / max(0.01, config.minScanDuration))
        let passes = min(1, Double(ocrFrames) / Double(max(1, config.earlyExitMinFrames)))
        let coverage = coverageSatisfied
            ? 1.0
            : min(1, Double(evidence.distinctViews) / Double(max(1, config.minDistinctViews)))
        // Without this term the bar sits full through every granted extension,
        // which is the bar telling the user the scan is done while it waits for
        // the one thing it still does not have.
        let read: Double
        switch currentLevel {
        case .none:       read = 0
        case .thin:       read = 0.5
        case .sufficient: read = 1
        }
        return (time + passes + coverage + read) / 4
    }

    /// Evidence level as it stands, without running a full resolve.
    private var currentLevel: EvidenceLevel {
        var fields = [LabelField: String]()
        for f in LabelField.allCases {
            if let v = evidence.vote(f, minFrames: 2) { fields[f] = v.value }
        }
        return Matcher.evidenceLevel(evidence, fields: fields)
    }

    private func hint(paced: Bool, budget: Bool, sharpEnough: Bool) -> String {
        if paced, budget, !sharpEnough { return "Hold steady" }
        if evidence.frameCount == 0 { return "Line the label up" }
        // Past the first deadline the generic hints stop being useful — the
        // scan is in extra time precisely because something specific is
        // missing, so say which.
        if extensionsGranted > 0 {
            return evidence.allTokens.isEmpty
                ? "Nothing readable yet — move closer, slower"
                : "Still need the catalog number — keep turning"
        }
        if !coverageSatisfied { return "Keep rotating" }
        if evidence.vote(.catalog, minFrames: 2) == nil { return "Looking for the catalog number" }
        return "Reading label…"
    }

    /// Force a verdict from whatever evidence exists (user stopped rotating).
    public func finish() -> ScanResult {
        if let s = settled { return s }
        let r = matcher.resolve(evidence)
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
