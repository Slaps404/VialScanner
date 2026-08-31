import Foundation

public struct DefinitionMatch: Sendable {
    public let definition: AntibodyDefinition
    public let score: Double
    public let reasons: [String]
}

public enum Verdict: String, Sendable, Codable {
    /// Barcode decoded and resolved against the catalog. Effectively certain.
    case certain
    /// Text evidence cleared both the absolute and margin thresholds.
    case confident
    /// Best candidate exists but did not clear thresholds — show, do not commit.
    case ambiguous
    /// Nothing plausible. This vial is new to the database.
    case notFound
}

/// How much the scan actually managed to read, independent of what it concluded.
///
/// A verdict and the evidence behind it are different questions, and `.notFound`
/// conflates them: it is the answer both for "this label is legible and names a
/// product we do not stock" and for "the camera never read anything". Those two
/// deserve opposite handling — the first is a new vial to receive, the second is
/// a scan to retry — so the engine reports them apart.
public enum EvidenceLevel: String, Sendable, Codable {
    /// No barcode, and no text recognised in any frame. There is nothing here to
    /// reach a verdict *about*.
    case none
    /// Text was read, but nothing that identifies a product.
    case thin
    /// A barcode, a catalog number, or a clone backed by a second field.
    case sufficient
}

public struct ScanResult: Sendable {
    public let verdict: Verdict
    public let definition: AntibodyDefinition?
    public let vial: InventoryItem?
    /// Other vials of the same definition, when the lot could not disambiguate.
    public let otherVials: [InventoryItem]
    public let score: Double
    public let margin: Double
    public let reasons: [String]
    /// A lot number was read confidently but matches none of the vials on record
    /// for this antibody: a new lot of a known product, not one of these vials.
    public let lotNotOnRecord: Bool
    public let fields: [LabelField: String]
    public let barcode: BarcodePayload?
    public let framesUsed: Int
    /// What the camera actually got, as distinct from what the matcher decided.
    public let evidenceLevel: EvidenceLevel

    public var isHit: Bool { verdict == .certain || verdict == .confident }
    /// Nothing was read at all. Not a result about a vial — a failed scan.
    public var readNothing: Bool { evidenceLevel == .none }
}

/// How much corroboration the evidence must carry before a verdict counts.
///
/// The two callers want different things. A verdict that ends the scan on its
/// own must be corroborated: it is being reached while the user is still
/// rotating the vial, and if it is wrong nobody gets the chance to notice. A
/// verdict the user asked for — "read what you have", or the duration cap —
/// should use everything available, because the alternative is not a better
/// answer later, it is no answer at all.
public enum EvidenceStandard: Sendable {
    /// Identity-bearing fields must be backed by more than one frame, and a
    /// barcode by more than one decode.
    case corroborated
    /// Whatever the buffer holds.
    case best

    /// Frames a field must appear in to be usable at this standard.
    func minFrames(for field: LabelField) -> Int {
        guard case .corroborated = self else { return 1 }
        switch field {
        case .catalog, .lot, .clone: return 2
        default: return 1
        }
    }

    var minBarcodeFrames: Int {
        if case .corroborated = self { return 2 }
        return 1
    }
}

public struct MatchConfig: Sendable {
    /// Absolute score a text match must clear. Non-matches in the sample set
    /// topped out at 4.8; real matches bottomed out at 8.0.
    public var minScore: Double = 7.0
    /// The runner-up gap. A high score against a crowded field of near-identical
    /// catalog numbers is not a match — it is a coin flip.
    public var minMargin: Double = 2.5
    public var fieldWeights: [LabelField: Double] = [
        .catalog: 6.0, .clone: 4.0, .vendor: 3.0, .isotype: 1.5,
    ]
    public init() {}
}

public struct Matcher: Sendable {
    public let catalog: Catalog
    public let config: MatchConfig

    public init(catalog: Catalog, config: MatchConfig = MatchConfig()) {
        self.catalog = catalog
        self.config = config
    }

