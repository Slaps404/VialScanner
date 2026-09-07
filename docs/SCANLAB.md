# ScanLab debug bundles

`scanlab` is the host-side red/green loop for a real phone scan. It validates a
versioned bundle, checks the operator's ground truth against the machine result,
and reports the first likely failing layer.

Run the same command after every Pixel or iPhone scan:

```bash
./scripts/scanlab check artifacts/pixel-20260903T120000Z
```

For a tray of scans rather than one, use `review`, which reads every bundle
under a directory in whatever format it arrived and ranks what broke. See
"Batch review" below.

The command prints a concise report and exits `0` only when the bundle is
structurally valid and its expected values agree with the result. It exits `1`
for missing schema, missing ground truth, invalid trace data, incomplete
catalogue rotation, or an expected-versus-actual mismatch. `--json` emits the
same result for a script or CI job.

The fixture loop is:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

Only Python 3.10 or newer and its standard library are needed. No pip package,
Swift build, Android SDK, or network connection is required for `check`.

## Bundle layout

Each bundle is a directory with a required `summary.json`, a required JSONL
trace, and optional evidence artifacts:

```text
bundle/
  summary.json
  trace.jsonl
  observations.tape       # optional Swift/Kotlin pipeline replay
  images/                  # optional exact recognizer inputs
  scan.mp4                 # optional human-review video
```

The summary starts with the version fence:

```json
{
  "schema": "scanlab.bundle",
  "schemaVersion": 1,
  "bundleId": "pixel-20260903-001",
  "createdAt": "2026-09-03T12:00:00Z",
  "platform": "android",
  "goal": "catalogue",
  "groundTruth": {},
  "actual": {},
  "capture": {},
  "artifacts": {"trace": "trace.jsonl"}
}
```

`artifacts` paths are relative to the bundle and cannot escape it. The checker
refuses a missing, absolute, unsafe, or wrong-type path instead of silently
producing a partial report. The trace and observations artifacts are files;
`images` is a directory when present.

## Phone writer contract

The phone writer must export this canonical shape for `scanlab check` to read
it. Native iOS or Android JSON files are not interchangeable with this
contract until they are translated; `scanlab translate` does that, and
"Translating phone output" below describes what it maps.

The bundle directory must contain:

- `summary.json`, one object with `schema: "scanlab.bundle"` and
  `schemaVersion: 1`.
- `trace.jsonl`, one JSON object per line. Frame events use ascending,
  non-negative `index` values, a numeric `timestamp`, and a boolean `usable`.
  OCR attempts are recorded as `ocr.ran` with optional `ocr.lines`; extracted
  values are under `fields`, and barcodes under `barcode` or `barcodes`.
- optional `observations.tape`, `images/`, and `video` artifacts referenced by
  relative paths in `summary.artifacts`.

`summary` also requires non-empty `bundleId`, `createdAt`, `platform`, and
`goal`, object values for `groundTruth`, `actual`, `capture`, and
`artifacts`, matching `capture.framesSeen`, `capture.usableFrames`, and
`capture.ocrFrames` counters, and `capture.rotation.required` plus a coverage
fraction. `groundTruth` must identify the scan mode and the values to check.
The checker accepts `platform` values such as `android` and `ios`, and the
goals `identify` or `catalogue`; the values are retained in the report.

## Ground truth and stopping rules

Ground truth is written before or immediately after the physical scan. It is
not inferred from the machine result.

For a known inventory vial, use `mode: "known_vial"` and provide `abNo` or
`catalog`, plus any identifiers that were verified. If `requiredFields` is
omitted, every declared label field is checked, including the identity field.
For a known antibody where the specific inventory vial is not known, use
`mode: "known_antibody"`.

For a vial being catalogued, use `mode: "new_vial"`. `requiredFields` names the
fields transcribed from the label, and `fields` gives their values. Use JSON
`null` only when the field was explicitly checked and is not present on the
label. A blank string means the ground truth was not collected and fails the
check.

