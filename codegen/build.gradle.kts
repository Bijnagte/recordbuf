import net.ltgt.gradle.errorprone.errorprone

// :recordbuf-codegen -- a generator-side ONLY code-emission library. Renders generated Java
// source (.rec records, .rec enums, nested Codec, interface implemenst). It has NO runtime
// dependency and is never referenced by the generated .rec surface (R22: the generated types
// stay dependency-free). It is a clean-room, original implementation -- NOT palantir/javapoet
// NOR a copy of it -- and its contract is sourced from the plugin's own (MIT-licensed) import
// behavior, documented in docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md.
plugins {
    `java-library`
    // Activates the default Error Prone check set; NullAway is added via the
    // `errorprone` configuration below (mirrors :recordbuf-runtime / :recordbuf-avro).
    alias(libs.plugins.gradle.errorprone)
}

group = "bijnagte.recordbuf"
version = "0.0.1"

java {
    // Pin the JDK via toolchain, matching the root/generator/runtime builds (Temurin 25).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // No MAIN runtime dependency: this is a self-contained emitter. JSpecify nullability is a
    // compile-only need kept for the spine records' `@Nullable String doc-comment` field convention
    // (project-wide: model.Field/Message/Enum use @Nullable, not Optional).
    compileOnly(libs.jspecify)

    // NullAway (an Error Prone check) proves the emitter's own @NullMarked tree is nullness-safe
    // by construction; error-prone-core loads Error Prone. Mirrors :recordbuf-runtime / :recordbuf-avro.
    errorprone(libs.nullaway)
    errorprone(libs.error.prone.core)

    // The node AST records carry hand-written, ergonomic builders (no external builder generator):
    // the consumer-facing surface is a small, self-owned, decoupled API rather than a pulled-in
    // annotation processor. So a separate record-builder annotation-processor dependency is gone.

    // JUnit for the module's own behavioral tests (a port of the plugin's original import test,
    // our own MIT asset).
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The module's main stays dependency-free; these are TEST-ONLY. A generated-source compile
    // gate compiles what the emitter produces against the real runtime (which carries protobuf via
    // api, and jspecify on its compile classpath -- re-declared here since runtime marks it
    // compileOnly and so does not propagate).
    testImplementation(project(":recordbuf-runtime"))
    testImplementation(libs.jspecify)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Configure Error Prone + NullAway on every Java compile task, mirroring :recordbuf-runtime /
// :recordbuf-avro. NullAway's OnlyNullMarked=true restricts it to our @NullMarked codegen package;
// no excludedPaths is needed (a hand-written module, not plugin output, so no record-builder-
// generated sources to exclude). JSpecifyMode is NOT set (0.14.x auto-detects JSpecify on JDK 22+).
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        // Runs the full default Error Prone check set (escalated to errors by -Werror) plus these
        // explicit escalations, mirroring :recordbuf-runtime / :recordbuf-avro. NullAway is the
        // nullness gate; OnlyNullMarked=true restricts it to this @NullMarked codegen package.
        // UnnecessarilyFullyQualified is escalated explicitly as in those modules. Every pre-existing
        // finding (FQN shortening, ClassInitializationDeadlock / AvoidCommonTypeNames suppressions on
        // TypeRef / Type, javadoc link/param fixes, and BadInstanceof / ObjectToString /
        // StringCaseLocaleUsage / NotJavadoc in the tests) has been cleaned up, so the full set passes.
        error("UnnecessarilyFullyQualified")
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
    }
}
