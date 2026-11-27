// net.ltgt.gradle.errorprone: brings the `options.errorprone { … }` DSL into
// scope for the `JavaCompile` tasks below (required in *.gradle.kts scripts).
import net.ltgt.gradle.errorprone.errorprone
import org.jreleaser.model.Active
import org.jreleaser.model.api.deploy.maven.MavenCentralMavenDeployer

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
    // Builds the executable fat jar (`shadowJar`); see the end of this file.
    alias(libs.plugins.shadow)
    // Builds the jlink runtime zips and creates the GitHub release; see the end of this file.
    alias(libs.plugins.jreleaser)
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

    // The pure-Java, proto-agnostic Java-source AST (M4 cross-module swap): the writer's
    // string-concatenation emit path is being replaced by bijnagte.recordbuf.codegen.source.
    // generator-internal only -> implementation (NOT api): the emitted .rec code stays
    // dependency-free (R22); :recordbuf-codegen is the generator's own concern.
    implementation(project(":recordbuf-codegen"))

    // The json module (bijnagte.recordbuf.json) hosts the temporal (de)serializer arms that a
    // generated record's temporal field references; the generator resolves the FQN pair
    // (JavaTimeTemporal.forType) at annotation-emit time, so :recordbuf-json lands on the
    // plugin's classpath to read the Class constants — implementation (NOT api), like
    // :recordbuf-codegen: the emitted .rec code stays R22-clean, its consumer pulls :recordbuf-json
    // transitively (the same R4-style transitive dependency the plan documents).
    implementation(project(":recordbuf-json"))

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

    // Google Java Format -- the generator-side default Formatter the plugin's
    // rendered source passes through (the GJF-backed impl, added in U2; wired into
    // the plugin's emit path in U3). generator-only: it is a generation-time concern,
    // so implementation is NOT api and the generated .rec surface stays dependency-free
    // (R22). GJF pokes com.sun.tools.javac.* reflectively, so --add-exports
    // jdk.compiler/... is required whenever its code runs (the shared `gjfJvmArgs`
    // set, wired onto the test + launched JVMs below).
    implementation(libs.google.java.format)
    // SLF4J -- the generator-side logging the plugin's internal diagnostics run through (Main's
    // request handling, CodeGenerator's per-file generation). `slf4j-simple` is the binding:
    // its ONLY output channel is stderr, so it can never touch the protoc plugin's stdout
    // CodeGeneratorResponse protocol; its default level INFO keeps the launched plugin quiet
    // (development debug is -Dorg.slf4j.simpleLogger.defaultLogLevel=debug). runtimeOnly because
    // the sources only import the API; the binding still ships in installDist's lib/ and on the
    // test runtime classpath. Generator-only (R22): the generated .rec surface stays
    // dependency-free.
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

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

    testImplementation(project(":recordbuf-runtime"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // M4-C2d-1: optional scalar @Nullable support requires JSpecify on the test classpath
    // so that assertCompilesWithRuntime (which compiles against the test runtime classpath)
    // resolves the @Nullable annotation emitted in the generated record header.
    testImplementation(libs.jspecify)
}

// The minimal --add-exports jdk.compiler/... set google-java-format 2.0.0 needs on this project's
// JDK 25 toolchain. GJF's `Formatter.formatSource` parses source into a com.sun.tools.javac
// compiler tree (JEP 396 module boundary), so the JVM that runs GJF -- the generator's `test`
// JVM and the launched plugin (`run`/`installDist`) -- must export these packages. It is one
// fact used by both, so it lives in one place (no drift between the two sets). Empirically minimal
// for *pure syntactic* formatting: `comp`/`processing`/`model` are NOT reached because GJF does
// not semantic-check, only reformat (verified this session on this JDK -- the launch smoke test
// resolves the plan's deferred D1). Add a package here if a future source shape triggers a new
// `IllegalAccessError` naming it.
// The --add-exports jdk.compiler/... set google-java-format 2.0.0 needs on this project's JDK 25
// toolchain. GJF's `Formatter.formatSource` parses source into a com.sun.tools.javac compiler tree
// (JEP 396 module boundary), so the JVM that runs GJF -- the generator's `test` JVM and the
// launched plugin (`run`/`installDist`) -- must export these packages. It is one fact used by both,
// so it lives in one place (no drift between the two sets). The full `com.sun.tools.javac.*` tree is
// exported (not a minimal subset) because GJF's cleanup phase can reach `comp`/`jvm`/`model` when
// re-parsing certain source shapes, and the set is sensitive to the exact shape parsed. Add a
// package here only if a new shape triggers an `IllegalAccessError` naming it.
val javacPackages =
    listOf(
        "",
        "api",
        "main",
        "tree",
        "util",
        "file",
        "code",
        "parser",
        "comp",
        "jvm",
        "model",
        "resources",
        "platform",
    )

val gjfJvmArgs: List<String> =
    javacPackages
        .map { "--add-exports=jdk.compiler/com.sun.tools.javac${if (it.isEmpty()) "" else "." + it}=ALL-UNNAMED" }
        .toList()

application {
    mainClass = "bijnagte.recordbuf.plugin.Main"
    applicationName = "protoc-gen-recordbuf"
    // applicationDefaultJvmArgs is baked into `run` and into the `installDist` launcher script, so
    // the launched plugin JVM exports the GJF set the plugin's formatter needs at runtime.
    applicationDefaultJvmArgs = gjfJvmArgs
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The generator's test JVM needs the same --add-exports jdk.compiler/... set as the launched
    // plugin (the launched plugin's set lives in the `application` block above) so GJF can run here
    // too. Both reference the same `gjfJvmArgs` fact, so they cannot drift.
    jvmArgs(gjfJvmArgs)
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
// The full Error Prone default check set runs (no `disableAllChecks`); both
// `error("NullAway")` and `error("UnnecessarilyFullyQualified")` promote
// their respective checks to build-failing severity, and all remaining default
// checks run at their natural severity.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
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
//   protoc --plugin=protoc-gen-recordbuf=$PWD/generator/build/install/protoc-gen-recordbuf/bin/protoc-gen-recordbuf \
//          --recordbuf_out=src/main/java src/main/proto/hello.proto
//
// After `./gradlew :recordbuf-generator:installDist` that script lives at
// generator/build/install/protoc-gen-recordbuf/bin/protoc-gen-recordbuf and is the self-contained
// launcher that actually runs (it puts the project classpath on `$CLASSPATH` and execs
// `java -cp ... bijnagte.recordbuf.plugin.Main`). Point protoc at this `install/.../bin/` launcher.
// The same task also emits a generator/build/scripts/protoc-gen-recordbuf, but that is a staging
// artifact, not the launched launcher -- its `APP_HOME` resolves to `generator/build` and looks for
// a `generator/build/lib` that does not exist, so it cannot load `Main` in place.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "bijnagte.recordbuf.plugin.Main",
            "Implementation-Title" to "recordbuf-gen",
            "Implementation-Version" to project.version,
        )
    }
}

