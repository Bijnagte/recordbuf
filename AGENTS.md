# AGENTS.md — recordbuf

Project context for `recordbuf`. Project rules here **override** the user-level
defaults in `~/.pi/agent/AGENTS.md` and the Java skills for this project.

## What this project is

`recordbuf` builds **`protoc-gen-recordbuf`**, a `protoc` code generator plugin
that turns `.proto` files into Java records + protobuf codecs.
- Group / version: `bijnagte.recordbuf` / `0.0.1`, co-versioned from a single
  `subprojects {}` block in the root `build.gradle.kts`.
- **Multi-module (flat role-named dirs under the repo root; see
  `settings.gradle.kts` — KTD1):**
  - `:recordbuf-generator` → `generator/` — the plugin itself.
  - `:recordbuf-runtime` → `runtime/` — the codec helpers generated code calls
    into (`bijnagte.recordbuf.runtime`).
  - `:recordbuf-options` → `options/` — the `recordbuf_options.proto`.
  - `:recordbuf-fixtures` → `fixtures/` — interop/interface test fixtures.
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
  protocol shim, the protobuf-java reference output under `build/`, and `options/` (no
  `java` source set, so skipped). The `:recordbuf-fixtures` module additionally pulls its
  recordbuf-generated `.rec` output into the formatted set, so generated records are
  expected to be format-clean.
- **Checkstyle: WIRED (non-formatting quality gate, complementary to Spotless).** The root
  `build.gradle.kts` applies Gradle's built-in `checkstyle` plugin to **every** Java
  subproject via the same `subprojects { withPlugin("java") { … } }` block that owns
  Spotless; `:recordbuf-options` (proto only) is skipped. A single shared config lives once at
  `config/checkstyle/checkstyle.xml` (the plugin's default location, resolved root-relative, so
  it is inherited everywhere — there is no per-module config). The plugin *defaults* its tool to a
  stale `10.24.0`, so the wiring MUST pin `checkstyle { toolVersion = libs.versions.checkstyle.get() }`
  to the catalog (`checkstyle = "14.4.0"`; checkstyle 14.x needs JDK 21+, satisfied by the
  project's Java 25 toolchain — no separate `javaLauncher`). It runs as part of
  `./gradlew check`/`build` via `checkstyleMain`/`checkstyleTest`.
  - **Scope is first-party `src/` only.** Generated protobuf/recordbuf/avro output under
    `build/generated/**` is out of scope: because Gradle adds that output as its **own source
    root**, the source-root-relative `exclude("**/build/generated/**")` cannot see the prefix —
    the wiring instead sets each task's `source` to the module's `src/` tree
    (`project.fileTree(dir="src", include="**/*.java", exclude="**/com/google/protobuf/compiler/**")`),
    which excludes generated output as a side effect and is the one in-`src` exclusion (the
    `com.google.protobuf.compiler` shim). The generator emits `@SuppressWarnings("checkstyle:MissingJavadoc")` on
    generated converters, but `MissingJavadoc` is **not** a checkstyle 14 module name (the live check is
    `MissingJavadocType`), so that annotation is a **legacy no-op** while scope is first-party only.
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
  every default EP finding on that module. Wired for **every** Java subproject; `:recordbuf-options`
  (proto only) is skipped. **Why not Error Prone/Checkstyle:** there is *no* EP or Checkstyle check
  for deprecated API usage — deprecation is a javac lint, so `-Werror` is the gate; EP's blanket
  severity flags (`-XepAllWarningsAsErrors`, etc.) only rescale EP's *own* checks, and a Checkstyle
  regex would be brittle. The escape hatch is a scoped `@SuppressWarnings("deprecation")` — currently
  the two `TypeRef` internal-bridge call sites that delegate to the still-`@Deprecated` `TypeCollector`
  API. Note the protobuf-gradle-plugin 0.10.0 `Configuration.setVisible(boolean)` deprecation
  (documented in `fixtures/build.gradle`) is a Gradle *configuration-time* warning, unaffected by
  this javac-level gate.
  - **Javadoc-before-annotations (Oracle standard, this session):** `SourceRenderer` now emits the
    Javadoc block *before* the annotation list at every site (record, component, class, enum,
    enum-constant, interface, field, method), per the Oracle JavaDoc spec ("a documentation comment
    should appear before any annotation, modifier, keyword, or other Java declaration"). This is
    what made the fixtures' generated `.rec` enums `NotJavadoc`-clean, which `-Werror` (EP's
    `NotJavadoc` in the fixtures' full default check set) now enforces.

- **Logging: NOT WIRED.** No SLF4J / logging framework is on any module's classpath;
  generated code stays dependency-free. The *only* `System.out`/`System.err` usage in the
  codebase is in `Main.java`, where they **are** the protoc plugin's stdio protocol
  (a `CodeGeneratorRequest` on stdin, the `CodeGeneratorResponse` on stdout, diagnostics
  on stderr) — intentional I/O that is the one sanctioned exception to the cross-project
  SLF4J-only rule, not application logging. That rule stays aspirational here; add a
  logging framework only when real logging becomes a need.
- **Protobuf runtime:** generated codecs use
  `com.google.protobuf.{CodedInputStream,CodedOutputStream}` — keep
  `com.google.protobuf:protobuf-java` (`4.36.2`, pinned in the catalog) on
  the consumer classpath.

## How to run / verify

- Full check (compile + test + assemble): `./gradlew build`
- Tests (JUnit Platform — no AssertJ/Mockito are in this project's classpath
  yet): `./gradlew test`
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
So the **only** remaining aspirational cross-project default that is not yet wired is the
SLF4J-only logging rule: no logging framework is present, and `Main.java`'s `System.out`/
`System.err` are the sanctioned protoc-plugin stdio exception.

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
