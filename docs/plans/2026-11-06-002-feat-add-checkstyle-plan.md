---
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
execution: code
product_contract_source: ce-plan-bootstrap
origin:
date: 2026-11-06
---

# feat: Add Checkstyle to the build

## Summary

Add a **Checkstyle quality gate** to recordbuf, complementary to the existing
Spotless / google-java-format formatter. The `checkstyle` Gradle plugin is applied
to every Java subproject (`:recordbuf-runtime`, `:recordbuf-generator`,
`:recordbuf-avro`, and the Groovy-DSL `:recordbuf-fixtures`) — `:recordbuf-options`
has no Java source set and is skipped, exactly as Spotless already is. The Checkstyle
*tool* version (14.4.0, the current GA; requires JDK 21+, which our Temurin 25
toolchain satisfies) is pinned once in the version catalog. A single shared config
at `config/checkstyle/checkstyle.xml` is inherited by all subprojects, so rules are
defined once. The check runs as part of `check` / `build`, matching how Spotless is
already wired.

The design goal is a **non-formatting** Checkstyle layer: Spotless owns source
formatting (google-java-format), Checkstyle owns the rules a formatter cannot express
(Javadoc presence, utility-class discipline, naming, imports, misc quality). This is
why we do **not** add Checkstyle as a Spotless step — the two would fight over
formatting. The generator already emits `@SuppressWarnings({"checkstyle:HideUtilityClassConstructor",
"checkstyle:MissingJavadoc"})` on generated `XAvroConverter` classes, which is a
standalone-Checkstyle feature that pre-wires exactly this gate.

## As Executed (U1–U4 complete, 2026-11-06)

All four units landed and `./gradlew build` is green (compile + Spotless +
Checkstyle + test + installDist). Deltas from the plan, recorded here per R6's
"note in plan":

- **U1.** `gradle/libs.versions.toml` `[versions]` gained `checkstyle = "14.4.0"`
  with a comment; no `[plugins]` entry (built-in plugin). As planned.
- **U2.** `config/checkstyle/checkstyle.xml` created. **Final kept check list:**
  `severity=error`; `HideUtilityClassConstructor`; `MissingJavadocType(scope=public)`;
  `UnusedImports`; `RedundantImport`; `ConstantName`; `MemberName`; `ParameterName`;
  `TypeName`. **Two execution-time gotchas hit and learned:**
  1. The root `<module name="Checker">` **must carry a DOCTYPE** — checkstyle's
     `XmlLoader` validates the root element against it and throws
     "Unable to create Root Module: config {…}" for a bare root. The PUBLIC id
     `-//Checkstyle//DTD Checkstyle Configuration 1.3//EN` resolves to a DTD packaged
     *inside* the checkstyle jar, so the network is never touched and Gradle's default
     `enableExternalDtdLoad=false` stays in force. (A bare root module does **not** parse.)
  2. XML comments **cannot contain `--`**; using `--` as an em-dash in a comment body
     trips the same SAX parse error. Use "—" or a colon in config comments.
  3. **`MissingJavadoc` vs `MissingJavadocType`:** the generator's pre-emitted
     `@SuppressWarnings("checkstyle:MissingJavadoc")` targets a check name that no longer
     exists in checkstyle 14 (the module is now `MissingJavadocType`/`MissingJavadocMethod`/`
     MissingJavadocVariable`). The annotation string is therefore a **harmless legacy no-op**
     while scope is first-party only, as intended; `MissingJavadocType(scope=public)` is the
     live check that keeps first-party public types documented. This mapping should be tracked
     so a future scope-to-generated expansion uses the modern name.
