import AVFoundation
import Combine
import CoreMedia
import CoreVideo
import Foundation
import UIKit
import VialCore

/// Owns the capture session and feeds frames to the engine.
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
    private var configured = false
    private var timer: Timer?

    init(catalog: Catalog, config: EngineConfig = EngineConfig()) {
        self.catalog = catalog
        self.config = config
        self.engine = ScanEngine(catalog: catalog, config: config)
        super.init()
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
            self.lock.lock(); self.engine = fresh; self.lock.unlock()
        }
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

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            publish { self.setupError = "No usable back camera on this device." }
            return
        }
        session.addInput(input)
        self.device = device

        // Close focus matters more than anything else here — the label is ~10 cm away.
        try? device.lockForConfiguration()
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isAutoFocusRangeRestrictionSupported { device.autoFocusRangeRestriction = .near }
        if device.isSmoothAutoFocusSupported { device.isSmoothAutoFocusEnabled = false }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
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
        publish { self.hasTorch = torchAvailable }
    }

    func stopSession() {
        endScan(discardClip: true)
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
        }
        setTorch(false)
    }

    // MARK: - Scanning

    func startScan(recordClip: Bool) {
        guard permission == .authorized else { requestAccessAndConfigure(); return }
        configureIfNeeded()

        ClipRecorder.discard(pendingClip)
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

        lock.lock()
        engine.reset()
        scanning = true
        busy = false
        self.recordClip = recordClip
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
            self.hint = "Hold the vial in the frame"
        }
    }

    /// Called once the operator has decided what happens to the clip.
    func consumePendingClip() -> URL? {
        let url = pendingClip
        pendingClip = nil
        return url
    }

    func discardPendingClip() {
        ClipRecorder.discard(pendingClip)
        pendingClip = nil
    }

    private func endScan(discardClip: Bool) {
        lock.lock()
        scanning = false
        let wasRecording = recordClip
        recordClip = false
        lock.unlock()
        stopTicking()
        publish { self.isRecording = false }
        if wasRecording, discardClip {
            queue.async { [recorder] in recorder.cancel() }
        }
    }

    private func deliver(_ r: ScanResult) {
        lock.lock()
        guard scanning else { lock.unlock(); return }
        scanning = false
        let wasRecording = recordClip
        recordClip = false
        lock.unlock()

        stopTicking()

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
            self.hint = r.verdict.headline
        }
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

    /// Tap-to-focus from the viewfinder, in normalised device coordinates.
    func focus(at point: CGPoint) {
        queue.async { [weak self] in
            guard let device = self?.device else { return }
            try? device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = point
                if device.isFocusModeSupported(.autoFocus) { device.focusMode = .autoFocus }
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
        if isScanning, !alreadyBusy { busy = true }
        let engine = self.engine
        lock.unlock()

        guard isScanning else { return }

        if wantsClip {
            if !recorder.isRecording { recorder.begin(with: sampleBuffer) }
            recorder.append(sampleBuffer)
        }

        // Drop frames while a pass is in flight rather than queueing them; stale
        // frames are worth nothing and the buffer pool is finite.
        guard !alreadyBusy, let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))

        visionQueue.async { [weak self] in
            guard let self else { return }
            let frame = engine.makeFrame(pixelBuffer: pb, timestamp: ts)
            let state = engine.ingest(frame)

            self.lock.lock()
            self.busy = false
            self.lock.unlock()

            switch state {
            case .scanning(let seen, let ocr, let views, let progress, let hint):
                self.publish {
                    guard self.phase == .scanning else { return }
                    self.framesSeen = seen
                    self.ocrFrames = ocr
                    self.views = views
                    self.progress = progress
                    self.hint = hint
                }
            case .finished(let r):
                self.deliver(r)
            }
        }
    }
}
