import AVKit
import SwiftUI
import VialCore

/// The queue of scans somebody parked.
///
/// Parking exists so that an uncertain read never stops the person at the
/// freezer. The cost of that is a promise: whatever they parked has to be
/// resolvable later without the vial in hand, which is why every parked scan
/// carries its clip and everything the engine read.
struct ReviewView: View {
    let openMenu: () -> Void
    @EnvironmentObject private var store: AppStore
    @State private var selected: ScanRecord?

    var body: some View {
        Screen(title: "Review queue", onLeading: openMenu) {
            if store.reviewQueue.isEmpty {
                EmptyState(icon: "checkmark.circle",
                           title: "Nothing waiting",
                           message: "Scans you park during a run show up here with the video the phone recorded.")
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(store.reviewQueue) { rec in
                            Button { selected = rec } label: { row(rec) }
                                .buttonStyle(PressScale())
                        }
                    }
                    .padding(.horizontal, Theme.pad)
                    .padding(.bottom, 28)
                }
            }
        }
        .sheet(item: $selected) { rec in
            ReviewDetail(record: rec).environmentObject(store)
        }
    }

    private func row(_ rec: ScanRecord) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(rec.verdictValue.tint.opacity(0.14)).frame(width: 44, height: 44)
                Image(systemName: rec.verdictValue.glyph)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(rec.verdictValue.tint)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(rec.title).font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.text).lineLimit(1)
                Text([rec.subtitle, AppStore.stamp(rec.date)].filter { !$0.isEmpty }
                    .joined(separator: " · "))
                    .font(Theme.caption).foregroundStyle(Theme.textSecond).lineLimit(1)
            }
            Spacer(minLength: 4)
            if rec.clipFile != nil {
                Image(systemName: "play.rectangle.fill")
                    .font(.system(size: 16)).foregroundStyle(Theme.accent)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.hairline))
    }
}

// MARK: - Detail

struct ReviewDetail: View {
    let record: ScanRecord
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    @State private var note: String
    @State private var linking = false
    @State private var receiving = false
    @State private var draft = ItemRecord(abNo: "")
    @State private var prefillDef: DefRecord? = nil
    @State private var sources: [String: FieldSource] = [:]

    init(record: ScanRecord) {
        self.record = record
        _note = State(initialValue: record.note)
    }