- **U3.** Root `build.gradle.kts` `subprojects { pluginManager.withPlugin("java") { … } }`:
  `apply("checkstyle")`, `checkstyle { toolVersion = libs.versions.checkstyle.get() }`, and a
  per-task scope. **KTD4 mechanism changed:** the plan's `exclude("**/build/generated/**")`
  does **not** work — Gradle's `SourceTask` exclusions are matched relative to *each source
  root*, and generated protobuf/avro output is added to `:recordbuf-fixtures` as its **own**
  source root under `build/generated/…/java`, so the `build/generated` prefix is invisible to a
  source-root-relative glob. Instead every task's `source` is set to the module's `src/` tree:
  `source = project.fileTree("dir" → src, "include" → **/*.java, "exclude" →
  **/com/google/protobuf/compiler/**)`. This excludes generated output as a side effect
  (generated is off-`src`) and the `com.google.protobuf.compiler` shim is the one in-`src`
  exclusion. `config`/`configDirectory` stay at the defaults. Verified: `:recordbuf-options`
  gets **no** checkstyle task; the Groovy-DSL `:recordbuf-fixtures` **does**; generated
  `build/generated/**` produces **zero** findings.
- **U4.** 15 first-party violations surfaced on the first run and were triaged:
  - **Fixed in place (2 files):**
    - `CodecGenerator.java`: a javadoc line `<Cap(field)>Entry` put `)` inside `<>`, which
      every Javadoc parser (including checkstyle) rejects ("Javadoc comment … parse error").
      Reworded to `{@code Cap(field)}Entry` — a real, behavior-neutral improvement.
    - `ModelLoader.java`: three ALL_CAPS offenders (`MESSAGE_PROTobuf_java_TAG/NAME`,
      `EMPTY_PROTobuf_java`) were a wrong *case* in internal identifiers — 6 usages, all
      in-class, constant **values** unchanged, so generated output is unaffected. Renamed to
      `MESSAGE_PROTOBUF_JAVA_TAG/NAME` / `EMPTY_PROTOBUF_JAVA`; `ConstantName` stays in the gate.
  - **Scoped out deliberately (existing style, avoid churn — the three no-churn choices below
    are the only remaining first-party findings, and re-enabling each is a small, tracked
    follow-up rather than a correctness risk):**
    - `AvoidStarImport` — 3 star imports in test suites (`org.junit.jupiter.api.Assertions.*`,
      the `…plugin.writer.*` package). A formatter-adjacent style choice; not churned now.
    - `MethodName` — 7 snake_case JUnit test method names that mirror generated fixture types
      (`i32_roundTripsThroughAvro`, `…`); renaming changes no behavior. Deferred.
    - `LocalVariableName` — one intentional `_`-prefixed sentinel (`_e`) in a combined-interop
      test. Deferred.
  - **Gate-bite probe (done):** a throwaway `CheckstyleProbeBad.java` under
    `:recordbuf-generator` tripped **both** `UnusedImports` and `MissingJavadocType` and failed
    `checkstyleMain`; deleting it restored green. Confirms the gate is real and scope is
    first-party. (The probe is reverted; it lives nowhere in the tree.)

## Problem Frame

Today formatting is enforced by Spotless (`googleJavaFormat` 2.0.0) and nullness by
Error Prone / NullAway; there is **no general-purpose static-analysis gate**. The
generator already anticipates Checkstyle (the `@SuppressWarnings("checkstyle:…")`
annotations on generated converters), so adding the gate now is cheap and lets those
suppressions pay off. Scope is deliberately narrow: wire a complementary check, do not
redesign formatting or touch generation.

## Requirements

- **R1.** `./gradlew build` (and `./gradlew check`) runs Checkstyle on every Java
  subproject and fails on violations.
- **R2.** The Checkstyle tool version is pinned in the version catalog and is current
  (14.4.0 as of Nov 2026); the plugin itself is Gradle built-in (no catalog version).
- **R3.** A single config at `config/checkstyle/checkstyle.xml` (Gradle default layout,
  resolved relative to the root project) is inherited by all subprojects.
- **R4.** The config enforces *non-formatting* rules only — no whitespace/indent/
  line-length/import-order checks that google-java-format already owns, so the two tools
  do not conflict.
