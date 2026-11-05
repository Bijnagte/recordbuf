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
//   protoc --plugin=protoc-gen-recordbuf=$PWD/generator/build/scripts/protoc-gen-recordbuf \
//          --recordbuf_out=src/main/java src/main/proto/hello.proto
//
// After `./gradlew :recordbuf-generator:installDist` that script lives at
// generator/build/scripts/protoc-gen-recordbuf and is a self-contained launcher.

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

// KTD7 (accepted at 0.0.1): no build-time plugin<->runtime version guardrail.
// Generated code references the runtime by fully-qualified name; the generator
// never links the runtime at build time, so a mismatch is only detectable at
// consumer link time. Within one co-versioned release the skip is safe; the two
// cheap future guardrails are recorded and DEFERRED for when an independent
// runtime release becomes possible:
//   (a) a co-release assertion that all module versions are equal (thin), or
//   (b) a generator-emitted runtime version stamp the fixtures assert at compile
//       time (stronger). Adding either is a small, self-contained follow-up.
