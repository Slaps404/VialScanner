import SwiftUI
import VialCore

struct InventoryView: View {
    let openMenu: () -> Void
    @EnvironmentObject private var store: AppStore

    @State private var query = ""
    @State private var filter: Filter = .all
    @State private var selected: ItemRecord?
    @State private var adding = false
    @State private var draft = ItemRecord(abNo: "")

    enum Filter: String, CaseIterable, Identifiable {
        case all, unconfirmed, confirmedToday, addedHere
        var id: String { rawValue }
        var label: String {
            switch self {
            case .all: return "All"
            case .unconfirmed: return "Never confirmed"
            case .confirmedToday: return "Confirmed today"
            case .addedHere: return "Added on this phone"
            }
        }
    }

    var body: some View {
        Screen(title: "Inventory", onLeading: openMenu, trailing: AnyView(
            CircleIcon(systemName: "plus") {
                draft = ItemRecord(abNo: store.nextAbNo())
                draft.owner = store.settings.defaultOwner
                draft.location = store.settings.defaultLocation
                draft.box = store.settings.defaultBox
                adding = true
            }
        )) {
            VStack(spacing: 12) {
                SearchField(placeholder: "AbNo, antibody, lot, box…", text: $query)
                    .padding(.horizontal, Theme.pad)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Filter.allCases) { f in
                            Button {
                                filter = f
                                Haptics.tap()
                            } label: {
                                Text(f.label)
                                    .font(Theme.caption)
                                    .foregroundStyle(filter == f ? Theme.onSolid : Theme.textSecond)
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(Capsule().fill(filter == f ? Theme.solid : Theme.fill))
                            }
                            .buttonStyle(PressScale())
                        }
                    }
                    .padding(.horizontal, Theme.pad)
                }

                if results.isEmpty {
                    EmptyState(icon: "tray",
                               title: "Nothing here",
                               message: query.isEmpty
                               ? "No vials match this filter."
                               : "No vial matches “\(query)”.")
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(results) { item in
                                Button { selected = item } label: { row(item) }
                                    .buttonStyle(PressScale())
                            }
                        }
                        .padding(.horizontal, Theme.pad)
                        .padding(.bottom, 28)
                    }
                }
            }
        }
        .sheet(item: $selected) { item in
            VialDetailSheet(item: item).environmentObject(store)
        }
        .sheet(isPresented: $adding) {
            NewVialSheet(draft: $draft) { saved in
                store.banner = AppStore.Banner(text: "AbNo \(saved.abNo) added")
            }
            .environmentObject(store)
        }
    }

    private func row(_ item: ItemRecord) -> some View {
        HStack(spacing: 12) {
            Text(item.abNo)
                .font(.system(size: 15, weight: .bold, design: .monospaced))
                .foregroundStyle(Theme.text)
                .frame(width: 46, height: 46)
                .background(Circle().fill(Theme.fill))

            VStack(alignment: .leading, spacing: 3) {
                Text(item.displayName)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    if !item.lot.isEmpty {
                        Text("Lot \(item.lot)").font(Theme.caption).foregroundStyle(Theme.textSecond)
                    }
                    if !item.box.isEmpty {
                        Text("· Box \(item.box)").font(Theme.caption).foregroundStyle(Theme.textSecond)
                    }
                    if !item.location.isEmpty {
                        Text("· \(item.location)").font(Theme.caption).foregroundStyle(Theme.textFaint)
                            .lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 4)

            if item.isLocal { Chip(text: "new", tint: Theme.accent) }
            if let c = item.lastConfirmedAt {
                Chip(text: relative(c), tint: Theme.good, icon: "checkmark")
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.hairline))
    }

    private func relative(_ d: Date) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        return f.localizedString(for: d, relativeTo: Date())
    }

    private var results: [ItemRecord] {
        let q = normKey(query)
        let startOfDay = Calendar.current.startOfDay(for: Date())
        return store.items.filter { item in
            switch filter {
            case .all: break
            case .unconfirmed: if item.lastConfirmedAt != nil { return false }
            case .confirmedToday:
                guard let c = item.lastConfirmedAt, c >= startOfDay else { return false }
            case .addedHere: if !item.isLocal { return false }
            }
            guard !q.isEmpty else { return true }
            return normKey(item.abNo).contains(q)
                || normKey(item.definitionName).contains(q)
                || normKey(item.vialName).contains(q)
                || normKey(item.lot).contains(q)
                || normKey(item.box).contains(q)
                || normKey(item.location).contains(q)
                || normKey(item.owner).contains(q)
        }
        .sorted { (Int($0.abNo) ?? 0, $0.abNo) < (Int($1.abNo) ?? 0, $1.abNo) }
    }
}

