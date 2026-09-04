package org.lji.vialcore

/**
 * Small deterministic seam test for the scan stopping policy. This is a plain
 * main rather than a JUnit test so the same source can run in the laptop parity
 * harness without Android or a test runner.
 */
private class StaticFrame(
    override val timestamp: Double,
    private val observations: List<TextObservation>,
    private val barcodes: List<BarcodePayload> = emptyList(),
) : VialFrame {
    override val sharpness: Double = 1.0

    override fun detectBarcodes(roi: NormRect?): List<BarcodePayload> = barcodes

    override fun recognizeText(roi: NormRect?): List<TextObservation> = observations
}

object ScanEnginePolicyTest {
    @JvmStatic
    fun main(args: Array<String>) {
        unresolvedStaticFrontWaitsForHarvestWindow()
        unresolvedScanUsesRemainingTimeWhileTokensArrive()
        ambiguousScanWaitsForHarvestWindow()
        offRecordLotWaitsForHarvestWindow()
        unknownBarcodeWaitsForHarvestWindow()
        knownBarcodeStillTakesFastPath()
        knownCataloguedIdentityStillTakesFastPath()
        println("ScanEnginePolicyTest: 7 passed")
    }

    private fun unresolvedStaticFrontWaitsForHarvestWindow() {
        val config = EngineConfig().also {
            it.barcodeEveryNthFrame = 0
            it.minOCRInterval = 0.0
            it.maxOCRFrames = 100
            it.earlyExitMinFrames = 100
            it.minHarvestDuration = 5.0
        }
        val engine = ScanEngine(Catalog(emptyList(), emptyList()), config)
        val front = listOf(TextObservation("CAT: 999999", 1.0f))

        var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
        for (second in 0..4) {
            state = engine.ingest(StaticFrame(second.toDouble(), front))
        }
        check(state is ScanState.Scanning) {
            "unknown static front ended at first deadline: $state"
        }

        state = engine.ingest(StaticFrame(6.5, front))
        check(state is ScanState.Finished) {
            "saturated unknown front did not finish after harvest window: $state"
        }
        val finished = state as? ScanState.Finished
            ?: error("state changed after finished assertion: $state")
        check(finished.result.verdict == Verdict.NOT_FOUND)
    }

    private fun unresolvedScanUsesRemainingTimeWhileTokensArrive() {
        val config = EngineConfig().also {
            it.barcodeEveryNthFrame = 0
            it.minOCRInterval = 0.0
            it.maxOCRFrames = 100
            it.earlyExitMinFrames = 100
            it.minHarvestDuration = 5.0
            it.saturationFrames = 2
            it.hardScanCeiling = 8.0
            it.graceExtension = 2.0
        }
        val engine = ScanEngine(Catalog(emptyList(), emptyList()), config)

        var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
        for (second in 0..6) {
            state = engine.ingest(
                StaticFrame(second.toDouble(), listOf(TextObservation("token$second value", 1.0f)))
            )
        }
        check(state is ScanState.Scanning) {
            "unresolved scan stopped while new tokens were arriving: $state"
        }

        state = engine.ingest(StaticFrame(8.0, listOf(TextObservation("token8 value", 1.0f))))
        check(state is ScanState.Finished) {
            "unresolved scan did not stop at the hard ceiling: $state"
        }
    }

    private fun ambiguousScanWaitsForHarvestWindow() {
        val definitions = listOf(
            definition(catalog = "999999", clone = "C1"),
            definition(catalog = "999999", clone = "C2"),
        )
        val config = harvestConfig()
        val engine = ScanEngine(Catalog(definitions, emptyList()), config)
        val front = listOf(TextObservation("CAT: 999999", 1.0f))

        var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
        for (second in 0..4) state = engine.ingest(StaticFrame(second.toDouble(), front))
        check(state is ScanState.Scanning) {
            "ambiguous identity ended before the harvest window: $state"
        }

        state = engine.ingest(StaticFrame(6.5, front))
        check(state is ScanState.Finished) {
            "ambiguous identity did not finish after saturation: $state"
        }
        val finished = state as ScanState.Finished
        check(finished.result.verdict == Verdict.AMBIGUOUS)
    }

