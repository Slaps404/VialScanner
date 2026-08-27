import Foundation
import CoreGraphics

/// One OCR line from one frame.
public struct TextObservation: Sendable {
    public let text: String
    public let confidence: Float
    public let boundingBox: CGRect?

    public init(text: String, confidence: Float, boundingBox: CGRect? = nil) {
        self.text = text
        self.confidence = confidence
        self.boundingBox = boundingBox
    }
}

/// Accumulates observations across frames.
///
/// The single most important behaviour here is **cross-frame voting**: a field
/// value is only trusted once several independent frames agree on it. Reading
/// one regex hit from one frame is what produced `04IS` for a lot number that
/// is really `F0412-S122Z`.
public struct EvidenceBuffer: Sendable {
    public private(set) var frameCount = 0
    /// token -> accumulated weight (sum of per-frame confidence)
    public private(set) var tokenWeight: [String: Double] = [:]
    /// token -> number of distinct frames it appeared in
    public private(set) var tokenFrames: [String: Int] = [:]
    /// normalized token -> best-looking raw spelling seen
    public private(set) var rawForms: [String: String] = [:]
    /// field candidates, per frame, awaiting a vote
    public private(set) var fieldVotes: [LabelField: [String: Double]] = [:]
    public private(set) var barcodes: [BarcodePayload] = []
    /// raw payload -> how many separate frames decoded it
    public private(set) var barcodeFrames: [String: Int] = [:]
    public private(set) var allText: [String] = []

    // MARK: - Rotation coverage
    //
    // The engine needs to know whether the vial was actually *turned*, as
    // opposed to held still while the same face was read over and over. Frames
    // are cheap to re-read and expensive to trust: ten frames of one face are
    // one observation repeated, not ten observations, and settling on them
    // means answering before the rest of the label has been shown at all.
    //
    // The signal is the token set. Two frames of the same face share most of
    // their tokens; two frames of different faces barely overlap. Counting the
    // token sets that do *not* resemble anything seen before counts faces.

    /// One representative token set per face of the label seen so far.
    private var views: [Set<String>] = []
    /// How many distinct faces of the label have been read.
    public var distinctViews: Int { views.count }
    /// Consecutive text-bearing frames that produced no token not already seen.
    /// Once this climbs, the face in view is exhausted: either the vial has
    /// stopped moving or there is nothing more on this side to read.
    public private(set) var framesSinceNewToken = 0

    /// Two token sets count as the same face above this overlap.
    private static let sameViewOverlap = 0.5
    /// Cap on stored views. A scan is a few seconds; this is only here so a
    /// pathological stream cannot grow the comparison list without bound.
    private static let maxViews = 8

    public init() {}

    public mutating func addBarcode(_ b: BarcodePayload) {
        if !barcodes.contains(where: { $0.raw == b.raw }) { barcodes.append(b) }
        barcodeFrames[b.raw, default: 0] += 1
    }

    /// How many frames decoded this exact payload.
    public func frames(for b: BarcodePayload) -> Int { barcodeFrames[b.raw] ?? 0 }

    /// True once some payload has decoded the same way in `minFrames` frames.
    public func hasConfirmedBarcode(minFrames: Int) -> Bool {
        barcodeFrames.values.contains { $0 >= minFrames }
    }

    public mutating func addFrame(_ observations: [TextObservation]) {
        frameCount += 1
        var seenThisFrame = Set<String>()
        var novelTokens = 0
        for o in observations {
            allText.append(o.text)
            for tok in tokenize(o.text) {
                let k = normKey(tok)
                guard k.count >= 2 else { continue }
                tokenWeight[k, default: 0] += Double(o.confidence)
                if seenThisFrame.insert(k).inserted {
                    if tokenFrames[k] == nil { novelTokens += 1 }
                    tokenFrames[k, default: 0] += 1
                }
                // Prefer the longest raw spelling: OCR truncates at frame edges,
                // so the longest variant is usually the most complete one.
                if let existing = rawForms[k], existing.count >= tok.count {} else { rawForms[k] = tok }
            }
        }
        // A frame that read nothing says nothing about coverage — it is a blur
        // or a blank stretch of glass, not evidence that the label is exhausted.
        if !seenThisFrame.isEmpty {
            framesSinceNewToken = novelTokens > 0 ? 0 : framesSinceNewToken + 1
            if seenThisFrame.count >= 2, views.count < Self.maxViews,
               !views.contains(where: { jaccard($0, seenThisFrame) >= Self.sameViewOverlap }) {
                views.append(seenThisFrame)
            }
        }

        // Field extraction runs per frame; each frame casts one vote per field.
        let fields = extractFields(from: observations.map(\.text))
        for (field, value) in fields {
            fieldVotes[field, default: [:]][value, default: 0] += 1
        }
    }

    /// The winning value for a field, plus how many frames backed it.
    ///
    /// `minFrames` is the corroboration bar. A value that only ever appeared in
    /// a single frame is one glimpse of one face through one focus state, and
    /// the identity-bearing fields must not be decided on that: it is how a lot
    /// misread as `F6122Z` gets to announce that a vial on the shelf is a lot
    /// nobody has on record.
    public func vote(_ field: LabelField, minFrames: Int = 1) -> (value: String, frames: Int)? {
        guard let votes = fieldVotes[field], !votes.isEmpty else { return nil }
        // Merge variants that differ only by OCR confusables, keeping the longest
        // spelling as the representative — this is what repairs `0412-S122Z` ->
        // `F0412-S122Z` when different frames catch different ends of the string.
        var merged: [String: (repr: String, score: Double)] = [:]
        for (value, count) in votes {
            let key = ocrFold(value)
            if var m = merged[key] {
                if value.count > m.repr.count { m.repr = value }
                m.score += count
                merged[key] = m
            } else {
                merged[key] = (value, count)
            }
        }
        // OCR truncates at the edge of the visible label curve, so the same value
        // often appears as `S10` in one frame and `S107` in the next. Fold a
        // strict prefix into its longer form instead of letting them compete.
        let keys = merged.keys.sorted { $0.count < $1.count }
        for short in keys {
            guard short.count >= 3, let s = merged[short] else { continue }
            let extensions = keys.filter { $0.count > short.count && $0.hasPrefix(short) }
            guard extensions.count == 1, let longKey = extensions.first,
                  var l = merged[longKey] else { continue }
            l.score += s.score
            merged[longKey] = l
            merged.removeValue(forKey: short)
        }

        guard let best = merged.values.max(by: {
            $0.score == $1.score ? $0.repr.count < $1.repr.count : $0.score < $1.score
        }) else { return nil }
        let frames = Int(best.score.rounded())
        guard frames >= minFrames else { return nil }
        return (best.repr, frames)
    }

    /// Tokens that survived at least `minFrames` frames — the denoised bag.
    public func stableTokens(minFrames: Int = 2) -> Set<String> {
        Set(tokenFrames.filter { $0.value >= minFrames }.keys)
    }

    public var allTokens: Set<String> { Set(tokenWeight.keys) }
}

/// Overlap between two token sets, 0 (nothing in common) to 1 (identical).
func jaccard(_ a: Set<String>, _ b: Set<String>) -> Double {
    if a.isEmpty || b.isEmpty { return 0 }
    let inter = a.intersection(b).count
    return Double(inter) / Double(a.count + b.count - inter)
}

@usableFromInline
func tokenize(_ s: String) -> [String] {
    s.split(whereSeparator: { !($0.isLetter || $0.isNumber || $0 == "-" || $0 == ".") })
        .map(String.init)
        .filter { $0.count >= 2 }
}
