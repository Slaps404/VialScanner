package org.lji.vialscanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lji.vialcore.Verdict

// The screens either side of the scanner. Each one answers a question somebody
// standing at a freezer actually asks: what do we have, what did the scanner
// give up on, what happened today, is this box complete, and how do I get the
// data out.

// ---- Inventory -------------------------------------------------------------

@Composable
fun InventoryScreen(store: AppStore) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ItemRecord?>(null) }

    val shown = remember(query, store.items) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) store.items.sortedBy { it.abNo.toIntOrNull() ?: 0 }
        else store.items.filter {
            listOf(it.abNo, it.vialName, it.definitionName, it.lot, it.box, it.location, it.owner)
                .any { f -> f.lowercase().contains(q) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Field("Search vials", query, Modifier.padding(horizontal = 12.dp)) { query = it }
        Text("${shown.size} of ${store.items.size}", color = Ink.dim, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SimpleList(shown, key = { it.id }) { i ->
            ListRow(
                title = i.displayName,
                subtitle = listOf("AbNo ${i.abNo}", i.lot.takeIf { it.isNotEmpty() }?.let { "lot $it" },
                    i.box.takeIf { it.isNotEmpty() }?.let { "box $it" }, i.location.takeIf { it.isNotEmpty() })
                    .filterNotNull().joinToString(" · "),
                trailing = i.lastConfirmedAt?.let { "seen ${AppStore.stamp(it).take(10)}" } ?: "",
                onClick = { editing = i },
            )
        }
    }

    editing?.let { VialSheet(store, it) { editing = null } }
}

// ---- Review queue ----------------------------------------------------------

@Composable
fun ReviewScreen(store: AppStore) {
    val queue = store.reviewQueue
    if (queue.isEmpty()) {
        EmptyState("Nothing waiting. Scans the engine was unsure about land here.")
        return
    }
    var open by remember { mutableStateOf<ScanRecord?>(null) }
    SimpleList(queue, key = { it.id }) { s ->
        ListRow(
            title = s.title,
            subtitle = "${s.subtitle} · ${AppStore.stamp(s.date)}",
            trailing = s.verdict,
            onClick = { open = s },
        )
    }

    open?.let { s ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(s.title) },
            text = {
                Column {
                    KeyValueRow("verdict", s.verdict)
                    KeyValueRow("catalog", s.catalog ?: "")
                    KeyValueRow("lot", s.lot ?: "")
                    KeyValueRow("barcode", s.barcode ?: "")
                    KeyValueRow("score", "%.1f (margin %.1f)".format(s.score, s.margin))
                    Mono(s.reasons.joinToString(", "))
                    if (s.lotNotOnRecord) Text("Lot not on record", color = Ink.warn, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Resolving a review is a judgement, so it is recorded as
                    // one: the vial is marked seen only if the scan named one.
                    s.abNo?.let { store.markConfirmed(it, s.id) }
                    store.resolveReview(s, ScanOutcome.CONFIRMED)
                    open = null
                }) { Text("Confirm") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { store.resolveReview(s, ScanOutcome.REJECTED); open = null }) {
                        Text("Reject")
                    }
                    TextButton(onClick = { store.delete(s); open = null }) { Text("Delete") }
                }
            },
        )
    }
}

// ---- History ---------------------------------------------------------------

@Composable
fun HistoryScreen(store: AppStore) {
    var filter by remember { mutableStateOf<ScanOutcome?>(null) }
    val shown = store.scans.filter { filter == null || it.outcome == filter }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            for (o in ScanOutcome.entries) {
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = filter == o,
                    onClick = { filter = if (filter == o) null else o },
                    label = { Text(o.label) },
                )
            }
        }
        if (shown.isEmpty()) EmptyState("No scans yet.")
        else SimpleList(shown, key = { it.id }) { s ->
            ListRow(
                title = s.title,
                subtitle = "${s.subtitle} · ${s.outcome.label} · ${AppStore.stamp(s.date)}",
                trailing = s.verdict,
            )
        }
    }
}

// ---- Audit -----------------------------------------------------------------

