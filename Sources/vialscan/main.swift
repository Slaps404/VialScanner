import Foundation
import AVFoundation
import CoreImage
import CoreGraphics
import VialCore

// Validation harness: replays .MOV files through the same ScanEngine the iOS app
// uses, so accuracy numbers here transfer directly to the device.

let args = Array(CommandLine.arguments.dropFirst())
guard args.count >= 1 else {
    FileHandle.standardError.write("usage: vialscan <dir-or-video...> [--defs P] [--inv P] [--bench]\n".data(using: .utf8)!)
    exit(2)
}

var paths: [String] = []
var defsPath = "1. Antibody Definitions-Grid view.csv"
var invPath = "2. Inventory Items-Grid view.csv"
var bench = false
var dump = false
var fps = 6.0
var roiInset = 0.15
var i = 0
while i < args.count {
    switch args[i] {
    case "--defs": i += 1; defsPath = args[i]
    case "--inv":  i += 1; invPath = args[i]
    case "--bench": bench = true
    case "--dump": dump = true
    case "--fps":  i += 1; fps = Double(args[i]) ?? 6.0
    case "--roi":  i += 1; roiInset = Double(args[i]) ?? 0.15
    default: paths.append(args[i])
    }
    i += 1
}

var videos: [String] = []
for p in paths {
    var isDir: ObjCBool = false
    if FileManager.default.fileExists(atPath: p, isDirectory: &isDir), isDir.boolValue {
        let items = (try? FileManager.default.contentsOfDirectory(atPath: p)) ?? []
        videos += items.filter { $0.lowercased().hasSuffix(".mov") || $0.lowercased().hasSuffix(".mp4") }
            .sorted().map { (p as NSString).appendingPathComponent($0) }
    } else { videos.append(p) }
}

let catalog: Catalog
do {
    catalog = try Catalog.load(definitionsCSV: URL(fileURLWithPath: defsPath),
                               inventoryCSV: URL(fileURLWithPath: invPath))
} catch {
    FileHandle.standardError.write("catalog load failed: \(error)\n".data(using: .utf8)!)
    exit(1)
}
print("catalog: \(catalog.definitions.count) definitions, \(catalog.items.count) inventory items\n")

/// Pull frames at a fixed rate, mimicking a 30 fps camera feed downsampled.
func frames(of path: String, fps: Double) -> [(CGImage, Double)] {
    let asset = AVURLAsset(url: URL(fileURLWithPath: path))
    let dur = CMTimeGetSeconds(asset.duration)
    guard dur > 0 else { return [] }
    let gen = AVAssetImageGenerator(asset: asset)
    gen.appliesPreferredTrackTransform = true
    // Exact-frame seeking costs ~28 ms/frame and buys nothing: we only need
    // frames spread across the rotation, not frames at precise timestamps.
    let tol = CMTime(seconds: 0.5 / fps, preferredTimescale: 600)
    gen.requestedTimeToleranceBefore = tol
    gen.requestedTimeToleranceAfter = tol
    var out: [(CGImage, Double)] = []
    var t = 0.0
    while t < dur {
        if let img = try? gen.copyCGImage(at: CMTime(seconds: t, preferredTimescale: 600), actualTime: nil) {
            out.append((img, t))
        }
        t += 1.0 / fps
    }
    return out
}

var hits = 0, misses = 0, newVials = 0
var totalEngineTime = 0.0, totalFrames = 0

for v in videos {
    let name = (v as NSString).lastPathComponent
    var cfg = EngineConfig()
    if roiInset > 0 {
        cfg.regionOfInterest = CGRect(x: roiInset, y: roiInset + 0.05,
                                      width: 1 - 2 * roiInset, height: 1 - 2 * (roiInset + 0.05))
    } else { cfg.regionOfInterest = nil }
    let engine = ScanEngine(catalog: catalog, config: cfg)
    let fs = frames(of: v, fps: fps)
    var state: ScanState = .scanning(framesSeen: 0, ocrFrames: 0, views: 0, progress: 0, hint: "")
    var stoppedAt = fs.count
    let start = CFAbsoluteTimeGetCurrent()
    for (idx, f) in fs.enumerated() {
        let frame = engine.makeFrame(cgImage: f.0, timestamp: f.1)
        state = engine.ingest(frame)
        if case .finished = state { stoppedAt = idx + 1; break }
    }
    let result: ScanResult
    if case .finished(let r) = state { result = r } else { result = engine.finish() }
    let elapsed = CFAbsoluteTimeGetCurrent() - start
    totalEngineTime += elapsed
    totalFrames += stoppedAt

    let badge: String
    switch result.verdict {
    case .certain:   badge = "CERTAIN  "; hits += 1
    case .confident: badge = "CONFIDENT"; hits += 1
    case .ambiguous: badge = "AMBIGUOUS"; misses += 1
    case .notFound:  badge = "NEW VIAL "; newVials += 1
    }

    let streamSeconds = Double(stoppedAt) / fps
    print("\(name)  [\(badge)]  frames \(stoppedAt)/\(fs.count)  ocr \(result.framesUsed)  "
          + "stream \(String(format: "%.2fs", streamSeconds))  \(String(format: "%.2fs", elapsed))")
    if let d = result.definition {
        print("    definition : \(d.name)   (\(d.vendor) \(d.catalog), clone \(d.clone))")
    }
    if let vial = result.vial {
        print("    vial       : AbNo \(vial.abNo)  \(vial.vialName)  lot \(vial.lot)  loc \(vial.location) box \(vial.box)")
        if !result.otherVials.isEmpty {
            print("    also       : \(result.otherVials.map { "AbNo \($0.abNo)" }.joined(separator: ", "))")
        }
    } else if result.definition != nil {
        if result.lotNotOnRecord {
            print("    vial       : LOT NOT ON RECORD — new lot of a known antibody")
            print("    on record  : \(result.otherVials.map { "AbNo \($0.abNo) lot \($0.lot)" }.joined(separator: ", "))")
        } else {
            print("    vial       : none on record — receive as new inventory item")
        }
    }
    let fieldStr = LabelField.allCases.compactMap { f in result.fields[f].map { "\(f)=\($0)" } }
    print("    read       : \(fieldStr.joined(separator: "  "))")
    print("    score      : \(String(format: "%.1f", result.score)) margin \(String(format: "%.1f", result.margin))  \(result.reasons.joined(separator: ", "))")
    if dump {
        let ev = engine.accumulated
        print("    views      : \(ev.distinctViews)  ocr frames \(ev.frameCount)")
        for f in LabelField.allCases {
            guard let votes = ev.fieldVotes[f], !votes.isEmpty else { continue }
            let sorted = votes.sorted { $0.value > $1.value }
                .map { "\($0.key)×\(Int($0.value))" }.joined(separator: " ")
            print("    votes.\(f)  : \(sorted)")
        }
        print("    --- raw OCR lines ---")
        for line in ev.allText { print("    | \(line)") }
    }
    print("")
}

print("─────")
print("hits \(hits)   ambiguous \(misses)   new-vial \(newVials)   of \(videos.count)")
if totalFrames > 0 {
    print(String(format: "engine: %.1f ms per ingested frame (avg over %d frames)",
                 totalEngineTime / Double(totalFrames) * 1000, totalFrames))
}
