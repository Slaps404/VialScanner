package org.lji.vialscanner

import kotlinx.serialization.Serializable
import org.lji.vialcore.AntibodyDefinition
import org.lji.vialcore.FieldBag
import org.lji.vialcore.FieldEntry
import org.lji.vialcore.InventoryItem
import org.lji.vialcore.Verdict
import java.util.UUID

// Persistable mirrors of the engine's records.
//
// `vialcore` deliberately knows nothing about storage: it takes a Catalog and
// answers questions. These are the writable, serialisable side of the same data,
// and the Catalog is rebuilt from them whenever they change. Everything the lab
// edits happens here, not in the engine.
//
// Times are epoch milliseconds rather than formatted strings: a phone that
// changes timezone mid-audit must not reorder its own history.

/** One imported column. `FieldBag` lives in `vialcore`, which has no
 *  serialisation dependency on purpose, so the DTO lives here instead. */
@Serializable
data class FieldEntryDTO(val name: String, val value: String)

fun List<FieldEntryDTO>.toBag(): FieldBag = FieldBag(map { FieldEntry(it.name, it.value) })
fun FieldBag.toDTO(): List<FieldEntryDTO> = entries.map { FieldEntryDTO(it.name, it.value) }

@Serializable
data class DefRecord(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var target: String = "",
    var clone: String = "",
    var host: String = "",
    var isotype: String = "",
    var vendor: String = "",
    var catalog: String = "",
    var inventoryLinks: MutableList<String> = mutableListOf(),
    /** Added on this device rather than imported. Kept so an import can be
     *  merged without silently discarding local receiving work. */
    var isLocal: Boolean = false,
    /** Every other column of the imported row. */
    var extra: List<FieldEntryDTO> = emptyList(),
) {
    val core: AntibodyDefinition
        get() = AntibodyDefinition(
            name = name, target = target, clone = clone, host = host, isotype = isotype,
            vendor = vendor, catalog = catalog, inventoryLinks = inventoryLinks.toList(),
            extra = extra.toBag(),
        )

    companion object {
        fun from(d: AntibodyDefinition) = DefRecord(
            name = d.name, target = d.target, clone = d.clone, host = d.host,
            isotype = d.isotype, vendor = d.vendor, catalog = d.catalog,
            inventoryLinks = d.inventoryLinks.toMutableList(), extra = d.extra.toDTO(),
        )
    }
}

@Serializable
data class ItemRecord(
    val id: String = UUID.randomUUID().toString(),
    var abNo: String,
    var definitionName: String = "",
    var vialName: String = "",
    var lot: String = "",
    var concentration: String = "",
    var location: String = "",
    var box: String = "",
    var owner: String = "",
    var status: String = "",
    var notes: String = "",
    var receivedAt: Long? = null,
    var isLocal: Boolean = false,
    /** Last time a scan confirmed this vial physically exists. */
    var lastConfirmedAt: Long? = null,
    var lastScanID: String? = null,
    var extra: List<FieldEntryDTO> = emptyList(),
) {
    val core: InventoryItem
        get() = InventoryItem(
            abNo = abNo, definitionName = definitionName, vialName = vialName, lot = lot,
            concentration = concentration, location = location, box = box, owner = owner,
            extra = extra.toBag(),
        )

    val displayName: String
        get() = when {
            vialName.isNotEmpty() -> vialName
            definitionName.isNotEmpty() -> definitionName
            else -> "AbNo $abNo"
        }

    companion object {
        fun from(i: InventoryItem) = ItemRecord(
            abNo = i.abNo, definitionName = i.definitionName, vialName = i.vialName,
            lot = i.lot, concentration = i.concentration, location = i.location,
            box = i.box, owner = i.owner,
            // `Ab Status` lives in the bag as an imported column and in `status`
            // as an editable one; seed the editable copy so the app shows it
            // before anybody touches it.
            status = i.status, notes = i.receivingNotes, extra = i.extra.toDTO(),
        )
    }
}

// MARK: - Scan log

@Serializable
enum class ScanOutcome {
    /** Operator confirmed the vial is what the app said it was. */
    CONFIRMED,

    /** Parked for a human to look at later. */
    REVIEW,

    /** A new vial was received into inventory from this scan. */
    RECEIVED,

    /** Operator rejected the reading. */
    REJECTED,

    /** Recorded but not acted on. */
    LOGGED;

    val label: String
        get() = when (this) {
            CONFIRMED -> "Confirmed"
            REVIEW -> "Needs review"
            RECEIVED -> "Received"
            REJECTED -> "Rejected"
            LOGGED -> "Logged"
        }
}

