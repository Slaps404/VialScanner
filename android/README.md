# Android port

Same engine, same verdicts, different camera stack. `:vialcore` is a straight
port of `Sources/VialCore` with one deliberate structural change, and `:app` is
the Android-specific shell around it.

```
vialcore/   Pure Kotlin/JVM. No Android SDK, no ML Kit, no CameraX.
            Text.kt  Catalog.kt  FieldExtraction.kt  Evidence.kt
            Matcher.kt  ScanEngine.kt
app/        CameraX + ML Kit + Compose.
            MlKitFrame.kt      the entire platform surface (OCR, barcodes, sharpness)
            ScanController.kt  camera binding, frame pacing, torch
            Models.kt Store.kt persistence: one JSON doc, imports, exports
            MainActivity.kt    tabbed shell; ScanScreen/Screens/Sheets/Common the UI
```

## The one structural change from Swift

`ScanEngine.swift` owns its `VNRecognizeTextRequest` and `VNDetectBarcodesRequest`,
so it imports Vision and the "platform-free engine" boundary leaks. Here the two
expensive passes sit behind the `VialFrame` interface instead:

```kotlin
interface VialFrame {
    val timestamp: Double
    val sharpness: Double
    fun detectBarcodes(roi: NormRect?): List<BarcodePayload>
    fun recognizeText(roi: NormRect?): List<TextObservation>
}
```

The app implements it with ML Kit over a CameraX frame; the parity harness
implements it with recorded observations. Neither changes a line of the pacing
logic.

## Coordinates: the one thing a transliteration gets wrong

Vision reports boxes normalised with the origin **bottom-left**. ML Kit reports
pixels with the origin **top-left**. `NormRect` keeps Vision's convention, and
`MlKitFrame.recognizeText` does the flip once. Getting this wrong does not
crash - it silently inverts "below", which is exactly what
`FieldExtraction.neighbours` uses to attach a value to a dangling label.

## Parity against the Swift engine

Running ML Kit over the sample videos would compare Apple's OCR to Google's, and
every disagreement would be unattributable. So the Swift CLI records the exact
observation stream that fed its evidence buffer, and the Kotlin side replays it:

```bash
./build-cli.sh
./vialscan ~/Downloads/"Vial vids" --defs Resources/definitions.csv \
    --inv Resources/inventory.csv --fixtures /tmp/tapes

kotlinc android/vialcore/src/main/kotlin/org/lji/vialcore/*.kt \
        android/vialcore/src/test/kotlin/org/lji/vialcore/*.kt \
        -include-runtime -d /tmp/parity.jar
java -cp /tmp/parity.jar org.lji.vialcore.ReplayParityKt \
        /tmp/tapes Resources/definitions.csv Resources/inventory.csv
```

Current result: **43/43 tapes match** - identical verdict, definition, vial, lot
status, evidence level, score and margin.

What this does *not* cover: `ScanEngine` pacing. A tape holds only the frames
Swift's sharpness gate let through, so the skipped frames have nothing to
replay. Pacing is the untested seam and needs synthetic frames.

## Replaying whole videos on an emulator (the other half of the test)

The tape replay holds OCR constant, which is what makes it a parity test and
also what makes it blind to Google's OCR and to `ScanEngine`'s pacing.
`app/src/androidTest/.../VideoReplayTest.kt` covers the rest: frames extracted
on the host at 6 fps go through `MlKitFrame` exactly as CameraX would feed them,
and it prints a verdict per clip. No camera, no phone.

```bash
# frames out of the recordings, 6 fps, native width
for f in Videos/pixel/*.mp4; do n=$(basename "$f" .mp4)
  ffmpeg -v error -i "$f" -vf "fps=6,scale=1080:-2" -q:v 3 "/tmp/frames/${n}_%03d.jpg" -y
done

$ANDROID_HOME/emulator/emulator -avd vialtest -no-window -no-audio -gpu swiftshader_indirect &
adb push /tmp/frames/. /sdcard/Android/media/org.lji.vialscanner/frames/
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e frames frames -e ocrLongEdge 1080 \
    -e class org.lji.vialscanner.VideoReplayTest \
    org.lji.vialscanner.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/media/org.lji.vialscanner/replay-frames-1080.txt
```

Frames must go in the *media* dir: under scoped storage an app cannot read what
adb pushed into its own data dir.

