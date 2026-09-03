package org.lji.vialcore

class DefinitionMatch(
    val definition: AntibodyDefinition,
    val score: Double,
    val reasons: List<String>,
)

enum class Verdict {
    /** Barcode decoded and resolved against the catalog. Effectively certain. */
    CERTAIN,

    /** Text evidence cleared both the absolute and margin thresholds. */
    CONFIDENT,

    /** Best candidate exists but did not clear thresholds - show, do not commit. */
    AMBIGUOUS,

    /** Nothing plausible. This vial is new to the database. */
    NOT_FOUND,
}

/**
 * How much the scan actually managed to read, independent of what it concluded.
 *
 * A verdict and the evidence behind it are different questions, and NOT_FOUND
 * conflates them: it is the answer both for "this label is legible and names a
 * product we do not stock" and for "the camera never read anything". Those two
 * deserve opposite handling - the first is a new vial to receive, the second is
 * a scan to retry - so the engine reports them apart.
 */
enum class EvidenceLevel {
    /** No barcode, and no text recognised in any frame. */
    NONE,

    /** Text was read, but nothing that identifies a product. */
    THIN,

    /** A barcode, a catalog number, or a clone backed by a second field. */
    SUFFICIENT,
}

class ScanResult(
    val verdict: Verdict,
    val definition: AntibodyDefinition?,
    val vial: InventoryItem?,
    /** Other vials of the same definition, when the lot could not disambiguate. */
    val otherVials: List<InventoryItem>,
    val score: Double,
    val margin: Double,
    val reasons: List<String>,
    /** A lot number was read confidently but matches none of the vials on record
     *  for this antibody: a new lot of a known product, not one of these vials. */
    val lotNotOnRecord: Boolean,
    val fields: Map<LabelField, String>,
    val barcode: BarcodePayload?,
    val framesUsed: Int,
    /** What the camera actually got, as distinct from what the matcher decided. */
    val evidenceLevel: EvidenceLevel,
) {
    val isHit: Boolean get() = verdict == Verdict.CERTAIN || verdict == Verdict.CONFIDENT

    /** Nothing was read at all. Not a result about a vial - a failed scan. */
    val readNothing: Boolean get() = evidenceLevel == EvidenceLevel.NONE
}

/**
 * How much corroboration the evidence must carry before a verdict counts.
 *
 * The two callers want different things. A verdict that ends the scan on its own
 * must be corroborated: it is being reached while the user is still rotating the
 * vial, and if it is wrong nobody gets the chance to notice. A verdict the user
 * asked for - "read what you have", or the duration cap - should use everything
 * available, because the alternative is not a better answer later, it is no
 * answer at all.
 */
enum class EvidenceStandard {
    /** Identity-bearing fields must be backed by more than one frame, and a
     *  barcode by more than one decode. */
    CORROBORATED,

    /** Whatever the buffer holds. */
    BEST;

    /** Frames a field must appear in to be usable at this standard. */
    fun minFrames(field: LabelField): Int {
        if (this != CORROBORATED) return 1
        return when (field) {
            LabelField.CATALOG, LabelField.LOT, LabelField.CLONE -> 2
            else -> 1
        }
    }

    val minBarcodeFrames: Int get() = if (this == CORROBORATED) 2 else 1
}

class MatchConfig(
    /** Absolute score a text match must clear. Non-matches in the sample set
     *  topped out at 4.8; real matches bottomed out at 8.0. */
    var minScore: Double = 7.0,
    /** The runner-up gap. A high score against a crowded field of near-identical
     *  catalog numbers is not a match - it is a coin flip. */
    var minMargin: Double = 2.5,
    var fieldWeights: Map<LabelField, Double> = mapOf(
        LabelField.CATALOG to 6.0,
        LabelField.CLONE to 4.0,
        LabelField.VENDOR to 3.0,
        LabelField.ISOTYPE to 1.5,
    ),
)

class Matcher(val catalog: Catalog, val config: MatchConfig = MatchConfig()) {

