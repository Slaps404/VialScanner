package org.lji.vialcore

/**
 * A bounding box in the engine's own coordinate space: normalised 0..1,
 * **origin bottom-left**, the same convention Apple's Vision framework uses.
 *
 * This is the one place the port had a real choice to make. ML Kit reports
 * pixel rects with the origin at the *top* left, so a straight transliteration
 * of the geometry in `FieldExtraction` would silently invert "below" and the
 * dangling-keyword pass would look for `Cat. No.`'s value above it instead of
 * under it — no crash, no error, just quietly worse reads on eBioscience
 * labels. Keeping the engine on Vision's convention and doing the flip once, at
 * the ML Kit adapter, means the extraction logic stays line-for-line comparable
 * with the Swift original and there is exactly one place to get it wrong.
 */
data class NormRect(val minX: Double, val minY: Double, val width: Double, val height: Double) {
    val maxX: Double get() = minX + width
    val maxY: Double get() = minY + height
    val midX: Double get() = minX + width / 2
    val midY: Double get() = minY + height / 2
}

/** One OCR line from one frame. */
data class TextObservation(
    val text: String,
    val confidence: Float,
    val boundingBox: NormRect? = null,
)

/**
 * Accumulates observations across frames.
 *
 * The single most important behaviour here is **cross-frame voting**: a field
 * value is only trusted once several independent frames agree on it. Reading
 * one regex hit from one frame is what produced `04IS` for a lot number that is
 * really `F0412-S122Z`.
 */
class EvidenceBuffer {
    var frameCount = 0
        private set

    /** token -> accumulated weight (sum of per-frame confidence) */
    val tokenWeight = LinkedHashMap<String, Double>()
    /** token -> number of distinct frames it appeared in */
    val tokenFrames = LinkedHashMap<String, Int>()
    /** normalized token -> best-looking raw spelling seen */
    val rawForms = LinkedHashMap<String, String>()
    /** field candidates awaiting a vote */
    val fieldVotes = LinkedHashMap<LabelField, LinkedHashMap<String, Double>>()

    val barcodes = ArrayList<BarcodePayload>()
    /** raw payload -> how many separate frames decoded it */
    val barcodeFrames = LinkedHashMap<String, Int>()
    val allText = ArrayList<String>()

    // ---- Rotation coverage --------------------------------------------------
    //
    // The engine needs to know whether the vial was actually *turned*, as
    // opposed to held still while the same face was read over and over. Frames
    // are cheap to re-read and expensive to trust: ten frames of one face are
    // one observation repeated, not ten observations, and settling on them
    // means answering before the rest of the label has been shown at all.
    //
    // The signal is the token set. Two frames of the same face share most of
    // their tokens; two frames of different faces barely overlap.

    private val views = ArrayList<Set<String>>()

    /** How many distinct faces of the label have been read. */
    val distinctViews: Int get() = views.size

    /** Consecutive text-bearing frames that produced no token not already seen.
     *  Once this climbs, the face in view is exhausted. */
    var framesSinceNewToken = 0
        private set

    private companion object {
        /** Two token sets count as the same face above this overlap. */
        const val SAME_VIEW_OVERLAP = 0.5
        /** Cap on stored views, so a pathological stream cannot grow the
         *  comparison list without bound. */
        const val MAX_VIEWS = 8
    }

    fun addBarcode(b: BarcodePayload) {
        if (barcodes.none { it.raw == b.raw }) barcodes.add(b)
        barcodeFrames[b.raw] = (barcodeFrames[b.raw] ?: 0) + 1
    }

    /** How many frames decoded this exact payload. */
    fun frames(b: BarcodePayload): Int = barcodeFrames[b.raw] ?: 0

    /** True once some payload has decoded the same way in [minFrames] frames. */
    fun hasConfirmedBarcode(minFrames: Int): Boolean =
        barcodeFrames.values.any { it >= minFrames }

    fun addFrame(observations: List<TextObservation>) {
        frameCount++
        val seenThisFrame = LinkedHashSet<String>()
        var novelTokens = 0
        for (o in observations) {
            allText.add(o.text)
            for (tok in tokenize(o.text)) {
                val k = normKey(tok)
                if (k.length < 2) continue
                tokenWeight[k] = (tokenWeight[k] ?: 0.0) + o.confidence.toDouble()
                if (seenThisFrame.add(k)) {
                    if (tokenFrames[k] == null) novelTokens++
                    tokenFrames[k] = (tokenFrames[k] ?: 0) + 1
                }
                // Prefer the longest raw spelling: OCR truncates at frame edges,
                // so the longest variant is usually the most complete one.
                val existing = rawForms[k]
                if (existing == null || existing.length < tok.length) rawForms[k] = tok
            }
        }
        // A frame that read nothing says nothing about coverage — it is a blur
        // or a blank stretch of glass, not evidence the label is exhausted.
        if (seenThisFrame.isNotEmpty()) {
            framesSinceNewToken = if (novelTokens > 0) 0 else framesSinceNewToken + 1
            if (seenThisFrame.size >= 2 && views.size < MAX_VIEWS &&
                views.none { jaccard(it, seenThisFrame) >= SAME_VIEW_OVERLAP }
            ) {
                views.add(seenThisFrame)
            }
        }

        // Field extraction runs per frame; each frame casts one vote per field.
        // The vote is weighted, not counted: a keyword-anchored read is worth a
        // whole frame, a shape-only read a fraction of one. That is the whole
        // mechanism keeping `\b[1-9]\d{5}\b` from deciding a vial's identity off
        // two lucky frames — it has to out-accumulate, not merely appear.
        for ((field, reading) in extractFields(observations)) {
            val bucket = fieldVotes.getOrPut(field) { LinkedHashMap() }
            bucket[reading.value] = (bucket[reading.value] ?: 0.0) + reading.weight
        }
    }

