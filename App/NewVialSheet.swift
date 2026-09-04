import SwiftUI
import VialCore

/// Receiving form. Reached from a scan that found nothing, from a scan that
/// found the antibody but no vial, or from the inventory screen by hand.
///
/// Everything the scan managed to read is pre-filled and left editable — the
/// engine is right about the catalog number far more often than it is right
/// about the lot, and the person holding the tube can see both.
struct NewVialSheet: View {
    @Binding var draft: ItemRecord
    /// A definition built from the same scan, offered up front rather than
    /// waiting for someone to notice "this antibody is not on file either".
    /// NEW VIAL is the common case (31 of 39 clips in the sample set end
    /// here), so the definition step opens automatically instead of being a
    /// second screen the operator has to go looking for.
    var prefillDefinition: DefRecord? = nil
    /// Where each prefilled value came from, keyed by the same title strings
    /// used on `LabeledField`. Missing entries render with no badge, which is
    /// what a value the operator typed themselves should look like.
    var sources: [String: FieldSource] = [:]
    let onSave: (ItemRecord) -> Void

    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    /// A binding onto one named column of the extra bag, which is where the
    /// label facts with no fixed schema slot live.
    private func bagField(_ name: String) -> Binding<String> {
        Binding(get: { draft.extra.value(name) }, set: { draft.extra.set(name, $0) })
    }

    @State private var pickingDefinition = false
    @State private var creatingDefinition = false
    @State private var newDef = DefRecord(name: "")
    @State private var offeredPrefill = false
    /// Set the moment somebody types in the vial-name box. Until then the name
    /// is derived, not entered.
    @State private var vialNameOverridden = false