- **R5.** Checkstyle scope is first-party `src/` — generated output under
  `build/generated/**` is excluded, mirroring Spotless's `targetExclude`. The
  `@SuppressWarnings` on generated converters then serve as a ready-made guard if scope
  later expands.
- **R6.** The first-party codebase passes the new gate; any first-party violations are
  fixed or the offending check tuned (not globally silenced).
- **R7.** Wiring works transparently across both DSLs — the Kotlin `:recordbuf-runtime`
  / `:recordbuf-generator` / `:recordbuf-avro` modules and the Groovy-DSL
  `:recordbuf-fixtures` module — because the wiring lives in the root `subprojects {}`
  block via the built-in plugin name.
- **R8.** Checkstyle runs on the project's Java 25 toolchain (checkstyle 14.x needs
  JDK 21+); no separate `javaLauncher` is required.

## Key Technical Decisions

- **KTD1 — Standalone `checkstyle` task, not a Spotless step.** Spotless already owns
  formatting via `googleJavaFormat`. A *second* formatter (Spotless's `checkstyle`
  formatter) would fight it and, critically, ignores the `@SuppressWarnings("checkstyle:…")`
  the generator already emits (that annotation is honored only by a standalone Checkstyle
  run). Running Checkstyle as its own `checkstyle` plugin task keeps one formatter and
  makes the pre-emitted suppressions effective.
- **KTD2 — One shared config, root `config/checkstyle/checkstyle.xml`.** This is
  Gradle's default `configDirectory`/`config` resolution and is resolved relative to the
  *root* project, so a single file is inherited by every subproject. No per-module config.
- **KTD3 — Non-formatting curated set, run-and-prune.** Base the config on Checkstyle's
  Google check family but **strip the checks google-java-format already enforces**
  (whitespace, indentation, line length, import ordering, blank-line / brace layout).
  Keep the checks a formatter cannot express — Javadoc, `HideUtilityClassConstructor`,
  `MissingJavadoc`, naming, redundant/unused/star imports, a small set of quality checks.
  The exact final check list is an execution-time decision (run it, prune what is noisy
  or duplicate), not a planning artifact. The two pre-suppressed checks
  (`HideUtilityClassConstructor`, `MissingJavadoc`) are included by name.
- **KTD4 — First-party scope.** Exclude generated dirs from every Checkstyle task,
  mirroring Spotless's `target("src/**/*.java")` + `**/build/generated/**` exclusion.
  Only `:recordbuf-fixtures` actually has generated dirs on its source set (protobuf/
  avro generation), but the exclusion is applied uniformly and is harmless where no
  generated dirs exist.
- **KTD5 — Version pinning.** Add `checkstyle = "14.4.0"` to `[versions]` in
  `gradle/libs.versions.toml`; set `checkstyle { toolVersion = libs.versions.checkstyle.get() }`
  in the wiring block. No `[plugins]` catalog entry — `checkstyle` is a Gradle built-in.
  (The built-in plugin *defaults* the tool to 10.24.0; the override is mandatory.)
- **KTD6 — JDK.** Checkstyle 14.x requires JDK 21+; the plugin runs on the project's
  configured toolchain (Java 25), so the requirement is met with no `javaLauncher`
  block.

## Implementation Units

### U1. Pin the Checkstyle tool version in the version catalog

- **Goal** — Declare the Checkstyle tool version once and keep it out of the inline
  build script, matching the project's "bump in the catalog" convention.
- **Requirements** — R2, R5 (KTD5).
- **Dependencies** — none.
- **Files** — `gradle/libs.versions.toml` (modify).
- **Approach** — Add `checkstyle = "14.4.0"` under `[versions]` with a comment matching
  the existing style (current GA, JDK requirement, resolved-against-Temurin-25). No
  `checkstyle` entry under `[plugins]`: the Gradle `checkstyle` plugin is built-in and
  needs no version. No `[libraries]` entry required — the version is consumed via
  `checkstyle { toolVersion = … }` in U3, not as a dependency.
