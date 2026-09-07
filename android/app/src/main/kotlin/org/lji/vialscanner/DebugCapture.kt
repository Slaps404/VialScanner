package org.lji.vialscanner

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lji.vialcore.ScanResult
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * What is retained while a scan is running. Performance mode keeps structured
 * events only. Accuracy mode also keeps a lossless copy of every OCR input and
 * writes those images after the scan, so neither mode writes to disk per frame.
 * Accuracy mode intentionally pays PNG encoding cost on the analyzer path.
 */
@Serializable
enum class DebugCaptureMode {
    @SerialName("performance")
    PERFORMANCE,
    @SerialName("accuracy")
    ACCURACY,
}

@Serializable
data class DebugBundle(
    val schemaVersion: Int = 1,
    val format: String = "org.lji.vialscanner.debug",
    val scan: DebugScanMetadata,
    val build: DebugBuildMetadata,
    val catalog: DebugCatalogMetadata,
    val config: DebugConfigMetadata,
    val device: DebugDeviceMetadata,
    val thermal: DebugThermalMetadata,
    val optics: DebugOpticsMetadata,
    val groundTruth: DebugGroundTruth? = null,
    val result: DebugResult? = null,
    val frames: List<DebugFrameTrace> = emptyList(),
    val artifacts: DebugArtifacts = DebugArtifacts(),
)

@Serializable
data class DebugArtifacts(
    /** Relative to the directory containing bundle.json. */
    val video: String? = null,
)

@Serializable
data class DebugScanMetadata(
    val id: String,
    val status: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val startedAt: String,
    val endedAt: String? = null,
)

@Serializable
data class DebugBuildMetadata(
    val applicationID: String,
    val appVersion: String,
    val versionCode: Int,
    val buildID: String,
)

@Serializable
data class DebugCatalogMetadata(
    val sha256: String,
    val definitions: Int,
    val inventoryItems: Int,
)

@Serializable
data class DebugConfigMetadata(
    val mode: DebugCaptureMode,
    val minScanSeconds: Double,
    val maxScanSeconds: Double,
    val roiInset: Double,
    val barcodeEveryNthFrame: Int,
    val minOCRInterval: Double,
    val sharpnessRatio: Double,
    val sharpnessWindow: Int,
    val ocrLongEdge: Int,
)

@Serializable
data class DebugDeviceMetadata(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
)

@Serializable
data class DebugThermalMetadata(
    val startStatus: Int? = null,
    val startLabel: String? = null,
    val endStatus: Int? = null,
    val endLabel: String? = null,
)

@Serializable
data class DebugGroundTruth(
    val note: String? = null,
    val expectation: ClipExpectationDTO? = null,
)

@Serializable
data class DebugImageSize(
    val width: Int,
    val height: Int,
)

@Serializable
data class DebugOpticsSample(
    val image: DebugImageSize,
    val crop: DebugImageSize,
    val rotationDegrees: Int,
    val zoomRatio: Float,
    val torchOn: Boolean,
    val focusLocked: Boolean,
    val ultraWideFocuses: Boolean,
    val minZoom: Float,
    val maxZoom: Float,
)

@Serializable
data class DebugOpticsMetadata(
    val first: DebugOpticsSample? = null,
    val last: DebugOpticsSample? = null,
)

@Serializable
data class DebugTextObservation(
    val text: String,
    val confidence: Float,
    val boundingBox: DebugNormRect? = null,
)

@Serializable
data class DebugNormRect(
    val minX: Double,
    val minY: Double,
    val width: Double,
    val height: Double,
)

