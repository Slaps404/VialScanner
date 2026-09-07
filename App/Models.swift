import Foundation
import CoreGraphics
import VialCore

// MARK: - Persistable mirrors of the engine's records
//
// `VialCore` deliberately knows nothing about storage: it takes a `Catalog` and
// answers questions. These structs are the writable, `Codable` side of the same
// data, and `Catalog` is rebuilt from them whenever they change. Everything the
// lab edits — receiving a vial, fixing a lot number, importing a fresh export —
// happens here, not in the engine.

struct DefRecord: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String
    var target: String = ""
    var clone: String = ""
    var host: String = ""
    var isotype: String = ""
    var vendor: String = ""
    var catalog: String = ""
    var inventoryLinks: [String] = []
    /// Added on this device rather than imported. Kept so an import can be
    /// merged without silently discarding local receiving work.
    var isLocal: Bool = false
    /// Every other column of the imported row. Optional purely so a document
    /// written before this field existed still decodes: Swift's synthesised
    /// `init(from:)` throws on a missing non-optional key even when the property
    /// has a default, and a throw here would discard somebody's receiving work.
    var extra: FieldBag? = nil

    var fields: FieldBag { extra ?? FieldBag() }

    var core: AntibodyDefinition {
        AntibodyDefinition(name: name, target: target, clone: clone, host: host,
                           isotype: isotype, vendor: vendor, catalog: catalog,
                           inventoryLinks: inventoryLinks, extra: fields)
    }

    init(id: UUID = UUID(), name: String, target: String = "", clone: String = "",
         host: String = "", isotype: String = "", vendor: String = "",
         catalog: String = "", inventoryLinks: [String] = [], isLocal: Bool = false,
         extra: FieldBag? = nil) {
        self.id = id; self.name = name; self.target = target; self.clone = clone
        self.host = host; self.isotype = isotype; self.vendor = vendor
        self.catalog = catalog; self.inventoryLinks = inventoryLinks; self.isLocal = isLocal
        self.extra = extra
    }

    init(_ d: AntibodyDefinition) {
        self.init(name: d.name, target: d.target, clone: d.clone, host: d.host,
                  isotype: d.isotype, vendor: d.vendor, catalog: d.catalog,
                  inventoryLinks: d.inventoryLinks,
                  extra: d.extra.isEmpty ? nil : d.extra)
    }
}

struct ItemRecord: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var abNo: String
    var definitionName: String = ""
    var vialName: String = ""
    var lot: String = ""
    var concentration: String = ""
    var location: String = ""
    var box: String = ""
    var owner: String = ""
    var status: String = ""
    var notes: String = ""
    var receivedAt: Date? = nil
    var isLocal: Bool = false
    /// Last time a scan confirmed this vial physically exists.
    var lastConfirmedAt: Date? = nil
    var lastScanID: UUID? = nil
    /// Every other column of the imported row. Optional for the same decoding
    /// reason as `DefRecord.extra`.
    var extra: FieldBag? = nil

    var fields: FieldBag { extra ?? FieldBag() }

    var core: InventoryItem {
        InventoryItem(abNo: abNo, definitionName: definitionName, vialName: vialName,
                      lot: lot, concentration: concentration, location: location,
                      box: box, owner: owner, extra: fields)
    }

    init(id: UUID = UUID(), abNo: String, definitionName: String = "", vialName: String = "",
         lot: String = "", concentration: String = "", location: String = "", box: String = "",
         owner: String = "", status: String = "", notes: String = "", receivedAt: Date? = nil,
         isLocal: Bool = false, lastConfirmedAt: Date? = nil, lastScanID: UUID? = nil,
         extra: FieldBag? = nil) {
        self.id = id; self.abNo = abNo; self.definitionName = definitionName
        self.vialName = vialName; self.lot = lot; self.concentration = concentration
        self.location = location; self.box = box; self.owner = owner; self.status = status
        self.notes = notes; self.receivedAt = receivedAt; self.isLocal = isLocal
        self.lastConfirmedAt = lastConfirmedAt; self.lastScanID = lastScanID
        self.extra = extra
    }

    init(_ i: InventoryItem) {
        // `Ab Status` lives in the bag as an imported column and in `status` as
        // an editable one; seed the editable copy so the app shows it before
        // anybody touches it.
        self.init(abNo: i.abNo, definitionName: i.definitionName, vialName: i.vialName,
                  lot: i.lot, concentration: i.concentration, location: i.location,
                  box: i.box, owner: i.owner, status: i.status,
                  notes: i.receivingNotes,
                  extra: i.extra.isEmpty ? nil : i.extra)
    }

    var displayName: String {
        if !vialName.isEmpty { return vialName }
        if !definitionName.isEmpty { return definitionName }
        return "AbNo \(abNo)"
    }
}

