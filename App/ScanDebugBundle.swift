import AVFoundation
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import ImageIO
import UIKit
import VialCore

#if canImport(CryptoKit)
import CryptoKit
#endif

extension AppSettings {
    /// Accuracy capture is a device-local debugging preference. Keeping it out
    /// of the inventory backup prevents a temporary image-capture choice from
    /// following the catalog to another phone.
    private static let debugAccuracyCaptureKey = "vial.debugAccuracyCapture"

    var debugAccuracyCapture: Bool {
        get { UserDefaults.standard.bool(forKey: Self.debugAccuracyCaptureKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.debugAccuracyCaptureKey) }
    }
}

// MARK: - Wire types

/// A rectangle with named scalar fields is easier to inspect and keeps the
/// sidecar shape identical on Swift and Kotlin.
struct DebugRect: Codable, Equatable {
    let x: Double
    let y: Double
    let width: Double
    let height: Double

    init(_ rect: CGRect) {
        x = rect.origin.x
        y = rect.origin.y
        width = rect.size.width
        height = rect.size.height
    }
}

struct DebugPoint: Codable, Equatable {
    let x: Double
    let y: Double

    init(_ point: CGPoint) {
        x = point.x
        y = point.y
    }
}

struct DebugTextObservation: Codable, Equatable {
    let text: String
    let confidence: Double
    let boundingBox: DebugRect?

    init(_ observation: TextObservation) {
        text = observation.text
        confidence = Double(observation.confidence)
        boundingBox = observation.boundingBox.map(DebugRect.init)
    }
}

struct DebugBarcode: Codable, Equatable {
    let raw: String
    let symbology: String
    let catalog: String?
    let lot: String?

    init(_ barcode: BarcodePayload) {
        raw = barcode.raw
        symbology = barcode.symbology
        catalog = barcode.catalog
        lot = barcode.lot
    }
}

struct DebugEngineState: Codable, Equatable {
    let kind: String
    let framesSeen: Int
    let ocrFrames: Int
    let views: Int
    let progress: Double
    let hint: String?
    let textBoxes: [DebugRect]
}

struct DebugTraceFrame: Codable, Equatable {
    let sequence: Int
    /// The camera media timestamp in seconds. This is the clock shared with the
    /// clip, not a wall-clock timestamp.
    let captureTimestamp: Double
    /// Monotonic timings measured by the app. They remain meaningful when a
    /// phone's wall clock changes or a clip is copied to another machine.
    let elapsedMilliseconds: Double
    let queueWaitMilliseconds: Double?
    let ingestMilliseconds: Double?
    let width: Int
    let height: Int
    let pixelFormat: String?
    let barcodeAttempted: Bool
    let ocrAttempted: Bool
    let observations: [DebugTextObservation]
    let barcodes: [DebugBarcode]
    let status: String
    let skipReason: String?
    let engineFrame: Int?
    let state: DebugEngineState?
    /// Present only when accuracy capture retained the recognizer input for
    /// this frame. The image is written after the scan, never from this path.
    let accuracyImageSequence: Int?
}

struct DebugEngineConfig: Codable, Equatable {
    let regionOfInterest: DebugRect?
    let barcodeEveryNthFrame: Int
    let maxOCRFrames: Int
    let minOCRInterval: Double
    let emptyOCRFramesBeforeBackoff: Int
    let ocrBackoffFactor: Double
    let maxOCRInterval: Double
    let backoffOnlyBeforeFirstText: Bool
    let sharpnessRatio: Double
    let sharpnessWindow: Int
    let earlyExitOnBarcode: Bool
    let earlyExitMinFrames: Int
    let minScanDuration: Double
    let barcodeConfirmations: Int
    let minDistinctViews: Int
    let saturationFrames: Int
    let settleConfirmations: Int
    let maxScanDuration: Double
    let graceExtension: Double
    let hardScanCeiling: Double
    let ocrFramesPerExtension: Int

