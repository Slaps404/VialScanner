package org.lji.vialcore

/**
 * What the operator said the clip *should* resolve to, pulled off the front of
 * their free-text note.
 *
 * The note is typed one-handed next to a freezer, so the grammar is the
 * cheapest thing that works: leading `key=value` tokens, then prose. Parsing
 * lives here rather than in the app because the CLI has to read the same
 * sidecars back as expectations when it replays a clip.
 */
data class ClipExpectation(
    val catalog: String? = null,
    val lot: String? = null,
    val clone: String? = null,
    val verdict: String? = null,
) {
    val isEmpty: Boolean
        get() = catalog == null && lot == null && clone == null && verdict == null
}

/**
 * Reads leading whitespace-separated `key=value` tokens off [note].
 * Stops at the first token without an `=`, so prose after the tokens is never
 * mined for keys. Returns null when nothing recognised was found.
 */
fun parseClipNote(note: String): ClipExpectation? {
    var catalog: String? = null
    var lot: String? = null
    var clone: String? = null
    var verdict: String? = null
    for (token in note.split(' ', '\t', '\n', '\r')) {
        if (token.isEmpty()) continue
        val eq = token.indexOf('=')
        if (eq < 0) break
        val key = token.substring(0, eq).lowercase()
        val value = token.substring(eq + 1)
        if (value.isEmpty()) continue
        when (key) {
            "cat", "catalog" -> catalog = value
            "lot" -> lot = value
            "clone" -> clone = value
            "expect", "verdict" -> verdict = value
            // Unknown key, still a token: keep scanning.
        }
    }
    val out = ClipExpectation(catalog, lot, clone, verdict)
    return if (out.isEmpty) null else out
}