Where the two engines stand on identical footage (19 iPhone clips, same catalog):
same verdict class on 18. On 20 Pixel clips Swift lands 3 hits to Android's 1;
one miss is a clip where the sharpness gate passed only 2 of 12 frames to OCR,
which is a pacing question rather than an OCR one. Most clips are `NOT_FOUND` on
both engines because the vials in them are not in the shipped catalog.

## Deliberate divergences

Swift iterates `Set`s and sorts unstably in `bestFuzzy`, in `vote()`'s repair
passes, and in `Matcher`'s ranking, so two candidates with equal scores can swap
between runs on identical evidence. Kotlin sorts deterministically in all three.
These are the only differences a parity run should ever surface.

## Building

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or set ANDROID_HOME
JAVA_HOME=/opt/homebrew/opt/openjdk@21/... ./gradlew assembleDebug
```

The wrapper is pinned to Gradle 8.10.2 because AGP 8.7.3 supports neither the
Gradle 9.x nor the JDK 26 that Homebrew installs by default. JDK 21 works;
the modules target 17 bytecode without demanding a JDK 17 toolchain.

## Unicode traps (found by review, now fixed)

Java's regex classes (`\b`, `\w`, `\s`) are ASCII by default; ICU's, which
Swift uses, are Unicode-aware. Untreated, a `µ` glued to a part number made
`BE0106µ` yield a catalog number on Android and nothing on iOS, and a
non-breaking space after `LOT` dropped that read from weight 1.0 to 0.6. Every
pattern here compiles through `urx()` with `UNICODE_CHARACTER_CLASS`. In the
same family: `normKey`/`tokenize` now keep combining marks and non-decimal
numerals (Swift's `CharacterSet.alphanumerics` does), the edit distances count
grapheme clusters rather than UTF-16 units, and `looksLikeYearMonth` drops empty
split fields so a smudged `2020--11` is still read as an expiry.

## Build status

`:vialcore` compiles and passes the parity replay on this machine.
`:app` assembles (`assembleDebug`) and passes `lintDebug`, but has **never run on
a device** - nothing here has seen a camera. Every screen is unQA'd.

Ported from iOS: scanning, persistence, inventory browsing, review queue, audits,
scan log, session box, CSV/JSON import and export, settings. Not ported: clip
recording (the per-scan video kept with a parked result) and speech feedback.

## The sharpness gate was mis-scaled (found by video replay, now fixed)

`EngineConfig.sharpnessRatio = 0.45` skips any frame scoring below 45% of the
best focus seen so far. That constant is only meaningful next to the thumbnail
the focus measure runs on: a harder downscale averages away high-frequency
detail, which pulls a crisp frame and a blurred one closer together, and a
softer one spreads them apart. Swift measures on a 160px thumbnail. The Kotlin
port used 240px and inherited the constant, so the same 0.45 threw away frames
Swift keeps:

| clip | frames reaching OCR at 240px | at 160px |
|---|---|---|
| PXL_20260830_202813337 | 7 / 12 | 12 / 12 |
| PXL_20260830_202545311 | 35 / 52 | 52 / 52 |

On `202813337` the crisp early frames show only the DataMatrix; the catalog
number is on a blurrier later face, and the gate locked exactly that face out.
Android returned NOT_FOUND where Swift returned CONFIDENT. With the thumbnail
matched to Swift's 160px it returns CONFIDENT `14-0808-82`, lot `2829436`,
LOT-NOT-ON-RECORD, which is Swift's answer field for field.

The lesson generalises: a threshold expressed as a fraction of a measurement is
only portable together with how the measurement is scaled.

## Where the two engines still disagree

Over the 39 sample recordings (20 Pixel, 19 iPhone) the engines return the same
verdict class on 36. All three exceptions are Apple Vision reading small text
better than ML Kit on the same pixels, and all three fall on the safe side -
Android is *less* confident, never confidently wrong:

| clip | Swift | Android | ML Kit's read |
|---|---|---|---|
| PXL_20260830_202456307 | CONFIDENT 10.0 | AMBIGUOUS 6.0 | `24510SF` yes, clone `E5I9V` never |
| PXL_20260830_202701348 | AMBIGUOUS 6.0 | NOT_FOUND 3.9 | `46173SE` for `46173SF` |
| IMG_4070 | CONFIDENT 11.6 | AMBIGUOUS 6.5 | `AB186`, `AB186921`, `8186928` for `ab186928` |

Use `-e only <substring>` and `-e dumpText true` to see this yourself: the
harness then prints every line ML Kit returned per frame, which is what
separates "OCR never saw it" from "extraction rejected what OCR saw".