// MARK: - Scan log

enum ScanOutcome: String, Codable, CaseIterable {
    /// Operator confirmed the vial is what the app said it was.
    case confirmed
    /// Parked for a human to look at later. Always keeps its clip.
    case review
    /// A new vial was received into inventory from this scan.
    case received
    /// Operator rejected the reading.
    case rejected
    /// Recorded but not acted on (hands-free pass with nothing to commit).
    case logged

    var label: String {
        switch self {
        case .confirmed: return "Confirmed"
        case .review:    return "Needs review"
        case .received:  return "Received"
        case .rejected:  return "Rejected"
        case .logged:    return "Logged"
        }
    }
}

struct ScanRecord: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var date: Date = Date()
    var verdict: String
    var outcome: ScanOutcome
    var abNo: String? = nil
    var definitionName: String? = nil
    var catalog: String? = nil
    var lot: String? = nil
    var vendor: String? = nil
    var clone: String? = nil
    var barcode: String? = nil
    // Label facts that do not decide identity but are still what the scan read.
    // The confirm path used to drop them, so a freshly-read expiry or storage
    // temperature survived nowhere once the vial was already on record.
    var concentration: String? = nil
    var volume: String? = nil
    var isotype: String? = nil
    var expiry: String? = nil
    var storage: String? = nil
    var score: Double = 0
    var margin: Double = 0
    var reasons: [String] = []
    var framesUsed: Int = 0
    var lotNotOnRecord: Bool = false
    /// Filename inside the clips directory, when the operator kept the video.
    var clipFile: String? = nil
    var note: String = ""
    /// Free text the operator typed on the result sheet for a kept clip. Kept
    /// apart from `note`, which a review resolution overwrites.
    var clipNote: String? = nil
    var auditID: UUID? = nil
    var resolvedAt: Date? = nil
    /// Set only on a rejected scan where the operator identified the real
    /// vial. Nil either means the scan wasn't rejected, or it was rejected
    /// with the truth unknown - "don't know" has to stay a real answer, not
    /// something indistinguishable from a scan nobody ever corrected.
    var trueAbNo: String? = nil
    /// Set instead of `trueAbNo` when the operator recognised the antibody
    /// but not a specific inventory vial - they picked a definition, not a
    /// vial, so only the catalog number is known ground truth.
    var trueCatalog: String? = nil

    var verdictValue: Verdict { Verdict(rawValue: verdict) ?? .notFound }

    var title: String {
        if let definitionName, !definitionName.isEmpty { return definitionName }
        if let catalog, !catalog.isEmpty { return "Cat \(catalog)" }
        return "Unreadable label"
    }

    var subtitle: String {
        var bits: [String] = []
        if let abNo { bits.append("AbNo \(abNo)") }
        if let lot, !lot.isEmpty { bits.append("Lot \(lot)") }
        if bits.isEmpty, let catalog { bits.append("Cat \(catalog)") }
        // A rejection without its correction visible in the list is a rejection
        // nobody will click into, and this is the data the labelling session
        // exists to produce.
        if let trueAbNo { bits.append("→ AbNo \(trueAbNo)") }
        else if let trueCatalog { bits.append("→ Cat \(trueCatalog)") }
        return bits.joined(separator: " · ")
    }
}