    init(_ config: EngineConfig) {
        regionOfInterest = config.regionOfInterest.map(DebugRect.init)
        barcodeEveryNthFrame = config.barcodeEveryNthFrame
        maxOCRFrames = config.maxOCRFrames
        minOCRInterval = config.minOCRInterval
        emptyOCRFramesBeforeBackoff = config.emptyOCRFramesBeforeBackoff
        ocrBackoffFactor = config.ocrBackoffFactor
        maxOCRInterval = config.maxOCRInterval
        backoffOnlyBeforeFirstText = config.backoffOnlyBeforeFirstText
        sharpnessRatio = config.sharpnessRatio
        sharpnessWindow = config.sharpnessWindow
        earlyExitOnBarcode = config.earlyExitOnBarcode
        earlyExitMinFrames = config.earlyExitMinFrames
        minScanDuration = config.minScanDuration
        barcodeConfirmations = config.barcodeConfirmations
        minDistinctViews = config.minDistinctViews
        saturationFrames = config.saturationFrames
        settleConfirmations = config.settleConfirmations
        maxScanDuration = config.maxScanDuration
        graceExtension = config.graceExtension
        hardScanCeiling = config.hardScanCeiling
        ocrFramesPerExtension = config.ocrFramesPerExtension
    }
}

struct DebugBuildMetadata: Codable, Equatable {
    let bundleIdentifier: String
    let appVersion: String
    let build: String
    let configuration: String
    let operatingSystem: String

    static func current() -> DebugBuildMetadata {
        let info = Bundle.main.infoDictionary ?? [:]
        #if DEBUG
        let configuration = "debug"
        #else
        let configuration = "release"
        #endif
        return DebugBuildMetadata(
            bundleIdentifier: Bundle.main.bundleIdentifier ?? "unknown",
            appVersion: info["CFBundleShortVersionString"] as? String ?? "unknown",
            build: info["CFBundleVersion"] as? String ?? "unknown",
            configuration: configuration,
            operatingSystem: ProcessInfo.processInfo.operatingSystemVersionString
        )
    }
}

struct DebugCatalogMetadata: Codable, Equatable {
    let definitionCount: Int
    let itemCount: Int
    /// SHA-256 of the ordered definition and inventory rows, including extra
    /// imported columns. It identifies the catalog that actually answered the
    /// scan, not merely the app version.
    let hash: String

    init(_ catalog: Catalog) {
        definitionCount = catalog.definitions.count
        itemCount = catalog.items.count
        hash = Self.hash(catalog)
    }

    private static func hash(_ catalog: Catalog) -> String {
        // Length-prefix every value so a delimiter in an imported field cannot
        // make two different catalogs share a canonical string.
        var values: [String] = []
        func add(_ value: String) { values.append("\(value.utf8.count):\(value)") }
        func add(_ bag: FieldBag) {
            add(String(bag.entries.count))
            for entry in bag.entries { add(entry.name); add(entry.value) }
        }

        add(String(catalog.definitions.count))
        for definition in catalog.definitions {
            add(definition.name); add(definition.target); add(definition.clone)
            add(definition.host); add(definition.isotype); add(definition.vendor)
            add(definition.catalog); add(String(definition.inventoryLinks.count))
            for link in definition.inventoryLinks { add(link) }
            add(definition.extra)
        }
        add(String(catalog.items.count))
        for item in catalog.items {
            add(item.abNo); add(item.definitionName); add(item.vialName); add(item.lot)
            add(item.concentration); add(item.location); add(item.box); add(item.owner)
            add(item.extra)
        }
        let data = Data(values.joined(separator: "\n").utf8)

        #if canImport(CryptoKit)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        #else
        // CryptoKit is present on supported iOS versions. This fallback keeps
        // raw source compilation possible on an SDK without the module.
        var h: UInt64 = 14_695_981_039_346_656_037
        for byte in data {
            h ^= UInt64(byte)
            h &*= 1_099_511_628_211
        }
        return String(format: "%016llx", h)
        #endif
    }
}

struct DebugDeviceMetadata: Codable, Equatable {
    let model: String
    let name: String
    let systemName: String
    let systemVersion: String
    let processArchitecture: String
    let lowPowerMode: Bool

