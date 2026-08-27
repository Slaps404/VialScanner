import SwiftUI
import VialCore

/// Confirm inventory: walk a box, scan every vial, and end up with a list of
/// what is there, what is missing, and what turned up that should not have.
///
/// This is the feature that pays for the scanner. Reading one vial saves a
/// lookup; checking a whole box is a job people postpone for months because
/// doing it by hand means reading 40 hand-written lot numbers off frozen tubes.
struct AuditView: View {
    let openMenu: () -> Void
    let startScanning: () -> Void

    @EnvironmentObject private var store: AppStore
    @State private var scope: AuditScope = .box
    @State private var scopeValue = ""
    @State private var showReport: AuditSession?
    @State private var confirmFinish = false

    var body: some View {
        Screen(title: "Confirm inventory", onLeading: openMenu) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let open = store.openAudit {
                        openSession(open)
                    } else {
                        setup
                    }
                    history
                }
                .padding(.horizontal, Theme.pad)
                .padding(.bottom, 30)
            }
        }
        .sheet(item: $showReport) { session in
            AuditReport(session: session).environmentObject(store)
        }
    }

    // MARK: Setup

    private var options: [String] {
        switch scope {
        case .box:        return store.boxes
        case .location:   return store.locations
        case .owner:      return store.owners
        case .everything: return []
        }
    }

    private var expectedCount: Int {
        store.expectedAbNos(scope: scope, value: scopeValue).count
    }

    private var setup: some View {
        VStack(alignment: .leading, spacing: 14) {
            Card {
                VStack(alignment: .leading, spacing: 14) {
                    SectionLabel(text: "What are you checking?")
                    HStack(spacing: 8) {
                        ForEach(AuditScope.allCases, id: \.self) { s in
                            Button {
                                scope = s
                                scopeValue = ""
                                Haptics.tap()
                            } label: {
                                Text(s.label)
                                    .font(Theme.caption)
                                    .foregroundStyle(scope == s ? Theme.onSolid : Theme.textSecond)
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(Capsule().fill(scope == s ? Theme.solid : Theme.fill))
                            }
                            .buttonStyle(PressScale())
                        }
                    }

                    if scope != .everything {
                        if options.isEmpty {
                            Text("No \(scope.label.lowercased()) values in the inventory yet.")
                                .font(Theme.caption).foregroundStyle(Theme.textSecond)
                        } else {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(options, id: \.self) { o in
                                        Button {
                                            scopeValue = o
                                            Haptics.tap()
                                        } label: {
                                            Text(o)
                                                .font(Theme.caption)
                                                .foregroundStyle(scopeValue == o ? Theme.onSolid : Theme.text)
                                                .padding(.horizontal, 12).padding(.vertical, 8)
                                                .background(Capsule().fill(scopeValue == o ? Theme.accent : Theme.fill))
                                        }
                                        .buttonStyle(PressScale())
                                    }
                                }
                            }
                        }
                    }

                    if scope == .everything || !scopeValue.isEmpty {
                        Text("\(expectedCount) vials expected")
                            .font(Theme.label).foregroundStyle(Theme.textSecond)
                    }
                }
            }

            Pill(title: "Start check", icon: "play.fill", kind: .solid) {
                let name = scope == .everything ? "Whole inventory" : "\(scope.label) \(scopeValue)"
                store.startAudit(scope: scope, value: scopeValue, name: name)
                Haptics.commit()
                Speaker.shared.say("Check started. \(expectedCount) vials expected.")
            }
            .disabled(scope != .everything && scopeValue.isEmpty)
            .opacity(scope != .everything && scopeValue.isEmpty ? 0.4 : 1)

            Notice(text: "While a check is open, every vial you confirm on the scan screen is ticked off here automatically. Hands-free mode works the same way.",
                   tint: Theme.accent, icon: "info.circle")
        }
    }

    // MARK: Open session

    private func openSession(_ session: AuditSession) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Card {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 14) {
                        ZStack {
                            Circle().strokeBorder(Theme.fillStrong, lineWidth: 8)
                            Circle()
                                .trim(from: 0, to: session.progress)
                                .stroke(Theme.good, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            Text("\(Int(session.progress * 100))%")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(Theme.text)
                        }
                        .frame(width: 68, height: 68)
                        .animation(.easeOut(duration: 0.25), value: session.progress)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.title).font(Theme.heading).foregroundStyle(Theme.text)
                            Text("\(session.foundSet.count) of \(session.expected.count) found · started \(AppStore.stamp(session.startedAt))")
                                .font(Theme.caption).foregroundStyle(Theme.textSecond)
                            if !session.extras.isEmpty {
                                Text("\(session.extras.count) unexpected")
                                    .font(Theme.caption).foregroundStyle(Theme.warn)
                            }
                        }
                        Spacer()
                    }

                    HStack(spacing: 10) {
                        Pill(title: "Scan vials", icon: "viewfinder", kind: .solid) { startScanning() }
                        Pill(title: "Finish", icon: "flag.checkered", kind: .outline, wide: false) {
                            confirmFinish = true
                        }
                    }
                }
            }

            if !session.extras.isEmpty {
                Card {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionLabel(text: "Turned up unexpectedly")
                        ForEach(session.extras) { e in
                            HStack(spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.system(size: 11)).foregroundStyle(Theme.warn)
                                Text(e.label).font(.system(size: 14)).foregroundStyle(Theme.text)
                                Spacer()
                                Text(AppStore.stamp(e.at))
                                    .font(Theme.caption).foregroundStyle(Theme.textFaint)
                            }
                        }
                    }
                }
            }

            Card {
                VStack(alignment: .leading, spacing: 10) {
                    SectionLabel(text: "Expected in \(session.title.lowercased())")
                    ForEach(session.expected, id: \.self) { abNo in
                        checklistRow(abNo: abNo, session: session)
                    }
                }
            }
        }
        .alert("Finish this check?", isPresented: $confirmFinish) {
            Button("Finish", role: .destructive) {
                store.finishAudit(session.id)
                Haptics.commit()
                if let done = store.audit(session.id) { showReport = done }
            }
            Button("Keep going", role: .cancel) {}
        } message: {
            Text("\(session.missing.count) vials are still unaccounted for. They will be listed as missing in the report.")
        }
    }

    private func checklistRow(abNo: String, session: AuditSession) -> some View {
        let found = session.found.first { $0.abNo == abNo }
        let item = store.item(abNo: abNo)
        return Button {
            Haptics.tap()
            if found != nil {
                store.unmarkFound(abNo, in: session.id)
            } else {
                // Ticking by hand is allowed and recorded as such: a vial whose
                // label is destroyed still exists, and forcing a scan would make
                // the report less true, not more.
                store.recordFound(abNo, in: session.id, method: "manual", scanID: nil)
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: found != nil ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(found != nil ? Theme.good : Theme.textFaint)
                VStack(alignment: .leading, spacing: 2) {
                    Text("AbNo \(abNo) · \(item?.displayName ?? "not on file")")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Theme.text).lineLimit(1)
                    if let lot = item?.lot, !lot.isEmpty {
                        Text("Lot \(lot)").font(Theme.caption).foregroundStyle(Theme.textSecond)
                    }
                }
                Spacer()
                if let f = found {
                    Chip(text: f.method == "scan" ? "scanned" : "by hand",
                         tint: f.method == "scan" ? Theme.good : Theme.textSecond)
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(PressScale())
    }

    // MARK: History

    @ViewBuilder
    private var history: some View {
        let past = store.audits.filter { !$0.isOpen }
        if !past.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "Past checks")
                ForEach(past) { s in
                    Button { showReport = s } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(Theme.fill).frame(width: 40, height: 40)
                                Text("\(Int(s.progress * 100))")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(s.missing.isEmpty ? Theme.good : Theme.warn)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.title).font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(Theme.text)
                                Text("\(s.foundSet.count)/\(s.expected.count) found · \(s.missing.count) missing · \(AppStore.stamp(s.finishedAt ?? s.startedAt))")
                                    .font(Theme.caption).foregroundStyle(Theme.textSecond)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.textFaint)
                        }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.hairline))
                    }
                    .buttonStyle(PressScale())
                }
            }
        }
    }
}