The usual label field names are `catalog`, `lot`, `clone`, `vendor`,
`concentration`, `volume`, `expiry`, `storage`, `isotype`, `target`, and `host`.
The checker also accepts the app's spelling such as `Catalog#`, `Initial
Volume`, `Expiration Date`, and `Storage Temperature`.

Catalogue bundles must declare `capture.rotation.required: true`, a
`capture.rotation.coverage` fraction, and at least 85% coverage. This keeps a
new-vial run from passing after only the first readable face. Known-vial runs
may stop early once the identity is corroborated, but recording the coverage
still makes a premature stop visible.

## Trace events

The trace is one JSON object per line. A frame event has an index, numeric
timestamp, and a usable flag:

```json
{"event":"frame","index":12,"timestamp":3.20,"usable":true,
 "ocr":{"ran":true,"lines":["LOT B411234"],"durationMs":34},
 "fields":{"lot":"B411234"}}
```

The checker counts frame, usable-frame, OCR, text-line, barcode, skip-reason,
and stage timing evidence. It compares the first three counts with
`capture.framesSeen`, `capture.usableFrames`, and `capture.ocrFrames`; a stale
summary is an integrity failure rather than a green run.

`capture.mode: "accuracy"` requires an `images` directory and at least one
image for every usable frame. This is the mode to use while finding an OCR or
extraction bug. `capture.mode: "performance"` should keep image encoding and
per-frame disk I/O out of the timing path; including images is allowed but
reported as a warning.

## Layer routing

The report is intentionally not a single accuracy number:

| Layer | Typical evidence | Meaning |
| --- | --- | --- |
| `schema` | malformed JSON, missing truth, missing trace | The bundle cannot be trusted yet |
| `ground-truth` | missing mode, identity, or required field | The operator's expected label record is incomplete |
| `capture` | no usable frames, missing images, incomplete rotation | Camera, focus, framing, pacing, or operator coverage |
| `recognizer` | OCR ran but the expected label text never appeared | Vision or ML Kit did not read the pixels |
| `extraction` | expected text appears in the trace but the field is absent/wrong | Label parsing or field association |
| `matching` | expected fields are present but verdict or identity is wrong | Evidence voting, catalog, or matcher |
| `feedback` | a manual correction is recorded | Review context, not automatically a scanner failure |
| `integrity` | summary counters or tape result disagree | Collection/writer bug |
| `performance` | stage timings or thermal context are absent | Timing is not yet comparable |

This is routing evidence, not proof. Open the saved image or video before
changing an algorithm.

## Review corrections

If a reviewer corrects a saved value, record the machine value, final saved
value, provenance, whether the value was visible on the label, and the review
reason. A field-keyed map or a list is accepted:

```json
{
  "actual": {"fields": {"lot": "B411999"}, "corrections": {
    "lot": {
      "original": "B411999",
      "final": "B411234",
      "provenance": "label_transcription",
      "visibleOnLabel": true,
      "reason": "The lot was legible after the clip was reviewed"
    }
  }}
}
```

When the final value is correct and `visibleOnLabel` is `true`, ScanLab reports
a scanner miss and fails the run. When it is `false`, ScanLab records feedback
but does not count the correction as an OCR miss. This distinguishes a value
recovered from the label from a value supplied by inventory lookup or another
source. Incomplete correction records fail schema validation so the flag
cannot be silently guessed. The `original` value must also agree with the
machine value in `actual`, so a review record cannot hide which value the
scanner produced.

## Translating phone output

Both apps write their own native shape, each with a full per-frame trace:

- iOS: one JSON file per kept clip, schema `vial-scanner.scan-debug`, written
  by `Store.writeClipSidecar` next to the clip.
- Android: one directory per scan holding `bundle.json`, format
  `org.lji.vialscanner.debug`, under the app's external media area.

Neither is a `scanlab.bundle`, so translate first:

```bash
./scripts/scanlab translate artifacts/ios/scan-0001.json --out /tmp/bundle
./scripts/scanlab translate artifacts/pixel/scan-0007  --out /tmp/bundle
./scripts/scanlab check /tmp/bundle
```

`translate` refuses an existing destination and never writes to the source.
Two translation details matter:

- `capture.framesSeen`, `usableFrames`, and `ocrFrames` are derived from the
  trace being written, not copied from the phone's engine counters, which count
  ingested frames rather than captured ones. The phone's own numbers are kept
  under `config`, so the integrity check compares like with like without losing
  them.
- Ground truth comes from the operator's clip note (`cat=`, `lot=`, `clone=`,
  `expect=`). A blank note produces a bundle with no `groundTruth`, which the
  checker refuses to judge rather than passing by default.

`check --pixel` still reads the native Android bundle directly and requires
`--truth` with independently verified values. Use it when the truth must come
from outside the phone; use `translate` when the operator's note is the truth.

## Batch review

After a tray of scans, review them all in one command:

```bash
./scripts/scanlab review artifacts/pixel-20260903T120000Z
```

`review` walks the directory, dispatches each scan by its schema (canonical
bundle, native Android directory, or iOS sidecar file), translates the native
ones into a temporary directory, and runs the same per-bundle checks. It prints
one row per scan and then ranks the failure modes:

```text
ScanLab review FAIL: 6 scans (3 correct, 2 wrong_result, 0 unresolved, 0 invalid, 1 unannotated)

  PASS  ios-fixture-known       ios     identify  -           cat=910801 lot=B411234 expect=confident
  FAIL  ios-fixture-miss        ios     identify  extraction  lot never made it into the result
  NOTE? ios-fixture-unannotated ios     identify  -