    static func current() -> DebugDeviceMetadata {
        let d = UIDevice.current
        #if arch(arm64)
        let architecture = "arm64"
        #elseif arch(x86_64)
        let architecture = "x86_64"
        #else
        let architecture = "unknown"
        #endif
        return DebugDeviceMetadata(
            model: d.model,
            name: d.name,
            systemName: d.systemName,
            systemVersion: d.systemVersion,
            processArchitecture: architecture,
            lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled
        )
    }
}

struct DebugThermalMetadata: Codable, Equatable {
    let start: String
    let end: String
    let changedDuringScan: Bool
}

struct DebugOpticsMetadata: Codable, Equatable {
    let deviceType: String?
    let position: String?
    let sessionPreset: String
    let activeFormatWidth: Int?
    let activeFormatHeight: Int?
    let pixelFormat: String?
    let userZoom: Double
    let avZoomFactor: Double?
    let minZoom: Double?
    let maxZoom: Double?
    let focusMode: String?
    let autofocusRangeRestriction: String?
    let focusLocked: Bool
    let focusPoint: DebugPoint?
    let torchOn: Bool
    let hasTorch: Bool
}

struct DebugCaptureMetadata: Codable, Equatable {
    let mode: String
    let requestedClip: Bool
    let frameEvents: Int
    let framesIngested: Int
    let ocrPasses: Int
    let barcodePasses: Int
    let droppedFrames: Int
    let framesSeen: Int
    let ocrFramesUsed: Int
    let mediaDurationSeconds: Double
    let elapsedSeconds: Double
    let roiInset: Double
    let engine: DebugEngineConfig
    let optics: DebugOpticsMetadata
}

struct DebugResultSummary: Codable, Equatable {
    let verdict: String
    let evidenceLevel: String
    let definitionName: String?
    let abNo: String?
    let catalog: String?
    let lot: String?
    let vendor: String?
    let clone: String?
    let barcode: DebugBarcode?
    let score: Double
    let margin: Double
    let reasons: [String]
    let framesUsed: Int
    let lotNotOnRecord: Bool
    let fields: [String: String]
    let missingFields: [String]

    init(_ result: ScanResult) {
        verdict = result.verdict.rawValue
        evidenceLevel = result.evidenceLevel.rawValue
        definitionName = result.definition?.name
        abNo = result.vial?.abNo
        catalog = result.fields[.catalog] ?? result.barcode?.catalog ?? result.definition?.catalog
        lot = result.fields[.lot] ?? result.barcode?.lot
        vendor = result.fields[.vendor] ?? result.definition?.vendor
        clone = result.fields[.clone] ?? result.definition?.clone
        barcode = result.barcode.map(DebugBarcode.init)
        score = result.score
        margin = result.margin
        reasons = result.reasons
        framesUsed = result.framesUsed
        lotNotOnRecord = result.lotNotOnRecord
        fields = Dictionary(uniqueKeysWithValues: result.fields.map { ($0.key.rawValue, $0.value) })
        missingFields = LabelField.allCases.filter { result.fields[$0] == nil }.map(\.rawValue)
    }
}

struct DebugEvidenceSummary: Codable, Equatable {
    let frameCount: Int
    let allText: [String]
    let stableTokens: [String]
    let tokenFrames: [String: Int]
    let rawForms: [String: String]
    let fieldVotes: [String: [String: Double]]
    let barcodes: [DebugBarcode]
    let barcodeFrames: [String: Int]
    let distinctViews: Int
    let framesSinceNewToken: Int

    init(_ evidence: EvidenceBuffer) {
        frameCount = evidence.frameCount
        allText = evidence.allText
        stableTokens = Array(evidence.stableTokens(minFrames: 2)).sorted()
        tokenFrames = evidence.tokenFrames
        rawForms = evidence.rawForms
        fieldVotes = Dictionary(uniqueKeysWithValues: evidence.fieldVotes.map {
            ($0.key.rawValue, $0.value)
        })
        barcodes = evidence.barcodes.map(DebugBarcode.init)
        barcodeFrames = evidence.barcodeFrames
        distinctViews = evidence.distinctViews
        framesSinceNewToken = evidence.framesSinceNewToken
    }
}