// MARK: - Report

struct AuditReport: View {
    let session: AuditSession
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var exportURL: URL?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(session.title).font(Theme.title).foregroundStyle(Theme.text)
                            Text("\(AppStore.stamp(session.startedAt)) → \(session.finishedAt.map(AppStore.stamp) ?? "open")")
                                .font(Theme.caption).foregroundStyle(Theme.textSecond)
                            HStack(spacing: 8) {
                                Chip(text: "\(session.foundSet.count) found", tint: Theme.good, icon: "checkmark")
                                if !session.missing.isEmpty {
                                    Chip(text: "\(session.missing.count) missing", tint: Theme.bad, icon: "questionmark")
                                }
                                if !session.extras.isEmpty {
                                    Chip(text: "\(session.extras.count) unexpected", tint: Theme.warn, icon: "exclamationmark")
                                }
                            }
                        }
                    }

                    if !session.missing.isEmpty {
                        Card {
                            VStack(alignment: .leading, spacing: 8) {
                                SectionLabel(text: "Missing")
                                ForEach(session.missing, id: \.self) { abNo in
                                    let item = store.item(abNo: abNo)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("AbNo \(abNo) · \(item?.displayName ?? "not on file")")
                                            .font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.text)
                                        Text([item?.lot ?? "", item?.box ?? "", item?.location ?? ""]
                                            .filter { !$0.isEmpty }.joined(separator: " · "))
                                            .font(Theme.caption).foregroundStyle(Theme.textSecond)
                                    }
                                }
                            }
                        }
                    }

                    if !session.extras.isEmpty {
                        Card {
                            VStack(alignment: .leading, spacing: 8) {
                                SectionLabel(text: "Unexpected")
                                ForEach(session.extras) { e in
                                    Text(e.label).font(.system(size: 14)).foregroundStyle(Theme.text)
                                }
                            }
                        }
                    }

                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            SectionLabel(text: "Found")
                            ForEach(session.found) { f in
                                HStack {
                                    Text("AbNo \(f.abNo) · \(store.item(abNo: f.abNo)?.displayName ?? "")")
                                        .font(.system(size: 14)).foregroundStyle(Theme.text).lineLimit(1)
                                    Spacer()
                                    Chip(text: f.method, tint: Theme.textSecond)
                                }
                            }
                        }
                    }

                    if let url = exportURL {
                        ShareLink(item: url) {
                            Text("Share the report")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(Theme.onSolid)
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .background(Capsule().fill(Theme.solid))
                        }
                    } else {
                        Pill(title: "Export CSV report", icon: "square.and.arrow.up", kind: .solid) {
                            exportURL = store.exportAuditCSV(session)
                        }
                    }

                    Pill(title: "Delete this check", icon: "trash", kind: .danger) {
                        store.deleteAudit(session.id)
                        dismiss()
                    }
                }
                .padding(Theme.pad)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("Check report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
            }
        }
    }
}
