# VialScanner

Point an iPhone at a vial, rotate it, get the inventory record. On-device, offline.

All numbers below were measured on this machine (Apple silicon, macOS) against
52 sample videos. **Nothing here has been run on an iPhone** — see *Timing* for
what that does and does not let us claim.

## Layout

```
Sources/VialCore/        Engine. No UIKit — shared by the iOS app and the CLI.
  Catalog.swift          CSV loading, catalog indexing, definition→inventory join
  Evidence.swift         Cross-frame token accumulation and voting
  FieldExtraction.swift  Label regexes, value plausibility, barcode parsing
  Matcher.swift          Tiered resolution, scoring, confidence
  Frame.swift            Pixel-buffer handling, downscaling, sharpness gate
  ScanEngine.swift       Orchestration, pacing, early exit
Sources/vialscan/        Offline validation harness (replays .MOV files)
App/                     The iOS app
  VialScannerApp.swift   Entry point
  RootView.swift         Drawer shell and routing
  ScanView.swift         Viewfinder, hands-free loop, commit actions
  ResultCard.swift       The verdict and the four things you can do about it
  CameraController.swift Capture session, frame pacing, torch, tap-to-focus
  CameraPreview.swift    Preview layer + aiming rectangle
  ClipRecorder.swift     Per-scan video, written from the same frames
  Store.swift            Persistence, import/export, audits, scan log
  Models.swift           Codable mirrors of the engine's records + settings
  InventoryView.swift    Browse, search, edit, receive
  ReviewView.swift       Parked scans, with the clip that goes with them
  AuditView.swift        Confirm inventory: box/location/owner checks
  HistoryView.swift      Every scan, filterable, with its evidence
  DataView.swift         CSV import, CSV/JSON export, clip storage
  SettingsView.swift     Hands-free, clips, receiving defaults, engine knobs
  Theme.swift            Colour, type, metrics
  Components.swift       Buttons, cards, chips, the orb
  Feedback.swift         Speech and haptics
Resources/               definitions.csv + inventory.csv, the shipped snapshot
project.yml              XcodeGen spec for the two targets
```

## Running the validator

```bash
./build-cli.sh
./vialscan videos/ --defs "Antibody Definitions.csv" --inv "Inventory Items.csv"
./vialscan videos/ --fps 6 --roi 0.15      # sampling rate and centre-crop inset
```

`build-cli.sh` compiles with `swiftc` directly, because SwiftPM's manifest step
needs a full Xcode install and fails with CommandLineTools alone. `Package.swift`
is still correct if you open the folder in Xcode.

## Results

Catalog: **161 antibody definitions, 136 inventory items.**

### Set A — the original 9 videos (7 distinct vials)

| Video | Verdict | Resolved to |
|---|---|---|
| IMG_2860 | CONFIDENT | AbNo 134 · isotype S107 0106-01 |
| IMG_2861 | CONFIDENT | AbNo 133 · isotype 11E10 0101-01 |
| IMG_2862 | CONFIDENT | AbNo 135 · isotype B10 0105-01 |
| IMG_2863 | CONFIDENT | AbNo 136 · isotype C38-2 (RUO) 557079 |
| IMG_2864 | CONFIDENT | AbNo 137 · isotype HOPC-1 0103-01 |
| IMG_2866/67/68 | new vial | cat 102116 — absent from the CSVs |
| IMG_2869 | new vial | cat 31154, lot RC2166902 — absent from the CSVs |

**5 of 5 in-database vials correct. 0 false positives.**

### Set B — the 43-video batch

1 match, 2 ambiguous, 40 new-vial.

That looks poor until you check why: **none of the catalog numbers in set B exist
in the CSVs.** Spot-checking 505706, 102112, 313502, 100401, 302923, 400155,
100331, 580702, 555051, 31315 and 400112 returns nothing in either file. Set B
therefore measures OCR extraction and *rejection* behaviour, not matching. The
40 new-vial verdicts are the correct answer, not a failure.

