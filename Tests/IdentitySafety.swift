import Foundation

@main
struct IdentitySafety {
    static func main() {
        let def = AntibodyDefinition(name: "Known", target: "Target", clone: "CloneX", host: "Mouse", isotype: "IgG", vendor: "Vendor", catalog: "999999", inventoryLinks: [])
        func item(_ id: String, _ lot: String) -> InventoryItem {
            InventoryItem(abNo: id, definitionName: "Known", vialName: id, lot: lot, concentration: "", location: "", box: "", owner: "")
        }
        func result(_ items: [InventoryItem], _ raw: String, _ standard: EvidenceStandard = .corroborated, _ repeats: Int = 2) -> ScanResult {
            var ev = EvidenceBuffer()
            for _ in 0..<repeats { ev.addBarcode(BarcodePayload(raw: raw, symbology: "test")) }
            return Matcher(catalog: Catalog(definitions: [def], items: items)).resolve(ev, standard: standard)
        }
        let rows = [item("16", "LOT001"), item("51", "LOT001"), item("40", "LOT006")]
        let duplicate = result(rows, "999999 LOT001")
        precondition(duplicate.vial == nil && duplicate.otherVials.count == 2 && !duplicate.canAutoConfirm)
        let noLot = result([rows[0]], "999999")
        precondition(noLot.vial == nil && noLot.otherVials.count == 1 && !noLot.canAutoConfirm)
        let exact = result(rows, "999999 LOT006")
        precondition(exact.vial?.abNo == "40" && exact.canAutoConfirm)
        let fuzzy = result(rows, "999999 LOT002")
        precondition(fuzzy.vial == nil && !fuzzy.canAutoConfirm)
        let once = result(rows, "999999 LOT006", .best, 1)
        precondition(once.isHit && !once.canAutoConfirm)
        let unconfirmed = result(rows, "999999 LOT006", .corroborated, 1)
        precondition(!unconfirmed.canAutoConfirm)
        var ev = EvidenceBuffer()
        for _ in 0..<2 {
            ev.addBarcode(BarcodePayload(raw: "999999 LOT001", symbology: "test"))
            ev.addBarcode(BarcodePayload(raw: "999999 LOT006", symbology: "test"))
        }
        let conflict = Matcher(catalog: Catalog(definitions: [def], items: rows)).resolve(ev, standard: .corroborated)
        precondition(conflict.verdict == .ambiguous && conflict.vial == nil)
        let dupDef = Matcher(catalog: Catalog(definitions: [def, def], items: rows))
        var oneCode = EvidenceBuffer()
        oneCode.addBarcode(BarcodePayload(raw: "999999 LOT006", symbology: "test"))
        precondition(dupDef.resolve(oneCode).verdict == .ambiguous)
        print("IdentitySafety Swift: 8 passed")
    }
}
