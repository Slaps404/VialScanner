import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation

/// Records the frames of a single scan to a file.
///
/// The clip is what makes "mark for review" honest. A parked scan with no video
/// is an accusation nobody can check a week later; with the video, whoever picks
/// it up can see the same label the phone saw — the glare, the thumb over the
/// lot number, the barcode that never faced the lens.
///
/// Frames are the *same* buffers the engine reads, appended on the capture
/// queue, so recording adds an encode but no extra capture path. Every clip
/// starts in the temporary directory and is only moved into Documents if the
/// operator keeps it; recording every scan and throwing most away costs less
/// than asking someone to reproduce a bad read.
final class ClipRecorder {

    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var started = false
    private(set) var outputURL: URL?
    private var failed = false

    var isRecording: Bool { writer != nil && !failed }

    /// Called on the capture queue with the first frame of a scan.
    func begin(with sampleBuffer: CMSampleBuffer) {
        guard writer == nil else { return }
        guard let fmt = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        let dims = CMVideoFormatDescriptionGetDimensions(fmt)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("scan-\(UUID().uuidString).mov")
        try? FileManager.default.removeItem(at: url)

        guard let w = try? AVAssetWriter(outputURL: url, fileType: .mov) else { failed = true; return }
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(dims.width),
            AVVideoHeightKey: Int(dims.height),
            AVVideoCompressionPropertiesKey: [
                // ~4 Mbps at 1080p: small enough that a day of scanning fits on
                // the phone, sharp enough to read a lot number back off it.
                AVVideoAverageBitRateKey: 4_000_000,
                AVVideoMaxKeyFrameIntervalKey: 30,
            ],
        ]
        let i = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        i.expectsMediaDataInRealTime = true
        // Capture buffers arrive landscape-right; the phone is held portrait.
        i.transform = CGAffineTransform(rotationAngle: .pi / 2)
        guard w.canAdd(i) else { failed = true; return }
        w.add(i)
        guard w.startWriting() else { failed = true; return }
        w.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))

        writer = w
        input = i
        outputURL = url
        started = true
    }

    /// Called on the capture queue for every frame while a scan is running.
    func append(_ sampleBuffer: CMSampleBuffer) {
        guard started, !failed, let input, let writer else { return }
        guard writer.status == .writing else {
            if writer.status == .failed { failed = true }
            return
        }
        // Dropping a frame the encoder is not ready for is correct: the clip is
        // evidence, not a measurement, and stalling the capture queue would cost
        // the engine an OCR pass.
        if input.isReadyForMoreMediaData {
            input.append(sampleBuffer)
        }
    }

    /// Finish and hand back the file, or nil if nothing usable was written.
    func finish(_ completion: @escaping (URL?) -> Void) {
        guard let writer, let input, started, !failed else {
            cleanup()
            completion(nil)
            return
        }
        input.markAsFinished()
        let url = outputURL
        writer.finishWriting { [weak self] in
            let ok = writer.status == .completed
            self?.reset()
            completion(ok ? url : nil)
        }
    }

    /// Abandon the current recording and delete the file.
    func cancel() {
        if let writer, writer.status == .writing { writer.cancelWriting() }
        cleanup()
    }

    private func cleanup() {
        if let outputURL { try? FileManager.default.removeItem(at: outputURL) }
        reset()
    }

    private func reset() {
        writer = nil
        input = nil
        started = false
        failed = false
        outputURL = nil
    }

    static func discard(_ url: URL?) {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
