---
title: "Add JMH performance benchmark suite - Plan"
type: feat
date: 2026-11-26
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
---

# Add JMH Performance Benchmark Suite - Plan

## Goal Capsule

**Objective.** Add a local JMH benchmark suite in a new `:recordbuf-benchmarks` module that measures recordbuf codec throughput and allocation against native protobuf-java, across two message sizes (small 3-field and the full proto3 conformance message), covering encode, decode, and field-by-field record↔protobuf-java interop conversion.

**Authority hierarchy.** This plan is the authority for the benchmark module's shape. The Product Contract (below) defines WHAT to measure; the Planning Contract defines HOW. The implementer's own investigation of the generated code shapes the benchmark classes.

**Stop conditions.**
- `me.champeau.jmh` plugin fails to work with the project's Gradle 9.x + Kotlin DSL setup — escalate to the user.
- The protobuf-gradle-plugin's Groovy/KTS DSL friction makes dual code generation (recordbuf + protobuf-java) impractical in a standalone module — escalate to the user.
- `ThreadMXBean.getThreadAllocatedBytes()` is unavailable in the project's Java 25 toolchain — escalate (this is a standard API, so this is unlikely).

**Execution profile.** Short, bounded. Three units, each a single commit. No external service calls, no CI integration.

**Tail ownership.** The implementer owns the full cycle: scaffold → write benchmarks → run → verify output. No follow-up handoff.

---

## Product Contract

### Summary

Add a new `:recordbuf-benchmarks` Gradle module using the `me.champeau.jmh` plugin (v0.7.4) that benchmarks recordbuf codec encode/decode throughput and allocation against native protobuf-java, plus the overhead of record↔protobuf-java interop conversion. Two message sizes: a small 3-field message and the full proto3 conformance message. Both throughput (ops/sec) and allocation (bytes/op) are measured. The benchmark suite is a **local-only tool** — it is not part of CI and does not gate the build.

### Problem Frame

The recordbuf plugin generates Java records + protobuf codecs from `.proto` files. The generated codecs use `CodedInputStream`/`CodedOutputStream` directly, which should be fast, but the overhead of the record layer (field access, builder pattern, interop conversion) is unknown. Without a benchmark suite, there is no quantitative baseline for:

1. How close recordbuf codec throughput is to native protobuf-java's generated `toByteArray()`/`parseFrom()`.
2. The cost of the interop bridge (record ↔ protobuf-java conversion via the `Interop` inner class).
3. How allocation characteristics compare between the two representations.

These numbers matter when evaluating performance-sensitive applications that need both the ergonomics of Java records and the wire-compatibility of protobuf.

### Requirements

R1. The benchmark suite measures **encode throughput** (ops/sec) for recordbuf codec `encode()` and native protobuf-java `toByteArray()` at both message sizes.

R2. The benchmark suite measures **decode throughput** (ops/sec) for recordbuf codec `decode()` and native protobuf-java `parseFrom()` at both message sizes.

R3. The benchmark suite measures **interop conversion throughput** (ops/sec) for `Interop.toProto()` and `Interop.fromProto()` at both message sizes.

R4. All benchmarks report **allocation** (bytes allocated per operation) via `ThreadMXBean.getThreadAllocatedBytes()`.

R5. The benchmark suite runs via `./gradlew :recordbuf-benchmarks:jmh` and produces readable JMH output with throughput and allocation metrics.

R6. The benchmark suite is **not part of CI** — it is a local-only tool. It must not be wired into `./gradlew build` or `check`.

R7. The benchmark module is a **new standalone Gradle module** (`:recordbuf-benchmarks`), not added to the existing `:recordbuf-fixtures` module.

R8. The benchmark module uses a **new, minimal `.proto` file** defining the small benchmark message (not reusing fixtures protos, which carry test-specific concerns).

### Acceptance Examples

AE1. Running `./gradlew :recordbuf-benchmarks:jmh` completes successfully and prints JMH benchmark results showing throughput (ops/sec) and allocation (B/op) for all benchmark methods.

AE2. The benchmark output includes entries for: `recordEncode` (small + conformance), `nativeEncode` (small + conformance), `recordDecode` (small + conformance), `nativeDecode` (small + conformance), `interopToProto` (small + conformance), `interopFromProto` (small + conformance).

AE3. The benchmark module compiles cleanly with `./gradlew build` (or at minimum `./gradlew :recordbuf-benchmarks:compileJava`), including Spotless formatting, Error Prone, and NullAway checks.

