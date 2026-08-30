// Plain Kotlin/JVM on purpose. `vialcore` must not depend on the Android SDK,
// ML Kit or CameraX: that is what lets it be compiled and replayed against
// recorded observations on a laptop, exactly as the Swift VialCore is exercised
// by the offline CLI. OCR and barcode decoding enter through `VialFrame`.
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

sourceSets {
    main { kotlin.srcDir("src/main/kotlin") }
    test { kotlin.srcDir("src/test/kotlin") }
}
