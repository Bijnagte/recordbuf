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

group = "bijnagte.recordbuf"
version = "0.0.1"

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
    // Protobuf runtime -- used by the plugin (FileDescriptorProto) and on the
    // GENERATED code's classpath (CodedInputStream/CodedOutputStream). Bumped
    // to 4.36.2 to match the protoc 36.2 that generated PluginProtos.java.
    implementation(libs.protobuf)
    implementation(libs.protobuf.util)

    // JSpecify nullability annotations.  compileOnly because the generated
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
    // JUnit Jupiter + plain assertions only. JSpecify is generated-code infra, not a
    // test library (compiled into the generated test source set as a type-use note).
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The generated record/codec (in the TEST source set via genInterop) now calls into
    // the runtime module (bijnagte.recordbuf.runtime.Encoder/Decoder/Wire/CodecDefaults), so
    // the runtime must be on the test COMPILE classpath. protobuf-java already flows in via
    // the main 'implementation' dep and transitively via the runtime; jspecify is compileOnly
    // in main and does NOT propagate to the test config, so add it explicitly.
    testImplementation(project(":recordbuf-runtime"))
    testImplementation(libs.jspecify)
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
//   protoc --plugin=protoc-gen-recordbuf=$PWD/build/scripts/protoc-gen-recordbuf \
//          --recordbuf_out=src/main/java src/main/proto/hello.proto
//
// After `./gradlew installDist` that script lives at build/scripts/protoc-gen-recordbuf
// and is a self-contained launcher (it puts the project classpath on `$CLASSPATH`
// and execs `java -cp ... bijnagte.recordbuf.plugin.Main`).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "bijnagte.recordbuf.plugin.Main",
            "Implementation-Title" to "recordbuf",
            "Implementation-Version" to project.version,
        )
    }
}

// ============================ interop harness ============================
// Each per-family .proto in src/test/proto drives generation of:
//   1. the recordbuf record + codec  (--recordbuf_out)
//   2. the protobuf-java reference     (--java_out, on a `.ref`-package variant)
// Both generated trees are compiled into the TEST source set so each family's
// JUnit test can byte-equality + interop its record against the reference.
//
// The generated record lives in the TEST source set (not main) to avoid the
// classic same-module codegen circular dependency: the plugin must be compiled
// into a jar BEFORE it can generate. So `genInterop` depends on `:jar`, and
// `compileTestJava`/`test` depend on `genInterop`.

val protocBinary: String =
    (findProperty("protoc") as String?)
        ?: System.getenv("PROTOC")
        ?: run {
            // First find `protoc` on PATH, else fall back to the common Homebrew
            // location on this machine.
            val onPath = System.getenv("PATH").split(":")
                .map { File(it, "protoc") }
                .firstOrNull { it.isFile && it.canExecute() }
            onPath?.absolutePath ?: "/opt/homebrew/bin/protoc"
        }

val interopBase = "generated/interop"
@Suppress("DEPRECATION")
val genRecordDir = File(project.buildDir, "$interopBase/record") // record + codec src
@Suppress("DEPRECATION")
val genRefDir = File(project.buildDir, "$interopBase/ref")        // protobuf-java reference src
@Suppress("DEPRECATION")
val binDir = File(project.buildDir, "$interopBase/bin")            // plugin wrapper
val genProtoDir = File(project.projectDir, "src/test/proto")
// recordbuf_options.proto now lives in the options module (KTD5); genInterface resolves it
// via a protoc include path, not a Gradle dependency -- so point -I at the options module's
// proto dir rather than the old src/main/proto host.
val mainProtoDir = project(":recordbuf-options").projectDir.resolve("src/main/proto")

// The iface_marker family has its OWN generator task (genInterface) rather than riding the
// all-proto glob, because it imports recordbuf_options.proto from the options module — an
// include path genInterop doesn't carry. genInterface generates the record/enum that implement
// user interfaces via the java_interface / java_enum_interface options.
val ifaceBase = "generated/iface"
@Suppress("DEPRECATION")
val genIfaceDir = File(project.buildDir, "$ifaceBase/java")       // generated iface record/enum src
@Suppress("DEPRECATION")
val ifaceBinDir = File(project.buildDir, "$ifaceBase/bin")        // dedicated plugin wrapper

