package org.lji.vialcore

import java.util.regex.Pattern

enum class LabelField { CATALOG, LOT, CLONE, VENDOR, CONCENTRATION, VOLUME, ISOTYPE }

class BarcodePayload(val raw: String, val symbology: String) {
    val catalog: String?
    val lot: String?

    init {
        val p = parse(raw)
        catalog = p.first
        lot = p.second
    }

    override fun equals(other: Any?) =
        other is BarcodePayload && other.raw == raw && other.symbology == symbology

    override fun hashCode() = raw.hashCode() * 31 + symbology.hashCode()

    companion object {
        /** GS1 group separator, 0x1D. */
        private const val GS = "\u001D"

        /**
         * Handles the two formats present in the sample set, plus generic GS1.
         *
         * * BioLegend: `"102116   B305480"` - catalog, whitespace run, lot.
         * * Thermo/Pierce GS1: `"91" <cat> GS "10" <lot> GS "301"`. AI 10 is the
         *   batch/lot identifier.
         */
        fun parse(raw: String): Pair<String?, String?> {
            if (raw.contains(GS)) {
                var catalog: String? = null
                var lot: String? = null
                for (seg in raw.split(GS)) {
                    val s = seg.trim()
                    if (s.startsWith("10") && s.length > 2) lot = s.substring(2)
                    else if (s.startsWith("91") && s.length > 2) catalog = s.substring(2)
                    else if (s.startsWith("01") && s.length > 2) catalog = s.substring(2)
                }
                if (catalog != null || lot != null) return catalog to lot
            }
            // Whitespace-separated two-field form.
            val parts = raw.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size == 2) return parts[0] to parts[1]
            if (parts.size == 1) return parts[0] to null
            return null to null
        }
    }
}

/**
 * A regex with Java's character classes widened to Unicode.
 *
 * Swift matches with `NSRegularExpression` (ICU), where `\b`, `\w` and `\s`
 * are Unicode-aware; Java's defaults are ASCII-only. Without this flag a `µ`
 * glued to a part number creates a word boundary on Android but not on iOS, so
 * `BE0106µ` yields a catalog number on one platform and nothing on the other,
 * and a non-breaking space silently defeats every `\s` in the keyword rules.
 */
internal fun urx(pattern: String, ignoreCase: Boolean = false): Regex {
    var flags = Pattern.UNICODE_CHARACTER_CLASS
    if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    return Pattern.compile(pattern, flags).toRegex()
}

private class Rule(val field: LabelField, pattern: String, val group: Int = 1) {
    val regex = urx(pattern, ignoreCase = true)
}

/**
 * Vendor label formats are highly stereotyped, which is what makes regex viable
 * here. Each rule is deliberately narrow; breadth comes from voting across
 * frames rather than from loose patterns.
 */
private val rules: List<Rule> = listOf(
    // `Cat`, optionally followed by one stray letter: OCR turns `Cat#` into
    // `Cate` and `Cat.` into `Catt` often enough to be worth the one character.
    Rule(LabelField.CATALOG, """CAT(?:ALOG)?[A-Z]?\s*(?:NO|N0|#|:)?\s*[.:]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"""),
    Rule(LabelField.CATALOG, """\bCat\s*[*:#]\s*([A-Z0-9][A-Z0-9\-]{3,14})"""),
    // `REF` is the ISO 15223 symbol for catalogue number and is what eBioscience,
    // Invitrogen and Affymetrix print instead of the word. The character class
    // absorbs the OCR variants actually observed (`BEF`, `REE`, `REI`); the
    // captured value still has to survive isPlausible and then match the catalog,
    // so a loose keyword costs precision nothing.
    Rule(LabelField.CATALOG, """\b[RB]E[FEI]\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"""),
    // Leinco and Thermo print `Prod. No.` / `Prod #`.
    Rule(LabelField.CATALOG, """\bP[RH]OD\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*([A-Z]\d{2,4})\b"""),
    Rule(LabelField.CATALOG, """\bP[RH]OD\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"""),
    // At the label seam the recogniser sometimes loses `Prod.` but preserves the
    // value line as `No.: C2855`. Restricted to Leinco's letter + 3/4 digit
    // shape; it cannot consume numeric lot/date rows.
    Rule(LabelField.CATALOG, """\bNO\.?\s*:\s*([A-Z]\d{3,4})\b"""),
    // Peptide and reagent vendors print `Part`.
    Rule(LabelField.CATALOG, """\bPART\s*(?:NO|#)?\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,14})"""),
    Rule(LabelField.LOT, """LOT\s*(?:NO|#|:)?\s*[.:]?\s*([A-Z0-9][A-Z0-9\-]{3,16})"""),
    Rule(LabelField.CLONE, """CLONE\s*[:#]?\s*\(?\s*([A-Z0-9][A-Z0-9.\-]{1,12})"""),
    Rule(LabelField.CLONE, """\(\s*Clone\s+([A-Z0-9][A-Z0-9.\-]{1,12})\s*\)"""),
    Rule(LabelField.ISOTYPE, """\b(Ig[GMAE](?:\d[a-c]?)?)\b"""),
    Rule(LabelField.CONCENTRATION, """(?:C|Conc)\s*[:.]?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:mg|ug|µg)\s*/\s*ml"""),
    Rule(LabelField.VOLUME, """(?:Vol|Size)\s*[:.]?\s*([0-9]+(?:\.[0-9]+)?)\s*m?[lL]"""),
)