// MARK: - Inventory confirmation (audit)

enum AuditScope: String, Codable, CaseIterable {
    case box, location, owner, everything
    var label: String {
        switch self {
        case .box: return "Box"
        case .location: return "Location"
        case .owner: return "Owner"
        case .everything: return "Whole inventory"
        }
    }
}

struct AuditFinding: Codable, Hashable, Identifiable {
    var id: UUID = UUID()
    var abNo: String
    var at: Date = Date()
    /// `scan` when the camera resolved it, `manual` when ticked by hand.
    var method: String = "scan"
    var scanID: UUID? = nil
}

struct AuditExtra: Codable, Hashable, Identifiable {
    var id: UUID = UUID()
    var label: String
    var catalog: String? = nil
    var lot: String? = nil
    var scanID: UUID? = nil
    var at: Date = Date()
}

struct AuditSession: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String
    var scope: AuditScope
    var scopeValue: String
    var startedAt: Date = Date()
    var finishedAt: Date? = nil
    /// AbNos that should be in the drawer when this session began.
    var expected: [String]
    var found: [AuditFinding] = []
    /// Vials scanned during the session that were not expected here.
    var extras: [AuditExtra] = []

    var isOpen: Bool { finishedAt == nil }
    var foundSet: Set<String> { Set(found.map(\.abNo)) }
    var missing: [String] { expected.filter { !foundSet.contains($0) } }
    var progress: Double {
        expected.isEmpty ? 1 : Double(foundSet.intersection(expected).count) / Double(expected.count)
    }
    var title: String {
        scope == .everything ? "Whole inventory" : "\(scope.label) \(scopeValue)"
    }
}

// MARK: - Field provenance
//
// A receiving form prefilled from a scan is a mix of certainties: a barcode
// decode is ground truth, an OCR read is a guess that is usually right, and a
// remembered default is neither — it is just what was true last time. The
// operator needs to see which is which at a glance, without the form turning
// into a spreadsheet of confidence scores.
enum FieldSource: String {
    case barcode, ocr, remembered

    var glyph: String {
        switch self {
        case .barcode:    return "barcode.viewfinder"
        case .ocr:        return "text.viewfinder"
        case .remembered: return "clock.arrow.circlepath"
        }
    }
    var label: String {
        switch self {
        case .barcode:    return "From barcode"
        case .ocr:        return "Read from label"
        case .remembered: return "Last used"
        }
    }
}

// MARK: - Settings

struct AppSettings: Codable {
    /// Hands-free: keep scanning, speak the result, commit without a tap.
    var handsFree: Bool = false
    var speak: Bool = true
    /// Seconds a hands-free result stays on screen before it is committed.
    var handsFreeDelay: Double = 3.0
    /// Only auto-commit at or above this confidence. `.certain` means barcode only.
    var autoCommitVerdict: String = Verdict.confident.rawValue
    /// Anything below the auto-commit bar goes to the review queue with its clip
    /// instead of stopping the line.
    var handsFreeParksUncertain: Bool = true
    /// Keep the clip for every scan, not just parked ones.
    var alwaysKeepClips: Bool = false
    var clipRetentionDays: Int = 30
    var haptics: Bool = true
    var torchOnByDefault: Bool = false
    var defaultOwner: String = ""
    var defaultLocation: String = ""
    var defaultBox: String = ""
    /// The box currently being worked through. Every vial confirmed while this
    /// is set gets stamped with it, which is the only realistic way the Box
    /// column ever gets filled in: it is 11% populated in the Airtable export
    /// and nobody is going to backfill 120 rows by hand.
    var sessionBox: String = ""
    /// When `sessionBox` was set. A box left over from last week must not
    /// silently re-file today's vials, so `AppStore.activeSessionBox` ignores
    /// anything older than `sessionBoxTTL`.
    var sessionBoxSetAt: Date? = nil
    /// Camera optics and focus tracking
    var preferUltraWideZoom: Bool = true
    var autoTextFocus: Bool = true
    var showTextTrackingHUD: Bool = true
    /// Engine knobs, surfaced because the right values are lab-dependent.
    var maxOCRFrames: Int = 24
    /// The floor on how long a scan runs before any answer is allowed to count.
    /// The knob people actually reach for: too low and the scanner answers
    /// before the vial has been turned, too high and every scan feels slow.
    var minScanSeconds: Double = 1.0
    var maxScanSeconds: Double = Self.maxScanDefault
    /// Faces of the label that must be read before a text verdict settles. Set
    /// to 1 for a lab whose labels carry everything on one side.
    var minDistinctViews: Int = 2
    var roiInset: Double = 0.15

