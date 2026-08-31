package org.lji.vialscanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.lji.vialcore.Verdict

// The screens either side of the scanner. Each one answers a question somebody
// standing at a freezer actually asks: what do we have, what did the scanner
// give up on, what happened today, is this box complete, and how do I get the
// data out.
//
// All of them wear the same `Screen` chrome as iOS - hamburger, title, at most
// one trailing control - because the drawer is the only navigation and every
// screen has to offer the way back to it.

// ---- Inventory -------------------------------------------------------------

/** The filters across the top of the iOS inventory screen, in the same order. */
private enum class VialFilter(val label: String) {
    ALL("All"),
    NEVER("Never confirmed"),
    TODAY("Confirmed today"),
    NEW("Added on this phone"),
}

@Composable
fun InventoryScreen(store: AppStore, openMenu: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VialFilter.ALL) }
    var editing by remember { mutableStateOf<ItemRecord?>(null) }
    var receiving by remember { mutableStateOf<ItemRecord?>(null) }

    val shown = remember(query, filter, store.items) {
        val q = query.trim().lowercase()
        val today = AppStore.stamp(System.currentTimeMillis()).take(10)
        store.items
            .filter { i ->
                q.isEmpty() || listOf(
                    i.abNo, i.vialName, i.definitionName, i.lot, i.box, i.location, i.owner,
                ).any { it.lowercase().contains(q) }
            }
            .filter { i ->
                when (filter) {
                    VialFilter.ALL -> true
                    VialFilter.NEVER -> i.lastConfirmedAt == null
                    VialFilter.TODAY -> i.lastConfirmedAt?.let { AppStore.stamp(it).take(10) == today } == true
                    VialFilter.NEW -> i.isLocal
                }
            }
            .sortedBy { it.abNo.toIntOrNull() ?: Int.MAX_VALUE }
    }

    Screen("Inventory", openMenu, trailing = {
        CircleIcon(Icons.Filled.Add) { receiving = ItemRecord(abNo = store.nextAbNo()) }
    }) {
        SearchField("AbNo, antibody, lot, box...", query, Modifier.padding(horizontal = Metric.pad)) { query = it }
        Spacer(Modifier.height(12.dp))
        PillRow(
            VialFilter.entries, filter, { it.label },
            Modifier.padding(horizontal = Metric.pad),
        ) { filter = it }
        Spacer(Modifier.height(4.dp))
        if (shown.isEmpty()) EmptyState("No vials match.")
        else SimpleList(shown, key = { it.id }) { i ->
            ListRow(
                leading = i.abNo,
                title = i.displayName,
                subtitle = listOfNotNull(
                    i.lot.takeIf { it.isNotEmpty() }?.let { "Lot $it" },
                    i.box.takeIf { it.isNotEmpty() }?.let { "Box $it" },
                    i.location.takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                trailing = i.lastConfirmedAt?.let { AppStore.stamp(it).take(10) } ?: "",
                onClick = { editing = i },
            )
        }
    }

    editing?.let { VialSheet(store, it) { editing = null } }
    receiving?.let { draft ->
        ReceiveSheet(store, draft, onDismiss = { receiving = null }, onSaved = { receiving = null })
    }
}

// ---- Review queue ----------------------------------------------------------

@Composable
fun ReviewScreen(store: AppStore, openMenu: () -> Unit) {
    val queue = store.reviewQueue
    var open by remember { mutableStateOf<ScanRecord?>(null) }

    Screen("Review queue", openMenu) {
        if (queue.isEmpty()) {
            EmptyState("Nothing waiting. Scans the engine was unsure about land here.")
        } else {
            SimpleList(queue, key = { it.id }) { s ->
                ListRow(
                    title = s.title,
                    subtitle = "${s.subtitle} · ${AppStore.stamp(s.date)}",
                    trailing = s.verdict,
                    tint = Ink.warn,
                    onClick = { open = s },
                )
            }
        }
    }

    open?.let { s ->
        AlertDialog(
            onDismissRequest = { open = null },
            containerColor = Ink.surface,
            titleContentColor = Ink.text,
            textContentColor = Ink.text,
            title = { Text(s.title, fontSize = Type.title, fontWeight = Type.titleWeight) },
            text = {
                Column {
                    KeyValueRow("verdict", s.verdict)
                    KeyValueRow("catalog", s.catalog ?: "")
                    KeyValueRow("lot", s.lot ?: "")
                    KeyValueRow("barcode", s.barcode ?: "")
                    KeyValueRow("score", "%.1f (margin %.1f)".format(s.score, s.margin))
                    Mono(s.reasons.joinToString(", "))
                    if (s.lotNotOnRecord) {
                        Text("Lot not on record", color = Ink.warn, fontSize = Type.label)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Resolving a review is a judgement, so it is recorded as
                    // one: the vial is marked seen only if the scan named one.
                    s.abNo?.let { store.markConfirmed(it, s.id) }
                    store.resolveReview(s, ScanOutcome.CONFIRMED)
                    Haptics.commit()
                    open = null
                }) { Text("Confirm", color = Ink.text, fontWeight = Type.labelWeight) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { store.resolveReview(s, ScanOutcome.REJECTED); open = null }) {
                        Text("Reject", color = Ink.textSecond)
                    }
                    TextButton(onClick = { store.delete(s); open = null }) {
                        Text("Delete", color = Ink.bad)
                    }
                }
            },
        )
    }
}

