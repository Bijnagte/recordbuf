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
- **Error Prone / NullAway: WIRED.** `:recordbuf-generator` and `:recordbuf-runtime`
  both apply `net.ltgt.errorprone`, add `errorprone(libs.nullaway)` +
  `errorprone(libs.error.prone.core)`, and run NullAway at error severity via
  `options.errorprone { disableAllChecks = true; error("NullAway"); option(
  "NullAway:OnlyNullMarked","true") }` (the generator also excludes its
  record-builder-generated sources via `excludedPaths`). The catalog declares
  `error-prone=2.51.0` (group `com.google.errorprone`, NOT `com.github.errorprone`
  — the latter group has no resolvable version), **`nullaway=0.14.3` (group
  `com.uber.nullaway` — NOT `com.uber`, which never resolves — a coordinate
  bug fixed this session)** and `gradle-errorprone=5.1.1`
  (`net.ltgt.errorprone`). JDK 16+ auto-forking + `--add-opens`/
  `--add-exports` are handled by the plugin; error-prone 2.51.0's min-JDK is 21
  (per the plugin docs table), so it runs cleanly on this project's JDK 25.
- **Formatting / logging:** the cross-project requirements — Spotless +
  Google Java Format and SLF4J-only logging — are **not yet wired into
  recordbuf.** Don't assume `spotlessCheck` or a logging framework are present;
  add them deliberately if the user asks. Until then match the surrounding
  (unformatted-by-tool, no-framework) code.
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
  → produces `generator/build/scripts/protoc-gen-recordbuf` (a self-contained
  launcher that execs `java -cp … bijnagte.recordbuf.plugin.Main`).
- Generate code from a `.proto` (after `installDist`):
  ```bash
  protoc --plugin=protoc-gen-recordbuf=$PWD/generator/build/scripts/protoc-gen-recordbuf \
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
the cross-project defaults; the overrides above win when they conflict
(principally the **not-yet-wired** Spotless + SLF4J-logging cross-project tooling).

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
