package org.lji.vialcore

import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Parity harness: replays the observation tapes recorded by the Swift CLI
 * (`vialscan --fixtures DIR`) through this Kotlin engine and checks that the
 * two agree.
 *
 * Why tapes and not videos: running ML Kit over the same videos would compare
 * Apple's OCR against Google's, and every disagreement would be unattributable
 * to either the OCR or the pipeline. A tape is the exact sequence of
 * observations that fed the Swift `EvidenceBuffer`, so replaying it holds the
 * OCR constant and tests the only thing a port can get wrong: Catalog,
 * FieldExtraction, Evidence and Matcher.
 *
 * What this does *not* cover: `ScanEngine` pacing. A tape only holds the frames
 * Swift's sharpness gate let through, so there is nothing to replay for the
 * frames it skipped. Pacing needs synthetic frames, and is tested separately.
 *
 *     kotlinc ... -include-runtime -d parity.jar
 *     java -cp parity.jar org.lji.vialcore.ReplayParityKt <tapeDir> <defs.csv> <inv.csv>
 */
private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                't' -> { out.append('\t'); i += 2; continue }
                'n' -> { out.append('\n'); i += 2; continue }
                '\\' -> { out.append('\\'); i += 2; continue }
            }
        }
        out.append(c); i += 1
    }
    return out.toString()
}

private class Expected(
    val verdict: String,
    val standard: EvidenceStandard,
    val catalog: String,
    val abNo: String,
    val lotNotOnRecord: Boolean,
    val evidenceLevel: String,
    val score: Double,
    val margin: Double,
)

private fun replay(tape: File, matcher: Matcher): Pair<Expected, ScanResult>? {
    val ev = EvidenceBuffer()
    var pending: MutableList<TextObservation>? = null
    var expected: Expected? = null

    fun flush() {
        pending?.let { ev.addFrame(it) }
        pending = null
    }

    for (line in tape.readLines()) {
        if (line.isEmpty()) continue
        val c = line.split("\t")
        when (c[0]) {
            "B" -> { flush(); ev.addBarcode(BarcodePayload(unescape(c[2]), c[1])) }
            "F" -> { flush(); pending = ArrayList() }
            "T" -> pending?.add(
                TextObservation(
                    text = unescape(c.subList(6, c.size).joinToString("\t")),
                    confidence = c[1].toFloat(),
                    boundingBox = NormRect(c[2].toDouble(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble()),
                )
            )
            "R" -> {
                flush()
                expected = Expected(
                    verdict = c[1],
                    standard = if (c[2] == "corroborated") EvidenceStandard.CORROBORATED else EvidenceStandard.BEST,
                    catalog = c[3], abNo = c[4],
                    lotNotOnRecord = c[5] == "true",
                    evidenceLevel = c[6],
                    score = c[7].toDouble(), margin = c[8].toDouble(),
                )
            }
        }
    }
    flush()
    val exp = expected ?: return null
    return exp to matcher.resolve(ev, exp.standard)
}

/** Swift spells its enums lowerCamel; Kotlin spells them SCREAMING_SNAKE. */
private fun swiftName(v: Verdict) = when (v) {
    Verdict.CERTAIN -> "certain"
    Verdict.CONFIDENT -> "confident"
    Verdict.AMBIGUOUS -> "ambiguous"
    Verdict.NOT_FOUND -> "notFound"
}

private fun swiftName(l: EvidenceLevel) = when (l) {
    EvidenceLevel.NONE -> "none"
    EvidenceLevel.THIN -> "thin"
    EvidenceLevel.SUFFICIENT -> "sufficient"
}

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: ReplayParity <tapeDir> <definitions.csv> <inventory.csv>")
        exitProcess(2)
    }
    val catalog = Catalog.load(File(args[1]), File(args[2]))
    println("catalog: ${catalog.definitions.size} definitions, ${catalog.items.size} inventory items")
    val matcher = Matcher(catalog)

    val tapes = File(args[0]).listFiles { f: File -> f.name.endsWith(".tape") }
        ?.sortedBy { it.name } ?: emptyList()
    var ok = 0
    val failures = ArrayList<String>()

    for (t in tapes) {
        val r = replay(t, matcher)
        if (r == null) { failures.add("${t.name}: tape has no result line"); continue }
        val (exp, got) = r
        val diffs = ArrayList<String>()
        if (swiftName(got.verdict) != exp.verdict) diffs.add("verdict ${exp.verdict} != ${swiftName(got.verdict)}")
        if ((got.definition?.catalog ?: "") != exp.catalog) {
            diffs.add("catalog '${exp.catalog}' != '${got.definition?.catalog ?: ""}'")
        }
        if ((got.vial?.abNo ?: "") != exp.abNo) diffs.add("abNo '${exp.abNo}' != '${got.vial?.abNo ?: ""}'")
        if (got.lotNotOnRecord != exp.lotNotOnRecord) diffs.add("lotNotOnRecord ${exp.lotNotOnRecord} != ${got.lotNotOnRecord}")
        if (swiftName(got.evidenceLevel) != exp.evidenceLevel) {
            diffs.add("evidence ${exp.evidenceLevel} != ${swiftName(got.evidenceLevel)}")
        }
        // Scores are float arithmetic over the same weights, not the same
        // instruction stream, so compare to a tolerance rather than exactly.
        if (abs(got.score - exp.score) > 0.01) diffs.add("score ${exp.score} != ${got.score}")
        if (abs(got.margin - exp.margin) > 0.01) diffs.add("margin ${exp.margin} != ${got.margin}")

        if (diffs.isEmpty()) ok += 1 else failures.add("${t.name}: ${diffs.joinToString("; ")}")
    }

    println("parity: $ok/${tapes.size} tapes match the Swift engine")
    for (f in failures) println("  FAIL $f")
    if (failures.isNotEmpty()) exitProcess(1)
}