@Serializable
data class ScanRecord(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    var verdict: String,
    var outcome: ScanOutcome,
    var abNo: String? = null,
    var definitionName: String? = null,
    var catalog: String? = null,
    var lot: String? = null,
    var vendor: String? = null,
    var clone: String? = null,
    var barcode: String? = null,
    var score: Double = 0.0,
    var margin: Double = 0.0,
    var reasons: List<String> = emptyList(),
    var framesUsed: Int = 0,
    var lotNotOnRecord: Boolean = false,
    var note: String = "",
    var auditID: String? = null,
    var resolvedAt: Long? = null,
    /** Filename under the Clips directory, or null when this scan's video was
     *  never recorded or was thrown away. Mirrors Swift's `ScanRecord.clipFile`. */
    var clipFile: String? = null,
    /** Set only on a rejected scan where the operator identified the real
     * vial. Null either means the scan wasn't rejected, or it was rejected
     * with the truth unknown - "don't know" has to stay a real answer, not
     * something indistinguishable from a scan nobody ever corrected. */
    var trueAbNo: String? = null,
    /** Set instead of [trueAbNo] when the operator recognised the antibody
     * but not a specific inventory vial - they picked a definition, not a
     * vial, so only the catalog number is known ground truth. */
    var trueCatalog: String? = null,
) {
    val verdictValue: Verdict
        get() = runCatching { Verdict.valueOf(verdict) }.getOrDefault(Verdict.NOT_FOUND)

    val title: String
        get() = definitionName?.takeIf { it.isNotEmpty() }
            ?: catalog?.takeIf { it.isNotEmpty() }?.let { "Cat $it" }
            ?: "Unreadable label"

    val subtitle: String
        get() {
            val bits = ArrayList<String>()
            abNo?.let { bits.add("AbNo $it") }
            lot?.takeIf { it.isNotEmpty() }?.let { bits.add("Lot $it") }
            if (bits.isEmpty()) catalog?.let { bits.add("Cat $it") }
            // A rejection without its correction visible in the list is a
            // rejection nobody will tap into, and this is the data the
            // labelling session exists to produce.
            trueAbNo?.let { bits.add("→ AbNo $it") }
                ?: trueCatalog?.let { bits.add("→ Cat $it") }
            return bits.joinToString(" · ")
        }
}

// MARK: - Inventory confirmation (audit)

@Serializable
enum class AuditScope {
    BOX, LOCATION, OWNER, EVERYTHING;

    val label: String
        get() = when (this) {
            BOX -> "Box"; LOCATION -> "Location"; OWNER -> "Owner"; EVERYTHING -> "Whole inventory"
        }
}

@Serializable
data class AuditFinding(
    val id: String = UUID.randomUUID().toString(),
    val abNo: String,
    val at: Long = System.currentTimeMillis(),
    /** `scan` when the camera resolved it, `manual` when ticked by hand. */
    val method: String = "scan",
    val scanID: String? = null,
)

@Serializable
data class AuditExtra(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val catalog: String? = null,
    val lot: String? = null,
    val scanID: String? = null,
    val at: Long = System.currentTimeMillis(),
)

@Serializable
data class AuditSession(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val scope: AuditScope,
    val scopeValue: String,
    val startedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long? = null,
    /** AbNos that should be in the drawer when this session began. */
    val expected: List<String>,
    var found: MutableList<AuditFinding> = mutableListOf(),
    /** Vials scanned during the session that were not expected here. */
    var extras: MutableList<AuditExtra> = mutableListOf(),
) {
    val isOpen: Boolean get() = finishedAt == null
    val foundSet: Set<String> get() = found.map { it.abNo }.toSet()
    val missing: List<String> get() = expected.filter { it !in foundSet }
    val progress: Double
        get() = if (expected.isEmpty()) 1.0
        else foundSet.intersect(expected.toSet()).size.toDouble() / expected.size
    val title: String get() = if (scope == AuditScope.EVERYTHING) "Whole inventory" else "${scope.label} $scopeValue"
}

// MARK: - Settings

