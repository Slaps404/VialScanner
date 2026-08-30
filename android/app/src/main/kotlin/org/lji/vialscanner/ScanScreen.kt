package org.lji.vialscanner

import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.lji.vialcore.LabelField
import org.lji.vialcore.ScanResult
import org.lji.vialcore.ScanState
import org.lji.vialcore.Verdict

/**
 * The main screen: viewfinder, live hints, the verdict, and the four things you
 * can do with it.
 *
 * The four actions are the whole point of the app and are deliberately the same
 * four as on iOS: confirm (this vial is here), rescan, park for review, or
 * receive it as a new vial. `ScanResult.verdict` is the gate for which of them
 * is offered without a second thought.
 */
@Composable
fun ScanScreen(store: AppStore, controller: ScanController, granted: Boolean) {
    val owner = LocalLifecycleOwner.current
    var showSessionBox by remember { mutableStateOf(false) }
    var receiving by remember { mutableStateOf<ItemRecord?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }

    val state = controller.state

    // Hands-free: commit the verdict on its own after a beat, so a run of vials
    // never needs a tap. The delay is not cosmetic - it is the window in which a
    // wrong answer can still be caught by the person holding the vial.
    LaunchedEffect(state) {
        val s = state
        if (s !is ScanState.Finished || !store.settings.handsFree) return@LaunchedEffect
        val r = s.result
        delay((store.settings.handsFreeDelay * 1000).toLong())
        if (controller.state !== s) return@LaunchedEffect
        when {
            meetsBar(r.verdict, store.settings.autoCommit) && r.vial != null -> {
                flash = confirm(store, r)
                controller.rescan()
            }
            store.settings.handsFreeParksUncertain && !r.readNothing -> {
                park(store, r)
                flash = "Parked for review"
                controller.rescan()
            }
            else -> controller.rescan()
        }
    }

    LaunchedEffect(flash) { if (flash != null) { delay(2500); flash = null } }

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { v ->
                        v.scaleType = PreviewView.ScaleType.FILL_CENTER
                        controller.bind(owner, v)
                    }
                },
            )
            // The reticle is not decoration: recognition is restricted to it, so
            // it is drawn where the region of interest actually is.
            val inset = store.settings.roiInset.toFloat().coerceIn(0f, 0.4f)
            // Doubled, dark outside white, for the same reason the chips carry
            // their own ground: a single white hairline is invisible against a
            // white vial label, which is the one thing this box is always
            // drawn over.
            Box(
                Modifier
                    .fillMaxWidth(1f - 2 * inset)
                    .fillMaxHeight(1f - 2.6f * inset)
                    .align(Alignment.Center)
                    .border(4.dp, Color.Black.copy(alpha = 0.35f))
                    .padding(2.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.85f))
            )
        } else {
            EmptyState("Camera permission is needed to scan vials.")
        }

        // Top bar: session box and torch.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val box = store.activeSessionBox
            // These sit on top of the camera, whose brightness is whatever the
            // user is pointing at. A default AssistChip is a transparent
            // container with dim grey text, which disappears entirely against a
            // pale label or a lit bench, so both chips carry their own dark
            // ground rather than borrowing the preview's.
            OverlayChip(box ?: "Set box") { showSessionBox = true }
            Spacer(Modifier.weight(1f))
            OverlayChip(if (controller.torchOn) "Torch on" else "Torch") { controller.toggleTorch() }
        }

        flash?.let {
            Surface(
                color = Ink.good,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            ) { Text(it, color = Color.Black, modifier = Modifier.padding(10.dp), fontWeight = FontWeight.SemiBold) }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = Ink.card,
        ) {
            Column(Modifier.padding(16.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (state) {
                    is ScanState.Scanning -> ScanningPanel(state, store, controller)
                    is ScanState.Finished -> ResultPanel(
                        r = state.result,
                        store = store,
                        onConfirm = { flash = confirm(store, state.result); controller.rescan() },
                        onPark = { park(store, state.result); flash = "Parked for review"; controller.rescan() },
                        onReceive = { receiving = draftFrom(store, state.result) },
                        onRescan = { controller.rescan() },
                    )
                }
            }
        }
    }

    if (showSessionBox) {
        SessionBoxSheet(store) { showSessionBox = false }
    }

    receiving?.let { draft ->
        ReceiveSheet(
            store = store,
            draft = draft,
            onDismiss = { receiving = null },
            onSaved = { saved ->
                val s = controller.state
                if (s is ScanState.Finished) {
                    store.log(record(s.result, ScanOutcome.RECEIVED).copy(abNo = saved.abNo))
                }
                receiving = null
                flash = "Received AbNo ${saved.abNo}"
                controller.rescan()
            },
        )
    }
}

