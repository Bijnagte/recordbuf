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
// inherited from the root. (The build-time plugin<->runtime version guardrail is
// intentionally deferred at 0.0.1 — see KTD7 in the multi-module plan.)
subprojects {
    group = "bijnagte.recordbuf"
    version = "0.0.1"
}