    public func resolve(_ ev: EvidenceBuffer, standard: EvidenceStandard = .best) -> ScanResult {
        var fields = [LabelField: String]()
        for f in LabelField.allCases {
            if let v = ev.vote(f, minFrames: standard.minFrames(for: f)) { fields[f] = v.value }
        }
        // ---- Tier 1: barcode ------------------------------------------------
        for bc in ev.barcodes {
            // One decode is one glimpse, and a DataMatrix in the tray behind the
            // vial being held decodes exactly as cleanly as the one on it.
            guard ev.frames(for: bc) >= standard.minBarcodeFrames else { continue }
            guard let cat = bc.catalog else { continue }
            // A decoded payload is stronger than OCR.  Promote it into the
            // common field map so receive/review screens do not show a blank or
            // contradictory catalog/lot while the reason line uses the barcode.
            fields[.catalog] = cat
            if let lot = bc.lot, !lot.isEmpty { fields[.lot] = lot }
            let level = Self.evidenceLevel(ev, fields: fields)
            let hits = catalog.definitionIndices(forCatalog: cat)
            if let idx = hits.first {
                let def = catalog.definitions[idx]
                let (vial, others, lotMiss) = pickVial(def: def, lot: bc.lot ?? fields[.lot])
                return ScanResult(
                    verdict: .certain, definition: def, vial: vial, otherVials: others,
                    score: 100, margin: 100,
                    reasons: ["barcode \(bc.symbology): catalog \(cat)"
                              + (bc.lot.map { ", lot \($0)" } ?? "")], lotNotOnRecord: lotMiss,
                    fields: fields, barcode: bc, framesUsed: ev.frameCount, evidenceLevel: level)
            }
            // Decoded cleanly but absent from the catalog: a definitive "new vial",
            // not an ambiguous read. Do not fall through to fuzzy text matching.
            return ScanResult(
                verdict: .notFound, definition: nil, vial: nil, otherVials: [],
                score: 0, margin: 0,
                reasons: ["barcode \(bc.symbology) decoded catalog \(cat) — not in catalog"], lotNotOnRecord: false,
                fields: fields, barcode: bc, framesUsed: ev.frameCount, evidenceLevel: level)
        }

        let level = Self.evidenceLevel(ev, fields: fields)

        // ---- Tier 2: exact catalog number from voted text -------------------
        if let cat = fields[.catalog] {
            let hits = catalog.definitionIndices(forCatalog: cat)
            if hits.count == 1 {
                let def = catalog.definitions[hits[0]]
                let (vial, others, lotMiss) = pickVial(def: def, lot: fields[.lot])
                return ScanResult(
                    verdict: .confident, definition: def, vial: vial, otherVials: others,
                    score: 50, margin: 50,
                    reasons: ["catalog # \(cat) matched exactly"], lotNotOnRecord: lotMiss,
                    fields: fields, barcode: nil, framesUsed: ev.frameCount, evidenceLevel: level)
            }
        }

        // ---- Tier 2b: unique near-exact catalog number ----------------------
        //
        // Guarded by corroboration. Real catalog spaces are dense: BioLegend
        // 406601 and 406602 are different products, and this catalog alone holds
        // 13 pairs of numbers one edit apart. A near-match on digits therefore
        // proves nothing on its own, so it must be backed by the clone or target
        // — vendor is deliberately excluded, since every vial from a vendor
        // shares it. A false positive here puts the wrong antibody in a panel;
        // a false negative merely costs a manual lookup.
        if let cat = fields[.catalog], let idx = catalog.definitionIndex(nearCatalog: cat),
           corroborates(catalog.definitions[idx], ev) {
            let def = catalog.definitions[idx]

            // Where the edit falls decides what it means. Vendors encode pack size
            // in the trailing character — BioLegend 406601 is the 50 µg vial of the
            // same clone that 406602 sells in bulk — so a difference there is a
            // different product, not a misread. A difference anywhere else is OCR
            // noise (`1103-01` for `0103-01`). Only the latter may name a vial.
            if differsOnlyInLastCharacter(normKey(cat), normKey(def.catalog)) {
                return ScanResult(
                    verdict: .ambiguous, definition: def, vial: nil,
                    otherVials: catalog.inventory(for: def), score: 20, margin: 0,
                    reasons: ["read catalog # \(cat), closest on file is \(def.catalog) — "
                              + "same clone, but the trailing digit usually encodes pack size"],
                    lotNotOnRecord: false,
                    fields: fields, barcode: nil, framesUsed: ev.frameCount, evidenceLevel: level)
            }

            let (vial, others, lotMiss) = pickVial(def: def, lot: fields[.lot])
            return ScanResult(
                verdict: .confident, definition: def, vial: vial, otherVials: others,
                score: 40, margin: 40,
                reasons: ["catalog # \(cat) -> \(def.catalog) (unique single-edit match)"], lotNotOnRecord: lotMiss,
                fields: fields, barcode: nil, framesUsed: ev.frameCount, evidenceLevel: level)
        }

        // ---- Tier 3: weighted fuzzy scoring over the stable token bag -------
        let stable = ev.stableTokens(minFrames: 2)
        let tokens = stable.count >= 8 ? stable : ev.allTokens
        let folded = Set(tokens.map(ocrFold))

        var ranked: [DefinitionMatch] = []
        for def in catalog.definitions {
            var score = 0.0
            var reasons: [String] = []
            func consider(_ field: LabelField, _ value: String) {
                guard let w = config.fieldWeights[field] else { return }
                let k = normKey(value)
                guard k.count >= 2 else { return }
                // Field extraction canonicalises as it reads: a label printed
                // `Cell Signaling Technology` yields vendor `CST`, the spelling
                // the catalog uses. That resolution has to be honoured here or
                // it is thrown away, because the token bag below holds the words
                // the label actually printed. A vendor that never prints its own
                // abbreviation would otherwise score zero on every definition it
                // sells, which is the whole reason the alias table exists.
                if let read = fields[field], normKey(read) == k {
                    score += w; reasons.append("\(field)=\(value)"); return
                }
                if tokens.contains(k) { score += w; reasons.append("\(field)=\(value)") }
                else if folded.contains(ocrFold(value)) { score += w * 0.85; reasons.append("\(field)≈\(value)") }
                else if let near = bestFuzzy(k, tokens), near.1 >= 0.82 {
                    score += w * 0.65; reasons.append("\(field)~\(near.0)")
                }
            }
            consider(.catalog, def.catalog)
            consider(.clone, def.clone)
            consider(.vendor, def.vendor)
            consider(.isotype, def.isotype)
            if score > 0 { ranked.append(.init(definition: def, score: score, reasons: reasons)) }
        }
        ranked.sort { $0.score > $1.score }

        guard let top = ranked.first else {
            return ScanResult(verdict: .notFound, definition: nil, vial: nil, otherVials: [],
                              score: 0, margin: 0, reasons: ["no catalog evidence"], lotNotOnRecord: false,
                              fields: fields, barcode: nil, framesUsed: ev.frameCount, evidenceLevel: level)
        }
        let runnerUp = ranked.dropFirst().first?.score ?? 0
        let margin = top.score - runnerUp
        let verdict: Verdict = (top.score >= config.minScore && margin >= config.minMargin)
            ? .confident
            : (top.score >= config.minScore * 0.6 ? .ambiguous : .notFound)

        let (vial, others, lotMiss) = verdict == .confident
            ? pickVial(def: top.definition, lot: fields[.lot])
            : (nil, [], false)

        return ScanResult(
            verdict: verdict,
            definition: verdict == .notFound ? nil : top.definition,
            vial: vial, otherVials: others,
            score: top.score, margin: margin, reasons: top.reasons, lotNotOnRecord: lotMiss,
            fields: fields, barcode: nil, framesUsed: ev.frameCount, evidenceLevel: level)
    }

