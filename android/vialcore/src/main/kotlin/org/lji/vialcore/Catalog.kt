package org.lji.vialcore

import java.io.File

// MARK: - Field bag

data class FieldEntry(val name: String, val value: String)

/**
 * Every column of the source row, in the order the export printed them.
 *
 * The catalog CSVs carry far more than the engine matches on: category,
 * conjugate, formulation, amounts, prices, links. Naming each one as a property
 * means editing code every time the Airtable base grows a column, and the base
 * grows. So the named properties below stay exactly what the *engine* reasons
 * about, and everything else rides along here, retrievable by its printed
 * header.
 */
class FieldBag(val entries: List<FieldEntry> = emptyList()) {
    operator fun get(header: String): String? {
        val k = normKey(header)
        return entries.firstOrNull { normKey(it.name) == k }?.value
    }

    /** The first of [headers] that is present and non-empty. Airtable renames and
     *  duplicates columns (`Conjugate` is empty, `Conjugate copy` is not), so a
     *  display field is a list of candidates, not one name. */
    fun firstValue(vararg headers: String): String {
        for (h in headers) {
            val v = this[h]
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Non-empty columns, minus the ones already shown as named properties. */
    fun others(excluding: List<String>): List<FieldEntry> {
        val skip = excluding.mapTo(HashSet(), ::normKey)
        return entries.filter { it.value.isNotEmpty() && normKey(it.name) !in skip }
    }
}

// MARK: - Records

class AntibodyDefinition(
    val name: String,
    val target: String,
    val clone: String,
    val host: String,
    val isotype: String,
    val vendor: String,
    val catalog: String,
    /** AbNo values of linked Inventory Items (the authoritative join). */
    val inventoryLinks: List<String>,
    val extra: FieldBag = FieldBag(),
) {
    val productURL: String get() = extra.firstValue("Product Website")
    val price: String get() = extra.firstValue("Price")
    val reactivity: String get() = extra.firstValue("Reactivity Vendor", "Reactivity Cited")
    val storageLocation: String get() = extra.firstValue("Storage location (from Inventory Items)")
    val proteinDatabase: String get() = extra.firstValue("Protein Database")
    val geneDatabase: String get() = extra.firstValue("Gene Database")
}

class InventoryItem(
    val abNo: String,
    val definitionName: String,
    val vialName: String,
    val lot: String,
    val concentration: String,
    val location: String,
    val box: String,
    val owner: String,
    val extra: FieldBag = FieldBag(),
) {
    val status: String get() = extra.firstValue("Ab Status")
    val category: String get() = extra.firstValue("Ab Category")

    /** Reads three headers because the base has an empty `Conjugate` column
     *  beside a populated `Conjugate copy`. */
    val conjugate: String
        get() = extra.firstValue("Conjugate", "Dye Name (from Conjugate copy)", "Conjugate copy")

    val amount: String get() = extra.firstValue("Amount")
    val formulation: String get() = extra.firstValue("Formulation")
    val initialVolume: String get() = extra.firstValue("Initial Volume")
    val currentVolume: String get() = extra.firstValue("Current Volume")
    val diluteTo2: String get() = extra.firstValue("Dilute to 2 ug/ml")
    val receivingNotes: String get() = extra.firstValue("Receiving Notes")
    val target: String get() = extra.firstValue("Target")
    val clone: String get() = extra.firstValue("Clone (from Antibody Definitions)", "Clone")
    val parentVial: String get() = extra.firstValue("Parent Vial")
    val childVial: String get() = extra.firstValue("Child Vial")

    companion object {
        /** Headers already surfaced as properties, so an "everything else" list
         *  does not repeat them. */
        val namedHeaders = listOf(
            "AbNo", "Antibody Definitions", "Vial Name", "Ab Lot", "Concentration",
            "Location", "Box", "Owner", "Ab Status", "Ab Category", "Conjugate",
            "Conjugate copy", "Dye Name (from Conjugate copy)", "Amount", "Formulation",
            "Initial Volume", "Current Volume", "Dilute to 2 ug/ml", "Receiving Notes",
            "Target", "Clone (from Antibody Definitions)", "Parent Vial", "Child Vial",
        )
    }
}

// MARK: - Catalog

class Catalog(
    val definitions: List<AntibodyDefinition>,
    val items: List<InventoryItem>,
) {
    private val byAbNo = HashMap<String, InventoryItem>()
    private val byDefName = HashMap<String, MutableList<InventoryItem>>()

    /** Exact + OCR-folded catalog-number index for O(1) tier-2 resolution. */
    private val byCatalog = HashMap<String, MutableList<Int>>()
    private val byCatalogFolded = HashMap<String, MutableList<Int>>()

    init {
        for (it in items) {
            byAbNo[it.abNo.trim()] = it
            byDefName.getOrPut(normKey(it.definitionName)) { ArrayList() }.add(it)
        }
        definitions.forEachIndexed { i, d ->
            val k = normKey(d.catalog)
            if (k.length >= 4) {
                byCatalog.getOrPut(k) { ArrayList() }.add(i)
                byCatalogFolded.getOrPut(ocrFold(d.catalog)) { ArrayList() }.add(i)
            }
        }
    }

    fun definitionIndices(forCatalog: String): List<Int> {
        val k = normKey(forCatalog)
        byCatalog[k]?.let { return it }
        val hit = byCatalogFolded[ocrFold(forCatalog)] ?: return emptyList()
        // The fold commits `s` to `5`, but the glyph is equally `8`. If some
        // other definition reads just as plausibly, this is not an exact match:
        // hand it to the corroborated near-match tier instead of asserting one.
        val rivals = confusableIndices(k).filter { it !in hit }
        return if (rivals.isEmpty()) hit else emptyList()
    }

    /** Definitions of the same length whose every character is equal to, or
     *  confusable with, the read. Only walked on a folded hit. */
    private fun confusableIndices(k: String): List<Int> {
        val read = graphemes(k)
        if (read.size < 4) return emptyList()
        val out = ArrayList<Int>()
        definitions.forEachIndexed { i, d ->
            val c = graphemes(normKey(d.catalog))
            if (c.size == read.size &&
                c.indices.all { c[it] == read[it] || confusable(c[it], read[it]) }
            ) out.add(i)
        }
        return out
    }

    /**
     * Catalog numbers within [maxDistance] edits, but only when the answer is
     * unambiguous. OCR reliably confuses 0/1 in these part numbers (`0103-01`
     * read as `1103-01`); accepting a unique single-edit neighbour recovers
     * those without ever guessing between `0101-01` and `0105-01`. Confusable
     * substitutions are half price, so a shape-plausible reading outranks an
     * arbitrary one instead of tying with it.
     */
    fun definitionIndexNearCatalog(raw: String, maxDistance: Int = 1): Int? {
        val k = normKey(raw)
        if (k.length < 5) return null
        var winners = ArrayList<Int>()
        // Doubled scale: one ordinary edit, or two confusable substitutions.
        val budget = maxDistance * 2
        var bestDistance = budget + 1
        definitions.forEachIndexed { i, d ->
            val c = normKey(d.catalog)
            if (c.length >= 5 && kotlin.math.abs(c.length - k.length) <= maxDistance) {
                val dist = confusionDistance(c, k)
                if (dist < bestDistance) {
                    bestDistance = dist
                    winners = arrayListOf(i)
                } else if (dist == bestDistance) winners.add(i)
            }
        }
        if (bestDistance > budget || winners.size != 1) return null
        return winners[0]
    }

    /** Resolve the vials belonging to a definition. Prefers the explicit link
     *  field; falls back to name join. Never keys on lot — lot is not unique. */
    fun inventoryFor(def: AntibodyDefinition): List<InventoryItem> {
        val linked = def.inventoryLinks.mapNotNull { byAbNo[it] }
        if (linked.isNotEmpty()) return linked
        return byDefName[normKey(def.name)] ?: emptyList()
    }

    companion object {
        fun load(definitionsCSV: File, inventoryCSV: File): Catalog =
            load(readStripped(definitionsCSV), readStripped(inventoryCSV))

        fun load(definitionsCSV: String, inventoryCSV: String): Catalog {
            // Excel and Airtable both write a UTF-8 BOM; strip it here as well as
            // in the `File` overload, because the app imports text it has already
            // read from a content URI and never touches the file path.
            val dRows = parseCSV(stripBOM(definitionsCSV))
            val dHead = dRows.firstOrNull() ?: throw CatalogException("definitions: empty file")
            val dIdx = indexer(dHead)
            fun dCol(n: String) = dIdx(n) ?: throw CatalogException("definitions: missing column $n")

            val cName = dCol("AntibodyName"); val cTarget = dCol("Target")
            val cClone = dCol("Clone"); val cHost = dCol("Host")
            val cIso = dCol("Isotype"); val cVendor = dCol("Vendor")
            val cCat = dCol("Catalog#"); val cInv = dCol("Inventory Items")

            val defs = ArrayList<AntibodyDefinition>()
            for (r in dRows.drop(1)) {
                if (r.size <= maxOf(cInv, cCat)) continue
                val name = r[cName].trim()
                if (name.isEmpty()) continue
                val links = r[cInv].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                defs.add(
                    AntibodyDefinition(
                        name = name,
                        target = r[cTarget].trim(),
                        clone = r[cClone].trim(),
                        host = r[cHost].trim(),
                        isotype = r[cIso].trim(),
                        vendor = r[cVendor].trim(),
                        catalog = r[cCat].trim(),
                        inventoryLinks = links,
                        extra = bag(dHead, r),
                    )
                )
            }

            val iRows = parseCSV(stripBOM(inventoryCSV))
            val iHead = iRows.firstOrNull() ?: throw CatalogException("inventory: empty file")
            val iIdx = indexer(iHead)
            fun iCol(n: String) = iIdx(n) ?: throw CatalogException("inventory: missing column $n")

            val kAb = iCol("AbNo"); val kDef = iCol("Antibody Definitions")
            val kVial = iCol("Vial Name"); val kLot = iCol("Ab Lot")
            val kConc = iCol("Concentration"); val kLoc = iCol("Location")
            val kBox = iCol("Box"); val kOwner = iCol("Owner")

            val items = ArrayList<InventoryItem>()
            for (r in iRows.drop(1)) {
                if (r.size <= maxOf(kOwner, kBox)) continue
                val ab = r[kAb].trim()
                if (ab.isEmpty()) continue
                items.add(
                    InventoryItem(
                        abNo = ab,
                        definitionName = r[kDef].trim(),
                        vialName = r[kVial].trim(),
                        lot = r[kLot].trim(),
                        concentration = r[kConc].trim(),
                        location = r[kLoc].trim(),
                        box = r[kBox].trim(),
                        owner = r[kOwner].trim(),
                        extra = bag(iHead, r),
                    )
                )
            }
            return Catalog(defs, items)
        }

        private fun readStripped(f: File): String = stripBOM(f.readText(Charsets.UTF_8))

        private fun stripBOM(s: String): String = if (s.startsWith("﻿")) s.substring(1) else s

        private fun indexer(header: List<String>): (String) -> Int? {
            val map = LinkedHashMap<String, Int>()
            header.forEachIndexed { i, h -> map.putIfAbsent(normKey(h), i) }
            return { map[normKey(it)] }
        }

        /** Every column of one row, header-keyed, trimmed, blanks kept so the
         *  column list still reflects the export's shape. */
        private fun bag(header: List<String>, row: List<String>): FieldBag {
            val out = ArrayList<FieldEntry>(header.size)
            header.forEachIndexed { i, name ->
                val n = name.trim()
                if (n.isNotEmpty()) out.add(FieldEntry(n, if (i < row.size) row[i].trim() else ""))
            }
            return FieldBag(out)
        }
    }
}

class CatalogException(message: String) : Exception(message)

/**
 * Minimal RFC-4180 reader: handles quoted fields, embedded commas/newlines, ""
 * escapes. Iterates chars rather than lines because a field may legally contain
 * a newline.
 */
fun parseCSV(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var i = 0

    fun endField() { row.add(field.toString()); field.setLength(0) }
    fun endRow() {
        endField()
        // Ignore the trailing blank line most exporters emit.
        if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
        row = ArrayList()
    }

    while (i < text.length) {
        val c = text[i]
        if (c == '"') {
            i++
            while (i < text.length) {
                if (text[i] == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                    i++
                    break
                }
                field.append(text[i]); i++
            }
            continue
        }
        when (c) {
            ',' -> { endField(); i++ }
            '\n' -> { endRow(); i++ }
            '\r' -> { endRow(); i += if (i + 1 < text.length && text[i + 1] == '\n') 2 else 1 }
            else -> { field.append(c); i++ }
        }
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}