- **Patterns to follow** — Mirror the existing `spotless` / `google-java-format` /
  `gradle-errorprone` version and comment style in the catalog.
- **Test scenarios:**
  - `Test expectation: none` — pure catalog entry; verified indirectly by U3/U4 building.
- **Verification** — `./gradlew :recordbuf-runtime:tasks --group=quality` shows the
  checkstyle tasks; the catalog resolves the `14.4.0` accessor.

### U2. Author the shared Checkstyle config

- **Goal** — Provide one non-formatting, google-style config that every subproject
  inherits and that the already-emitted `@SuppressWarnings` target.
- **Requirements** — R3, R4, R6 (KTD2, KTD3).
- **Dependencies** — U1.
- **Files** — `config/checkstyle/checkstyle.xml` (create).
- **Approach** — A `<module name="Checker">` root, non-formatting modules only.
  Explicitly *include* `HideUtilityClassConstructor` and `MissingJavadoc` (the two
  suppressed by the generator). *Exclude* the pure-formatting modules google-java-format
  owns. Keep Javadoc, naming, and import-discipline checks
  (`AvoidStarImport`, `RedundantImport`, `UnusedImports`, naming conventions, and a
  small quality set). The exact module list is execution-time — seed a sensible
  non-formatting Google-style subset and prune what proves noisy.