Top failure modes:
  1x [extraction] field-mismatch
  1x [extraction] scanner-miss
```

The layer column names the first failing layer, so the tally says which layer
to fix first instead of leaving it to be inferred from thirty separate reports.
`--only-fail` hides passing scans and `--json` emits the same result with per
scan issues and a ranked `failureModes` array.

Three row kinds are listed but never counted as failures, because none of them
is a checkable scanner result:

- `NOTE!` is a scan whose note says something the DSL cannot read, which is
  what an operator writing "ended before full rotation" produces. It is a real
  complaint with no checkable expectation, so the note is printed in full and
  read first. Filing these as un-annotated buries the only content they have.
- `NOTE?` is a scan with no note at all, so there is nothing to judge it
  against. Listed so a forgotten annotation stays visible.
- `SKIP` is a capture that never finished: an aborted scan, or one that
  recorded no frames. A tray pulled off a phone normally contains a few.

`review` exits `1` only when an annotated, finished scan comes back
`wrong_result`, `unresolved`, or `invalid`.

Two rules follow from the ground-truth contract above:

- A catalogue scan needs the fields, not just the verdict. `expect=newVial`
  alone reports a ground-truth error, because `new_vial` truth must name the
  fields that should have been captured. Write
  `cat=910801 lot=B411234 expect=newVial`.
- A known-vial note needs `cat=` (or an AbNo); `lot=` alone cannot identify
  what should have matched.

`review` is the loose, note-driven loop. `corpus` remains the strict one: it
takes a manifest with an external truth file per case and development/holdout
splits, and is what timing and accuracy claims should come from.

## Tapes

The optional `observations.tape` parser understands the existing Swift format:
`F`, `T`, `B`, and terminal `R` records. It checks that the tape has evidence,
has one result record, and agrees with `summary.actual` for verdict, catalog,
and AbNo. A tape is a pipeline-parity artifact, not a substitute for camera
images or ground truth.

## Pulling Pixel data safely

When the Android writer stores bundles under the app's external media area,
use:

```bash
./scripts/scanlab pull-pixel artifacts/pixel-20260903T120000Z \
  --remote /sdcard/Android/media/org.lji.vialscanner/debug-bundles
```

The helper checks that exactly one ADB device is online and reads
`dumpsys thermalservice` before pulling. Nonzero thermal status is printed as a
timing warning; `--require-nominal` refuses the pull unless it is zero. The
destination must not already exist, the pull happens in a temporary directory,
and the helper never deletes or writes device data. It writes a local
`pull.json` with the serial, thermal status, remote path, and pull time.

If several devices are connected, pass `--serial SERIAL`. If the app uses a
different external path, pass its absolute path with `--remote PATH`; the
default is deliberately scoped to `debug-bundles` rather than pulling the
whole phone media directory.