private val vendorAliases: List<Pair<String, String>> = listOf(
    "southernbiotech" to "SouthernBiotech", "southernbio" to "SouthernBiotech",
    // The brand sits at the label seam in several clips. Accept the stable stems
    // the recogniser returns there; the associated identifier shapes remain
    // narrow and are still required in the same frame.
    "biolegend" to "BioLegend", "bioleg" to "BioLegend",
    "bdbiosciences" to "BD", "pharmingen" to "BD",
    "cellsignal" to "CST", "abcam" to "Abcam", "invitrogen" to "Invitrogen",
    "thermofisher" to "ThermoFisher", "thermo" to "ThermoFisher", "pierce" to "ThermoFisher",
    "jacksonimmuno" to "Jackson", "proteintech" to "ProteinTech", "rndsystems" to "R&D",
    "biorad" to "BioRad", "sigma" to "Sigma", "bioxcell" to "BioXCell",
    "leinco" to "Leinco", "leince" to "Leinco", "laince" to "Leinco",
    // eBioscience and Affymetrix are the brands printed on tubes the catalog
    // files list as Invitrogen. Without these, every eBioscience label reads as
    // vendorless - which costs the vendor-gated shape rules below their gate.
    "ebioscience" to "Invitrogen", "affymetrix" to "Invitrogen",
    "roche" to "Roche",
)

/**
 * Words that regularly survive the field regexes but can never be a real
 * identifier. These come from label furniture ("Cat", "Size", "Purified") that
 * OCR splices onto the wrong side of a delimiter.
 */
private val stopValues: Set<String> = setOf(
    "cat", "catno", "lot", "lotno", "size", "side", "conc", "vol", "volume",
    "prod", "prodno", "part", "partno", "ref", "refno", "qty", "pkg", "pkgsize",
    "store", "storeat", "clone", "poly", "polyclonal", "polyclona", "pol",
    "purified", "purifled", "purif", "unlb", "research", "use", "only",
    "isotype", "sotype", "control", "mouse", "rabbit", "human", "rat",
    "sizei", "each", "azide", "endotoxin", "free", "sterile",
)

/**
 * Minimum plausible length per field. A four-character "catalog number" is a
 * truncation artefact, not a part number, and letting it through invites a fuzzy
 * match against something unrelated.
 */
private val minLength = mapOf(LabelField.CATALOG to 5, LabelField.LOT to 4, LabelField.CLONE to 2)

/** The one catalog shape short enough to trip the length floor and still be a
 *  real part number: Leinco prints `P362` and `C2855`. */
private val shortCatalogException = urx("""^[A-Z][0-9]{3}$""")

/** A curved Leinco row can expose `Prod. No.: C28` before the remaining digits
 *  rotate into view. Kept at low weight so Evidence can repair a later
 *  near-prefix (`C2355` -> `C2855`); it can never win alone. */
private val partialCatalog = urx("""^[A-Z][0-9]{2}$""")