// The executable fat jar: `java -jar protoc-gen-recordbuf-<version>-all.jar` runs the plugin with every
// dependency inside. It is a separate artifact from the `jar` above, which stays the plain library jar
// that the published POM's dependencies resolve against, so what consumers get from Maven Central is
// unchanged.
//
// `Add-Exports` is the manifest equivalent of the `--add-exports` flags in `gjfJvmArgs`: google-java-format
// reaches into com.sun.tools.javac.*, and `java -jar` cannot take JVM flags from the jar, but the JVM does
// read this attribute from the main jar's manifest. It is derived from `javacPackages`, so the launcher
// script and the fat jar share one list.
//
// `mergeServiceFiles()` keeps the META-INF/services entries of every bundled library (the slf4j binding
// is found through one), which would otherwise overwrite each other.
tasks.shadowJar {
    mergeServiceFiles()
    manifest {
        attributes(
            "Add-Exports" to
                javacPackages.joinToString(" ") {
                    "jdk.compiler/com.sun.tools.javac${if (it.isEmpty()) "" else "." + it}"
                },
            "Implementation-Title" to "recordbuf-gen",
            "Implementation-Version" to project.version,
        )
    }
}

// Build the fat jar as part of `assemble`/`build` so CI exercises it on every change.
tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// JReleaser does two independent jobs for a version tag, each run by its own workflow:
//  - Maven Central (`signing` and `deploy` below, .github/workflows/publish.yml): signs the modules that
//    `maven-publish` staged (see the root build) and uploads them as one Portal deployment.
//  - GitHub release (`assemble` and `release` below, .github/workflows/release.yml): jlink runtime zips
//    (Linux and macOS, built natively per platform). Each zip holds a trimmed Java runtime plus the plugin, so it runs with no JDK
//    installed: unzip it and point protoc at `bin/protoc-gen-recordbuf`.
//
// Each platform's zip is built on a runner of that platform (the `assemble` job of release.yml, a matrix):
// jlink then links from the JDK running Gradle, with that platform's own native libraries, and the zip name
// carries the platform (protoc-gen-recordbuf-<version>-<platform>.zip). The release job downloads the four
// zips into `build/release-assets` and `files` below uploads and checksums them. `files` is off when that
// directory does not exist, so the assemble jobs and local runs are unaffected.
val releaseAssetsDir = layout.buildDirectory.dir("release-assets")