struct DebugSummary: Codable, Equatable {
    let scanID: String
    let startedAt: String
    let endedAt: String
    let build: DebugBuildMetadata
    let catalog: DebugCatalogMetadata
    let device: DebugDeviceMetadata
    let thermal: DebugThermalMetadata
    let capture: DebugCaptureMetadata
    let result: DebugResultSummary
    let evidence: DebugEvidenceSummary
}

struct DebugAccuracyMetadata: Codable, Equatable {
    let enabled: Bool
    let policy: String
    let imageFiles: [String]
}

/// Versioned, self-contained metadata for one kept clip. The trace is buffered
/// during capture and is serialized only after the result and video exist.
struct ScanDebugBundle: Codable, Equatable {
    static let schema = "vial-scanner.scan-debug"
    static let currentVersion = 1

    let schema: String
    let schemaVersion: Int
    var clip: String
    var recordedAt: String
    var note: String
    var expect: ClipExpectationDTO?
    let summary: DebugSummary
    let trace: [DebugTraceFrame]
    var accuracy: DebugAccuracyMetadata
}

/// Images are deliberately kept out of the Codable bundle. They are retained
/// only until Store writes them after the scan, so normal performance capture
/// never encodes or writes an image per frame.
struct ScanDebugImage {
    let sequence: Int
    let image: CGImage
}

struct ScanDebugArtifact {
    var bundle: ScanDebugBundle
    let images: [ScanDebugImage]
}

// MARK: - Buffered capture

/// In-memory trace collector used by CameraController. Calls are made from the
/// capture queue, Vision queue, and recognizer callbacks, so every mutation is
/// protected by one short lock. There is no file or Log call on this path.
final class ScanDebugRecorder {
    enum Mode: String {
        case performance
        case accuracy
    }

    private struct Frame {
        let sequence: Int
        let captureTimestamp: Double
        let capturedUptime: UInt64
        let width: Int
        let height: Int
        let pixelFormat: String?
        var processingUptime: UInt64?
        var barcodeAttempted = false
        var ocrAttempted = false
        var observations: [DebugTextObservation] = []
        var barcodes: [DebugBarcode] = []
        var status = "captured"
        var skipReason: String?
        var engineFrame: Int?
        var state: DebugEngineState?
        var accuracyImageSequence: Int?
    }

    let mode: Mode
    let scanID: UUID
    let startedAt: Date
    let build: DebugBuildMetadata
    let catalog: DebugCatalogMetadata
    let config: DebugEngineConfig
    let device: DebugDeviceMetadata
    let thermalStart: String

    private let lock = NSLock()
    private let startedUptime: UInt64
    private var nextSequence = 0
    private var frames: [Frame] = []
    private var indices: [Int: Int] = [:]
    private var activeSequence: Int?
    private var images: [Int: CGImage] = [:]

    init(mode: Mode, scanID: UUID, startedAt: Date, catalog: Catalog, config: EngineConfig) {
        self.mode = mode
        self.scanID = scanID
        self.startedAt = startedAt
        self.build = DebugBuildMetadata.current()
        self.catalog = DebugCatalogMetadata(catalog)
        self.config = DebugEngineConfig(config)
        self.device = DebugDeviceMetadata.current()
        self.thermalStart = Self.thermalName(ProcessInfo.processInfo.thermalState)
        self.startedUptime = DispatchTime.now().uptimeNanoseconds
    }

    var wantsAccuracyImages: Bool { mode == .accuracy }

    /// Called once for every frame delivered by AVCaptureVideoDataOutput.
    @discardableResult
    func capture(timestamp: Double, width: Int, height: Int, pixelFormat: String?) -> Int {
        lock.lock(); defer { lock.unlock() }
        let sequence = nextSequence
        nextSequence += 1
        let frame = Frame(sequence: sequence, captureTimestamp: timestamp,
                          capturedUptime: DispatchTime.now().uptimeNanoseconds,
                          width: width, height: height, pixelFormat: pixelFormat)
        indices[sequence] = frames.count
        frames.append(frame)
        return sequence
    }

    func markDropped(_ sequence: Int, reason: String) {
        lock.lock(); defer { lock.unlock() }
        guard let i = indices[sequence] else { return }
        frames[i].status = "dropped"
        frames[i].skipReason = reason
    }

