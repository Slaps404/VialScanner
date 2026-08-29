# AGENTS.md

Guidance for Claude Code (and other agents) working in this repository.

For architecture and the module map, see **[CODEMAP.md](CODEMAP.md)** — read
that before making non-trivial changes, it's the fast path to understanding
how the pieces connect.

## Commands

```bash
./build-cli.sh              # build the CLI (swiftc directly, not SwiftPM — see CODEMAP.md)
./vialscan videos/ --defs "Antibody Definitions.csv" --inv "Inventory Items.csv"
./vialscan videos/ --fps 6 --roi 0.15   # sampling rate and centre-crop inset

brew install xcodegen        # once
xcodegen generate            # writes VialScanner.xcodeproj from project.yml
open VialScanner.xcodeproj   # set a signing team, run on a real device (no simulator camera)
```

There is no test suite and no lint config in this repo. The CLI run against
`videos/` (52 sample videos, not checked in) is the closest thing to a
regression check for `VialCore` changes — rerun it after touching
`Sources/VialCore/*` and compare verdicts against README's results tables.

This dev machine has CommandLineTools only, no Xcode/iOS SDK: `App/` code that
imports SwiftUI/UIKit/AVFoundation cannot be built or run here (see CODEMAP.md
"Build status"). Don't claim such a change compiles without it having actually
been built in Xcode.

## Repo-specific notes

- `Sources/VialCore` must stay UIKit/SwiftUI/AVFoundation-free — that's what
  lets the CLI exercise the engine without a phone.
- `Catalog.load` is the only place that knows the CSV format; keep it that way
  if you touch catalog loading.
- `ScanResult.verdict` is the single gate for what the app auto-commits vs.
  requires confirmation for — see README "Wiring to Airtable" for the exact
  per-verdict rule before changing commit behavior.
- Ignore `Vials/` and `Vyals.xcodeproj` — stale unused scaffold, not the real
  app (see CODEMAP.md).

See README.md for full validation results, thresholds, and known gaps.
