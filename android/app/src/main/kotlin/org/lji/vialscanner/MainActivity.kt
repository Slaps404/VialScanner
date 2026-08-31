package org.lji.vialscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File

/**
 * The app shell: one store, one scan controller, six screens.
 *
 * Everything that decides *what a vial is* lives in `:vialcore` and is the same
 * logic the iOS app runs, held to it by the tape-replay parity test. This layer
 * is the parts that cannot be shared: camera, storage, files and screens.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: AppStore
    private lateinit var controller: ScanController

    private var hasCamera by mutableStateOf(false)
    /** Two CSVs are imported together: definitions without inventory would leave
     *  every vial unlinked, so the picker collects both before anything loads. */
    private var pendingDefs by mutableStateOf<String?>(null)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }

    private val pickDefs = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        pendingDefs = readText(uri)
        if (pendingDefs == null) store.banner = AppStore.Banner("Could not read that file.", isError = true)
        else pickInv.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv"))
    }

    private val pickInv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val defs = pendingDefs
        pendingDefs = null
        if (uri == null || defs == null) return@registerForActivityResult
        val inv = readText(uri)
        if (inv == null) {
            store.banner = AppStore.Banner("Could not read that file.", isError = true)
            return@registerForActivityResult
        }
        store.importCSVs(defs, inv, AppStore.ImportMode.MERGE)
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = readText(uri)
        if (text == null) store.banner = AppStore.Banner("Could not read that file.", isError = true)
        else store.importBackupJSON(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(applicationContext)
        controller = ScanController(store, applicationContext)
        // Both need a real Context handed in once before anything calls them -
        // see the class doc in Feedback.kt for why that differs from iOS.
        Haptics.init(applicationContext)
        Speaker.init(applicationContext)
        Haptics.enabled = store.settings.haptics
        Speaker.enabled = store.settings.speak

        refreshCameraPermission()
        if (!hasCamera) permission.launch(Manifest.permission.CAMERA)

        setContent {
            VialTheme {
                AppRoot(
                    store = store,
                    controller = controller,
                    hasCamera = hasCamera,
                    onRequestCamera = { permission.launch(Manifest.permission.CAMERA) },
                    onPickCSVs = { pickDefs.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv")) },
                    onPickBackup = { pickBackup.launch(arrayOf("application/json", "text/*")) },
                    onShare = { share(it) },
                )
            }
        }
    }

    /** The user can grant the permission in system settings and come back, so
     *  re-check on every resume rather than only at launch. */
    override fun onResume() {
        super.onResume()
        refreshCameraPermission()
    }

    private fun refreshCameraPermission() {
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun readText(uri: Uri): String? =
        runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "json") "application/json" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${file.name}"))
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.shutdown()
        Speaker.shutdown()
    }
}

@Composable
private fun AppRoot(
    store: AppStore,
    controller: ScanController,
    hasCamera: Boolean,
    onRequestCamera: () -> Unit,
    onPickCSVs: () -> Unit,
    onPickBackup: () -> Unit,
    onShare: (File) -> Unit,
) {
    var route by remember { mutableStateOf(Route.SCAN) }
    var menuOpen by remember { mutableStateOf(false) }

    // The camera keeps running behind other screens only while a scan is live;
    // leaving the scanner mid-rotation would otherwise silently commit a verdict
    // built from frames of a drawer.
    // Leaving the scanner stops the analyser; coming back does not restart it.
    // iOS idles until the shutter is pressed, and an app that starts reading the
    // room the moment you close the drawer burns battery and logs frames nobody
    // asked for.
    LaunchedEffect(route) { if (route != Route.SCAN) controller.pause() }

    // Mirrors Swift's `.onChange(of: store.settings.haptics)` /
    // `.onChange(of: store.settings.speak)` in RootView.swift: the toggle in
    // Settings only edits the store, something still has to push the new value
    // into the plain (non-Composable) objects that actually fire feedback.
    LaunchedEffect(store.settings.haptics) { Haptics.enabled = store.settings.haptics }
    LaunchedEffect(store.settings.speak) { Speaker.enabled = store.settings.speak }

    AppShell(
        store = store,
        route = route,
        open = menuOpen,
        onOpenChange = { menuOpen = it },
        onRoute = { route = it },
    ) {
        Box(Modifier.fillMaxSize().background(Ink.bg)) {
            val openMenu = { menuOpen = true }
            when (route) {
                Route.SCAN -> ScanScreen(store, controller, hasCamera, openMenu, onRequestCamera)
                Route.INVENTORY -> InventoryScreen(store, openMenu)
                Route.REVIEW -> ReviewScreen(store, openMenu)
                Route.AUDIT -> AuditScreen(store, openMenu)
                Route.HISTORY -> HistoryScreen(store, openMenu)
                Route.DATA -> DataScreen(store, openMenu, onPickCSVs, onPickBackup, onShare)
                Route.SETTINGS -> SettingsScreen(store, openMenu)
            }

            store.banner?.let { b ->
                LaunchedEffect(b.id) { delay(4000); if (store.banner?.id == b.id) store.banner = null }
                Banner(b)
            }
        }
    }
}