private fun isPlausible(field: LabelField, value: String): Boolean {
    val k = normKey(value)
    // Measure the value as printed, not as normalised. normKey strips the hyphen,
    // which turns Leinco's `I-118` into four characters and fails it on a floor
    // that exists to reject truncation artefacts - but the hyphen is structure
    // the vendor printed, not padding.
    var floor = minLength[field] ?: 2
    if (field == LabelField.CATALOG && shortCatalogException.matches(value.uppercase())) floor = 4
    if (field == LabelField.CATALOG && partialCatalog.matches(value.uppercase())) floor = 3
    if (maxOf(k.length, value.length) < floor) return false
    if (k in stopValues) return false
    // A value with no digits at all is almost never a catalog or lot number.
    if (field == LabelField.CATALOG || field == LabelField.LOT) {
        if (k.none { it.isDigit() }) return false
    }
    return true
}

// MARK: - Dangling keywords
//
// The recogniser returns *lines*, and a vendor's line breaks are not the
// engine's. On a curved tube the printed row `Cat. No.  107701` regularly comes
// back as two observations - `Cat. No.` and `107701` - because the curve puts a
// gap in the middle wide enough to split on. Joining every line in the frame
// into one string only works when the recogniser happens to emit them
// adjacently, which it does not reliably do.
//
// So stop pretending the frame is a sentence. A keyword line that ends without
// its value is *dangling*; its value is the observation printed next to it on
// the label, which is a question about geometry, and the bounding boxes answer
// it directly.

/** A keyword line with nothing usable after it - `Cat. No.`, `REF`, `LOT #`. */
private val danglingKeywords: List<Pair<LabelField, Regex>> = listOf(
    LabelField.CATALOG to urx(
        """(?:CAT(?:ALOG)?|PROD|PART|[RB]E[FEI])[A-Z]?\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*$""",
        ignoreCase = true
    ),
    LabelField.LOT to urx(
        """LOT\.?\s*(?:NO|N0|#)?\.?\s*[:#]?\s*$""",
        ignoreCase = true
    ),
)

private val identifierRun = urx("""[A-Z0-9][A-Z0-9\-]{2,15}""")

/**
 * The first identifier-shaped run in a neighbouring observation.
 *
 * Leading punctuation is stripped rather than rejected: the character that
 * separates a keyword from its value (`:`, `#`, `.`) frequently lands at the
 * head of the *value's* observation instead of the tail of the keyword's, and
 * `#728619` is a lot number with a stray glyph, not a failed read.
 */
private fun leadingIdentifier(text: String): String? {
    val up = text.uppercase()
    val m = identifierRun.find(up) ?: return null
    // Only the head of the line: a value further in is some other field's.
    if (m.range.first > 3) return null
    return m.value.trim(' ', '.', ',', ';', ':', '-')
}

/**
 * The observation printed immediately right of, or immediately below, [anchor].
 *
 * Boxes are normalised with the origin bottom-left (see [NormRect]), so "below"
 * is a smaller midY. Both directions are needed: vendors set `Cat. No.` beside
 * its number on BioLegend labels and above it on eBioscience ones.
 */
private fun neighbours(
    anchor: NormRect,
    obs: List<TextObservation>,
    excluding: Int,
): List<TextObservation> {
    val h = maxOf(anchor.height, 0.005)
    val right = ArrayList<Pair<Double, TextObservation>>()
    val below = ArrayList<Pair<Double, TextObservation>>()
    obs.forEachIndexed { i, o ->
        val b = o.boundingBox
        if (i != excluding && b != null) {
            val sameRow = kotlin.math.abs(b.midY - anchor.midY) < h * 0.6
            if (sameRow && b.minX >= anchor.maxX - h * 0.2) right.add((b.minX - anchor.maxX) to o)
            val overlapsX = b.maxX > anchor.minX - h && b.minX < anchor.maxX + h * 4
            if (overlapsX && b.midY < anchor.minY && anchor.minY - b.midY < h * 2.5) {
                below.add((anchor.minY - b.midY) to o)
            }
        }
    }
    return (right.sortedBy { it.first } + below.sortedBy { it.first }).map { it.second }
}

