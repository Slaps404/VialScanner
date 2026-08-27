import Foundation

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

    public init(name: String, target: String, clone: String, host: String,
                isotype: String, vendor: String, catalog: String, inventoryLinks: [String]) {
        self.name = name; self.target = target; self.clone = clone; self.host = host
        self.isotype = isotype; self.vendor = vendor; self.catalog = catalog
        self.inventoryLinks = inventoryLinks
    }
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

    public init(abNo: String, definitionName: String, vialName: String, lot: String,
                concentration: String, location: String, box: String, owner: String) {
        self.abNo = abNo; self.definitionName = definitionName; self.vialName = vialName
        self.lot = lot; self.concentration = concentration; self.location = location
        self.box = box; self.owner = owner
    }
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
            abNo[it.abNo.trimmingCharacters(in: .whitespaces)] = it
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
        if let hit = byCatalogFolded[ocrFold(raw)] { return hit }
        return []
    }

    /// Catalog numbers within `maxDistance` edits, but only when the answer is
    /// unambiguous. OCR reliably confuses 0/1 in these part numbers (`0103-01`
    /// read as `1103-01`); accepting a unique single-edit neighbour recovers
    /// those without ever guessing between `0101-01` and `0105-01`.
    public func definitionIndex(nearCatalog raw: String, maxDistance: Int = 1) -> Int? {
        let k = normKey(raw)
        guard k.count >= 5 else { return nil }
        var winners: [Int] = []
        var bestDistance = maxDistance + 1
        for (i, d) in definitions.enumerated() {
            let c = normKey(d.catalog)
            guard c.count >= 5, abs(c.count - k.count) <= maxDistance else { continue }
            let dist = editDistance(c, k)
            if dist < bestDistance { bestDistance = dist; winners = [i] }
            else if dist == bestDistance { winners.append(i) }
        }
        guard bestDistance <= maxDistance, winners.count == 1 else { return nil }
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
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            defs.append(.init(
                name: name,
                target: r[cTarget].trimmingCharacters(in: .whitespacesAndNewlines),
                clone: r[cClone].trimmingCharacters(in: .whitespacesAndNewlines),
                host: r[cHost].trimmingCharacters(in: .whitespacesAndNewlines),
                isotype: r[cIso].trimmingCharacters(in: .whitespacesAndNewlines),
                vendor: r[cVendor].trimmingCharacters(in: .whitespacesAndNewlines),
                catalog: r[cCat].trimmingCharacters(in: .whitespacesAndNewlines),
                inventoryLinks: links))
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
                owner: r[kOwner].trimmingCharacters(in: .whitespacesAndNewlines)))
        }
        return Catalog(definitions: defs, items: items)
    }
}
