import SwiftUI
import UIKit

// MARK: - Circular icon button
//
// The corner control from the reference: a hairline circle, an SF Symbol, and
// nothing else. 44pt is the smallest thing a gloved finger hits reliably, so
// that is the floor.

struct CircleIcon: View {
    let systemName: String
    var size: CGFloat = 44
    var filled: Bool = false
    var tint: Color = Theme.text
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size * 0.40, weight: .medium))
                .foregroundStyle(filled ? Theme.onSolid : tint)
                .frame(width: size, height: size)
                .background(
                    Circle().fill(filled ? Theme.solid : Theme.surface)
                )
                .overlay(
                    Circle().strokeBorder(filled ? .clear : Theme.hairline, lineWidth: 1)
                )
        }
        .buttonStyle(PressScale())
    }
}

/// Same shape, but legible on top of a live camera feed.
struct GlassCircleIcon: View {
    let systemName: String
    var size: CGFloat = 44
    var active: Bool = false
    /// Fill when on. Defaults to white, the neutral "this is engaged"
    /// treatment; the clip toggle overrides it to red so recording does not
    /// look like every other engaged control.
    var activeColor: Color = .white
    /// Icon tint when on. Picked off the fill by the caller rather than
    /// hard-coded, so a dark `activeColor` still reads.
    var activeIconColor: Color = .black
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size * 0.40, weight: .medium))
                .foregroundStyle(active ? activeIconColor : .white)
                .frame(width: size, height: size)
                .background(
                    Circle().fill(active ? activeColor : Color.black.opacity(0.42))
                )
                .overlay(Circle().strokeBorder(Color.white.opacity(active ? 0 : 0.22), lineWidth: 1))
        }
        .buttonStyle(PressScale())
    }
}

/// A round toggle with its state written underneath.
///
/// The two controls flanking the shutter used to say nothing: on was a white
/// circle, off was a grey one, and which was which is only obvious if you
/// already know. The caption is the state, not the control's name, so there is
/// nothing to infer - and it is what makes "am I recording?" answerable without
/// tapping to find out.
struct CaptionedToggle: View {
    let systemName: String
    let caption: String
    var active: Bool = false
    var activeColor: Color = .white
    var activeIconColor: Color = .black
    var action: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            GlassCircleIcon(systemName: systemName, size: 50, active: active,
                            activeColor: activeColor, activeIconColor: activeIconColor,
                            action: action)
            Text(caption)
                .font(.system(size: 12, weight: active ? .semibold : .regular))
                .foregroundStyle(active ? activeColor : .white.opacity(0.65))
                .lineLimit(1)
        }
        // Fixed width so the row does not shift when the caption changes length.
        .frame(width: 92)
    }
}

// MARK: - Capsule buttons

enum PillKind { case solid, tinted, outline, quiet, danger }

struct Pill: View {
    let title: String
    var icon: String? = nil
    var kind: PillKind = .solid
    var wide: Bool = true
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon).font(.system(size: 15, weight: .semibold)) }
                Text(title).font(.system(size: 16, weight: .semibold))
            }
            .foregroundStyle(fg)
            .padding(.horizontal, 20)
            .frame(maxWidth: wide ? .infinity : nil, minHeight: 52)
            .background(Capsule().fill(bg))
            .overlay(Capsule().strokeBorder(border, lineWidth: 1))
        }
        .buttonStyle(PressScale())
    }

    private var fg: Color {
        switch kind {
        case .solid:   return Theme.onSolid
        case .tinted:  return Theme.accent
        case .outline: return Theme.text
        case .quiet:   return Theme.textSecond
        case .danger:  return Theme.bad
        }
    }
    private var bg: Color {
        switch kind {
        case .solid:   return Theme.solid
        case .tinted:  return Theme.accent.opacity(0.12)
        case .outline: return .clear
        case .quiet:   return Theme.fill
        case .danger:  return Theme.bad.opacity(0.12)
        }
    }
    private var border: Color {
        kind == .outline ? Theme.hairline : .clear
    }
}

struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.965 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - Surfaces

struct Card<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous).fill(Theme.surface))
            .overlay(RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous)
                .strokeBorder(Theme.hairline, lineWidth: 1))
    }
}

struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(Theme.caption)
            .tracking(0.6)
            .foregroundStyle(Theme.textFaint)
    }
}

struct KeyValueRow: View {
    let key: String
    let value: String
    var mono: Bool = false
    var body: some View {
        if !value.isEmpty {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(key)
                    .font(Theme.label)
                    .foregroundStyle(Theme.textSecond)
                    .frame(width: 116, alignment: .leading)
                Text(value)
                    .font(mono ? Theme.mono : Theme.body)
                    .foregroundStyle(Theme.text)
                    .textSelection(.enabled)
                Spacer(minLength: 0)
            }
        }
    }
}

struct Chip: View {
    let text: String
    var tint: Color = Theme.textSecond
    var icon: String? = nil
    var body: some View {
        HStack(spacing: 5) {
            if let icon { Image(systemName: icon).font(.system(size: 10, weight: .bold)) }
            Text(text).font(Theme.caption)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(Capsule().fill(tint.opacity(0.12)))
    }
}

struct Notice: View {
    let text: String
    var tint: Color = Theme.warn
    var icon: String = "exclamationmark.triangle.fill"
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).font(.system(size: 13, weight: .bold)).foregroundStyle(tint)
            Text(text).font(.system(size: 14)).foregroundStyle(Theme.text)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(tint.opacity(0.10)))
    }
}

struct EmptyState: View {
    let icon: String
    let title: String
    let message: String
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(Theme.textFaint)
            Text(title).font(Theme.heading).foregroundStyle(Theme.text)
            Text(message)
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecond)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
        .padding(.vertical, 48)
    }
}

// MARK: - The orb
//
// The gradient sphere from the reference's voice mode. Here it stands in for
// "the engine is working": it breathes while frames are being read and goes
// still when a verdict lands.

struct Orb: View {
    var active: Bool
    var tint: Color = Theme.orbTop
    @State private var phase = false

    var body: some View {
        Circle()
            .fill(
                LinearGradient(colors: [tint, Theme.orbBottom],
                               startPoint: .top, endPoint: .bottom)
            )
            .overlay(
                Circle().fill(
                    RadialGradient(colors: [.white.opacity(0.55), .clear],
                                   center: .init(x: 0.35, y: 0.28),
                                   startRadius: 2, endRadius: 60)
                )
            )
            .scaleEffect(phase && active ? 1.06 : 0.94)
            .animation(active ? .easeInOut(duration: 1.1).repeatForever(autoreverses: true)
                              : .easeOut(duration: 0.25),
                       value: phase)
            .onAppear { phase = true }
    }
}

// MARK: - Search field

struct SearchField: View {
    let placeholder: String
    @Binding var text: String
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Theme.textSecond)
            TextField(placeholder, text: $text)
                .font(Theme.body)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Theme.textFaint)
                }
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 44)
        .background(Capsule().fill(Theme.fill))
    }
}

// MARK: - Nav scaffolding

/// Every screen behind the drawer uses this: a title row with a circular
/// leading button, then content. Deliberately not `NavigationStack`'s chrome —
/// the reference has no bars.
struct Screen<Content: View>: View {
    let title: String
    var leadingIcon: String = "line.3.horizontal"
    var onLeading: () -> Void
    var trailing: AnyView? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                CircleIcon(systemName: leadingIcon, action: onLeading)
                Text(title).font(Theme.title).foregroundStyle(Theme.text)
                Spacer()
                if let trailing { trailing }
            }
            .padding(.horizontal, Theme.pad)
            .padding(.bottom, 12)

            content
        }
        .background(Theme.bg.ignoresSafeArea())
    }
}

extension View {
    func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                        to: nil, from: nil, for: nil)
    }
}