    /// What the scan actually recovered.
    ///
    /// "Enough to answer with" means enough to *retrieve* on: a barcode, a
    /// catalog number, or a clone corroborated by a second field. A frame full
    /// of `For Research Use Only` and `Store at 4°C` is text, but it is label
    /// furniture — it identifies no product and must not be mistaken for
    /// evidence that the scan worked.
    static func evidenceLevel(_ ev: EvidenceBuffer, fields: [LabelField: String]) -> EvidenceLevel {
        if !ev.barcodes.isEmpty { return .sufficient }
        if ev.allTokens.isEmpty { return .none }
        if fields[.catalog] != nil { return .sufficient }
        if fields[.clone] != nil, fields[.vendor] != nil || fields[.isotype] != nil { return .sufficient }
        if fields[.lot] != nil, fields[.clone] != nil { return .sufficient }
        return .thin
    }

    /// Independent support for a candidate from product-identifying fields.
    private func corroborates(_ def: AntibodyDefinition, _ ev: EvidenceBuffer) -> Bool {
        let tokens = ev.allTokens
        let folded = Set(tokens.map(ocrFold))
        for value in [def.clone, def.target] {
            let k = normKey(value)
            guard k.count >= 3 else { continue }
            if tokens.contains(k) || folded.contains(ocrFold(k)) { return true }
            // OCR truncates at the label curve, so accept a token that is a
            // prefix of the real value (`RMG` for clone `RMG1-1`).
            if tokens.contains(where: { $0.count >= 3 && k.hasPrefix($0) }) { return true }
            if let near = bestFuzzy(k, tokens), near.1 >= 0.85 { return true }
        }
        return false
    }

