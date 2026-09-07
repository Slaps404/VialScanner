import Foundation
import CoreGraphics

public enum LabelField: String, Sendable, CaseIterable, Codable {
    case catalog, lot, clone, vendor, concentration, volume, isotype, expiry, storage
}

public struct BarcodePayload: Sendable, Equatable {
    public let raw: String
    public let symbology: String
    /// Parsed catalog number, when the payload format is understood.
    public let catalog: String?
    public let lot: String?

    public init(raw: String, symbology: String) {
        self.raw = raw
        self.symbology = symbology
        let parsed = Self.parse(raw)
        self.catalog = parsed.catalog
        self.lot = parsed.lot
    }

    /// Handles the two formats present in the sample set, plus generic GS1.
    ///
    /// * BioLegend: `"102116   B305480"` — catalog, whitespace run, lot.
    /// * Thermo/Pierce GS1: `"91" <cat> GS "10" <lot> GS "301"` where 0x1D is
    ///   the GS1 group separator. AI 10 is the batch/lot identifier.
    static func parse(_ raw: String) -> (catalog: String?, lot: String?) {
        let gs = "\u{1D}"
        if raw.contains(gs) {
            var catalog: String?, lot: String?
            for seg in raw.components(separatedBy: gs) {
                let s = seg.trimmingCharacters(in: .whitespaces)
                if s.hasPrefix("10"), s.count > 2 { lot = String(s.dropFirst(2)) }
                else if s.hasPrefix("91"), s.count > 2 { catalog = String(s.dropFirst(2)) }
                else if s.hasPrefix("01"), s.count > 2 { catalog = String(s.dropFirst(2)) }
            }
            if catalog != nil || lot != nil { return (catalog, lot) }
        }
        // Whitespace-separated two-field form.
        let parts = raw.split(whereSeparator: { $0 == " " || $0 == "\t" })
            .map(String.init).filter { !$0.isEmpty }
        if parts.count == 2 { return (parts[0], parts[1]) }
        if parts.count == 1 { return (parts[0], nil) }
        return (nil, nil)
    }
}

private struct Rule {
    let field: LabelField
    let pattern: NSRegularExpression
    let group: Int
    init(_ field: LabelField, _ p: String, group: Int = 1) {
        self.field = field
        self.pattern = try! NSRegularExpression(pattern: p, options: [.caseInsensitive])
        self.group = group
    }
}

