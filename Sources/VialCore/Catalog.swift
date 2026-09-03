import Foundation

// MARK: - Field bag

/// Every column of the source row, in the order the export printed them.
///
/// The catalog CSVs carry far more than the engine matches on: category,
/// conjugate, formulation, amounts, prices, links. Naming each one as a stored
/// property means editing Swift every time the Airtable base grows a column, and
/// the base grows. So the named properties below stay exactly what the *engine*
/// reasons about, and everything else rides along here, retrievable by its
/// printed header. A column that is empty today (`Current Volume`) starts
/// appearing the moment somebody fills it in, with no code change.
public struct FieldBag: Sendable, Codable, Hashable {
    public struct Entry: Sendable, Codable, Hashable {
        public let name: String
        public let value: String
        public init(name: String, value: String) { self.name = name; self.value = value }
    }

    public var entries: [Entry]

    public init(entries: [Entry] = []) { self.entries = entries }

    /// Header lookup is normalised, so `Ab Category`, `ab category` and
    /// `AbCategory` all find the same column. Callers should not have to know
    /// how a given exporter capitalised things.
    public subscript(_ header: String) -> String? {
        let k = normKey(header)
        return entries.first { normKey($0.name) == k }?.value
    }

    /// The first of `headers` that is present and non-empty. Airtable renames
    /// and duplicates columns (`Conjugate` is empty, `Conjugate copy` is not),
    /// so a display field is a list of candidates, not one name.
    public func firstValue(_ headers: [String]) -> String {
        for h in headers {
            if let v = self[h], !v.isEmpty { return v }
        }
        return ""
    }

    public var isEmpty: Bool { entries.isEmpty }

    /// Non-empty columns, minus the ones already shown as named properties.
    public func others(excluding shown: [String]) -> [Entry] {
        let skip = Set(shown.map(normKey))
        return entries.filter { !$0.value.isEmpty && !skip.contains(normKey($0.name)) }
    }
}

// MARK: - Records

public struct AntibodyDefinition: Sendable {
    public let name: String
    public let target: String
    public let clone: String
    public let host: String
    public let isotype: String
    public let vendor: String
    public let catalog: String
    /// AbNo values of linked Inventory Items (the authoritative join).
    public let inventoryLinks: [String]
    /// Every remaining column of the source row. See `FieldBag`.
    public let extra: FieldBag

    public init(name: String, target: String, clone: String, host: String,
                isotype: String, vendor: String, catalog: String, inventoryLinks: [String],
                extra: FieldBag = FieldBag()) {
        self.name = name; self.target = target; self.clone = clone; self.host = host
        self.isotype = isotype; self.vendor = vendor; self.catalog = catalog
        self.inventoryLinks = inventoryLinks; self.extra = extra
    }

    // Named views onto the bag. These are the definition columns worth putting
    // in front of somebody holding the vial; the rest stay addressable by header.
    public var productURL: String { extra.firstValue(["Product Website"]) }
    public var price: String { extra.firstValue(["Price"]) }
    public var reactivity: String { extra.firstValue(["Reactivity Vendor", "Reactivity Cited"]) }
    public var storageLocation: String { extra.firstValue(["Storage location (from Inventory Items)"]) }
    public var proteinDatabase: String { extra.firstValue(["Protein Database"]) }
    public var geneDatabase: String { extra.firstValue(["Gene Database"]) }
}

public struct InventoryItem: Sendable {
    public let abNo: String
    public let definitionName: String
    public let vialName: String
    public let lot: String
    public let concentration: String
    public let location: String
    public let box: String
    public let owner: String
    /// Every remaining column of the source row. See `FieldBag`.
    public let extra: FieldBag

    public init(abNo: String, definitionName: String, vialName: String, lot: String,
                concentration: String, location: String, box: String, owner: String,
                extra: FieldBag = FieldBag()) {
        self.abNo = abNo; self.definitionName = definitionName; self.vialName = vialName
        self.lot = lot; self.concentration = concentration; self.location = location
        self.box = box; self.owner = owner; self.extra = extra
    }

    // Named views onto the bag: the columns somebody at the freezer actually
    // asks about. `conjugate` reads three headers because the base has an empty
    // `Conjugate` column beside a populated `Conjugate copy`.
    public var status: String { extra.firstValue(["Ab Status"]) }
    public var category: String { extra.firstValue(["Ab Category"]) }
    public var conjugate: String {
        extra.firstValue(["Conjugate", "Dye Name (from Conjugate copy)", "Conjugate copy"])
    }
    public var amount: String { extra.firstValue(["Amount"]) }
    public var formulation: String { extra.firstValue(["Formulation"]) }
    public var initialVolume: String { extra.firstValue(["Initial Volume"]) }
    public var currentVolume: String { extra.firstValue(["Current Volume"]) }
    public var diluteTo2: String { extra.firstValue(["Dilute to 2 ug/ml"]) }
    public var receivingNotes: String { extra.firstValue(["Receiving Notes"]) }
    public var target: String { extra.firstValue(["Target"]) }
    public var clone: String { extra.firstValue(["Clone (from Antibody Definitions)", "Clone"]) }
    public var parentVial: String { extra.firstValue(["Parent Vial"]) }
    public var childVial: String { extra.firstValue(["Child Vial"]) }

