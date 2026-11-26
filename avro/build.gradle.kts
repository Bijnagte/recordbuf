import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    // Activates the default Error Prone check set; NullAway is added via the
    // `errorprone` configuration below (mirrors :recordbuf-runtime).
    alias(libs.plugins.gradle.errorprone)
}

group = "io.github.bijnagte.recordbuf"

java {
    // Pin the JDK via toolchain, matching the runtime/generator builds (Temurin 25).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Avro is the alternative-wire-format bridge's runtime dependency: the
    // :recordbuf-avro WKT conversions and every generated XAvro/XSpecific call
    // org.apache.avro.* (Schema, GenericData, DatumReader/Writer, ...), so it
    // flows api to the fixture module and the generated output (R3).
    api(libs.avro)

    // The WKT bridge reuses runtime helpers (UnknownFields / Decoder / Encoder)
    // and the runtime is null-free-by-construction; it flows api so generated
    // code that references bijnagte.recordbuf.runtime.* compiles.
    api(project(":recordbuf-runtime"))

    // The bridge's own code and its @NullMarked package-info need JSpecify on the
    // build classpath; generated output reaches it transitively.
    api(libs.jspecify)

    // error-prone-core loads Error Prone, nullaway is the nullness check.
    errorprone(libs.nullaway)
    errorprone(libs.error.prone.core)

    // The bridge's WKT conversions are pinned in isolation by their own JUnit
    // tests (AC3 + the WKT-oracle test) without the generator or a full .rec
    // fixture.
    testImplementation(libs.junit.jupiter)
    // Avro's own schema->SpecificRecord generator (org.apache.avro.compiler.specific.SpecificCompiler),
    // on the TEST/generation classpath only: it is how the per-file .avsc is turned into the consumed
    // specific record (option B, KTD6 delegation) - a generation-time concern, not a runtime one, so it
    // is NOT an api dependency (:recordbuf-avro main + WktAvroBridge need only core `avro`).
    testImplementation(libs.avro.compiler)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Configure Error Prone + NullAway on every Java compile task, mirroring
// :recordbuf-runtime. NullAway's OnlyNullMarked=true restricts it to our
// @NullMarked package; no excludedPaths is needed (the bridge is a hand-written
// module, not plugin output, so there are no record-builder-generated sources to
// exclude). JSpecifyMode is NOT set (0.14.x auto-detects JSpecify on JDK 22+).
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        error("UnnecessarilyFullyQualified")
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
    }
}
