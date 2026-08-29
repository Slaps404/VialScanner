# Codemap

On-device vial scanner: point an iPhone camera at a vial, get its inventory
record. Offline, no network calls, no backend.

## Module boundary

```
Sources/VialCore/   Engine. No UIKit/SwiftUI/AVFoundation import — pure logic,
                    shared by the iOS app target and the CLI target.
Sources/vialscan/   CLI harness that replays .MOV files through VialCore
                    (offline validation against sample videos, no camera).
App/                iOS app (SwiftUI + AVFoundation). Depends on VialCore.
Resources/          definitions.csv + inventory.csv — the shipped catalog snapshot.
```

`VialCore` is the seam: anything engine-related goes there, is UIKit-free, and
is testable/runnable from the CLI without a phone. `App/` is orchestration,
persistence and UI on top of it.

## VialCore pipeline

Frame in → evidence out → match → verdict. Read in this order:

1. **Frame.swift** — pixel-buffer handling, downscaling, sharpness gate. Decides
   whether a frame is even worth processing.
2. **FieldExtraction.swift** — label regexes, value plausibility checks, barcode
   (DataMatrix/Code128) parsing. Turns one frame's OCR/barcode output into
   candidate fields (AbNo, clone, catalog #, lot).
3. **Evidence.swift** — accumulates candidate fields *across* frames and votes,
   since no single frame is trusted alone.
4. **Catalog.swift** — loads definitions.csv + inventory.csv, indexes them, and
   joins a definition to an inventory row.
5. **Matcher.swift** — tiered resolution against the catalog (barcode match >
   label match > partial), scoring and confidence. Produces the verdict:
   `.certain` / `.confident` / `.ambiguous` / `lotNotOnRecord` / new-vial.
6. **ScanEngine.swift** — orchestrates 1-5: pacing (how often to sample frames),
   early exit once confidence is high enough, the per-scan pass budget.

`Sources/vialscan/main.swift` drives this same pipeline over recorded video
instead of a live camera feed — it's the fast way to check a change against the
52 sample videos without touching the app.

## App layer

```
VialScannerApp.swift   Entry point.
RootView.swift         Drawer shell and routing between the tabs below.
ScanView.swift         Viewfinder, hands-free loop, commit actions — the main screen.
CameraController.swift Capture session, frame pacing, torch, tap-to-focus.
CameraPreview.swift    Preview layer + aiming rectangle (recognition is
                       restricted to this rectangle, not decorative).
ClipRecorder.swift     Per-scan video, written from the same frames the engine reads.
ResultCard.swift       The verdict and the four actions available on it.
Store.swift            Persistence (one atomic JSON doc), import/export, audits, scan log.
Models.swift           Codable mirrors of VialCore's records + app settings.
InventoryView.swift    Browse/search/edit/receive inventory.
ReviewView.swift       Parked scans (below-confidence results), with their clip.
AuditView.swift        Confirm inventory: box/location/owner checks.
HistoryView.swift      Every scan, filterable, with its evidence.
DataView.swift         CSV import, CSV/JSON export, clip storage.
SettingsView.swift     Hands-free mode, clip retention, receiving defaults, engine knobs.
Theme.swift / Components.swift / Feedback.swift   Design system, shared widgets, speech+haptics.
```

Four actions on every scan result: confirm & save, rescan, park for review
(keeps the clip regardless of the clip setting), or receive as a new vial.
`ScanResult.verdict` is the gate for all of them — see README "Wiring to
Airtable" for the exact rule per verdict.

## Data flow / persistence

- Every column of the source row rides along in a `FieldBag` on
  `AntibodyDefinition.extra` / `InventoryItem.extra`, addressable by its printed
  header. The named stored properties are only what the *engine* matches on;
  category, conjugate, formulation, amounts, prices and links are bag lookups
  behind named accessors (`vial.category`, `def.price`). A column added to the
  Airtable base appears in the app with no code change.
- Catalog (definitions + inventory) loads from CSV via `Catalog.load` — that's
  the only place that knows about CSV as a format. Swapping in a live source
  (e.g. Airtable) means replacing that one function; `ScanEngine`/`Matcher`
  just consume a `Catalog`.
- App state (inventory, scan log, review queue, audits) is one JSON document,
  written atomically after every mutation. No database.
- Writes are local only — there is no sync back to Airtable; round-tripping
  means exporting CSV and re-importing there.

## Build surfaces — don't confuse these

- **`App/` + `project.yml`** is the real iOS app. `project.yml` is an XcodeGen
  spec (bundle id `com.lab.vyals`) that generates `VialScanner.xcodeproj`.
- **`Vials/` + `Vyals.xcodeproj`** is an unrelated, unused default Xcode
  template scaffold (SwiftData `Item` model, no VialCore dependency) left over
  from initial setup. Do not add app features there.
- **`Package.swift`** describes `VialCore` + `vialscan` for SwiftPM/Xcode, but
  `build-cli.sh` compiles the CLI directly with `swiftc` because SwiftPM's
  manifest step needs a full Xcode install (CommandLineTools alone isn't
  enough).

## Build status (know before you assume something compiles)

- `VialCore` + `vialscan` CLI: compiles and is validated against all 52 sample
  videos.
- `App/Models.swift`, `App/Store.swift`: type-checked against the macOS SDK
  (they only import Foundation + VialCore).
- Everything importing SwiftUI/UIKit/AVFoundation (the rest of `App/`): parse-
  checked only, never built or run — this dev machine has no iOS SDK (no
  Xcode, CommandLineTools only). Expect first-build fixups in Xcode.

See README.md for full validation results, thresholds, and known gaps.