@Composable
private fun ScanningPanel(state: ScanState.Scanning, store: AppStore, controller: ScanController) {
    Text(state.hint, color = Ink.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(progress = { state.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Mono("frames ${state.framesSeen} · ocr ${state.ocrFrames} · views ${state.views} · ${store.items.size} vials on file")
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { controller.finishNow() }, modifier = Modifier.fillMaxWidth()) {
        Text("Answer with what you have")
    }
}

@Composable
private fun ResultPanel(
    r: ScanResult,
    store: AppStore,
    onConfirm: () -> Unit,
    onPark: () -> Unit,
    onReceive: () -> Unit,
    onRescan: () -> Unit,
) {
    val (badge, color) = when {
        r.readNothing -> "NOTHING READ" to Ink.dim
        r.verdict == Verdict.CERTAIN -> "CERTAIN" to Ink.good
        r.verdict == Verdict.CONFIDENT -> "CONFIDENT" to Ink.good
        r.verdict == Verdict.AMBIGUOUS -> "NEEDS REVIEW" to Ink.warn
        else -> "NEW VIAL" to Ink.info
    }
    Text(badge, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))

    val def = r.definition
    if (def != null) {
        Text(def.name, color = Ink.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("${def.vendor} ${def.catalog} · clone ${def.clone}", color = Ink.dim, fontSize = 14.sp)
    } else {
        Text(
            if (r.readNothing) "The camera never read the label" else "Nothing on file matches this label",
            color = Ink.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        )
    }

    val vial = r.vial
    if (vial != null) {
        Spacer(Modifier.height(8.dp))
        KeyValueRow("AbNo", vial.abNo)
        KeyValueRow("lot", vial.lot)
        KeyValueRow("where", listOf(vial.location, vial.box).filter { it.isNotEmpty() }.joinToString(" · "))
        KeyValueRow("conc", vial.concentration)
        KeyValueRow("owner", vial.owner)
        if (r.otherVials.isNotEmpty()) {
            KeyValueRow("also on file", r.otherVials.joinToString(", ") { "AbNo ${it.abNo}" })
        }
    } else if (r.lotNotOnRecord) {
        Spacer(Modifier.height(8.dp))
        Text("Lot not on record - a new lot of a known antibody", color = Ink.warn, fontSize = 14.sp)
        Mono(r.otherVials.joinToString(", ") { "AbNo ${it.abNo} lot ${it.lot}" })
    }

    Spacer(Modifier.height(8.dp))
    Mono(LabelField.entries.mapNotNull { f -> r.fields[f]?.let { "$f=$it" } }.joinToString("  "))
    Mono(r.reasons.joinToString(", "))
    store.activeSessionBox?.let { Mono("will file to box $it", Ink.info) }

    Spacer(Modifier.height(12.dp))
    if (vial != null) {
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Confirm this vial is here") }
        Spacer(Modifier.height(6.dp))
    }
    Row {
        OutlinedButton(onClick = onRescan, modifier = Modifier.weight(1f)) { Text("Rescan") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = onPark, modifier = Modifier.weight(1f)) { Text("Review later") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = onReceive, modifier = Modifier.weight(1f)) { Text("Receive") }
    }
}

// ---- Actions ---------------------------------------------------------------

private fun meetsBar(v: Verdict, bar: Verdict): Boolean {
    val rank = mapOf(Verdict.CERTAIN to 3, Verdict.CONFIDENT to 2, Verdict.AMBIGUOUS to 1, Verdict.NOT_FOUND to 0)
    return (rank[v] ?: 0) >= (rank[bar] ?: 2)
}

fun record(r: ScanResult, outcome: ScanOutcome): ScanRecord = ScanRecord(
    verdict = r.verdict.name,
    outcome = outcome,
    abNo = r.vial?.abNo,
    definitionName = r.definition?.name,
    catalog = r.fields[LabelField.CATALOG] ?: r.definition?.catalog,
    lot = r.fields[LabelField.LOT],
    vendor = r.definition?.vendor,
    clone = r.definition?.clone,
    barcode = r.barcode?.raw,
    score = r.score,
    margin = r.margin,
    reasons = r.reasons,
    framesUsed = r.framesUsed,
    lotNotOnRecord = r.lotNotOnRecord,
)

/**
 * Confirm: the vial was physically seen. Returns the banner text, which names
 * the box when the session box actually filed it somewhere new - a stamp nobody
 * can see is a stamp nobody can correct.
 */
private fun confirm(store: AppStore, r: ScanResult): String {
    val abNo = r.vial?.abNo ?: return "Nothing to confirm"
    val open = store.openAudit
    val logged = store.log(record(r, ScanOutcome.CONFIRMED).copy(auditID = open?.id))
    store.markConfirmed(abNo, logged.id)
    val filed = store.stampSessionBox(abNo)
    if (open != null) store.recordFound(abNo, open.id, method = "scan", scanID = logged.id)
    var text = "Confirmed AbNo $abNo"
    if (filed != null) text += " · filed to $filed"
    if (open != null) text += " · counted in ${open.title}"
    return text
}

private fun park(store: AppStore, r: ScanResult) {
    store.log(record(r, ScanOutcome.REVIEW))
    // A scan the audit could not name is still evidence that something was in
    // the drawer, so record it as an unexpected item rather than dropping it.
    val open = store.openAudit
    if (open != null && r.vial == null) {
        store.recordExtra(
            AuditExtra(
                label = r.definition?.name ?: (r.fields[LabelField.CATALOG]?.let { "Cat $it" } ?: "Unreadable label"),
                catalog = r.fields[LabelField.CATALOG],
                lot = r.fields[LabelField.LOT],
            ),
            open.id,
        )
    }
}

/** Pre-fill a receive form from what the scan read. */
fun draftFrom(store: AppStore, r: ScanResult): ItemRecord = ItemRecord(
    abNo = store.nextAbNo(),
    definitionName = r.definition?.name ?: "",
    vialName = r.definition?.name ?: "",
    lot = r.fields[LabelField.LOT] ?: "",
    concentration = r.fields[LabelField.CONCENTRATION] ?: "",
    location = store.settings.defaultLocation,
    box = store.activeSessionBox ?: store.settings.defaultBox,
    owner = store.settings.defaultOwner,
)

/** A chip legible over an arbitrary camera image. */
@Composable
private fun OverlayChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, color = Ink.text) },
        colors = AssistChipDefaults.assistChipColors(containerColor = Ink.bg.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, Ink.text.copy(alpha = 0.45f)),
    )
}
