import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    // Activates the default Error Prone check set; NullAway is added via the
    // `errorprone` configuration below. Mirrors :recordbuf-generator -- the
    // runtime is null-free-by-construction, so @NullMarked on its package-info
    // makes the whole module non-null by default and NullAway honours it.
    alias(libs.plugins.gradle.errorprone)
}

group = "io.github.bijnagte.recordbuf"

java {
    // Pin the JDK via toolchain, matching the root/generator builds (Temurin 25).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // JSpecify nullability annotations. compileOnly because generated user
    // types (independent modules) pull JSpecify transitively; the runtime's own
    // code and its @NullMarked package-info need it on the build classpath.
    compileOnly(libs.jspecify)

    // NullAway runs as an Error Prone check: it registers via the errorprone
    // configuration (the net.ltgt.errorprone plugin puts that on the annotation-
    // processor path of every compile task). error-prone-core loads Error Prone;
    // nullaway is the nullness check we actually care about. Mirrors :generator;
    // see that module's block for the full rationale.
    errorprone(libs.nullaway)
    errorprone(libs.error.prone.core)

    // Generated code that calls the runtime also reaches protobuf's
    // CodedInputStream/CodedOutputStream through CodedOutputStream/Decoder.in(), so
    // protobuf must flow transitively to every consumer of the runtime (R4).
    api(libs.protobuf)

    // Direct mechanical-contract tests for the framing helpers (AE1 oracle lives in
    // the fixtures interop suite; these pin the helpers themselves in isolation).
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Configure Error Prone + NullAway on every Java compile task, mirroring
// :recordbuf-generator. NullAway has no default scope in 0.14.x, so
// OnlyNullMarked=true restricts it to our @NullMarked package (the runtime's
// own package-info carries it). The runtime is null-free-by-construction, so
// no excludedPaths is needed (unlike the generator, which excludes its
// record-builder-generated sources). JSpecifyMode is NOT set (0.14.x
// auto-detects JSpecify on JDK 22+).
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        error("UnnecessarilyFullyQualified")
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
    }
}
