# AGENTS.md — recordbuf

Project context for `recordbuf`. Project rules here **override** the user-level
defaults in `~/.pi/agent/AGENTS.md` and the Java skills for this project.

## What this project is

`recordbuf` builds **`protoc-gen-recordbuf`**, a `protoc` code generator plugin
that turns `.proto` files into Java records + protobuf codecs.
- Group / version: `io.github.bijnagte.recordbuf` / `releaseVersion` (a Gradle property; default
  `0.0.1-SNAPSHOT`, set from the release tag). Co-versioned from a single `subprojects {}` block in the root `build.gradle.kts`.
- **Multi-module (flat role-named dirs under the repo root; see
  `settings.gradle.kts` — KTD1):**
  - `:recordbuf-generator` → `generator/` — the plugin itself.
  - `:recordbuf-runtime` → `runtime/` — the codec helpers generated code calls
    into (`bijnagte.recordbuf.runtime`).
  - `:recordbuf-options` → `options/` — the `recordbuf_options.proto`.
  - `:recordbuf-codegen` → `codegen/` — the generator-only Java source AST + renderer.
  - `:recordbuf-avro` → `avro/` — Avro runtime support (WKT ↔ Avro conversions).
  - `:recordbuf-json` → `json/` — Jackson support generated records use (temporal /
    bytes / `FieldMask` / `Empty` (de)serializers, `RecordbufJsonModule`, `JsonOneof`,
    `DynamicWkt`, `JsonFieldMaskFilter`). Same Error Prone + NullAway wiring as the runtime.
  - `:recordbuf-benchmarks` → `benchmarks/` — JMH benchmarks (recordbuf vs protobuf-java; local
    tool, never published, results in `benchmarks/results/`).
  - `:recordbuf-fixtures` → `fixtures/` — the compatibility suite (golden matrix and conformance
    mirror, see *Fixtures: golden matrix* below) plus the `.proto` fixtures it generates from.
  There is **no top-level `src/`**; each module has its own `src/`.
- Main class: `bijnagte.recordbuf.plugin.Main` (in `:recordbuf-generator`);
  application name `protoc-gen-recordbuf`.
- The plugin's own source lives under
  `generator/src/main/java/bijnagte/recordbuf/plugin` (subpackages
  `plugin.model`, `plugin.types`, `plugin.writer`). A `com.google.protobuf.compiler`
  shim in `generator/src/main/java/com/google/protobuf/compiler` holds the protoc
  plugin-protocol classes — treat it as generated/protocol glue; don't
  hand-rename. Generated codec records/encoders are written wherever you point
  `--recordbuf_out`; they are not part of the repo's own `src/`.

## Documented solutions

`docs/solutions/` — durable, searchable notes from the compound-engineering
workflow (`ce-compound`), organized by category with YAML frontmatter
(`module`, `tags`, `problem_type`). Relevant when implementing or debugging in a
documented area. `docs/README.md` describes the layout.

`CONCEPTS.md` (repo root) holds the project's shared domain vocabulary — entities,
named processes, and status concepts (e.g. `Codec`, the unknown-field carrier) —
consult it when orienting to the codebase or discussing domain concepts.

## Build & toolchain overrides (read these)

- **Build tool:** Gradle, **Kotlin DSL** (`build.gradle.kts`). Always use the
  wrapper `./gradlew` — never a global `gradle`.
- **Java level: this project targets Java 25** via the Gradle toolchain
  (`toolchain { languageVersion = JavaLanguageVersion.of(25) }` in both
  `:recordbuf-generator` and `:recordbuf-runtime`), **NOT**
  `sourceCompatibility`/`targetCompatibility` or `java.release`. The user's active JDK is
  Temurin 25 (LTS); we don't artificially down-level. Use **Java 25 language
  features** when building on the project's toolchain — but generated code
  that goes into *user's* classpaths should stay close to a wider baseline
  (e.g. 17+) unless asked — discuss before changing the target. Don't bump the
  target further without being asked.
