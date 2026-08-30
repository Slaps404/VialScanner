# Android port

Same engine, same verdicts, different camera stack. `:vialcore` is a straight
port of `Sources/VialCore` with one deliberate structural change, and `:app` is
the Android-specific shell around it.

```
vialcore/   Pure Kotlin/JVM. No Android SDK, no ML Kit, no CameraX.
            Text.kt  Catalog.kt  FieldExtraction.kt  Evidence.kt
            Matcher.kt  ScanEngine.kt
app/        CameraX + ML Kit + Compose. MlKitFrame.kt is the entire
            platform surface; MainActivity.kt is camera plumbing and the screen.
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

## Deliberate divergences

Swift iterates `Set`s and sorts unstably in `bestFuzzy`, in `vote()`'s repair
passes, and in `Matcher`'s ranking, so two candidates with equal scores can swap
between runs on identical evidence. Kotlin sorts deterministically in all three.
These are the only differences a parity run should ever surface.

## Build status

`:vialcore` compiles and passes the parity replay on this machine (kotlinc 2.2.20,
JDK 25). `:app` has **never been built**: this machine has no Android SDK and no
Gradle. Expect first-build fixups in Android Studio, particularly around the
CameraX and Compose versions pinned in `app/build.gradle.kts`.

Not ported yet: persistence, inventory browsing, review queue, audits, the scan
log and the session box. The Android app scans and reports; the iOS app is still
the one that writes anything down.