// MARK: - Detail

struct VialDetailSheet: View {
    let item: ItemRecord
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    @State private var editing = false
    @State private var draft: ItemRecord
    @State private var confirmDelete = false

    init(item: ItemRecord) {
        self.item = item
        _draft = State(initialValue: item)
    }

    private var scans: [ScanRecord] {
        store.scans.filter { $0.abNo == item.abNo }.prefix(12).map { $0 }
    }

    /// A binding onto one named column of the extra bag. Same helper the
    /// receiving sheet uses, for the same reason.
    private func bagField(_ name: String) -> Binding<String> {
        Binding(get: { draft.extra.value(name) }, set: { draft.extra.set(name, $0) })
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if editing {
                        Card {
                            VStack(alignment: .leading, spacing: 12) {
                                SectionLabel(text: "Vial")
                                LabeledField(title: "AbNo", text: $draft.abNo, mono: true)
                                LabeledField(title: "Vial name", text: $draft.vialName)
                                LabeledField(title: "Antibody", text: $draft.definitionName)
                                LabeledField(title: "Lot", text: $draft.lot, mono: true)
                                LabeledField(title: "Concentration", text: $draft.concentration)
                                LabeledField(title: "Size", text: bagField(colVolume))
                                LabeledField(title: "Expiration date", text: bagField(colExpiry))
                                LabeledField(title: "Storage temp", text: bagField(colStorage))
                                LabeledField(title: "Status", text: $draft.status)
                            }
                        }
                        Card {
                            VStack(alignment: .leading, spacing: 12) {
                                SectionLabel(text: "Where it lives")
                                SuggestField(title: "Location", text: $draft.location, options: store.locations)
                                SuggestField(title: "Box", text: $draft.box, options: store.boxes)
                                SuggestField(title: "Owner", text: $draft.owner, options: store.owners)
                            }
                        }
                        ExtraColumnsCard(title: "More vial fields",
                                         columns: vialFormColumns,
                                         bag: $draft.extra)

                        Card {
                            VStack(alignment: .leading, spacing: 12) {
                                SectionLabel(text: "Notes")
                                TextEditor(text: $draft.notes)
                                    .font(.system(size: 15))
                                    .frame(minHeight: 80)
                                    .scrollContentBackground(.hidden)
                                    .padding(8)
                                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.fill))
                            }
                        }
                        Pill(title: "Save changes", icon: "checkmark", kind: .solid) {
                            store.updateVial(draft)
                            editing = false
                            Haptics.commit()
                        }
                        Pill(title: "Delete this vial", icon: "trash", kind: .danger) {
                            confirmDelete = true
                        }
                    } else {
                        header
                        Card {
                            VStack(alignment: .leading, spacing: 6) {
                                SectionLabel(text: "Record")
                                KeyValueRow(key: "Antibody", value: item.definitionName)
                                KeyValueRow(key: "Lot", value: item.lot, mono: true)
                                KeyValueRow(key: "Concentration", value: item.concentration)
                                KeyValueRow(key: "Location", value: item.location)
                                KeyValueRow(key: "Box", value: item.box)
                                KeyValueRow(key: "Owner", value: item.owner)
                                KeyValueRow(key: "Status", value: item.status)
                                if let r = item.receivedAt {
                                    KeyValueRow(key: "Received", value: AppStore.stamp(r))
                                }
                                if let c = item.lastConfirmedAt {
                                    KeyValueRow(key: "Last confirmed", value: AppStore.stamp(c))
                                }
                                if !item.notes.isEmpty { KeyValueRow(key: "Notes", value: item.notes) }
                            }
                        }

                        // Everything else the import carried. Kept in its own
                        // card rather than merged above: the card over it is the
                        // record this app edits, this one is what Airtable said.
                        let carried = item.fields.others(excluding: ["AbNo"])
                        if !carried.isEmpty {
                            Card {
                                VStack(alignment: .leading, spacing: 6) {
                                    SectionLabel(text: "All catalog fields")
                                    ForEach(Array(carried.enumerated()), id: \.offset) { _, e in
                                        KeyValueRow(key: e.name, value: e.value)
                                    }
                                }
                            }
                        }

                        if let def = store.definition(named: item.definitionName) {
                            Card {
                                VStack(alignment: .leading, spacing: 6) {
                                    SectionLabel(text: "Definition")
                                    KeyValueRow(key: "Target", value: def.target)
                                    KeyValueRow(key: "Clone", value: def.clone)
                                    KeyValueRow(key: "Host", value: def.host)
                                    KeyValueRow(key: "Isotype", value: def.isotype)
                                    KeyValueRow(key: "Vendor", value: def.vendor)
                                    KeyValueRow(key: "Catalog #", value: def.catalog, mono: true)
                                    KeyValueRow(key: "Reactivity", value: def.core.reactivity)
                                    KeyValueRow(key: "Price", value: def.core.price)
                                    KeyValueRow(key: "Storage", value: def.core.storageLocation)
                                    if let u = URL(string: def.core.productURL), !def.core.productURL.isEmpty {
                                        Link("Vendor datasheet", destination: u)
                                            .font(Theme.label)
                                            .foregroundStyle(Theme.accent)
                                    }
                                }
                            }
                        }

                        if !scans.isEmpty {
                            Card {
                                VStack(alignment: .leading, spacing: 10) {
                                    SectionLabel(text: "Scans of this vial")
                                    ForEach(scans) { s in
                                        HStack(spacing: 8) {
                                            Circle().fill(s.verdictValue.tint).frame(width: 7, height: 7)
                                            Text(AppStore.stamp(s.date))
                                                .font(Theme.caption).foregroundStyle(Theme.textSecond)
                                            Text(s.outcome.label)
                                                .font(Theme.caption).foregroundStyle(Theme.text)
                                            Spacer()
                                            if s.clipFile != nil {
                                                Image(systemName: "video.fill")
                                                    .font(.system(size: 10))
                                                    .foregroundStyle(Theme.accent)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Pill(title: "Mark confirmed today", icon: "checkmark.seal", kind: .solid) {
                            store.markConfirmed(abNo: item.abNo, scanID: nil)
                            Haptics.commit()
                            store.banner = AppStore.Banner(text: "AbNo \(item.abNo) confirmed by hand")
                            dismiss()
                        }
                    }
                }
                .padding(Theme.pad)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("AbNo \(item.abNo)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(editing ? "Cancel" : "Edit") {
                        if editing { draft = item }
                        editing.toggle()
                    }
                    .foregroundStyle(Theme.accent)
                }
            }
            .alert("Delete AbNo \(item.abNo)?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) {
                    store.deleteVial(item)
                    dismiss()
                }
                Button("Keep", role: .cancel) {}
            } message: {
                Text("The vial is removed from this phone's inventory. Scans that referenced it are kept.")
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(item.displayName).font(Theme.display).foregroundStyle(Theme.text)
            HStack(spacing: 6) {
                if !item.box.isEmpty { Chip(text: "Box \(item.box)", tint: Theme.accent, icon: "shippingbox") }
                if !item.location.isEmpty { Chip(text: item.location, tint: Theme.textSecond, icon: "mappin") }
                if item.isLocal { Chip(text: "added here", tint: Theme.accent) }
                if item.lastConfirmedAt == nil {
                    Chip(text: "never confirmed", tint: Theme.warn, icon: "exclamationmark")
                }
            }
        }
    }
}
