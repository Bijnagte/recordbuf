// :recordbuf-codegen -- a generator-side ONLY code-emission library. Renders generated Java
// source (.rec records, .rec enums, nested Codec, interface implemenst). It has NO runtime
// dependency and is never referenced by the generated .rec surface (R22: the generated types
// stay dependency-free). It is a clean-room, original implementation -- NOT palantir/javapoet
// NOR a copy of it -- and its contract is sourced from the plugin's own (MIT-licensed) import
// behavior, documented in docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md.
plugins {
    `java-library`
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
    // compile-only need: the spine records carry the proto doc-comment on a `@Nullable String` field
    // (project convention -- model.Field/Message/Enum use @Nullable, not Optional), so the record
    // nodes + their @RecordBuilder-generated builders reference org.jspecify.annotations.Nullable.
    compileOnly(libs.jspecify)

    // io.soabase.record-builder, COMPILE-TIME ONLY: core carries the @RecordBuilder / @RecordBuilder.*
    // annotations onto the compile classpath, and the processor generates the nested fluent
    // `Foo.builder()` / `Foo.Builder` surface for the module's AST node records, so the emitter's
    // public API gains a fluent builder-like experience WITHOUT enlarging the code we hand-write or
    // the dependency-free emitted .rec surface. Same wiring as :recordbuf-generator / :recordbuf-runtime.
    compileOnly(libs.record.builder.core)
    annotationProcessor(libs.record.builder.processor)

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