    /// The bundled inventory makes `Vial Name` the AbNo followed by the
    /// imported `Antibody Definitions` value. That is a source-backed handle,
    /// while a clone alone is only a field on the antibody definition.
    private var composedVialName: String {
        [draft.abNo, draft.definitionName]
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private func syncVialName() {
        guard !vialNameOverridden else { return }
        draft.vialName = composedVialName
    }

    /// Clone belongs to the antibody, not the tube, but the base rolls it back
    /// onto the vial row and so does this sheet: somebody holding a vial reads
    /// the clone off the label and wants to see it here, not one screen away.
    private var linkedClone: String {
        store.definition(named: draft.definitionName)?.clone ?? ""
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Card {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionLabel(text: "Antibody")
                            Button { pickingDefinition = true } label: {
                                HStack {
                                    Text(draft.definitionName.isEmpty ? "Choose a definition" : draft.definitionName)
                                        .font(Theme.body)
                                        .foregroundStyle(draft.definitionName.isEmpty ? Theme.textSecond : Theme.text)
                                        .multilineTextAlignment(.leading)
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(Theme.textFaint)
                                }
                                .padding(.horizontal, 14).frame(minHeight: 48)
                                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.fill))
                            }
                            .buttonStyle(PressScale())

                            Button {
                                newDef = DefRecord(name: "")
                                creatingDefinition = true
                            } label: {
                                Label("This antibody is not on file either",
                                      systemImage: "plus.circle")
                                    .font(Theme.label)
                                    .foregroundStyle(Theme.accent)
                            }
                        }
                    }

                    Card {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionLabel(text: "Vial")
                            LabeledField(title: "AbNo", text: $draft.abNo, mono: true)
                                .onChange(of: draft.abNo) { _, _ in syncVialName() }
                            LabeledField(title: "Vial name", text: Binding(
                                get: { draft.vialName },
                                set: { vialNameOverridden = true; draft.vialName = $0 }))
                            if vialNameOverridden, draft.vialName != composedVialName {
                                Button {
                                    vialNameOverridden = false
                                    syncVialName()
                                } label: {
                                    Label("Rebuild from AbNo and antibody", systemImage: "arrow.counterclockwise")
                                        .font(Theme.caption)
                                        .foregroundStyle(Theme.accent)
                                }
                            }
                            KeyValueRow(key: "Clone (from antibody)", value: linkedClone)
                            LabeledField(title: "Lot", text: $draft.lot, mono: true, source: sources["Lot"])
                            LabeledField(title: "Concentration", text: $draft.concentration, source: sources["Concentration"])
                            LabeledField(title: "Size", text: bagField(colVolume), source: sources[colVolume])
                            LabeledField(title: "Expiration date", text: bagField(colExpiry), source: sources[colExpiry])
                            LabeledField(title: "Storage temp", text: bagField(colStorage), source: sources[colStorage])
                            LabeledField(title: "Status", text: $draft.status)
                        }
                    }

                    Card {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionLabel(text: "Where it lives")
                            SuggestField(title: "Location", text: $draft.location, options: store.locations, source: sources["Location"])
                            SuggestField(title: "Box", text: $draft.box, options: store.boxes, source: sources["Box"])
                            SuggestField(title: "Owner", text: $draft.owner, options: store.owners, source: sources["Owner"])
                        }
                    }

                    ExtraColumnsCard(title: "More vial fields",
                                     columns: vialFormColumns,
                                     bag: $draft.extra,
                                     sources: sources)

                    Card {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionLabel(text: "Notes")
                            TextEditor(text: $draft.notes)
                                .font(.system(size: 15))
                                .frame(minHeight: 90)
                                .scrollContentBackground(.hidden)
                                .padding(8)
                                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.fill))
                        }
                    }

                    if abNoTaken {
                        Notice(text: "AbNo \(draft.abNo) already exists. Pick another, or the two vials will be impossible to tell apart.",
                               tint: Theme.bad)
                    }

                    Pill(title: "Add to inventory", icon: "tray.and.arrow.down", kind: .solid) {
                        save()
                    }
                    .disabled(!canSave)
                    .opacity(canSave ? 1 : 0.4)
                }
                .padding(Theme.pad)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("Receive vial")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
            }
            .sheet(isPresented: $pickingDefinition) {
                DefinitionPicker { def in
                    draft.definitionName = def.name
                    syncVialName()
                }
                .environmentObject(store)
            }
            .sheet(isPresented: $creatingDefinition) {
                NewDefinitionSheet(def: $newDef, sources: sources) { saved in
                    let added = store.addDefinition(saved)
                    draft.definitionName = added.name
                    syncVialName()
                }
                .environmentObject(store)
            }
            .onAppear {
                syncVialName()
                // NEW VIAL is the main path (most scans end here), so the
                // definition step opens by itself instead of waiting for
                // someone to find "this antibody is not on file either".
                guard !offeredPrefill, let prefillDefinition, draft.definitionName.isEmpty else { return }
                offeredPrefill = true
                newDef = prefillDefinition
                creatingDefinition = true
            }
        }
    }

    private var abNoTaken: Bool {
        !draft.abNo.isEmpty && store.item(abNo: draft.abNo) != nil
    }
    private var canSave: Bool {
        !draft.abNo.trimmingCharacters(in: .whitespaces).isEmpty && !abNoTaken
    }

    private func save() {
        var v = draft
        v.syncReceivingNotes()
        v.receivedAt = Date()
        let saved = store.addVial(v)
        onSave(saved)
        dismiss()
    }
}

// MARK: - Definition picker

struct DefinitionPicker: View {
    let onPick: (DefRecord) -> Void
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var results: [DefRecord] {
        let q = normKey(query)
        guard !q.isEmpty else { return store.defs }
        return store.defs.filter {
            normKey($0.name).contains(q) || normKey($0.catalog).contains(q)
                || normKey($0.clone).contains(q) || normKey($0.target).contains(q)
                || normKey($0.vendor).contains(q)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                SearchField(placeholder: "Name, catalog #, clone…", text: $query)
                    .padding(.horizontal, Theme.pad)
                List(results) { def in
                    Button {
                        onPick(def)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(def.name).font(Theme.body).foregroundStyle(Theme.text)
                            Text([def.vendor, def.catalog, def.clone]
                                .filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(Theme.caption).foregroundStyle(Theme.textSecond)
                        }
                    }
                    .listRowBackground(Theme.bg)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
            .padding(.top, 10)
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("Antibody")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
            }
        }
    }
}

// MARK: - New definition

