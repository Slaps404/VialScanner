package org.lji.vialscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.lji.vialcore.*
import java.util.concurrent.Executors

/**
 * The Android port of the scanner.
 *
 * Everything that decides *what a vial is* lives in `:vialcore` and is the same
 * logic the iOS app runs, checked against it by the tape replay in
 * `vialcore/src/test`. This file is only the parts that cannot be shared:
 * camera plumbing, permissions and the screen.
 */
class MainActivity : ComponentActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scanner = BarcodeScanning.getClient()

    private lateinit var catalog: Catalog
    private var engine: ScanEngine? = null

    private val uiState = mutableStateOf<ScanState>(
        ScanState.Scanning(0, 0, 0, 0.0, "Line the label up")
    )
    private val hasCamera = mutableStateOf(false)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera.value = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        catalog = assets.open("definitions.csv").bufferedReader().use { d ->
            assets.open("inventory.csv").bufferedReader().use { i ->
                Catalog.load(d.readText(), i.readText())
            }
        }
        engine = ScanEngine(catalog)

        hasCamera.value = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCamera.value) permission.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ScanScreen(
                    granted = hasCamera.value,
                    state = uiState.value,
                    catalogSize = catalog.items.size,
                    onBind = { view -> bindCamera(view) },
                    onRescan = { rescan() },
                )
            }
        }
    }

    private fun rescan() {
        engine?.reset()
        uiState.value = ScanState.Scanning(0, 0, 0, 0.0, "Line the label up")
    }

    private fun bindCamera(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                // Drop frames rather than queue them: a stale frame answers a
                // question about a vial the user has already turned past.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val e = engine ?: return
            if (uiState.value is ScanState.Finished) return

            val upright = uprightBitmap(proxy)
            val frame = MlKitFrame(
                bitmap = upright,
                timestamp = proxy.imageInfo.timestamp / 1_000_000_000.0,
                recognizer = recognizer,
                scanner = scanner,
            )
            val state = e.ingest(frame)
            uiState.value = state
        } finally {
            proxy.close()
        }
    }

    /** Rotate into display orientation, because the ROI is expressed relative to
     *  what the user is aiming with, not to the sensor. */
    private fun uprightBitmap(proxy: ImageProxy): Bitmap {
        val bmp = proxy.toBitmap()
        val deg = proxy.imageInfo.rotationDegrees
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        recognizer.close()
        scanner.close()
    }
}

@Composable
private fun ScanScreen(
    granted: Boolean,
    state: ScanState,
    catalogSize: Int,
    onBind: (PreviewView) -> Unit,
    onRescan: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { v ->
                        v.scaleType = PreviewView.ScaleType.FILL_CENTER
                        onBind(v)
                    }
                },
            )
            // The reticle is not decoration: recognition is restricted to it, so
            // it has to sit where the ROI actually is (15% in, 20% down).
            Box(
                Modifier
                    .fillMaxWidth(0.70f)
                    .fillMaxHeight(0.60f)
                    .align(Alignment.Center)
                    .border(2.dp, Color.White.copy(alpha = 0.7f))
            )
        } else {
            Text(
                "Camera permission is needed to scan vials.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = Color(0xFF111111),
        ) {
            Column(Modifier.padding(16.dp)) {
                when (state) {
                    is ScanState.Scanning -> {
                        Text(state.hint, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progress.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "frames ${state.framesSeen} · ocr ${state.ocrFrames} · views ${state.views} · $catalogSize vials on file",
                            color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    is ScanState.Finished -> ResultCard(state.result, onRescan)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: ScanResult, onRescan: () -> Unit) {
    val (badge, color) = when {
        r.readNothing -> "NOTHING READ" to Color(0xFF888888)
        r.verdict == Verdict.CERTAIN -> "CERTAIN" to Color(0xFF3DDC84)
        r.verdict == Verdict.CONFIDENT -> "CONFIDENT" to Color(0xFF3DDC84)
        r.verdict == Verdict.AMBIGUOUS -> "NEEDS REVIEW" to Color(0xFFFFC107)
        else -> "NEW VIAL" to Color(0xFF64B5F6)
    }
    Text(badge, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))

    val def = r.definition
    if (def != null) {
        Text(def.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("${def.vendor} ${def.catalog} · clone ${def.clone}", color = Color.LightGray, fontSize = 14.sp)
    } else {
        Text(
            if (r.readNothing) "The camera never read the label" else "Nothing on file matches this label",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        )
    }

    val vial = r.vial
    if (vial != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            "AbNo ${vial.abNo} · lot ${vial.lot} · ${vial.location} ${vial.box}",
            color = Color.White, fontSize = 14.sp,
        )
        if (vial.concentration.isNotEmpty()) {
            Text("conc ${vial.concentration}", color = Color.LightGray, fontSize = 13.sp)
        }
    } else if (r.lotNotOnRecord) {
        Spacer(Modifier.height(6.dp))
        Text("Lot not on record - new lot of a known antibody", color = Color(0xFFFFC107), fontSize = 14.sp)
    }

    Spacer(Modifier.height(8.dp))
    val read = LabelField.entries.mapNotNull { f -> r.fields[f]?.let { "$f=$it" } }
    Text(read.joinToString("  "), color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    Text(r.reasons.joinToString(", "), color = Color.Gray, fontSize = 12.sp)

    Spacer(Modifier.height(12.dp))
    Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text("Scan another") }
}