The three non-rejections:

* **IMG_4018 — CERTAIN.** DataMatrix decoded `14-0808-82` / lot `2003225`, matching
  definition `CD8a 4SM15 14-0808-82`. The lot does not match AbNo 18's lot
  `3362675`, so it reports **lot not on record**: a new lot of a known antibody.
* **IMG_4015 — AMBIGUOUS.** Label reads `Clone: RMG1-1  Cat 406601  Size: 50 µg`.
  The catalog holds `406602`, same clone. Reported as ambiguous rather than
  matched, because the trailing digit is a pack size, not a typo.
* **IMG_4023 — AMBIGUOUS.** Clone `C1.18.4` matches `Isotype C1.18.4 BP0085`
  (BioXCell) but the vendor reads Leinco. Score 5.3, margin 1.0.

**Across all 52 videos: 7 matches, 0 known false positives.**

Catalog numbers were extracted from 20 of the 43 set-B videos. Five produced no
usable text at all (IMG_3989, 3992, 4014, 4025, 4026); two of those resolved
anyway from a barcode in a single frame.

## Timing

Measured per Vision pass, median over 56 frames at 1080×1920:

| Stage | Median | p90 |
|---|---|---|
| Barcode, full resolution + ROI | 10.5 ms | 15.3 ms |
| OCR, half resolution + ROI, `.accurate` | 49.1 ms | 59.0 ms |
| Mono + half-scale conversion | 3.9 ms | 5.4 ms |
| Sharpness thumbnail | 3.2 ms | 5.0 ms |

Per scan: a **match** resolves in a median of 4 OCR passes (max 13); a rejection
runs until the 4-second cap.

Budget for one second of live scanning at 30 fps, with barcode on every 2nd frame
and OCR paced to ~6/s: `15×10.5 + 6×49.1 + 6×7 ≈ 495 ms`. That is roughly half of
one thread on this machine.

**On an iPhone this has not been measured.** If Vision runs 1.5–2× slower on an
A-series chip, the same budget lands at 0.75–1.0 s per second of scanning, which
saturates one background thread. The engine already drops frames while a pass is
in flight, so it degrades by sampling less rather than by falling behind. Confirm
on the target device before trusting any of this.

## How it resolves

Five tiers, first hit wins:

1. **Barcode** — full resolution, every 2nd frame. A payload that decodes cleanly
   but is absent from the catalog returns "new vial" immediately rather than
   falling through to fuzzy matching: that is a known answer, not a bad read.
2. **Exact catalog number** from voted text.
3. **Near-exact catalog number**, gated twice over — see below.
4. **Weighted fuzzy scoring** over the stable token bag, requiring both an
   absolute score and a margin over the runner-up.
5. Otherwise **new vial**.

## Two goals, opposite stopping rules

The scanner does two jobs and they want opposite things from a scan:

| | Identify (vial is in the catalog) | Catalogue (vial is new) |
|---|---|---|
| Target | margin clears | every field captured |
| Completeness | waste, stop early | the entire point |
| Partial reads | noise, reject | valuable, keep |
| Right stop | as soon as margin clears | when fields stop arriving |

The trap: the engine cannot know which mode it is in until it has read enough
to establish the vial is *not* in the catalog. So the sequence is identify
first, and on `NOT_FOUND` switch to harvesting fields. Only the first half of
that exists today (see *Known gaps*).

"Know when it has read every field" is not achievable and is not the goal for
the identify path. Absence of new text is indistinguishable from text not yet
rotated into view, so no rule can be built on it. What is observable is
discriminating power: `margin = top.score - runnerUp` in `Matcher.resolve`.
For the catalogue path completeness *is* the target, and there the right signal
is whether new tokens are still arriving (`framesSinceNewToken`).

## When a scan stops

Three paths, and only these:

