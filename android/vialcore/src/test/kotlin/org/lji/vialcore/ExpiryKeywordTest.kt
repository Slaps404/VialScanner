package org.lji.vialcore

/** `Best before` / `Use by` are printed instead of `Exp.` by several vendors. */
object ExpiryKeywordTest {
    @JvmStatic fun main(args: Array<String>) {
        fun expiry(vararg lines: String) =
            extractFieldsFromLines(lines.toList())[LabelField.EXPIRY]?.value

        check(expiry("Best: 11/2026") == "11/2026")
        check(expiry("Best Before 11/2026") == "11/2026")
        check(expiry("Best by: 11/2026") == "11/2026")
        check(expiry("Use by 11/2026") == "11/2026")
        check(expiry("Exp: 11/2026") == "11/2026")
        check(expiry("Expiry Date: 2027-05-31") == "2027-05-31")
        // Separators are `-`, `/` or `.`, never a bare space: a trailing token
        // used to be swallowed as a day, reading `2022-08 10` as a full date.
        check(expiry("Best before 2022-08 10") == "2022-08")
        check(expiry("Exp: 11-2026") == "11-2026")
        check(expiry("EXP 05.2027") == "05.2027")
        check(expiry("Exp 2027-05-31") == "2027-05-31")
        // `Research Use Only` is on nearly every label; it must not trip `Use by`.
        check(expiry("Research Use Only 11/2026") == null)

        // The keyword still needs a date-shaped value after it.
        check(expiry("Best quality reagent") == null)
        check(expiry("Lot 11/2026") == null)

        // The leading `-` is the meaning, not punctuation. Scan 706d8e18 read
        // `Store: -20C` off the tube and committed `20C`.
        fun storage(vararg lines: String) =
            extractFieldsFromLines(lines.toList())[LabelField.STORAGE]?.value
        check(storage("Store: -20C") == "-20C")
        check(storage("Store at -20C") == "-20C")
        check(storage("Storage: -20 C") == "-20 C")
        check(storage("Store -20\u00B0C") == "-20\u00B0C")
        check(storage("Store: 4C") == "4C")

        // Pixel scan ade23819 reached this complete label face. These are the
        // recognizer's exact lines, so a failure here belongs to extraction.
        val invitrogen = extractFieldsFromLines(listOf(
            "2033-09-23",
            "Store at: 2-8°C",
            "100 ug (0.5 mg/ml)",
        ))
        check(invitrogen[LabelField.EXPIRY]?.value == "2033-09-23")
        check(invitrogen[LabelField.STORAGE]?.value == "2-8°C")
        check(invitrogen[LabelField.CONCENTRATION]?.value == "0.5 mg/ml")

        // Pixel scan 30432fd1 read the unit cleanly but attached a stray glyph
        // to the number. The unit makes this safe to recover without a keyword.
        val bioLegend = extractFieldsFromLines(listOf("E100 uL"))
        check(bioLegend[LabelField.VOLUME]?.value == "100 uL")

        // iPhone scans ECEA2A24, 8DD154C7, and 91D78534 reached these values
        // without preserving every short field keyword.
        val cst = extractFieldsFromLines(listOf(
            "1.1mg/ml",
            ": 11/2026",
        ))
        check(cst[LabelField.CONCENTRATION]?.value == "1.1mg/ml")
        check(cst[LabelField.EXPIRY]?.value == "11/2026")

        val rareCyte = extractFieldsFromLines(listOf(
            "50 uL",
            "2022-08-18",
            "-25 to -15°C",
        ))
        check(rareCyte[LabelField.VOLUME]?.value == "50 uL")
        check(rareCyte[LabelField.EXPIRY]?.value == "2022-08-18")
        check(rareCyte[LabelField.STORAGE]?.value == "-25 to -15°C")

        val gata3 = extractFieldsFromLines(listOf("1mg/ml", "-20C"))
        check(gata3[LabelField.CONCENTRATION]?.value == "1mg/ml")
        check(gata3[LabelField.STORAGE]?.value == "-20C")

        // IMG_4070 hallucinated this from clone EPR4877. A vial cannot contain
        // a near-solid 977 mg/ml antibody solution.
        check(extractFieldsFromLines(listOf("0977 mg/ml"))[LabelField.CONCENTRATION] == null)

        // A shape without its keyword needs two independent frames. One OCR
        // hallucination must not become a field at the scan deadline.
        var evidence = EvidenceBuffer()
        evidence.addFrame(listOf(TextObservation("10.5 mg/ml", 1f)))
        check(evidence.vote(LabelField.CONCENTRATION) == null)
        evidence.addFrame(listOf(TextObservation("10.5 mg/ml", 1f)))
        check(evidence.vote(LabelField.CONCENTRATION)?.first == "10.5 mg/ml")

        // Shapes do not combine unrelated OCR observations into a fake range.
        val separateLines = extractFieldsFromLines(listOf("Lot: 1", "-20C"))
        check(separateLines[LabelField.STORAGE]?.value == "-20C")
        check(extractFieldsFromLines(listOf("- 20C"))[LabelField.STORAGE]?.value == "-20C")

        var storageEvidence = EvidenceBuffer()
        storageEvidence.addFrame(listOf(TextObservation("-20C", 1f)))
        check(storageEvidence.vote(LabelField.STORAGE) == null)
        storageEvidence.addFrame(listOf(TextObservation("-20C", 1f)))
        check(storageEvidence.vote(LabelField.STORAGE)?.first == "-20C")
        println("ExpiryKeywordTest ok")
    }
}
