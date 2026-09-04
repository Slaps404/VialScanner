import AVFoundation
import Combine
import CoreMedia
import CoreVideo
import Foundation
import UIKit
import VialCore

/// Owns the capture session, optics (focus, zoom, macro, torch), and feeds frames to the engine.
///
/// Frames are processed *during* the rotation, not after it. By the time the
/// user stops turning the vial the answer already exists, so the result card is
/// animation time rather than compute time.
///
/// The session stays running between scans. A viewfinder that goes black
/// between vials makes people wait for it; a live one lets them line up the
/// next tube while the last card is still on screen.
final class CameraController: NSObject, ObservableObject {

    enum Phase: Equatable {
        case idle
        case scanning
        case finished

        static func == (a: Phase, b: Phase) -> Bool {
            switch (a, b) {
            case (.idle, .idle), (.scanning, .scanning), (.finished, .finished): return true
            default: return false
            }
        }
    }

    // Published on the main thread only.
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var hint = "Hold the vial in the frame"
    @Published private(set) var framesSeen = 0
    @Published private(set) var ocrFrames = 0
    /// Distinct faces of the label read so far — rotation, as the engine sees it.
    @Published private(set) var views = 0
    /// Readiness to answer, 0–1. Not a frame count: see `ScanState.scanning`.
    @Published private(set) var progress: Double = 0
    @Published private(set) var elapsed: Double = 0
    @Published private(set) var result: ScanResult?
    @Published private(set) var torchOn = false
    @Published private(set) var hasTorch = false
    @Published private(set) var permission: AVAuthorizationStatus = .notDetermined
    @Published private(set) var setupError: String?
    /// Temp file for the scan that just finished. Kept only if someone says so.
    @Published private(set) var pendingClip: URL?
    @Published private(set) var isRecording = false

    // MARK: - Zoom & Optics properties
    /// User-facing zoom factor (0.5 = Ultra-Wide/Macro, 1.0 = Standard Wide, 2.0 = Close-up digital/tele).
    @Published private(set) var userZoom: Double = 1.0
    @Published private(set) var minUserZoom: Double = 1.0
    @Published private(set) var maxUserZoom: Double = 5.0
    @Published private(set) var availableZoomOptions: [Double] = [1.0, 2.0]
    @Published private(set) var hasUltraWide: Bool = false

    // MARK: - Text Tracking & Focus Lock properties
    @Published private(set) var activeTextBoxes: [CGRect] = []
    @Published private(set) var isFocusLocked: Bool = false
    @Published private(set) var focusPoint: CGPoint? = nil
    var autoTextFocusEnabled: Bool = true

