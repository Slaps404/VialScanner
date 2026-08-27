import Foundation

public enum LabelField: String, Sendable, CaseIterable, Codable {
    case catalog, lot, clone, vendor, concentration, volume, isotype
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
    .init(.catalog, #"\bP[RH]OD\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    // Peptide and reagent vendors print `Part`.
    .init(.catalog, #"\bPART\s*(?:NO|#)?\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"#),
    .init(.lot,     #"LOT\s*(?:NO|#|:)?\s*[.:]?\s*([A-Z0-9][A-Z0-9\-]{3,16})"#),
    .init(.clone,   #"CLONE\s*[:#]?\s*\(?\s*([A-Z0-9][A-Z0-9\.\-]{1,12})"#),
    .init(.clone,   #"\(\s*Clone\s+([A-Z0-9][A-Z0-9\.\-]{1,12})\s*\)"#),
    .init(.isotype, #"\b(Ig[GMAE](?:\d[a-c]?)?)\b"#),
    .init(.concentration, #"(?:C|Conc)\s*[:.]?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:mg|ug|µg)\s*/\s*ml"#),
    .init(.volume,  #"(?:Vol|Size)\s*[:.]?\s*([0-9]+(?:\.[0-9]+)?)\s*m?[lL]"#),
]

private let vendorAliases: [(String, String)] = [
    ("southernbiotech", "SouthernBiotech"), ("southernbio", "SouthernBiotech"),
    ("biolegend", "BioLegend"), ("bdbiosciences", "BD"), ("pharmingen", "BD"),
    ("cellsignal", "CST"), ("abcam", "Abcam"), ("invitrogen", "Invitrogen"),
    ("thermofisher", "ThermoFisher"), ("thermo", "ThermoFisher"), ("pierce", "ThermoFisher"),
    ("jacksonimmuno", "Jackson"), ("proteintech", "ProteinTech"), ("rndsystems", "R&D"),
    ("biorad", "BioRad"), ("sigma", "Sigma"), ("bioxcell", "BioXCell"), ("leinco", "Leinco"),
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

private func isPlausible(_ field: LabelField, _ value: String) -> Bool {
    let k = normKey(value)
    // Measure the value as printed, not as normalised. `normKey` strips the
    // hyphen, which turns Leinco's `I-118` into four characters and fails it on
    // a floor that exists to reject truncation artefacts — but the hyphen is
    // structure the vendor printed, not padding. A bare `6260` is still four.
    guard max(k.count, value.count) >= (minLength[field] ?? 2) else { return false }
    guard !stopValues.contains(k) else { return false }
    // A value with no digits at all is almost never a catalog or lot number.
    if field == .catalog || field == .lot {
        if !k.contains(where: \.isNumber) { return false }
    }
    return true
}

/// Extract at most one candidate per field from a single frame's text.
func extractFields(from lines: [String]) -> [LabelField: String] {
    let joined = lines.joined(separator: " ")
    var out = [LabelField: String]()
    let ns = joined as NSString
    let full = NSRange(location: 0, length: ns.length)

    for rule in rules where out[rule.field] == nil {
        guard let m = rule.pattern.firstMatch(in: joined, options: [], range: full),
              m.numberOfRanges > rule.group,
              m.range(at: rule.group).location != NSNotFound else { continue }
        let v = ns.substring(with: m.range(at: rule.group))
            .trimmingCharacters(in: CharacterSet(charactersIn: " .,;:-"))
        if !v.isEmpty, isPlausible(rule.field, v) { out[rule.field] = v.uppercased() }
    }

    let flat = normKey(joined)
    for (needle, canonical) in vendorAliases where flat.contains(needle) {
        out[.vendor] = canonical
        break
    }
    return out
}