@Serializable
data class DebugFrameTrace(
    val index: Int,
    val timestampSeconds: Double,
    val elapsedSeconds: Double,
    val image: DebugImageSize,
    val rotationDegrees: Int,
    val sharpness: Double? = null,
    val sharpnessMs: Long? = null,
    val sharpnessEvaluated: Boolean,
    val barcodeAttempted: Boolean,
    val barcodeMs: Long? = null,
    val barcodeInput: DebugImageSize? = null,
    val barcodes: List<BarcodeMetadata> = emptyList(),
    val ocrAttempted: Boolean,
    val ocrMs: Long? = null,
    val ocrInput: DebugImageSize? = null,
    val ocrInputFile: String? = null,
    val observations: List<DebugTextObservation> = emptyList(),
    val ingestMs: Long,
    val engineState: String,
    val engineFramesSeen: Int? = null,
    val engineOcrFrames: Int? = null,
    val engineViews: Int? = null,
    val engineProgress: Double? = null,
    val engineHint: String? = null,
    /** This is an inference from the public frame seam, not engine internals. */
    val skipReason: String? = null,
)

@Serializable
data class DebugResult(
    val verdict: String,
    val evidenceLevel: String,
    val readNothing: Boolean,
    val definitionName: String? = null,
    val abNo: String? = null,
    val catalog: String? = null,
    val lot: String? = null,
    val vendor: String? = null,
    val clone: String? = null,
    val score: Double,
    val margin: Double,
    val reasons: List<String>,
    val fields: Map<String, String>,
    val barcode: BarcodeMetadata? = null,
    val framesUsed: Int,
    val lotNotOnRecord: Boolean,
    val canAutoConfirm: Boolean = false,
    val evidenceStandard: String = "BEST",
)

/** Input from one `MlKitFrame` after the synchronous engine pass returns. */
data class DebugFrameInput(
    val timestampSeconds: Double,
    val image: DebugImageSize,
    val rotationDegrees: Int,
    val sharpness: Double?,
    val sharpnessMs: Long?,
    val sharpnessEvaluated: Boolean,
    val barcodeAttempted: Boolean,
    val barcodeMs: Long?,
    val barcodeInput: DebugImageSize?,
    val barcodes: List<BarcodeMetadata>,
    val ocrAttempted: Boolean,
    val ocrMs: Long?,
    val ocrInput: DebugImageSize?,
    val observations: List<DebugTextObservation>,
    val ingestMs: Long,
    val engineState: String,
    val engineFramesSeen: Int? = null,
    val engineOcrFrames: Int? = null,
    val engineViews: Int? = null,
    val engineProgress: Double? = null,
    val engineHint: String? = null,
    val skipReason: String? = null,
    val ocrInputBytes: ByteArray? = null,
)

private data class PendingFrame(
    val trace: DebugFrameTrace,
    val ocrInputBytes: ByteArray?,
)

private data class SessionSnapshot(
    val id: String,
    val status: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val build: DebugBuildMetadata,
    val catalog: DebugCatalogMetadata,
    val config: DebugConfigMetadata,
    val device: DebugDeviceMetadata,
    val thermal: DebugThermalMetadata,
    val optics: DebugOpticsMetadata,
    val groundTruth: DebugGroundTruth?,
    val result: DebugResult?,
    val frames: List<PendingFrame>,
    val bundleFile: File,
    val videoFile: String?,
)

private class DebugSession(
    val id: String,
    val startedAtEpochMs: Long,
    val build: DebugBuildMetadata,
    val catalog: DebugCatalogMetadata,
    val config: DebugConfigMetadata,
    val device: DebugDeviceMetadata,
    val thermalStart: ThermalSample,
    val bundleFile: File,
) {
    val frames = ArrayList<PendingFrame>()
    var firstTimestamp: Double? = null
    var status: String = "running"
    var endedAtEpochMs: Long? = null
    var thermalEnd: ThermalSample? = null
    var opticsFirst: DebugOpticsSample? = null
    var opticsLast: DebugOpticsSample? = null
    var groundTruth: DebugGroundTruth? = null
    var result: DebugResult? = null
    var videoFile: String? = null
}

private data class ThermalSample(val status: Int?, val label: String?)

/**
 * Owns the low-observer-effect debug stream. All calls from the camera and UI
 * are cheap synchronized mutations; serialization and image writes happen on a
 * single worker after a scan has ended.
 */
class DebugCapture(private val context: Context) {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val rootDir: File = context.externalMediaDirs.firstOrNull()
        ?.resolve("debug-bundles")
        ?: File(context.filesDir, "debug-bundles")

