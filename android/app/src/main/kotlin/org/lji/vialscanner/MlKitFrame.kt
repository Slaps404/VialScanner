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
) : VialFrame {

    override val sharpness: Double by lazy { laplacianVariance(bitmap) }

    override fun detectBarcodes(roi: NormRect?): List<BarcodePayload> {
        // Full resolution: decode rate collapses when a DataMatrix is downscaled.
        val (crop, _) = cropped(roi, scale = 1.0)
        val result = runCatching {
            Tasks.await(scanner.process(InputImage.fromBitmap(crop, 0)))
        }.getOrNull() ?: return emptyList()
        return result.mapNotNull { b ->
            val raw = b.rawValue ?: return@mapNotNull null
            if (raw.isEmpty()) null else BarcodePayload(raw, symbologyName(b.format))
        }
    }

    override fun recognizeText(roi: NormRect?): List<TextObservation> {
        val (crop, rect) = cropped(roi, scale = 0.5)
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
        return out
    }

    // ---- helpers ----------------------------------------------------------

    private class Frac(val x: Double, val y: Double, val w: Double, val h: Double)

    /**
     * Crop to the region of interest and optionally halve it. Returns the crop
     * together with where it sits in the full frame, in top-left fractions, so
     * boxes can be mapped back.
     */
    private fun cropped(roi: NormRect?, scale: Double): Pair<Bitmap, Frac> {
        val frac = if (roi == null) Frac(0.0, 0.0, 1.0, 1.0)
        else Frac(roi.minX, 1.0 - roi.maxY, roi.width, roi.height)

        val left = (frac.x * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (frac.y * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val w = (frac.w * bitmap.width).roundToInt().coerceIn(1, bitmap.width - left)
        val h = (frac.h * bitmap.height).roundToInt().coerceIn(1, bitmap.height - top)

        var crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        if (scale < 1.0) {
            val sw = max(1, (w * scale).roundToInt())
            val sh = max(1, (h * scale).roundToInt())
            crop = Bitmap.createScaledBitmap(crop, sw, sh, true)
        }
        return crop to Frac(frac.x, frac.y, frac.w, frac.h)
    }

    companion object {
        /**
         * Variance of a 3x3 Laplacian over a downsampled gray copy. Only ever
         * compared against other frames of the same stream, so the absolute
         * scale does not matter - what matters is that a motion-blurred frame
         * scores far below a crisp one, and that measuring it is cheap enough to
         * run on every frame that passes the pacing gate.
         */
        fun laplacianVariance(src: Bitmap, target: Int = 240): Double {
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
