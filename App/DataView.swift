import SwiftUI
import UniformTypeIdentifiers
import VialCore

/// Import and export.
///
/// The lab's source of truth is Airtable, and the freezer room is where the
/// network is worst, so the contract here is deliberately file-shaped: bring in
/// a grid-view export before you go downstairs, take a CSV back out afterwards.
/// Nothing in this app requires a server to be reachable at the moment it is
/// used.
struct DataView: View {
    let openMenu: () -> Void
    @EnvironmentObject private var store: AppStore

    @State private var defsURL: URL?
    @State private var invURL: URL?
    @State private var mode: AppStore.ImportMode = .merge
    @State private var picking: Picking?
    @State private var exportURL: URL?
    @State private var confirmReset = false
    @State private var confirmPurge = false

    private enum Picking: String, Identifiable {
        case definitions, inventory, backup
        var id: String { rawValue }
    }

    var body: some View {
        Screen(title: "Import & export", onLeading: openMenu) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    importCard
                    exportCard
                    storageCard
                    dangerCard
                }
                .padding(.horizontal, Theme.pad)
                .padding(.bottom, 30)
            }
        }
        .fileImporter(isPresented: Binding(get: { picking != nil },
                                           set: { if !$0 { picking = nil } }),
                      allowedContentTypes: allowedTypes,
                      allowsMultipleSelection: false) { result in
            handlePicked(result)
        }
    }

    private var allowedTypes: [UTType] {
        switch picking {
        case .backup: return [.json]
        default:      return [.commaSeparatedText, .plainText, .text]
        }
    }

    private func handlePicked(_ result: Result<[URL], Error>) {
        let which = picking
        picking = nil
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            switch which {
            case .definitions: defsURL = url
            case .inventory:   invURL = url
            case .backup:      store.importBackupJSON(url)
            case nil:          break
            }
        case .failure(let error):
            store.banner = AppStore.Banner(text: error.localizedDescription, isError: true)
        }
    }

    // MARK: Import

    private var importCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                SectionLabel(text: "Import from Airtable")
                Text("Export both grid views as CSV, AirDrop them to the phone, then pick them here.")
                    .font(.system(size: 14)).foregroundStyle(Theme.textSecond)

                filePickerRow(title: "Antibody Definitions",
                              url: defsURL,
                              hint: "AntibodyName, Catalog#, Inventory Items…") { picking = .definitions }
                filePickerRow(title: "Inventory Items",
                              url: invURL,
                              hint: "AbNo, Ab Lot, Location, Box…") { picking = .inventory }

                HStack(spacing: 8) {
                    ForEach(AppStore.ImportMode.allCases) { m in
                        Button {
                            mode = m
                            Haptics.tap()
                        } label: {
                            Text(m.label)
                                .font(Theme.caption)
                                .foregroundStyle(mode == m ? Theme.onSolid : Theme.textSecond)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(Capsule().fill(mode == m ? Theme.solid : Theme.fill))
                        }
                        .buttonStyle(PressScale())
                    }
                    Spacer()
                }
                Text(mode.blurb).font(Theme.caption).foregroundStyle(Theme.textSecond)

                Pill(title: "Import", icon: "square.and.arrow.down", kind: .solid) {
                    guard let d = defsURL, let i = invURL else { return }
                    store.importCSVs(definitions: d, inventory: i, mode: mode)
                    defsURL = nil
                    invURL = nil
                }
                .disabled(defsURL == nil || invURL == nil)
                .opacity(defsURL == nil || invURL == nil ? 0.4 : 1)
            }
        }
    }

    private func filePickerRow(title: String, url: URL?, hint: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: url == nil ? "doc.badge.plus" : "doc.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(url == nil ? Theme.textSecond : Theme.good)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.text)
                    Text(url?.lastPathComponent ?? hint)
                        .font(Theme.caption)
                        .foregroundStyle(url == nil ? Theme.textFaint : Theme.textSecond)
                        .lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.textFaint)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.fill))
        }
        .buttonStyle(PressScale())
    }

    // MARK: Export

    private var exportCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(text: "Export")
                exportRow(title: "Inventory", subtitle: "\(store.items.count) vials, including everything received on this phone", icon: "tray.full") {
                    store.exportInventoryCSV()
                }
                exportRow(title: "Definitions", subtitle: "\(store.defs.count) antibodies", icon: "list.bullet.rectangle") {
                    store.exportDefinitionsCSV()
                }
                exportRow(title: "Scan log", subtitle: "\(store.scans.count) scans with verdicts and reasons", icon: "doc.text.magnifyingglass") {
                    store.exportScanLogCSV()
                }
                exportRow(title: "Full backup", subtitle: "Everything, as JSON — restorable on another phone", icon: "externaldrive") {
                    store.exportBackupJSON()
                }
                Divider().overlay(Theme.hairline)
                Button { picking = .backup } label: {
                    Label("Restore from a backup file", systemImage: "arrow.uturn.backward")
                        .font(Theme.label).foregroundStyle(Theme.accent)
                }
            }
        }
    }

    private func exportRow(title: String, subtitle: String, icon: String,
                           make: @escaping () -> URL?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 16)).foregroundStyle(Theme.textSecond)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.text)
                Text(subtitle).font(Theme.caption).foregroundStyle(Theme.textSecond).lineLimit(2)
            }
            Spacer(minLength: 6)
            ShareableExport(make: make)
        }
        .padding(.vertical, 2)
    }

    // MARK: Storage

    private var storageCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "Clips")
                KeyValueRow(key: "Kept clips", value: "\(store.scans.filter { $0.clipFile != nil }.count)")
                KeyValueRow(key: "On disk", value: ByteCountFormatter.string(fromByteCount: store.clipBytes, countStyle: .file))
                KeyValueRow(key: "Auto-delete after",
                            value: store.settings.clipRetentionDays > 0
                            ? "\(store.settings.clipRetentionDays) days"
                            : "never")
                Text("Clips attached to an unresolved review are never auto-deleted.")
                    .font(Theme.caption).foregroundStyle(Theme.textSecond)
                Pill(title: "Delete every clip", icon: "trash", kind: .danger) { confirmPurge = true }
            }
        }
        .alert("Delete every clip?", isPresented: $confirmPurge) {
            Button("Delete", role: .destructive) {
                store.purgeAllClips()
                store.banner = AppStore.Banner(text: "All clips deleted")
            }
            Button("Keep", role: .cancel) {}
        } message: {
            Text("Scan records stay; the videos attached to them are removed, including ones still waiting for review.")
        }
    }

    private var dangerCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "Reset")
                Text("Puts the catalog back to the snapshot that shipped with the app. Vials received on this phone are lost.")
                    .font(.system(size: 14)).foregroundStyle(Theme.textSecond)
                Pill(title: "Reset catalog", icon: "arrow.counterclockwise", kind: .danger) {
                    confirmReset = true
                }
            }
        }
        .alert("Reset the catalog?", isPresented: $confirmReset) {
            Button("Reset", role: .destructive) { store.resetToBundled() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Export first if anything on this phone is not yet in Airtable.")
        }
    }
}

/// Generates the file on demand, then hands it to the share sheet. Doing it in
/// two taps rather than pre-building every export keeps the screen instant and
/// avoids writing four files nobody asked for.
struct ShareableExport: View {
    let make: () -> URL?
    @State private var url: URL?

    var body: some View {
        Group {
            if let url {
                ShareLink(item: url) {
                    Text("Share")
                        .font(Theme.caption)
                        .foregroundStyle(Theme.onSolid)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(Theme.solid))
                }
            } else {
                Button {
                    url = make()
                    Haptics.tap()
                } label: {
                    Text("Build")
                        .font(Theme.caption)
                        .foregroundStyle(Theme.text)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(Theme.fill))
                }
                .buttonStyle(PressScale())
            }
        }
    }
}