    @Volatile
    var mode: DebugCaptureMode = DebugCaptureMode.PERFORMANCE
        private set

    @Volatile
    var currentScanID: String? = null
        private set

    @Volatile
    var currentBundleFile: File? = null
        private set

    @Volatile
    var currentBundleReference: String? = null
        private set

    private var session: DebugSession? = null

    @Synchronized
    fun begin(store: AppStore) {
        session?.takeIf { it.status == "running" }?.let { finishWithoutResult(it, "replaced") }

        mode = store.settings.debugCaptureMode
        val id = UUID.randomUUID().toString()
        val dir = File(rootDir, id)
        val file = File(dir, "bundle.json")
        val config = store.settings.engineConfig()
        val started = System.currentTimeMillis()
        val build = DebugBuildMetadata(
            applicationID = BuildConfig.APPLICATION_ID,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildID = "${BuildConfig.APPLICATION_ID}:${BuildConfig.VERSION_NAME}:${BuildConfig.VERSION_CODE}:${BuildConfig.SOURCE_ID}",
        )
        val device = DebugDeviceMetadata(
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            androidRelease = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            apiLevel = Build.VERSION.SDK_INT,
        )
        val s = DebugSession(
            id = id,
            startedAtEpochMs = started,
            build = build,
            catalog = DebugCatalogMetadata(
                sha256 = catalogSha256(store),
                definitions = store.defs.size,
                inventoryItems = store.items.size,
            ),
            config = DebugConfigMetadata(
                mode = mode,
                minScanSeconds = config.minScanDuration,
                maxScanSeconds = config.maxScanDuration,
                roiInset = store.settings.roiInset,
                barcodeEveryNthFrame = config.barcodeEveryNthFrame,
                minOCRInterval = config.minOCRInterval,
                sharpnessRatio = config.sharpnessRatio,
                sharpnessWindow = config.sharpnessWindow,
                ocrLongEdge = MlKitFrame.DEFAULT_OCR_LONG_EDGE,
            ),
            device = device,
            thermalStart = thermalSample(),
            bundleFile = file,
        )
        session = s
        currentScanID = id
        currentBundleFile = file
        currentBundleReference = "$id/bundle.json"
    }

    @Synchronized
    fun record(frame: DebugFrameInput, optics: DebugOpticsSample) {
        val s = session ?: return
        if (s.status != "running") return
        val index = s.frames.size
        val first = s.firstTimestamp ?: frame.timestampSeconds.also { s.firstTimestamp = it }
        val elapsed = max(0.0, frame.timestampSeconds - first)
        val imageFile = if (frame.ocrInputBytes?.isNotEmpty() == true) {
            "ocr/ocr-%04d.png".format(index)
        } else {
            null
        }
        s.frames += PendingFrame(
            trace = DebugFrameTrace(
                index = index,
                timestampSeconds = frame.timestampSeconds,
                elapsedSeconds = elapsed,
                image = frame.image,
                rotationDegrees = frame.rotationDegrees,
                sharpness = frame.sharpness,
                sharpnessMs = frame.sharpnessMs,
                sharpnessEvaluated = frame.sharpnessEvaluated,
                barcodeAttempted = frame.barcodeAttempted,
                barcodeMs = frame.barcodeMs,
                barcodeInput = frame.barcodeInput,
                barcodes = frame.barcodes,
                ocrAttempted = frame.ocrAttempted,
                ocrMs = frame.ocrMs,
                ocrInput = frame.ocrInput,
                ocrInputFile = imageFile,
                observations = frame.observations,
                ingestMs = frame.ingestMs,
                engineState = frame.engineState,
                engineFramesSeen = frame.engineFramesSeen,
                engineOcrFrames = frame.engineOcrFrames,
                engineViews = frame.engineViews,
                engineProgress = frame.engineProgress,
                engineHint = frame.engineHint,
                skipReason = frame.skipReason,
            ),
            ocrInputBytes = frame.ocrInputBytes,
        )
        if (s.opticsFirst == null) s.opticsFirst = optics
        s.opticsLast = optics
    }