    /// Headers already surfaced as stored properties or named accessors, so a
    /// "everything else" list does not repeat them.
    public static let namedHeaders = [
        "AbNo", "Antibody Definitions", "Vial Name", "Ab Lot", "Concentration",
        "Location", "Box", "Owner", "Ab Status", "Ab Category", "Conjugate",
        "Conjugate copy", "Dye Name (from Conjugate copy)", "Amount", "Formulation",
        "Initial Volume", "Current Volume", "Dilute to 2 ug/ml", "Receiving Notes",
        "Target", "Clone (from Antibody Definitions)", "Parent Vial", "Child Vial",
    ]
}

// MARK: - Normalization

@inlinable
public func normKey(_ s: String) -> String {
    var out = String.UnicodeScalarView()
    for u in s.lowercased().unicodeScalars where CharacterSet.alphanumerics.contains(u) {
        out.append(u)
    }
    return String(out)
}

/// OCR confusables, folded so `0106-01` and `O1O6O1` collapse together.
@inlinable
public func ocrFold(_ s: String) -> String {
    var r = ""
    for c in normKey(s) {
        switch c {
        case "o": r.append("0")
        case "i", "l": r.append("1")
        case "s": r.append("5")
        case "b": r.append("8")
        case "z": r.append("2")
        case "g", "q": r.append("9")
        default: r.append(c)
        }
    }
    return r
}

/// OCR confusions are one-to-many: `s` is genuinely both `5` and `8`, and a
/// fold to a single digit can never express that. One bit per glyph-shape
/// group, two characters are confusable when their groups overlap. See README
/// "One-to-many OCR confusions" for why this sits beside `ocrFold` rather than
/// replacing it.
@inlinable
public func confusionGroups(_ c: Character) -> UInt8 {
    switch c {
    case "0", "o", "d": return 0b0000_0001
    case "q":           return 0b0010_0001
    case "1", "i", "l", "7": return 0b0000_0010
    case "5":           return 0b0000_0100
    case "s":           return 0b0000_1100
    case "8":           return 0b0000_1000
    case "b":           return 0b0001_1000
    case "6":           return 0b0001_0000
    case "g":           return 0b0011_0000
    case "9":           return 0b0010_0000
    case "2", "z":      return 0b0100_0000
    default:            return 0
    }
}

@inlinable
public func confusable(_ a: Character, _ b: Character) -> Bool {
    a != b && (confusionGroups(a) & confusionGroups(b)) != 0
}

/// Levenshtein on a doubled cost scale: an ordinary edit costs 2, a
/// substitution between two confusable glyphs costs 1. Ranking, not gating:
/// the budget still admits exactly one ordinary edit.
public func confusionDistance(_ a: String, _ b: String) -> Int {
    if a == b { return 0 }
    if a.isEmpty { return b.count * 2 }
    if b.isEmpty { return a.count * 2 }
    let x = Array(a), y = Array(b)
    var prev = (0...y.count).map { $0 * 2 }
    var cur = [Int](repeating: 0, count: y.count + 1)
    for i in 1...x.count {
        cur[0] = i * 2
        for j in 1...y.count {
            let sub: Int = x[i-1] == y[j-1] ? 0 : (confusable(x[i-1], y[j-1]) ? 1 : 2)
            cur[j] = min(prev[j] + 2, cur[j - 1] + 2, prev[j - 1] + sub)
        }
        swap(&prev, &cur)
    }
    return prev[y.count]
}

/// A letter read where the catalog number carries a digit. Pack size is encoded
/// in a digit, so a letter in that slot is a misread, not a different product.
@inlinable
public func letterForDigit(_ read: Character, _ onFile: Character) -> Bool {
    read.isLetter && onFile.isNumber
}

// MARK: - Catalog

public struct Catalog: Sendable {
    public let definitions: [AntibodyDefinition]
    public let items: [InventoryItem]

    private let byAbNo: [String: InventoryItem]
    private let byDefName: [String: [InventoryItem]]
    /// Exact + OCR-folded catalog-number index for O(1) tier-2 resolution.
    private let byCatalog: [String: [Int]]
    private let byCatalogFolded: [String: [Int]]