// Checked-in protos split by name: record protos drive --recordbuf_out, their
// `-ref` counterparts drive --java_out. Each pair shares a message shape but the
// `-ref` proto uses a `.ref`-suffixed java_package, so the reference type and the
// record land in sibling packages and don't collide. No runtime string-surgery:
// the `-ref.proto` is a plain checked-in file.
val genInterop = tasks.register("genInterop") {
    group = "verification"
    description = "Generate interop records + protobuf-java references for all family protos"
    dependsOn(tasks.jar, tasks.compileJava)
    doLast {
        genRecordDir.deleteRecursively(); genRecordDir.mkdirs()
        genRefDir.deleteRecursively(); genRefDir.mkdirs()
        binDir.deleteRecursively(); binDir.mkdirs()

        val protos: List<File> = (genProtoDir.listFiles()?.toList() ?: emptyList<File>())
            .filter { it.name.endsWith(".proto") }
            .sortedBy { it.name }
        if (protos.isEmpty()) {
            logger.warn("genInterop: no .proto found under ${genProtoDir.path}; skipping")
            return@doLast
        }
        // iface_marker.proto has its own generator task (genInterface); exclude it here so the
        // all-proto interop glob doesn't need recordbuf_options.proto on its include path.
        val recordProtos = protos.filter { !it.name.endsWith("-ref.proto") && it.name != "iface_marker.proto" }
        val refProtos = protos.filter { it.name.endsWith("-ref.proto") }

        // An executable wrapper so protoc can invoke the plugin over stdin/stdout.
        val runtimeCp = sourceSets.main.get().runtimeClasspath.asPath
        val wrapper = File(binDir, "protoc-gen-recordbuf")
        wrapper.writeText("#!/bin/sh\nexec java -cp \"$runtimeCp\" bijnagte.recordbuf.plugin.Main \"\$@\"\n")
        wrapper.setExecutable(true)
        wrapper.setReadable(true)

        fun run(args: List<String>): Int {
            val proc = ProcessBuilder(listOf(protocBinary) + args)
                .directory(project.projectDir).redirectErrorStream(false).start()
            proc.inputStream.bufferedReader().use { it.forEachLine { System.out.println(it) } }
            proc.errorStream.bufferedReader().use { it.forEachLine { System.err.println(it) } }
            return proc.waitFor()
        }

        if (recordProtos.isNotEmpty()) {
            val code = run(listOf(
                "-I", genProtoDir.absolutePath,
                "--recordbuf_out=" + genRecordDir.absolutePath,
                "--plugin=protoc-gen-recordbuf=" + wrapper.absolutePath
            ) + recordProtos.map { it.absolutePath })
            if (code != 0) throw GradleException("protoc --recordbuf_out failed (exit $code)")
        }
        if (refProtos.isNotEmpty()) {
            val code = run(listOf(
                "-I", genProtoDir.absolutePath,
                "--java_out=" + genRefDir.absolutePath
            ) + refProtos.map { it.absolutePath })
            if (code != 0) throw GradleException("protoc --java_out failed (exit $code)")
        }
        logger.lifecycle("genInterop: ${recordProtos.size} record(s) + ${refProtos.size} reference(s)")
    }
    inputs.dir(genProtoDir)
    outputs.dirs(genRecordDir, genRefDir)
}

// Both trees compiled into the test source set; generation runs before compile.
sourceSets.test.get().java.srcDir(genRecordDir)
sourceSets.test.get().java.srcDir(genRefDir)
tasks.named("compileTestJava") { dependsOn(genInterop) }

// ---- Interface-implementation feature (java_interface / java_enum_interface) ----
// Generate the iface_marker family: a record + enum that implement user interfaces. It reuses
// the plugin launcher pattern but adds src/main/proto to the include path so the import of
// recordbuf_options.proto (the two extension definitions) resolves. The generated types are
// compiled in src/test/java against the hand-written Marker / Named interfaces.
val genInterface = tasks.register("genInterface") {
    group = "verification"
    description = "Generate the iface_marker family (record/enum implementing a user interface via the option)"
    dependsOn(tasks.jar, tasks.compileJava)
    doLast {
        genIfaceDir.deleteRecursively(); genIfaceDir.mkdirs()
        ifaceBinDir.deleteRecursively(); ifaceBinDir.mkdirs()

        val ifaceProto = File(genProtoDir, "iface_marker.proto")
        val runtimeCp = sourceSets.main.get().runtimeClasspath.asPath
        val wrapper = File(ifaceBinDir, "protoc-gen-recordbuf")
        wrapper.writeText("#!/bin/sh\nexec java -cp \"$runtimeCp\" bijnagte.recordbuf.plugin.Main \"\$@\"\n")
        wrapper.setExecutable(true)
        wrapper.setReadable(true)

        fun run(args: List<String>): Int {
            val proc = ProcessBuilder(listOf(protocBinary) + args)
                .directory(project.projectDir).redirectErrorStream(false).start()
            proc.inputStream.bufferedReader().use { it.forEachLine { System.out.println(it) } }
            proc.errorStream.bufferedReader().use { it.forEachLine { System.err.println(it) } }
            return proc.waitFor()
        }

        val code = run(listOf(
            "-I", genProtoDir.absolutePath,
            "-I", mainProtoDir.absolutePath,
            "--recordbuf_out=" + genIfaceDir.absolutePath,
            "--plugin=protoc-gen-recordbuf=" + wrapper.absolutePath
        ) + ifaceProto.absolutePath)
        if (code != 0) throw GradleException("genInterface: protoc --recordbuf_out failed (exit $code)")
        logger.lifecycle("genInterface: generated iface_marker family")
    }
    inputs.file(File(genProtoDir, "iface_marker.proto"))
    inputs.dir(mainProtoDir)
    outputs.dir(genIfaceDir)
}

sourceSets.test.get().java.srcDir(genIfaceDir)
tasks.named("compileTestJava") { dependsOn(genInterface) }

