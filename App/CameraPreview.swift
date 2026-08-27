import AVFoundation
import SwiftUI
import UIKit

/// The viewfinder. Live from launch and never hidden behind a result — the next
/// vial gets lined up while the last card is still on screen.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    var onFocusTap: ((CGPoint, CGPoint) -> Void)?
    var onPinch: ((CGFloat) -> Void)?

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.videoPreviewLayer.session = session
        v.videoPreviewLayer.videoGravity = .resizeAspectFill
        v.onTap = onFocusTap
        v.onPinch = onPinch
        return v
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        view.onTap = onFocusTap
        view.onPinch = onPinch
        if view.videoPreviewLayer.session !== session { view.videoPreviewLayer.session = session }
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var onTap: ((CGPoint, CGPoint) -> Void)?
        var onPinch: ((CGFloat) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .black
            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
            addGestureRecognizer(tap)

            let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
            addGestureRecognizer(pinch)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is unused") }

        @objc private func handleTap(_ g: UITapGestureRecognizer) {
            let p = g.location(in: self)
            // Layer point -> device point: the preview is aspect-fill cropped, so
            // the conversion is not a plain division by bounds.
            let devicePoint = videoPreviewLayer.captureDevicePointConverted(fromLayerPoint: p)
            onTap?(devicePoint, p)
        }

        @objc private func handlePinch(_ g: UIPinchGestureRecognizer) {
            if g.state == .changed {
                onPinch?(g.scale)
                g.scale = 1.0
            }
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

/// Real-time live HUD overlay rendering glowing tracking boxes on text lines detected by OCR.
struct TextLockOverlay: View {
    let boxes: [CGRect]
    var isLocked: Bool = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                ForEach(boxes.indices, id: \.self) { idx in
                    let b = boxes[idx]
                    // Convert Vision coordinate (bottom-left 0..1) to View coordinate (top-left)
                    let x = b.minX * geo.size.width
                    let y = (1.0 - b.maxY) * geo.size.height
                    let width = b.width * geo.size.width
                    let height = b.height * geo.size.height

                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .stroke(isLocked ? Color.green.opacity(0.85) : Color.yellow.opacity(0.75), lineWidth: 1.5)
                        .background(
                            RoundedRectangle(cornerRadius: 4, style: .continuous)
                                .fill((isLocked ? Color.green : Color.yellow).opacity(0.12))
                        )
                        .frame(width: max(8, width), height: max(6, height))
                        .position(x: x + width / 2, y: y + height / 2)
                        .animation(.easeOut(duration: 0.15), value: b)
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Animated visual ring displayed at the point of focus/tap.
struct FocusTargetReticle: View {
    let point: CGPoint?
    let isLocked: Bool

    var body: some View {
        if let point {
            ZStack {
                Circle()
                    .stroke(isLocked ? Color.green : Color.white, lineWidth: 2)
                    .frame(width: 64, height: 64)

                if isLocked {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.green)
                        .offset(y: -42)
                }
            }
            .position(point)
            .transition(.scale.combined(with: .opacity))
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: point)
        }
    }
}