    fun resolve(ev: EvidenceBuffer, standard: EvidenceStandard = EvidenceStandard.BEST): ScanResult {
        val fields = LinkedHashMap<LabelField, String>()
        for (f in LabelField.entries) {
            ev.vote(f, standard.minFrames(f))?.let { fields[f] = it.first }
        }

        // ---- Tier 1: barcode ------------------------------------------------
        for (bc in ev.barcodes) {
            // One decode is one glimpse, and a DataMatrix in the tray behind the
            // vial being held decodes exactly as cleanly as the one on it.
            if (ev.frames(bc) < standard.minBarcodeFrames) continue
            val cat = bc.catalog ?: continue
            // A decoded payload is stronger than OCR. Promote it into the common
            // field map so receive/review screens do not show a blank or
            // contradictory catalog/lot while the reason line uses the barcode.
            fields[LabelField.CATALOG] = cat
            bc.lot?.takeIf { it.isNotEmpty() }?.let { fields[LabelField.LOT] = it }
            val level = evidenceLevel(ev, fields)
            val hits = catalog.definitionIndices(cat)
            val idx = hits.firstOrNull()
            if (idx != null) {
                val def = catalog.definitions[idx]
                val (vial, others, lotMiss) = pickVial(def, bc.lot ?: fields[LabelField.LOT])
                return ScanResult(
                    verdict = Verdict.CERTAIN, definition = def, vial = vial, otherVials = others,
                    score = 100.0, margin = 100.0,
                    reasons = listOf(
                        "barcode ${bc.symbology}: catalog $cat" +
                            (bc.lot?.let { ", lot $it" } ?: "")
                    ),
                    lotNotOnRecord = lotMiss, fields = fields, barcode = bc,
                    framesUsed = ev.frameCount, evidenceLevel = level,
                )
            }
            // Decoded cleanly but absent from the catalog: a definitive "new
            // vial", not an ambiguous read. Do not fall through to fuzzy text.
            return ScanResult(
                verdict = Verdict.NOT_FOUND, definition = null, vial = null, otherVials = emptyList(),
                score = 0.0, margin = 0.0,
                reasons = listOf("barcode ${bc.symbology} decoded catalog $cat - not in catalog"),
                lotNotOnRecord = false, fields = fields, barcode = bc,
                framesUsed = ev.frameCount, evidenceLevel = level,
            )
        }

        val level = evidenceLevel(ev, fields)

        // ---- Tier 2: exact catalog number from voted text -------------------
        fields[LabelField.CATALOG]?.let { cat ->
            val hits = catalog.definitionIndices(cat)
            if (hits.size == 1) {
                val def = catalog.definitions[hits[0]]
                val (vial, others, lotMiss) = pickVial(def, fields[LabelField.LOT])
                return ScanResult(
                    verdict = Verdict.CONFIDENT, definition = def, vial = vial, otherVials = others,
                    score = 50.0, margin = 50.0,
                    reasons = listOf("catalog # $cat matched exactly"),
                    lotNotOnRecord = lotMiss, fields = fields, barcode = null,
                    framesUsed = ev.frameCount, evidenceLevel = level,
                )
            }
        }

        // ---- Tier 2b: unique near-exact catalog number ----------------------
        //
        // Guarded by corroboration. Real catalog spaces are dense: BioLegend
        // 406601 and 406602 are different products, and this catalog alone holds
        // 13 pairs of numbers one edit apart. A near-match on digits therefore
        // proves nothing on its own, so it must be backed by the clone or target
        // - vendor is deliberately excluded, since every vial from a vendor
        // shares it. A false positive here puts the wrong antibody in a panel; a
        // false negative merely costs a manual lookup.
        val cat = fields[LabelField.CATALOG]
        val nearIdx = cat?.let { catalog.definitionIndexNearCatalog(it) }
        if (cat != null && nearIdx != null && corroborates(catalog.definitions[nearIdx], ev)) {
            val def = catalog.definitions[nearIdx]

            // Where the edit falls decides what it means. Vendors encode pack
            // size in the trailing character - BioLegend 406601 is the 50 ug vial
            // of the same clone that 406602 sells in bulk - so a difference there
            // is a different product, not a misread. A difference anywhere else
            // is OCR noise (`1103-01` for `0103-01`). Only the latter may name a
            // vial.
            // ...unless the trailing character we read is a letter over a digit
            // on file: pack codes are digits, so that is OCR, not a product.
            val read = normKey(cat)
            val onFile = normKey(def.catalog)
            val gRead = graphemes(read)
            val gFile = graphemes(onFile)
            val tailIsMisread = gRead.size == gFile.size &&
                gRead.zip(gFile).all { (r, f) -> r == f || letterForDigit(r, f) }
            if (!tailIsMisread && differsOnlyInLastCharacter(read, onFile)) {
                return ScanResult(
                    verdict = Verdict.AMBIGUOUS, definition = def, vial = null,
                    otherVials = catalog.inventoryFor(def), score = 20.0, margin = 0.0,
                    reasons = listOf(
                        "read catalog # $cat, closest on file is ${def.catalog} - " +
                            "same clone, but the trailing digit usually encodes pack size"
                    ),
                    lotNotOnRecord = false, fields = fields, barcode = null,
                    framesUsed = ev.frameCount, evidenceLevel = level,
                )
            }

            val (vial, others, lotMiss) = pickVial(def, fields[LabelField.LOT])
            return ScanResult(
                verdict = Verdict.CONFIDENT, definition = def, vial = vial, otherVials = others,
                score = 40.0, margin = 40.0,
                reasons = listOf("catalog # $cat -> ${def.catalog} (unique single-edit match)"),
                lotNotOnRecord = lotMiss, fields = fields, barcode = null,
                framesUsed = ev.frameCount, evidenceLevel = level,
            )
        }

        // ---- Tier 3: weighted fuzzy scoring over the stable token bag -------
        val stable = ev.stableTokens(2)
        val tokens = if (stable.size >= 8) stable else ev.allTokens
        val folded = tokens.mapTo(HashSet()) { ocrFold(it) }

        val ranked = ArrayList<DefinitionMatch>()
        for (def in catalog.definitions) {
            var score = 0.0
            val reasons = ArrayList<String>()
            fun consider(field: LabelField, value: String) {
                val w = config.fieldWeights[field] ?: return
                val k = normKey(value)
                if (k.length < 2) return
                if (k in tokens) {
                    score += w; reasons.add("$field=$value")
                } else if (ocrFold(value) in folded) {
                    score += w * 0.85; reasons.add("$field~=$value")
                } else {
                    val near = bestFuzzy(k, tokens)
                    if (near != null && near.second >= 0.82) {
                        score += w * 0.65; reasons.add("$field~${near.first}")
                    }
                }
            }
            consider(LabelField.CATALOG, def.catalog)
            consider(LabelField.CLONE, def.clone)
            consider(LabelField.VENDOR, def.vendor)
            consider(LabelField.ISOTYPE, def.isotype)
            if (score > 0) ranked.add(DefinitionMatch(def, score, reasons))
        }
        // Sorted stably, and by catalog on a tie: Swift's sort is unstable, so
        // two definitions with the same score could swap places between runs on
        // the same evidence. The margin test below cares about which of them is
        // on top.
        ranked.sortWith(compareByDescending<DefinitionMatch> { it.score }.thenBy { it.definition.catalog })

        val top = ranked.firstOrNull()
            ?: return ScanResult(
                verdict = Verdict.NOT_FOUND, definition = null, vial = null, otherVials = emptyList(),
                score = 0.0, margin = 0.0, reasons = listOf("no catalog evidence"),
                lotNotOnRecord = false, fields = fields, barcode = null,
                framesUsed = ev.frameCount, evidenceLevel = level,
            )

        val runnerUp = ranked.getOrNull(1)?.score ?: 0.0
        val margin = top.score - runnerUp
        val verdict = when {
            top.score >= config.minScore && margin >= config.minMargin -> Verdict.CONFIDENT
            top.score >= config.minScore * 0.6 -> Verdict.AMBIGUOUS
            else -> Verdict.NOT_FOUND
        }

        val picked = if (verdict == Verdict.CONFIDENT) {
            pickVial(top.definition, fields[LabelField.LOT])
        } else {
            Triple(null, emptyList(), false)
        }

        return ScanResult(
            verdict = verdict,
            definition = if (verdict == Verdict.NOT_FOUND) null else top.definition,
            vial = picked.first, otherVials = picked.second,
            score = top.score, margin = margin, reasons = top.reasons,
            lotNotOnRecord = picked.third, fields = fields, barcode = null,
            framesUsed = ev.frameCount, evidenceLevel = level,
        )
    }