struct NewDefinitionSheet: View {
    @Binding var def: DefRecord
    var sources: [String: FieldSource] = [:]
    let onSave: (DefRecord) -> Void
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Card {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionLabel(text: "Definition")
                            LabeledField(title: "Name", text: $def.name)
                            LabeledField(title: "Target", text: $def.target)
                            LabeledField(title: "Clone", text: $def.clone, source: sources["Clone"])
                            LabeledField(title: "Host", text: $def.host)
                            LabeledField(title: "Isotype", text: $def.isotype, source: sources["Isotype"])
                            LabeledField(title: "Vendor", text: $def.vendor, source: sources["Vendor"])
                            LabeledField(title: "Catalog #", text: $def.catalog, mono: true, source: sources["Catalog #"])
                        }
                    }
                    ExtraColumnsCard(title: "More antibody fields",
                                     columns: definitionFormColumns,
                                     bag: $def.extra,
                                     sources: sources)

                    Notice(text: "New definitions are matched by catalog number on the next scan, so get that field right even if the rest is blank.",
                           tint: Theme.accent, icon: "info.circle")

                    Pill(title: "Save definition", icon: "checkmark", kind: .solid) {
                        onSave(def)
                        dismiss()
                    }
                    .disabled(def.name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .opacity(def.name.trimmingCharacters(in: .whitespaces).isEmpty ? 0.4 : 1)
                }
                .padding(Theme.pad)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("New antibody")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
            }
        }
    }
}

// MARK: - Extra columns

/// The columns from `vialFormColumns` / `definitionFormColumns`, folded away by
/// default. Eleven more boxes stacked above "Add to inventory" would turn the
/// common case - scan, glance, save - into a scroll, but a vial that needs its
/// formulation recorded should not require a trip to Airtable either.
struct ExtraColumnsCard: View {
    let title: String
    let columns: [FormColumn]
    @Binding var bag: FieldBag?
    var sources: [String: FieldSource] = [:]

    @State private var expanded = false

    private var filled: Int {
        columns.filter { !bag.value($0.column).isEmpty }.count
    }

    private func field(_ c: FormColumn) -> Binding<String> {
        Binding(get: { bag.value(c.columns) }, set: { bag.set(c.columns, $0) })
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                Button {
                    withAnimation(.easeOut(duration: 0.18)) { expanded.toggle() }
                } label: {
                    HStack {
                        SectionLabel(text: title)
                        // Folded is not hidden: a count means nobody has to open
                        // the section to find out whether anything is in it.
                        if filled > 0 {
                            Text("\(filled) filled")
                                .font(Theme.caption)
                                .foregroundStyle(Theme.textSecond)
                        }
                        Spacer()
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(Theme.textFaint)
                    }
                }
                .buttonStyle(PressScale())

                if expanded {
                    ForEach(columns, id: \.column) { c in
                        LabeledField(title: c.title, text: field(c), source: sources[c.column])
                    }
                }
            }
        }
    }
}

// MARK: - Fields

struct LabeledField: View {
    let title: String
    @Binding var text: String
    var mono: Bool = false
    var placeholder: String = ""
    /// Where this value came from, if it was prefilled rather than typed.
    /// A barcode read is ground truth; an OCR read or a remembered default
    /// is a guess worth a second look. Once the operator edits the field the
    /// caller stops passing a source, and the badge goes away on its own.
    var source: FieldSource? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 5) {
                Text(title).font(Theme.caption).foregroundStyle(Theme.textSecond)
                if let source {
                    Image(systemName: source.glyph)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(source == .barcode ? Theme.accent : Theme.textFaint)
                        .help(source.label)
                }
            }
            TextField(placeholder, text: $text)
                .font(mono ? Theme.mono : Theme.body)
                .autocorrectionDisabled()
                .padding(.horizontal, 12).frame(height: 44)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.fill))
        }
    }
}

/// A text field with the values already in use one tap away. Freezer locations
/// are typed the same way every time or they stop being searchable.
struct SuggestField: View {
    let title: String
    @Binding var text: String
    let options: [String]
    var source: FieldSource? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            LabeledField(title: title, text: $text, source: source)
            if !options.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(options.prefix(12), id: \.self) { o in
                            Button {
                                text = o
                                Haptics.tap()
                            } label: {
                                Text(o)
                                    .font(Theme.caption)
                                    .foregroundStyle(text == o ? Theme.onSolid : Theme.textSecond)
                                    .padding(.horizontal, 10).padding(.vertical, 6)
                                    .background(Capsule().fill(text == o ? Theme.solid : Theme.fill))
                            }
                            .buttonStyle(PressScale())
                        }
                    }
                }
            }
        }
    }
}