// MARK: - Vendor shapes
//
// Every rule above needs a keyword. Five of the sample videos never produce one
// that survives: the word is occluded by a finger, sheared off by the curve, or
// mangled past recognition, while the *number itself* reads cleanly in half a
// dozen frames. The number is the high-entropy half; refusing to read it because
// its label was unreadable throws away the part that identifies the vial.
//
// What makes this safe rather than a licence to grab digits is that part numbers
// are formatted. `14-9948-82` is not a date, `BE0106` is not a quantity, and
// `10 223 565 001` is not a phone number. Each shape is narrow enough to name
// its vendor, and each carries a weight below 1 so a shape-only read needs more
// frames of corroboration than a keyword-anchored one.

private class Shape(
    val field: LabelField,
    pattern: String,
    val weight: Double,
    /** Canonical vendor that must be legible in the same frame, if any. The
     *  looser a shape is, the more it needs this: six bare digits are a
     *  BioLegend catalog number only once something says BioLegend. */
    val vendor: String? = null,
    val normalize: (String) -> String = { it },
) {
    // Deliberately case-sensitive, like the Swift original: `BE0106` is a shape,
    // `be0106` inside a word is not.
    val regex = urx(pattern)
}

private val shapes: List<Shape> = listOf(
    // eBioscience / Invitrogen / Affymetrix: `14-9948-82`. Unmistakable, and not
    // confusable with a date - those are `2027-05-31`, four digits first.
    Shape(LabelField.CATALOG, """\b\d{2}-\d{4}-\d{2}\b""", 0.75),
    // The same number with the second hyphen lost to the curve: `14-491481`.
    Shape(LabelField.CATALOG, """\b\d{2}-\d{4}\d{2}\b""", 0.5, normalize = { v ->
        // Swift's `split(separator:)` drops empty fields, so a smudged `2020--11`
    // is still a year-month there. Without the filter it reads as a catalog
    // number on Android and an expiry date gets committed as a part number.
    val parts = v.split("-").filter { it.isNotEmpty() }
        if (parts.size == 2 && parts[1].length == 6) {
            "${parts[0]}-${parts[1].take(4)}-${parts[1].takeLast(2)}"
        } else v
    }),
    // BioXCell prints `BE0106`, and nothing else on the label is shaped like it.
    Shape(LabelField.CATALOG, """\bBE\d{4}\b""", 0.9),
    // Roche's four groups of digits, printed with the spaces: `10 223 565 001`.
    Shape(LabelField.CATALOG, """\b\d{2} \d{3} \d{3} \d{3}\b""", 0.9,
        normalize = { it.replace(" ", "") }),
    // SouthernBiotech: `0106-01`, four digits and two. This is also the shape of
    // every expiry date a label carries - `2020-11`, `2027-05` - which is why
    // looksLikeYearMonth exists and is checked before the value is taken.
    Shape(LabelField.CATALOG, """\b\d{4}-\d{2}\b""", 0.6),
    // Leinco: `P362`, `C2855`.
    Shape(LabelField.CATALOG, """\b[A-Z]\d{3,4}\b""", 0.5, vendor = "Leinco"),
    // BioLegend catalog numbers are six bare digits. Unsafe unconditionally
    // (dates and small-print IDs look the same), safe once the BioLegend stem is
    // present in the same cropped frame.
    Shape(LabelField.CATALOG, """\b[1-9]\d{5}\b""", 0.5, vendor = "BioLegend"),

    // BioLegend lots are `B` and five or six digits.
    Shape(LabelField.LOT, """\bB\d{5,6}\b""", 0.6),
    // Leinco lots encode the month: `0821L490`, `0520L310`.
    Shape(LabelField.LOT, """\b\d{4}[A-Z]\d{3}\b""", 0.75),
    // eBioscience lots come in two forms, `E009424` and bare digits.
    Shape(LabelField.LOT, """\bE\d{6}\b""", 0.6),
    Shape(LabelField.LOT, """\b\d{7,8}\b""", 0.4, vendor = "Invitrogen"),
    Shape(LabelField.LOT, """\b\d{7,8}\b""", 0.4, vendor = "Roche"),
)

/** `2020-11`, `2027-05`: an expiry, not a part number. Deliberately narrow -
 *  SouthernBiotech's `1040-01` and `4510-01` must survive it. */