    private fun offRecordLotWaitsForHarvestWindow() {
        val definition = definition(catalog = "999999", clone = "C1")
        val item = InventoryItem(
            abNo = "1", definitionName = "Known", vialName = "Known vial",
            lot = "ONFILE", concentration = "", location = "", box = "", owner = "",
        )
        val config = harvestConfig()
        val engine = ScanEngine(Catalog(listOf(definition), listOf(item)), config)
        val front = listOf(TextObservation("CAT: 999999 LOT: NEW1234", 1.0f))

        var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
        for (second in 0..4) state = engine.ingest(StaticFrame(second.toDouble(), front))
        check(state is ScanState.Scanning) {
            "off-record lot ended before the harvest window: $state"
        }

        state = engine.ingest(StaticFrame(6.5, front))
        check(state is ScanState.Finished) {
            "off-record lot did not finish after saturation: $state"
        }
        val finished = state as ScanState.Finished
        check(finished.result.verdict == Verdict.CONFIDENT)
        check(finished.result.lotNotOnRecord)
    }

    private fun unknownBarcodeWaitsForHarvestWindow() {
        val config = harvestConfig().also { it.barcodeEveryNthFrame = 1 }
        val engine = ScanEngine(Catalog(emptyList(), emptyList()), config)
        val barcode = BarcodePayload("999999 NEWLOT", "test")
        val front = listOf(TextObservation("CAT: 999999", 1.0f))

        var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
        for (second in 0..5) {
            state = engine.ingest(StaticFrame(second.toDouble(), front, listOf(barcode)))
        }
        check(state is ScanState.Finished) {
            "unknown barcode did not finish after the harvest window: $state"
        }
        val finished = state as ScanState.Finished
        check(finished.result.verdict == Verdict.NOT_FOUND)
    }

    private fun knownBarcodeStillTakesFastPath() {
        val definition = definition(catalog = "999999", clone = "C1")
        val item = InventoryItem(
            abNo = "1", definitionName = "Known", vialName = "Known vial",
            lot = "ONFILE", concentration = "", location = "", box = "", owner = "",
        )
        val config = harvestConfig().also {
            it.barcodeEveryNthFrame = 1
            it.barcodeConfirmations = 2
            it.minScanDuration = 1.0
        }
        val engine = ScanEngine(Catalog(listOf(definition), listOf(item)), config)
        val barcode = BarcodePayload("999999 ONFILE", "test")

        val first = engine.ingest(StaticFrame(0.0, emptyList(), listOf(barcode)))
        check(first is ScanState.Scanning) { "single barcode decode ended the scan: $first" }
        val second = engine.ingest(StaticFrame(1.0, emptyList(), listOf(barcode)))
        check(second is ScanState.Finished) { "confirmed catalogued barcode did not finish: $second" }
        val finished = second as ScanState.Finished
        check(finished.result.verdict == Verdict.CERTAIN)
    }

    private fun knownCataloguedIdentityStillTakesFastPath() {
        val definition = definition(catalog = "999999", clone = "C1")
        val config = EngineConfig().also {
            it.barcodeEveryNthFrame = 0
            it.minOCRInterval = 0.0
            it.maxOCRFrames = 100
            it.earlyExitMinFrames = 4
            it.minScanDuration = 1.0
            it.minHarvestDuration = 5.0
            it.settleConfirmations = 2
        }
        val engine = ScanEngine(Catalog(listOf(definition), emptyList()), config)
        val front = listOf(TextObservation("CAT: 999999", 1.0f))
        var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
        for (index in 0..6) {
            state = engine.ingest(StaticFrame(index * 0.2, front))
        }
        check(state is ScanState.Finished) { "catalogued identity did not take fast path: $state" }
        val finished = state as? ScanState.Finished
            ?: error("state changed after finished assertion: $state")
        check(finished.result.verdict == Verdict.CONFIDENT)
    }

    private fun harvestConfig() = EngineConfig().also {
        it.barcodeEveryNthFrame = 0
        it.minOCRInterval = 0.0
        it.maxOCRFrames = 100
        it.earlyExitMinFrames = 100
        it.minHarvestDuration = 5.0
    }

    private fun definition(catalog: String, clone: String) = AntibodyDefinition(
        name = "Known",
        target = "Target",
        clone = clone,
        host = "Mouse",
        isotype = "IgG",
        vendor = "Vendor",
        catalog = catalog,
        inventoryLinks = emptyList(),
    )
}
