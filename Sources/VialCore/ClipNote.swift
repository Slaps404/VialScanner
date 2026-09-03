import Foundation

/// What the operator said the clip *should* resolve to, pulled off the front of
/// their free-text note.
///
/// The note is typed one-handed next to a freezer, so the grammar is the
/// cheapest thing that works: leading `key=value` tokens, then prose. Parsing
/// lives here rather than in the app because the CLI has to read the same
/// sidecars back as expectations when it replays a clip.
public struct ClipExpectation: Equatable, Sendable {
    public var catalog: String?
    public var lot: String?
    public var clone: String?
    public var verdict: String?

    public init(catalog: String? = nil, lot: String? = nil,
                clone: String? = nil, verdict: String? = nil) {
        self.catalog = catalog; self.lot = lot
        self.clone = clone; self.verdict = verdict
    }

    public var isEmpty: Bool {
        catalog == nil && lot == nil && clone == nil && verdict == nil
    }
}

/// Reads leading whitespace-separated `key=value` tokens off `note`.
/// Stops at the first token without an `=`, so prose after the tokens is never
/// mined for keys. Returns nil when nothing recognised was found.
public func parseClipNote(_ note: String) -> ClipExpectation? {
    var out = ClipExpectation()
    for token in note.split(whereSeparator: { $0.isWhitespace }) {
        guard let eq = token.firstIndex(of: "=") else { break }
        let key = token[token.startIndex..<eq].lowercased()
        let value = String(token[token.index(after: eq)...])
        if value.isEmpty { continue }
        switch key {
        case "cat", "catalog": out.catalog = value
        case "lot":            out.lot = value
        case "clone":          out.clone = value
        case "expect", "verdict": out.verdict = value
        default: break // Unknown key, still a token: keep scanning.
        }
    }
    return out.isEmpty ? nil : out
}