jreleaser {
    // The git repository root is the parent of this module.
    gitRootSearch.set(true)

    project {
        description.set("protoc plugin that generates Java records and protobuf codecs from .proto files")
        copyright.set("2026 Dylan Bijnagte")
        authors.add("Dylan Bijnagte")
        license.set("MIT")
        inceptionYear.set("2026")
        links {
            homepage.set("https://github.com/Bijnagte/recordbuf")
        }
    }

    assemble {
        jlink {
            register("protoc-gen-recordbuf") {
                // Off in the release job: the zips arrive there pre-built (`files`), and the release step
                // would otherwise demand this host's own zip as well.
                active.set(if (releaseAssetsDir.get().asFile.isDirectory) Active.NEVER else Active.ALWAYS)
                executable.set("protoc-gen-recordbuf")
                imageName.set("protoc-gen-recordbuf-{{projectVersion}}")

                // The fat jar is the input; its Main-Class and every dependency are inside it.
                mainJar { path.set(tasks.shadowJar.flatMap { it.archiveFile }) }
                java {
                    mainClass.set("bijnagte.recordbuf.plugin.Main")
                    // The jlink image runs the jar on the classpath, where the manifest `Add-Exports`
                    // of the fat jar does not apply, so the launcher needs the same --add-exports flags
                    // as the installDist launcher (one list: `gjfJvmArgs`).
                    //
                    // They go in as the launcher script's DEFAULT_JAVA_OPTS, NOT through `jvmOptions`:
                    // JReleaser forwards `jvmOptions` to jlink as one quoted `--add-options="..."`
                    // string, which jlink bakes into the runtime as a single garbled option and the JVM
                    // then warns "Unknown module: ALL-UNNAMED --add-exports=..." on every run.
                    environmentVariables {
                        universal.put("DEFAULT_JAVA_OPTS", gjfJvmArgs.joinToString(" "))
                    }
                }

                // google-java-format uses jdk.compiler, which jdeps cannot see through the reflective
                // access, so it is listed explicitly. The fat jar bundles optional dependencies that
                // are not all on the module path, hence ignoreMissingDeps.
                additionalModuleNames.addAll("jdk.compiler", "java.logging")
                // The fat jar is multi-release (bundled dependencies ship versioned classes), which jdeps
                // refuses to scan without a --multi-release setting; `base` scans the unversioned classes.
                jdeps {
                    ignoreMissingDeps.set(true)
                    multiRelease.set("base")
                }
            }
        }
    }

    // The zips built on the matrix runners, attached to the GitHub release and included in its checksums.
    files {
        active.set(if (releaseAssetsDir.get().asFile.isDirectory) Active.ALWAYS else Active.NEVER)
        glob {
            pattern.set("${releaseAssetsDir.get().asFile}/*.zip")
        }
    }

    // Signing is for the Maven Central deployment below. It is on only when a key is supplied
    // (JRELEASER_GPG_SECRET_KEY, set by publish.yml), so the GitHub release job, which has no key, and
    // local runs are unaffected. `verify` is off because it would need the public key as another secret;
    // Central itself validates every signature when the deployment is uploaded.
    signing {
        val hasKey = providers.environmentVariable("JRELEASER_GPG_SECRET_KEY").isPresent
        active.set(if (hasKey) Active.ALWAYS else Active.NEVER)
        armored.set(true)
        verify.set(false)
    }

    // Maven Central, through the Central Portal publisher API. The modules are staged into one directory
    // by `maven-publish` (see the root build), so this uploads them as a SINGLE deployment.
    //   applyMavenCentralRules: signs and checksums what is staged and checks each POM and that every
    //     module has its sources and javadoc jars, before anything is uploaded.
    //   stage UPLOAD: upload and stop. The deployment waits in the Portal until someone clicks Publish
    //     (FULL would also publish it), keeping the review step the Central releases have always had.
    //   namespace: the namespace registered in the Portal, which is shorter than the groupId.
    // Credentials come from JRELEASER_MAVENCENTRAL_SONATYPE_USERNAME / _PASSWORD (a Portal user token).
    deploy {
        maven {
            mavenCentral {
                register("sonatype") {
                    // On only when the Central credentials are defined (publish.yml defines them), because
                    // JReleaser validates every section on every run and would otherwise demand a token from
                    // the GitHub release job too. publish.yml always defines the variable, even when the
                    // secret is missing (then empty), so a missing secret fails validation loudly instead of
                    // quietly skipping the upload. RELEASE: never for a snapshot, which Central rejects.
                    val hasCredentials =
                        providers.environmentVariable("JRELEASER_MAVENCENTRAL_SONATYPE_USERNAME").isPresent
                    active.set(if (hasCredentials) Active.RELEASE else Active.NEVER)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    namespace.set("io.github.bijnagte")
                    applyMavenCentralRules.set(true)
                    stage.set(MavenCentralMavenDeployer.Stage.UPLOAD)
                    stagingRepository(rootProject.layout.buildDirectory.dir("staging-deploy").get().asFile.absolutePath)
                }
            }
        }
    }

    release {
        github {
            repoOwner.set("Bijnagte")
            name.set("recordbuf")
            // The tag is the trigger, so it already exists; JReleaser must not create or move it.
            skipTag.set(true)
            // CI checks out the tag (a detached HEAD), so name the branch instead of letting JReleaser
            // read it from git.
            branch.set("main")
            // Re-running a failed release updates it instead of failing on the existing one.
            overwrite.set(true)
            changelog {
                formatted.set(Active.ALWAYS)
                preset.set("conventional-commits")
            }
        }
    }
}

// The mainJar above is a plain path, so Gradle does not know the assemble step needs the fat jar built
// first. Without this a stale jar from an earlier build would be bundled.
tasks.withType<org.jreleaser.gradle.plugin.tasks.AbstractJReleaserTask>().configureEach {
    dependsOn(tasks.shadowJar)
}