    let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "vial.capture", qos: .userInitiated)
    /// Vision work runs off the capture queue so a slow OCR pass can never stall
    /// the camera or drop preview frames.
    private let visionQueue = DispatchQueue(label: "vial.vision", qos: .userInitiated)

    private var engine: ScanEngine
    private var catalog: Catalog
    private var config: EngineConfig
    private var device: AVCaptureDevice?
    private let recorder = ClipRecorder()

    private let lock = NSLock()
    private var scanning = false
    private var busy = false
    private var recordClip = false
    private var startedAt = Date()
    private var scanID = UUID()
    private var configured = false
    private var timer: Timer?
    private var lastAutoFocusUpdate = Date.distantPast
    /// Captured in memory while a scan runs. It is moved to the result card only
    /// after the engine and clip have finished, so a failed/cancelled scan leaves
    /// no half-written diagnostic file behind.
    private var debugCapture: ScanDebugRecorder?
    private var pendingDebugArtifact: ScanDebugArtifact?
    private var debugEngineFrame = 0
    private var debugFramesSeen = 0
    private var debugOCRFrames = 0

    init(catalog: Catalog, config: EngineConfig = EngineConfig()) {
        self.catalog = catalog
        self.config = config
        self.engine = ScanEngine(catalog: catalog, config: config)
        super.init()
        installDebugHooks(on: self.engine)
    }

    deinit { timer?.invalidate() }

    // MARK: - Catalog / settings changes

    /// A vial received mid-session must be findable by the next scan, so the
    /// engine is rebuilt whenever the catalog or the tuning changes. It is a
    /// cheap object; the indexes it holds are rebuilt in milliseconds.
    func reconfigure(catalog: Catalog, config: EngineConfig) {
        self.catalog = catalog
        self.config = config
        visionQueue.async { [weak self] in
            guard let self else { return }
            let fresh = ScanEngine(catalog: catalog, config: config)
            self.installDebugHooks(on: fresh)
            self.lock.lock(); self.engine = fresh; self.lock.unlock()
        }
    }

    /// Recognizer callbacks fire synchronously inside `ScanEngine.ingest`, so
    /// they can be attached without a second OCR path or any per-frame logging.
    private func installDebugHooks(on engine: ScanEngine) {
        engine.onBarcode = { [weak self] barcode in
            guard let self else { return }
            self.activeDebugCapture()?.recordBarcode(barcode)
        }
        engine.onOCRFrame = { [weak self] observations in
            guard let self else { return }
            self.activeDebugCapture()?.recordOCR(observations)
        }
    }

    private func activeDebugCapture() -> ScanDebugRecorder? {
        lock.lock(); defer { lock.unlock() }
        return debugCapture
    }

    // MARK: - Lifecycle

    func requestAccessAndConfigure() {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        publish { self.permission = status }
        switch status {
        case .authorized:
            configureIfNeeded()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                self.publish { self.permission = granted ? .authorized : .denied }
                if granted { self.configureIfNeeded() }
            }
        default:
            break
        }
    }

    private func configureIfNeeded() {
        queue.async { [weak self] in
            guard let self else { return }
            if !self.configured {
                self.configureSession()
                self.configured = true
            }
            if !self.session.isRunning { self.session.startRunning() }
            self.applyTorch(self.torchOn)
        }
    }

    private func configureSession() {
        session.beginConfiguration()
        // 1080p is enough: the DataMatrix codes decode reliably at this width and
        // 4K would triple the per-frame conversion cost for no accuracy gain.
        session.sessionPreset = .hd1920x1080

        // Multi-Camera Discovery: Discover virtual multi-camera systems that enable
        // seamless ultra-wide (0.5x macro) through wide and telephoto zoom.
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInTripleCamera,
                .builtInDualWideCamera,
                .builtInUltraWideCamera,
                .builtInWideAngleCamera
            ],
            mediaType: .video,
            position: .back
        )

        guard let device = discovery.devices.first
                ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            publish { self.setupError = "No usable back camera on this device." }
            return
        }
        session.addInput(input)
        self.device = device

        // Determine zoom and ultra-wide capabilities
        let isVirtualMultiCam = device.deviceType == .builtInTripleCamera || device.deviceType == .builtInDualWideCamera
        let isUltraWideOnly = device.deviceType == .builtInUltraWideCamera
        let ultraWideAvailable = isVirtualMultiCam || isUltraWideOnly

        let minZ: Double = ultraWideAvailable ? 0.5 : 1.0
        let maxZ: Double = min(10.0, Double(device.maxAvailableVideoZoomFactor))
        let options: [Double] = ultraWideAvailable ? [0.5, 1.0, 2.0] : [1.0, 2.0]

        // Close focus matters more than anything else here — the label is ~5–10 cm away.
        try? device.lockForConfiguration()
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isAutoFocusRangeRestrictionSupported { device.autoFocusRangeRestriction = .near }
        if device.isSmoothAutoFocusSupported { device.isSmoothAutoFocusEnabled = false }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }

        // Set default zoom factor
        let defaultUserZoom = ultraWideAvailable ? 0.5 : 1.0
        let initialAvZoom = avZoomFactor(for: defaultUserZoom, device: device)
        device.videoZoomFactor = max(device.minAvailableVideoZoomFactor, min(device.maxAvailableVideoZoomFactor, initialAvZoom))
        device.unlockForConfiguration()

        // Native biplanar YUV: Vision reads the luma plane with no conversion.
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String:
                kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()

        let torchAvailable = device.hasTorch
        publish {
            self.hasTorch = torchAvailable
            self.hasUltraWide = ultraWideAvailable
            self.minUserZoom = minZ
            self.maxUserZoom = maxZ
            self.availableZoomOptions = options
            self.userZoom = defaultUserZoom
        }
    }

    func stopSession() {
        endScan(discardClip: true)
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
        }
        setTorch(false)
    }

    // MARK: - Zoom Controls

    private func avZoomFactor(for userFactor: Double, device: AVCaptureDevice) -> CGFloat {
        if device.deviceType == .builtInTripleCamera || device.deviceType == .builtInDualWideCamera {
            // In virtual multi-camera systems, 1.0 = ultra-wide (0.5x), 2.0 = wide (1.0x), 4.0 = 2.0x
            return CGFloat(userFactor * 2.0)
        } else if device.deviceType == .builtInUltraWideCamera {
            return CGFloat(userFactor / 0.5)
        } else {
            return CGFloat(userFactor)
        }
    }

    func setZoom(_ factor: Double, animated: Bool = true) {
        let clamped = max(minUserZoom, min(maxUserZoom, factor))
        publish { self.userZoom = clamped }
        queue.async { [weak self] in
            guard let self, let device = self.device else { return }
            let avFactor = self.avZoomFactor(for: clamped, device: device)
            let safeFactor = max(device.minAvailableVideoZoomFactor, min(device.maxAvailableVideoZoomFactor, avFactor))
            try? device.lockForConfiguration()
            if animated {
                device.ramp(toVideoZoomFactor: safeFactor, withRate: 8.0)
            } else {
                device.videoZoomFactor = safeFactor
            }
            device.unlockForConfiguration()
        }
    }

    func zoomBy(scale: CGFloat) {
        let newZoom = userZoom * Double(scale)
        setZoom(newZoom, animated: false)
    }

    // MARK: - Focus & Text Lock-In

    /// Tap-to-focus from the viewfinder, in normalised device coordinates.
    func focus(at point: CGPoint, lockFocus: Bool = false) {
        publish {
            self.focusPoint = point
            self.isFocusLocked = lockFocus
        }
        queue.async { [weak self] in
            guard let device = self?.device else { return }
            try? device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = point
                if lockFocus {
                    if device.isFocusModeSupported(.autoFocus) { device.focusMode = .autoFocus }
                } else {
                    if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
                }
            }
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = point
                if device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposureMode = .continuousAutoExposure
                }
            }
            device.unlockForConfiguration()
        }
    }

    /// Toggle optical focus lock on the current focal distance to prevent hunting.
    func toggleFocusLock() {
        setFocusLocked(!isFocusLocked)
    }

    func setFocusLocked(_ locked: Bool) {
        publish { self.isFocusLocked = locked }
        queue.async { [weak self] in
            guard let device = self?.device else { return }
            try? device.lockForConfiguration()
            if locked {
                if device.isFocusModeSupported(.locked) {
                    device.focusMode = .locked
                }
            } else {
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    device.focusMode = .continuousAutoFocus
                }
            }
            device.unlockForConfiguration()
        }
    }

    /// Reset to continuous auto-focus and centered point of interest.
    func resetFocus() {
        publish {
            self.isFocusLocked = false
            self.focusPoint = nil
        }
        queue.async { [weak self] in
            guard let device = self?.device else { return }
            try? device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = CGPoint(x: 0.5, y: 0.5)
                if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            }
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = CGPoint(x: 0.5, y: 0.5)
                if device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposureMode = .continuousAutoExposure
                }
            }
            device.unlockForConfiguration()
        }
    }

    /// Automatically adjust optical focus to track detected text centroid in real-time.
    private func updateTextAutoFocus(with boxes: [CGRect]) {
        guard autoTextFocusEnabled, !isFocusLocked, !boxes.isEmpty else { return }
        let now = Date()
        guard now.timeIntervalSince(lastAutoFocusUpdate) > 0.4 else { return }
        lastAutoFocusUpdate = now

        // Calculate centroid of detected text boxes in Vision normalized space (0..1)
        let midX = boxes.map(\.midX).reduce(0, +) / Double(boxes.count)
        let midY = boxes.map(\.midY).reduce(0, +) / Double(boxes.count)

        // Convert Vision coordinate (bottom-left 0,0) to AVCaptureDevice coordinate (portrait 0,0 top-right)
        let devicePoint = CGPoint(x: 1.0 - midY, y: midX)

        queue.async { [weak self] in
            guard let device = self?.device else { return }
            try? device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = devicePoint
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    device.focusMode = .continuousAutoFocus
                }
            }
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = devicePoint
                if device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposureMode = .continuousAutoExposure
                }
            }
            device.unlockForConfiguration()
        }
    }

    // MARK: - Scanning

    func startScan(recordClip: Bool, accuracyCapture: Bool = false) {
        guard permission == .authorized else { requestAccessAndConfigure(); return }
        configureIfNeeded()

        ClipRecorder.discard(pendingClip)
        discardPendingDebugArtifact()
        result = nil
        framesSeen = 0
        ocrFrames = 0
        views = 0
        progress = 0
        elapsed = 0
        hint = "Rotate the vial slowly"
        phase = .scanning
        pendingClip = nil
        startedAt = Date()
        scanID = UUID()
        activeTextBoxes = []
        debugEngineFrame = 0
        debugFramesSeen = 0
        debugOCRFrames = 0

        let trace = recordClip
            ? ScanDebugRecorder(mode: accuracyCapture ? .accuracy : .performance,
                                scanID: scanID, startedAt: startedAt,
                                catalog: catalog, config: config)
            : nil

        lock.lock()
        engine.reset()
        scanning = true
        busy = false
        self.recordClip = recordClip
        debugCapture = trace
        lock.unlock()

        isRecording = recordClip
        startTicking()
    }

    /// The operator stopped rotating without a decisive read — resolve on what
    /// evidence exists rather than making them wait out the cap.
    func readNow() {
        guard phase == .scanning else { return }
        visionQueue.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let engine = self.engine
            self.lock.unlock()
            let r = engine.finish()
            self.deliver(r)
        }
    }

    func cancelScan() {
        endScan(discardClip: true)
        publish {
            self.phase = .idle
            self.result = nil
            self.activeTextBoxes = []
            self.hint = "Hold the vial in the frame"
        }
    }

    /// Called once the operator has decided what happens to the clip.
    func consumePendingClip() -> URL? {
        let url = pendingClip
        pendingClip = nil
        return url
    }

    /// The metadata stays in memory until the matching clip is either kept or
    /// discarded. Callers that keep a clip must consume both values together.
    func consumePendingDebugArtifact() -> ScanDebugArtifact? {
        lock.lock(); defer { lock.unlock() }
        let artifact = pendingDebugArtifact
        pendingDebugArtifact = nil
        return artifact
    }

    func discardPendingDebugArtifact() {
        lock.lock(); pendingDebugArtifact = nil; lock.unlock()
    }

    func discardPendingClip() {
        ClipRecorder.discard(pendingClip)
        pendingClip = nil
        discardPendingDebugArtifact()
    }

    private func endScan(discardClip: Bool) {
        lock.lock()
        scanning = false
        let wasRecording = recordClip
        recordClip = false
        debugCapture = nil
        lock.unlock()
        stopTicking()
        publish {
            self.isRecording = false
            self.activeTextBoxes = []
        }
        if wasRecording, discardClip {
            queue.async { [recorder] in recorder.cancel() }
        }
        if discardClip { discardPendingDebugArtifact() }
    }

    private func deliver(_ r: ScanResult) {
        lock.lock()
        guard scanning else { lock.unlock(); return }
        scanning = false
        let wasRecording = recordClip
        recordClip = false
        let trace = debugCapture
        debugCapture = nil
        let framesSeen = debugFramesSeen
        let ocrFrames = debugOCRFrames
        lock.unlock()

        stopTicking()

        let artifact = trace?.finish(
            result: r,
            evidence: engine.accumulated,
            endedAt: Date(),
            framesSeen: framesSeen,
            ocrFramesUsed: ocrFrames,
            roiInset: roiInset,
            optics: debugOpticsMetadata()
        )
        lock.lock()
        pendingDebugArtifact = artifact
        lock.unlock()

        guard wasRecording else {
            publishResult(r, clip: nil)
            return
        }
        // Finishing the writer is asynchronous, and the card must not appear
        // before the file it offers to keep actually exists.
        queue.async { [weak self] in
            guard let self else { return }
            self.recorder.finish { url in self.publishResult(r, clip: url) }
        }
    }

    private func publishResult(_ r: ScanResult, clip: URL?) {
        publish {
            self.pendingClip = clip
            self.isRecording = false
            self.result = r
            self.phase = .finished
            self.activeTextBoxes = []
            self.hint = r.verdict.headline
        }
    }

    private var roiInset: Double {
        // The trace keeps the engine's actual rectangle. This value is only a
        // compact compatibility field for the Android capture metadata shape.
        let roi = config.regionOfInterest
        guard let roi else { return 0 }
        return max(0, min(0.5, min(roi.minX, roi.minY / 1.3)))
    }

    /// Snapshot optics on the Vision queue after the final frame. Device and
    /// format properties are read-only here, while the session is still alive.
    private func debugOpticsMetadata() -> DebugOpticsMetadata {
        guard let device else {
            return DebugOpticsMetadata(deviceType: nil, position: nil,
                                       sessionPreset: session.sessionPreset.rawValue,
                                       activeFormatWidth: nil, activeFormatHeight: nil,
                                       pixelFormat: nil, userZoom: userZoom,
                                       avZoomFactor: nil, minZoom: nil, maxZoom: nil,
                                       focusMode: nil, autofocusRangeRestriction: nil,
                                       focusLocked: isFocusLocked, focusPoint: focusPoint.map(DebugPoint.init),
                                       torchOn: torchOn, hasTorch: hasTorch)
        }
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let pixel = output.videoSettings[kCVPixelBufferPixelFormatTypeKey as String] as? NSNumber
        let pixelFormat = pixel.map { String(format: "0x%08X", $0.uint32Value) }
        return DebugOpticsMetadata(
            deviceType: device.deviceType.rawValue,
            position: device.position == .back ? "back" : device.position == .front ? "front" : "unspecified",
            sessionPreset: session.sessionPreset.rawValue,
            activeFormatWidth: Int(dims.width),
            activeFormatHeight: Int(dims.height),
            pixelFormat: pixelFormat,
            userZoom: userZoom,
            avZoomFactor: Double(device.videoZoomFactor),
            minZoom: Double(device.minAvailableVideoZoomFactor),
            maxZoom: Double(device.maxAvailableVideoZoomFactor),
            focusMode: device.debugFocusMode,
            autofocusRangeRestriction: device.debugAutofocusRangeRestriction,
            focusLocked: isFocusLocked,
            focusPoint: focusPoint.map(DebugPoint.init),
            torchOn: torchOn,
            hasTorch: device.hasTorch
        )
    }

    // MARK: - Torch

    func setTorch(_ on: Bool) {
        publish { self.torchOn = on }
        queue.async { [weak self] in self?.applyTorch(on) }
    }

    func toggleTorch() { setTorch(!torchOn) }

    private func applyTorch(_ on: Bool) {
        guard let device, device.hasTorch, device.isTorchAvailable else { return }
        try? device.lockForConfiguration()
        if on {
            // Half power: full brightness on a glass vial 10 cm away is a mirror.
            try? device.setTorchModeOn(level: 0.5)
        } else {
            device.torchMode = .off
        }
        device.unlockForConfiguration()
    }

    // MARK: - Timing

    private func startTicking() {
        stopTicking()
        let t = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self else { return }
            DispatchQueue.main.async { self.elapsed = Date().timeIntervalSince(self.startedAt) }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func stopTicking() {
        timer?.invalidate()
        timer = nil
    }

    private func publish(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }
}