    public init(definitions: [AntibodyDefinition], items: [InventoryItem]) {
        self.definitions = definitions
        self.items = items

        var abNo = [String: InventoryItem]()
        var defName = [String: [InventoryItem]]()
        for it in items {
            abNo[it.abNo.trimmingCharacters(in: .whitespacesAndNewlines)] = it
            defName[normKey(it.definitionName), default: []].append(it)
        }
        self.byAbNo = abNo
        self.byDefName = defName

        var cat = [String: [Int]]()
        var catF = [String: [Int]]()
        for (i, d) in definitions.enumerated() {
            let k = normKey(d.catalog)
            guard k.count >= 4 else { continue }
            cat[k, default: []].append(i)
            catF[ocrFold(d.catalog), default: []].append(i)
        }
        self.byCatalog = cat
        self.byCatalogFolded = catF
    }

    public func definitionIndices(forCatalog raw: String) -> [Int] {
        let k = normKey(raw)
        if let hit = byCatalog[k] { return hit }
        guard let hit = byCatalogFolded[ocrFold(raw)] else { return [] }
        // The fold commits `s` to `5`, but the glyph is equally `8`. If some
        // other definition reads just as plausibly, this is not an exact match:
        // hand it to the corroborated near-match tier instead of asserting one.
        let rivals = confusableIndices(k).filter { !hit.contains($0) }
        return rivals.isEmpty ? hit : []
    }

    /// Definitions of the same length whose every character is equal to, or
    /// confusable with, the read. Only walked on a folded hit.
    private func confusableIndices(_ k: String) -> [Int] {
        guard k.count >= 4 else { return [] }
        let read = Array(k)
        var out: [Int] = []
        for (i, d) in definitions.enumerated() {
            let c = Array(normKey(d.catalog))
            guard c.count == read.count else { continue }
            var ok = true
            for j in 0..<c.count where c[j] != read[j] && !confusable(c[j], read[j]) {
                ok = false; break
            }
            if ok { out.append(i) }
        }
        return out
    }

    /// Catalog numbers within `maxDistance` edits, but only when the answer is
    /// unambiguous. OCR reliably confuses 0/1 in these part numbers (`0103-01`
    /// read as `1103-01`); accepting a unique single-edit neighbour recovers
    /// those without ever guessing between `0101-01` and `0105-01`. Confusable
    /// substitutions are half price, so a shape-plausible reading outranks an
    /// arbitrary one instead of tying with it.
    public func definitionIndex(nearCatalog raw: String, maxDistance: Int = 1) -> Int? {
        let k = normKey(raw)
        guard k.count >= 5 else { return nil }
        var winners: [Int] = []
        // Doubled scale: one ordinary edit, or two confusable substitutions.
        let budget = maxDistance * 2
        var bestDistance = budget + 1
        for (i, d) in definitions.enumerated() {
            let c = normKey(d.catalog)
            guard c.count >= 5, abs(c.count - k.count) <= maxDistance else { continue }
            let dist = confusionDistance(c, k)
            if dist < bestDistance { bestDistance = dist; winners = [i] }
            else if dist == bestDistance { winners.append(i) }
        }
        guard bestDistance <= budget, winners.count == 1 else { return nil }
        return winners[0]
    }

    /// Resolve the vials belonging to a definition. Prefers the explicit link
    /// field; falls back to name join. Never keys on lot — lot is not unique.
    public func inventory(for def: AntibodyDefinition) -> [InventoryItem] {
        let linked = def.inventoryLinks.compactMap { byAbNo[$0] }
        if !linked.isEmpty { return linked }
        return byDefName[normKey(def.name)] ?? []
    }
}

// MARK: - CSV loading

public enum CSVError: Error { case missingColumn(String), unreadable(String) }

/// Minimal RFC-4180 reader: handles quoted fields, embedded commas/newlines, "" escapes.
///
/// Iterates unicode scalars rather than `Character`s on purpose: Swift folds
/// CRLF into a single grapheme cluster, so a `Character`-based switch matches
/// neither "\r" nor "\n" and silently swallows every row terminator in a
/// CRLF file.
public func parseCSV(_ text: String) -> [[String]] {
    let u = Array(text.unicodeScalars)
    var rows: [[String]] = []
    var row: [String] = []
    var field = String.UnicodeScalarView()
    var i = 0

    func endField() { row.append(String(field)); field = String.UnicodeScalarView() }
    func endRow() {
        endField()
        // Ignore the trailing blank line most exporters emit.
        if !(row.count == 1 && row[0].isEmpty) { rows.append(row) }
        row = []
    }

    while i < u.count {
        let c = u[i]
        if c == "\"" {
            // Quoted field: scan to the closing quote, folding "" into a literal.
            i += 1
            while i < u.count {
                if u[i] == "\"" {
                    if i + 1 < u.count, u[i + 1] == "\"" { field.append("\""); i += 2; continue }
                    i += 1
                    break
                }
                field.append(u[i]); i += 1
            }
            continue
        }
        switch c {
        case ",":
            endField(); i += 1
        case "\n":
            endRow(); i += 1
        case "\r":
            endRow()
            i += (i + 1 < u.count && u[i + 1] == "\n") ? 2 : 1
        default:
            field.append(c); i += 1
        }
    }
    if !field.isEmpty || !row.isEmpty { endRow() }
    return rows
}

