package org.lji.vialscanner

import android.content.Context
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

/**
 * Records the frames of a single scan to a file.
 *
 * The clip is what makes "mark for review" honest. A parked scan with no video
 * is an accusation nobody can check a week later; with the video, whoever picks
 * it up can see the same label the phone saw. Mirrors `App/ClipRecorder.swift`.
 *
 * Swift appends the exact sample buffers the engine reads onto an
 * `AVAssetWriter`, so recording adds an encode but no extra capture path. There
 * is no equivalent hook here: `VideoCapture<Recorder>` is its own CameraX use
 * case, bound alongside `Preview` and `ImageAnalysis` in
 * `ScanController.bind`, drawing from the same sensor but through a separate
 * pipeline. The trade is a few extra milliseconds of camera-graph bookkeeping
 * for not hand-rolling a `MediaCodec` encoder off ML Kit's frames.
 */
class ClipRecorder(private val context: Context) {

    /**
     * ~4 Mbps at 1080p, same budget as Swift's `AVVideoAverageBitRateKey`:
     * small enough that a day of scanning fits on the phone, sharp enough to
     * read a lot number back off it. No audio - a barcode scanner has no
     * business asking for the microphone, and Swift never enables it either.
     */
    val useCase: VideoCapture<Recorder> = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
            )
            .setTargetVideoEncodingBitRate(4_000_000)
            .build()
    )

    private var recording: Recording? = null
    private var pendingFile: File? = null
    private var completion: ((File?) -> Unit)? = null
    private var cancelling = false

    var isRecording = false
        private set

    /**
     * Called once a scan starts, if the caller decided this scan should be
     * recorded. Every clip starts in a temp directory and is only moved into
     * permanent storage if the operator keeps it - see `AppStore.keepClip`.
     */
    @Synchronized
    fun start() {
        if (recording != null) return
        val dir = File(context.filesDir, "clips-tmp").apply { mkdirs() }
        val file = File(dir, "scan-${UUID.randomUUID()}.mp4")
        pendingFile = file
        val output = FileOutputOptions.Builder(file).build()
        recording = useCase.output
            .prepareRecording(context, output)
            .start(ContextCompat.getMainExecutor(context)) { event -> onEvent(event) }
        isRecording = true
    }

    @Synchronized
    private fun onEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        isRecording = false
        val wasCancelling = cancelling
        cancelling = false
        val file = pendingFile
        recording = null
        pendingFile = null
        if (wasCancelling) {
            file?.delete()
            return
        }
        val done = completion
        completion = null
        if (event.hasError() || file == null || !file.exists()) {
            file?.delete()
            done?.invoke(null)
        } else {
            done?.invoke(file)
        }
    }

    /**
     * Finish and hand back the file, or null if nothing usable was written.
     * `Recording.stop()` finalises asynchronously, so the file only exists once
     * `onEvent` fires - callers must not act on a result before this returns.
     */
    @Synchronized
    fun finish(onDone: (File?) -> Unit) {
        val r = recording
        if (r == null) {
            onDone(null)
            return
        }
        completion = onDone
        r.stop()
    }

    /** Abandon the current recording and delete the file. */
    @Synchronized
    fun cancel() {
        val r = recording ?: return
        cancelling = true
        r.close()
    }

    companion object {
        fun discard(file: File?) {
            file?.delete()
        }
    }
}
