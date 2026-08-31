import SwiftUI
import VialCore

struct SettingsView: View {
    let openMenu: () -> Void
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Screen(title: "Settings", onLeading: openMenu) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    handsFree
                    capture
                    defaults
                    engine
                    about
                }
                .padding(.horizontal, Theme.pad)
                .padding(.bottom, 30)
            }
        }
    }

    // MARK: Hands-free

    private var handsFree: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                SectionLabel(text: "Hands-free")
                Toggle(isOn: $store.settings.handsFree) {
                    settingLabel("Hands-free mode",
                                 "Scans start on their own, results are spoken, and confident reads commit without a tap.")
                }
                .tint(Theme.accent)

                Toggle(isOn: $store.settings.speak) {
                    settingLabel("Speak results", "Says the AbNo and box out loud, digit by digit.")
                }
                .tint(Theme.accent)

                VStack(alignment: .leading, spacing: 6) {
                    settingLabel("Pause before committing",
                                 "How long a result stays on screen so you can catch a wrong one.")
                    HStack {
                        Slider(value: $store.settings.handsFreeDelay, in: 1...10, step: 0.5)
                            .tint(Theme.accent)
                        Text(String(format: "%.1fs", store.settings.handsFreeDelay))
                            .font(Theme.mono).foregroundStyle(Theme.textSecond)
                            .frame(width: 50, alignment: .trailing)
                    }
                }

                VStack(alignment: .leading, spacing: 8) {
                    settingLabel("Commit automatically when",
                                 "A barcode read is effectively certain. Label-text matches clear a score and a margin, which is a lower bar.")
                    HStack(spacing: 8) {
                        autoBarButton(.certain, "Barcode only")
                        autoBarButton(.confident, "Barcode or text")
                    }
                }

                Toggle(isOn: $store.settings.handsFreeParksUncertain) {
                    settingLabel("Park anything below that bar",
                                 "Uncertain reads go to the review queue with their clip instead of stopping the run.")
                }
                .tint(Theme.accent)
            }
        }
    }

    private func autoBarButton(_ v: Verdict, _ label: String) -> some View {
        let selected = store.settings.autoCommit == v
        return Button {
            store.settings.autoCommitVerdict = v.rawValue
            Haptics.tap()
        } label: {
            Text(label)
                .font(Theme.caption)
                .foregroundStyle(selected ? Theme.onSolid : Theme.textSecond)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Capsule().fill(selected ? Theme.solid : Theme.fill))
        }
        .buttonStyle(PressScale())
    }

    // MARK: Capture

    private var capture: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                SectionLabel(text: "Camera & clips")
                Toggle(isOn: $store.settings.alwaysKeepClips) {
                    settingLabel("Always keep clips",
                                 "Keeps the video for every scan, not just parked ones.")
                }
                .tint(Theme.accent)

                VStack(alignment: .leading, spacing: 6) {
                    settingLabel("Delete clips after", "Saved scans keep theirs until they are resolved.")
                    HStack(spacing: 8) {
                        ForEach([0, 7, 30, 90], id: \.self) { days in
                            Button {
                                store.settings.clipRetentionDays = days
                                Haptics.tap()
                            } label: {
                                Text(days == 0 ? "Never" : "\(days)d")
                                    .font(Theme.caption)
                                    .foregroundStyle(store.settings.clipRetentionDays == days ? Theme.onSolid : Theme.textSecond)
                                    .padding(.horizontal, 14).padding(.vertical, 8)
                                    .background(Capsule().fill(store.settings.clipRetentionDays == days ? Theme.solid : Theme.fill))
                            }
                            .buttonStyle(PressScale())
                        }
                    }
                }

                Toggle(isOn: $store.settings.preferUltraWideZoom) {
                    settingLabel("Prefer 0.5× Ultra-Wide / Macro", "Default to 0.5× lens for ~2 cm close focus on small vials.")
                }
                .tint(Theme.accent)

                Toggle(isOn: $store.settings.autoTextFocus) {
                    settingLabel("Auto text focus tracking", "Dynamically steers camera lens focus toward detected label text.")
                }
                .tint(Theme.accent)

                Toggle(isOn: $store.settings.showTextTrackingHUD) {
                    settingLabel("Show text tracking boxes", "Displays live visual bounding boxes around scanned label lines.")
                }
                .tint(Theme.accent)

                Toggle(isOn: $store.settings.torchOnByDefault) {
                    settingLabel("Torch on by default", "Half power — full brightness turns a glass vial into a mirror.")
                }
                .tint(Theme.accent)

                Toggle(isOn: $store.settings.haptics) {
                    settingLabel("Haptics", "A different buzz for identified, unsure, and new.")
                }
                .tint(Theme.accent)
            }
        }
    }

    // MARK: Defaults

    private var defaults: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(text: "Receiving defaults")
                Text("Pre-filled whenever you receive a vial, so a run of new tubes needs three fields instead of six.")
                    .font(Theme.caption).foregroundStyle(Theme.textSecond)
                SuggestField(title: "Owner", text: $store.settings.defaultOwner, options: store.owners)
                SuggestField(title: "Location", text: $store.settings.defaultLocation, options: store.locations)
                SuggestField(title: "Box", text: $store.settings.defaultBox, options: store.boxes)
            }
        }
    }

    // MARK: Engine

    private var engine: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                SectionLabel(text: "Engine")

                VStack(alignment: .leading, spacing: 6) {
                    settingLabel("Look for at least",
                                 "Nothing is decided before this. The scanner used to answer off the first frame that happened to read, which is right often enough that the times it is wrong go unnoticed.")
                    HStack {
                        Slider(value: $store.settings.minScanSeconds, in: 0.4...3, step: 0.1)
                            .tint(Theme.accent)
                        Text(String(format: "%.1fs", store.settings.minScanSeconds))
                            .font(Theme.mono).foregroundStyle(Theme.textSecond)
                            .frame(width: 50, alignment: .trailing)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    settingLabel("Sides of the label to see",
                                 "A verdict waits until this many distinctly different faces of the label have been read — the scanner's way of knowing the vial was actually turned. Drop to 1 if your labels say everything on one side.")
                    HStack {
                        Slider(value: Binding(
                            get: { Double(store.settings.minDistinctViews) },
                            set: { store.settings.minDistinctViews = Int($0) }),
                               in: 1...3, step: 1)
                        .tint(Theme.accent)
                        Text("\(store.settings.minDistinctViews)")
                            .font(Theme.mono).foregroundStyle(Theme.textSecond)
                            .frame(width: 50, alignment: .trailing)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    settingLabel("Give up after",
                                 "A match resolves in about four OCR passes. Without a cap, failing takes longer than succeeding.")
                    HStack {
                        Slider(value: $store.settings.maxScanSeconds, in: 2...10, step: 0.5)
                            .tint(Theme.accent)
                        Text(String(format: "%.1fs", store.settings.maxScanSeconds))
                            .font(Theme.mono).foregroundStyle(Theme.textSecond)
                            .frame(width: 50, alignment: .trailing)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    settingLabel("OCR passes at most",
                                 "Each pass costs about 50 ms of one background thread.")
                    HStack {
                        Slider(value: Binding(
                            get: { Double(store.settings.maxOCRFrames) },
                            set: { store.settings.maxOCRFrames = Int($0) }),
                               in: 6...40, step: 1)
                        .tint(Theme.accent)
                        Text("\(store.settings.maxOCRFrames)")
                            .font(Theme.mono).foregroundStyle(Theme.textSecond)
                            .frame(width: 50, alignment: .trailing)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    settingLabel("Aiming rectangle",
                                 "Text outside the rectangle is never read. A tighter crop is faster and rejects the freezer behind the vial.")
                    HStack {
                        Slider(value: $store.settings.roiInset, in: 0.05...0.30, step: 0.01)
                            .tint(Theme.accent)
                        Text(String(format: "%.0f%%", store.settings.roiInset * 100))
                            .font(Theme.mono).foregroundStyle(Theme.textSecond)
                            .frame(width: 50, alignment: .trailing)
                    }
                }

                Notice(text: "Changes take effect on the next scan.", tint: Theme.accent, icon: "info.circle")
            }
        }
    }

    // MARK: About

    private var about: some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "About")
                KeyValueRow(key: "Definitions", value: "\(store.defs.count)")
                KeyValueRow(key: "Vials", value: "\(store.items.count)")
                KeyValueRow(key: "Scans logged", value: "\(store.scans.count)")
                KeyValueRow(key: "Checks run", value: "\(store.audits.count)")
                Text("Everything runs on this phone. No frame, clip or record is uploaded anywhere; export is the only way data leaves.")
                    .font(Theme.caption).foregroundStyle(Theme.textSecond)
                    .padding(.top, 4)
            }
        }
    }

    private func settingLabel(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.text)
            Text(detail).font(Theme.caption).foregroundStyle(Theme.textSecond)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
