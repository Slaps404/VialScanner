import Foundation
import Combine
import VialCore

/// Everything the app owns that outlives a scan.
///
/// One store, one JSON file, written atomically after every mutation. The lab's
/// data set is 161 definitions and ~140 vials — small enough that a document
/// model beats a database, and a document is also the thing you can hand to
/// someone as an export when the network in the freezer room is unusable.
final class AppStore: ObservableObject {

    @Published private(set) var defs: [DefRecord] = []
    @Published private(set) var items: [ItemRecord] = []
    @Published private(set) var scans: [ScanRecord] = []
    @Published private(set) var audits: [AuditSession] = []
    @Published private(set) var catalog = Catalog(definitions: [], items: [])
    @Published var settings = AppSettings() { didSet { scheduleSave() } }
    /// Surfaced to the user rather than logged: an import that silently did
    /// nothing is worse than one that says why.
    @Published var banner: Banner?

    struct Banner: Identifiable, Equatable {
        let id = UUID()
        var text: String
        var isError: Bool = false
    }

    // MARK: Paths

    private static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("VialScanner", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }
    private var storeURL: URL { Self.root.appendingPathComponent("store.json") }

    /// Clips live in Documents so the Files app can reach them — a reviewer with
    /// an iPad and no Xcode still needs to be able to pull a video off the phone.
    static var clipsDir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Clips", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    // MARK: Persisted shape

    private struct Snapshot: Codable {
        var version = 1
        var defs: [DefRecord] = []
        var items: [ItemRecord] = []
        var scans: [ScanRecord] = []
        var audits: [AuditSession] = []
        var settings = AppSettings()
    }

    private var saveWork: DispatchWorkItem?
    private let io = DispatchQueue(label: "vial.store.io", qos: .utility)

    init() {
        load()
        purgeExpiredClips()
    }

    // MARK: - Load / save

    private func load() {
        if let data = try? Data(contentsOf: storeURL),
           let snap = try? JSONDecoder.vial.decode(Snapshot.self, from: data) {
            defs = snap.defs; items = snap.items; scans = snap.scans
            audits = snap.audits; settings = snap.settings
            rebuild()
            return
        }
        seedFromBundle()
    }

    /// First launch: the CSV snapshot shipped in the bundle. The app is fully
    /// usable offline before anyone imports anything.
    private func seedFromBundle() {
        guard let d = Bundle.main.url(forResource: "definitions", withExtension: "csv"),
              let i = Bundle.main.url(forResource: "inventory", withExtension: "csv"),
              let loaded = try? Catalog.load(definitionsCSV: d, inventoryCSV: i) else {
            banner = Banner(text: "Bundled catalog is missing. Import CSVs to get started.", isError: true)
            rebuild()
            return
        }
        defs = loaded.definitions.map(DefRecord.init)
        items = loaded.items.map(ItemRecord.init)
        rebuild()
        save()
    }

    private func rebuild() {
        catalog = Catalog(definitions: defs.map(\.core), items: items.map(\.core))
    }

    private func scheduleSave() {
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.save() }
        saveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    func save() {
        let snap = Snapshot(defs: defs, items: items, scans: scans, audits: audits, settings: settings)
        let url = storeURL
        io.async {
            guard let data = try? JSONEncoder.vial.encode(snap) else { return }
            // Atomic: a phone that dies mid-write must not lose the inventory.
            try? data.write(to: url, options: .atomic)
        }
    }

    private func commit() { rebuild(); scheduleSave() }

    // MARK: - Derived collections

    var boxes: [String] { unique(items.map(\.box)) }
    var locations: [String] { unique(items.map(\.location)) }
    var owners: [String] { unique(items.map(\.owner)) }

