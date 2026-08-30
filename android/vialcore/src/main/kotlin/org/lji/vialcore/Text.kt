package org.lji.vialcore

/// Keys and distances shared by the catalog index, the evidence vote and the
/// matcher. Ported 1:1 from `Sources/VialCore` — anything that changes here
/// changes what the two platforms conclude from the same label, so changes
/// belong on both sides at once.

/// Lowercase, alphanumerics only. `Ab Category`, `ab category` and `AbCategory`
/// all collapse to the same key, so callers never have to know how a given
/// exporter capitalised things.
fun normKey(s: String): String = buildString(s.length) {
    for (c in s.lowercase()) if (c.isLetterOrDigit()) append(c)
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
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            )
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return 1.0 - editDistance(a, b).toDouble() / maxOf(a.length, b.length).toDouble()
}

/// True when two strings are identical except for their final character, or when
/// one is the other plus a trailing character.
fun differsOnlyInLastCharacter(a: String, b: String): Boolean {
    if (a.length == b.length) {
        if (a.length < 2) return false
        return a.dropLast(1) == b.dropLast(1) && a.last() != b.last()
    }
    if (kotlin.math.abs(a.length - b.length) == 1) {
        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a
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
        if (kotlin.math.abs(t.length - needle.length) > 3) continue
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
        if (c.isLetterOrDigit() || c == '-' || c == '.') sb.append(c)
        else {
            if (sb.length >= 2) out.add(sb.toString())
            sb.setLength(0)
        }
    }
    if (sb.length >= 2) out.add(sb.toString())
    return out
}
