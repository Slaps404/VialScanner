# ScanLab debug bundles

`scanlab` is the host-side red/green loop for a real phone scan. It validates a
versioned bundle, checks the operator's ground truth against the machine result,
and reports the first likely failing layer.

Run the same command after every Pixel or iPhone scan:

```bash
./scripts/scanlab check artifacts/pixel-20260903T120000Z
```

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
contract unless they are translated while being copied off the phone.

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

## Existing sidecars and tapes

The current iOS sidecar contains only the clip name, note, and parsed expectation.
The current Android sidecar contains a richer result and capture record, but
neither sidecar is a `scanlab.bundle`: neither has the versioned summary plus
per-frame trace. They remain useful evidence and should be copied into a new
bundle as supporting artifacts once the phone writer emits the v1 fields.

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