    private func unique(_ xs: [String]) -> [String] {
        Array(Set(xs.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })).sorted {
            $0.localizedStandardCompare($1) == .orderedAscending
        }
    }

    func item(abNo: String) -> ItemRecord? {
        items.first { $0.abNo.caseInsensitiveCompare(abNo) == .orderedSame }
    }

    func definition(named name: String) -> DefRecord? {
        defs.first { normKey($0.name) == normKey(name) }
    }

    var reviewQueue: [ScanRecord] {
        scans.filter { $0.outcome == .review && $0.resolvedAt == nil }
            .sorted { $0.date > $1.date }
    }

    var openAudit: AuditSession? { audits.first { $0.isOpen } }

    // MARK: - Inventory mutations

    /// The next free AbNo. Airtable's AbNo is a plain integer here, so the next
    /// one is max + 1; anything non-numeric is ignored rather than guessed at.
    func nextAbNo() -> String {
        let maxN = items.compactMap { Int($0.abNo.filter(\.isNumber)) }.max() ?? 0
        return String(maxN + 1)
    }

    @discardableResult
    func addVial(_ item: ItemRecord) -> ItemRecord {
        var v = item
        v.isLocal = true
        if v.receivedAt == nil { v.receivedAt = Date() }
        items.append(v)
        // Keep the definition's link field in step, since `Catalog.inventory(for:)`
        // trusts the link before the name join.
        if let idx = defs.firstIndex(where: { normKey($0.name) == normKey(v.definitionName) }),
           !defs[idx].inventoryLinks.contains(v.abNo) {
            defs[idx].inventoryLinks.append(v.abNo)
        }
        commit()
        return v
    }

    func updateVial(_ item: ItemRecord) {
        guard let idx = items.firstIndex(where: { $0.id == item.id }) else { return }
        items[idx] = item
        commit()
    }

    func deleteVial(_ item: ItemRecord) {
        items.removeAll { $0.id == item.id }
        for i in defs.indices { defs[i].inventoryLinks.removeAll { $0 == item.abNo } }
        commit()
    }

    @discardableResult
    func addDefinition(_ def: DefRecord) -> DefRecord {
        var d = def
        d.isLocal = true
        defs.append(d)
        commit()
        return d
    }

    func updateDefinition(_ def: DefRecord) {
        guard let idx = defs.firstIndex(where: { $0.id == def.id }) else { return }
        defs[idx] = def
        commit()
    }

    /// Mark a vial as physically seen. This is what "confirm" means: not that
    /// the record changed, but that someone laid eyes on the tube today.
    func markConfirmed(abNo: String, scanID: UUID?) {
        guard let idx = items.firstIndex(where: { $0.abNo == abNo }) else { return }
        items[idx].lastConfirmedAt = Date()
        items[idx].lastScanID = scanID
        commit()
    }

    // MARK: - Scan log

    @discardableResult
    func log(_ record: ScanRecord) -> ScanRecord {
        scans.insert(record, at: 0)
        scheduleSave()
        return record
    }

    func update(_ record: ScanRecord) {
        guard let idx = scans.firstIndex(where: { $0.id == record.id }) else { return }
        scans[idx] = record
        scheduleSave()
    }

    func resolveReview(_ record: ScanRecord, as outcome: ScanOutcome, note: String? = nil) {
        guard let idx = scans.firstIndex(where: { $0.id == record.id }) else { return }
        scans[idx].outcome = outcome
        scans[idx].resolvedAt = Date()
        if let note { scans[idx].note = note }
        scheduleSave()
    }

    func delete(_ record: ScanRecord) {
        if let clip = record.clipFile { deleteClip(named: clip) }
        scans.removeAll { $0.id == record.id }
        scheduleSave()
    }

    // MARK: - Clips

    static func clipURL(_ name: String) -> URL { clipsDir.appendingPathComponent(name) }

    /// Move a just-recorded temp file into permanent storage. Returns the stored
    /// filename, or nil when there was nothing to keep.
    func keepClip(at temp: URL?) -> String? {
        guard let temp, FileManager.default.fileExists(atPath: temp.path) else { return nil }
        let name = "scan-\(Int(Date().timeIntervalSince1970))-\(UUID().uuidString.prefix(4)).mov"
        let dest = Self.clipURL(name)
        do {
            try FileManager.default.moveItem(at: temp, to: dest)
            return name
        } catch {
            banner = Banner(text: "Could not save the clip: \(error.localizedDescription)", isError: true)
            return nil
        }
    }

    func deleteClip(named name: String) {
        try? FileManager.default.removeItem(at: Self.clipURL(name))
    }

    var clipBytes: Int64 {
        let names = Set(scans.compactMap(\.clipFile))
        return names.reduce(into: Int64(0)) { total, name in
            let attrs = try? FileManager.default.attributesOfItem(atPath: Self.clipURL(name).path)
            total += (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        }
    }

    /// Clips age out, but never while their scan is still waiting on a human.
    func purgeExpiredClips() {
        let days = settings.clipRetentionDays
        guard days > 0 else { return }
        let cutoff = Date().addingTimeInterval(-Double(days) * 86_400)
        var changed = false
        for idx in scans.indices {
            guard let clip = scans[idx].clipFile,
                  scans[idx].date < cutoff,
                  !(scans[idx].outcome == .review && scans[idx].resolvedAt == nil) else { continue }
            deleteClip(named: clip)
            scans[idx].clipFile = nil
            changed = true
        }
        if changed { scheduleSave() }
    }

    func purgeAllClips() {
        for idx in scans.indices where scans[idx].clipFile != nil {
            deleteClip(named: scans[idx].clipFile!)
            scans[idx].clipFile = nil
        }
        // Anything orphaned by a crash goes too.
        if let files = try? FileManager.default.contentsOfDirectory(at: Self.clipsDir,
                                                                    includingPropertiesForKeys: nil) {
            for f in files { try? FileManager.default.removeItem(at: f) }
        }
        scheduleSave()
    }

    // MARK: - Audits

    @discardableResult
    func startAudit(scope: AuditScope, value: String, name: String) -> AuditSession {
        let expected = expectedAbNos(scope: scope, value: value)
        let session = AuditSession(name: name, scope: scope, scopeValue: value, expected: expected)
        audits.insert(session, at: 0)
        scheduleSave()
        return session
    }

    func expectedAbNos(scope: AuditScope, value: String) -> [String] {
        let matching: [ItemRecord]
        switch scope {
        case .box:        matching = items.filter { $0.box == value }
        case .location:   matching = items.filter { $0.location == value }
        case .owner:      matching = items.filter { $0.owner == value }
        case .everything: matching = items
        }
        return matching.map(\.abNo).sorted { lhs, rhs in
            (Int(lhs) ?? 0, lhs) < (Int(rhs) ?? 0, rhs)
        }
    }

    func audit(_ id: UUID) -> AuditSession? { audits.first { $0.id == id } }

    func recordFound(_ abNo: String, in auditID: UUID, method: String, scanID: UUID?) {
        guard let idx = audits.firstIndex(where: { $0.id == auditID }) else { return }
        guard !audits[idx].found.contains(where: { $0.abNo == abNo }) else { return }
        audits[idx].found.append(AuditFinding(abNo: abNo, method: method, scanID: scanID))
        markConfirmed(abNo: abNo, scanID: scanID)
        scheduleSave()
    }

    func recordExtra(_ extra: AuditExtra, in auditID: UUID) {
        guard let idx = audits.firstIndex(where: { $0.id == auditID }) else { return }
        audits[idx].extras.append(extra)
        scheduleSave()
    }

    func unmarkFound(_ abNo: String, in auditID: UUID) {
        guard let idx = audits.firstIndex(where: { $0.id == auditID }) else { return }
        audits[idx].found.removeAll { $0.abNo == abNo }
        scheduleSave()
    }

    func finishAudit(_ id: UUID) {
        guard let idx = audits.firstIndex(where: { $0.id == id }) else { return }
        audits[idx].finishedAt = Date()
        scheduleSave()
    }

    func deleteAudit(_ id: UUID) {
        audits.removeAll { $0.id == id }
        scheduleSave()
    }

    // MARK: - Import

    enum ImportMode: String, CaseIterable, Identifiable {
        case merge, replace
        var id: String { rawValue }
        var label: String { self == .merge ? "Merge" : "Replace" }
        var blurb: String {
            self == .merge
            ? "Keep vials added on this phone, update everything the file also has."
            : "Discard what is here and use the file exactly as exported."
        }
    }

    /// Import a fresh Airtable export. Both files are required together: a
    /// definitions file without inventory would leave every vial unlinked.
    func importCSVs(definitions: URL, inventory: URL, mode: ImportMode) {
        let needsStop = definitions.startAccessingSecurityScopedResource()
        let needsStop2 = inventory.startAccessingSecurityScopedResource()
        defer {
            if needsStop { definitions.stopAccessingSecurityScopedResource() }
            if needsStop2 { inventory.stopAccessingSecurityScopedResource() }
        }
        do {
            let loaded = try Catalog.load(definitionsCSV: definitions, inventoryCSV: inventory)
            let newDefs = loaded.definitions.map(DefRecord.init)
            let newItems = loaded.items.map(ItemRecord.init)
            guard !newDefs.isEmpty, !newItems.isEmpty else {
                banner = Banner(text: "That export parsed to zero rows — check the two files are the right way round.", isError: true)
                return
            }

            switch mode {
            case .replace:
                defs = newDefs
                items = newItems
            case .merge:
                var mergedDefs = newDefs
                for local in defs where local.isLocal {
                    if !mergedDefs.contains(where: { normKey($0.name) == normKey(local.name) }) {
                        mergedDefs.append(local)
                    }
                }
                var mergedItems = newItems
                for local in items {
                    guard let idx = mergedItems.firstIndex(where: { $0.abNo == local.abNo }) else {
                        // Received here and not yet pushed upstream — keep it.
                        if local.isLocal { mergedItems.append(local) }
                        continue
                    }
                    // Confirmation history is ours, not Airtable's.
                    mergedItems[idx].lastConfirmedAt = local.lastConfirmedAt
                    mergedItems[idx].lastScanID = local.lastScanID
                    mergedItems[idx].notes = local.notes.isEmpty ? mergedItems[idx].notes : local.notes
                }
                defs = mergedDefs
                items = mergedItems
            }
            commit()
            save()
            banner = Banner(text: "Imported \(defs.count) definitions and \(items.count) vials.")
        } catch CSVError.missingColumn(let col) {
            banner = Banner(text: "Column “\(col)” is missing. Export the grid view, not a filtered subset.", isError: true)
        } catch {
            banner = Banner(text: "Import failed: \(error.localizedDescription)", isError: true)
        }
    }

    func resetToBundled() {
        defs = []; items = []
        seedFromBundle()
        banner = Banner(text: "Catalog reset to the bundled snapshot.")
    }

    // MARK: - Export

    func exportInventoryCSV() -> URL? {
        var rows: [[String]] = [[
            "AbNo", "Antibody Definitions", "Vial Name", "Ab Lot", "Concentration",
            "Location", "Box", "Owner", "Ab Status", "Notes", "Received", "Added on device",
            "Last confirmed",
        ]]
        for i in items.sorted(by: { (Int($0.abNo) ?? 0) < (Int($1.abNo) ?? 0) }) {
            rows.append([
                i.abNo, i.definitionName, i.vialName, i.lot, i.concentration,
                i.location, i.box, i.owner, i.status, i.notes,
                i.receivedAt.map(Self.stamp) ?? "", i.isLocal ? "yes" : "",
                i.lastConfirmedAt.map(Self.stamp) ?? "",
            ])
        }
        return write(rows, name: "inventory-\(Self.fileStamp()).csv")
    }

    func exportDefinitionsCSV() -> URL? {
        var rows: [[String]] = [[
            "AntibodyName", "Target", "Clone", "Host", "Isotype", "Vendor", "Catalog#",
            "Inventory Items", "Added on device",
        ]]
        for d in defs {
            rows.append([d.name, d.target, d.clone, d.host, d.isotype, d.vendor, d.catalog,
                         d.inventoryLinks.joined(separator: ", "), d.isLocal ? "yes" : ""])
        }
        return write(rows, name: "definitions-\(Self.fileStamp()).csv")
    }

    func exportScanLogCSV() -> URL? {
        var rows: [[String]] = [[
            "When", "Verdict", "Outcome", "AbNo", "Antibody", "Catalog", "Lot",
            "Barcode", "Score", "Margin", "Frames", "Lot not on record", "Clip", "Note", "Why",
        ]]
        for s in scans {
            rows.append([
                Self.stamp(s.date), s.verdict, s.outcome.rawValue, s.abNo ?? "",
                s.definitionName ?? "", s.catalog ?? "", s.lot ?? "", s.barcode ?? "",
                String(format: "%.1f", s.score), String(format: "%.1f", s.margin),
                String(s.framesUsed), s.lotNotOnRecord ? "yes" : "", s.clipFile ?? "",
                s.note, s.reasons.joined(separator: "; "),
            ])
        }
        return write(rows, name: "scan-log-\(Self.fileStamp()).csv")
    }

    func exportAuditCSV(_ session: AuditSession) -> URL? {
        var rows: [[String]] = [["Status", "AbNo", "Vial", "Antibody", "Lot", "Box", "Location", "When", "How"]]
        for f in session.found {
            let it = item(abNo: f.abNo)
            rows.append(["found", f.abNo, it?.vialName ?? "", it?.definitionName ?? "",
                         it?.lot ?? "", it?.box ?? "", it?.location ?? "",
                         Self.stamp(f.at), f.method])
        }
        for abNo in session.missing {
            let it = item(abNo: abNo)
            rows.append(["MISSING", abNo, it?.vialName ?? "", it?.definitionName ?? "",
                         it?.lot ?? "", it?.box ?? "", it?.location ?? "", "", ""])
        }
        for e in session.extras {
            rows.append(["unexpected", "", e.label, "", e.lot ?? "", "", "", Self.stamp(e.at), "scan"])
        }
        let slug = session.title.replacingOccurrences(of: " ", with: "-").lowercased()
        return write(rows, name: "audit-\(slug)-\(Self.fileStamp()).csv")
    }

    /// The whole store, for backup or for moving to another phone.
    func exportBackupJSON() -> URL? {
        let snap = Snapshot(defs: defs, items: items, scans: scans, audits: audits, settings: settings)
        guard let data = try? JSONEncoder.vial.encode(snap) else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("vialscanner-backup-\(Self.fileStamp()).json")
        do { try data.write(to: url, options: .atomic); return url } catch { return nil }
    }

    func importBackupJSON(_ url: URL) {
        let needsStop = url.startAccessingSecurityScopedResource()
        defer { if needsStop { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url),
              let snap = try? JSONDecoder.vial.decode(Snapshot.self, from: data) else {
            banner = Banner(text: "That file is not a Vial Scanner backup.", isError: true)
            return
        }
        defs = snap.defs; items = snap.items; scans = snap.scans
        audits = snap.audits; settings = snap.settings
        commit(); save()
        banner = Banner(text: "Restored \(items.count) vials and \(scans.count) scans.")
    }

    // MARK: CSV writing

    private func write(_ rows: [[String]], name: String) -> URL? {
        let text = rows.map { $0.map(Self.escape).joined(separator: ",") }.joined(separator: "\r\n")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            // BOM so Excel opens UTF-8 exports without mangling µ.
            var data = Data([0xEF, 0xBB, 0xBF])
            data.append(text.data(using: .utf8) ?? Data())
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            banner = Banner(text: "Export failed: \(error.localizedDescription)", isError: true)
            return nil
        }
    }

    private static func escape(_ field: String) -> String {
        if field.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) {
            return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return field
    }

    static func stamp(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: d)
    }

    static func fileStamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmm"
        return f.string(from: Date())
    }
}

extension JSONEncoder {
    static var vial: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }
}

extension JSONDecoder {
    static var vial: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }
}
