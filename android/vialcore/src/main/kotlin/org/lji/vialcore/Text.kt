package org.lji.vialcore

import java.text.BreakIterator

/// Keys and distances shared by the catalog index, the evidence vote and the
/// matcher. Ported 1:1 from `Sources/VialCore` — anything that changes here
/// changes what the two platforms conclude from the same label, so changes
/// belong on both sides at once.

/// Lowercase, alphanumerics only. `Ab Category`, `ab category` and `AbCategory`
/// all collapse to the same key, so callers never have to know how a given
/// exporter capitalised things.
fun normKey(s: String): String = buildString(s.length) {
    for (c in s.lowercase()) if (isAlphanumeric(c)) append(c)
}

/// Swift's `CharacterSet.alphanumerics` is letters + marks + *all* numerals,
/// while Kotlin's `isLetterOrDigit` is letters + decimal digits only. The gap is
/// combining accents and things like `²`, which would make `café` key
/// differently on the two platforms.
internal fun isAlphanumeric(c: Char): Boolean = c.isLetter() || isMark(c) || isNumeral(c)

internal fun isMark(c: Char): Boolean = when (Character.getType(c)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

internal fun isNumeral(c: Char): Boolean = when (Character.getType(c)) {
    Character.DECIMAL_DIGIT_NUMBER.toInt(),
    Character.LETTER_NUMBER.toInt(),
    Character.OTHER_NUMBER.toInt() -> true
    else -> false
}

/// Swift's `String` iterates grapheme clusters, Kotlin's iterates UTF-16 units,
/// so `e` + a combining acute is one character to Swift and two to Kotlin.
/// Every distance below counts clusters, or the two platforms would score the
/// same pair of strings differently.
/// `BreakIterator.getCharacterInstance()` is not a cheap accessor: on Android it
/// builds an ICU rule-based iterator, measured at roughly 290us a time on a
/// Pixel 5a against a few microseconds on desktop OpenJDK. `graphemes` was
/// constructing one per call, four calls per `similarity`, so Tier 3 of the
/// matcher spent 5.8 SECONDS on 5096 comparisons of an 8-token bag. Cache it.
/// `BreakIterator` is stateful and not thread safe, hence per thread and not
/// a shared singleton: the analyser thread and the replay harness both call this.
private val characterBreaker: ThreadLocal<BreakIterator> =
    ThreadLocal.withInitial { BreakIterator.getCharacterInstance() }

/// True when every char is printable ASCII, in which case each char is its own
/// grapheme cluster and ICU has nothing to contribute. Control characters are
/// excluded deliberately rather than lazily: ICU joins CR LF into a *single*
/// cluster, so `\r` must not take this path. Catalog numbers, clones and lot
/// codes are all in this set, which is why it is worth having.
private fun isPlainAscii(s: String): Boolean {
    for (c in s) if (c < ' ' || c > '~') return false
    return true
}

/// Cluster count without building the list. Every caller that only wanted
/// `graphemes(x).size` was allocating a list of single-char strings to throw
/// it away.
internal fun graphemeCount(s: String): Int =
    if (isPlainAscii(s)) s.length else graphemes(s).size

internal fun graphemes(s: String): List<String> {
    if (isPlainAscii(s)) {
        val out = ArrayList<String>(s.length)
        for (c in s) out.add(c.toString())
        return out
    }
    val it = characterBreaker.get()
    it.setText(s)
    val out = ArrayList<String>(s.length)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out.add(s.substring(start, end))
        start = end
        end = it.next()
    }
    return out
}

/// OCR confusables, folded so `0106-01` and `O1O6O1` collapse together.
fun ocrFold(s: String): String = buildString {
    for (c in normKey(s)) {
        append(
            when (c) {
                'o' -> '0'
                'i', 'l' -> '1'
                's' -> '5'
                'b' -> '8'
                'z' -> '2'
                'g', 'q' -> '9'
                else -> c
            }
        )
    }
}

fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return graphemeCount(b)
    if (b.isEmpty()) return graphemeCount(a)
    // Both plain ASCII means one cluster per char, so the DP can run straight
    // over the chars and skip two list allocations per call.
    if (isPlainAscii(a) && isPlainAscii(b)) return editDistanceAscii(a, b)
    val ga = graphemes(a)
    val gb = graphemes(b)
    var prev = IntArray(gb.size + 1) { it }
    var cur = IntArray(gb.size + 1)
    for (i in 1..ga.size) {
        cur[0] = i
        for (j in 1..gb.size) {
            cur[j] = minOf(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + if (ga[i - 1] == gb[j - 1]) 0 else 1
            )
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[gb.size]
}

/// Levenshtein over chars. Only correct when both strings are plain ASCII, so
/// only `editDistance` may call it, after checking.
private fun editDistanceAscii(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        val ca = a[i - 1]
        for (j in 1..b.length) {
            cur[j] = minOf(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + if (ca == b[j - 1]) 0 else 1
            )
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val len = maxOf(graphemeCount(a), graphemeCount(b))
    return 1.0 - editDistance(a, b).toDouble() / len.toDouble()
}

/// True when two strings are identical except for their final character, or when
/// one is the other plus a trailing character.
fun differsOnlyInLastCharacter(a: String, b: String): Boolean {
    val ga = graphemes(a)
    val gb = graphemes(b)
    if (ga.size == gb.size) {
        if (ga.size < 2) return false
        return ga.dropLast(1) == gb.dropLast(1) && ga.last() != gb.last()
    }
    if (kotlin.math.abs(ga.size - gb.size) == 1) {
        val longer = if (ga.size > gb.size) ga else gb
        val shorter = if (ga.size > gb.size) gb else ga
        return longer.dropLast(1) == shorter
    }
    return false
}

/// Closest token in `hay` to `needle`.
///
/// Swift iterates a `Set` here, so a tie between two equally close tokens is
/// resolved by hash order. This version sorts first, which makes the same
/// evidence always produce the same answer — a deliberate divergence, and the
/// only kind of difference the parity test is allowed to see.
fun bestFuzzy(needle: String, hay: Collection<String>): Pair<String, Double>? {
    var best: Pair<String, Double>? = null
    // Hoisted: the needle does not change across the loop, and this used to be
    // re-measured once per candidate.
    val needleLen = graphemeCount(needle)
    for (t in hay.sorted()) {
        // Length gate keeps this cheap: edit distance cannot beat the threshold
        // if the lengths are already too far apart.
        if (kotlin.math.abs(graphemeCount(t) - needleLen) > 3) continue
        val s = similarity(needle, t)
        if (s > (best?.second ?: 0.0)) best = t to s
    }
    return best
}

/// Overlap between two token sets, 0 (nothing in common) to 1 (identical).
fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val inter = a.intersect(b).size
    return inter.toDouble() / (a.size + b.size - inter).toDouble()
}

fun tokenize(s: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (c in s) {
        // Swift's `isLetter || isNumber`: `½` is a number there, so `50½mg` is
        // one token on both platforms.
        if (c.isLetter() || isNumeral(c) || c == '-' || c == '.') sb.append(c)
        else {
            if (sb.length >= 2) out.add(sb.toString())
            sb.setLength(0)
        }
    }
    if (sb.length >= 2) out.add(sb.toString())
    return out
}