/// Vendor label formats are highly stereotyped, which is what makes regex
/// viable here. Each rule is deliberately narrow; breadth comes from voting
/// across frames rather than from loose patterns.
private let rules: [Rule] = [
    // `Cat`, optionally followed by one stray letter: OCR turns `Cat#` into
    // `Cate` and `Cat.` into `Catt` often enough to be worth the one character.
    .init(.catalog, #"CAT(?:ALOG)?[A-Z]?\s*(?:NO|N0|#|:)?\s*[.:]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    .init(.catalog, #"\bCat\s*[*:#]\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    // `REF` is the ISO 15223 symbol for catalogue number and is what eBioscience,
    // Invitrogen and Affymetrix print instead of the word — four videos in the
    // sample set carry a perfectly legible `REF 14-9766-81` that nothing read.
    // The character class absorbs the OCR variants actually observed (`BEF`,
    // `REE`, `REI`); the captured value still has to survive `isPlausible` and
    // then match the catalog, so a loose keyword costs precision nothing.
    .init(.catalog, #"\b[RB]E[FEI]\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    // Leinco and Thermo print `Prod. No.` / `Prod #`.
    .init(.catalog, #"\bP[RH]OD\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*([A-Z]\d{2,4})\b"#),
    .init(.catalog, #"\bP[RH]OD\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    // At the label seam Vision sometimes loses `Prod.` but preserves the full
    // value line as `No.: C2855`.  Restrict this fallback to Leinco's distinctive
    // letter + 3/4 digit product shape; it cannot consume numeric lot/date rows.
    .init(.catalog, #"\bNO\.?\s*:\s*([A-Z]\d{3,4})\b"#),
    // Peptide and reagent vendors print `Part`.
    .init(.catalog, #"\bPART\s*(?:NO|#)?\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    // Miltenyi prints the vendor product identifier as `Order no.`.
    .init(.catalog, #"\bORDER\s*(?:NO|N0|NUMBER)\.?\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    .init(.lot,     #"LOT\s*(?:NO|#|:)?\s*[.:]?\s*([A-Z0-9][A-Z0-9\-]{3,16})"#),
    .init(.clone,   #"CLONE\s*[:#]?\s*\(?\s*([A-Z0-9][A-Z0-9\.\-]{1,12})"#),
    .init(.clone,   #"\(\s*Clone\s+([A-Z0-9][A-Z0-9\.\-]{1,12})\s*\)"#),
    .init(.isotype, #"\b(Ig[GMAE](?:\d[a-c]?)?)\b"#),
    // Capture the unit, not just the number: `1` alone fails the plausibility
    // floor, and a concentration without its unit is not a concentration.
    .init(.concentration, #"(?:C|Conc)\s*[:.]?\s*([0-9]+(?:\.[0-9]+)?\s*(?:mg|ug|µg|μg)\s*/\s*m?[lL])"#),
    // Microlitre volumes print the unit as µL, uL or μL; the old rule
    // only allowed mL, so `Size: 100 µL` fell out entirely.
    .init(.volume,  #"(?:Vol|Size)\s*[:.]?\s*([0-9]+(?:\.[0-9]+)?\s*(?:m|u|µ|μ)?[lL])"#),
    // Cataloguing a vial the catalog has never seen needs the whole label, not
    // just the identifiers - see README "Two goals, opposite stopping rules".
    // Separators are `-`, `/` or `.`, never a bare space: across the sample
    // labels a space only ever appeared between a date and the token after it,
    // where it let `2022-08 10` read as a three-part date.
    // `Best before` / `Best by` / `Use by` are what several vendors print
    // instead of `Exp.`; the value still has to be date-shaped, so widening the
    // keyword costs precision nothing.
    .init(.expiry, #"\b(?:EXP(?:IR(?:Y|ES|ATION))?|BEST(?:\s*(?:BEFORE|BY))?|USE\s*(?:BY|BEFORE))\.?\s*(?:DATE)?\s*[:.]?\s*([0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}|[0-9]{1,2}[-/.][0-9]{1,2}[-/.][0-9]{2,4}|[0-9]{1,2}[-/.][0-9]{4}|[0-9]{4}[-/.][0-9]{1,2})"#),
    .init(.storage, #"STOR(?:E|AGE)\s*(?:AT)?\s*[:.]?\s*(-?[0-9]{1,3}\s*(?:(?:°?\s*C)?\s*(?:-|to)\s*-?\s*[0-9]{1,3}\s*°?\s*C|°?\s*C))"#),
]

/// Fields copied onto the record verbatim rather than folded to an upper-case
/// voting key: they are label facts, not identifiers.
private let descriptiveFields: Set<LabelField> = [.concentration, .volume, .expiry, .storage]

private let vendorAliases: [(String, String)] = [
    ("southernbiotech", "SouthernBiotech"), ("southernbio", "SouthernBiotech"),
    // The brand sits at the label seam in several clips.  Accept the stable
    // stems Vision returns there; the associated identifier shapes remain
    // narrow and are still required in the same frame.
    ("biolegend", "BioLegend"), ("bioleg", "BioLegend"),
    ("bdbiosciences", "BD"), ("pharmingen", "BD"),
    ("cellsignal", "CST"), ("abcam", "Abcam"), ("invitrogen", "Invitrogen"),
    ("thermofisher", "ThermoFisher"), ("thermo", "ThermoFisher"), ("pierce", "ThermoFisher"),
    ("jacksonimmuno", "Jackson"), ("proteintech", "ProteinTech"), ("rndsystems", "R&D"),
    ("biorad", "BioRad"), ("sigma", "Sigma"), ("bioxcell", "BioXCell"),
    ("leinco", "Leinco"), ("leince", "Leinco"), ("laince", "Leinco"),
    // eBioscience and Affymetrix are the brands printed on tubes the catalog
    // files list as Invitrogen. Without these, every eBioscience label reads as
    // vendorless — which costs the vendor-gated shape rules below their gate,
    // not just a field in the result.
    ("ebioscience", "Invitrogen"), ("affymetrix", "Invitrogen"),
    ("roche", "Roche"),
]

/// Words that regularly survive the field regexes but can never be a real
/// identifier. These come from label furniture ("Cat", "Size", "Purified") that
/// OCR splices onto the wrong side of a delimiter.
private let stopValues: Set<String> = [
    "cat", "catno", "lot", "lotno", "size", "side", "conc", "vol", "volume",
    // Furniture the new REF/Prod/Part rules can splice onto the wrong side of a
    // delimiter, the same way `Cat` and `Size` did for the original rules.
    "prod", "prodno", "part", "partno", "ref", "refno", "qty", "pkg", "pkgsize",
    "store", "storeat", "clone", "poly", "polyclonal", "polyclona", "pol",
    "purified", "purifled", "purif", "unlb", "research", "use", "only",
    "isotype", "sotype", "control", "mouse", "rabbit", "human", "rat",
    "sizei", "each", "azide", "endotoxin", "free", "sterile",
]


/// Minimum plausible length per field. A four-character "catalog number" is a
/// truncation artefact, not a part number, and letting it through invites a
/// fuzzy match against something unrelated.
private let minLength: [LabelField: Int] = [.catalog: 5, .lot: 4, .clone: 2]

/// The one catalog shape short enough to trip the length floor and still be a
/// real part number: Leinco prints `P362` and `C2855`, a letter and three or
/// four digits, with nothing else on the line to pad it out.
private let shortCatalogException = try! NSRegularExpression(pattern: #"^[A-Z][0-9]{3}$"#)
/// A curved Leinco row can expose `Prod. No.: C28` before the remaining two
/// digits rotate into view.  Keep that fragment at low weight so Evidence can
/// repair a later near-prefix (`C2355` -> `C2855`); it can never win alone.
private let partialCatalog = try! NSRegularExpression(pattern: #"^[A-Z][0-9]{2}$"#)
/// The same truncation on a bare-numeric vendor: BioLegend's `910801` reads as
/// `Cat: 9108` while the rest is round the curve. Four digits are too
/// promiscuous a fuzzy-match target to score, so this is carried at zero weight
/// purely so `EvidenceBuffer.vote` can graft its prefix onto a fuller read.
private let numericPartialCatalog = try! NSRegularExpression(pattern: #"^[0-9]{4}$"#)

private func matchesWhole(_ re: NSRegularExpression, _ s: String) -> Bool {
    let ns = s as NSString
    guard let m = re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: ns.length))
    else { return false }
    return m.range.length == ns.length
}

private func isPlausible(_ field: LabelField, _ value: String) -> Bool {
    let k = normKey(value)
    if field == .concentration {
        let prefix = value.prefix { $0.isNumber || $0 == "." }
        guard let amount = Double(prefix), amount > 0, amount <= 100 else { return false }
    }
    // Measure the value as printed, not as normalised. `normKey` strips the
    // hyphen, which turns Leinco's `I-118` into four characters and fails it on
    // a floor that exists to reject truncation artefacts — but the hyphen is
    // structure the vendor printed, not padding. A bare `6260` is still four.
    var floor = minLength[field] ?? 2
    if field == .catalog, matchesWhole(shortCatalogException, value.uppercased()) { floor = 4 }
    if field == .catalog, matchesWhole(partialCatalog, value.uppercased()) { floor = 3 }
    guard max(k.count, value.count) >= floor else { return false }
    guard !stopValues.contains(k) else { return false }
    // A value with no digits at all is almost never a catalog or lot number.
    if field == .catalog || field == .lot {
        if !k.contains(where: \.isNumber) { return false }
    }
    return true
}

// MARK: - Dangling keywords
//
// Vision returns *lines*, and a vendor's line breaks are not the engine's. On
// a curved tube the printed row `Cat. No.  107701` regularly comes back as two
// observations — `Cat. No.` and `107701` — because the curve puts a gap in the
// middle wide enough for the recogniser to split on. Joining every line in the
// frame into one string and running the keyword rules over it only works when
// the recogniser happens to emit them adjacently, which it does not reliably
// do: it orders by its own confidence-weighted reading order, and a `Lot No.`
// from the row below can land between a `Cat. No.` and its number.
//
// The fix is to stop pretending the frame is a sentence. A keyword line that
// ends without its value is *dangling*; its value is the observation printed
// next to it on the label, which is a question about geometry, and the
// bounding boxes answer it directly.

/// A keyword line with nothing usable after it - `Cat. No.`, `Order no.`, `REF`, `LOT #`.
private let danglingKeywords: [(LabelField, NSRegularExpression)] = [
    (.catalog, try! NSRegularExpression(pattern: #"(?:CAT(?:ALOG)?|PROD|PART|ORDER|[RB]E[FEI])[A-Z]?\.?\s*(?:NO|N0|NUMBER|#)?\.?\s*[:#]?\s*$"#, options: [.caseInsensitive])),
    (.lot,     try! NSRegularExpression(pattern: #"LOT\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*$"#, options: [.caseInsensitive])),
]

/// The first identifier-shaped run in a neighbouring observation.
///
/// Leading punctuation is stripped rather than rejected: the character that
/// separates a keyword from its value (`:`, `#`, `.`) frequently lands at the
/// head of the *value's* observation instead of the tail of the keyword's, and
/// `#728619` is a lot number with a stray glyph, not a failed read.
private let identifierRun = try! NSRegularExpression(pattern: #"[A-Z0-9][A-Z0-9\-]{2,15}"#)

private func leadingIdentifier(of text: String) -> String? {
    let up = text.uppercased()
    let ns = up as NSString
    guard let m = identifierRun.firstMatch(in: up, options: [],
                                           range: NSRange(location: 0, length: ns.length))
    else { return nil }
    // Only the head of the line: a value further in is some other field's.
    guard m.range.location <= 3 else { return nil }
    return ns.substring(with: m.range).trimmingCharacters(in: CharacterSet(charactersIn: " .,;:-"))
}

/// The observation printed immediately right of, or immediately below, `anchor`.
///
/// Vision's boxes are normalised with the origin bottom-left, so "below" is a
/// smaller `midY`. Both directions are needed: vendors set `Cat. No.` beside
/// its number on BioLegend labels and above it on eBioscience ones.
private func neighbour(of anchor: CGRect, in obs: [TextObservation], excluding: Int) -> [TextObservation] {
    let h = max(anchor.height, 0.005)
    var right: [(Double, TextObservation)] = []
    var below: [(Double, TextObservation)] = []
    for (i, o) in obs.enumerated() {
        guard i != excluding, let b = o.boundingBox else { continue }
        let sameRow = abs(b.midY - anchor.midY) < h * 0.6
        if sameRow, b.minX >= anchor.maxX - h * 0.2 {
            right.append((b.minX - anchor.maxX, o))
        }
        let overlapsX = b.maxX > anchor.minX - h && b.minX < anchor.maxX + h * 4
        if overlapsX, b.midY < anchor.minY, anchor.minY - b.midY < h * 2.5 {
            below.append((anchor.minY - b.midY, o))
        }
    }
    return (right.sorted { $0.0 < $1.0 } + below.sorted { $0.0 < $1.0 }).map(\.1)
}

// MARK: - Vendor shapes
//
// Every rule above needs a keyword. Five of the sample videos never produce
// one that survives: the word is occluded by a finger, sheared off by the
// curve, or mangled past recognition (`Cat. No.` came back as `n`, `Lot #` as
// `OL#`), while the *number itself* reads cleanly in half a dozen frames. The
// number is the high-entropy half; refusing to read it because its label was
// unreadable throws away the part that identifies the vial.
//
// What makes this safe rather than a licence to grab digits is that part
// numbers are formatted. `14-9948-82` is not a date, `BE0106` is not a
// quantity, and `10 223 565 001` is not a phone number. Each shape below is
// narrow enough to name its vendor, and each carries a weight below 1 so a
// shape-only read needs more frames of corroboration than a keyword-anchored
// one before the matcher will use it — see `EvidenceBuffer.vote`.

private struct Shape {
    let field: LabelField
    let pattern: NSRegularExpression
    let weight: Double
    /// Canonical vendor that must be legible in the same frame, if any. The
    /// looser a shape is, the more it needs this: six bare digits are a
    /// BioLegend catalog number only once something says BioLegend.
    let vendor: String?
    let normalize: (String) -> String

    init(_ field: LabelField, _ p: String, weight: Double, vendor: String? = nil,
         normalize: @escaping (String) -> String = { $0 }) {
        self.field = field
        self.pattern = try! NSRegularExpression(pattern: p, options: [])
        self.weight = weight
        self.vendor = vendor
        self.normalize = normalize
    }
}

private let shapes: [Shape] = [
    // Descriptive values can survive while their short keyword falls around
    // the label curve. Units and ISO dates keep these fallbacks narrow.
    .init(.concentration,
          #"(?<![0-9])[0-9]+(?:\.[0-9]+)?\s*(?:[mMuUµμ][gG])\s*/\s*[mM]?[lL]\b"#,
          weight: 0.49),
    .init(.volume,
          #"(?<![0-9])[0-9]+(?:\.[0-9]+)?\s*(?:[mMuUµμ][lL])\b"#,
          weight: 0.49),
    .init(.expiry,
          #"\b(?:19|20)[0-9]{2}[-/.][0-9]{1,2}[-/.][0-9]{1,2}\b"#,
          weight: 0.49),
    .init(.expiry,
          #":\s*(?:0?[1-9]|1[0-2])[-/.](?:19|20)[0-9]{2}\b"#,
          weight: 0.49,
          normalize: { $0.dropFirst().trimmingCharacters(in: .whitespaces) }),
    .init(.storage,
          #"(?<![0-9])(?:-\s*)?[0-9]{1,3}\s*(?:°?\s*C)?\s*(?:-|to)\s*(?:-\s*)?[0-9]{1,3}\s*°?\s*C\b"#,
          weight: 0.49,
          normalize: { $0.replacingOccurrences(of: #"-\s+"#, with: "-", options: .regularExpression) }),
    .init(.storage,
          #"(?<![0-9])(?:-\s*)?[0-9]{1,3}\s*°?\s*C\b"#,
          weight: 0.49,
          normalize: { $0.replacingOccurrences(of: #"-\s+"#, with: "-", options: .regularExpression) }),

    // eBioscience / Invitrogen / Affymetrix: `14-9948-82`. Unmistakable, and
    // not confusable with a date — those are `2027-05-31`, four digits first.
    .init(.catalog, #"\b\d{2}-\d{4}-\d{2}\b"#, weight: 0.75),
    // The same number with the second hyphen lost to the curve: `14-491481`.
    .init(.catalog, #"\b\d{2}-\d{4}\d{2}\b"#, weight: 0.5, normalize: {
        let parts = $0.split(separator: "-")
        guard parts.count == 2, parts[1].count == 6 else { return $0 }
        return "\(parts[0])-\(parts[1].prefix(4))-\(parts[1].suffix(2))"
    }),
    // BioXCell prints `BE0106`, and nothing else on the label is shaped like it.
    .init(.catalog, #"\bBE\d{4}\b"#, weight: 0.9),
    // Roche's four groups of digits, printed with the spaces: `10 223 565 001`.
    .init(.catalog, #"\b\d{2} \d{3} \d{3} \d{3}\b"#, weight: 0.9,
          normalize: { $0.replacingOccurrences(of: " ", with: "") }),
    // SouthernBiotech: `0106-01`, four digits and two. This is also the shape of
    // every expiry date a label carries — `2020-11`, `2027-05` — which is why
    // `looksLikeYearMonth` exists and why it is checked before the value is
    // taken. Three of the sample videos read their expiry as a catalog number
    // before that guard went in, and one of them overrode a correct read.
    .init(.catalog, #"\b\d{4}-\d{2}\b"#, weight: 0.6),
    // Leinco: `P362`, `C2855`.
    .init(.catalog, #"\b[A-Z]\d{3,4}\b"#, weight: 0.5, vendor: "Leinco"),
    // BioLegend catalog numbers are six bare digits.  This is unsafe as an
    // unconditional shape (dates and small-print IDs look the same), but safe
    // once the BioLegend stem is present in the same cropped frame.  Curved
    // labels commonly expose `BioLeg...` and the number without ever yielding
    // a complete `Cat. No. 107701` observation.
    .init(.catalog, #"\b[1-9]\d{5}\b"#, weight: 0.5, vendor: "BioLegend"),

    // BioLegend lots are `B` and five or six digits.
    .init(.lot, #"\bB\d{5,6}\b"#, weight: 0.6),
    // Leinco lots encode the month: `0821L490`, `0520L310`.
    .init(.lot, #"\b\d{4}[A-Z]\d{3}\b"#, weight: 0.75),
    // eBioscience lots come in two forms, `E009424` and bare digits.
    .init(.lot, #"\bE\d{6}\b"#, weight: 0.6),
    .init(.lot, #"\b\d{7,8}\b"#, weight: 0.4, vendor: "Invitrogen"),
    .init(.lot, #"\b\d{7,8}\b"#, weight: 0.4, vendor: "Roche"),
]

/// `2020-11`, `2027-05`: an expiry, not a part number. The check is deliberately
/// narrow — SouthernBiotech's `1040-01` and `4510-01` must survive it.
private func looksLikeYearMonth(_ v: String) -> Bool {
    let parts = v.split(separator: "-")
    guard parts.count == 2, parts[0].count == 4, parts[1].count == 2,
          let year = Int(parts[0]), let month = Int(parts[1]) else { return false }
    return (1990...2039).contains(year) && (1...12).contains(month)
}

/// `20030225`-shaped values are dates, not lots. Cheap to check and it removes
/// the one category of eight-digit number a label reliably carries.
private func looksLikeDate(_ v: String) -> Bool {
    guard v.count == 8, v.allSatisfy(\.isNumber) else { return false }
    guard v.hasPrefix("19") || v.hasPrefix("20") else { return false }
    let month = Int(v.dropFirst(4).prefix(2)) ?? 0
    let day = Int(v.suffix(2)) ?? 0
    return (1...12).contains(month) && (1...31).contains(day)
}

/// Structure a value must have before geometry alone is allowed to name it.
///
/// The dangling-keyword pass has no keyword-to-value delimiter to check its
/// work against — it is trusting a bounding box — so it checks the value
/// instead. Part numbers and lot numbers are mostly digits and are not short:
/// `14-00` is a catalog number sheared in half by the curve of the tube, and
/// without this it read as one on IMG_4018.
private func hasIdentifierStructure(_ v: String) -> Bool {
    let k = normKey(v)
    guard k.count >= 5 else { return false }
    guard !looksLikeYearMonth(v), !looksLikeDate(k) else { return false }
    var run = 0, longestRun = 0
    for c in k {
        run = c.isNumber ? run + 1 : 0
        longestRun = max(longestRun, run)
    }
    return longestRun >= 4
}

// MARK: - Extraction

/// One frame's reading of one field, and how much a frame's worth of it counts.
struct FieldReading {
    let value: String
    /// 1.0 for a keyword-anchored read; less for a shape-only one, which needs
    /// proportionally more frames to clear the same corroboration bar.
    let weight: Double
}

/// Extract at most one candidate per field from a single frame's observations.
func extractFields(from observations: [TextObservation]) -> [LabelField: FieldReading] {
    let lines = observations.map(\.text)
    let joined = lines.joined(separator: " ")
    var out = [LabelField: FieldReading]()
    let ns = joined as NSString
    let full = NSRange(location: 0, length: ns.length)

    // 1. Keyword rules over the whole frame.
    var numericPartial: String?
    for rule in rules where out[rule.field] == nil {
        guard let m = rule.pattern.firstMatch(in: joined, options: [], range: full),
              m.numberOfRanges > rule.group,
              m.range(at: rule.group).location != NSNotFound else { continue }
        // A storage temperature's leading `-` is its meaning, not stray
        // punctuation: trimming it turned `-20C` into `20C`, a freezer into a
        // benchtop. Identifiers keep the old trim, where a dangling hyphen is
        // an OCR artefact.
        let trimSet = rule.field == .storage ? " .,;:" : " .,;:-"
        let v = ns.substring(with: m.range(at: rule.group))
            .trimmingCharacters(in: CharacterSet(charactersIn: trimSet))
        if rule.field == .catalog, numericPartial == nil,
           !isPlausible(.catalog, v), matchesWhole(numericPartialCatalog, v) {
            numericPartial = v
        }
        if !v.isEmpty, isPlausible(rule.field, v) {
            let partial = rule.field == .catalog && matchesWhole(partialCatalog, v.uppercased())
            // Identifiers are voted on as upper-case keys; descriptive fields go
            // onto the record as printed, where `100 ΜL` would be wrong.
            let value = descriptiveFields.contains(rule.field) ? v : v.uppercased()
            out[rule.field] = FieldReading(value: value, weight: partial ? 0.4 : 1.0)
        }
    }

    // Vendor first: the shape rules below are gated on it.
    let flat = normKey(joined)
    for (needle, canonical) in vendorAliases where flat.contains(needle) {
        out[.vendor] = FieldReading(value: canonical, weight: 1.0)
        break
    }

    // 2. Dangling keywords, resolved by geometry.
    for (i, o) in observations.enumerated() {
        guard let box = o.boundingBox else { continue }
        for (field, kw) in danglingKeywords where out[field] == nil {
            let t = o.text as NSString
            guard kw.firstMatch(in: o.text, options: [],
                                range: NSRange(location: 0, length: t.length)) != nil else { continue }
            for cand in neighbour(of: box, in: observations, excluding: i) {
                guard let v = leadingIdentifier(of: cand.text), isPlausible(field, v),
                      hasIdentifierStructure(v) else { continue }
                out[field] = FieldReading(value: v, weight: 1.0)
                break
            }
        }
    }

    // 3. Vendor shapes, for the fields still unread.
    let vendor = out[.vendor]?.value
    for shape in shapes where out[shape.field] == nil {
        if let need = shape.vendor, need != vendor { continue }
        for line in lines {
            let lineNS = line as NSString
            let lineRange = NSRange(location: 0, length: lineNS.length)
            guard let m = shape.pattern.firstMatch(in: line, options: [], range: lineRange) else { continue }
            let value = shape.normalize(lineNS.substring(with: m.range))
            guard isPlausible(shape.field, value) else { continue }
            if shape.field == .catalog,
               looksLikeYearMonth(value) || looksLikeDate(normKey(value)) { continue }
            let output = descriptiveFields.contains(shape.field) ? value : value.uppercased()
            out[shape.field] = FieldReading(value: output, weight: shape.weight)
            break
        }
    }

    // 4. Last resort only: a fragment must never displace a fuller read that the
    // same frame produced, and at zero weight it can only ever lend its prefix.
    if out[.catalog] == nil, let p = numericPartial {
        out[.catalog] = FieldReading(value: p, weight: 0)
    }

    return out
}

/// Text-only entry point, for tests and callers with no geometry to offer.
func extractFields(from lines: [String]) -> [LabelField: FieldReading] {
    extractFields(from: lines.map { TextObservation(text: $0, confidence: 1) })
}
