package org.lji.vialscanner

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import org.lji.vialcore.BarcodePayload
import org.lji.vialcore.NormRect
import org.lji.vialcore.TextObservation
import org.lji.vialcore.VialFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One upright camera frame, wired to ML Kit.
 *
 * This is the whole Android-specific surface of the engine: everything the
 * pipeline does with a frame goes through the three members below, so `vialcore`
 * never imports the Android SDK and stays replayable on a laptop.
 *
 * Both ML Kit calls are blocking (`Tasks.await`) because `ScanEngine.ingest` is
 * synchronous and is meant to be driven from CameraX's analyzer thread, which is
 * a background thread whose backpressure strategy already drops frames while we
 * are busy. Making the engine async instead would buy nothing and would let
 * frames arrive out of order, which the evidence buffer's rotation tracking
 * reads as extra label faces.
 */
class MlKitFrame(
    private val bitmap: Bitmap,
    override val timestamp: Double,
    private val recognizer: TextRecognizer,
    private val scanner: BarcodeScanner,
    /** Long edge, in pixels, the text pass wants its crop to be. Absolute on
     *  purpose: a fixed fraction like "half the frame" compounds with whatever
     *  resolution CameraX happens to hand in, so the same code would feed OCR
     *  360px on one phone and 1000px on another. Exposed so the video replay
     *  harness can sweep it.
     *
     *  1152, not 1080, because that is where the downscale stops happening at
     *  all: a 1080x1920 frame cropped to the 0.70x0.60 ROI is 756x1152, and on
     *  the Pixel 5a the post-ViewPort analysis frame (916x1920) crops to
     *  641x1152. Swept over the 39-clip corpus, 1080 -> 1152 turned one clip
     *  from NOT_FOUND into a correct read and cost nothing measurable
     *  (140-154 ms per frame across 576/810/1080/1152, no trend). Going higher
     *  buys nothing: `cropped` never upscales. */
    private val ocrLongEdge: Int = 1152,
) : VialFrame {

    override val sharpness: Double by lazy { laplacianVariance(bitmap) }

    /**
     * Where the recogniser last found text, in the engine's coordinates.
     *
     * The engine has no use for this - it wants the strings - but the camera
     * does: steering focus at the label means knowing where on the frame the
     * label is, and this pass has already paid for that answer. Swift gets it
     * the same way, by keeping the boxes Vision hands back
     * (`CameraController.activeTextBoxes`).
     */
    var lastTextBoxes: List<NormRect> = emptyList()
        private set

    override fun detectBarcodes(roi: NormRect?): List<BarcodePayload> {
        // Full resolution: decode rate collapses when a DataMatrix is downscaled.
        val (crop, _) = cropped(roi, longEdge = 0)
        val result = runCatching {
            Tasks.await(scanner.process(InputImage.fromBitmap(crop, 0)))
        }.getOrNull() ?: return emptyList()
        return result.mapNotNull { b ->
            val raw = b.rawValue ?: return@mapNotNull null
            if (raw.isEmpty()) null else BarcodePayload(raw, symbologyName(b.format))
        }
    }

    override fun recognizeText(roi: NormRect?): List<TextObservation> {
        val (crop, rect) = cropped(roi, longEdge = ocrLongEdge)
        val result = runCatching {
            Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)))
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<TextObservation>()
        for (block in result.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            // Two conversions in one place, which is the point of doing it here:
            // ML Kit reports pixels with the origin top-left, and the engine
            // (following Vision) wants a fraction of the *full* frame with the
            // origin bottom-left. Getting the flip wrong does not crash, it
            // quietly inverts "below", which is what the dangling-keyword pass
            // in FieldExtraction uses to attach a value to its label.
            val x = rect.x + box.left.toDouble() / crop.width * rect.w
            val w = box.width().toDouble() / crop.width * rect.w
            val h = box.height().toDouble() / crop.height * rect.h
            val yTop = rect.y + box.top.toDouble() / crop.height * rect.h
            val minY = 1.0 - (yTop + h)
            // ML Kit has no per-line confidence; the engine's floor is 0.3 and
            // a returned line has already cleared the recogniser's own bar.
            out.add(TextObservation(line.text, 1.0f, NormRect(x, minY, w, h)))
        }
        lastTextBoxes = out.mapNotNull { it.boundingBox }
        return out
    }

    // ---- helpers ----------------------------------------------------------

    private class Frac(val x: Double, val y: Double, val w: Double, val h: Double)

    /**
     * Crop to the region of interest, then shrink it so its long edge is
     * `longEdge` pixels (0 or a larger crop than that means: leave it alone -
     * upscaling invents no detail). Returns the crop together with where it sits
     * in the full frame, in top-left fractions, so boxes can be mapped back.
     */
    private fun cropped(roi: NormRect?, longEdge: Int): Pair<Bitmap, Frac> {
        val frac = if (roi == null) Frac(0.0, 0.0, 1.0, 1.0)
        else Frac(roi.minX, 1.0 - roi.maxY, roi.width, roi.height)

        val left = (frac.x * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (frac.y * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val w = (frac.w * bitmap.width).roundToInt().coerceIn(1, bitmap.width - left)
        val h = (frac.h * bitmap.height).roundToInt().coerceIn(1, bitmap.height - top)

        var crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        val scale = if (longEdge <= 0) 1.0 else min(1.0, longEdge.toDouble() / max(w, h))
        if (scale < 1.0) {
            val sw = max(1, (w * scale).roundToInt())
            val sh = max(1, (h * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(crop, sw, sh, true)
            // Never recycle the frame itself: a full-frame crop returns the
            // same instance, and `sharpness` may still be waiting to read it.
            if (scaled !== crop && crop !== bitmap) crop.recycle()
            crop = scaled
        }
        return crop to Frac(frac.x, frac.y, frac.w, frac.h)
    }

    companion object {
        /**
         * Variance of a 3x3 Laplacian over a downsampled gray copy.
         *
         * `target` is not a free performance knob: it sets how hard the
         * downscale averages, which sets how far apart a crisp frame and a
         * blurred one score, and `EngineConfig.sharpnessRatio` is a fixed
         * fraction of the best score seen. Bigger thumbnails widen that spread,
         * so the same 0.45 throws away frames it was never meant to. 160 is
         * what Swift's `Frame.computeSharpness` uses and what the ratio was
         * tuned against; a 240px copy starved whole clips of OCR frames.
         */
        fun laplacianVariance(src: Bitmap, target: Int = 160): Double {
            val s = min(1.0, target.toDouble() / max(src.width, src.height))
            val w = max(3, (src.width * s).roundToInt())
            val h = max(3, (src.height * s).roundToInt())
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val gray = DoubleArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                gray[i] = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            }
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                sum += lap; sumSq += lap * lap; n += 1
            }
            if (n == 0) return 0.0
            val mean = sum / n
            return sumSq / n - mean * mean
        }

        /** ML Kit format ints, named the way the reason lines print them. */
        fun symbologyName(format: Int): String = when (format) {
            com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX -> "DataMatrix"
            com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE -> "QR"
            com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128 -> "Code128"
            com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_39 -> "Code39"
            com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13 -> "EAN13"
            com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417 -> "PDF417"
            com.google.mlkit.vision.barcode.common.Barcode.FORMAT_AZTEC -> "Aztec"
            else -> "code$format"
        }
    }
}