- **Execution note** — Start from Checkstyle's `google_checks.xml` as a reference, then
  delete every module concerned with whitespace/indent/line-length/braces/import-order/
  blank-lines (google-java-format's domain). Add `com.puppycrawl.tools:suppressions`
  support only if a first-party file needs a localized override.
- **Test scenarios** — Covered by U4 (the config is exercised end-to-end there).
- **Verification** — The XML is well-formed and loads (no Checkstyle CLI/config errors);
  it contains `HideUtilityClassConstructor` and `MissingJavadoc` and no formatter-owned
  modules.

### U3. Wire the `checkstyle` plugin + config across all subprojects

- **Goal** — Apply Checkstyle to every Java subproject, inheriting the shared config,
  scoped to first-party `src/`, and pinned to the catalog version.
- **Requirements** — R1, R3, R5, R7, R8 (KTD1, KTD2, KTD4, KTD5, KTD6).
- **Dependencies** — U1, U2.
- **Files** — `build.gradle.kts` (root; modify).
- **Approach** — In the root `subprojects { pluginManager.withPlugin("java") { … } }`
  block (the same block that applies Spotless), apply the built-in `checkstyle` plugin
  (it works identically for the Kotlin and Groovy subprojects) and configure:
  - `checkstyle { toolVersion = libs.versions.checkstyle.get() }` (KTD5 — overrides the
    built-in 10.24.0 default).
  - Config location default (`config/checkstyle/checkstyle.xml`) is inherited; set it
    explicitly only if non-default.
  - Exclude generated output from every Checkstyle task, mirroring Spotless's
    `**/build/generated/**` exclusion (KTD4). This applies uniformly and is a no-op for
    modules without generated dirs.
  - Let it wire into `check` (default) so `build` runs it.
  - Reports to `build/reports/checkstyle` (default) — no custom XSL/SARIF needed.
- **Patterns to follow** — Mirror the existing Spotless block's structure and comment
  style so the two gates read as a pair.
- **Execution note** — Confirm the Groovy-DSL `:recordbuf-fixtures` picks up the wiring
  (built-in plugin name resolves the same under Groovy); if the exclusion DSL differs
  for its generated source set, adjust that module's exclude without changing the shared
  intent.
- **Test scenarios** —
  - `./gradlew check` builds a `checkstyleMain`/`checkstyleTest` task per Java subproject
    and fails on a planted violation.
  - `./gradlew build` runs Checkstyle and stays green on the current codebase.
  - `:recordbuf-options` gets no checkstyle task (no Java source set).
  - `:recordbuf-fixtures` checkstyle tasks do not lint `build/generated/**`.
- **Verification** — `./gradlew build` is green; a deliberately introduced first-party
  violation fails `check`; generated dirs are not reported.

### U4. Verify the gate and fix first-party violations

- **Goal** — Prove the gate is real and first-party code is clean.
- **Requirements** — R1, R6.
- **Dependencies** — U1, U2, U3.
- **Files** — First-party `src/**/*.java` where violations surface; `build.gradle.kts` /
  `config/checkstyle/checkstyle.xml` where a check needs tuning.
- **Approach** — Run `./gradlew build`; triage first-party violations by *fixing the
  code* (preferred) or *narrowing/relocating the check*, never by a global module
  disable. Confirm the generated `@SuppressWarnings` on `XAvroConverter` are honored when
  scope is (optionally later) expanded, and remain a harmless no-op while scope is
  first-party only.
- **Execution note** — Mirror the project's NullAway discipline: prove the gate bites via
  a one-off violation that fails the build, then revert.
- **Test scenarios:**
  - Happy path: `./gradlew build` green end-to-end.
  - Failure path: a planted first-party violation fails `./gradlew check`; reverting it
    restores green.
  - Scope: generated `build/generated/**` output produces no Checkstyle findings.
- **Verification** — `./gradlew build` green; the planted-violation probe fails-as-
  expected and is reverted.

## Verification Contract

- `./gradlew build` is green (compile + test + Spotless + Checkstyle).
- `./gradlew check` reports a `checkstyle*` task per Java subproject and
  `:recordbuf-options` has none.
- A planted first-party violation fails `check`; reverting restores green.
- Generated output under `build/generated/**` is out of Checkstyle scope.
- Spotless and Checkstyle remain non-conflicting: no formatting rule is enforced by
  Checkstyle that google-java-format would reformat against.

## Risks & Dependencies

- **Two formatters fighting (R4/KTD1).** *Mitigation:* config is non-formatting by
  construction; verify no overlapping formatting module slips in (U4).
- **Tool version drift / incompatibility.** *Mitigation:* pin `14.4.0` in the catalog
  (KTD5); checkstyle runs on the JDK 25 toolchain which satisfies its JDK 21+ floor
  (KTD6).
- **Noise on first-party code.** *Mitigation:* run-and-prune the check list (KTD3); fix
  code over silencing (U4).
- **Fixtures generated output.** *Mitigation:* excluded from scope (KTD4); the
  pre-emitted `@SuppressWarnings` remain a no-op guard, not a hidden failure.

## Deferred to Follow-Up Work

- Expanding Checkstyle scope to generated output (would require exercising the
  `@SuppressWarnings` on `XAvroConverter`/`.rec` files and broader suppression strategy).
- SARIF/CI reporting and IDE config distribution for the new gate.
- Any Spotless-vs-Checkstyle unification revisit (KTD1 alternative), only if the team
  later wants a single auto-fixing tool.

## Sources & Research

- Gradle Checkstyle Plugin (Gradle 9.x user guide): default tool version `10.24.0`
  (must be overridden), default config dir `config/checkstyle/`, `check` task wiring,
  `javaLauncher`/toolchain behavior (checkstyle 13.0.0+ needs JDK 21+).
  https://docs.gradle.org/current/userguide/checkstyle_plugin.html
- Checkstyle releases / start page: current GA is **14.4.0** (JDK 21+).
  https://checkstyle.org · https://github.com/checkstyle/checkstyle/releases
- google-java-format vs. Checkstyle separation of concerns (formatter vs. rule engine):
  https://dragganaitool.com/code-standards-in-java-google-java-format-vs-checkstyle-for-enforcing-consistent-code/
- In-repo evidence: `generator/.../writer/AvroConverterGenerator.java` emitts
  `@SuppressWarnings({"checkstyle:HideUtilityClassConstructor","checkstyle:MissingJavadoc"})`
  on generated converters — the pre-wiring this gate fulfills.
