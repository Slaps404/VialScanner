import SwiftUI
import UIKit
import VialCore

/// The visual language, in one place.
///
/// The reference is the ChatGPT iOS app: a near-white page, one weight of
/// hairline border, circular icon buttons in the corners, capsule controls at
/// the bottom, and colour reserved almost entirely for state. Nothing here is
/// decorative — in a freezer room with gloves on, contrast and target size are
/// the whole design.
enum Theme {

    // MARK: Colour

    static func dyn(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }

    /// Page background.
    static let bg          = dyn(light: 0xFFFFFF, dark: 0x0D0D0D)
    /// Cards, sheets, raised surfaces.
    static let surface     = dyn(light: 0xFFFFFF, dark: 0x1B1B1D)
    /// Inset fills: pills, search fields, list rows.
    static let fill        = dyn(light: 0xF4F4F5, dark: 0x2A2A2D)
    static let fillStrong  = dyn(light: 0xECECEE, dark: 0x343437)
    static let hairline    = dyn(light: 0xE5E5E7, dark: 0x323235)

    static let text        = dyn(light: 0x0D0D0D, dark: 0xECECEC)
    static let textSecond  = dyn(light: 0x8E8EA0, dark: 0x9B9BA6)
    static let textFaint   = dyn(light: 0xB4B4C0, dark: 0x6E6E78)

    /// Primary action. Black on white, white on black — the ChatGPT send button.
    static let solid       = dyn(light: 0x0D0D0D, dark: 0xECECEC)
    static let onSolid     = dyn(light: 0xFFFFFF, dark: 0x0D0D0D)

    static let accent      = dyn(light: 0x2F6FED, dark: 0x5C8FF6)
    static let good        = dyn(light: 0x1E9E62, dark: 0x35C486)
    static let warn        = dyn(light: 0xD98416, dark: 0xE8A33D)
    static let bad         = dyn(light: 0xD2453C, dark: 0xEE6A61)

    /// The orb gradient from the voice screen — used for the scanning state.
    static let orbTop      = Color(UIColor(hex: 0x6B7BF7))
    static let orbBottom   = Color(UIColor(hex: 0xC6CEFF))

    // MARK: Metrics

    static let radius: CGFloat = 18
    static let cardRadius: CGFloat = 22
    static let pad: CGFloat = 20

    // MARK: Type

    static let display  = Font.system(size: 30, weight: .bold)
    static let title    = Font.system(size: 22, weight: .semibold)
    static let heading  = Font.system(size: 17, weight: .semibold)
    static let body     = Font.system(size: 16, weight: .regular)
    static let label    = Font.system(size: 14, weight: .medium)
    static let caption  = Font.system(size: 12, weight: .medium)
    static let mono     = Font.system(size: 15, weight: .medium, design: .monospaced)
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red:   CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue:  CGFloat(hex & 0xFF) / 255,
                  alpha: 1)
    }
}

// MARK: - Verdict colour mapping

extension Verdict {
    var tint: Color {
        switch self {
        case .certain, .confident: return Theme.good
        case .ambiguous:           return Theme.warn
        case .notFound:            return Theme.accent
        }
    }
    var glyph: String {
        switch self {
        case .certain:   return "barcode.viewfinder"
        case .confident: return "checkmark.circle.fill"
        case .ambiguous: return "questionmark.circle.fill"
        case .notFound:  return "sparkles"
        }
    }
    var headline: String {
        switch self {
        case .certain:   return "Identified"
        case .confident: return "Identified"
        case .ambiguous: return "Not sure"
        case .notFound:  return "New vial"
        }
    }
    var sub: String {
        switch self {
        case .certain:   return "Matched by barcode"
        case .confident: return "Matched from label text"
        case .ambiguous: return "Best guess — confirm before use"
        case .notFound:  return "Nothing on file matches this label"
        }
    }
}