    /** Independent support for a candidate from product-identifying fields. */
    private fun corroborates(def: AntibodyDefinition, ev: EvidenceBuffer): Boolean {
        val tokens = ev.allTokens
        val folded = tokens.mapTo(HashSet()) { ocrFold(it) }
        for (value in listOf(def.clone, def.target)) {
            val k = normKey(value)
            if (k.length < 3) continue
            if (k in tokens || ocrFold(k) in folded) return true
            // OCR truncates at the label curve, so accept a token that is a
            // prefix of the real value (`RMG` for clone `RMG1-1`).
            if (tokens.any { it.length >= 3 && k.startsWith(it) }) return true
            val near = bestFuzzy(k, tokens)
            if (near != null && near.second >= 0.85) return true
        }
        return false
    }

    /**
     * Choose the vial. The definition link is authoritative; lot only breaks ties
     * *within* that set. A bad lot read can never suppress a real vial.
     */
    private fun pickVial(
        def: AntibodyDefinition,
        lot: String?,
    ): Triple<InventoryItem?, List<InventoryItem>, Boolean> {
        val candidates = catalog.inventoryFor(def)
        if (candidates.isEmpty()) return Triple(null, emptyList(), false)

        if (!lot.isNullOrEmpty()) {
            val want = ocrFold(lot)
            val exact = candidates.firstOrNull { ocrFold(it.lot) == want }
            if (exact != null) {
                return Triple(exact, candidates.filter { it.abNo != exact.abNo }, false)
            }
            val near = candidates
                .map { it to similarity(ocrFold(it.lot), want) }
                .filter { it.second >= 0.8 }
                .maxByOrNull { it.second }?.first
            if (near != null) {
                return Triple(near, candidates.filter { it.abNo != near.abNo }, false)
            }
            // The lot read cleanly and matches nothing on record. Reporting one
            // of the existing vials here would be a lie: this is a new lot of a
            // known antibody, and naming a vial invites someone to draw from the
            // wrong one.
            return Triple(null, candidates, true)
        }

        if (candidates.size == 1) return Triple(candidates[0], emptyList(), false)
        return Triple(candidates[0], candidates.drop(1), false)
    }

    companion object {
        /**
         * What the scan actually recovered.
         *
         * "Enough to answer with" means enough to *retrieve* on: a barcode, a
         * catalog number, or a clone corroborated by a second field. A frame full
         * of `For Research Use Only` and `Store at 4C` is text, but it is label
         * furniture - it identifies no product and must not be mistaken for
         * evidence that the scan worked.
         */
        fun evidenceLevel(ev: EvidenceBuffer, fields: Map<LabelField, String>): EvidenceLevel {
            if (ev.barcodes.isNotEmpty()) return EvidenceLevel.SUFFICIENT
            if (ev.allTokens.isEmpty()) return EvidenceLevel.NONE
            if (fields[LabelField.CATALOG] != null) return EvidenceLevel.SUFFICIENT
            if (fields[LabelField.CLONE] != null &&
                (fields[LabelField.VENDOR] != null || fields[LabelField.ISOTYPE] != null)
            ) return EvidenceLevel.SUFFICIENT
            if (fields[LabelField.LOT] != null && fields[LabelField.CLONE] != null) {
                return EvidenceLevel.SUFFICIENT
            }
            return EvidenceLevel.THIN
        }
    }
}