    var autoCommit: Verdict { Verdict(rawValue: autoCommitVerdict) ?? .confident }

    /// Anchored to the physical task, see README "When a scan stops".
    static let maxScanDefault: Double = 9
    /// Matches the Settings slider's lower bound.
    static let maxScanFloor: Double = 5

    /// Repairs a stored value the current UI could not have produced.
    ///
    /// Settings saved before the deadline moved to 9 s still hold the old 4 s,
    /// and `engineConfig` used to rescue that to the 5 s floor. Five seconds is
    /// short of the 1.5 rotations a wrapped label needs, so those scans ended
    /// with part of the label never turned into view. A value below what the
    /// slider can reach was never chosen, so take the default instead.
    func migrated() -> AppSettings {
        guard maxScanSeconds < Self.maxScanFloor else { return self }
        var copy = self
        copy.maxScanSeconds = Self.maxScanDefault
        return copy
    }

    func engineConfig() -> EngineConfig {
        var c = EngineConfig()
        // Repair rather than clamp: a stored 4 s pinned to the 5 s floor is the
        // bug `migrated()` exists to fix, and clamping here reintroduced it for
        // any caller that reached the engine without the load-time repair.
        c.maxScanDuration = migrated().maxScanSeconds
        c.minScanDuration = max(0, min(minScanSeconds, c.maxScanDuration - 0.5))
        c.hardScanCeiling = c.maxScanDuration
        let inset = min(max(roiInset, 0), 0.4)
        c.regionOfInterest = CGRect(x: inset, y: inset * 1.3,
                                    width: 1 - inset * 2, height: 1 - inset * 2.6)
        return c
    }
}

// Label facts the fixed schema has no column for - size, expiry, storage temp -
// live in the extra bag, which round-trips through CSV export as its own column.
let colVolume = "Initial Volume"
let colExpiry = "Expiration Date"
let colStorage = "Storage Temperature"
let colReceivingNotes = "Receiving Notes"

/// One editable column in a form: what to call it on screen, and the printed
/// header(s) it reads and writes. A few Airtable exports contain both a source
/// field and a copy/formula of that field. The first non-empty candidate wins,
/// so editing a value updates the column that actually carried the import.
struct FormColumn: Hashable {
    let title: String
    let columns: [String]

    init(title: String, column: String, aliases: [String] = []) {
        self.title = title
        self.columns = [column] + aliases
    }

    var column: String { columns[0] }
}

