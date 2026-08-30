// Plain Kotlin/JVM on purpose. `vialcore` must not depend on the Android SDK,
// ML Kit or CameraX: that is what lets it be compiled and replayed against
// recorded observations on a laptop, exactly as the Swift VialCore is exercised
// by the offline CLI. OCR and barcode decoding enter through `VialFrame`.
plugins { id("org.jetbrains.kotlin.jvm") }

// Target 17 bytecode with whatever JDK is running the build, rather than
// demanding a JDK 17 toolchain be installed: the app module compiles to 17 too,
// and requiring an exact JDK on every dev machine buys nothing here.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main { kotlin.srcDir("src/main/kotlin") }
    test { kotlin.srcDir("src/test/kotlin") }
}
