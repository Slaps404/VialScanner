package org.lji.vialcore

object IdentitySafetyTest {
    @JvmStatic fun main(args: Array<String>) {
        val def = AntibodyDefinition("Known", "Target", "CloneX", "Mouse", "IgG", "Vendor", "999999", emptyList())
        fun item(id: String, lot: String) = InventoryItem(id, "Known", id, lot, "", "", "", "")
        fun result(items: List<InventoryItem>, raw: String, standard: EvidenceStandard = EvidenceStandard.CORROBORATED, repeats: Int = 2): ScanResult {
            val ev = EvidenceBuffer()
            repeat(repeats) { ev.addBarcode(BarcodePayload(raw, "test")) }
            return Matcher(Catalog(listOf(def), items)).resolve(ev, standard)
        }
        val rows = listOf(item("16", "LOT001"), item("51", "LOT001"), item("40", "LOT006"))
        val duplicate = result(rows, "999999 LOT001")
        check(duplicate.vial == null && duplicate.otherVials.size == 2 && !duplicate.canAutoConfirm)
        val noLot = result(listOf(rows[0]), "999999")
        check(noLot.vial == null && noLot.otherVials.size == 1 && !noLot.canAutoConfirm)
        val exact = result(rows, "999999 LOT006")
        check(exact.vial?.abNo == "40" && exact.canAutoConfirm)
        val fuzzy = result(rows, "999999 LOT002")
        check(fuzzy.vial == null && !fuzzy.canAutoConfirm)
        val once = result(rows, "999999 LOT006", EvidenceStandard.BEST, 1)
        check(once.isHit && !once.canAutoConfirm)
        check(!result(rows, "999999 LOT006", EvidenceStandard.CORROBORATED, 1).canAutoConfirm)
        val ev = EvidenceBuffer()
        repeat(2) {
            ev.addBarcode(BarcodePayload("999999 LOT001", "test"))
            ev.addBarcode(BarcodePayload("999999 LOT006", "test"))
        }
        val conflict = Matcher(Catalog(listOf(def), rows)).resolve(ev, EvidenceStandard.CORROBORATED)
        check(conflict.verdict == Verdict.AMBIGUOUS && conflict.vial == null)
        val oneCode = EvidenceBuffer().apply { addBarcode(BarcodePayload("999999 LOT006", "test")) }
        check(Matcher(Catalog(listOf(def, def), rows)).resolve(oneCode).verdict == Verdict.AMBIGUOUS)
        println("IdentitySafety Kotlin: 8 passed")
    }
}