    /** Adds the operator's ground truth after the result card is visible. */
    @Synchronized
    fun annotate(note: String) {
        if (note.isBlank()) return
        val s = session ?: return
        val expectation = org.lji.vialcore.parseClipNote(note)
        s.groundTruth = DebugGroundTruth(
            note = note,
            expectation = expectation?.let { ClipExpectationDTO(it.catalog, it.lot, it.clone, it.verdict) },
        )
        if (s.status != "running") enqueue(snapshot(s))
    }

    @Synchronized
    fun finish(
        result: ScanResult,
        sourceVideo: File? = null,
        onVideoPackaged: ((Boolean) -> Unit)? = null,
    ): File? {
        val s = session ?: run {
            onVideoPackaged?.invoke(false)
            return currentBundleFile
        }
        if (s.status != "running") {
            onVideoPackaged?.invoke(false)
            return s.bundleFile
        }
        s.status = "finished"
        s.endedAtEpochMs = System.currentTimeMillis()
        s.thermalEnd = thermalSample()
        s.result = result.toDebugResult()
        enqueue(snapshot(s), sourceVideo, onVideoPackaged)
        return s.bundleFile
    }

    @Synchronized
    fun cancel(reason: String = "cancelled") {
        val s = session ?: return
        if (s.status != "running") return
        finishWithoutResult(s, reason)
    }

    @Synchronized
    fun shutdown() {
        session?.takeIf { it.status == "running" }?.let { finishWithoutResult(it, "shutdown") }
        writer.shutdown()
        runCatching { writer.awaitTermination(5, TimeUnit.SECONDS) }
    }

    private fun finishWithoutResult(s: DebugSession, reason: String) {
        s.status = reason
        s.endedAtEpochMs = System.currentTimeMillis()
        s.thermalEnd = thermalSample()
        enqueue(snapshot(s))
    }

    private fun snapshot(s: DebugSession): SessionSnapshot = SessionSnapshot(
        id = s.id,
        status = s.status,
        startedAtEpochMs = s.startedAtEpochMs,
        endedAtEpochMs = s.endedAtEpochMs,
        build = s.build,
        catalog = s.catalog,
        config = s.config,
        device = s.device,
        thermal = DebugThermalMetadata(
            startStatus = s.thermalStart.status,
            startLabel = s.thermalStart.label,
            endStatus = s.thermalEnd?.status,
            endLabel = s.thermalEnd?.label,
        ),
        optics = DebugOpticsMetadata(s.opticsFirst, s.opticsLast),
        groundTruth = s.groundTruth,
        result = s.result,
        frames = s.frames.toList(),
        bundleFile = s.bundleFile,
        videoFile = s.videoFile,
    )

    private fun enqueue(
        snapshot: SessionSnapshot,
        sourceVideo: File? = null,
        onVideoPackaged: ((Boolean) -> Unit)? = null,
    ) {
        writer.execute {
            val packaged = if (sourceVideo == null) false else packageVideo(snapshot, sourceVideo)
            val ready = if (packaged) snapshot.copy(videoFile = VIDEO_FILE) else snapshot
            if (sourceVideo != null) {
                synchronized(this) {
                    session?.takeIf { it.id == snapshot.id }?.videoFile = ready.videoFile
                }
                onVideoPackaged?.invoke(packaged)
            }
            writeSnapshot(ready)
        }
    }

    /** Copy before exposing the result card, so a quick action cannot move the source first. */
    private fun packageVideo(snapshot: SessionSnapshot, source: File): Boolean = runCatching {
        val dir = snapshot.bundleFile.parentFile ?: return@runCatching false
        dir.mkdirs()
        val target = File(dir, VIDEO_FILE)
        val partial = File(dir, "$VIDEO_FILE.tmp")
        partial.delete()
        source.copyTo(partial, overwrite = true)
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        target.isFile && target.length() == source.length()
    }.getOrDefault(false)

