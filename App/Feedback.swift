import AVFoundation
import UIKit
import VialCore

/// Spoken results for hands-free mode.
///
/// Hands-free exists because the operator has a vial in one hand and a rack in
/// the other. If they have to look at the phone to learn what it decided, the
/// mode is pointless — so the phone says it out loud, and says the *identifying*
/// facts (AbNo, lot) rather than a confidence adjective.
final class Speaker {
    static let shared = Speaker()
    private let synth = AVSpeechSynthesizer()
    var enabled = true

    private init() {}

    func configureAudioSession() {
        // Duck rather than interrupt: people play music in the lab, and this
        // speaks a sentence every few seconds.
        try? AVAudioSession.sharedInstance().setCategory(
            .playback, mode: .spokenAudio,
            options: [.duckOthers, .mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    func say(_ text: String, force: Bool = false) {
        guard enabled || force, !text.isEmpty else { return }
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        let u = AVSpeechUtterance(string: text)
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 1.04
        u.postUtteranceDelay = 0.05
        u.voice = AVSpeechSynthesisVoice(language: "en-US")
        synth.speak(u)
    }

    func stop() {
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
    }

    /// What the phone says about a result. Deliberately short — anything longer
    /// than a breath and the operator has already moved to the next vial.
    static func phrase(for result: ScanResult) -> String {
        // Said before the verdict is consulted: "new vial" would be a claim
        // about a label the camera never read.
        if result.readNothing { return "Nothing read. Hold it closer and turn it slowly." }
        switch result.verdict {
        case .certain, .confident:
            if let vial = result.vial {
                var s = "AbNo \(spellOut(vial.abNo))"
                if !vial.box.isEmpty { s += ", box \(vial.box)" }
                if result.lotNotOnRecord { s += ", but the lot does not match" }
                return s
            }
            if let def = result.definition {
                return "\(def.name). No vial on record."
            }
            return "Identified"
        case .ambiguous:
            return "Not sure. Check this one."
        case .notFound:
            return "New vial. Not in the inventory."
        }
    }

    /// "133" as "1 3 3": a spoken three-digit number is easy to mishear as
    /// another, and the operator is comparing it against a written label.
    private static func spellOut(_ s: String) -> String {
        s.map { String($0) }.joined(separator: " ")
    }
}

enum Haptics {
    static var enabled = true

    static func result(_ verdict: Verdict) {
        guard enabled else { return }
        let g = UINotificationFeedbackGenerator()
        g.prepare()
        switch verdict {
        case .certain, .confident: g.notificationOccurred(.success)
        case .ambiguous:           g.notificationOccurred(.warning)
        case .notFound:            g.notificationOccurred(.warning)
        }
    }

    static func tap() {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func commit() {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
}
