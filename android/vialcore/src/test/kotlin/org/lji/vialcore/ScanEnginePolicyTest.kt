package org.lji.vialcore

private class PolicyFrame(override val timestamp: Double, private val text: String = "", private val codes: List<BarcodePayload> = emptyList()) : VialFrame {
    override val sharpness = 1.0
    override fun detectBarcodes(roi: NormRect?) = codes
    override fun recognizeText(roi: NormRect?) = if (text.isEmpty()) emptyList() else listOf(TextObservation(text, 1.0f))
}

object ScanEnginePolicyTest {
    @JvmStatic fun main(args: Array<String>) {
        val def = AntibodyDefinition("Known", "Target", "CloneX", "Mouse", "IgG", "Vendor", "999999", emptyList())
        val item = InventoryItem("1", "Known", "Vial", "LOT1234", "", "", "", "")
        val catalog = Catalog(listOf(def), listOf(item))
        fun engine(c: Catalog = catalog) = ScanEngine(c, EngineConfig().apply {
            maxScanDuration = 5.0; minOCRInterval = 0.0; barcodeEveryNthFrame = 1
        })
        val unknown = engine(Catalog(emptyList(), emptyList()))
        for (i in 0..4) check(unknown.ingest(PolicyFrame(i.toDouble(), "CAT: 888888")) is ScanState.Scanning)
        check(unknown.ingest(PolicyFrame(5.0)) is ScanState.Finished)
        val noisy = engine(Catalog(emptyList(), emptyList()))
        for (i in 0..4) check(noisy.ingest(PolicyFrame(i.toDouble(), "token$i more$i")) is ScanState.Scanning)
        check(noisy.ingest(PolicyFrame(5.0)) is ScanState.Finished)
        val barcode = BarcodePayload("999999 LOT1234", "test")
        val known = engine()
        check(known.ingest(PolicyFrame(0.0, codes = listOf(barcode))) is ScanState.Scanning)
        val confirmed = known.ingest(PolicyFrame(1.0, codes = listOf(barcode))) as ScanState.Finished
        check(confirmed.result.canAutoConfirm)
        val deadline = engine()
        deadline.ingest(PolicyFrame(0.0, codes = listOf(barcode)))
        val timedOut = deadline.ingest(PolicyFrame(5.0)) as ScanState.Finished
        check(timedOut.result.isHit && !timedOut.result.canAutoConfirm)
        val manual = engine()
        manual.ingest(PolicyFrame(0.0, codes = listOf(barcode)))
        check(!manual.finish().canAutoConfirm)
        val productOnly = engine()
        for (i in 0..4) check(productOnly.ingest(PolicyFrame(i.toDouble(), "CAT: 999999")) is ScanState.Scanning)
        check((productOnly.ingest(PolicyFrame(5.0)) as ScanState.Finished).result.vial == null)
        val duplicateInFrame = engine().apply { config.minScanDuration = 0.0 }
        check(duplicateInFrame.ingest(PolicyFrame(0.0, codes = listOf(barcode, barcode))) is ScanState.Scanning)
        val lateField = engine()
        lateField.ingest(PolicyFrame(0.0, "CAT: 999999"))
        lateField.ingest(PolicyFrame(4.0, "LOT: LOT1234"))
        check(lateField.finish().fields[LabelField.LOT] == "LOT1234")
        val text = engine()
        var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
        for (i in 0..5) state = text.ingest(PolicyFrame(i * 0.3, "CAT: 999999 LOT: LOT1234"))
        check(state is ScanState.Finished && state.result.canAutoConfirm)
        println("ScanEnginePolicyTest: 9 passed")
    }
}