// ---- History ---------------------------------------------------------------

@Composable
fun HistoryScreen(store: AppStore, openMenu: () -> Unit) {
    var filter by remember { mutableStateOf<ScanOutcome?>(null) }
    val shown = store.scans.filter { filter == null || it.outcome == filter }

    Screen("Scan history", openMenu) {
        Row(
            Modifier
                .padding(horizontal = Metric.pad)
                .horizontalScroll(rememberScrollState()),
        ) {
            Pill("All", filter == null) { filter = null; Haptics.tap() }
            for (o in ScanOutcome.entries) {
                Spacer(Modifier.width(8.dp))
                Pill(o.label, filter == o) { filter = if (filter == o) null else o; Haptics.tap() }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (shown.isEmpty()) EmptyState("No scans yet.")
        else SimpleList(shown, key = { it.id }) { s ->
            ListRow(
                title = s.title,
                subtitle = "${s.subtitle} · ${AppStore.stamp(s.date)}",
                trailing = s.outcome.label,
                tint = when (s.outcome) {
                    ScanOutcome.CONFIRMED, ScanOutcome.RECEIVED -> Ink.good
                    ScanOutcome.REVIEW -> Ink.warn
                    ScanOutcome.REJECTED -> Ink.bad
                    ScanOutcome.LOGGED -> Ink.textSecond
                },
            )
        }
    }
}

// ---- Audit -----------------------------------------------------------------

@Composable
fun AuditScreen(store: AppStore, openMenu: () -> Unit) {
    val open = store.openAudit

    Screen("Confirm inventory", openMenu) {
        if (open == null) {
            NewAuditPanel(store)
            return@Screen
        }

        SectionCard(open.title) {
            LinearProgressIndicator(
                progress = { open.progress.toFloat() },
                color = Ink.accent,
                trackColor = Ink.fill,
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Mono("${open.foundSet.size} found · ${open.missing.size} missing · ${open.extras.size} unexpected")
            Spacer(Modifier.height(14.dp))
            Row {
                PrimaryButton("Finish", Modifier.weight(1f)) { store.finishAudit(open.id); Haptics.commit() }
                Spacer(Modifier.width(10.dp))
                SecondaryButton("Export CSV", Modifier.weight(1f)) {
                    store.exportAuditCSV(open)?.let { store.banner = AppStore.Banner("Wrote ${it.name}") }
                }
            }
        }

        // Missing first: the whole point of an audit is the gap, not the tally.
        SimpleList(open.missing + open.foundSet.toList(), key = { it }) { abNo ->
            val found = abNo in open.foundSet
            val rec = store.item(abNo)
            ListRow(
                leading = abNo,
                title = rec?.displayName ?: "AbNo $abNo",
                subtitle = rec?.lot?.takeIf { it.isNotEmpty() }?.let { "Lot $it" } ?: "",
                trailing = if (found) "found" else "MISSING",
                tint = if (found) Ink.good else Ink.bad,
                onClick = {
                    if (found) store.unmarkFound(abNo, open.id)
                    else store.recordFound(abNo, open.id, method = "manual", scanID = null)
                    Haptics.tap()
                },
            )
        }
    }
}

@Composable
private fun NewAuditPanel(store: AppStore) {
    var scope by remember { mutableStateOf(AuditScope.BOX) }
    var value by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("Start an inventory check") {
            PillRow(AuditScope.entries, scope, { it.label }) { scope = it; value = "" }
            Spacer(Modifier.height(10.dp))
            if (scope != AuditScope.EVERYTHING) {
                val options = when (scope) {
                    AuditScope.BOX -> store.boxes
                    AuditScope.LOCATION -> store.locations
                    else -> store.owners
                }
                SuggestField(scope.label, value, options) { value = it }
            }
            val expected = store.expectedAbNos(scope, value)
            Spacer(Modifier.height(6.dp))
            Mono("${expected.size} vials expected")
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Start", enabled = expected.isNotEmpty()) {
                store.startAudit(scope, value, "${scope.label} $value".trim())
                Haptics.commit()
                Speaker.say("Check started. ${expected.size} vials expected.")
            }
        }

        val past = store.audits.filter { !it.isOpen }
        if (past.isNotEmpty()) {
            SectionCard("Finished") {
                for (a in past.take(10)) {
                    KeyValueRow(a.title, "${a.foundSet.size}/${a.expected.size} · ${AppStore.stamp(a.startedAt)}")
                }
            }
        }
    }
}

// ---- Data ------------------------------------------------------------------

@Composable
fun DataScreen(
    store: AppStore,
    openMenu: () -> Unit,
    onPickCSVs: () -> Unit,
    onPickBackup: () -> Unit,
    onShare: (java.io.File) -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Ink.card,
            title = { Text("Replace everything on this phone?", color = Ink.text) },
            text = {
                Text(
                    "This deletes all ${store.defs.size} definitions, ${store.items.size} vials and " +
                        "${store.scans.size} logged scans, and puts back the snapshot the app shipped " +
                        "with. Export first if anything here is not already in Airtable. It cannot be undone.",
                    color = Ink.dim,
                )
            },
            confirmButton = {
                TextButton(onClick = { store.resetToBundled(); confirmReset = false }) {
                    Text("Reset", color = Ink.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = Ink.text) }
            },
        )
    }

    Screen("Import & export", openMenu) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard("Catalog") {
                KeyValueRow("definitions", store.defs.size.toString())
                KeyValueRow("vials", store.items.size.toString())
                KeyValueRow("scans logged", store.scans.size.toString())
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Import definitions + inventory CSV", onClick = onPickCSVs)
                Spacer(Modifier.height(8.dp))
                // Behind a confirmation because it is one tap from wiping every
                // definition, vial and scan the phone holds, and it sits directly
                // under the import button people actually came here to press.
                // iOS has always had this alert; Android shipped without it.
                SecondaryButton("Reset to bundled snapshot", Modifier.fillMaxWidth()) {
                    confirmReset = true
                }
            }

            SectionCard("Export") {
                Text(
                    "Writes into the app's export folder and hands it to whatever you share it with. " +
                        "Round-tripping to Airtable means exporting here and importing there: nothing syncs on its own.",
                    color = Ink.textSecond, fontSize = Type.label, lineHeight = Type.body,
                )
                Spacer(Modifier.height(14.dp))
                ExportRow("Inventory CSV") { store.exportInventoryCSV()?.let(onShare) }
                ExportRow("Definitions CSV") { store.exportDefinitionsCSV()?.let(onShare) }
                ExportRow("Scan log CSV") { store.exportScanLogCSV()?.let(onShare) }
                ExportRow("Full backup (JSON)") { store.exportBackupJSON()?.let(onShare) }
            }

            SectionCard("Restore") {
                SecondaryButton("Restore from a backup file", Modifier.fillMaxWidth(), onClick = onPickBackup)
            }
            Spacer(Modifier.height(Metric.pad))
        }
    }
}