| Path | Trigger | Category |
|---|---|---|
| Barcode early exit | barcode decoded and resolved | got it |
| Text early exit | CERTAIN or CONFIDENT, same answer twice | got it |
| Deadline | both grace extensions spent, or 12 s ceiling | timed out |

`maxOCRFrames` is **not** a stop path. It only gates whether OCR runs on a
given frame; a scan out of OCR budget coasts to the deadline decoding barcodes.

Timeout is 4.0 s plus two 2.5 s extensions, so about 9 s, hard-capped at 12 s.
The number is anchored to the physical task rather than chosen: a deliberate
full rotation of a vial in the hand is 3-5 s, and a wrapped label needs roughly
1.5 rotations to cover the overlap.

`evidenceLevel == SUFFICIENT` means retrievable fields were read, **not** that
they retrieved anything. Reading it as "I have an answer" is the bug fixed in
`da370b6`: a scan that read a catalog number absent from the definitions
stopped at 4.0 s with `NOT_FOUND` while the rest of the label was still hidden
around the curve of the vial. A deadline stop now requires CERTAIN or
CONFIDENT.

## Design decisions worth keeping

**Region of interest is the biggest single saving.** OCR cost scales with pixel
area and the outer band of the frame is floor, freezer and glove. Restricting
recognition to the centre — matching the on-screen reticle the user aims with —
took OCR from 70 ms to 33 ms on one video and 78 ms to 28 ms on another, with
text lines going 115→114 and 58→56. It is free accuracy-wise.

**Half resolution for OCR, full for barcodes.** The tiers want opposite things.
Barcode decode rate across full/half/quarter scale: 4/29 → 2/29 → 0/29. OCR
survives half but not quarter — quarter caused total failure on 2 of 5 vials.

**`.accurate` recognition, never `.fast`.** `.fast` still ranks the right vial
first but collapses scores into the non-match band, destroying the ability to
distinguish "detected" from "not detected".

**Cross-frame voting, not single-frame extraction.** Reading one regex hit from
one frame produced lot `04IS` for a lot that is really `F0412-S122Z`. Values are
merged across frames by OCR-confusable folding and prefix extension, so `S10` and
`S107` reinforce instead of competing.

**Near-exact catalog matches are gated twice.** This catalog contains 13 pairs of
catalog numbers exactly one edit apart, so a near-match proves nothing alone.
It must be (a) corroborated by clone or target — never vendor, since every vial
from a vendor shares it — and (b) differ somewhere other than the final
character, because vendors encode pack size there. Both gates come from observed
failures: IMG_4016 (`407101`→`408101`, clone contradicted) and IMG_4015
(`406601`→`406602`, same clone, different pack size).

**One-to-many OCR confusions.** `ocrFold` maps each confusable letter to one
digit (`o`->`0`, `s`->`5`, ...). That is a lie about the glyphs: `s` is equally
`5` and `8`, `1` is equally `l` and `7`, `b` is equally `8` and `6`. The fold
commits to one reading, so any candidate that needed the other reading is
unreachable. Real failure: a BioLegend `910801` read as `91080S`, which folds to
`910805` and can never converge on `910801`.

The fix is not a looser fold. Folding is a hash key, and a hash key has to be
single-valued or it stops being an index; widening it would loosen tier 1 and
tier 2, which are the tiers that produce uncorroborated `certain`/`confident`
verdicts. So the fold stays exactly as it is, and the one-to-many relation lives
beside it as `confusable(a, b)`: a bitmask per glyph-shape group, true when two
characters share a group. It is used in three places, all downstream of the
exact index:

- **Near-catalog distance** (`confusionDistance`) runs Levenshtein on a doubled
  cost scale: an ordinary edit costs 2, a substitution between confusable glyphs
  costs 1. The accept budget is still one ordinary edit, so recall is unchanged
  for arbitrary edits; what changes is ranking. Reading `91080S` against a
  catalog holding both `910805` and `910801` now prefers `910805` (cost 1) over
  `910801` (cost 2) instead of tying and refusing, and with only `910801` on
  file it still reaches it. Two confusable substitutions also fit the budget,
  which is the recall the fold used to hide.
