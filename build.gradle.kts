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

// KTD3: one `bijnagte.recordbuf:0.0.1` version of truth for every subproject,
// inherited from the root. By construction the generator and the runtime its
// generated code calls into are the same version, so no mismatch is possible
// within this co-release.
subprojects {
    group = "bijnagte.recordbuf"
    version = "0.0.1"
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
