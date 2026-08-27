import AVFoundation
import UIKit
import SwiftUI
import VialCore

/// The screen the app exists for.
///
/// Layout rules, all of them from watching people hold a vial in one hand:
/// the viewfinder is always live and always visible, every control that matters
/// sits in the bottom third where a thumb reaches, and the result never covers
/// the aiming rectangle — it slides up underneath it, so the next vial can be
/// lined up while the last one is still being decided.
struct ScanView: View {
    @ObservedObject var camera: CameraController
    let openMenu: () -> Void
    let goTo: (Route) -> Void

    @EnvironmentObject private var store: AppStore

    @State private var showNewVial = false
    @State private var newVialDraft = ItemRecord(abNo: "")
    @State private var keepClip = false
    @State private var countdown: Double = 0
    @State private var countdownTotal: Double = 1
    @State private var autoAction: AutoAction? = nil

    private enum AutoAction { case confirm, park, rescan }

    private let tick = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack(alignment: .bottom) {
            cameraLayer

            VStack(spacing: 0) {
                topBar
                    .padding(.top, Theme.safeAreaTop + 6)
                Spacer()
                bottomStack
                    .padding(.bottom, Theme.safeAreaBottom + 12)
            }
            .padding(.horizontal, 16)
        }
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            camera.requestAccessAndConfigure()
            camera.setTorch(store.settings.torchOnByDefault)
            keepClip = store.settings.alwaysKeepClips
        }
        .onDisappear { camera.stopSession() }
        .onChange(of: camera.phase) { _, phase in
            if phase == .finished { handleResult() }
        }
        .onReceive(tick) { _ in
            advanceCountdown()
            if store.settings.handsFree, camera.phase == .idle, autoAction == nil,
               camera.permission == .authorized {
                beginScan()
            }
        }
        .sheet(isPresented: $showNewVial) {
            NewVialSheet(draft: $newVialDraft) { saved in
                receive(saved)
            }
            .environmentObject(store)
        }
    }

    // MARK: - Camera layers

    private var cameraLayer: some View {
        ZStack {
            if camera.permission == .authorized {
                CameraPreview(session: camera.session) { point in
                    camera.focus(at: point)
                    Haptics.tap()
                }
                .ignoresSafeArea()
                Reticle(inset: store.settings.roiInset, active: camera.phase == .scanning)
                    .ignoresSafeArea()
            } else {
                permissionPane
            }
        }
    }

    private var permissionPane: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.metering.unknown")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.white.opacity(0.7))
            Text(camera.permission == .denied || camera.permission == .restricted
                 ? "Camera access is off"
                 : "Camera access needed")
                .font(Theme.title).foregroundStyle(.white)
            Text("The scanner reads the label and barcode straight from the camera. Nothing leaves the phone.")
                .font(.system(size: 14))
                .foregroundStyle(.white.opacity(0.75))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            if camera.permission == .denied || camera.permission == .restricted {
                Pill(title: "Open Settings", icon: "gear", kind: .solid, wide: false) {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            } else {
                Pill(title: "Allow camera", icon: "camera.fill", kind: .solid, wide: false) {
                    camera.requestAccessAndConfigure()
                }
            }
            if let err = camera.setupError {
                Text(err).font(Theme.caption).foregroundStyle(Theme.bad)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack(spacing: 10) {
            GlassCircleIcon(systemName: "line.3.horizontal") { openMenu() }

            if let audit = store.openAudit {
                Button { goTo(.audit) } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "checklist").font(.system(size: 12, weight: .bold))
                        Text("\(audit.title) · \(audit.foundSet.count)/\(audit.expected.count)")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12).frame(height: 34)
                    .background(Capsule().fill(Color.white))
                }
                .buttonStyle(PressScale())
            }

            Spacer()

            if camera.isRecording {
                HStack(spacing: 6) {
                    Circle().fill(Theme.bad).frame(width: 8, height: 8)
                    Text("REC").font(.system(size: 11, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 10).frame(height: 30)
                .background(Capsule().fill(Color.black.opacity(0.42)))
            }

            if camera.hasTorch {
                GlassCircleIcon(systemName: camera.torchOn ? "bolt.fill" : "bolt.slash",
                                active: camera.torchOn) {
                    camera.toggleTorch()
                    Haptics.tap()
                }
            }
        }
        .padding(.top, 6)
    }

    // MARK: - Bottom

    @ViewBuilder
    private var bottomStack: some View {
        VStack(spacing: 12) {
            switch camera.phase {
            case .idle:      idleControls
            case .scanning:  scanningControls
            case .finished:  resultCard
            }
        }
    }

    private var idleControls: some View {
        VStack(spacing: 14) {
            Text(store.settings.handsFree
                 ? "Hands-free — hold up the next vial"
                 : "Hold the vial in the frame and press scan")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white.opacity(0.85))
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Capsule().fill(Color.black.opacity(0.38)))

            HStack(spacing: 14) {
                handsFreeToggle

                Button {
                    Haptics.commit()
                    beginScan()
                } label: {
                    ZStack {
                        Circle().fill(Color.white).frame(width: 74, height: 74)
                        Circle().strokeBorder(Color.white.opacity(0.6), lineWidth: 2)
                            .frame(width: 86, height: 86)
                        Image(systemName: "viewfinder")
                            .font(.system(size: 26, weight: .semibold))
                            .foregroundStyle(.black)
                    }
                }
                .buttonStyle(PressScale())

                GlassCircleIcon(systemName: keepClip ? "video.fill" : "video.slash",
                                size: 50, active: keepClip) {
                    keepClip.toggle()
                    Haptics.tap()
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 4)
        }
    }

    private var handsFreeToggle: some View {
        GlassCircleIcon(systemName: store.settings.handsFree ? "waveform" : "hand.tap",
                        size: 50, active: store.settings.handsFree) {
            store.settings.handsFree.toggle()
            Haptics.tap()
            if store.settings.handsFree {
                Speaker.shared.say("Hands-free on", force: true)
            } else {
                Speaker.shared.stop()
                cancelAuto()
            }
        }
    }

    private var scanningControls: some View {
        VStack(spacing: 12) {
            HStack(spacing: 10) {
                Orb(active: true).frame(width: 22, height: 22)
                Text(camera.hint)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                Spacer()
                Text(String(format: "%.1fs", camera.elapsed))
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(Capsule().fill(Color.black.opacity(0.5)))

            // The bar tracks readiness to answer, not frames burned: elapsed
            // time, OCR passes and how much of the label has actually been
            // turned into view. A bar that fills at a fixed rate tells the user
            // nothing about whether rotating further would help.
            ProgressView(value: camera.progress)
                .progressViewStyle(.linear)
                .tint(.white)
                .frame(height: 3)
                .padding(.horizontal, 24)
                .animation(.easeOut(duration: 0.2), value: camera.progress)

            // Faces of the label seen so far. This is the one piece of engine
            // state worth showing outright: "keep rotating" is a nag, but a pip
            // that lights up when a new side registers tells the user what the
            // scanner is actually waiting for.
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { i in
                    Capsule()
                        .fill(.white.opacity(i < camera.views ? 0.95 : 0.25))
                        .frame(width: 16, height: 3)
                }
            }
            .animation(.easeOut(duration: 0.2), value: camera.views)

            HStack(spacing: 12) {
                Pill(title: "Cancel", kind: .quiet, wide: false) {
                    camera.cancelScan()
                    cancelAuto()
                }
                Pill(title: "Read what you have", icon: "text.viewfinder", kind: .solid, wide: false) {
                    camera.readNow()
                }
            }
            .padding(.bottom, 4)
        }
    }

    // MARK: - Result

    @ViewBuilder
    private var resultCard: some View {
        if let result = camera.result {
            ResultCard(
                result: result,
                clipURL: camera.pendingClip,
                keepClip: $keepClip,
                countdown: countdown,
                countdownTotal: countdownTotal,
                autoLabel: autoLabel,
                inAudit: store.openAudit != nil,
                onCancelAuto: cancelAuto,
                onConfirm: { confirm(result) },
                onRescan: { rescan() },
                onPark: { park(result) },
                onReject: { reject(result) },
                onReceive: { startReceive(result) },
                onChoose: { abNo in confirm(result, abNo: abNo) }
            )
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private var autoLabel: String? {
        switch autoAction {
        case .confirm: return "Saving"
        case .park:    return "Parking for review"
        case .rescan:  return "Next vial"
        case nil:      return nil
        }
    }

    // MARK: - Scan lifecycle

    private func beginScan() {
        cancelAuto()
        // Record whenever the operator asked for clips, and always in hands-free:
        // an unattended pass that gets it wrong is exactly the case where the
        // video is the only way to find out what happened.
        let record = keepClip || store.settings.alwaysKeepClips || store.settings.handsFree
        camera.startScan(recordClip: record)
    }

    private func handleResult() {
        guard let result = camera.result else { return }
        Haptics.result(result.verdict)
        if store.settings.handsFree {
            Speaker.shared.say(Speaker.phrase(for: result))
            scheduleAuto(for: result)
        }
    }

    private func scheduleAuto(for result: ScanResult) {
        // A scan that read nothing is not a result about a vial, and parking it
        // would file a clip and a blank row into the review queue for someone to
        // decode later — at which point all they can conclude is that the camera
        // never saw the label. Nothing to confirm, nothing to review: go back to
        // scanning and let them present it again.
        if result.readNothing {
            autoAction = .rescan
            countdownTotal = 1.5
            countdown = 1.5
            return
        }
        let meets = meetsAutoCommit(result.verdict)
        if meets, result.vial != nil {
            autoAction = .confirm
        } else if store.settings.handsFreeParksUncertain {
            autoAction = .park
        } else {
            autoAction = nil
            return
        }
        countdownTotal = max(1, store.settings.handsFreeDelay)
        countdown = countdownTotal
    }

    private func meetsAutoCommit(_ v: Verdict) -> Bool {
        let bar = store.settings.autoCommit
        switch bar {
        case .certain:   return v == .certain
        case .confident: return v == .certain || v == .confident
        default:         return v != .notFound
        }
    }

    private func advanceCountdown() {
        guard autoAction != nil, countdown > 0 else { return }
        countdown -= 0.1
        guard countdown <= 0 else { return }
        let action = autoAction
        autoAction = nil
        guard let result = camera.result else { return }
        switch action {
        case .confirm: confirm(result)
        case .park:    park(result)
        case .rescan, nil: rescan()
        }
    }

    private func cancelAuto() {
        autoAction = nil
        countdown = 0
    }

    private func rescan() {
        cancelAuto()
        camera.discardPendingClip()
        beginScan()
    }

    // MARK: - Committing a result

    /// Build the log entry once, so every outcome records the same evidence.
    private func makeRecord(_ result: ScanResult, outcome: ScanOutcome, clip: String?) -> ScanRecord {
        ScanRecord(
            verdict: result.verdict.rawValue,
            outcome: outcome,
            abNo: result.vial?.abNo,
            definitionName: result.definition?.name,
            catalog: result.fields[.catalog] ?? result.barcode?.catalog ?? result.definition?.catalog,
            lot: result.fields[.lot] ?? result.barcode?.lot,
            vendor: result.fields[.vendor],
            clone: result.fields[.clone],
            barcode: result.barcode?.raw,
            score: result.score,
            margin: result.margin,
            reasons: result.reasons,
            framesUsed: result.framesUsed,
            lotNotOnRecord: result.lotNotOnRecord,
            clipFile: clip,
            auditID: store.openAudit?.id
        )
    }

    /// `abNo` overrides the engine's pick. That happens when the lot was misread
    /// — the antibody is right, the vial the matcher chose is not, and the person
    /// holding the tube can see which of the listed vials it actually is.
    private func confirm(_ result: ScanResult, abNo overrideAbNo: String? = nil) {
        cancelAuto()
        let clip = (keepClip || store.settings.alwaysKeepClips)
            ? store.keepClip(at: camera.consumePendingClip())
            : nil
        if clip == nil { camera.discardPendingClip() }

        var built = makeRecord(result, outcome: .confirmed, clip: clip)
        if let overrideAbNo {
            built.abNo = overrideAbNo
            built.note = "Vial picked by hand — the engine could not tell these lots apart."
        }
        let record = store.log(built)

        if let abNo = overrideAbNo ?? result.vial?.abNo {
            store.markConfirmed(abNo: abNo, scanID: record.id)
            if let audit = store.openAudit {
                if audit.expected.contains(abNo) {
                    store.recordFound(abNo, in: audit.id, method: "scan", scanID: record.id)
                } else {
                    store.recordExtra(AuditExtra(label: "AbNo \(abNo) · \(result.definition?.name ?? "")",
                                                 catalog: result.definition?.catalog,
                                                 lot: result.vial?.lot,
                                                 scanID: record.id),
                                      in: audit.id)
                }
            }
        }
        Haptics.commit()
        store.banner = AppStore.Banner(text: bannerText(for: result, abNo: overrideAbNo))
        afterCommit()
    }

    private func bannerText(for result: ScanResult, abNo: String? = nil) -> String {
        if let abNo { return "AbNo \(abNo) confirmed" }
        if let vial = result.vial {
            return "AbNo \(vial.abNo) confirmed" + (result.lotNotOnRecord ? " — lot differs" : "")
        }
        if let def = result.definition { return "\(def.name) logged" }
        return "Scan logged"
    }

    private func park(_ result: ScanResult) {
        cancelAuto()
        // A parked scan always keeps its video, whatever the clip toggle says.
        // That is the entire point of parking: someone else has to be able to
        // see what the camera saw.
        let clip = store.keepClip(at: camera.consumePendingClip())
        store.log(makeRecord(result, outcome: .review, clip: clip))
        Haptics.commit()
        store.banner = AppStore.Banner(
            text: clip == nil ? "Parked for review (no clip was recorded)" : "Parked for review with clip")
        afterCommit()
    }

    private func reject(_ result: ScanResult) {
        cancelAuto()
        camera.discardPendingClip()
        // A rejected scan is worth logging because someone looked at a verdict
        // and disagreed with it. A scan that read nothing carries no verdict to
        // disagree with, and logging it only fills the history with blank rows.
        if !result.readNothing {
            store.log(makeRecord(result, outcome: .rejected, clip: nil))
        }
        afterCommit()
    }

    private func startReceive(_ result: ScanResult) {
        cancelAuto()
        var draft = ItemRecord(abNo: store.nextAbNo())
        draft.definitionName = result.definition?.name ?? ""
        draft.lot = result.fields[.lot] ?? result.barcode?.lot ?? ""
        draft.concentration = result.fields[.concentration] ?? ""
        draft.owner = store.settings.defaultOwner
        draft.location = store.settings.defaultLocation
        draft.box = store.settings.defaultBox
        draft.vialName = result.definition?.name ?? (result.fields[.catalog].map { "Cat \($0)" } ?? "")
        draft.notes = receivingNote(result)
        newVialDraft = draft
        showNewVial = true
    }

    private func receivingNote(_ result: ScanResult) -> String {
        var bits: [String] = []
        if let cat = result.fields[.catalog] ?? result.barcode?.catalog { bits.append("catalog \(cat)") }
        if let vendor = result.fields[.vendor] { bits.append(vendor) }
        if let clone = result.fields[.clone] { bits.append("clone \(clone)") }
        if let bc = result.barcode { bits.append("barcode \(bc.raw)") }
        return bits.isEmpty ? "" : "Read from label: " + bits.joined(separator: ", ")
    }

    private func receive(_ item: ItemRecord) {
        guard let result = camera.result else { return }
        let clip = (keepClip || store.settings.alwaysKeepClips)
            ? store.keepClip(at: camera.consumePendingClip())
            : nil
        if clip == nil { camera.discardPendingClip() }

        var record = makeRecord(result, outcome: .received, clip: clip)
        record.abNo = item.abNo
        record.definitionName = item.definitionName
        let logged = store.log(record)
        store.markConfirmed(abNo: item.abNo, scanID: logged.id)
        store.banner = AppStore.Banner(text: "AbNo \(item.abNo) received into inventory")
        Haptics.commit()
        afterCommit()
    }

    /// After anything is committed: keep the line moving in hands-free, go back
    /// to a live idle viewfinder otherwise.
    private func afterCommit() {
        camera.cancelScan()
        if store.settings.handsFree {
            autoAction = .rescan
            countdownTotal = 1.0
            countdown = 1.0
        }
    }
}
