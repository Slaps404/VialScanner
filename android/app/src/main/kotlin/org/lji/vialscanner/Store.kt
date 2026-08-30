package org.lji.vialscanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lji.vialcore.Catalog
import org.lji.vialcore.CatalogException
import org.lji.vialcore.normKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Everything the app owns that outlives a scan.
 *
 * One store, one JSON file, written after every mutation. The lab's data set is
 * 161 definitions and ~140 vials, small enough that a document model beats a
 * database, and a document is also the thing you can hand to someone as an
 * export when the network in the freezer room is unusable.
 *
 * State is held in Compose `mutableStateOf` over *immutable* lists, and every
 * mutation replaces the list. Mutating a record in place would persist fine and
 * silently fail to redraw, which is the kind of bug that looks like the scan
 * failing rather than the screen being stale.
 */
class AppStore(private val context: Context) {

    var defs by mutableStateOf<List<DefRecord>>(emptyList())
        private set
    var items by mutableStateOf<List<ItemRecord>>(emptyList())
        private set
    var scans by mutableStateOf<List<ScanRecord>>(emptyList())
        private set
    var audits by mutableStateOf<List<AuditSession>>(emptyList())
        private set
    var catalog by mutableStateOf(Catalog(emptyList(), emptyList()))
        private set
    var settings by mutableStateOf(AppSettings())

    /** Surfaced to the user rather than logged: an import that silently did
     *  nothing is worse than one that says why. */
    var banner by mutableStateOf<Banner?>(null)