public extension Catalog {
    static func load(definitionsCSV: URL, inventoryCSV: URL) throws -> Catalog {
        func read(_ u: URL) throws -> [[String]] {
            guard var s = try? String(contentsOf: u, encoding: .utf8) else {
                throw CSVError.unreadable(u.lastPathComponent)
            }
            if s.hasPrefix("\u{FEFF}") { s.removeFirst() }
            return parseCSV(s)
        }
        func indexer(_ header: [String]) -> (String) -> Int? {
            let map = Dictionary(header.enumerated().map { (normKey($0.element), $0.offset) },
                                 uniquingKeysWith: { a, _ in a })
            return { map[normKey($0)] }
        }

        /// Every column of one row, header-keyed, trimmed, blanks kept so the
        /// column list still reflects the export's shape.
        func bag(_ header: [String], _ row: [String]) -> FieldBag {
            var out: [FieldBag.Entry] = []
            out.reserveCapacity(header.count)
            for (i, name) in header.enumerated() {
                let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !n.isEmpty else { continue }
                let v = i < row.count ? row[i].trimmingCharacters(in: .whitespacesAndNewlines) : ""
                out.append(.init(name: n, value: v))
            }
            return FieldBag(entries: out)
        }

        let dRows = try read(definitionsCSV)
        guard let dHead = dRows.first else { throw CSVError.unreadable("definitions") }
        let dIdx = indexer(dHead)
        func dCol(_ n: String) throws -> Int {
            guard let i = dIdx(n) else { throw CSVError.missingColumn(n) }
            return i
        }
        let cName = try dCol("AntibodyName"), cTarget = try dCol("Target")
        let cClone = try dCol("Clone"), cHost = try dCol("Host")
        let cIso = try dCol("Isotype"), cVendor = try dCol("Vendor")
        let cCat = try dCol("Catalog#"), cInv = try dCol("Inventory Items")

        var defs: [AntibodyDefinition] = []
        for r in dRows.dropFirst() where r.count > max(cInv, cCat) {
            let name = r[cName].trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { continue }
            let links = r[cInv].split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            defs.append(.init(
                name: name,
                target: r[cTarget].trimmingCharacters(in: .whitespacesAndNewlines),
                clone: r[cClone].trimmingCharacters(in: .whitespacesAndNewlines),
                host: r[cHost].trimmingCharacters(in: .whitespacesAndNewlines),
                isotype: r[cIso].trimmingCharacters(in: .whitespacesAndNewlines),
                vendor: r[cVendor].trimmingCharacters(in: .whitespacesAndNewlines),
                catalog: r[cCat].trimmingCharacters(in: .whitespacesAndNewlines),
                inventoryLinks: links,
                extra: bag(dHead, r)))
        }

        let iRows = try read(inventoryCSV)
        guard let iHead = iRows.first else { throw CSVError.unreadable("inventory") }
        let iIdx = indexer(iHead)
        func iCol(_ n: String) throws -> Int {
            guard let i = iIdx(n) else { throw CSVError.missingColumn(n) }
            return i
        }
        let kAb = try iCol("AbNo"), kDef = try iCol("Antibody Definitions")
        let kVial = try iCol("Vial Name"), kLot = try iCol("Ab Lot")
        let kConc = try iCol("Concentration"), kLoc = try iCol("Location")
        let kBox = try iCol("Box"), kOwner = try iCol("Owner")

        var items: [InventoryItem] = []
        for r in iRows.dropFirst() where r.count > max(kOwner, kBox) {
            let ab = r[kAb].trimmingCharacters(in: .whitespacesAndNewlines)
            guard !ab.isEmpty else { continue }
            items.append(.init(
                abNo: ab,
                definitionName: r[kDef].trimmingCharacters(in: .whitespacesAndNewlines),
                vialName: r[kVial].trimmingCharacters(in: .whitespacesAndNewlines),
                lot: r[kLot].trimmingCharacters(in: .whitespacesAndNewlines),
                concentration: r[kConc].trimmingCharacters(in: .whitespacesAndNewlines),
                location: r[kLoc].trimmingCharacters(in: .whitespacesAndNewlines),
                box: r[kBox].trimmingCharacters(in: .whitespacesAndNewlines),
                owner: r[kOwner].trimmingCharacters(in: .whitespacesAndNewlines),
                extra: bag(iHead, r)))
        }
        return Catalog(definitions: defs, items: items)
    }
}