// Columns the base carries that the fixed schema has no named slot for. Listing
// them here rather than in each sheet is what keeps the receiving form and the
// edit form from drifting apart. Rollups and links (Validations, Panel
// Configurations, Staining Log, Conjugations, Child Vial) are deliberately
// absent: they are computed in Airtable, not typed at a freezer, and they still
// show in the read-only carried-fields list.
let vialFormColumns: [FormColumn] = [
    FormColumn(title: "Category", column: "Ab Category"),
    // The bundled export has an empty `Conjugate` field beside populated
    // `Conjugate copy` values. Preserve whichever source column is carrying
    // the value instead of creating a second, misleading edit.
    FormColumn(title: "Conjugate", column: "Conjugate", aliases: ["Conjugate copy"]),
    FormColumn(title: "Amount", column: "Amount"),
    FormColumn(title: "Formulation", column: "Formulation"),
    FormColumn(title: "Current volume", column: "Current Volume"),
    FormColumn(title: "Dilute to 2 ug/ml", column: "Dilute to 2 ug/ml"),
    FormColumn(title: "Volume from parent", column: "Volume Taken From Parent"),
    FormColumn(title: "Total conjugated", column: "Total Volume Conjugated"),
    FormColumn(title: "Parent vial", column: "Parent Vial"),
    FormColumn(title: "Projects", column: "Projects"),
]

let definitionFormColumns: [FormColumn] = [
    FormColumn(title: "Reactivity", column: "Reactivity Vendor"),
    FormColumn(title: "Reactivity cited", column: "Reactivity Cited"),
    FormColumn(title: "Product website", column: "Product Website"),
    FormColumn(title: "Price", column: "Price"),
    FormColumn(title: "Gene database", column: "Gene Database"),
    FormColumn(title: "Protein database", column: "Protein Database"),
]

extension Optional where Wrapped == FieldBag {
    func value(_ name: String) -> String { value([name]) }

    func value(_ names: [String]) -> String {
        for name in names {
            if let value = self?[name], !value.isEmpty { return value }
        }
        // Keep an imported empty field visible to the binding. This matters
        // when the row has the header but no value yet.
        for name in names {
            if let value = self?[name] { return value }
        }
        return ""
    }

    mutating func set(_ name: String, _ value: String) {
        set([name], value)
    }

    mutating func set(_ names: [String], _ value: String) {
        guard !names.isEmpty else { return }
        var bag = self ?? FieldBag()
        let keys = names.map(normKey)
        let matching = bag.entries.indices.filter { keys.contains(normKey(bag.entries[$0].name)) }
        // Prefer the non-empty alias. In the shipped inventory this selects
        // `Conjugate copy` over the empty `Conjugate` column.
        let target = matching.first(where: { !bag.entries[$0].value.isEmpty }) ?? matching.first
        if let i = target {
            bag.entries[i] = FieldBag.Entry(name: bag.entries[i].name, value: value)
        } else if !value.isEmpty {
            bag.entries.append(FieldBag.Entry(name: names[0], value: value))
        }
        self = bag.isEmpty ? nil : bag
    }
}

extension ItemRecord {
    /// `Receiving Notes` is an imported Airtable column, while `notes` is the
    /// app's convenient fixed property. Keep the two in step before a form
    /// sends the row to the store, otherwise an edited note is lost on export.
    mutating func syncReceivingNotes() {
        extra.set(colReceivingNotes, notes)
    }
}

// MARK: - Clip sidecar

/// Written next to a kept clip as `<clipname>.json` so the video still means
/// something after it is copied off the phone.
struct ClipSidecar: Codable {
    let clip: String
    /// ISO8601 UTC.
    let recordedAt: String
    /// Verbatim operator note, tokens and all.
    let note: String
    /// Whatever `parseClipNote` read off the front of `note`.
    let expect: ClipExpectationDTO?
}

/// Codable mirror of `VialCore.ClipExpectation`: the engine's parser stays
/// dependency-free, so the wire shape lives here.
struct ClipExpectationDTO: Codable, Equatable {
    var catalog: String?
    var lot: String?
    var clone: String?
    var verdict: String?

    init(_ e: ClipExpectation) {
        catalog = e.catalog; lot = e.lot; clone = e.clone; verdict = e.verdict
    }
}
