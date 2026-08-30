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
internal fun graphemes(s: String): List<String> {
    val it = BreakIterator.getCharacterInstance()
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
    if (a.isEmpty()) return graphemes(b).size
    if (b.isEmpty()) return graphemes(a).size
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

fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val len = maxOf(graphemes(a).size, graphemes(b).size)
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
    for (t in hay.sorted()) {
        // Length gate keeps this cheap: edit distance cannot beat the threshold
        // if the lengths are already too far apart.
        if (kotlin.math.abs(graphemes(t).size - graphemes(needle).size) > 3) continue
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
