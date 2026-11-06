// net.ltgt.gradle.errorprone: brings the `options.errorprone { … }` DSL into
// scope for the `JavaCompile` tasks below (required in *.gradle.kts scripts).
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    application
    // Activates the default Error Prone check set; NullAway is added via the
    // `errorprone` configuration below. The plugin auto-forks javac and adds
    // the --add-exports/--add-opens JEP 396 needs (JDK 16+), so NullAway's
    // scope flag reaches error-prone correctly. NullAway options are passed
    // through the blessed `options.errorprone { … }` DSL -- NOT as raw
    // `compilerArgs.add("-XepOpt:…")`, which hand-feeds the flag to javac and
    // is rejected as "invalid flag" on this plugin/toolchain combo.
    // Version comes from the catalog via `alias` (libs.plugins.gradle.errorprone).
    alias(libs.plugins.gradle.errorprone)
}

java {
    // Pin the JDK via toolchain (per the user's java-gradle-build guidance),
    // not sourceCompatibility/targetCompatibility. The active JDK is Temurin 25;
    // toolchain lets Gradle reuse it without a separate install.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Protobuf runtime -- used by the plugin (FileDescriptorProto) for parsing
    // the CodeGeneratorRequest and on the GENERATED code's classpath
    // (CodedInputStream/CodedOutputStream). Bumped to 4.36.2 to match the protoc
    // 36.2 that generated PluginProtos.java.
    implementation(libs.protobuf)

    // Avro's specific-record compiler (generation-time only). Per the R5 decision (same output dir,
    // no gradle task, in-process generation): the plugin runs Avro's SpecificCompiler over the
    // per-file .avsc it emits, reads back the generated XSpecific.java, and emits it as a
    // CodeGeneratorResponse.File alongside .avsc + the XAvro converter -- all one protoc
    // invocation, same output dir. This puts avro-compiler (+ its transitive Velocity+Jackson,
    // generation-time only) on the plugin's main classpath: a deliberate, one-way coupling the
    // operator accepted for build-system decoupling. The generated .rec code (R22) stays
    // dependency-free; core avro remains the sole *runtime* dependency of the consumed types.
    implementation(libs.avro.compiler)
    // Jackson: the .avsc schema is built as a Jackson JSON tree and serialized via its pretty
    // printer in AvroSchemaGenerator; tests parse the emitted .avsc back with the same ObjectMapper
    // and assert structurally rather than by brittle string matching. It is already on the plugin's
    // classpath transitively via avro-compiler (2.22.1); declared explicit to name the generator's
    // direct use. The generated .rec output (R22) stays dependency-free -- this is a generator concern.
    implementation(libs.jackson.databind)
    // JSpecify nullability annotations. compileOnly because the generated
    // record/codec types (independent modules) pull JSpecify transitively.
    compileOnly(libs.jspecify)

    // Record-builder generates <Record>With interfaces for the plugin's OWN
    // model records (Field/Message/Enum/ProtoFile/EnumConstant), NOT for
    // generated user-facing output (that stays dependency-free). core carries
    // the @RecordBuilder/@RecordBuilder.* annotations onto the compile classpath;
    // processor is the annotation processor itself.
    compileOnly(libs.record.builder.core)
    annotationProcessor(libs.record.builder.processor)

    // NullAway runs as an Error Prone check: it registers via the errorprone
    // configuration (the net.ltgt.errorprone plugin puts that configuration on
    // the annotation-processor path of every compile task). error-prone-core
    // is on the plugin path so Error Prone itself loads; nullaway is the
    // nullness check we actually care about. JSpecify (via its transitive dep
    // on nullaway) provides the @NullMarked / @Nullable annotations NullAway
    // honors; JSpecifyMode is NOT a flag we set (0.14.x auto-detects it).
    errorprone(libs.nullaway)
    errorprone(libs.error.prone.core)

    // JUnit Jupiter + plain assertions only (the plugin/model tests).
    // Avro is a test-only dependency here: the Avro bridge's schema emitter
    // (AvroSchemaGenerator) is unit-tested against Avro's own parser so a generated
    // .avsc is guaranteed to be valid schema JSON the runtime bridge + SpecificCompiler
    // (option B) can consume. The generator's MAIN classpath stays avro-free (R22):
    // the dependency is generated types' concern, not the plugin's.
    testImplementation(libs.avro)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "bijnagte.recordbuf.plugin.Main"
    applicationName = "protoc-gen-recordbuf"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// NullAway via `options.errorprone { … }` (the DSL the plugin needs on JDK 16+;
// raw `compilerArgs.add("-XepOpt:NullAway:…")` is an invalid flag there).
// NullAway 0.14.3 has NO default scope - its scanner crashes at init unless it
// is told a scope. Our generator packages carry JSpecify `@NullMarked`, so
// `OnlyNullMarked=true` restricts NullAway to those and keeps the un-annotated
// generated `com.google.protobuf.compiler` shim out of NullAway's scope. `JSpecifyMode`
// is NOT set: NullAway 0.14.x auto-detects JSpecify on JDK 22+, so passing it
// is an unknown-flag rejection. The record-builder-generated `*Builder`/`*With`
// classes live in our `@NullMarked` `model` package but don't propagate
// `@Nullable`; NullAway would flag ~25 lines of *generator output*, so
// `excludedPaths` excludes `.*build/generated/.*`.
//
// `disableAllChecks = true` + `error("NullAway")` turns on ONLY NullAway, at
// error severity: a real null violation blocks the build, while the ~15 first-
// party Error Prone default-check findings (UnusedVariable, etc.) stay deferred
// to a separate "cleanup" scope.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        // NullAway only: turn off the default check set (its other
        // first-party findings are style/cleanup, deferred to a separate
        // scope per the "warnings are OK, fix later" decision), then re-enable
        // NullAway at error severity so a real null violation blocks the build.
        disableAllChecks = true
        error("UnnecessarilyFullyQualified")
        error("NullAway")
        // `OnlyNullMarked=true` restricts NullAway to our `@NullMarked` packages. 0.14.x
        // auto-detects JSpecify; `JSpecifyMode` is NOT set.
        option("NullAway:OnlyNullMarked", "true")
        // The @RecordBuilder-generated *Builder/*With interfaces live in our
        // @NullMarked `model` package but do NOT propagate @Nullable; NullAway
        // would flag ~25 lines of *generated* output, so exclude them.
        excludedPaths.set(".*build/generated/.*")
        disableWarningsInGeneratedCode = true
    }
}

// The plugin is consumed by `protoc` via:
//
//   protoc --plugin=protoc-gen-recordbuf=$PWD/generator/build/scripts/protoc-gen-recordbuf \
//          --recordbuf_out=src/main/java src/main/proto/hello.proto
//
// After `./gradlew :recordbuf-generator:installDist` that script lives at
// generator/build/scripts/protoc-gen-recordbuf and is a self-contained launcher
// (it puts the project classpath on `$CLASSPATH` and execs
// `java -cp ... bijnagte.recordbuf.plugin.Main`).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "bijnagte.recordbuf.plugin.Main",
            "Implementation-Title" to "recordbuf-gen",
            "Implementation-Version" to project.version,
        )
    }
}