@Composable
fun AuditScreen(store: AppStore) {
    val open = store.openAudit
    if (open == null) {
        NewAuditPanel(store)
        return
    }

    Column(Modifier.fillMaxSize()) {
        SectionCard(open.title) {
            LinearProgressIndicator(progress = { open.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Mono("${open.foundSet.size} found · ${open.missing.size} missing · ${open.extras.size} unexpected")
            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = { store.finishAudit(open.id) }, modifier = Modifier.weight(1f)) {
                    Text("Finish")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { store.exportAuditCSV(open)?.let { store.banner = AppStore.Banner("Wrote ${it.name}") } },
                    modifier = Modifier.weight(1f),
                ) { Text("Export CSV") }
            }
        }

        // Missing first: the whole point of an audit is the gap, not the tally.
        SimpleList(open.missing + open.foundSet.toList(), key = { it }) { abNo ->
            val found = abNo in open.foundSet
            val it0 = store.item(abNo)
            ListRow(
                title = it0?.displayName ?: "AbNo $abNo",
                subtitle = listOfNotNull("AbNo $abNo", it0?.lot?.takeIf { l -> l.isNotEmpty() }?.let { l -> "lot $l" })
                    .joinToString(" · "),
                trailing = if (found) "found" else "MISSING",
                onClick = {
                    if (found) store.unmarkFound(abNo, open.id)
                    else store.recordFound(abNo, open.id, method = "manual", scanID = null)
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
            Row {
                for (s in AuditScope.entries) {
                    FilterChip(
                        selected = scope == s,
                        onClick = { scope = s; value = "" },
                        label = { Text(s.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (scope != AuditScope.EVERYTHING) {
                val options = when (scope) {
                    AuditScope.BOX -> store.boxes
                    AuditScope.LOCATION -> store.locations
                    else -> store.owners
                }
                SuggestField(scope.label, value, options) { value = it }
            }
            val expected = store.expectedAbNos(scope, value)
            Spacer(Modifier.height(4.dp))
            Mono("${expected.size} vials expected")
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { store.startAudit(scope, value, "${scope.label} $value".trim()) },
                enabled = expected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start") }
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
fun DataScreen(store: AppStore, onPickCSVs: () -> Unit, onPickBackup: () -> Unit, onShare: (java.io.File) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("Catalog") {
            KeyValueRow("definitions", store.defs.size.toString())
            KeyValueRow("vials", store.items.size.toString())
            KeyValueRow("scans logged", store.scans.size.toString())
            Spacer(Modifier.height(10.dp))
            Button(onClick = onPickCSVs, modifier = Modifier.fillMaxWidth()) {
                Text("Import definitions + inventory CSV")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { store.resetToBundled() }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to bundled snapshot")
            }
        }

        SectionCard("Export") {
            Text(
                "Writes into the app's export folder and hands it to whatever you share it with. " +
                    "Round-tripping to Airtable means exporting here and importing there: nothing syncs on its own.",
                color = Ink.dim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            ExportRow("Inventory CSV") { store.exportInventoryCSV()?.let(onShare) }
            ExportRow("Definitions CSV") { store.exportDefinitionsCSV()?.let(onShare) }
            ExportRow("Scan log CSV") { store.exportScanLogCSV()?.let(onShare) }
            ExportRow("Full backup (JSON)") { store.exportBackupJSON()?.let(onShare) }
        }

        SectionCard("Restore") {
            OutlinedButton(onClick = onPickBackup, modifier = Modifier.fillMaxWidth()) {
                Text("Restore from a backup file")
            }
        }
    }
}

@Composable
private fun ExportRow(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label)
    }
}

// ---- Settings --------------------------------------------------------------

@Composable
fun SettingsScreen(store: AppStore) {
    val s = store.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("Hands-free") {
            SwitchRow("Commit without a tap", s.handsFree) { store.updateSettings { c -> c.copy(handsFree = it) } }
            SwitchRow("Park anything below the bar", s.handsFreeParksUncertain) {
                store.updateSettings { c -> c.copy(handsFreeParksUncertain = it) }
            }
            NumberRow("Seconds before committing", s.handsFreeDelay, 0.5, 10.0) {
                store.updateSettings { c -> c.copy(handsFreeDelay = it) }
            }
            Text("Auto-commit at or above", color = Ink.dim, fontSize = 12.sp)
            Row {
                for (v in listOf(Verdict.CERTAIN, Verdict.CONFIDENT)) {
                    FilterChip(
                        selected = s.autoCommit == v,
                        onClick = { store.updateSettings { c -> c.copy(autoCommitVerdict = v.name) } },
                        label = { Text(if (v == Verdict.CERTAIN) "Barcode only" else "Confident") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
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
                color = Ink.dim, fontSize = 12.sp,
            )
            NumberRow("Minimum scan seconds", s.minScanSeconds, 0.0, 5.0) {
                store.updateSettings { c -> c.copy(minScanSeconds = it) }
            }
            NumberRow("Give up after (seconds)", s.maxScanSeconds, 2.0, 15.0) {
                store.updateSettings { c -> c.copy(maxScanSeconds = it) }
            }
            NumberRow("Label faces required", s.minDistinctViews.toDouble(), 1.0, 4.0) {
                store.updateSettings { c -> c.copy(minDistinctViews = it.toInt()) }
            }
            NumberRow("OCR frame budget", s.maxOCRFrames.toDouble(), 6.0, 60.0) {
                store.updateSettings { c -> c.copy(maxOCRFrames = it.toInt()) }
            }
            NumberRow("Aiming inset", s.roiInset, 0.0, 0.4) {
                store.updateSettings { c -> c.copy(roiInset = it) }
            }
        }

        SectionCard("Camera") {
            SwitchRow("Torch on by default", s.torchOnByDefault) {
                store.updateSettings { c -> c.copy(torchOnByDefault = it) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Ink.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberRow(label: String, value: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("$label: ${"%.2f".format(value).trimEnd('0').trimEnd('.')}", color = Ink.text, fontSize = 14.sp)
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}
