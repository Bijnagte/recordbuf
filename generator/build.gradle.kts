plugins {
    java
    application
    // NOTE: net.ltgt.errorprone is NOT applied yet. Error Prone + NullAway were
    // the step-4 cross-project goal, but (1) NullAway's jar (com.uber:nullaway)
    // is not resolvable from this environment's Maven mirror, and (2) enabling
    // Error Prone's full default checker set on its own spams ~71 warnings, most
    // of them in the generated com.google.protobuf.compiler.PluginProtos shim, a
    // file we treat as out-of-scope glue. Both are a clean, self-contained
    // future task. When done, apply `id("net.ltgt.errorprone")` (auto-forks
    // javac + add-exports on JDK 16+), add the `errorprone` configuration below,
    // and (on JDK 22+) pass -XepOpt:NullAway:JSpecifyMode=true +
    // -XepOpt:NullAway:OnlyNullMarked=true to compileJava/compileTestJava so the
    // generated shim stays out of NullAway's scope.
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

    // Error Prone / NullAway wiring is DEFERRED -- see the note on the plugin
    // block above. The catalog still declares the coordinates (com.google.errorprone
    // for both error-prone-core and error-prone-check; com.uber for nullaway) so
    // re-enabling is a one-line `errorprone(...)` addition. To turn on when the
    // jars are reachable, add the `id("net.ltgt.errorprone")` plugin and, per task:
    //     options.compilerArgs += "-XepOpt:NullAway:JSpecifyMode=true"
    //     options.compilerArgs += "-XepOpt:NullAway:OnlyNullMarked=true"
    // JUnit Jupiter + plain assertions only (the plugin/model tests).
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