    private var clipURL: URL? {
        guard let f = record.clipFile else { return nil }
        let url = AppStore.clipURL(f)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let clipURL {
                        VideoPlayer(player: AVPlayer(url: clipURL))
                            .frame(height: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    } else {
                        Notice(text: "No clip was kept for this scan. Turn on “Always keep clips” in Settings if this keeps happening.",
                               tint: Theme.textSecond, icon: "video.slash")
                    }

                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Chip(text: record.verdictValue.headline, tint: record.verdictValue.tint)
                                Chip(text: record.outcome.label, tint: Theme.textSecond)
                                Spacer()
                                Text(AppStore.stamp(record.date))
                                    .font(Theme.caption).foregroundStyle(Theme.textFaint)
                            }
                            KeyValueRow(key: "Antibody", value: record.definitionName ?? "")
                            KeyValueRow(key: "AbNo", value: record.abNo ?? "")
                            KeyValueRow(key: "Catalog #", value: record.catalog ?? "", mono: true)
                            KeyValueRow(key: "Lot", value: record.lot ?? "", mono: true)
                            KeyValueRow(key: "Vendor", value: record.vendor ?? "")
                            KeyValueRow(key: "Clone", value: record.clone ?? "")
                            if let bc = record.barcode {
                                KeyValueRow(key: "Barcode",
                                            value: bc.replacingOccurrences(of: "\u{1D}", with: "⎵"), mono: true)
                            }
                            KeyValueRow(key: "Frames", value: String(record.framesUsed))
                            if !record.reasons.isEmpty {
                                SectionLabel(text: "Why the engine said this")
                                ForEach(record.reasons, id: \.self) { r in
                                    Text("• " + r).font(.system(size: 13)).foregroundStyle(Theme.textSecond)
                                }
                            }
                        }
                    }

                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            SectionLabel(text: "Note")
                            TextEditor(text: $note)
                                .font(.system(size: 15))
                                .frame(minHeight: 70)
                                .scrollContentBackground(.hidden)
                                .padding(8)
                                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.fill))
                        }
                    }

                    VStack(spacing: 10) {
                        if let abNo = record.abNo, store.item(abNo: abNo) != nil {
                            Pill(title: "Confirm AbNo \(abNo)", icon: "checkmark", kind: .solid) {
                                store.markConfirmed(abNo: abNo, scanID: record.id)
                                store.resolveReview(record, as: .confirmed, note: note)
                                Haptics.commit()
                                dismiss()
                            }
                        }
                        Pill(title: "Link to a vial", icon: "link", kind: .outline) { linking = true }
                        Pill(title: "Receive as a new vial", icon: "tray.and.arrow.down", kind: .tinted) {
                            draft = ItemRecord(abNo: store.nextAbNo())
                            draft.definitionName = record.definitionName ?? ""
                            draft.lot = record.lot ?? ""
                            var s: [String: FieldSource] = [:]
                            if let lot = record.lot, !lot.isEmpty {
                                s["Lot"] = record.barcode != nil ? .barcode : .ocr
                            }
                            draft.owner = store.settings.defaultOwner
                            if !draft.owner.isEmpty { s["Owner"] = .remembered }
                            draft.location = store.settings.defaultLocation
                            if !draft.location.isEmpty { s["Location"] = .remembered }
                            draft.box = store.activeSessionBox ?? store.settings.defaultBox
                            if !draft.box.isEmpty { s["Box"] = .remembered }

                            // A parked scan already logged catalog/vendor/clone
                            // separately from `definitionName`, so a review that
                            // never matched a definition still has enough to
                            // offer one instead of a blank name field.
                            if record.definitionName == nil || record.definitionName?.isEmpty == true {
                                let catalog = record.catalog ?? ""
                                let clone = record.clone ?? ""
                                let vendor = record.vendor ?? ""
                                if !catalog.isEmpty || !clone.isEmpty || !vendor.isEmpty {
                                    if !catalog.isEmpty { s["Catalog #"] = record.barcode != nil ? .barcode : .ocr }
                                    if !clone.isEmpty { s["Clone"] = .ocr }
                                    if !vendor.isEmpty { s["Vendor"] = .ocr }
                                    var name = [vendor, clone.isEmpty ? nil : "Clone \(clone)"]
                                        .compactMap { $0 }.joined(separator: " ")
                                    if name.isEmpty { name = "Cat \(catalog)" }
                                    prefillDef = DefRecord(name: name, clone: clone, vendor: vendor,
                                                            catalog: catalog, isLocal: true)
                                }
                            }
                            sources = s
                            receiving = true
                        }
                        Pill(title: "Dismiss — nothing to do", icon: "xmark", kind: .quiet) {
                            store.resolveReview(record, as: .rejected, note: note)
                            dismiss()
                        }
                        if record.clipFile != nil {
                            Pill(title: "Delete the clip", icon: "trash", kind: .danger) {
                                if let f = record.clipFile { store.deleteClip(named: f) }
                                var r = record
                                r.clipFile = nil
                                store.update(r)
                                dismiss()
                            }
                        }
                    }
                }
                .padding(Theme.pad)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle(record.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        var r = record
                        r.note = note
                        store.update(r)
                        dismiss()
                    }
                    .foregroundStyle(Theme.textSecond)
                }
            }
            .sheet(isPresented: $linking) {
                VialPicker { item in
                    store.markConfirmed(abNo: item.abNo, scanID: record.id)
                    var r = record
                    r.abNo = item.abNo
                    r.definitionName = item.definitionName
                    r.note = note
                    store.update(r)
                    store.resolveReview(r, as: .confirmed, note: note)
                    dismiss()
                }
                .environmentObject(store)
            }
            .sheet(isPresented: $receiving) {
                NewVialSheet(draft: $draft, prefillDefinition: prefillDef, sources: sources) { saved in
                    var r = record
                    r.abNo = saved.abNo
                    r.definitionName = saved.definitionName
                    r.note = note
                    store.update(r)
                    store.resolveReview(r, as: .received, note: note)
                    dismiss()
                }
                .environmentObject(store)
            }
        }
    }
}

// MARK: - Vial picker

struct VialPicker: View {
    let onPick: (ItemRecord) -> Void
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var results: [ItemRecord] {
        let q = normKey(query)
        guard !q.isEmpty else { return Array(store.items.prefix(60)) }
        return store.items.filter {
            normKey($0.abNo).contains(q) || normKey($0.definitionName).contains(q)
                || normKey($0.vialName).contains(q) || normKey($0.lot).contains(q)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                SearchField(placeholder: "AbNo, antibody, lot…", text: $query)
                    .padding(.horizontal, Theme.pad)
                List(results) { item in
                    Button {
                        onPick(item)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("AbNo \(item.abNo) · \(item.displayName)")
                                .font(Theme.body).foregroundStyle(Theme.text)
                            Text([item.lot, item.box, item.location].filter { !$0.isEmpty }
                                .joined(separator: " · "))
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
            .navigationTitle("Pick a vial")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
            }
        }
    }
}