    func beginProcessing(_ sequence: Int, barcodeAttempted: Bool) {
        lock.lock(); defer { lock.unlock() }
        guard let i = indices[sequence] else { return }
        frames[i].processingUptime = DispatchTime.now().uptimeNanoseconds
        frames[i].barcodeAttempted = barcodeAttempted
        activeSequence = sequence
    }

    /// The engine fires this callback even for an empty OCR pass, which makes
    /// `ocrAttempted` distinguish an empty read from a pacing/sharpness skip.
    func recordOCR(_ observations: [TextObservation]) {
        lock.lock(); defer { lock.unlock() }
        guard let sequence = activeSequence, let i = indices[sequence] else { return }
        frames[i].ocrAttempted = true
        frames[i].observations = observations.map(DebugTextObservation.init)
    }

    func recordBarcode(_ barcode: BarcodePayload) {
        lock.lock(); defer { lock.unlock() }
        guard let sequence = activeSequence, let i = indices[sequence] else { return }
        frames[i].barcodes.append(DebugBarcode(barcode))
    }

    func wantsImage(for sequence: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard mode == .accuracy, let i = indices[sequence] else { return false }
        // OCR inputs are the most useful replay artifact. A barcode-sampled
        // frame is also retained, even when that pass found no code, so the
        // reviewer can tell a code was visible or absent at full rotation.
        return frames[i].ocrAttempted || frames[i].barcodeAttempted
    }

    func storeImage(_ image: CGImage, for sequence: Int) {
        lock.lock(); defer { lock.unlock() }
        guard mode == .accuracy, indices[sequence] != nil else { return }
        // A hard cap prevents a pathological camera stream from retaining an
        // unbounded number of CoreGraphics images before the user acts.
        guard images.count < 64 || images[sequence] != nil else { return }
        images[sequence] = image
        if let i = indices[sequence] { frames[i].accuracyImageSequence = sequence }
    }

    func finishProcessing(_ sequence: Int, engineFrame: Int?, state: DebugEngineState?, resultFinished: Bool,
                          ingestMilliseconds: Double) {
        lock.lock(); defer { lock.unlock() }
        guard let i = indices[sequence] else { return }
        let frame = frames[i]
        frames[i].engineFrame = engineFrame
        frames[i].state = state
        frames[i].status = status(for: frame, resultFinished: resultFinished)
        if frames[i].status == "engine-skipped" {
            frames[i].skipReason = "pacing_or_sharpness_or_budget"
        }
        // Keep the measured duration in a side table-free field by replacing a
        // tiny marker in the frame's processing uptime is not possible, so the
        // final trace stores this value through `ingestDurations`.
        ingestDurations[sequence] = ingestMilliseconds
        activeSequence = nil
    }

    func markCanceled(_ sequence: Int) {
        lock.lock(); defer { lock.unlock() }
        guard let i = indices[sequence] else { return }
        frames[i].status = "dropped"
        frames[i].skipReason = "scan_cancelled"
        activeSequence = nil
    }

    private var ingestDurations: [Int: Double] = [:]

