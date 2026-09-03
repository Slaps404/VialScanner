package org.lji.vialscanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lji.vialcore.normKey

/**
 * Set the box being worked through.
 *
 * This exists because the Box column arrives 11% populated and nobody is going
 * to backfill 120 rows by hand. Set it once at the start of a run and every vial
 * confirmed during that run gets filed, with a 12 hour expiry so a box left over
 * from yesterday cannot silently re-file today's work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionBoxSheet(store: AppStore, onDismiss: () -> Unit) {
    var box by remember { mutableStateOf(store.activeSessionBox ?: store.settings.sessionBox) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.card) {
        Column(Modifier.padding(16.dp)) {
            Text("Session box", color = Ink.text, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            SuggestField("Box", box, store.boxes) { box = it }

            store.activeSessionBox?.let { current ->
                Spacer(Modifier.height(12.dp))
                SectionCard("Currently stamping") {
                    KeyValueRow("box", current)
                    KeyValueRow("vials filed here", store.items.count { it.box == current }.toString())
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Stops on its own after 12 hours, so a box from an earlier shift cannot re-file today's vials.",
                color = Ink.dim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { store.setSessionBox(box); Haptics.commit(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start stamping") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { store.setSessionBox(""); Haptics.tap(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop stamping") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Receive a vial that is not on file, pre-filled from what the scan read.
 *
 * `prefillDef` and `sources` come from `buildReceiveDraft`: NEW VIAL is the
 * common outcome of a scan (most clips in the sample set end here), so this
 * sheet opens straight into building the definition too instead of leaving
 * `definitionName` a blank the operator has to notice and go type by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveSheet(
    store: AppStore,
    draft: ItemRecord,
    prefillDef: DefRecord? = null,
    sources: Map<String, FieldSource> = emptyMap(),
    onDismiss: () -> Unit,
    onSaved: (ItemRecord) -> Unit,
) {
    var d by remember { mutableStateOf(draft) }
    var definingAntibody by remember {
        mutableStateOf(prefillDef != null && draft.definitionName.isBlank())
    }
    var newDef by remember { mutableStateOf(prefillDef ?: DefRecord(name = "")) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.card) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Receive a vial", color = Ink.text, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Field("AbNo", d.abNo) { d = d.copy(abNo = it) }
            Field("Vial name", d.vialName) { d = d.copy(vialName = it) }
            Field("Antibody definition", d.definitionName) { d = d.copy(definitionName = it) }
            TextButton(onClick = { newDef = prefillDef ?: DefRecord(name = ""); definingAntibody = true }) {
                Text("This antibody is not on file either", color = Ink.accent)
            }
            Field("Lot", d.lot, source = sources["Lot"]) { d = d.copy(lot = it) }
            Field("Concentration", d.concentration, source = sources["Concentration"]) { d = d.copy(concentration = it) }
            Field("Size", d.extra.value(COL_VOLUME), source = sources[COL_VOLUME]) {
                d = d.copy(extra = d.extra.setting(COL_VOLUME, it))
            }
            Field("Expiration date", d.extra.value(COL_EXPIRY), source = sources[COL_EXPIRY]) {
                d = d.copy(extra = d.extra.setting(COL_EXPIRY, it))
            }
            Field("Storage temp", d.extra.value(COL_STORAGE), source = sources[COL_STORAGE]) {
                d = d.copy(extra = d.extra.setting(COL_STORAGE, it))
            }
            SuggestField("Location", d.location, store.locations, sources["Location"]) { d = d.copy(location = it) }
            SuggestField("Box", d.box, store.boxes, sources["Box"]) { d = d.copy(box = it) }
            SuggestField("Owner", d.owner, store.owners, sources["Owner"]) { d = d.copy(owner = it) }
            Field("Notes", d.notes) { d = d.copy(notes = it) }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSaved(store.addVial(d))
                    // Whatever drawer this just went into is next scan's
                    // default - a run of ten vials should not mean typing the
                    // same box ten times.
                    store.updateSettings { s ->
                        s.copy(
                            defaultOwner = d.owner.ifBlank { s.defaultOwner },
                            defaultLocation = d.location.ifBlank { s.defaultLocation },
                            defaultBox = if (store.activeSessionBox == null) d.box.ifBlank { s.defaultBox } else s.defaultBox,
                        )
                    }
                },
                enabled = d.abNo.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add to inventory") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (definingAntibody) {
        DefinitionSheet(
            def = newDef,
            sources = sources,
            onDismiss = { definingAntibody = false },
            onSaved = { saved ->
                val added = store.addDefinition(saved)
                d = d.copy(definitionName = added.name, vialName = d.vialName.ifBlank { added.name })
                definingAntibody = false
            },
        )
    }
}

/** Build a new antibody definition, pre-filled from the same scan that could
 *  not match one on file. Catalog, clone, vendor and isotype are exactly the
 *  fields a label read enough of; a name still has to be typed or confirmed,
 *  which is the one thing `enabled` below refuses to let through blank. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefinitionSheet(
    def: DefRecord,
    sources: Map<String, FieldSource> = emptyMap(),
    onDismiss: () -> Unit,
    onSaved: (DefRecord) -> Unit,
) {
    var d by remember { mutableStateOf(def) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.card) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("New antibody", color = Ink.text, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Field("Name", d.name) { d = d.copy(name = it) }
            Field("Target", d.target) { d = d.copy(target = it) }
            Field("Clone", d.clone, source = sources["Clone"]) { d = d.copy(clone = it) }
            Field("Host", d.host) { d = d.copy(host = it) }
            Field("Isotype", d.isotype, source = sources["Isotype"]) { d = d.copy(isotype = it) }
            Field("Vendor", d.vendor, source = sources["Vendor"]) { d = d.copy(vendor = it) }
            Field("Catalog #", d.catalog, source = sources["Catalog #"]) { d = d.copy(catalog = it) }
            Text(
                "New definitions are matched by catalog number on the next scan, " +
                    "so get that field right even if the rest is blank.",
                color = Ink.dim, fontSize = Type.caption,
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSaved(d) },
                enabled = d.name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save definition") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * What "Wrong match" opens onto.
 *
 * A rejected reading with no correction attached teaches the matcher nothing -
 * it just says "wrong" without saying what right looks like. So the three
 * real answers are each one tap away: pick the vial, say it's not on file
 * (routes straight into the existing receiving flow), or admit nobody knows.
 * The last one has to be exactly as fast as a plain reject button, because it
 * is the one the operator reaches for constantly - a labelling flow that
 * makes "I don't know" cost more than a tap trains people to guess instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RejectSheet(
    store: AppStore,
    // Other vials of the same antibody the matcher already ranked and
    // rejected itself - shown before search so the common case, right
    // antibody wrong lot, is one tap rather than a retype of something the
    // engine already knew.
    rankedCandidates: List<org.lji.vialcore.InventoryItem>,
    onDismiss: () -> Unit,
    onPickItem: (ItemRecord) -> Unit,
    onPickDefinition: (DefRecord) -> Unit,
    onNotOnFile: () -> Unit,
    onDontKnow: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val items = remember(query, store.items) {
        val q = normKey(query)
        if (q.isEmpty()) emptyList()
        else store.items.filter {
            normKey(it.abNo).contains(q) || normKey(it.definitionName).contains(q) ||
                normKey(it.vialName).contains(q) || normKey(it.lot).contains(q)
        }.sortedBy { it.abNo.toIntOrNull() ?: 0 }.take(20)
    }
    val defs = remember(query, store.defs) {
        val q = normKey(query)
        if (q.isEmpty()) emptyList()
        else store.defs.filter {
            normKey(it.name).contains(q) || normKey(it.catalog).contains(q) ||
                normKey(it.clone).contains(q) || normKey(it.target).contains(q)
        }.take(20)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.card) {
        Column(
            Modifier.padding(16.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("What was it actually?", color = Ink.text, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Not on file", Modifier.weight(1f)) { onDismiss(); onNotOnFile() }
                SecondaryButton("Don't know", Modifier.weight(1f)) { onDismiss(); onDontKnow() }
            }
            Spacer(Modifier.height(10.dp))
            SearchField("AbNo, antibody, catalog #…", query) { query = it }
            Spacer(Modifier.height(10.dp))

            if (rankedCandidates.isNotEmpty()) {
                Text("Other vials of this antibody", color = Ink.textSecond, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                for (v in rankedCandidates) {
                    ListRow(
                        title = "AbNo ${v.abNo} · ${v.vialName.ifEmpty { v.definitionName }}",
                        subtitle = listOfNotNull(
                            v.lot.takeIf { it.isNotEmpty() }?.let { "Lot $it" },
                            v.box.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        onClick = { store.item(v.abNo)?.let(onPickItem); onDismiss() },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
            }

            if (items.isNotEmpty()) {
                Text("Vials on record", color = Ink.textSecond, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                for (item in items) {
                    ListRow(
                        title = "AbNo ${item.abNo} · ${item.displayName}",
                        subtitle = listOfNotNull(
                            item.lot.takeIf { it.isNotEmpty() }?.let { "Lot $it" },
                            item.box.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        onClick = { onPickItem(item); onDismiss() },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
            }

            if (defs.isNotEmpty()) {
                Text("Antibodies (no specific vial)", color = Ink.textSecond, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                for (def in defs) {
                    ListRow(
                        title = def.name,
                        subtitle = listOfNotNull(
                            def.vendor.takeIf { it.isNotEmpty() },
                            def.catalog.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        onClick = { onPickDefinition(def); onDismiss() },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (query.isEmpty() && rankedCandidates.isEmpty()) {
                Text(
                    "Type to search inventory and antibody definitions.",
                    color = Ink.dim, fontSize = 12.sp,
                )
            } else if (query.isNotEmpty() && items.isEmpty() && defs.isEmpty()) {
                Text("No matches.", color = Ink.dim, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Edit a vial already on file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VialSheet(store: AppStore, item: ItemRecord, onDismiss: () -> Unit) {
    var d by remember { mutableStateOf(item) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.card) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(d.displayName, color = Ink.text, fontSize = 20.sp)
            d.lastConfirmedAt?.let {
                Text("Last seen ${AppStore.stamp(it)}", color = Ink.dim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Field("Vial name", d.vialName) { d = d.copy(vialName = it) }
            Field("Lot", d.lot) { d = d.copy(lot = it) }
            Field("Concentration", d.concentration) { d = d.copy(concentration = it) }
            SuggestField("Location", d.location, store.locations) { d = d.copy(location = it) }
            SuggestField("Box", d.box, store.boxes) { d = d.copy(box = it) }
            SuggestField("Owner", d.owner, store.owners) { d = d.copy(owner = it) }
            Field("Status", d.status) { d = d.copy(status = it) }
            Field("Notes", d.notes) { d = d.copy(notes = it) }

            // Everything else the import carried, read-only: the app is not the
            // system of record for these, and showing them is what makes a scan
            // useful at the bench.
            val others = d.extra.filter { it.value.isNotBlank() }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SectionCard("From the import") {
                    for (e in others) KeyValueRow(e.name, e.value)
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { store.updateVial(d); Haptics.commit(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    store.markConfirmed(d.abNo, null)
                    Haptics.commit()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mark as seen now") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