    data class Banner(val text: String, val isError: Boolean = false, val id: String = UUID.randomUUID().toString())

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val defs: List<DefRecord> = emptyList(),
        val items: List<ItemRecord> = emptyList(),
        val scans: List<ScanRecord> = emptyList(),
        val audits: List<AuditSession> = emptyList(),
        val settings: AppSettings = AppSettings(),
    )

    private val json = Json {
        prettyPrint = true
        // A store written by an older build must still open after a field is
        // added or removed, or an app update costs somebody their receiving work.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storeFile: File get() = File(context.filesDir, "store.json")

    init { load() }

    // ---- Load / save --------------------------------------------------------

    private fun load() {
        val f = storeFile
        if (f.exists()) {
            val snap = runCatching { json.decodeFromString<Snapshot>(f.readText()) }.getOrNull()
            if (snap != null) {
                defs = snap.defs; items = snap.items; scans = snap.scans
                audits = snap.audits; settings = snap.settings
                rebuild()
                return
            }
            banner = Banner("The saved store could not be read; starting from the bundled catalog.", isError = true)
        }
        seedFromAssets()
    }

    /** First launch: the CSV snapshot shipped in the APK. The app is fully
     *  usable offline before anyone imports anything. */
    fun seedFromAssets() {
        val loaded = runCatching {
            context.assets.open("definitions.csv").bufferedReader().use { d ->
                context.assets.open("inventory.csv").bufferedReader().use { i ->
                    Catalog.load(d.readText(), i.readText())
                }
            }
        }.getOrNull()
        if (loaded == null) {
            banner = Banner("Bundled catalog is missing. Import CSVs to get started.", isError = true)
            rebuild()
            return
        }
        defs = loaded.definitions.map { DefRecord.from(it) }
        items = loaded.items.map { ItemRecord.from(it) }
        rebuild()
        save()
    }

    private fun rebuild() {
        catalog = Catalog(defs.map { it.core }, items.map { it.core })
    }

    fun save() {
        val snap = Snapshot(defs = defs, items = items, scans = scans, audits = audits, settings = settings)
        val text = runCatching { json.encodeToString(snap) }.getOrNull() ?: return
        // Write beside the target and rename: a phone that dies mid-write must
        // not lose the inventory to a half-written file.
        val tmp = File(context.filesDir, "store.json.tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(storeFile)) { storeFile.writeText(text); tmp.delete() }
        }
    }

    private fun commit() { rebuild(); save() }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        settings = block(settings)
        save()
    }

    // ---- Derived collections ------------------------------------------------

    val boxes: List<String> get() = unique(items.map { it.box })
    val locations: List<String> get() = unique(items.map { it.location })
    val owners: List<String> get() = unique(items.map { it.owner })

    private fun unique(xs: List<String>): List<String> =
        xs.filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun item(abNo: String): ItemRecord? = items.firstOrNull { it.abNo.equals(abNo, ignoreCase = true) }

    fun definition(named: String): DefRecord? = defs.firstOrNull { normKey(it.name) == normKey(named) }

    val reviewQueue: List<ScanRecord>
        get() = scans.filter { it.outcome == ScanOutcome.REVIEW && it.resolvedAt == null }
            .sortedByDescending { it.date }

    val openAudit: AuditSession? get() = audits.firstOrNull { it.isOpen }

    // ---- Session box --------------------------------------------------------

    /** A box set more than this long ago is treated as stale and stops stamping.
     *  The failure it prevents is quiet and expensive: yesterday's box silently
     *  re-filing today's vials, discovered only at the next audit. */
    val activeSessionBox: String?
        get() {
            val box = settings.sessionBox.trim()
            if (box.isEmpty()) return null
            val at = settings.sessionBoxSetAt ?: return null
            if (System.currentTimeMillis() - at >= SESSION_BOX_TTL_MS) return null
            return box
        }

    fun setSessionBox(box: String) {
        val trimmed = box.trim()
        updateSettings {
            it.copy(
                sessionBox = trimmed,
                sessionBoxSetAt = if (trimmed.isEmpty()) null else System.currentTimeMillis(),
            )
        }
    }

    /** Stamp the active session box onto a vial that was just physically seen.
     *  Returns the box only when it actually changed something, so the caller can
     *  say so in the banner instead of claiming a filing that already held. */
    fun stampSessionBox(abNo: String): String? {
        val box = activeSessionBox ?: return null
        val existing = items.firstOrNull { it.abNo == abNo } ?: return null
        if (existing.box == box) return null
        items = items.map { if (it.abNo == abNo) it.copy(box = box) else it }
        commit()
        return box
    }

    // ---- Inventory mutations ------------------------------------------------

    /** The next free AbNo. AbNo is a plain integer here, so the next one is
     *  max + 1; anything non-numeric is ignored rather than guessed at. */
    fun nextAbNo(): String {
        val maxN = items.mapNotNull { it.abNo.filter { c -> c.isDigit() }.toIntOrNull() }.maxOrNull() ?: 0
        return (maxN + 1).toString()
    }

    fun addVial(item: ItemRecord): ItemRecord {
        val v = item.copy(isLocal = true, receivedAt = item.receivedAt ?: System.currentTimeMillis())
        items = items + v
        // Keep the definition's link field in step, since `Catalog.inventoryFor`
        // trusts the link before the name join.
        defs = defs.map { d ->
            if (normKey(d.name) == normKey(v.definitionName) && v.abNo !in d.inventoryLinks) {
                d.copy(inventoryLinks = (d.inventoryLinks + v.abNo).toMutableList())
            } else d
        }
        commit()
        return v
    }

    fun updateVial(item: ItemRecord) {
        items = items.map { if (it.id == item.id) item else it }
        commit()
    }

    fun deleteVial(item: ItemRecord) {
        items = items.filter { it.id != item.id }
        defs = defs.map { d ->
            if (item.abNo in d.inventoryLinks)
                d.copy(inventoryLinks = d.inventoryLinks.filter { it != item.abNo }.toMutableList())
            else d
        }
        commit()
    }

    fun addDefinition(def: DefRecord): DefRecord {
        val d = def.copy(isLocal = true)
        defs = defs + d
        commit()
        return d
    }

    /** Mark a vial as physically seen. This is what "confirm" means: not that
     *  the record changed, but that someone laid eyes on the tube today. */
    fun markConfirmed(abNo: String, scanID: String?) {
        items = items.map {
            if (it.abNo == abNo) it.copy(lastConfirmedAt = System.currentTimeMillis(), lastScanID = scanID) else it
        }
        commit()
    }

    // ---- Scan log -----------------------------------------------------------

    fun log(record: ScanRecord): ScanRecord {
        scans = listOf(record) + scans
        save()
        return record
    }

    fun update(record: ScanRecord) {
        scans = scans.map { if (it.id == record.id) record else it }
        save()
    }

    fun resolveReview(record: ScanRecord, outcome: ScanOutcome, note: String? = null) {
        scans = scans.map {
            if (it.id == record.id)
                it.copy(outcome = outcome, resolvedAt = System.currentTimeMillis(), note = note ?: it.note)
            else it
        }
        save()
    }

    fun delete(record: ScanRecord) {
        scans = scans.filter { it.id != record.id }
        save()
    }

    // ---- Audits -------------------------------------------------------------

    fun startAudit(scope: AuditScope, value: String, name: String): AuditSession {
        val session = AuditSession(
            name = name, scope = scope, scopeValue = value,
            expected = expectedAbNos(scope, value),
        )
        audits = listOf(session) + audits
        save()
        return session
    }

    fun expectedAbNos(scope: AuditScope, value: String): List<String> {
        val matching = when (scope) {
            AuditScope.BOX -> items.filter { it.box == value }
            AuditScope.LOCATION -> items.filter { it.location == value }
            AuditScope.OWNER -> items.filter { it.owner == value }
            AuditScope.EVERYTHING -> items
        }
        return matching.map { it.abNo }.sortedWith(compareBy({ it.toIntOrNull() ?: 0 }, { it }))
    }

    fun audit(id: String): AuditSession? = audits.firstOrNull { it.id == id }

    fun recordFound(abNo: String, auditID: String, method: String, scanID: String?) {
        val a = audit(auditID) ?: return
        if (a.found.any { it.abNo == abNo }) return
        audits = audits.map {
            if (it.id == auditID)
                it.copy(found = (it.found + AuditFinding(abNo = abNo, method = method, scanID = scanID)).toMutableList())
            else it
        }
        markConfirmed(abNo, scanID)
        save()
    }

    fun recordExtra(extra: AuditExtra, auditID: String) {
        audits = audits.map {
            if (it.id == auditID) it.copy(extras = (it.extras + extra).toMutableList()) else it
        }
        save()
    }

    fun unmarkFound(abNo: String, auditID: String) {
        audits = audits.map {
            if (it.id == auditID) it.copy(found = it.found.filter { f -> f.abNo != abNo }.toMutableList()) else it
        }
        save()
    }

    fun finishAudit(id: String) {
        audits = audits.map { if (it.id == id) it.copy(finishedAt = System.currentTimeMillis()) else it }
        save()
    }

    fun deleteAudit(id: String) {
        audits = audits.filter { it.id != id }
        save()
    }

    // ---- Import -------------------------------------------------------------

    enum class ImportMode {
        MERGE, REPLACE;

        val label: String get() = if (this == MERGE) "Merge" else "Replace"
        val blurb: String
            get() = if (this == MERGE)
                "Keep vials added on this phone, update everything the file also has."
            else "Discard what is here and use the file exactly as exported."
    }

    /** Import a fresh export. Both files are required together: a definitions
     *  file without inventory would leave every vial unlinked. */
    fun importCSVs(definitions: String, inventory: String, mode: ImportMode) {
        val loaded = try {
            Catalog.load(definitions, inventory)
        } catch (e: CatalogException) {
            banner = Banner("${e.message}. Export the grid view, not a filtered subset.", isError = true)
            return
        } catch (e: Exception) {
            banner = Banner("Import failed: ${e.message}", isError = true)
            return
        }

        val newDefs = loaded.definitions.map { DefRecord.from(it) }
        val newItems = loaded.items.map { ItemRecord.from(it) }
        if (newDefs.isEmpty() || newItems.isEmpty()) {
            banner = Banner("That export parsed to zero rows - check the two files are the right way round.", isError = true)
            return
        }

        when (mode) {
            ImportMode.REPLACE -> { defs = newDefs; items = newItems }
            ImportMode.MERGE -> {
                val mergedDefs = newDefs.toMutableList()
                for (local in defs.filter { it.isLocal }) {
                    if (mergedDefs.none { normKey(it.name) == normKey(local.name) }) mergedDefs.add(local)
                }
                val mergedItems = newItems.toMutableList()
                for (local in items) {
                    val idx = mergedItems.indexOfFirst { it.abNo == local.abNo }
                    if (idx < 0) {
                        // Received here and not yet pushed upstream - keep it.
                        if (local.isLocal) mergedItems.add(local)
                        continue
                    }
                    // Confirmation history is ours, not the export's.
                    mergedItems[idx] = mergedItems[idx].copy(
                        lastConfirmedAt = local.lastConfirmedAt,
                        lastScanID = local.lastScanID,
                        notes = if (local.notes.isEmpty()) mergedItems[idx].notes else local.notes,
                    )
                }
                defs = mergedDefs; items = mergedItems
            }
        }
        commit()
        banner = Banner("Imported ${defs.size} definitions and ${items.size} vials.")
    }

    fun resetToBundled() {
        defs = emptyList(); items = emptyList()
        seedFromAssets()
        banner = Banner("Catalog reset to the bundled snapshot.")
    }

    // ---- Export -------------------------------------------------------------

    /** Columns the app owns: whatever the operator edited here wins over the
     *  value that came in from the import, even though both live in the row. */
    private val ownedInventoryColumns: Map<String, (ItemRecord) -> String> = mapOf(
        "AbNo" to { it.abNo }, "Antibody Definitions" to { it.definitionName },
        "Vial Name" to { it.vialName }, "Ab Lot" to { it.lot },
        "Concentration" to { it.concentration }, "Location" to { it.location },
        "Box" to { it.box }, "Owner" to { it.owner }, "Ab Status" to { it.status },
    )

    fun exportInventoryCSV(): File? {
        val sorted = items.sortedBy { it.abNo.toIntOrNull() ?: 0 }
        // Reproduce the imported column set, in its original order, so the file
        // can go straight back into Airtable rather than losing every column the
        // app does not name.
        val source = ArrayList<String>()
        val seen = HashSet<String>()
        for (i in sorted) for (e in i.extra) {
            if (seen.add(normKey(e.name))) source.add(e.name)
        }
        for (owned in ownedInventoryColumns.keys.sorted()) {
            if (seen.add(normKey(owned))) source.add(owned)
        }
        val appOnly = listOf("Notes", "Received", "Added on device", "Last confirmed")

        val rows = ArrayList<List<String>>()
        rows.add(source + appOnly)
        for (i in sorted) {
            val bag = i.extra.toBag()
            val row = source.map { col -> ownedInventoryColumns[col]?.invoke(i) ?: (bag[col] ?: "") }
            rows.add(row + listOf(
                i.notes, i.receivedAt?.let { stamp(it) } ?: "",
                if (i.isLocal) "yes" else "", i.lastConfirmedAt?.let { stamp(it) } ?: "",
            ))
        }
        return write(rows, "inventory-${fileStamp()}.csv")
    }

    private val ownedDefinitionColumns: Map<String, (DefRecord) -> String> = mapOf(
        "AntibodyName" to { it.name }, "Target" to { it.target }, "Clone" to { it.clone },
        "Host" to { it.host }, "Isotype" to { it.isotype }, "Vendor" to { it.vendor },
        "Catalog#" to { it.catalog },
        "Inventory Items" to { it.inventoryLinks.joinToString(", ") },
    )

    fun exportDefinitionsCSV(): File? {
        val source = ArrayList<String>()
        val seen = HashSet<String>()
        for (d in defs) for (e in d.extra) {
            if (seen.add(normKey(e.name))) source.add(e.name)
        }
        for (owned in ownedDefinitionColumns.keys.sorted()) {
            if (seen.add(normKey(owned))) source.add(owned)
        }
        val rows = ArrayList<List<String>>()
        rows.add(source + "Added on device")
        for (d in defs) {
            val bag = d.extra.toBag()
            val row = source.map { col -> ownedDefinitionColumns[col]?.invoke(d) ?: (bag[col] ?: "") }
            rows.add(row + (if (d.isLocal) "yes" else ""))
        }
        return write(rows, "definitions-${fileStamp()}.csv")
    }

    fun exportScanLogCSV(): File? {
        val rows = ArrayList<List<String>>()
        rows.add(listOf(
            "When", "Verdict", "Outcome", "AbNo", "Antibody", "Catalog", "Lot",
            "Barcode", "Score", "Margin", "Frames", "Lot not on record", "Note", "Why",
        ))
        for (s in scans) {
            rows.add(listOf(
                stamp(s.date), s.verdict, s.outcome.name, s.abNo ?: "",
                s.definitionName ?: "", s.catalog ?: "", s.lot ?: "", s.barcode ?: "",
                "%.1f".format(s.score), "%.1f".format(s.margin),
                s.framesUsed.toString(), if (s.lotNotOnRecord) "yes" else "",
                s.note, s.reasons.joinToString("; "),
            ))
        }
        return write(rows, "scan-log-${fileStamp()}.csv")
    }

    fun exportAuditCSV(session: AuditSession): File? {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Status", "AbNo", "Vial", "Antibody", "Lot", "Box", "Location", "When", "How"))
        for (f in session.found) {
            val it0 = item(f.abNo)
            rows.add(listOf("found", f.abNo, it0?.vialName ?: "", it0?.definitionName ?: "",
                it0?.lot ?: "", it0?.box ?: "", it0?.location ?: "", stamp(f.at), f.method))
        }
        for (abNo in session.missing) {
            val it0 = item(abNo)
            rows.add(listOf("MISSING", abNo, it0?.vialName ?: "", it0?.definitionName ?: "",
                it0?.lot ?: "", it0?.box ?: "", it0?.location ?: "", "", ""))
        }
        for (e in session.extras) {
            rows.add(listOf("unexpected", "", e.label, "", e.lot ?: "", "", "", stamp(e.at), "scan"))
        }
        val slug = session.title.replace(" ", "-").lowercase()
        return write(rows, "audit-$slug-${fileStamp()}.csv")
    }

    /** The whole store, for backup or for moving to another phone. */
    fun exportBackupJSON(): File? {
        val snap = Snapshot(defs = defs, items = items, scans = scans, audits = audits, settings = settings)
        val text = runCatching { json.encodeToString(snap) }.getOrNull() ?: return null
        val f = File(exportDir(), "vialscanner-backup-${fileStamp()}.json")
        return runCatching { f.writeText(text); f }.getOrNull()
    }

    fun importBackupJSON(text: String) {
        val snap = runCatching { json.decodeFromString<Snapshot>(text) }.getOrNull()
        if (snap == null) {
            banner = Banner("That file is not a Vial Scanner backup.", isError = true)
            return
        }
        defs = snap.defs; items = snap.items; scans = snap.scans
        audits = snap.audits; settings = snap.settings
        commit()
        banner = Banner("Restored ${items.size} vials and ${scans.size} scans.")
    }

    // ---- CSV writing --------------------------------------------------------

    /** Exports land in a FileProvider-visible directory so they can be shared
     *  out; nothing here is written where another app could read it silently. */
    private fun exportDir(): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun write(rows: List<List<String>>, name: String): File? {
        val text = rows.joinToString("\r\n") { it.joinToString(",") { c -> escape(c) } }
        val f = File(exportDir(), name)
        return runCatching {
            // BOM so Excel opens UTF-8 exports without mangling µ.
            f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())
            f
        }.getOrElse {
            banner = Banner("Export failed: ${it.message}", isError = true)
            null
        }
    }

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + field.replace("\"", "\"\"") + "\""
        else field

    companion object {
        /** 12 hours. Long enough for a shift, short enough that yesterday's box
         *  cannot re-file today's vials. */
        const val SESSION_BOX_TTL_MS: Long = 12 * 3600 * 1000

        fun stamp(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

        fun fileStamp(): String =
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    }
}