// MARK: - Frame delivery

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {

    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {

        lock.lock()
        let isScanning = scanning
        let wantsClip = recordClip
        let alreadyBusy = busy
        let trace = debugCapture
        if isScanning, !alreadyBusy { busy = true }
        let engine = self.engine
        let engineConfig = config
        lock.unlock()

        guard isScanning else { return }

        let format = CMSampleBufferGetFormatDescription(sampleBuffer)
        let dimensions = format.map(CMVideoFormatDescriptionGetDimensions)
        let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        let pixelFormat = pixelBuffer.map { String(format: "0x%08X", CVPixelBufferGetPixelFormatType($0)) }
        let ts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
        let sequence = trace?.capture(timestamp: ts,
                                      width: Int(dimensions?.width ?? 0),
                                      height: Int(dimensions?.height ?? 0),
                                      pixelFormat: pixelFormat)

        if wantsClip {
            if !recorder.isRecording { recorder.begin(with: sampleBuffer) }
            recorder.append(sampleBuffer)
        }

        // Drop frames while a pass is in flight rather than queueing them; stale
        // frames are worth nothing and the buffer pool is finite.
        guard !alreadyBusy else {
            if let sequence { trace?.markDropped(sequence, reason: "vision_busy") }
            return
        }
        guard let pb = pixelBuffer else {
            if let sequence { trace?.markDropped(sequence, reason: "no_pixel_buffer") }
            lock.lock(); busy = false; lock.unlock()
            return
        }
        guard let sequence else {
            // A trace is normally present whenever a clip is being recorded.
            // The engine still runs when it is not, so keep this path separate.
            visionQueue.async { [weak self] in
                guard let self else { return }
                let frame = engine.makeFrame(pixelBuffer: pb, timestamp: ts)
                let state = engine.ingest(frame)
                self.lock.lock(); self.busy = false; self.lock.unlock()
                self.handle(state)
            }
            return
        }

        visionQueue.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let stillScanning = self.scanning
            self.debugEngineFrame += 1
            let engineFrame = self.debugEngineFrame
            self.lock.unlock()
            guard stillScanning else {
                trace?.markCanceled(sequence)
                self.lock.lock(); self.busy = false; self.lock.unlock()
                return
            }

            let barcodeAttempted = engineConfig.barcodeEveryNthFrame > 0
                && (engineFrame - 1) % engineConfig.barcodeEveryNthFrame == 0
            trace?.beginProcessing(sequence, barcodeAttempted: barcodeAttempted)
            let started = DispatchTime.now().uptimeNanoseconds
            let frame = engine.makeFrame(pixelBuffer: pb, timestamp: ts)
            let state = engine.ingest(frame)
            let ingestMilliseconds = Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000

            var debugState: DebugEngineState?
            var resultFinished = false

            self.lock.lock()
            self.busy = false
            self.lock.unlock()

            switch state {
            case .scanning(let seen, let ocr, let views, let progress, let hint, let textBoxes):
                self.debugFramesSeen = seen
                self.debugOCRFrames = ocr
                debugState = DebugEngineState(kind: "scanning", framesSeen: seen,
                                              ocrFrames: ocr, views: views,
                                              progress: progress, hint: hint,
                                              textBoxes: textBoxes.map(DebugRect.init))
                self.updateTextAutoFocus(with: textBoxes)
                self.publish {
                    guard self.phase == .scanning else { return }
                    self.framesSeen = seen
                    self.ocrFrames = ocr
                    self.views = views
                    self.progress = progress
                    self.hint = hint
                    self.activeTextBoxes = textBoxes
                }
            case .finished(let r):
                resultFinished = true
                self.debugFramesSeen = engineFrame
                // `ScanResult.framesUsed` is the evidence-buffer count, which
                // includes empty OCR passes. Keep the engine's OCR counter from
                // the last scanning state instead.
                let finalOCRFrames = self.debugOCRFrames
                debugState = DebugEngineState(kind: "finished", framesSeen: engineFrame,
                                              ocrFrames: finalOCRFrames,
                                              views: engine.accumulated.distinctViews,
                                              progress: 1,
                                              hint: r.verdict.headline,
                                              textBoxes: [])
            }
            trace?.finishProcessing(sequence, engineFrame: engineFrame,
                                    state: debugState, resultFinished: resultFinished,
                                    ingestMilliseconds: ingestMilliseconds)
            if trace?.wantsImage(for: sequence) == true,
               let image = Self.accuracyImage(from: frame) {
                trace?.storeImage(image, for: sequence)
            }

            switch state {
            case .scanning:
                break
            case .finished(let r):
                self.deliver(r)
            }
        }
    }

    /// The recorder needs the same half-resolution luma image Vision receives.
    /// This is only called in accuracy mode and the image is retained in memory;
    /// Store writes it after the scan, so this never adds a per-frame file write.
    private static func accuracyImage(from frame: VialFrame) -> CGImage? {
        switch frame.source {
        case .pixelBuffer(let pb): return VialFrame.scaledGray(from: pb, scale: 0.5)
        case .cgImage(let image): return image
        }
    }

    /// Non-recording scans do not need a trace, but they still share this state
    /// handling path so the debug instrumentation cannot change scan behavior.
    private func handle(_ state: ScanState) {
        switch state {
        case .scanning(let seen, let ocr, let views, let progress, let hint, let textBoxes):
            debugFramesSeen = seen
            debugOCRFrames = ocr
            updateTextAutoFocus(with: textBoxes)
            publish {
                guard self.phase == .scanning else { return }
                self.framesSeen = seen; self.ocrFrames = ocr; self.views = views
                self.progress = progress; self.hint = hint; self.activeTextBoxes = textBoxes
            }
        case .finished(let r):
            deliver(r)
        }
    }
}