    private fun writeSnapshot(s: SessionSnapshot) {
        runCatching {
            val dir = s.bundleFile.parentFile ?: return@runCatching
            dir.mkdirs()
            val traces = s.frames.map { pending ->
                val bytes = pending.ocrInputBytes
                val path = pending.trace.ocrInputFile
                if (bytes != null && path != null) {
                    val image = File(dir, path)
                    image.parentFile?.mkdirs()
                    runCatching { image.writeBytes(bytes) }.getOrNull()
                    if (!image.exists()) pending.trace.copy(ocrInputFile = null) else pending.trace
                } else {
                    pending.trace
                }
            }
            val bundle = DebugBundle(
                scan = DebugScanMetadata(
                    id = s.id,
                    status = s.status,
                    startedAtEpochMs = s.startedAtEpochMs,
                    endedAtEpochMs = s.endedAtEpochMs,
                    startedAt = Instant.ofEpochMilli(s.startedAtEpochMs).toString(),
                    endedAt = s.endedAtEpochMs?.let { Instant.ofEpochMilli(it).toString() },
                ),
                build = s.build,
                catalog = s.catalog,
                config = s.config,
                device = s.device,
                thermal = s.thermal,
                optics = s.optics,
                groundTruth = s.groundTruth,
                result = s.result,
                frames = traces,
                artifacts = DebugArtifacts(video = s.videoFile),
            )
            val text = json.encodeToString(bundle)
            val tmp = File(dir, "bundle.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(s.bundleFile)) {
                s.bundleFile.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun thermalSample(): ThermalSample {
        if (Build.VERSION.SDK_INT < 29) return ThermalSample(null, null)
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = power?.currentThermalStatus
        return ThermalSample(status, thermalLabel(status))
    }

    companion object {
        private const val VIDEO_FILE = "scan.mp4"

        private fun thermalLabel(status: Int?): String? = when (status) {
            null -> null
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }

        private fun catalogSha256(store: AppStore): String {
            // Match the iOS sidecar's length-prefixed, source-order hash. A
            // delimiter-only hash can collide when an imported value contains
            // that delimiter, and sorting would hide a source-row reorder.
            val values = ArrayList<String>()
            fun add(value: String?) {
                val text = value ?: ""
                values += "${text.toByteArray(Charsets.UTF_8).size}:$text"
            }
            fun addExtra(entries: List<FieldEntryDTO>) {
                add(entries.size.toString())
                entries.forEach { add(it.name); add(it.value) }
            }

            add(store.defs.size.toString())
            store.defs.forEach { d ->
                add(d.name); add(d.target); add(d.clone); add(d.host)
                add(d.isotype); add(d.vendor); add(d.catalog)
                add(d.inventoryLinks.size.toString())
                d.inventoryLinks.forEach(::add)
                addExtra(d.extra)
            }
            add(store.items.size.toString())
            store.items.forEach { i ->
                add(i.abNo); add(i.definitionName); add(i.vialName); add(i.lot)
                add(i.concentration); add(i.location); add(i.box); add(i.owner)
                addExtra(i.extra)
            }
            val data = values.joinToString("\n").toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }
        }

        private fun ScanResult.toDebugResult(): DebugResult = DebugResult(
            verdict = verdict.name,
            evidenceLevel = evidenceLevel.name,
            readNothing = readNothing,
            definitionName = definition?.name,
            abNo = vial?.abNo,
            catalog = fields[org.lji.vialcore.LabelField.CATALOG] ?: definition?.catalog,
            lot = fields[org.lji.vialcore.LabelField.LOT],
            vendor = definition?.vendor,
            clone = definition?.clone,
            score = score,
            margin = margin,
            reasons = reasons,
            fields = fields.entries.associate { it.key.name to it.value },
            barcode = barcode?.let { BarcodeMetadata(it.raw, it.symbology, it.catalog, it.lot) },
            framesUsed = framesUsed,
            lotNotOnRecord = lotNotOnRecord,
            canAutoConfirm = canAutoConfirm,
            evidenceStandard = evidenceStandard.name,
        )
    }
}
