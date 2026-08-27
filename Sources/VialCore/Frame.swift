import Foundation
import CoreVideo
import CoreImage
import CoreGraphics
import Vision
import Accelerate

/// A single camera or video frame.
///
/// The important property is that the **live camera path performs no image
/// conversion at all**. Vision accepts a `CVPixelBuffer` directly and reads the
/// luma plane itself, so the "convert to grayscale" step that makes this fast is
/// something we get by *not* doing work, rather than by doing cheaper work.
/// The CGImage path exists for the offline validation harness, where a
/// per-frame CoreImage conversion is acceptable.
public final class VialFrame {
    public enum Source {
        case pixelBuffer(CVPixelBuffer)
        case cgImage(CGImage)
    }

    public let source: Source
    public let timestamp: Double
    private let ciContext: CIContext
    private var _halfResGray: CGImage??
    private var _sharpness: Double?

    public init(pixelBuffer: CVPixelBuffer, timestamp: Double, context: CIContext) {
        self.source = .pixelBuffer(pixelBuffer)
        self.timestamp = timestamp
        self.ciContext = context
    }

    public init(cgImage: CGImage, timestamp: Double, context: CIContext) {
        self.source = .cgImage(cgImage)
        self.timestamp = timestamp
        self.ciContext = context
    }

    /// Handler for the barcode tier. Full resolution, no conversion: decode rate
    /// on the sample videos collapsed 4/29 -> 2/29 -> 0/29 across full, half and
    /// quarter scale, so this tier cannot afford downscaling.
    public func fullResHandler() -> VNImageRequestHandler {
        switch source {
        case .pixelBuffer(let pb): return VNImageRequestHandler(cvPixelBuffer: pb, options: [:])
        case .cgImage(let cg):     return VNImageRequestHandler(cgImage: cg, options: [:])
        }
    }

    /// Handler for the OCR tier at half resolution. Quarter resolution is not an
    /// option — it caused total recognition failure on two of five sample vials.
    public func halfResHandler() -> VNImageRequestHandler? {
        if let cg = halfResGray { return VNImageRequestHandler(cgImage: cg, options: [:]) }
        return nil
    }

    private var halfResGray: CGImage? {
        if let c = _halfResGray { return c }
        let v: CGImage?
        switch source {
        case .pixelBuffer(let pb):
            v = Self.scaledGray(from: pb, scale: 0.5)
        case .cgImage(let cg):
            let ci = CIImage(cgImage: cg)
                .applyingFilter("CIPhotoEffectMono")
                .transformed(by: CGAffineTransform(scaleX: 0.5, y: 0.5))
            v = ciContext.createCGImage(ci, from: ci.extent)
        }
        _halfResGray = .some(v)
        return v
    }

    /// Downscale the luma plane with vImage. Never touches chroma and never
    /// allocates a CoreImage render, which is what keeps the OCR tier affordable.
    static func scaledGray(from pb: CVPixelBuffer, scale: Double) -> CGImage? {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }

        let planar = CVPixelBufferGetPlaneCount(pb) > 0
        guard let base = planar ? CVPixelBufferGetBaseAddressOfPlane(pb, 0)
                                : CVPixelBufferGetBaseAddress(pb) else { return nil }
        let srcW = planar ? CVPixelBufferGetWidthOfPlane(pb, 0) : CVPixelBufferGetWidth(pb)
        let srcH = planar ? CVPixelBufferGetHeightOfPlane(pb, 0) : CVPixelBufferGetHeight(pb)
        let srcRB = planar ? CVPixelBufferGetBytesPerRowOfPlane(pb, 0) : CVPixelBufferGetBytesPerRow(pb)

        var src = vImage_Buffer(data: base, height: vImagePixelCount(srcH),
                                width: vImagePixelCount(srcW), rowBytes: srcRB)
        let dstW = max(1, Int(Double(srcW) * scale))
        let dstH = max(1, Int(Double(srcH) * scale))
        var dst = vImage_Buffer()
        guard vImageBuffer_Init(&dst, vImagePixelCount(dstH), vImagePixelCount(dstW),
                                8, vImage_Flags(kvImageNoFlags)) == kvImageNoError else { return nil }
        defer { free(dst.data) }
        guard vImageScale_Planar8(&src, &dst, nil, vImage_Flags(kvImageHighQualityResampling)) == kvImageNoError
        else { return nil }

        let bytes = Data(bytes: dst.data, count: dst.rowBytes * Int(dst.height))
        guard let provider = CGDataProvider(data: bytes as CFData),
              let space = CGColorSpace(name: CGColorSpace.linearGray) else { return nil }
        return CGImage(width: dstW, height: dstH, bitsPerComponent: 8, bitsPerPixel: 8,
                       bytesPerRow: dst.rowBytes, space: space, bitmapInfo: [],
                       provider: provider, decode: nil, shouldInterpolate: false,
                       intent: .defaultIntent)
    }

    /// Variance of the Laplacian on a thumbnail. Deliberately cheap — this gate
    /// only earns its place if it costs far less than the OCR pass it protects.
    public var sharpness: Double {
        if let s = _sharpness { return s }
        let s = computeSharpness()
        _sharpness = s
        return s
    }

    private func computeSharpness() -> Double {
        guard let (buf, w, h) = thumbnailGray(width: 160), w > 4, h > 4 else { return 0 }
        var lap = [Double](); lap.reserveCapacity((w - 2) * (h - 2))
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                let i = y * w + x
                lap.append(4.0 * Double(buf[i])
                           - Double(buf[i - 1]) - Double(buf[i + 1])
                           - Double(buf[i - w]) - Double(buf[i + w]))
            }
        }
        guard !lap.isEmpty else { return 0 }
        var mean = 0.0
        vDSP_meanvD(lap, 1, &mean, vDSP_Length(lap.count))
        var acc = 0.0
        for v in lap { let d = v - mean; acc += d * d }
        return acc / Double(lap.count)
    }

    private func thumbnailGray(width target: Int) -> ([UInt8], Int, Int)? {
        switch source {
        case .pixelBuffer(let pb):
            let srcW = CVPixelBufferGetPlaneCount(pb) > 0
                ? CVPixelBufferGetWidthOfPlane(pb, 0) : CVPixelBufferGetWidth(pb)
            guard srcW > 0,
                  let cg = Self.scaledGray(from: pb, scale: Double(target) / Double(srcW))
            else { return nil }
            return readGray(cg)
        case .cgImage(let cg):
            let scale = CGFloat(target) / CGFloat(cg.width)
            let ci = CIImage(cgImage: cg)
                .applyingFilter("CIPhotoEffectMono")
                .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            guard let small = ciContext.createCGImage(ci, from: ci.extent) else { return nil }
            return readGray(small)
        }
    }

    private func readGray(_ cg: CGImage) -> ([UInt8], Int, Int)? {
        let w = cg.width, h = cg.height
        var buf = [UInt8](repeating: 0, count: w * h)
        guard let space = CGColorSpace(name: CGColorSpace.linearGray) else { return nil }
        let ok = buf.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h,
                                      bitsPerComponent: 8, bytesPerRow: w,
                                      space: space, bitmapInfo: 0) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? (buf, w, h) : nil
    }
}
