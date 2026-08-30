import SwiftUI
import VialCore

/// Set the box being worked through right now.
///
/// The Box column arrives from Airtable 11% filled, so a box audit covers
/// almost nothing. Backfilling it by hand is a job nobody does. Stamping it
/// instead is nearly free: the person is already holding the tube and already
/// knows which box they pulled it from, so the only thing missing is telling
/// the app once at the start of the run.
///
/// The stamp is deliberately not silent. It shows in the top bar for the whole
/// session and it names the box in the confirm banner, because a wrong box
/// applied to forty vials is worse than no box at all.
struct SessionBoxSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    @State private var box: String = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Card {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionLabel(text: "Box for this session")
                            Text("Every vial you confirm from now on is filed to this box. Leave it empty to stop stamping.")
                                .font(Theme.caption).foregroundStyle(Theme.textSecond)
                            SuggestField(title: "Box", text: $box, options: store.boxes)
                        }
                    }

                    if let current = store.activeSessionBox {
                        Card {
                            VStack(alignment: .leading, spacing: 6) {
                                SectionLabel(text: "Currently stamping")
                                KeyValueRow(key: "Box", value: current, mono: true)
                                KeyValueRow(key: "Vials on file here",
                                            value: "\(store.items.filter { $0.box == current }.count)")
                            }
                        }
                    }

                    Text("A box set more than 12 hours ago stops stamping on its own, so yesterday's run cannot re-file today's vials.")
                        .font(Theme.caption).foregroundStyle(Theme.textFaint)
                        .padding(.horizontal, 4)

                    VStack(spacing: 10) {
                        Pill(title: "Start stamping", icon: "shippingbox.fill", kind: .solid) {
                            store.setSessionBox(box)
                            Haptics.commit()
                            dismiss()
                        }
                        if store.activeSessionBox != nil {
                            Pill(title: "Stop stamping", icon: "xmark", kind: .outline) {
                                store.setSessionBox("")
                                Haptics.tap()
                                dismiss()
                            }
                        }
                    }
                }
                .padding(Theme.pad)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("Session box")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Theme.textSecond)
                }
            }
            .onAppear { box = store.activeSessionBox ?? store.settings.sessionBox }
        }
    }
}