@Composable
private fun ExportRow(label: String, onClick: () -> Unit) {
    // iOS builds the file on one tap (Haptics.tap()) and shares it on a second;
    // this collapses both into one tap, so the one buzz iOS gives the first tap
    // is the whole feedback there is.
    SecondaryButton(label, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { onClick(); Haptics.tap() }
}

// ---- Settings --------------------------------------------------------------

@Composable
fun SettingsScreen(store: AppStore, openMenu: () -> Unit) {
    val s = store.settings

    Screen("Settings", openMenu) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard("Hands-free") {
                ToggleRow(
                    "Hands-free mode",
                    "Scans start on their own and confident reads commit without a tap.",
                    s.handsFree,
                ) { store.updateSettings { c -> c.copy(handsFree = it) } }

                ToggleRow(
                    "Speak results",
                    "Says the AbNo and box out loud, digit by digit.",
                    s.speak,
                ) { store.updateSettings { c -> c.copy(speak = it) } }

                SliderRow(
                    "Pause before committing",
                    "How long a result stays on screen so you can catch a wrong one.",
                    s.handsFreeDelay, 0.5..10.0, { "%.1fs".format(it) },
                ) { store.updateSettings { c -> c.copy(handsFreeDelay = it) } }

                SettingRow(
                    "Commit automatically when",
                    "A barcode read is effectively certain. Label-text matches clear a score and " +
                        "a margin, which is a lower bar.",
                )
                PillRow(
                    listOf(Verdict.CERTAIN, Verdict.CONFIDENT), s.autoCommit,
                    { if (it == Verdict.CERTAIN) "Barcode only" else "Barcode or text" },
                ) { store.updateSettings { c -> c.copy(autoCommitVerdict = it.name) } }
                Spacer(Modifier.height(6.dp))

                ToggleRow(
                    "Park anything below that bar",
                    "Uncertain reads go to the review queue instead of stopping the run.",
                    s.handsFreeParksUncertain,
                ) { store.updateSettings { c -> c.copy(handsFreeParksUncertain = it) } }
            }

            SectionCard("Receiving defaults") {
                SuggestField("Owner", s.defaultOwner, store.owners) {
                    store.updateSettings { c -> c.copy(defaultOwner = it) }
                }
                SuggestField("Location", s.defaultLocation, store.locations) {
                    store.updateSettings { c -> c.copy(defaultLocation = it) }
                }
                SuggestField("Box", s.defaultBox, store.boxes) {
                    store.updateSettings { c -> c.copy(defaultBox = it) }
                }
            }

            SectionCard("Engine") {
                Text(
                    "These decide when an answer is allowed to count, not how accurate it is. " +
                        "Raise the floor if the scanner answers before the vial has been turned.",
                    color = Ink.textSecond, fontSize = Type.label, lineHeight = Type.body,
                )
                Spacer(Modifier.height(6.dp))
                SliderRow(
                    "Minimum scan seconds", "The scanner will not answer sooner than this.",
                    s.minScanSeconds, 0.0..5.0, { "%.1fs".format(it) },
                ) { store.updateSettings { c -> c.copy(minScanSeconds = it) } }
                SliderRow(
                    "Give up after", "Answers with whatever it has once this runs out.",
                    s.maxScanSeconds, 2.0..15.0, { "%.0fs".format(it) },
                ) { store.updateSettings { c -> c.copy(maxScanSeconds = it) } }
                SliderRow(
                    "Label faces required",
                    "Distinct sides of the label, not frames. Turning the vial is what earns these.",
                    s.minDistinctViews.toDouble(), 1.0..4.0, { "%.0f".format(it) },
                ) { store.updateSettings { c -> c.copy(minDistinctViews = it.toInt()) } }
                SliderRow(
                    "OCR frame budget", "How many frames may be sent to text recognition per scan.",
                    s.maxOCRFrames.toDouble(), 6.0..60.0, { "%.0f".format(it) },
                ) { store.updateSettings { c -> c.copy(maxOCRFrames = it.toInt()) } }
                SliderRow(
                    "Aiming inset", "How far in from the edges the scanner is allowed to look.",
                    s.roiInset, 0.0..0.4, { "%.2f".format(it) },
                ) { store.updateSettings { c -> c.copy(roiInset = it) } }
            }

            SectionCard("Camera") {
                ToggleRow(
                    "Torch on by default", "Freezer drawers are dark and frosted labels are worse.",
                    s.torchOnByDefault,
                ) { store.updateSettings { c -> c.copy(torchOnByDefault = it) } }
                ToggleRow(
                    "Auto text focus tracking",
                    "Steers the lens at the label text instead of the middle of the frame.",
                    s.autoTextFocus,
                ) { store.updateSettings { c -> c.copy(autoTextFocus = it) } }
                ToggleRow(
                    "Prefer 0.5x ultra-wide",
                    "The wide lens focuses about 2 cm away against roughly 10 for the main one.",
                    s.preferUltraWideZoom,
                ) { store.updateSettings { c -> c.copy(preferUltraWideZoom = it) } }
                ToggleRow(
                    "Haptics",
                    "A different buzz for identified, unsure, and new.",
                    s.haptics,
                ) { store.updateSettings { c -> c.copy(haptics = it) } }
            }
            Spacer(Modifier.height(Metric.pad))
        }
    }
}
