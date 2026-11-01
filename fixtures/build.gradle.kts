plugins {
    `java-library`
}

// Internal fixtures module, not published. It compiles the generated record +
// protobuf-java reference into its own jar (R5) and hosts the interop/byte-equality
// and interface families (R11/R13), breaking the "codegen into the compiling
// module's own test source set" circularity (KTD6). Co-versioning is wired from
// the root in U5.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// ============================ generation ============================
// Each family .proto in src/main/proto drives generation of:
//   1. the recordbuf record + codec        (--recordbuf_out, all record protos)
//   2. the protobuf-java reference          (--java_out, on the `-ref` protos)
// Both trees are joined to the fixtures `main` source set so the compiled
// fixtures jar the test suite consumes carries the generated code. The
// iface_marker family (java_interface / java_enum_interface) rides the same
// recordbuf_out invocation; it just needs the options module on the include
// path, which the other record protos ignore harmlessly.
//
// Generation invokes the generator's installDist launcher (the still-root
// launcher at this step; U4 re-points this at :recordbuf-generator:installDist),
// NOT the jar of the module under test.

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

val protoDir = File(project.projectDir, "src/main/proto")
// iface_marker imports recordbuf_options.proto from the options module (KTD5):
// a protoc include path, not a Gradle dependency.
val optionsProtoDir = project(":recordbuf-options").projectDir.resolve("src/main/proto")

@Suppress("DEPRECATION")
val recordDir = File(project.buildDir, "generated/record") // recordbuf record + codec
@Suppress("DEPRECATION")
val refDir = File(project.buildDir, "generated/ref")        // protobuf-java reference

// Generation invokes the generator module's installDist launcher -- its
// distribution bin, which carries a sibling lib/ of jars + plugin classes.
// Resolved at EXECUTION time (after the install exists) by globbing the
// generator module's build/install/<app>/bin, so the co-version never hard-codes
// the artifact name.
val generatorProjectDir = project(":recordbuf-generator").projectDir

fun resolveGeneratorLauncher(): File = File(generatorProjectDir, "build/install")
    .listFiles().orEmpty().map { File(it, "bin/protoc-gen-recordbuf") }
    .firstOrNull { it.isFile }
        ?: throw GradleException("genFixtures: generator launcher not found under generator/build/install; run :recordbuf-generator:installDist first")

val genFixtures = tasks.register("genFixtures") {
    group = "verification"
    description = "Generate the recordbuf records + protobuf-java refs into the fixtures main source set"
    // Deferred string-path dependency; forces the generator project to configure
    // so its application-plugin tasks exist without the generator's config running
    // ahead of fixtures during the initial configuration pass.
    dependsOn(":recordbuf-generator:installDist")
    doLast {
        recordDir.deleteRecursively(); recordDir.mkdirs()
        refDir.deleteRecursively(); refDir.mkdirs()

        val launcher = resolveGeneratorLauncher()

        val protos: List<File> =
            (protoDir.listFiles()?.toList() ?: emptyList<File>()).sortedBy { it.name }
        val recordProtos = protos.filter { it.name.endsWith(".proto") && !it.name.endsWith("-ref.proto") }
        val refProtos = protos.filter { it.name.endsWith("-ref.proto") }
        if (recordProtos.isEmpty() && refProtos.isEmpty()) {
            logger.warn("genFixtures: no .proto found under ${protoDir.path}; skipping")
            return@doLast
        }

        fun run(args: List<String>): Int {
            val proc = ProcessBuilder(listOf(protocBinary) + args)
                .directory(project.projectDir).redirectErrorStream(false).start()
            proc.inputStream.bufferedReader().use { it.forEachLine { System.out.println(it) } }
            proc.errorStream.bufferedReader().use { it.forEachLine { System.err.println(it) } }
            return proc.waitFor()
        }

        if (recordProtos.isNotEmpty()) {
            val code = run(listOf(
                "-I", protoDir.absolutePath,
                "-I", optionsProtoDir.absolutePath,
                "--recordbuf_out=" + recordDir.absolutePath,
                "--plugin=protoc-gen-recordbuf=" + launcher.absolutePath
            ) + recordProtos.map { it.absolutePath })
            if (code != 0) throw GradleException(
                "genFixtures: protoc --recordbuf_out failed (exit $code); launcher=$launcher"
            )
        }
        if (refProtos.isNotEmpty()) {
            val code = run(listOf(
                "-I", protoDir.absolutePath,
                "--java_out=" + refDir.absolutePath
            ) + refProtos.map { it.absolutePath })
            if (code != 0) throw GradleException(
                "genFixtures: protoc --java_out failed (exit $code); launcher=$launcher"
            )
        }
        logger.lifecycle("genFixtures: ${recordProtos.size} record(s) + ${refProtos.size} reference(s)")
    }
    inputs.dir(protoDir)
    inputs.dir(optionsProtoDir)
    outputs.dirs(recordDir, refDir)
}

// Both generated trees compiled into the fixtures main source set; generation runs before compile.
sourceSets.main.get().java.srcDir(recordDir)
sourceSets.main.get().java.srcDir(refDir)
tasks.named("compileJava") { dependsOn(genFixtures) }

dependencies {
    // Generated records call the runtime (bijnagte.recordbuf.runtime.*) by FQN; the
    // runtime api-exposes protobuf, so the generated records link via this edge.
    implementation(project(":recordbuf-runtime"))
    // The protobuf-java references (the *.ref types) and the records' Coded* usage
    // need protobuf on the compile classpath.
    implementation(libs.protobuf)
    // Generated records annotate present-able components @org.jspecify.annotations.Nullable.
    compileOnly(libs.jspecify)

    // JUnit Jupiter + plain assertions only.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
