// recordbuf — root aggregator.
//
// This is a pure aggregator: no `java`/`application` plugin, no `src/`, no
// `dependencies` block. It (1) wires the subprojects via settings.gradle.kts,
// (2) co-versions them from a single version of truth (KTD3), and (3) hosts any
// cross-project wiring. The plugin itself lives in :recordbuf-generator, the
// codec-helper runtime in :recordbuf-runtime, the options .proto in
// :recordbuf-options, and the interop/interface fixtures in :recordbuf-fixtures.
//
// The plugin is consumed by `protoc` via:
//
//   protoc --plugin=protoc-gen-recordbuf=$PWD/generator/build/install/protoc-gen-recordbuf/bin/protoc-gen-recordbuf \
//          --recordbuf_out=src/main/java src/main/proto/hello.proto
//
// After `./gradlew :recordbuf-generator:installDist` that script lives at
// generator/build/install/protoc-gen-recordbuf/bin/protoc-gen-recordbuf and is a self-contained
// launcher. Use this `install/.../bin/` launcher -- NOT the sibling
// `build/scripts/protoc-gen-recordbuf` the same task also emits, whose `APP_HOME` resolves to
// `generator/build` and looks for a non-existent `generator/build/lib`.

// Spotless is applied to every subproject that has a `java` source set (see the
// `subprojects { pluginManager.withPlugin("java") … }` block below). Declared
// here with `apply false` so the version is pinned once in the catalog
// (libs.plugins.spotless) without applying it to this aggregating root, which has
// no Java source set of its own.
plugins {
    alias(libs.plugins.spotless) apply false
}

// KTD3: one `bijnagte.recordbuf:0.0.1` version of truth for every subproject,
// inherited from the root. By construction the generator and the runtime its
// generated code calls into are the same version, so no mismatch is possible
// within this co-release.
subprojects {
    group = "bijnagte.recordbuf"
    version = "0.0.1"
}

// Spotless: format every Java file with Google Java Format, configured once and
// inherited by each subproject that carries a `java` source set (runtime,
// generator, fixtures). `options/` has no `java` source set and is skipped. The
// version catalog pins both the plugin (com.diffplug.spotless) and the
// formatter (google-java-format); the pin lives in gradle/libs.versions.toml.
subprojects {
    pluginManager.withPlugin("java") {
        pluginManager.apply("com.diffplug.spotless")
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            java {
                // Only first-party `src/` sources. Generated protobuf/recordbuf
                // output lives under build/generated (outside this target).
                target("src/**/*.java")
                // The one generated file checked into src/ -- the protoc plugin
                // protocol shim -- is treated as generated glue and left untouched.
                targetExclude(
                    "**/com/google/protobuf/compiler/**",
                    "**/build/generated/**",
                )
                googleJavaFormat(libs.versions.google.java.format.get())
            }
        }
    }
}

// Checkstyle: a NON-FORMATTING quality gate, complementary to Spotless (which owns formatting).
// Applied to every subproject that carries a `java` source set, mirroring the Spotless block
// above; `options/` (proto only) is skipped exactly as it is for Spotless. The config lives
// once at config/checkstyle/checkstyle.xml -- the plugin's default `configDirectory`/`config`
// location, resolved relative to the root project -- and is inherited by every subproject, so
// rules are defined a single time. The `checkstyle` plugin is a Gradle built-in (no catalog
// plugin entry), but its tool otherwise defaults to a stale 10.24.0, so its version is pinned
// to the catalog's `checkstyle` pin (14.4.0, JDK 21+; our Java 25 toolchain satisfies it).
//
// Task source is first-party `src/` plus the recordbuf-generated `.rec` output under
// build/generated/**/recordbuf/**/rec/ (only :recordbuf-fixtures carries generated dirs). The
// recordbuf output now emits a minimal Javadoc on every generated type, so it is checkstyle-clean
// and is brought into scope -- closing the last gap that left MissingJavadocType un-enforced on
// generated code. The protobuf-java/Avro reference output (the natural, un-suffixed package) and
// the com.google.protobuf.compiler protocol shim stay out: they are not the project's own generated
// code. The includes are applied uniformly and are a no-op for modules without a generated `.rec` dir.
subprojects {
    pluginManager.withPlugin("java") {
        // Built-in plugin, no catalog entry; `withPlugin("java")` fires for the same
        // subprojects Spotless does and is skipped for `options/` (no `java` source set).
        pluginManager.apply("checkstyle")
        configure<org.gradle.api.plugins.quality.CheckstyleExtension> {
            // The plugin otherwise defaults its tool to a stale 10.24.0; pin to the catalog.
            toolVersion = libs.versions.checkstyle.get()
        }
        // Scope each task to its OWN source set plus the recordbuf-generated `.rec` output that
        // that set produces (see the header block above): a source-root-relative glob cannot see
        // the build/generated prefix of a generated source root, so the `.rec` dir is listed
        // explicitly. Main and test are scoped separately so checkstyleMain/checkstyleTest each
        // read only their own generated output -- no cross-task dependency on the other set's
        // generateProto. The protobuf-java/Avro reference output (the natural, un-suffixed package)
        // and the com.google.protobuf.compiler protocol shim stay out; the `.rec` includes are a
        // no-op for modules without a generated dir.
        tasks
            .withType(org.gradle.api.plugins.quality.Checkstyle::class.java)
            .configureEach {
                val seg = if (name == "checkstyleTest") "test" else "main"
                source =
                    project.fileTree(
                        mapOf(
                            "dir" to project.projectDir,
                            "include" to
                                arrayOf(
                                    "src/${seg}/**/*.java",
                                    "build/generated/sources/proto/${seg}/recordbuf/**/rec/**/*.java",
                                ),
                            "exclude" to arrayOf("**/com/google/protobuf/compiler/**"),
                        )
                    )
            }
    }
}
// Compiler warnings as errors: promote every first-party javac compiler warning to a build failure
// -- most importantly -Xlint:deprecation, the project's "no deprecated APIs unless explicitly
// suppressed" gate. javac offers no per-category error severity, so the other default-on category
// (-Xlint:unchecked) and every Error-Prone finding ride along. Wired once here and inherited by each
// subproject with a `java` source set (runtime, generator, codegen, avro, fixtures); `options/` (proto
// only) is skipped. The escape hatch is a scoped @SuppressWarnings -- see the two TypeRef internal-
// bridge call sites, which delegate to the project's still-deprecated TypeCollector API.
subprojects {
    pluginManager.withPlugin("java") {
        tasks.withType<JavaCompile>().configureEach {
            // options.compilerArgs.add("-Werror") // OFF for the functional checkpoint; re-enable after code+tests are green
        }
    }
}