AE4. `./gradlew build` (full project build) succeeds with the new module present. The benchmark module does not add any tasks to the `check` lifecycle.

### Scope Boundaries

#### In scope
- New `:recordbuf-benchmarks` Gradle module with `me.champeau.jmh` plugin
- One small benchmark proto (3 scalar fields) + reuse of the existing proto3 conformance message
- Benchmark classes: record encode/decode, native encode/decode, interop toProto/fromProto
- Throughput (ops/sec) + allocation (B/op) via ThreadMXBean
- Version catalog entry for the JMH plugin
- `settings.gradle.kts` update to include the new module

#### Deferred for later
- CI integration (e.g., nightly benchmark runs with regression thresholds)
- `-Xint` interpreter-mode benchmarks for JIT overhead analysis
- Statistical guardrails (e.g., fail if throughput regresses >X% vs a baseline)
- More message sizes or custom stress-test messages
- Jackson JSON serialization benchmarks (interop already covers the hot path)
- Avro serialization benchmarks

#### Deferred to Follow-Up Work
- (none identified at this time)

#### Outside this product's identity
- A published Maven artifact for the benchmark module (it is a local tool)
- Benchmark results as a public API or dashboard

### Key Product Decisions

KTD1. **New module over `src/jmh/` in fixtures.** The fixtures module is Groovy (forced by `protobuf-gradle-plugin`), carries JaCoCo, and is about correctness. A standalone module keeps concerns separated and avoids adding a `src/jmh/` source set to an already-complex build.

KTD2. **Throughput + allocation.** JMH measures wall-clock throughput natively (`@BenchmarkMode(Mode.Throughput)`). Allocation is reported via JMH's `-prof alloc` profiler, which internally uses `ThreadMXBean.getThreadAllocatedBytes(true)`.

KTD3. **Two message sizes: small (3 fields) + conformance (full proto3).** The small message isolates per-field overhead; the conformance message (`test_messages_proto3.proto`) represents real-world complexity with nested messages, oneofs, maps, WKTs, and packed repeated fields.

KTD4. **Local-only.** No CI wiring, no gate, no published artifact. The module exists to be run by developers when they want to measure performance.


---

## Planning Contract

### Key Technical Decisions

KTD5. **Groovy build file for the benchmarks module.** The protobuf-gradle-plugin's per-task DSL (`generateProtoTasks { all().configureEach { task -> task.plugins.create(...) } }`) is not cleanly drivable from Kotlin DSL (see the extensive comment block at the top of `fixtures/build.gradle`). The benchmarks module needs the same dual code generation (recordbuf + protobuf-java) and faces the same DSL limitations. Use a Groovy `build.gradle` file, matching the fixtures module's proven pattern.

KTD6. **JMH plugin from version catalog.** The `me.champeau.jmh` plugin is added to `gradle/libs.versions.toml` under `[plugins]` as `jmh = { id = "me.champeau.jmh", version.ref = "jmh" }` with `jmh = "0.7.4"` in `[versions]`. This keeps version management consistent with the rest of the project.

KTD7. **Conformance message copied, not depended on.** The `test_messages_proto3.proto` is copied into the benchmarks module's proto source directory rather than depending on `:recordbuf-fixtures`. The fixtures module is heavy (Avro, Jackson, golden tests) and a dependency would pull all of that into the benchmark classpath. The conformance proto is a standard, self-contained file that only imports WKTs (which come from the protobuf-java jar).

KTD8. **No Error Prone / NullAway on benchmark code.** The benchmark module's hand-written benchmark classes are not `@NullMarked` and do not need NullAway. The generated code (record + interop) is `@NullMarked` by the generator, so NullAway scope is correct. Error Prone is applied to the generated code (mirroring fixtures' pattern) but the benchmark classes themselves are plain Java. The `-Werror` flag applies project-wide via the root `subprojects` block.

### Output Structure

```
benchmarks/
├── build.gradle              # Groovy — JMH + protobuf-gradle-plugin + Error Prone
├── src/
│   ├── main/
│   │   └── proto/
│   │       ├── benchmark_small.proto       # 3-field small message
│   │       └── benchmark_conformance.proto # copy of test_messages_proto3.proto
│   └── jmh/
│       └── java/
│           └── bijnagte/recordbuf/benchmarks/
│               ├── SmallMessageBenchmark.java     # encode/decode/interop, small msg
│               └── ConformanceMessageBenchmark.java # encode/decode/interop, conformance msg
```