private fun looksLikeYearMonth(v: String): Boolean {
    val parts = v.split("-")
    if (parts.size != 2 || parts[0].length != 4 || parts[1].length != 2) return false
    val year = parts[0].toIntOrNull() ?: return false
    val month = parts[1].toIntOrNull() ?: return false
    return year in 1990..2039 && month in 1..12
}

/** `20030225`-shaped values are dates, not lots. */
private fun looksLikeDate(v: String): Boolean {
    if (v.length != 8 || v.any { !it.isDigit() }) return false
    if (!v.startsWith("19") && !v.startsWith("20")) return false
    val month = v.substring(4, 6).toIntOrNull() ?: 0
    val day = v.substring(6, 8).toIntOrNull() ?: 0
    return month in 1..12 && day in 1..31
}

/**
 * Structure a value must have before geometry alone is allowed to name it.
 *
 * The dangling-keyword pass has no keyword-to-value delimiter to check its work
 * against - it is trusting a bounding box - so it checks the value instead.
 * `14-00` is a catalog number sheared in half by the curve of the tube, and
 * without this it read as one.
 */
private fun hasIdentifierStructure(v: String): Boolean {
    val k = normKey(v)
    if (k.length < 5) return false
    if (looksLikeYearMonth(v) || looksLikeDate(k)) return false
    var run = 0
    var longestRun = 0
    for (c in k) {
        run = if (c.isDigit()) run + 1 else 0
        longestRun = maxOf(longestRun, run)
    }
    return longestRun >= 4
}

// MARK: - Extraction

/** One frame's reading of one field, and how much a frame's worth of it counts. */
data class FieldReading(
    val value: String,
    /** 1.0 for a keyword-anchored read; less for a shape-only one, which needs
     *  proportionally more frames to clear the same corroboration bar. */
    val weight: Double,
)

/** Extract at most one candidate per field from a single frame's observations. */
fun extractFields(observations: List<TextObservation>): Map<LabelField, FieldReading> {
    val joined = observations.joinToString(" ") { it.text }
    val out = LinkedHashMap<LabelField, FieldReading>()

    // 1. Keyword rules over the whole frame.
    for (rule in rules) {
        if (out.containsKey(rule.field)) continue
        val m = rule.regex.find(joined) ?: continue
        val g = m.groups[rule.group] ?: continue
        val v = g.value.trim(' ', '.', ',', ';', ':', '-')
        if (v.isNotEmpty() && isPlausible(rule.field, v)) {
            val partial = rule.field == LabelField.CATALOG && partialCatalog.matches(v.uppercase())
            out[rule.field] = FieldReading(v.uppercase(), if (partial) 0.4 else 1.0)
        }
    }

    // Vendor first: the shape rules below are gated on it.
    val flat = normKey(joined)
    for ((needle, canonical) in vendorAliases) {
        if (flat.contains(needle)) {
            out[LabelField.VENDOR] = FieldReading(canonical, 1.0)
            break
        }
    }

    // 2. Dangling keywords, resolved by geometry.
    observations.forEachIndexed { i, o ->
        val box = o.boundingBox
        if (box != null) {
            for ((field, kw) in danglingKeywords) {
                if (out.containsKey(field)) continue
                if (kw.find(o.text) == null) continue
                for (cand in neighbours(box, observations, i)) {
                    val v = leadingIdentifier(cand.text) ?: continue
                    if (!isPlausible(field, v) || !hasIdentifierStructure(v)) continue
                    out[field] = FieldReading(v, 1.0)
                    break
                }
            }
        }
    }

    // 3. Vendor shapes, for the fields still unread.
    val vendor = out[LabelField.VENDOR]?.value
    for (shape in shapes) {
        if (out.containsKey(shape.field)) continue
        if (shape.vendor != null && shape.vendor != vendor) continue
        val m = shape.regex.find(joined) ?: continue
        val v = shape.normalize(m.value).uppercase()
        if (!isPlausible(shape.field, v) || looksLikeYearMonth(v) || looksLikeDate(normKey(v))) continue
        out[shape.field] = FieldReading(v, shape.weight)
    }

    return out
}

/** Text-only entry point, for tests and callers with no geometry to offer. */
fun extractFieldsFromLines(lines: List<String>): Map<LabelField, FieldReading> =
    extractFields(lines.map { TextObservation(it, 1f) })