@Serializable
data class AppSettings(
    /** Hands-free: keep scanning, commit without a tap. */
    var handsFree: Boolean = false,
    /** Seconds a hands-free result stays on screen before it is committed. */
    var handsFreeDelay: Double = 3.0,
    /** Only auto-commit at or above this confidence. CERTAIN means barcode only. */
    var autoCommitVerdict: String = Verdict.CONFIDENT.name,
    /** Anything below the auto-commit bar goes to the review queue instead of
     *  stopping the line. */
    var handsFreeParksUncertain: Boolean = true,
    /** Says the AbNo and box out loud, digit by digit - see `Speaker.phrase`. */
    var speak: Boolean = true,
    var haptics: Boolean = true,
    var torchOnByDefault: Boolean = false,
    /** Steer focus at the label text instead of at the middle of the frame. */
    var autoTextFocus: Boolean = true,
    /** Start on the ultra-wide lens, which focuses far closer than the main one.
     *  Off by default: it is the softer sensor, and it only wins up close. */
    var preferUltraWideZoom: Boolean = false,
    var defaultOwner: String = "",
    var defaultLocation: String = "",
    var defaultBox: String = "",
    /** Keeps the video for every scan, not just parked ones. */
    var alwaysKeepClips: Boolean = false,
    /** Days before a kept clip is auto-deleted. 0 means never - the labelling
     *  runs this app is built for depend on that being a real "never", not a
     *  very long default. */
    var clipRetentionDays: Int = 30,
    /** The box currently being worked through. Every vial confirmed while this
     *  is set gets stamped with it, which is the only realistic way the Box
     *  column ever gets filled in: it is 11% populated in the export and nobody
     *  is going to backfill 120 rows by hand. */
    var sessionBox: String = "",
    /** When `sessionBox` was set. A box left over from last week must not
     *  silently re-file today's vials. */
    var sessionBoxSetAt: Long? = null,
    // Engine knobs, surfaced because the right values are lab-dependent.
    var maxOCRFrames: Int = 24,
    var minScanSeconds: Double = 1.0,
    var maxScanSeconds: Double = 4.0,
    var minDistinctViews: Int = 2,
    var roiInset: Double = 0.15,
) {
    val autoCommit: Verdict
        get() = runCatching { Verdict.valueOf(autoCommitVerdict) }.getOrDefault(Verdict.CONFIDENT)

    fun engineConfig(): org.lji.vialcore.EngineConfig {
        val c = org.lji.vialcore.EngineConfig()
        c.maxOCRFrames = maxOCRFrames
        c.maxScanDuration = maxScanSeconds
        // The floor can never cross the ceiling: a minimum above the give-up
        // time would mean no verdict is ever allowed to settle on its own.
        c.minScanDuration = minOf(minScanSeconds, maxScanSeconds - 0.5)
        c.minDistinctViews = maxOf(1, minDistinctViews)
        // Grace is measured from the give-up time, so raising that knob does not
        // quietly eat the extra seconds a scan gets when it has read nothing.
        c.hardScanCeiling = maxOf(12.0, maxScanSeconds + 2 * c.graceExtension)
        val inset = roiInset.coerceIn(0.0, 0.4)
        c.regionOfInterest = org.lji.vialcore.NormRect(inset, inset * 1.3, 1 - inset * 2, 1 - inset * 2.6)
        return c
    }
}

// MARK: - Clip diagnostics

/**
 * Sidecar written next to a kept clip (same basename, `.json`) so the video
 * can be understood without the app: what the engine concluded, what it read
 * to get there, and the conditions the frame was captured under. Written for
 * every clip that gets kept, including scans nobody confirmed or parked - a
 * NEW VIAL or a result the operator waved past is exactly the failure nobody
 * can reproduce later without this.
 *
 * Does not carry raw OCR lines: `ScanEngine`/`MlKitFrame` (in `:vialcore` and
 * this module) consume recognised text into `LabelField` values and discard
 * the source strings, so the lines never reach anything outside the engine.
 * Recovering them would mean plumbing new state through `:vialcore`, which is
 * out of bounds for this change - see the port notes for `ClipRecorder.kt`.
 */
@Serializable
data class ClipMetadata(
    val verdict: String,
    val definitionName: String? = null,
    val abNo: String? = null,
    val score: Double,
    val margin: Double,
    val reasons: List<String>,
    /** `LabelField` name to the value the engine extracted for it. */
    val fields: Map<String, String>,
    val barcode: BarcodeMetadata? = null,
    val capture: CaptureMetadata,
    val device: DeviceMetadata,
)

@Serializable
data class BarcodeMetadata(
    val raw: String,
    val symbology: String,
    val catalog: String? = null,
    val lot: String? = null,
)

@Serializable
data class CaptureMetadata(
    val zoomRatio: Float,
    val torchOn: Boolean,
    val focusLocked: Boolean,
    val framesSeen: Int,
    val ocrFramesUsed: Int,
    val roiInset: Double,
    val maxOCRFrames: Int,
    val minScanSeconds: Double,
    val maxScanSeconds: Double,
)

@Serializable
data class DeviceMetadata(
    val model: String,
    val androidVersion: String,
    val appVersion: String,
)
