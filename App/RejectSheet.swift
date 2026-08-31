import SwiftUI
import VialCore

/// What "Discard" opens onto.
///
/// A rejected reading with no correction attached teaches the matcher nothing
/// - it just says "wrong" without saying what right looks like. So the three
/// real answers are each one tap away: pick the vial, say it's not on file
/// (routes straight into the existing receiving flow), or admit nobody knows.
/// The last one has to be exactly as fast as the old plain reject button was,
/// because it is the one the operator reaches for constantly - a labelling
/// flow that makes "I don't know" cost more than a tap trains people to
/// guess instead.
struct RejectCorrectionSheet: View {
    /// Other vials of the same antibody the matcher already ranked and rejected
    /// itself — shown before search so the common case, right antibody wrong
    /// lot, is one tap rather than a retype of something the engine already knew.
    var rankedCandidates: [InventoryItem] = []
    /// A specific vial already in inventory: the correction carries a true AbNo.
    let onPickItem: (ItemRecord) -> Void
    /// The antibody is known but no inventory vial matches it - the correction
    /// carries a true catalog number instead of an AbNo.
    let onPickDefinition: (DefRecord) -> Void
    /// Genuinely not on file. The same destination as the card's own
    /// "Add to inventory" action, just reachable without backing out of reject.
    let onNotOnFile: () -> Void
    /// Unreadable, or nobody can tell. No truth attached.
    let onDontKnow: () -> Void

    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var items: [ItemRecord] {
        let q = normKey(query)
        guard !q.isEmpty else { return [] }
        return store.items.filter {
            normKey($0.abNo).contains(q) || normKey($0.definitionName).contains(q)
                || normKey($0.vialName).contains(q) || normKey($0.lot).contains(q)
        }
        .sorted { (Int($0.abNo) ?? 0, $0.abNo) < (Int($1.abNo) ?? 0, $1.abNo) }
        .prefix(20).map { $0 }
    }

    private var defs: [DefRecord] {
        let q = normKey(query)
        guard !q.isEmpty else { return [] }
        return store.defs.filter {
            normKey($0.name).contains(q) || normKey($0.catalog).contains(q)
                || normKey($0.clone).contains(q) || normKey($0.target).contains(q)
        }
        .prefix(20).map { $0 }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                SearchField(placeholder: "AbNo, antibody, catalog #…", text: $query)
                    .padding(.horizontal, Theme.pad)

                HStack(spacing: 10) {
                    Pill(title: "Not on file", icon: "plus", kind: .tinted) {
                        dismiss(); onNotOnFile()
                    }
                    Pill(title: "Don't know", icon: "questionmark", kind: .quiet, wide: false) {
                        dismiss(); onDontKnow()
                    }
                }
                .padding(.horizontal, Theme.pad)

                List {
                    if !rankedCandidates.isEmpty {
                        Section("Other vials of this antibody") {
                            ForEach(rankedCandidates, id: \.abNo) { v in
                                Button {
                                    if let item = store.item(abNo: v.abNo) { onPickItem(item) }
                                    dismiss()
                                } label: {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("AbNo \(v.abNo) · \(v.vialName.isEmpty ? v.definitionName : v.vialName)")
                                            .font(Theme.body).foregroundStyle(Theme.text)
                                        Text([v.lot.isEmpty ? "" : "Lot \(v.lot)", v.box]
                                            .filter { !$0.isEmpty }.joined(separator: " · "))
                                            .font(Theme.caption).foregroundStyle(Theme.textSecond)
                                    }
                                }
                                .listRowBackground(Theme.bg)
                            }
                        }
                    }
                    if !items.isEmpty {
                        Section("Vials on record") {
                            ForEach(items) { item in
                                Button { onPickItem(item); dismiss() } label: {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("AbNo \(item.abNo) · \(item.displayName)")
                                            .font(Theme.body).foregroundStyle(Theme.text)
                                        Text([item.lot.isEmpty ? "" : "Lot \(item.lot)", item.box]
                                            .filter { !$0.isEmpty }.joined(separator: " · "))
                                            .font(Theme.caption).foregroundStyle(Theme.textSecond)
                                    }
                                }
                                .listRowBackground(Theme.bg)
                            }
                        }
                    }
                    if !defs.isEmpty {
                        Section("Antibodies (no specific vial)") {
                            ForEach(defs) { def in
                                Button { onPickDefinition(def); dismiss() } label: {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(def.name).font(Theme.body).foregroundStyle(Theme.text)
                                        Text([def.vendor, def.catalog]
                                            .filter { !$0.isEmpty }.joined(separator: " · "))
                                            .font(Theme.caption).foregroundStyle(Theme.textSecond)
                                    }
                                }
                                .listRowBackground(Theme.bg)
                            }
                        }
                    }
                    if query.isEmpty {
                        Text("Type to search inventory and antibody definitions.")
                            .font(Theme.caption).foregroundStyle(Theme.textSecond)
                            .listRowBackground(Theme.bg)
                    } else if items.isEmpty && defs.isEmpty {
                        Text("No matches.")
                            .font(Theme.caption).foregroundStyle(Theme.textSecond)
                            .listRowBackground(Theme.bg)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
            .padding(.top, 10)
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("What was it actually?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
            }
        }
    }
}
