import SwiftUI
import VialCore

/// The verdict, and the four things you can do about it.
///
/// The card's job is to make "we are sure" and "we are guessing" look visibly
/// different. A lab that learns to tap through an amber card the same way it
/// taps through a green one has thrown away the confidence score, so the amber
/// path deliberately does not put "Confirm" under the thumb by default.
struct ResultCard: View {
    let result: ScanResult
    let clipURL: URL?
    @Binding var keepClip: Bool
    let countdown: Double
    let countdownTotal: Double
    let autoLabel: String?
    let inAudit: Bool

    let onCancelAuto: () -> Void
    let onConfirm: () -> Void
    let onRescan: () -> Void
    let onPark: () -> Void
    let onReject: () -> Void
    let onReceive: () -> Void
    /// "It is actually this one." Used when the antibody is right but the lot
    /// read badly, which is the common way a real vial ends up unnamed.
    let onChoose: (String) -> Void

    @State private var showDetail = false

    private var canConfirm: Bool { result.vial != nil }
    private var tint: Color { result.readNothing ? Theme.textSecond : result.verdict.tint }
    private var isSure: Bool { result.verdict == .certain || result.verdict == .confident }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    if autoLabel != nil { autoBar }
                    if result.readNothing {
                        nothingRead
                    } else {
                        identity
                        notices
                        candidates
                        detail
                    }
                }
                .padding(18)
            }
            .frame(maxHeight: 420)

            actions
                .padding(.horizontal, 18)
                .padding(.bottom, 16)
        }
        .background(
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .fill(Theme.surface)
                .shadow(color: .black.opacity(0.3), radius: 30, y: -4)
        )
        .overlay(alignment: .top) {
            Capsule().fill(Theme.hairline).frame(width: 38, height: 4).padding(.top, 8)
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                Circle().fill(tint.opacity(0.14)).frame(width: 46, height: 46)
                Image(systemName: result.readNothing ? "eye.slash" : result.verdict.glyph)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(tint)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(result.readNothing ? "Nothing read" : result.verdict.headline)
                    .font(Theme.title).foregroundStyle(Theme.text)
                Text(result.readNothing
                     ? "No text or barcode was recognised"
                     : result.verdict.sub)
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecond)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                if result.verdict != .certain, result.score > 0 {
                    Chip(text: String(format: "score %.0f", result.score), tint: result.verdict.tint)
                }
                Chip(text: "\(result.framesUsed) frames", tint: Theme.textSecond)
            }
        }
    }

    private var autoBar: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().strokeBorder(Theme.hairline, lineWidth: 3).frame(width: 26, height: 26)
                Circle()
                    .trim(from: 0, to: max(0, min(1, countdown / max(countdownTotal, 0.01))))
                    .stroke(Theme.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: 26, height: 26)
            }
            Text("\(autoLabel ?? "") in \(String(format: "%.0f", max(countdown, 0)))s")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.text)
            Spacer()
            Button("Hold") { onCancelAuto() }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.accent)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.fill))
    }

    // MARK: Identity

    @ViewBuilder
    private var identity: some View {
        if let vial = result.vial {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("AbNo \(vial.abNo)")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(Theme.text)
                    if !vial.box.isEmpty { Chip(text: "Box \(vial.box)", tint: Theme.accent, icon: "shippingbox") }
                    if !vial.location.isEmpty { Chip(text: vial.location, tint: Theme.textSecond, icon: "mappin") }
                }
                Text(vial.vialName.isEmpty ? vial.definitionName : vial.vialName)
                    .font(Theme.heading)
                    .foregroundStyle(Theme.text)
                VStack(alignment: .leading, spacing: 4) {
                    KeyValueRow(key: "Lot", value: vial.lot, mono: true)
                    // What somebody standing at the freezer actually needs off
                    // the record: what it is, what it is conjugated to, how much
                    // is there and what to dilute it to. All of it was already
                    // in the CSV; none of it used to be loaded.
                    KeyValueRow(key: "Status", value: vial.status)
                    KeyValueRow(key: "Category", value: vial.category)
                    KeyValueRow(key: "Conjugate", value: vial.conjugate)
                    KeyValueRow(key: "Concentration", value: vial.concentration)
                    KeyValueRow(key: "Dilute to 2 µg/ml", value: vial.diluteTo2)
                    KeyValueRow(key: "Amount", value: vial.amount)
                    KeyValueRow(key: "Volume", value: volumeSummary(vial))
                    KeyValueRow(key: "Formulation", value: vial.formulation)
                    KeyValueRow(key: "Owner", value: vial.owner)
                    KeyValueRow(key: "Notes", value: vial.receivingNotes)
                }
                // The definition side of the join. Host and isotype decide a
                // secondary antibody, so they belong on the vial's card even
                // though they live in the other table.
                if let def = result.definition {
                    VStack(alignment: .leading, spacing: 4) {
                        KeyValueRow(key: "Host", value: def.host)
                        KeyValueRow(key: "Isotype", value: def.isotype)
                        KeyValueRow(key: "Vendor", value: def.vendor)
                        KeyValueRow(key: "Catalog #", value: def.catalog, mono: true)
                        KeyValueRow(key: "Reactivity", value: def.reactivity)
                        KeyValueRow(key: "Storage", value: def.storageLocation)
                    }
                    if let url = URL(string: def.productURL), !def.productURL.isEmpty {
                        Link("Vendor datasheet", destination: url)
                            .font(Theme.label)
                            .foregroundStyle(Theme.accent)
                    }
                }
                remainingFields(vial)
            }
        } else if let def = result.definition {
            VStack(alignment: .leading, spacing: 8) {
                Text(def.name).font(Theme.heading).foregroundStyle(Theme.text)
                VStack(alignment: .leading, spacing: 4) {
                    KeyValueRow(key: "Target", value: def.target)
                    KeyValueRow(key: "Clone", value: def.clone)
                    KeyValueRow(key: "Vendor", value: def.vendor)
                    KeyValueRow(key: "Catalog #", value: def.catalog, mono: true)
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text("Nothing on file matches this label")
                    .font(Theme.heading).foregroundStyle(Theme.text)
                VStack(alignment: .leading, spacing: 4) {
                    KeyValueRow(key: "Catalog #",
                                value: result.fields[.catalog] ?? result.barcode?.catalog ?? "—", mono: true)
                    KeyValueRow(key: "Lot", value: result.fields[.lot] ?? result.barcode?.lot ?? "", mono: true)
                    KeyValueRow(key: "Vendor", value: result.fields[.vendor] ?? "")
                    KeyValueRow(key: "Clone", value: result.fields[.clone] ?? "")
                }
            }
        }
    }

    /// `50 of 100 µl` when both are known, otherwise whichever one is.
    private func volumeSummary(_ vial: InventoryItem) -> String {
        let current = vial.currentVolume, initial = vial.initialVolume
        if !current.isEmpty, !initial.isEmpty { return "\(current) of \(initial)" }
        return current.isEmpty ? initial : current
    }

    /// Every remaining non-empty column, collapsed. The named rows above are the
    /// answer to "what is this vial"; this is the escape hatch for the columns
    /// this app has no opinion about, including ones added to the base later.
    @ViewBuilder
    private func remainingFields(_ vial: InventoryItem) -> some View {
        let rest = vial.extra.others(excluding: InventoryItem.namedHeaders)
        if !rest.isEmpty {
            DisclosureGroup("All catalog fields (\(rest.count))") {
                VStack(alignment: .leading, spacing: 4) {
                    // Indexed rather than keyed by name: an export can repeat a
                    // header, and duplicate ForEach ids drop rows silently.
                    ForEach(Array(rest.enumerated()), id: \.offset) { _, e in
                        KeyValueRow(key: e.name, value: e.value)
                    }
                }
                .padding(.top, 4)
            }
            .font(Theme.label)
            .tint(Theme.textSecond)
        }
    }

    // MARK: Failed scan

    /// The failed-scan card.
    ///
    /// Deliberately not the `.notFound` card. "New vial — nothing on file
    /// matches this label" is a claim about a label, and there was no label:
    /// offering **Add to inventory** here invites someone to create a blank
    /// inventory row for a vial the camera never saw.
    private var nothingRead: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("The scan ran its full time without recognising anything on the vial. "
                 + "This is a camera problem rather than an inventory one — the vial has "
                 + "not been ruled out, it has not been read.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecond)
            VStack(alignment: .leading, spacing: 6) {
                bullet("Hold the label about 10 cm from the lens, inside the rectangle")
                bullet("Turn the vial slowly — a full rotation takes about three seconds")
                bullet("Frosted or hand-written labels often need the torch off, not on")
            }
        }
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Circle().fill(Theme.textSecond.opacity(0.5))
                .frame(width: 4, height: 4).padding(.top, 7)
            Text(text).font(.system(size: 13)).foregroundStyle(Theme.textSecond)
        }
    }

    // MARK: Notices

    @ViewBuilder
    private var notices: some View {
        if result.lotNotOnRecord {
            Notice(text: "The lot read from this label matches no vial on record for this antibody. "
                   + "Treat it as a new lot rather than one of the vials below.",
                   tint: Theme.warn)
        }
        if !result.otherVials.isEmpty {
            Notice(text: "Other vials share this antibody: "
                   + result.otherVials.prefix(6).map { "AbNo \($0.abNo)" }.joined(separator: ", ")
                   + ". Check the lot before drawing.",
                   tint: Theme.warn, icon: "square.stack.3d.up")
        }
        if result.verdict == .ambiguous {
            Notice(text: "Below the confidence bar — confirm against the label yourself before saving.",
                   tint: Theme.warn)
        }
        if inAudit, result.vial != nil {
            Notice(text: "Confirming will tick this vial off the open inventory check.",
                   tint: Theme.accent, icon: "checklist")
        }
    }

    // MARK: Candidates

    /// When the engine knows the antibody but refuses to name the vial — a lot
    /// that read badly, or several vials of the same definition — the operator
    /// gets the shortlist rather than a dead end. Picking one is recorded as a
    /// human decision, not as something the engine resolved.
    @ViewBuilder
    private var candidates: some View {
        if result.vial == nil, !result.otherVials.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "Vials on record for this antibody")
                ForEach(result.otherVials.prefix(8), id: \.abNo) { v in
                    Button { onChoose(v.abNo) } label: {
                        HStack(spacing: 10) {
                            Text(v.abNo)
                                .font(.system(size: 13, weight: .bold, design: .monospaced))
                                .foregroundStyle(Theme.text)
                                .frame(width: 42, height: 34)
                                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.fill))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(v.vialName.isEmpty ? v.definitionName : v.vialName)
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundStyle(Theme.text).lineLimit(1)
                                Text([v.lot.isEmpty ? "" : "Lot \(v.lot)",
                                      v.box.isEmpty ? "" : "Box \(v.box)",
                                      v.location]
                                    .filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(Theme.caption).foregroundStyle(Theme.textSecond).lineLimit(1)
                            }
                            Spacer(minLength: 4)
                            Text("It's this one")
                                .font(Theme.caption).foregroundStyle(Theme.accent)
                        }
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(Theme.hairline, lineWidth: 1))
                    }
                    .buttonStyle(PressScale())
                }
            }
        }
    }

    // MARK: Detail

    private var detail: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeOut(duration: 0.18)) { showDetail.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text(showDetail ? "Hide what was read" : "What was read")
                        .font(Theme.label).foregroundStyle(Theme.accent)
                    Image(systemName: showDetail ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.accent)
                }
            }

            if showDetail {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(LabelField.allCases, id: \.self) { f in
                        if let v = result.fields[f] {
                            KeyValueRow(key: f.rawValue.capitalized, value: v, mono: true)
                        }
                    }
                    if let bc = result.barcode {
                        KeyValueRow(key: "Barcode",
                                    value: bc.raw.replacingOccurrences(of: "\u{1D}", with: "⎵"), mono: true)
                        KeyValueRow(key: "Symbology", value: bc.symbology)
                    }
                    if !result.reasons.isEmpty {
                        SectionLabel(text: "Why")
                        ForEach(result.reasons, id: \.self) { r in
                            Text("• " + r).font(.system(size: 13)).foregroundStyle(Theme.textSecond)
                        }
                    }
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.fill))
            }

            clipRow
        }
    }

    private var clipRow: some View {
        HStack(spacing: 10) {
            Image(systemName: clipURL == nil ? "video.slash" : "video.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(clipURL == nil ? Theme.textFaint : Theme.accent)
            Text(clipURL == nil ? "No clip recorded for this scan" : "Clip recorded")
                .font(.system(size: 13)).foregroundStyle(Theme.textSecond)
            Spacer()
            if clipURL != nil {
                Toggle("", isOn: $keepClip).labelsHidden().tint(Theme.accent)
                Text("Keep").font(Theme.caption).foregroundStyle(Theme.textSecond)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.fill))
    }

    // MARK: Actions

    @ViewBuilder
    private var actions: some View {
        if result.readNothing {
            HStack(spacing: 10) {
                Pill(title: "Try again", icon: "arrow.clockwise", kind: .solid) { onRescan() }
                Pill(title: "Cancel", kind: .quiet, wide: false) { onReject() }
            }
        } else {
            VStack(spacing: 10) {
                HStack(spacing: 10) {
                    if canConfirm {
                        Pill(title: isSure ? "Confirm & save" : "Save anyway",
                             icon: "checkmark",
                             kind: isSure ? .solid : .outline) { onConfirm() }
                    } else {
                        Pill(title: "Add to inventory", icon: "plus", kind: .solid) { onReceive() }
                    }
                    Pill(title: "Rescan", icon: "arrow.clockwise", kind: .outline, wide: false) { onRescan() }
                }

                HStack(spacing: 10) {
                    Pill(title: "Review later — keep clip", icon: "clock.badge.exclamationmark",
                         kind: .quiet) { onPark() }
                    if canConfirm {
                        Pill(title: "New vial", icon: "plus", kind: .tinted, wide: false) { onReceive() }
                    } else {
                        Pill(title: "Discard", icon: "xmark", kind: .danger, wide: false) { onReject() }
                    }
                }
            }
        }
    }
}