    func finish(result: ScanResult, evidence: EvidenceBuffer, endedAt: Date,
                framesSeen: Int, ocrFramesUsed: Int, roiInset: Double,
                optics: DebugOpticsMetadata) -> ScanDebugArtifact {
        lock.lock()
        let localFrames = frames
        let localDurations = ingestDurations
        let localImages = images
        lock.unlock()

        let trace = localFrames.map { frame in
            DebugTraceFrame(
                sequence: frame.sequence,
                captureTimestamp: frame.captureTimestamp,
                elapsedMilliseconds: milliseconds(since: startedUptime, until: frame.capturedUptime),
                queueWaitMilliseconds: frame.processingUptime.map {
                    milliseconds(since: frame.capturedUptime, until: $0)
                },
                ingestMilliseconds: localDurations[frame.sequence],
                width: frame.width,
                height: frame.height,
                pixelFormat: frame.pixelFormat,
                barcodeAttempted: frame.barcodeAttempted,
                ocrAttempted: frame.ocrAttempted,
                observations: frame.observations,
                barcodes: frame.barcodes,
                status: frame.status,
                skipReason: frame.skipReason,
                engineFrame: frame.engineFrame,
                state: frame.state,
                accuracyImageSequence: frame.accuracyImageSequence
            )
        }

        let mediaTimestamps = localFrames.map(\.captureTimestamp).filter { $0.isFinite }
        let mediaDuration = max(0, (mediaTimestamps.max() ?? 0) - (mediaTimestamps.min() ?? 0))
        let nowUptime = DispatchTime.now().uptimeNanoseconds
        let elapsed = milliseconds(since: startedUptime, until: nowUptime) / 1000
        let dropped = trace.filter { $0.status == "dropped" }.count
        let ingested = trace.count - dropped
        let ocrPasses = trace.filter(\.ocrAttempted).count
        let barcodePasses = trace.filter(\.barcodeAttempted).count
        let thermalEnd = Self.thermalName(ProcessInfo.processInfo.thermalState)
        let capture = DebugCaptureMetadata(
            mode: mode.rawValue,
            requestedClip: true,
            frameEvents: trace.count,
            framesIngested: max(0, ingested),
            ocrPasses: ocrPasses,
            barcodePasses: barcodePasses,
            droppedFrames: dropped,
            framesSeen: framesSeen,
            ocrFramesUsed: ocrFramesUsed,
            mediaDurationSeconds: mediaDuration,
            elapsedSeconds: elapsed,
            roiInset: roiInset,
            engine: config,
            optics: optics
        )
        let summary = DebugSummary(
            scanID: scanID.uuidString,
            startedAt: Self.iso8601(startedAt),
            endedAt: Self.iso8601(endedAt),
            build: build,
            catalog: catalog,
            device: device,
            thermal: DebugThermalMetadata(
                start: thermalStart,
                end: thermalEnd,
                changedDuringScan: thermalStart != thermalEnd
            ),
            capture: capture,
            result: DebugResultSummary(result),
            evidence: DebugEvidenceSummary(evidence)
        )
        let bundle = ScanDebugBundle(
            schema: ScanDebugBundle.schema,
            schemaVersion: ScanDebugBundle.currentVersion,
            clip: "",
            recordedAt: Self.iso8601(endedAt),
            note: "",
            expect: nil,
            summary: summary,
            trace: trace,
            accuracy: DebugAccuracyMetadata(
                enabled: mode == .accuracy,
                policy: mode == .accuracy ? "half_res_gray_recognizer_inputs" : "none",
                imageFiles: []
            )
        )
        let retained = localImages.keys.sorted().compactMap { sequence in
            localImages[sequence].map { ScanDebugImage(sequence: sequence, image: $0) }
        }
        return ScanDebugArtifact(bundle: bundle, images: retained)
    }

    private func status(for frame: Frame, resultFinished: Bool) -> String {
        if frame.ocrAttempted {
            return frame.observations.isEmpty ? "ocr_empty" : "ocr_text"
        }
        if !frame.barcodes.isEmpty { return "barcode_hit" }
        if resultFinished { return "finished" }
        return "engine-skipped"
    }

    private func milliseconds(since start: UInt64, until end: UInt64) -> Double {
        guard end >= start else { return 0 }
        return Double(end - start) / 1_000_000
    }

    static func thermalName(_ state: ProcessInfo.ThermalState) -> String {
        switch state {
        case .nominal: return "nominal"
        case .fair: return "fair"
        case .serious: return "serious"
        case .critical: return "critical"
        @unknown default: return "unknown"
        }
    }

    static func iso8601(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.timeZone = TimeZone(secondsFromGMT: 0)
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: date)
    }
}

// MARK: - Camera metadata helpers

extension AVCaptureDevice {
    var debugFocusMode: String {
        switch focusMode {
        case .locked: return "locked"
        case .autoFocus: return "auto"
        case .continuousAutoFocus: return "continuous"
        @unknown default: return "unknown"
        }
    }

    var debugAutofocusRangeRestriction: String {
        switch autoFocusRangeRestriction {
        case .none: return "none"
        case .near: return "near"
        case .far: return "far"
        @unknown default: return "unknown"
        }
    }
}