    private data class Merged(var repr: String, var score: Double)

    /**
     * The winning value for a field, plus how many frames backed it.
     *
     * [minFrames] is the corroboration bar. A value that only ever appeared in a
     * single frame is one glimpse of one face through one focus state, and the
     * identity-bearing fields must not be decided on that.
     */
    fun vote(field: LabelField, minFrames: Int = 1): Pair<String, Int>? {
        val votes = fieldVotes[field]
        if (votes.isNullOrEmpty()) return null

        // Merge variants that differ only by OCR confusables, keeping the longest
        // spelling as the representative — this is what repairs `0412-S122Z` ->
        // `F0412-S122Z` when different frames catch different ends of the string.
        val merged = LinkedHashMap<String, Merged>()
        for ((value, count) in votes) {
            val key = ocrFold(value)
            val m = merged[key]
            if (m != null) {
                if (value.length > m.repr.length) m.repr = value
                m.score += count
            } else {
                merged[key] = Merged(value, count)
            }
        }

        // Reconcile a keyword-anchored fragment with a later complete read that
        // differs in one character at the seam. `C28` followed by `C2355` means
        // the complete value is `C2855`; the short observation is trusted only
        // for the prefix it actually saw. Partial reads carry sub-frame weight
        // and therefore cannot become output on their own.
        // Both passes below filter over a snapshot of the keys, as the Swift
        // original does: a key removed by an earlier iteration is still a
        // candidate here, and is caught by the null check on lookup. Ties in the
        // length sort are broken alphabetically, which Swift leaves to hash
        // order; that is the port being deterministic where the original is not.
        // Four bare digits get the same treatment: a vendor that prints no letter
        // (`910801`) truncates to `9108`, and the fragment is carried at zero
        // weight, so this is the only way it can affect the outcome.
        val fuzzyKeys = merged.keys.sortedWith(compareBy({ it.length }, { it }))
        for (short in fuzzyKeys) {
            if (short.length != 3 && !(short.length == 4 && short.all { it.isDigit() })) continue
            val s = merged[short] ?: continue
            if (s.score >= 1) continue
            val candidates = fuzzyKeys.filter { long ->
                long.length > short.length &&
                    editDistance(short, long.take(short.length)) == 1 &&
                    short.first() == long.first()
            }
            if (candidates.size != 1) continue
            val longKey = candidates[0]
            val l = merged[longKey] ?: continue
            val corrected = short + longKey.drop(short.length)
            merged[corrected] = Merged(short.uppercase() + l.repr.drop(short.length), s.score + l.score)
            merged.remove(short)
            merged.remove(longKey)
        }

        // OCR truncates at the edge of the visible label curve, so the same value
        // often appears as `S10` in one frame and `S107` in the next. Fold a
        // strict prefix into its longer form instead of letting them compete.
        val keys = merged.keys.sortedWith(compareBy({ it.length }, { it }))
        for (short in keys) {
            if (short.length < 3) continue
            val s = merged[short] ?: continue
            val extensions = keys.filter { it.length > short.length && it.startsWith(short) }
            if (extensions.size != 1) continue
            val longKey = extensions[0]
            val l = merged[longKey] ?: continue
            // One spurious trailing glyph must not replace a value read cleanly
            // in several frames (`364107` + one `3641071`). Extend only when the
            // longer form has independent support or is at least as well
            // supported as the truncation it would absorb.
            if (longKey.length == short.length + 1 && !(l.score >= 2 || l.score >= s.score)) continue
            l.score += s.score
            merged.remove(short)
        }

        val best = merged.values.maxWithOrNull(
            compareBy<Merged> { it.score }.thenBy { it.repr.length }
        ) ?: return null
        val frames = Math.round(best.score).toInt()
        if (frames < minFrames) return null
        return best.repr to frames
    }

    /** Tokens that survived at least [minFrames] frames — the denoised bag. */
    fun stableTokens(minFrames: Int = 2): Set<String> =
        tokenFrames.filterValues { it >= minFrames }.keys.toSet()

    val allTokens: Set<String> get() = tokenWeight.keys.toSet()
}
