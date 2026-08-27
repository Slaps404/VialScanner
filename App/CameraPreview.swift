import AVFoundation
import SwiftUI
import UIKit

/// The viewfinder. Live from launch and never hidden behind a result — the next
/// vial gets lined up while the last card is still on screen.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    var onFocusTap: ((CGPoint) -> Void)?

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.videoPreviewLayer.session = session
        v.videoPreviewLayer.videoGravity = .resizeAspectFill
        v.onTap = onFocusTap
        return v
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        view.onTap = onFocusTap
        if view.videoPreviewLayer.session !== session { view.videoPreviewLayer.session = session }
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var onTap: ((CGPoint) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .black
            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
            addGestureRecognizer(tap)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is unused") }

        @objc private func handleTap(_ g: UITapGestureRecognizer) {
            let p = g.location(in: self)
            // Layer point -> device point: the preview is aspect-fill cropped, so
            // the conversion is not a plain division by bounds.
            let devicePoint = videoPreviewLayer.captureDevicePointConverted(fromLayerPoint: p)
            onTap?(devicePoint)
        }
    }
}

/// The aiming rectangle. It exists to tell the operator where the engine is
/// actually looking: recognition is restricted to this region, and text outside
/// it is never read, so a reticle that lied would cost real scans.
struct Reticle: View {
    var inset: Double
    var active: Bool
    var tint: Color = .white

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width * (1 - inset * 2)
            let h = geo.size.height * (1 - inset * 2.6)
            ZStack {
                // Dim everything the engine ignores.
                Color.black.opacity(0.34)
                    .mask {
                        Rectangle()
                            .overlay {
                                RoundedRectangle(cornerRadius: 26, style: .continuous)
                                    .frame(width: w, height: h)
                                    .blendMode(.destinationOut)
                            }
                            .compositingGroup()
                    }
                    .ignoresSafeArea()

                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(tint.opacity(active ? 0.95 : 0.5), lineWidth: active ? 2.5 : 1.5)
                    .frame(width: w, height: h)
                    .shadow(color: .black.opacity(0.25), radius: 8)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .allowsHitTesting(false)
    }
}