- **Nullability (JSpecify): WIRED for the plugin's own model layer.** Each
  of the `bijnagte.recordbuf.plugin.*` subpackages has a `package-info.java`
  carrying `@NullMarked`, and `Optional<String>` comment / name fields have
  been rewritten to `@Nullable String` (see
  `model.Field#comment`, `model.Message#comment`, `model.Enum.EnumConstant#name`,
  `model.ProtoFile#message`/`enum`/`package`). **NullAway is WIRED and
  enforced at error severity** in both `:recordbuf-generator` and
  `:recordbuf-runtime` via `net.ltgt.errorprone` (catalog alias
  `libs.plugins.gradle.errorprone`, v5.1.1) + `errorprone(libs.nullaway)`
  (catalog coordinate is **`com.uber.nullaway:nullaway:0.14.3`**, NOT
  `com.uber:nullaway` — the latter never resolves). Each affected module
  passes its `JSpecifyMode`/`OnlyNullMarked` scope via the *blessed*
  `options.errorprone { … }` DSL (**not** `compilerArgs.add("-XepOpt:…")`,
  which is rejected as `invalid flag` on this toolchain combo); only
  `OnlyNullMarked=true` is set (0.14.x auto-detects JSpecify on 22+, so
  `JSpecifyMode` is NOT set and the `OnlyNullMarked` flag keeps the un-
  `@NullMarked` generated `com.google.protobuf.compiler` shim out of scope).
  In `:recordbuf-generator` the record-builder-generated `*Builder`/`*With`
  sources (which don't propagate `@Nullable`) are kept out via `excludedPaths`.
  Both modules are also `@NullMarked`-annotated on their `package-info.java`
  (runtime's was added this session). A real null violation fails the build —
  verified by a mutation test in both modules.
- **Record builders: WIRED for the plugin's own model records.**
  `Field`, `Message`, `Enum`, `Enum.EnumConstant`, and `ProtoFile` all carry
  `@RecordBuilder` (`io.soabase.recordbuilder.core.RecordBuilder`); the
  generated `*With` interfaces and static `*Builder` factories live on the
  build classpath and are used throughout the codebase. Generated *user*
  code produced by the plugin itself does NOT get `@RecordBuilder` — the
  plugin output stays dependency-free (per the original `record`-output
  requirement).
- **Error Prone / NullAway: WIRED — incl. the generated output.** `:recordbuf-generator`,
  `:recordbuf-runtime`, **and `:recordbuf-fixtures`** all apply `net.ltgt.errorprone`, add
  `errorprone(libs.nullaway)` + `errorprone(libs.error.prone.core)`, and run NullAway at error
  severity via
  `options.errorprone { disableAllChecks = true; error("NullAway"); option(
  "NullAway:OnlyNullMarked","true") }` (the generator also excludes its
  record-builder-generated sources via `excludedPaths`). On `:recordbuf-fixtures` this scopes, via
  `OnlyNullMarked=true`, to the recordbuf-generated `.rec` records: the generator now emits a
  per-package `@NullMarked` `package-info.java` for every non-empty generated package
  (de-duplicated across a request; a type-less file like the options proto emits none), so the
  generated records fall in NullAway's scope while the un-marked protobuf-java reference output
  (the un-suffixed natural package) and the WKT `recordbuf.RecordbufOptions` stay out of scope for
  free. Because the encode/decode arms re-invoke a guarded getter into the un-marked
  `com.google.protobuf.CodedOutputStream` boundary (which `OnlyNullMarked` leaves un-scoped), that
  re-invocation is NOT a finding; the one real generated-code finding was the builder
  copy-constructor assigning a null-guarded `byte[]` read to a `@NonNull` record field, now
  defaulted to `new byte[0]` for `@NonNull` fields. The catalog declares
  `error-prone=2.51.0` (group `com.google.errorprone`, NOT `com.github.errorprone`
  — the latter group has no resolvable version), **`nullaway=0.14.3` (group
  `com.uber.nullaway` — NOT `com.uber`, which never resolves — a coordinate
  bug fixed this session)** and `gradle-errorprone=5.1.1`
  (`net.ltgt.errorprone`). JDK 16+ auto-forking + `--add-opens`/
  `--add-exports` are handled by the plugin; error-prone 2.51.0's min-JDK is 21
  (per the plugin docs table), so it runs cleanly on this project's JDK 25.
- **Formatting: WIRED (Spotless + Google Java Format).** The root `build.gradle.kts`
  applies `com.diffplug.spotless` to **every** subproject that carries a `java` source
  set (`subprojects { withPlugin("java") { apply(...) } }`), formatting first-party
  `src/**/*.java` with `googleJavaFormat` — pinned in the catalog
  (`spotless = "8.11.0"`, `google-java-format = "2.0.0"`). So `spotlessCheck` **is** part
  of `./gradlew build`; run `./gradlew spotlessApply` before committing and match the
  Google Java Format style. Excluded from formatting: the `com.google.protobuf.compiler`
  protocol shim and the protobuf-java reference output under `build/`. `options/` applies the java plugin but
  has no Java sources, so nothing in it is formatted. The `:recordbuf-fixtures` module additionally pulls its
  recordbuf-generated `.rec` output into the formatted set, so generated records are
  expected to be format-clean.
  `googleJavaFormat` is configured with `.cleanup(false)`: Spotless 8.11 resolves GJF 2.0.0's
  cleanup machinery (`CleanupPass`) through the provisioned-jar classloader, and a daemon whose
  classloader state for that jar is stale surfaces that as `NoClassDefFoundError` on every file
  (2026-11-22; the gate went red for a whole branch for it). Cleanup off keeps the gate on the
  core formatter; unused imports stay enforced by the checkstyle `UnusedImports` rule.
- **Checkstyle: WIRED (non-formatting quality gate, complementary to Spotless).** The root
  `build.gradle.kts` applies Gradle's built-in `checkstyle` plugin to **every** Java
  subproject via the same `subprojects { withPlugin("java") { … } }` block that owns
  Spotless; `:recordbuf-options` has no Java sources, so checkstyle has nothing to read there. A single shared config lives once at
  `config/checkstyle/checkstyle.xml` (the plugin's default location, resolved root-relative, so
  it is inherited everywhere — there is no per-module config). The plugin *defaults* its tool to a
  stale `10.24.0`, so the wiring MUST pin `checkstyle { toolVersion = libs.versions.checkstyle.get() }`
  to the catalog (`checkstyle = "14.4.0"`; checkstyle 14.x needs JDK 21+, satisfied by the
  project's Java 25 toolchain — no separate `javaLauncher`). It runs as part of
  `./gradlew check`/`build` via `checkstyleMain`/`checkstyleTest`.
  - **Scope is first-party `src/` plus the recordbuf-generated `.rec` output.** The wiring sets
    each task's `source` to its own source set (`src/main`/`src/test`) **plus** the `.rec` output
    that set produces — `project.fileTree(dir="…", include=["src/${seg}/**/*.java",
    "build/generated/sources/proto/${seg}/recordbuf/**/rec/**/*.java"], exclude=["**/com/google/
    protobuf/compiler/**"])` with `seg` = `main`/`test` per task — so `checkstyleMain`/`checkstyleTest`
    each read only their own generated output (no cross-task dependency on the other set's
    `generateProto`). A source-root-relative `exclude("**/build/generated/**")` cannot see that
    prefix (Gradle adds the generated dir as its own source root), so the `.rec` dir is listed
    explicitly. The protobuf-java/Avro reference output (the natural, un-suffixed package) and the
    `com.google.protobuf.compiler` shim stay out: they are not the project's own generated code.
    The generator now emits a minimal Javadoc on **every** generated type (record, enum, map-entry,
    codec, converter — see `writer/GeneratedDocs`), so the `.rec` output is `MissingJavadocType`-clean
    and needs no suppression; the former `@SuppressWarnings("checkstyle:MissingJavadoc")` it emitted
    on converters was a legacy no-op (the live check is `MissingJavadocType`) and has been removed.
  - **Curated non-formatting set (chosen to pass the current codebase with zero churn):**
    `HideUtilityClassConstructor`, `MissingJavadocType(scope=public)`, `UnusedImports`,
    `RedundantImport`, `ConstantName`, `MemberName`, `ParameterName`, `TypeName`. **Deliberately
    *not* enforced** for now (existing test-suite style; re-enable as a small tracked
    follow-up): `AvoidStarImport` (JUnit `Assertions.*` etc.), `MethodName` (snake_case test-method
    names), `LocalVariableName` (`_e` sentinel).
  - **Config gotchas:** the XML config must carry its `<!DOCTYPE …>` (checkstyle's `XmlLoader`
    validates the root module against it; a bare root throws "Unable to create Root Module" — the
    PUBLIC DTD id resolves to a copy bundled in the checkstyle jar, so no network fetch even with
    `enableExternalDtdLoad=false`), and a `<!-- comment -->` body **cannot contain `--`** (a SAX
    parse error); use "—" or a colon instead.
- **Compiler warnings as errors (`-Werror`): WIRED project-wide.** The root `build.gradle.kts`
  adds `options.compilerArgs.add("-Werror")` in the `subprojects { withPlugin("java") { … } }`
  block — the equivalent of `options.allWarningsAsErrors = true`, which does **not** resolve in
  this root `subprojects` DSL, so the raw javac flag is used instead. Every javac warning becomes
  a build failure: most importantly `-Xlint:deprecation` (a use of a `@Deprecated` API), plus the
  default-on `-Xlint:unchecked`, and — in the modules that run Error Prone (`:recordbuf-generator`,
  `:recordbuf-runtime`, `:recordbuf-avro`, `:recordbuf-fixtures`; `:recordbuf-codegen` has no EP) —
  every default EP finding on that module, with **two scoped exceptions**, each documented at its
  site: (1) `:recordbuf-fixtures` `disable("UndefinedEquals")` — the check fires on the generated
  converters' `Map<String, _>` → `Map<CharSequence, _>` avro boundary (avro strings are
  `CharSequence`), but the keys ARE Strings, so it is a false positive on `.rec` output; (2) the
  pre-existing NullAway `excludedPaths` per module. Wired for **every** Java subproject;
  `:recordbuf-options` has no Java sources, so there is nothing for it to check.
  **Why not Error Prone/Checkstyle:** there is *no* EP or Checkstyle check
  for deprecated API usage — deprecation is a javac lint, so `-Werror` is the gate; EP's blanket
  severity flags (`-XepAllWarningsAsErrors`, etc.) only rescale EP's *own* checks, and a Checkstyle
  regex would be brittle. The escape hatch is a scoped `@SuppressWarnings("deprecation")` — currently
  the two `TypeRef` internal-bridge call sites that delegate to the still-`@Deprecated` `TypeCollector`
  API. Note protobuf-gradle-plugin 0.10.0 used to emit a Gradle *configuration-time*
  `Configuration.setVisible(boolean)` deprecation (see the note in `fixtures/build.gradle`);
  that call was removed upstream in 0.10.1 (PR #816) and the fixtures pin it, so the warning
  is gone — and in any case a configuration-time warning would not be affected by this javac
  gate.
  - **Javadoc-before-annotations (Oracle standard, this session):** `SourceRenderer` now emits the
    Javadoc block *before* the annotation list at every site (record, component, class, enum,
    enum-constant, interface, field, method), per the Oracle JavaDoc spec ("a documentation comment
    should appear before any annotation, modifier, keyword, or other Java declaration"). This is
    what made the fixtures' generated `.rec` enums `NotJavadoc`-clean, which `-Werror` (EP's
    `NotJavadoc` in the fixtures' full default check set) now enforces.

- **WKT `Duration` ↔ avro `wkt.Duration` pair (generator `AvroConverterSourceMapping`):** the avro WKT
  schema is `{seconds: long, nanos: int}` with `nanos` **non-negative** (the canonical
  seconds+fraction decomposition), so the generated conversion must use the **floor** pair
  `java.time.Duration.toSeconds()` + the fraction `toNanosPart()` (NOT `toNanos() - toSeconds()*1e9`,
  which overflows beyond ~292 years) —
  NOT `getSeconds()`/`getNano()` (which is `truncate` toward zero: for a negative duration the pair
  `{getSeconds(), getNano()}` is the *wrong* avro value, e.g. -1.5s would encode as {-1, +500M} =
  -0.5s). The reverse direction uses `ofSeconds(avro.getSeconds(), avro.getNanos() % 1_000_000_000L)`.
  A wrong getter here compiles cleanly (both getters exist on their sides) and EP only caught it at
  the *pairing* site in the fixtures' generated output — a value-corruption bug the tests never hit
  because they only used whole-hour durations. Round-trip regression tests:
  `fixtures/src/test/java/bijnagte/recordbuf/fixture/avro/AvroInteropTest`
  (`wellKnownDurationRoundTripsThroughAvro`, `wellKnownSubSecondDurationRoundTripsThroughAvro`).

- **Logging: WIRED (`:recordbuf-generator` main scope; `:recordbuf-fixtures` test scope only) —
  SLF4J via `slf4j-simple.** The generator carries `org.slf4j:slf4j-api` (`implementation`) +
  `org.slf4j:slf4j-simple` (`runtimeOnly`; `slf4j = "2.0.20"` in the catalog — latest GA, 2.1.x
  still alpha). The plugin's internal diagnostics log through it: `Main`'s fatal malformed-request
  path at `ERROR`, `Main`'s reported generation failure and `CodeGenerator`'s per-file progress at
  `DEBUG`. `:recordbuf-fixtures` carries the same pair as `testImplementation` /
  `testRuntimeOnly`: `GoldenSeeder` logs its report at `INFO` through them, so `seedGoldens`
  shows it on stderr. Why slf4j-simple specifically: its ONLY output channel is stderr, so it can
  never touch the protoc plugin's stdout `CodeGeneratorResponse` protocol; its default level INFO
  keeps the launched plugin quiet (development debug:
  `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`). That replaces the old pattern of
  temporary `System.err` debug dumps left in formatter/writer code and stripped by hand. No
  other module gets a logging framework, and no generated code (`.rec` surface) depends on one (R22).
  `Main.java`'s `System.in`/`System.out` remain the sanctioned protoc-plugin stdio protocol
  I/O, not application logging.
- **Coverage: JaCoCo, gated in `check` (`:recordbuf-fixtures` only).** `fixtures/build.gradle`
  applies `jacoco` (`jacoco = "0.8.14"` in the catalog) and wires `jacocoTestReport` and
  `jacocoTestCoverageVerification`, which `check` depends on. The scope is the runtime, json and
  avro module classes plus the module's `.rec` output; the vendored `com.google.protobuf_test_messages`
  package is excluded as new scope. The minimums are LINE 0.8, BRANCH 0.7 (raised from the
  pre-restructure baseline 0.6222 / 0.5545). Do not lower them (see the matrix rules below).
- **Protobuf runtime:** generated codecs use
  `com.google.protobuf.{CodedInputStream,CodedOutputStream}` — keep
  `com.google.protobuf:protobuf-java` (`4.36.2`, pinned in the catalog) on
  the consumer classpath.

## Fixtures: golden matrix

`:recordbuf-fixtures` is a case × format matrix. A `GoldenCase` (`fixtures/src/test/java/.../fixture/golden/`)
is one built record with one outcome per format: lossless, expected-after-trip, or not applicable. The
five `GoldenFormat` adapters live in `golden/format/`: protobuf binary (`.pb.hex`), proto3 JSON through
the generated `Interop` (`.proto3.json`), Jackson (`.jackson.json`), Avro binary (`.avro.hex`) and
canonical Avro JSON (`.avro.json`). `GoldenMatrix` turns each applicable pair into a forward and a
reverse test, and `GoldenStore` resolves goldens at `golden/<family>/<case>.<ext>`. The case sources
the seeder walks are listed in `GoldenCaseRegistry`. The README's *Compatibility suite* section has the
details. Rules:

- **Goldens are frozen.** A golden changes only by reviewed hand edit. Adding or renaming a golden
  file is fine. Changing the content of an existing golden is a **stop condition**: stop and surface
  it, do not edit it.
- **The seeder writes missing goldens only** (`./gradlew :recordbuf-fixtures:seedGoldens`). It skips
  existing goldens and never overwrites one. Jackson goldens it writes are recordbuf's own output and
  need human review before commit. `seedGoldens` is not part of `check`.
- **JSON goldens are order-sensitive.** Both sides are normalized to a pretty-printed tree; member
  order and value types count, whitespace does not.
- **The coverage gate must not be lowered.** The JaCoCo minimums in `fixtures/build.gradle` are the
  pre-restructure baseline; lowering them is a stop condition.
- **Gaps are ledgered and close one per change.** Every `GAP(<id>)` marker in a vendored proto has a
  row in `fixtures/src/test/resources/golden/gap-ledger.md`, and every construct row has a marker
  (`GapLedgerTest`). Closing one is a single change: uncomment or fix the construct, fix the generator,
  seed its goldens in every applicable format, remove the row and marker. Conformance-only rows must
  be named by a gap row in `conformance-manifest.md`.
- **The vendored corpus is minimal-edit.** `fixtures/src/main/proto/test_messages_proto3.proto` changes
  only to switch recordbuf's surfaces on and to comment out gaps (KTD11); its header lists the edits.
- **Structural checks stay structural.** Reflection, annotation and builder assertions are not goldens.
- Generator findings that are not proto constructs go in `docs/known-findings.md`, not the ledger.

## How to run / verify

- Full check (compile + test + assemble + coverage gate): `./gradlew build`
- Tests (JUnit Platform — no AssertJ/Mockito are in this project's classpath
  yet): `./gradlew test`
- Missing golden seeding (writes missing only, never overwrites):
  `./gradlew :recordbuf-fixtures:seedGoldens`
- Coverage report: `./gradlew :recordbuf-fixtures:jacocoTestReport`
- Profile a benchmark (JFR; async-profiler too when `ASYNC_PROFILER_LIB` is set):
  `./gradlew :recordbuf-benchmarks:profile -Pbench=ConformanceBenchmark.decode [-PprofileQuick]` — prints
  top CPU and allocation frames; artifacts in `benchmarks/build/profiles/`. See
  `docs/solutions/performance-issues/profiling-jmh-benchmarks-with-jfr-and-async-profiler.md`.
- Regenerate the plugin launcher for use by `protoc`:
  `./gradlew :recordbuf-generator:installDist`
  → the launched launcher is `generator/build/install/protoc-gen-recordbuf/bin/protoc-gen-recordbuf`
  (self-contained: execs `java -cp … bijnagte.recordbuf.plugin.Main` with its `lib/` populated).
  Note: `installDist` also emits a `generator/build/scripts/protoc-gen-recordbuf`, but that is a
  *staging* artifact, not the launcher -- it is meant to be run from the `install/.../bin/` location
  where its `lib/` sits; invoked in place its `APP_HOME` resolves to `generator/build` and looks
  for a `generator/build/lib` that does not exist, so `protoc` must be pointed at the `install/.../bin/`
  launcher.
- Generate code from a `.proto` (after `installDist`):
  ```bash
  protoc --plugin=protoc-gen-recordbuf=$PWD/generator/build/install/protoc-gen-recordbuf/bin/protoc-gen-recordbuf \
         --recordbuf_out=src/main/java <some>/hello.proto
  ```
- Gradle is configured for **cache + parallel** (`gradle.properties`); expect
  up-to-date cache hits and run `./gradlew --rerun-tasks` only to bypass it.

## Conventions specific to this project

- **Match existing code:** the plugin's model records ARE JSpecify-annotated
  (`@NullMarked` on the package + `@Nullable` for the `comment`/`name` /
  `message`/`enum` fields) and carry `@RecordBuilder`. Don't strip them. Add
  new annotations only when a new model record is added, not per-declaration.
  The un-annotated `types`/`writer`/top-level `Main`/`CodeGenerator` classes
  follow the older, un-annotated style until they are touched.
- Generated code (wherever you point `--recordbuf_out`; the fixtures use
  `fixtures/src/main/java`) — prefer editing the generator / source model over
  hand-patching generated outputs; if you must, say so.
- The `com.google.protobuf.compiler` shim: don't rename or refactor without a
  reason.

## Verification before "done"

1. `./gradlew build` is green (compile + `./gradlew test`).
2. If codegen was touched: `./gradlew :recordbuf-generator:installDist`, re-generate a sample
   `.proto`, and confirm the generated output compiles + round-trips.
3. No manual reformatting that the project doesn't otherwise enforce.

## Publishing (Maven Central)

- **Published modules:** `runtime`, `json`, `avro`, `codegen`, `generator`, `options`. `fixtures` is never published.
  Coordinates are `io.github.bijnagte.recordbuf:<module>:<version>`.
- **Version comes from one place:** the `releaseVersion` Gradle property, read in the root `build.gradle.kts` and
  inherited by every subproject. Do not hard-code a version in a module's build file.
- **Release = push a tag** `v<version>` (e.g. `v0.0.2`). `.github/workflows/publish.yml` validates the tag,
  runs `./gradlew build`, stages with `publishAllPublicationsToStagingRepository`, then uploads with
  `:recordbuf-generator:jreleaserDeploy`. All modules go up as ONE deployment, which waits in the Central Portal until
  someone clicks Publish (`stage = UPLOAD`). Central never overwrites a version, so a bad release needs a new version.
- **No third-party publishing plugin besides JReleaser.** The root `build.gradle.kts` applies Gradle's built-in
  `maven-publish` to the `publishedModules` set (POM, sources and javadoc jars, a `staging` repository at
  `build/staging-deploy`). JReleaser (`deploy.maven.mavenCentral.sonatype` + `signing`, in `generator/build.gradle.kts`)
  signs the staged files, checks POMs, and uploads. Credentials are `JRELEASER_MAVENCENTRAL_SONATYPE_USERNAME/_PASSWORD`;
  the key is `JRELEASER_GPG_SECRET_KEY` + `JRELEASER_GPG_PASSPHRASE`. Signing is active only when the key variable is set,
  so the GitHub release job and local runs need none. `signing.verify` is off to avoid a public-key secret. The Portal
  namespace is set explicitly (`io.github.bijnagte`); it is not the groupId. (This replaced the vanniktech
  publishing plugin; do not reintroduce it.)
- **`options` publishes a resource-only jar:** `java-library` with `src/main/proto` as a resource root, so the jar
  holds `recordbuf_options.proto` at its root.
- **The generator also ships an executable fat jar** (`shadowJar`, `com.gradleup.shadow`, catalog alias `shadow`),
  published with each release as the signed `recordbuf-generator-<version>-all.jar` (Shadow adds it to the publication
  on its own). The plain `jar` stays the library artifact, so the POM and default Gradle resolution are unchanged.
  Its manifest `Add-Exports` is derived from `javacPackages`, the same list as the launcher's `--add-exports` flags,
  because `java -jar` cannot take JVM flags. `mergeServiceFiles()` keeps the slf4j binding working.
- **GitHub releases are a second, independent channel** (`.github/workflows/release.yml`, same `v*` tag as the Central
  publish): JReleaser (`org.jreleaser`, catalog alias `jreleaser`, configured at the end of `generator/build.gradle.kts`)
  builds jlink runtime zips for linux/osx x86_64+aarch_64 (no Windows) and uploads them with a checksum file.
  Non-obvious points, each learned the hard way:
  - Each platform's zip is built on its own runner (matrix in `release.yml`: `ubuntu-latest`, `ubuntu-24.04-arm` (public
    repos only), `macos-latest`, `macos-15-intel`), so jlink links from the runner's Temurin 25 (no `jmods` needed,
    JEP 493) and the zip is named `protoc-gen-recordbuf-<version>-<platform>.zip`. The release job downloads the four
    zips into `generator/build/release-assets` and runs `jreleaserRelease` (NOT `FullRelease`, which would assemble
    again). That directory's existence switches the build: `files` (upload + checksum the zips) goes on and the jlink
    assembler goes off, because the release step otherwise demands the host's own zip. Local assembles are unaffected.
  - Do not use JReleaser's default jlink `--strip-debug` with cross-platform images: on a Linux host it runs the host
    `objcopy` over every target runtime. Native builds do not hit this.
  - Do NOT pass the `--add-exports` flags via JReleaser `jvmOptions`: it forwards them to jlink as one quoted
    `--add-options` string, which jlink bakes in garbled (a JVM "Unknown module" warning on every run). They go through
    `environmentVariables { DEFAULT_JAVA_OPTS }` instead, and `src/jreleaser/.../bin/launcher.tpl` is the stock template
    with one change (`{{{value}}}`, unescaped) because the stock one HTML-escapes `=` to `&#61;`. Regenerate it with
    `jreleaserTemplateGenerate` when bumping JReleaser and re-apply that change.
  - `jdeps` needs `multiRelease = base` because the fat jar is multi-release.
  - CI needs `fetch-depth: 0` (changelog) and `branch = main` (the tag checkout is a detached HEAD).
- **Javadoc must pass doclint** (the javadoc jar is part of publishing). Write `{@code X}` for classes that are not
  imported into the file. A `{@link}` to a missing class fails the publish.
  Javadoc runs with `-Xdoclint:all,-missing` (root `build.gradle.kts`): everything that breaks a javadoc is still
  checked, but a missing `@param`/`@return`/comment is not a warning (hundreds per module, capped at 100).
- Verify locally with `./gradlew publishToMavenLocal` (every module into `~/.m2`) or
  `./gradlew -PreleaseVersion=0.1.1-test publishAllPublicationsToStagingRepository` (what the upload sends). To exercise the
  JReleaser side without uploading, dry-run it with a throwaway key: `JRELEASER_DRY_RUN=true JRELEASER_GPG_SECRET_KEY=... 
  JRELEASER_GPG_PASSPHRASE=... JRELEASER_MAVENCENTRAL_SONATYPE_USERNAME=u JRELEASER_MAVENCENTRAL_SONATYPE_PASSWORD=p
  ./gradlew -PreleaseVersion=0.1.1-test :recordbuf-generator:jreleaserDeploy`. Use a non-snapshot version or the deployer is inactive.


## Skills (apply automatically, user-level)

`java-code-style`, `java-unit-testing`, `java-gradle-build`, and
`java-design-review` from `~/.pi/agent/skills/` are in scope here. They carry
the cross-project defaults; the overrides above win where they conflict. The
former divergences — Spotless and Error Prone/NullAway — are now wired to those
same defaults; this session additionally wired a **first-party Checkstyle quality gate**
(a non-formatting layer complementary to Spotless — see the **Checkstyle** bullet above), a
**project-wide `-Werror` gate** (`options.compilerArgs.add("-Werror")` in the root `build.gradle.kts`,
enforcing no `@Deprecated` API usage unless explicitly suppressed — see the **-Werror** bullet above),
and flipped the `SourceRenderer` to emit Javadoc *before* annotations at every site (Oracle spec).
So all the cross-project defaults are now wired, logging included: the modules with a logging
framework are `:recordbuf-generator` (SLF4J + slf4j-simple to stderr) and, in test scope only,
`:recordbuf-fixtures` (the golden seeder's report); see the **Logging** bullet above. `Main.java`'s
`System.in`/`System.out` remain the sanctioned protoc-plugin stdio protocol exception.

<!-- BEGIN COMPOUND PI TOOL MAP -->
## Compound Engineering (Pi compatibility)

This block is added by the pi-compound-engineering package.

Pi extensions used by skills shipped by this package:
- Required for full functionality: `pi-subagents` (by nicobailon) provides the `subagent` tool used by ce-compound, ce-code-review, ce-plan, ce-compound-refresh, and other parallel-agent skills.
- Recommended: `pi-ask-user` (by edlsh) provides the `ask_user` tool; skills fall back to numbered options in chat when it is missing.

Install with:
  pi install npm:pi-subagents
  pi install npm:pi-ask-user
<!-- END COMPOUND PI TOOL MAP -->