- **Fold hits are checked for rivals.** A folded hit is only returned as an
  exact match if no *other* definition of the same length is also equal-or-
  confusable to the read. `91080S` against a catalog holding both `910805` and
  `910808` used to assert `910805` at `confident` with no corroboration; it now
  declines and falls through to the corroborated tiers.
- **The pack-size guard reads the trailing character's type.** A trailing
  difference normally means a different product (`406601` vs `406602`). But pack
  size is encoded in a *digit*, so a letter read over a digit on file is a
  misread, not a product, and that case is exempt from the guard. That is what
  lets `91080S` name `910801`.

Cost is unchanged in the hot path: `ocrFold`, `similarity` and the ~35k
per-resolve fuzzy comparisons are untouched. The confusion-aware distance
replaces a plain edit distance that already ran over the same 161 definitions,
and the rival check only walks the catalog on a folded hit, which is rare.
Verdicts across all 39 sample clips are byte-identical before and after.

**Inventory joins on the definition link, never on lot.** Lot `F0412-S122Z`
covers five different vials here. When a lot reads cleanly and matches none of
the vials on record, the result says so instead of naming one — a wrong vial
invites someone to draw from the wrong tube.

**Rejection is time-capped.** Without a cap, failure took longer than success: a
hit resolves in a median of 4 OCR passes while a miss would spend the entire
24-pass budget first.

## The app

Open it with:

```bash
brew install xcodegen        # once
xcodegen generate            # writes VialScanner.xcodeproj
open VialScanner.xcodeproj   # set a signing team, run on a device
```

Two targets: `VialCore` (framework) and `VialScanner` (app). The camera needs a
real phone; the simulator has no capture device.

What is in it:

* **Live viewfinder, always.** The session runs from launch and is never covered
  by a result — the card slides up beneath the aiming rectangle, so the next
  vial is lined up while the last one is still being decided. The rectangle is
  not decoration: recognition is restricted to exactly that region, so a reticle
  that lied would cost real scans.
* **Four actions on every result**: confirm and save, rescan, park for review
  keeping the video, and receive as a new vial. A parked scan *always* keeps its
  clip, whatever the clip toggle says — that is the whole point of parking.
* **Clips.** Every scan can be recorded from the same frames the engine reads,
  written straight to a file by `AVAssetWriter`. Clips start in the temporary
  directory and are only moved into Documents when someone keeps one; they age
  out on a schedule, except while a review is still open.
* **Hands-free mode.** Scans start on their own, results are spoken (AbNo digit
  by digit, then the box), confident reads commit after a visible countdown that
  any tap cancels, and anything below the bar is parked with its clip instead of
  stopping the run. The auto-commit bar is configurable between "barcode only"
  and "barcode or label text".
* **Confirm inventory.** Pick a box, a location, an owner or the lot, and the
  app builds the expected list. Every vial confirmed on the scan screen ticks
  itself off; items can also be ticked by hand and are recorded as such. Finish
  and you get found / missing / unexpected, exportable as CSV.
* **Import and export.** Airtable grid-view CSVs in (merge or replace, with the
  merge keeping vials received on the phone and their confirmation history),
  inventory / definitions / scan-log CSV and a full JSON backup out.
* **Inventory, review queue and history** are all browsable and searchable, and
  every scan keeps its verdict, its evidence and the reasons the engine gave.

Persistence is one JSON document written atomically after every mutation. At 161
definitions and ~140 vials that beats a database, and a document is also the
thing you can hand to somebody when the freezer room has no network.

## Wiring to Airtable

`Catalog.load` is the only place that knows about CSVs. Replace it with an
Airtable fetch that caches rows to disk; the engine takes a `Catalog` and does
not care where the rows came from. Keep the on-disk cache — the freezer room is
where the network is worst.

