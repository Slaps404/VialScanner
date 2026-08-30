package org.lji.vialscanner

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Test
import org.lji.vialcore.Catalog
import org.lji.vialcore.EngineConfig
import org.lji.vialcore.extractFieldsFromLines
import org.lji.vialcore.LabelField
import org.lji.vialcore.ScanEngine
import org.lji.vialcore.ScanState
import java.io.File

/**
 * The Android answer to `Sources/vialscan`: run whole recordings through the
 * real ML Kit pipeline and print a verdict per video.
 *
 * The emulator has no usable camera, and pointing one at a screen would measure
 * the screen. So frames are extracted on the host at 6 fps (the CLI's sampling
 * rate), pushed to the device, and fed to `MlKitFrame` exactly as CameraX would
 * feed them: upright, 720-wide, in order. What this exercises that the tape
 * replay cannot is everything downstream of Google's OCR - the real reads, and
 * the `ScanEngine` pacing that a tape cannot reproduce.
 *
 * Frames go in `/sdcard/Android/media/org.lji.vialscanner/frames`, named
 * `<video>_<seq>.jpg`.
 */
class VideoReplayTest {

    private val tag = "VialReplay"

    /**
     * The Unicode class fix, checked on the platform it has to hold on.
     *
     * The desktop JVM needs `UNICODE_CHARACTER_CLASS` to make `\b` behave like
     * Swift's ICU; Android rejects that flag outright because it *is* ICU. This
     * test is what says the fallback lands on the same reading, rather than
     * quietly restoring the ASCII behaviour the fix was for.
     */
    @Test
    fun unicodeBoundariesMatchSwift() {
        // A micro sign glued to a part number is not a word boundary: Swift
        // reads nothing here, so Android must read nothing here.
        assert(extractFieldsFromLines(listOf("BE0106\u00b5")).isEmpty())
        // ...while the bare number is still a catalog number.
        assert(extractFieldsFromLines(listOf("BE0106")).isNotEmpty())
        // A non-breaking space must not demote the keyword read to a shape read.
        val nbsp = extractFieldsFromLines(listOf("LOT\u00a0B305480"))[LabelField.LOT]
        assert(nbsp != null && nbsp.weight == 1.0) { "NBSP lot read: $nbsp" }
    }

    @Test
    fun replayPixelVideos() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val ocrLongEdge = InstrumentationRegistry.getArguments().getString("ocrLongEdge")?.toInt() ?: 1080
        val dirName = InstrumentationRegistry.getArguments().getString("frames") ?: "frames"
        // The media dir, not getExternalFilesDir: under scoped storage a file
        // pushed by adb into the app's data dir is not readable by the app.
        val root = ctx.externalMediaDirs.first()
        val dir = File(root, dirName)
        val files = dir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
        require(files.isNotEmpty()) { "no frames in ${dir.absolutePath}" }

        val catalog = Catalog.load(
            ctx.assets.open("definitions.csv").bufferedReader().use { it.readText() },
            ctx.assets.open("inventory.csv").bufferedReader().use { it.readText() },
        )
        Log.i(tag, "ocrLongEdge=$ocrLongEdge catalog: ${catalog.definitions.size} definitions, ${catalog.items.size} vials")

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val scanner = BarcodeScanning.getClient()
        val report = StringBuilder()

        // `<video>_<seq>.jpg` - everything before the last underscore is the clip.
        val byVideo = files.groupBy { it.name.substringBeforeLast('_') }
        for ((video, frames) in byVideo.toSortedMap()) {
            val engine = ScanEngine(catalog, EngineConfig())
            val began = System.nanoTime()
            var state: ScanState = ScanState.Scanning(0, 0, 0, 0.0, "")
            var used = 0
            for ((i, f) in frames.withIndex()) {
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                // 6 fps in, same as the CLI: the engine paces itself off these
                // timestamps, so they have to be the recording's, not the wall clock.
                state = engine.ingest(MlKitFrame(bmp, i / 6.0, recognizer, scanner, ocrLongEdge))
                bmp.recycle()
                used = i + 1
                if (state is ScanState.Finished) break
            }
            val result = (state as? ScanState.Finished)?.result ?: engine.finish()
            val line = buildString {
                append(video.padEnd(28)).append(' ')
                append(result.verdict.name.padEnd(10))
                append(result.definition?.name?.take(34)?.padEnd(36) ?: "-".padEnd(36))
                append("ab=").append(result.vial?.abNo ?: "-")
                append(" cat=").append(result.fields[LabelField.CATALOG] ?: "-")
                append(" lot=").append(result.fields[LabelField.LOT] ?: "-")
                append(" score=").append(String.format("%.1f", result.score))
                append(" margin=").append(String.format("%.1f", result.margin))
                append(" frames=").append(used).append('/').append(frames.size)
                // OCR passes vs frames ingested: the gap is what the sharpness
                // gate and the pacing threw away, which is the difference
                // between "read nothing" and "never got to look".
                append(" ocr=").append(result.framesUsed)
                append(" ms/frame=").append((System.nanoTime() - began) / 1_000_000 / maxOf(1, used))
                if (result.lotNotOnRecord) append(" LOT-NOT-ON-RECORD")
            }
            Log.i(tag, line)
            report.appendLine(line)
        }

        recognizer.close()
        scanner.close()
        File(root, "replay-$dirName-$ocrLongEdge.txt").writeText(report.toString())
        Log.i(tag, "wrote replay-report.txt")
    }
}
