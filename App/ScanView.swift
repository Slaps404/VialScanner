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
    @State private var showSessionBox = false
    @State private var newVialDraft = ItemRecord(abNo: "")
    @State private var newVialPrefillDef: DefRecord? = nil
    @State private var newVialSources: [String: FieldSource] = [:]
    @State private var keepClip = false
    @State private var countdown: Double = 0
    @State private var countdownTotal: Double = 1
    @State private var autoAction: AutoAction? = nil
    @State private var visualFocusPoint: CGPoint? = nil
    @State private var caption: String? = nil
    @State private var captionSeq = 0

    private enum AutoAction { case confirm, park, rescan }

    private let tick = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack(alignment: .bottom) {
            cameraLayer

            VStack(spacing: 0) {
                topBar
                captionBar
                Spacer()
                bottomStack
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            camera.autoTextFocusEnabled = store.settings.autoTextFocus
            camera.requestAccessAndConfigure()
            camera.setTorch(store.settings.torchOnByDefault)
            keepClip = store.settings.alwaysKeepClips
            if store.settings.preferUltraWideZoom, camera.hasUltraWide {
                camera.setZoom(0.5, animated: false)
            }
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
        .sheet(isPresented: $showSessionBox) {
            SessionBoxSheet().environmentObject(store)
        }
        .sheet(isPresented: $showNewVial) {
            NewVialSheet(draft: $newVialDraft, prefillDefinition: newVialPrefillDef, sources: newVialSources) { saved in
                receive(saved)
            }
            .environmentObject(store)
        }
    }

    // MARK: - Camera layers

    private var cameraLayer: some View {
        ZStack {
            if camera.permission == .authorized {
                CameraPreview(session: camera.session, onFocusTap: { devicePoint, viewPoint in
                    visualFocusPoint = viewPoint
                    camera.focus(at: devicePoint, lockFocus: false)
                    Haptics.tap()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        if !camera.isFocusLocked { visualFocusPoint = nil }
                    }
                }, onPinch: { scale in
                    camera.zoomBy(scale: scale)
                })
                .ignoresSafeArea()

                if store.settings.showTextTrackingHUD {
                    TextLockOverlay(boxes: camera.activeTextBoxes, isLocked: camera.isFocusLocked)
                        .ignoresSafeArea()
                }

                FocusTargetReticle(point: visualFocusPoint, isLocked: camera.isFocusLocked)
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

            sessionBoxButton

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

            // Focus Lock Toggle
            GlassCircleIcon(systemName: camera.isFocusLocked ? "lock.fill" : "lock.open",
                            active: camera.isFocusLocked) {
                camera.toggleFocusLock()
                Haptics.tap()
                if !camera.isFocusLocked { visualFocusPoint = nil }
                flash(camera.isFocusLocked ? "Focus locked" : "Focus following the label")
            }

            if camera.hasTorch {
                GlassCircleIcon(systemName: camera.torchOn ? "bolt.fill" : "bolt.slash",
                                active: camera.torchOn) {
                    camera.toggleTorch()
                    Haptics.tap()
                    flash(camera.torchOn ? "Light on" : "Light off")
                }
            }
        }
        .padding(.top, 6)
    }

    /// What the control you just pressed did, in words, for about a second.
    ///
    /// Every control up here is an icon, and an icon is only obvious to whoever
    /// chose it: a padlock could plausibly lock focus, lock exposure, or lock the
    /// whole screen. A hover tooltip is not available on a phone and a long-press
    /// one is a gesture nobody will discover either, so the caption rides on the
    /// tap that already happened and names the state the control just moved to.
    /// It sits under the top bar rather than at the finger, so it never appears
    /// beneath the hand that summoned it.
    @ViewBuilder
    private var captionBar: some View {
        if let caption {
            Text(caption)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(Color.black.opacity(0.55)))
                .padding(.top, 8)
                .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    /// Show a caption and retire it, unless another one has replaced it first.
    private func flash(_ text: String) {
        captionSeq += 1
        let seq = captionSeq
        withAnimation(.easeOut(duration: 0.15)) { caption = text }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
            guard seq == captionSeq else { return }
            withAnimation(.easeIn(duration: 0.3)) { caption = nil }
        }
    }

    /// Always visible, because a stamp nobody can see is a stamp nobody can
    /// correct. Filled white while it is stamping, an outline while it is not.
    @ViewBuilder
    private var sessionBoxButton: some View {
        if let box = store.activeSessionBox {
            Button { showSessionBox = true } label: {
                HStack(spacing: 6) {
                    Image(systemName: "shippingbox.fill").font(.system(size: 12, weight: .bold))
                    Text(box).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                }
                .foregroundStyle(.black)
                .padding(.horizontal, 12).frame(height: 34)
                .background(Capsule().fill(Color.white))
            }
            .buttonStyle(PressScale())
        } else {
            GlassCircleIcon(systemName: "shippingbox", size: 34) {
                flash("Stamp a box onto every vial you confirm")
                showSessionBox = true
            }
        }
    }

    // MARK: - Bottom

    @ViewBuilder
    private var bottomStack: some View {
        VStack(spacing: 10) {
            if camera.availableZoomOptions.count > 1, camera.phase != .finished {
                zoomBar
            }

            switch camera.phase {
            case .idle:      idleControls
            case .scanning:  scanningControls
            case .finished:  resultCard
            }
        }
    }

    private var zoomBar: some View {
        HStack(spacing: 6) {
            ForEach(camera.availableZoomOptions, id: \.self) { z in
                let isSelected = abs(camera.userZoom - z) < 0.1
                Button {
                    camera.setZoom(z)
                    Haptics.tap()
                    flash(z < 1 ? "Wide lens — focuses closest" : "\(zoomLabel(z)) zoom")
                } label: {
                    Text(zoomLabel(z))
                        .font(.system(size: 13, weight: isSelected ? .bold : .medium))
                        .foregroundStyle(isSelected ? .black : .white)
                        .frame(width: 44, height: 30)
                        .background(
                            Capsule().fill(isSelected ? Color.white : Color.black.opacity(0.48))
                        )
                }
                .buttonStyle(PressScale())
            }
        }
        .padding(4)
        .background(Capsule().fill(Color.black.opacity(0.38)))
    }

    /// Whole stops read as "1×"; anything else keeps a decimal, because a phone
    /// whose wide lens is at 0.615× should not be told it is at 0.5×.
    private func zoomLabel(_ z: CGFloat) -> String {
        z >= 1 && z == z.rounded() ? "\(Int(z))×" : String(format: "%.1f×", z)
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
                    flash(keepClip ? "Keeping a clip of the next scan" : "Not keeping clips")
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
            flash(store.settings.handsFree
                  ? "Hands-free — scans and saves on its own"
                  : "Hands-free off — press to scan")
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
        case .park:    return "Saving for review"
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

        var filed: String? = nil
        if let abNo = overrideAbNo ?? result.vial?.abNo {
            store.markConfirmed(abNo: abNo, scanID: record.id)
            // Confirming means someone is holding the tube. If they told us
            // which box they are working through, that is the moment we know
            // where it lives — better evidence than anything on the label.
            filed = store.stampSessionBox(abNo: abNo)
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
        var text = bannerText(for: result, abNo: overrideAbNo)
        if let filed { text += " · filed to \(filed)" }
        store.banner = AppStore.Banner(text: text)
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
            text: clip == nil ? "Saved for review (no clip was recorded)" : "Saved for review with clip")
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
        var sources: [String: FieldSource] = [:]

        draft.definitionName = result.definition?.name ?? ""
        draft.lot = result.fields[.lot] ?? result.barcode?.lot ?? ""
        if !draft.lot.isEmpty { sources["Lot"] = result.barcode?.lot != nil ? .barcode : .ocr }
        draft.concentration = result.fields[.concentration] ?? ""
        if !draft.concentration.isEmpty { sources["Concentration"] = .ocr }

        // Remembered rather than blank: the freezer drawer someone is standing
        // in front of is almost always the one they used last time, and a box
        // being worked through beats either.
        draft.owner = store.settings.defaultOwner
        if !draft.owner.isEmpty { sources["Owner"] = .remembered }
        draft.location = store.settings.defaultLocation
        if !draft.location.isEmpty { sources["Location"] = .remembered }
        draft.box = store.activeSessionBox ?? store.settings.defaultBox
        if !draft.box.isEmpty { sources["Box"] = .remembered }

        // NEW VIAL is the common outcome, not the edge case: 31 of 39 sample
        // clips end here. Build the definition the scan already read enough
        // for instead of leaving `vialName` as a "Cat 12345" placeholder that
        // someone has to notice and go fix by hand.
        if result.definition == nil {
            newVialPrefillDef = definitionDraft(from: result, sources: &sources)
            draft.vialName = newVialPrefillDef?.name ?? ""
        } else {
            newVialPrefillDef = nil
            draft.vialName = result.definition!.name
        }

        newVialSources = sources
        draft.notes = receivingNote(result)
        newVialDraft = draft
        showNewVial = true
    }

    /// A `DefRecord` built from whatever the scan managed to read, so the
    /// operator is correcting OCR mistakes rather than retyping a label from
    /// scratch. `sources` is filled in alongside so the definition sheet can
    /// show the same barcode/OCR badges the vial sheet does.
    private func definitionDraft(from result: ScanResult, sources: inout [String: FieldSource]) -> DefRecord? {
        let catalog = result.fields[.catalog] ?? result.barcode?.catalog ?? ""
        let clone = result.fields[.clone] ?? ""
        let vendor = result.fields[.vendor] ?? ""
        let isotype = result.fields[.isotype] ?? ""
        guard !catalog.isEmpty || !clone.isEmpty || !vendor.isEmpty else { return nil }

        if !catalog.isEmpty { sources["Catalog #"] = result.barcode?.catalog != nil ? .barcode : .ocr }
        if !clone.isEmpty { sources["Clone"] = .ocr }
        if !isotype.isEmpty { sources["Isotype"] = .ocr }
        if !vendor.isEmpty { sources["Vendor"] = .ocr }

        var name = [vendor, clone.isEmpty ? nil : "Clone \(clone)"].compactMap { $0 }.joined(separator: " ")
        if name.isEmpty { name = catalog.isEmpty ? "" : "Cat \(catalog)" }
        return DefRecord(name: name, clone: clone, isotype: isotype, vendor: vendor, catalog: catalog, isLocal: true)
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
        rememberReceivingDefaults(item)
        store.banner = AppStore.Banner(text: "AbNo \(item.abNo) received into inventory")
        Haptics.commit()
        afterCommit()
    }

    /// Whatever owner/location/box just got typed in becomes next scan's
    /// default. A lab receiving ten vials in a row types the same drawer ten
    /// times without this; a box already being worked through still wins, see
    /// `AppStore.activeSessionBox`.
    private func rememberReceivingDefaults(_ item: ItemRecord) {
        if !item.owner.isEmpty { store.settings.defaultOwner = item.owner }
        if !item.location.isEmpty { store.settings.defaultLocation = item.location }
        if store.activeSessionBox == nil, !item.box.isEmpty { store.settings.defaultBox = item.box }
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