The `me.champeau.jmh` plugin creates the `jmh` source set automatically. Generated code from the protos lands in `build/generated/sources/proto/main/` (standard protobuf-gradle-plugin output), with the recordbuf `.rec` output under a `recordbuf/` subdirectory (from the `package_suffix=.rec` option).

### Assumptions

- `me.champeau.jmh` 0.7.4 is compatible with Gradle 9.x (verified: the plugin's `compatibleVersions` property is `7.1+` and it is tested up to Gradle 9).
- The protobuf-gradle-plugin's `package_suffix=.rec` option works identically in the benchmarks module as it does in fixtures (same plugin, same option, same launcher).
- `ThreadMXBean.getThreadAllocatedBytes(true)` is accurate enough for benchmarking (it is the standard JDK API and is what JMH's `-prof alloc` profiler uses).
- The conformance proto (`test_messages_proto3.proto`) is self-contained and only imports WKTs — no dependency on other project protos. (Verified: it imports `google/protobuf/{any,duration,field_mask,struct,timestamp,wrappers}.proto`.)

### Implementation Units

---

### U1. Scaffold the benchmark module

**Goal.** Create the `:recordbuf-benchmarks` module with its build file, proto sources, and version catalog entry. The module must compile (empty `src/jmh/` is fine) and generate the record + protobuf-java reference code from the benchmark protos.

**Requirements.** R5, R6, R7, R8.

**Dependencies.** None.

**Files.**
- `gradle/libs.versions.toml` — add `jmh` version + plugin entries
- `settings.gradle.kts` — add `include(":recordbuf-benchmarks")` + projectDir mapping
- `benchmarks/build.gradle` — Groovy build file (new)
- `benchmarks/src/main/proto/benchmark_small.proto` — small 3-field message (new)
- `benchmarks/src/main/proto/benchmark_conformance.proto` — copy of the conformance message (new)

**Approach.**

1. **Version catalog** (`gradle/libs.versions.toml`):
   - Add `jmh = "0.7.4"` to `[versions]`
   - Add `jmh = { id = "me.champeau.jmh", version.ref = "jmh" }` to `[plugins]`
   - Note: the JMH plugin is NOT in the `[libraries]` section — the `me.champeau.jmh` plugin transitively brings `jmh-core` and `jmh-generator-annprocess` into the `jmh` source set's `annotationProcessor` configuration. No explicit JMH library dependencies are needed.

2. **Settings** (`settings.gradle.kts`):
   - Add `include(":recordbuf-benchmarks")` and `project(":recordbuf-benchmarks").projectDir = File(rootDir, "benchmarks")` — matching the existing pattern.

3. **Build file** (`benchmarks/build.gradle`, Groovy):
   - `buildscript` block: classpath for `com.google.protobuf:protobuf-gradle-plugin:0.10.1` (same as fixtures)
   - `plugins` block: `alias(libs.plugins.gradle.errorprone)`
   - `apply plugin: 'java-library'` and `apply plugin: 'com.google.protobuf'`
   - Apply the JMH plugin: `apply plugin: 'me.champeau.jmh'` (or `id 'me.champeau.jmh'` in the plugins block if the catalog entry resolves)
   - `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }`
   - Dependencies: `implementation protobufJava` (4.36.2), `implementation project(':recordbuf-runtime')`, `implementation project(':recordbuf-json')`, `implementation libs.jspecify`, `errorprone libs.nullaway`, `errorprone libs.error.prone.core`
   - `protobuf { }` block: same structure as fixtures' `generateProtoTasks` — `ofSourceSet('main')`, `task.plugins.create('recordbuf')`, `task.plugins.getByName('recordbuf').option('package_suffix=.rec')`, `task.getIncludeDirs().from(optionsProtoDir)`. The `optionsProtoDir` is `project(':recordbuf-options').layout.projectDirectory.dir('src/main/proto')`.
   - The `recordbufLauncher` path is `project(':recordbuf-generator').file('build/install/protoc-gen-recordbuf/bin/protoc-gen-recordbuf').absolutePath`.
   - Wire `generateProto` to depend on `:recordbuf-generator:installDist` and track the installDist output dir as an input (same KTD6/KTD8 pattern as fixtures).
   - `sourceSets.main.proto { srcDir project(':recordbuf-options').file('src/main/proto') }` — so the options proto is on the include path.
   - Error Prone config on `compileJava`: `options.errorprone { error("NullAway"); option("NullAway:OnlyNullMarked", "true") }` — scoped to the `@NullMarked` `.rec` output. The protobuf-java reference (un-suffixed natural package) is out of scope by `OnlyNullMarked`.
   - JMH config: `jmh { warmupIterations = 3; iterations = 5; fork = 1 }` — sensible defaults for local runs. Override at the command line with `-Djmh.warmupIterations=...` if needed.

4. **Small benchmark proto** (`benchmarks/src/main/proto/benchmark_small.proto`):
   ```proto
   syntax = "proto3";
   package bijnagte.recordbuf.benchmarks.small;
   option java_package = "bijnagte.recordbuf.benchmarks.small";
   option java_multiple_files = true;

   import "recordbuf_options.proto";

   message BenchmarkSmall {
     string name = 1;
     int64 id = 2;
     bool enabled = 3;
     option (.recordbuf.protobuf_java) = "bijnagte.recordbuf.benchmarks.small";
   }
   ```
   The `option (.recordbuf.protobuf_java)` enables interop generation (the `Interop` inner class with `toProto()`/`fromProto()`).

5. **Conformance benchmark proto** (`benchmarks/src/main/proto/benchmark_conformance.proto`):
   - Copy the contents of `fixtures/src/main/proto/test_messages_proto3.proto` verbatim.
   - Change the `java_package` to `bijnagte.recordbuf.benchmarks.conformance` (to keep it in a distinct package from the fixtures' conformance output).
   - Add `option (.recordbuf.protobuf_java) = "bijnagte.recordbuf.benchmarks.conformance";` to the `TestAllTypesProto3` message to enable interop.
   - Keep all WKT imports as-is (they resolve from the protobuf-java jar).

**Test scenarios.**
- `./gradlew :recordbuf-benchmarks:generateProto` completes and produces both the `.rec` record and the protobuf-java reference for both benchmark protos.
- `./gradlew :recordbuf-benchmarks:compileJava` succeeds (the generated code compiles).
- `./gradlew :recordbuf-benchmarks:spotlessCheck` passes.
- `./gradlew :recordbuf-benchmarks:checkstyleMain` passes (the generated `.rec` output is Checkstyle-clean).

**Verification.** The module is registered in settings, the build file compiles, proto generation works, and the generated code compiles.

**Patterns to follow.** `fixtures/build.gradle` for the protobuf-gradle-plugin Groovy DSL, the `recordbuf` plugin registration, the `package_suffix=.rec` option, the `installDist` dependency wiring, and the Error Prone config. The root `build.gradle.kts` for the `subprojects` block (Spotless, Checkstyle, `-Werror` are applied automatically to any new Java subproject).

---

### U2. Write the benchmark classes

**Goal.** Write the JMH benchmark classes that measure encode, decode, and interop conversion for both message sizes, reporting throughput and allocation.

**Requirements.** R1, R2, R3, R4.

**Dependencies.** U1.

**Files.**
- `benchmarks/src/jmh/java/bijnagte/recordbuf/benchmarks/SmallMessageBenchmark.java` (new)
- `benchmarks/src/jmh/java/bijnagte/recordbuf/benchmarks/ConformanceMessageBenchmark.java` (new)

**Approach.**

Each benchmark class follows the same shape. The difference is which message type it benchmarks.

Two classes, one per message size. Each is `@State(Scope.Thread)` with `@BenchmarkMode(Mode.Throughput)` and `@OutputTimeUnit(SECONDS)`. The difference is the message type and the `@Setup` population.
**Allocation measurement.** Throughput is the primary metric (`@BenchmarkMode(Mode.Throughput)`). Allocation is measured via JMH's `-prof alloc` profiler, which internally calls `ThreadMXBean.getThreadAllocatedBytes(true)` before and after each iteration and reports `B/op`. This satisfies R4 without manual instrumentation — the benchmark methods just do the operation and return the result.

The user runs allocation profiling with:
```
./gradlew :recordbuf-benchmarks:jmh -Pjmh.args="-prof alloc"
```

The `me.champeau.jmh` plugin's `jmh` task forwards `-Pjmh.args="..."` to the JMH runner. Without the flag, only throughput is reported.

**Benchmark class structure.** Each class is `@State(Scope.Thread)` with:
- `@Setup(Level.Trial)`: build the input message (record + protobuf-java reference), pre-encode to bytes for decode benchmarks.
- Six `@Benchmark` methods: `recordEncode`, `nativeEncode`, `recordDecode`, `nativeDecode`, `interopToProto`, `interopFromProto`. Each does the operation and returns the result (JMH sinks the return value).
- The conformance message's `@Setup` populates every field: nested messages, oneofs (with active field set), maps (2-3 entries), WKTs (Timestamp.now(), Duration.ofHours(1)), repeated fields (3 elements each).

**Test scenarios.**
- `./gradlew :recordbuf-benchmarks:jmh` runs and produces JMH output with throughput for all 12 benchmark methods (6 operations x 2 message sizes).
- `./gradlew :recordbuf-benchmarks:jmh -Pjmh.args="-prof alloc"` adds allocation (B/op) to the output.
- The benchmark output table shows sensible numbers (encode/decode in the millions of ops/sec range, interop conversion lower).
- All 12 benchmark methods complete without exceptions.

**Verification.** Run the benchmarks and confirm the output table has all 12 methods with throughput and (with `-prof alloc`) allocation data.

---

### U3. Verify the module integrates with the project build

**Goal.** Confirm the new module doesn't break the existing build and that the benchmark suite is usable end-to-end.

**Requirements.** R5, R6.

**Dependencies.** U1, U2.

**Files.**
- (no new files — verification only)

**Approach.**
1. `./gradlew build` — full project build succeeds with the new module present.
2. `./gradlew :recordbuf-benchmarks:spotlessCheck` — the benchmark classes and generated code are format-clean.
3. `./gradlew :recordbuf-benchmarks:checkstyleMain` — the generated `.rec` output passes Checkstyle.
4. `./gradlew :recordbuf-benchmarks:jmh -Pjmh.args="-f 1 -wi 1 -i 1"` — a quick smoke run (1 fork, 1 warmup, 1 iteration) completes and produces output.
5. Confirm `./gradlew build` does NOT run the `jmh` task (the benchmark is not part of `check`).

**Test scenarios.**
- `./gradlew build` succeeds.
- `./gradlew :recordbuf-benchmarks:jmh -Pjmh.args="-f 1 -wi 1 -i 1"` produces JMH output.
- `./gradlew :recordbuf-benchmarks:check` does NOT trigger the `jmh` task.

**Verification.** All commands succeed. The `jmh` task is not in the `check` dependency chain.

---

## Verification Contract

### Per-unit verification

| Unit | Command | Expected |
|------|---------|----------|
| U1 | `./gradlew :recordbuf-benchmarks:generateProto` | Proto generation succeeds, produces `.rec` + protobuf-java reference |
| U1 | `./gradlew :recordbuf-benchmarks:compileJava` | Generated code compiles |
| U2 | `./gradlew :recordbuf-benchmarks:jmh -Pjmh.args="-f 1 -wi 1 -i 1"` | JMH runs, produces throughput table for all 12 methods |
| U3 | `./gradlew build` | Full project build succeeds |
| U3 | `./gradlew :recordbuf-benchmarks:spotlessCheck` | Format-clean |
| U3 | `./gradlew :recordbuf-benchmarks:checkstyleMain` | Checkstyle-clean |

### Quality gates

- **Spotless:** `googleJavaFormat` on the benchmark classes + generated `.rec` output. The benchmark module is a Java subproject, so the root `subprojects { withPlugin("java") { apply("com.diffplug.spotless") } }` block applies automatically.
- **Checkstyle:** The generated `.rec` output is in Checkstyle scope (the root `subprojects` block wires Checkstyle for all Java subprojects, including generated `.rec` dirs). The benchmark classes in `src/jmh/` are NOT in Checkstyle scope (Checkstyle is scoped to `src/main` and `src/test` source sets, not `src/jmh`).
- **Error Prone + NullAway:** Applied to `compileJava` (the generated `.rec` output). The `src/jmh/` benchmark classes are in the `jmh` source set, which has its own `compileJmhJava` task. Error Prone is NOT applied to the `jmh` source set (it's not in the root `subprojects` block's scope, which only covers `main` and `test`).
- **`-Werror`:** Applies to all Java compile tasks in the module (root `subprojects` block). The benchmark classes in `src/jmh/` must not produce javac warnings.
- **JaCoCo:** NOT applied to the benchmarks module (it's not in the root `subprojects` block's JaCoCo scope, which is only `:recordbuf-fixtures`).

### Behavioral proof

The benchmark output is the proof. A successful JMH run with all 12 methods reporting sensible throughput numbers (encode/decode in the millions of ops/sec range, interop conversion lower) demonstrates the module works end-to-end.

---

## Definition of Done

### Global

- [ ] `./gradlew build` succeeds with the `:recordbuf-benchmarks` module present.
- [ ] `./gradlew :recordbuf-benchmarks:jmh` runs and produces JMH output with throughput for all 12 benchmark methods.
- [ ] `./gradlew :recordbuf-benchmarks:jmh -Pjmh.args="-prof alloc"` adds allocation (B/op) to the output.
- [ ] The benchmark module is not part of `./gradlew check` or `./gradlew build`'s test/verification tasks.
- [ ] Spotless, Checkstyle, and Error Prone pass on the module.
- [ ] No dead-end or experimental code remains in the diff.

### Per-unit

| Unit | Done when |
|------|-----------|
| U1 | The module is registered, the build file compiles, proto generation works, and the generated code compiles |
| U2 | All 12 benchmark methods exist, compile, and produce sensible JMH output |
| U3 | Full project build succeeds; benchmark smoke run works; `check` does not trigger `jmh` |

---

## Risks & Dependencies

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `me.champeau.jmh` plugin has issues with Gradle 9.x + Kotlin DSL | Low | High (blocks U1) | The plugin is widely used and tested. If it fails, the fallback is to use the JMH Gradle plugin from a different author or to wire JMH manually (add `jmh-core` + `jmh-generator-annprocess` as dependencies and create a `JavaExec` task). |
| The Groovy build file's protobuf-gradle-plugin DSL has a bug on Gradle 9.x | Low | High (blocks U1) | The fixtures module already uses this exact pattern on the same Gradle version. The risk is low. |
| The conformance proto's `java_package` change (from `com.google.protobuf_test_messages` to `bijnagte.recordbuf.benchmarks.conformance`) breaks interop | Low | Medium | The `java_package` option is independent of the proto package. The interop option (`recordbuf.protobuf_java`) references the java_package, not the proto package. Changing the java_package should be safe. Verify by running the benchmark. |
| ThreadMXBean allocation measurement is noisy on a shared CI/dev machine | Medium | Low | This is a local-only tool. The user runs it on a quiet machine. JMH's warmup iterations absorb most noise. |

### Dependencies

- `:recordbuf-generator` must be built (`installDist`) before proto generation in the benchmarks module (same as fixtures).
- `:recordbuf-runtime`, `:recordbuf-json` must be on the compile classpath (for the generated code's imports).
- `:recordbuf-options` must be available (for the `recordbuf_options.proto` include).
- The protobuf-gradle-plugin must be on the buildscript classpath (Groovy build file's `buildscript` block).

---

## Open Questions

- **Deferred to implementation:** What are the actual throughput numbers? This is what the benchmark is for — the plan sets up the measurement, the numbers come from running it.
- **Deferred to implementation:** Should the conformance message's `java_package` be changed from `com.google.protobuf_test_messages`? The plan assumes yes (to `bijnagte.recordbuf.benchmarks.conformance`) to keep it in a distinct package. If the generated code has issues with the package change, the fallback is to keep the original package and use the fixtures' conformance output via a classpath dependency.

---

## Sources & Research

- **`me.champeau.jmh` plugin docs:** https://github.com/GradleUp/jmh-gradle-plugin — the plugin creates a `jmh` source set, adds `jmh-core` + `jmh-generator-annprocess` as `annotationProcessor` deps, and creates a `jmh` task. Version 0.7.4 is the latest (2025).
- **JMH `-prof alloc`:** JMH's built-in allocation profiler reports `B/op` and `B/call` using `ThreadMXBean.getThreadAllocatedBytes()` internally. This satisfies R4 without manual instrumentation.
- **`fixtures/build.gradle`:** The proven pattern for the protobuf-gradle-plugin's Groovy DSL, dual code generation (recordbuf + protobuf-java), the `recordbuf` plugin registration, the `package_suffix=.rec` option, the `installDist` dependency wiring, and the Error Prone config.
- **`test_messages_proto3.proto`:** The standard protobuf conformance test message with 30+ fields covering all proto3 constructs. Self-contained (only imports WKTs).
- **`Interop` inner class pattern:** Seen in the generated `InteropBytes` output — the `Interop` class has static `toProto(record)` and `fromProto(proto)` methods that do field-by-field conversion.