    /// Choose the vial. The definition link is authoritative; lot only breaks
    /// ties *within* that set. A bad lot read can never suppress a real vial.
    private func pickVial(def: AntibodyDefinition, lot: String?) -> (InventoryItem?, [InventoryItem], Bool) {
        let candidates = catalog.inventory(for: def)
        guard !candidates.isEmpty else { return (nil, [], false) }

        if let lot, !lot.isEmpty {
            let want = ocrFold(lot)
            if let exact = candidates.first(where: { ocrFold($0.lot) == want }) {
                return (exact, candidates.filter { $0.abNo != exact.abNo }, false)
            }
            if let near = candidates
                .map({ ($0, similarity(ocrFold($0.lot), want)) })
                .filter({ $0.1 >= 0.8 })
                .max(by: { $0.1 < $1.1 })?.0 {
                return (near, candidates.filter { $0.abNo != near.abNo }, false)
            }
            // The lot read cleanly and matches nothing on record. Reporting one of
            // the existing vials here would be a lie: this is a new lot of a known
            // antibody, and naming a vial invites someone to draw from the wrong one.
            return (nil, candidates, true)
        }

        if candidates.count == 1 { return (candidates[0], [], false) }
        return (candidates[0], Array(candidates.dropFirst()), false)
    }
}

// MARK: - String similarity

func similarity(_ a: String, _ b: String) -> Double {
    if a == b { return 1 }
    if a.isEmpty || b.isEmpty { return 0 }
    return 1.0 - Double(editDistance(a, b)) / Double(max(a.count, b.count))
}

func editDistance(_ a: String, _ b: String) -> Int {
    if a == b { return 0 }
    if a.isEmpty { return b.count }
    if b.isEmpty { return a.count }
    let x = Array(a), y = Array(b)
    var prev = Array(0...y.count)
    var cur = [Int](repeating: 0, count: y.count + 1)
    for i in 1...x.count {
        cur[0] = i
        for j in 1...y.count {
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x[i-1] == y[j-1] ? 0 : 1))
        }
        swap(&prev, &cur)
    }
    return prev[y.count]
}

/// True when two strings are identical except for their final character, or when
/// one is the other plus a trailing character.
func differsOnlyInLastCharacter(_ a: String, _ b: String) -> Bool {
    if a.count == b.count {
        guard a.count >= 2 else { return false }
        return a.dropLast() == b.dropLast() && a.last != b.last
    }
    if abs(a.count - b.count) == 1 {
        let (longer, shorter) = a.count > b.count ? (a, b) : (b, a)
        return longer.dropLast() == shorter
    }
    return false
}

func bestFuzzy(_ needle: String, _ hay: Set<String>) -> (String, Double)? {
    var best: (String, Double)?
    for t in hay {
        // Length gate keeps this cheap: edit distance cannot beat the threshold
        // if the lengths are already too far apart.
        guard abs(t.count - needle.count) <= 3 else { continue }
        let s = similarity(needle, t)
        if s > (best?.1 ?? 0) { best = (t, s) }
    }
    return best
}