For writes, `ScanResult.verdict` is the gate: commit automatically only on
`.certain`, require a tap on `.confident`, never auto-create on `.ambiguous`, and
treat `lotNotOnRecord` as "receive a new lot" rather than "found the vial".

## Status

`VialCore` and the CLI compile and are validated against all 52 videos; set A
still resolves 5 of 5 in-database vials with zero false positives after the app
layer landed.

`App/Models.swift` and `App/Store.swift` — the whole data, import/export and
audit layer — are **type-checked** against the macOS SDK, since they touch only
Foundation and `VialCore`.

Everything that imports SwiftUI, UIKit or AVFoundation is **parse-checked
only**. There is no iOS SDK on this machine (Command Line Tools, no Xcode), so
the UI and camera layers have never been type-checked, built or run. Expect to
fix small API details on the first build in Xcode, and treat every number in
*Timing* as still unmeasured on a phone.

## Known gaps

- **Matching is still validated on 7 vials.** Set B was almost entirely absent
  from the database, so it exercised rejection, not matching. Abcam and CST are
  90 of the 161 definitions and remain effectively untested for matching.
- Thresholds (`minScore` 7.0, `minMargin` 2.5) separate cleanly on this data but
  are drawn from a small number of true positives.
- Extraction is still imperfect: truncated clones (`145-20` for `145-2C11`),
  truncated lots (`B227`), and five videos yielding no text.
- **Harvest mode does not exist.** On `NOT_FOUND` the engine keeps turning but
  is still trying to identify, not to collect fields for a new record. See
  *Two goals, opposite stopping rules*. The fields themselves are now read:
  size, concentration, expiry and storage temperature come off the label and
  pre-fill the receive form (expiry and storage land in the extra bag as
  `Expiration Date` and `Storage Temperature`). What is missing is a stopping
  rule that keeps turning until they are all present.
- **Partial catalog fragments are discarded.** `minLength[CATALOG] = 5` rejects
  a truncated read as an artefact, so a BioLegend label showing `Cat: 9108`
  before the digits rotate away contributes nothing. `partialCatalog` already
  does keep-at-low-weight for Leinco's `[A-Z][0-9]{2}` shape; bare-numeric
  catalog numbers have no equivalent.
- **Frame throughput, not sampling policy, is the live bottleneck.** A measured
  scan on a Pixel 5a took 8 frames in 11.84 s (0.68 fps) and ran OCR on 7 of
  them, so the sharpness gate was skipping nothing. Of a 2229 ms `ingest`, only
  311 ms was sharpness plus barcode plus OCR; the remaining ~1.9 s is the fuzzy
  matcher: `Matcher.resolve` tier 3 measures 660-1192 ms per frame on device
  (1-9 ms on the desktop JVM), and roughly four of nine resolves in a scan
  recompute a bit-identical answer.
  OCR also runs synchronously on the CameraX analyser thread, so frames
  arriving during a pass are dropped by backpressure.
- **Thermal throttling invalidates timing measurements.** A Pixel 5a reaches
  `Thermal Status: 4` (CRITICAL) after roughly 40 min of camera use and every
  stage runs 2-3x slower. Check
  `adb shell dumpsys thermalservice | grep "Thermal Status"` before trusting
  any number, and confirm a suspected regression against a stage you did not
  change.
- **A DataMatrix printed on the label still does not decode.** One is visible
  in a captured BioLegend crop at roughly 40-50 px square; barcode detection
  ran on five frames of that scan and decoded none. A decode is tier 1 and
  would settle identity in one frame, bypassing the fuzzy matcher entirely.
- No handling for the hand-applied AbNo dot labels, which would make
  already-received vials trivial to identify.
- iPhone timing is extrapolated, not measured.
- The UI and camera layers have not been compiled — see *Status*.
- Import expects the Airtable grid-view column names; a filtered or renamed
  export is rejected by name rather than guessed at.
- Writes are local. Getting them back into Airtable means exporting a CSV and
  importing it there; there is no API sync.
