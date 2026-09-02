# AGENTS.md

Guidance for Claude Code (and other agents) working in this repository.

For architecture and the module map, see **[CODEMAP.md](CODEMAP.md)** — read
that before making non-trivial changes, it's the fast path to understanding
how the pieces connect.

## Commands

```bash
./build-cli.sh              # build the CLI (swiftc directly, not SwiftPM — see CODEMAP.md)
./vialscan Videos/pixel --defs Resources/definitions.csv --inv Resources/inventory.csv
./vialscan Videos/pixel --fps 6 --roi 0.15   # sampling rate and centre-crop inset
```

There is no test suite and no lint config in this repo. The CLI run against
`Videos/` (not checked in; currently 39 clips, 20 under `Videos/pixel` and 19
under `Videos/iCloud Photos` - README's results tables predate this and were
run against a 52-clip corpus) is the closest thing to a regression check for
`VialCore` changes: rerun it after touching `Sources/VialCore/*` and compare
verdicts against README's results tables.

### Building the iOS app

Xcode (26.6, iOS 26.5 SDK) *is* installed on this machine and `App/` builds
clean. Two ways, fastest first:

```bash
# 1. Type-check/compile the sources without a project file. VialCore is a
#    module, so emit it first or App/*.swift can't find it.
SDK=$(xcrun --sdk iphoneos --show-sdk-path)
xcrun swiftc -sdk "$SDK" -target arm64-apple-ios17.0 -emit-module \
  -module-name VialCore -emit-module-path /tmp/ios/VialCore.swiftmodule \
  Sources/VialCore/*.swift
xcrun swiftc -sdk "$SDK" -target arm64-apple-ios17.0 -I /tmp/ios \
  -module-name Vyals -wmo -c App/*.swift -o /tmp/ios/Vyals.o   # -wmo: one object

# 2. Full app bundle. NOTE: project.yml is `name: Vyals`, so a bare
#    `xcodegen generate` OVERWRITES the tracked, stale Vyals.xcodeproj.
#    Generate out of tree instead.
xcodegen generate --spec project.yml --project /tmp/iosproj \
  --project-root "$PWD"
xcodebuild -project /tmp/iosproj/Vyals.xcodeproj -scheme Vyals \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

A device build still needs a signing team, and the camera needs real hardware
(no simulator camera). Don't claim an `App/` change compiles without having
actually run one of the above.

## Repo-specific notes

- `Sources/VialCore` must stay UIKit/SwiftUI/AVFoundation-free — that's what
  lets the CLI exercise the engine without a phone.
- `Catalog.load` is the only place that knows the CSV format; keep it that way
  if you touch catalog loading.
- The scanner has **two goals with opposite stopping rules**: identify a known
  vial (stop as soon as the margin clears) and catalogue a new one (capture
  every field). Read README "Two goals, opposite stopping rules" and "When a
  scan stops" before changing pacing, deadlines or field extraction.
- Before trusting any on-device timing, check
  `adb shell dumpsys thermalservice | grep "Thermal Status"`. A throttled Pixel
  runs 2-3x slower and has produced false regressions here.
- `ScanResult.verdict` is the single gate for what the app auto-commits vs.
  requires confirmation for — see README "Wiring to Airtable" for the exact
  per-verdict rule before changing commit behavior.
- Ignore `Vials/` and the checked-in `Vyals.xcodeproj`: stale unused scaffold,
  not the real app (see CODEMAP.md). It shares a name with what `project.yml`
  generates, which is why iOS project generation goes out of tree (above). If
  you clobber it, `git checkout -- Vyals.xcodeproj`.
- Android build, replay tests and emulator QA commands live in
  `android/README.md`.

See README.md for full validation results, thresholds, and known gaps.

## Style

- Keep code comments short. A comment earns its place by saying *why*, in a
  line or two. If a decision needs paragraphs of justification, it belongs in
  README.md or CODEMAP.md with a one-line pointer from the code, not inlined.
- No em-dashes in code comments or docs.
