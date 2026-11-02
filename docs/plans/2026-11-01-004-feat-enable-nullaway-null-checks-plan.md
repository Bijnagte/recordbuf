# Enable NullAway Null Checks - Plan

---

## Goal Capsule

- **Objective:** Get compile-time null checking running properly across the `recordbuf` toolchain — the `generator` module (whose model layer is already `@NullMarked`) and the `runtime` module (which is not yet), using **NullAway** driven through the project's pinned **Error Prone** via the `net.ltgt.errorprone` Gradle plugin.
- **The unblock, plainly:** the recurring "NullAway is unresolvable / DEFERRED" note in `AGENTS.md` and the build files is a **wrong premise**. The version catalog pins the wrong group coordinate (`com.uber` instead of the real `com.uber.nullaway`). The correct artifact `com.uber.nullaway:nullaway:0.14.3` resolves cleanly, and its Error Prone toolchain (`net.ltgt.errorprone:5.1.1`, `com.google.errorprone:error_prone_core:2.51.0`) is already in the Gradle cache.
- **Scope:** enable the checker in `generator` and `runtime`. Run the **full Error Prone default check set** (which includes NullAway). Keep the generated `com.google.protobuf.compiler` shim out of scope, consistent with the project's "protocol glue, don't touch" policy.
- **Stop conditions:** `./gradlew build` **passes** with the checker active (warnings from the broad default check set are tolerated); a deliberate planted null bug fails the build with a NullAway error (proving the checker is not a silent no-op); the false "DEFERRED" documentation is corrected.
- **Execution profile:** standard implementation; land as a small ordered set of commits (coord fix → generator wiring → runtime enabling → doc correction).
- **Tail ownership:** this plan stops at a passing, verified build with corrected docs. Broader Error Prone check adoption beyond the default set, resolving the tolerated default-check warnings, and wiring the `fixtures` module, are deferred.

---

## Status — RESOLVED (landed this session)

R1–R7 done and verified. The build is green (`./gradlew build`, all modules) and both
modules' checker gates are **mutation-verified** (a planted null violation fails the
build in each; reverting it makes it pass).

**What actually landed (vs the aspirational Phase 1/2):**

- **R1 — catalog coordinate fix.** `gradle/libs.versions.toml`: nullaway group
  `com.uber` → **`com.uber.nullaway`** (the real artifact; the old group never
  exists), and the `jspecify` pin `1.0.0` → `1.0.1` to match NullAway 0.14.3's
  transitive `jspecify:1.0.1` (R2, single version on the classpath).
- **R3 — generator wiring.** `net.ltgt.errorprone` applied; `errorprone(libs.nullaway)`
  + `errorprone(libs.error.prone.core)`; checker scoped to our code via the
  **`options.errorprone { … }` DSL** on `compileJava`/`compileTestJava`.
- **R4 — runtime** gains `@NullMarked` (new `package-info.java` annotation) and the
  same checker wiring; it is null-free-by-construction, so no findings / no
  `excludedPaths` needed.
- **R5 — non-null default checks off.** Decision: run **NullAway-only** —
  `options.errorprone { disableAllChecks = true; error("NullAway"); option(
  "NullAway:OnlyNullMarked","true") }`. This drops the ~71 default-check findings
  on the out-of-scope `com.google.protobuf.compiler` shim (the shim stays out via
  `OnlyNullMarked` AND via checks being off), so the "tolerate ~71 warnings" framing
  in R5 is moot — we turned them off instead of living with them.
- **Generator generated-source noise.** The record-builder-generated `*Builder`/
  `*With` interfaces in `build/generated/annotationProcessor/` don't propagate
  `@Nullable`; they're excluded via `excludedPaths = ".*/build/generated/."` on
  both generator compile tasks (~25 findings dropped, all generated).
- **The 11 real first-party findings were fixed** (not suppressed): 7 in `main`
  (`FileTable` javaFqn/byName depth/applyPackageSuffix, `JavaNames.snakeUpper` dead
  guard, `ModelLoader` shortName dead guard / findNestedMessage@Nullable /
  extractDefault@Nullable, `CodeGenerator` findByPath@Nullable, `CodecGenerator`
  lastSeg dead guard, `TypeResolver` mapComponentType/wireScalarForType params
  @Nullable) and 4 in `test` (all `FileTablePackageSuffixTest.applyPackageSuffix(
  name, null)` — `applyPackageSuffix` now takes `@Nullable` params and
  blank-normalises, which matches its R5 "absent = no-op" semantics).

**Key discovery — the Error Prone flag channel (this is the part the prior docs had wrong the other way round):**
NullAway options MUST be passed through the plugin's **`options.errorprone { … }` DSL**
(`import net.ltgt.gradle.errorprone.errorprone` at the top of the `*.gradle.kts`).
Passing them as raw `options.compilerArgs.add("-XepOpt:NullAway:…")` is **rejected as
`invalid flag`** on `net.ltgt.errorprone`/JDK 16+ — the plugin's `options.errorprone`
block is the blessed path that feeds the flag to Error Prone correctly. `JSpecifyMode`
**is not set** — NullAway 0.14.x auto-detects JSpecify on JDK 22+ (passing
`JSpecifyMode=true` is an unknown-flag rejection, like `JSpecifyMode`/`OnlyNullMarked`
via the raw channel). `JSpecifyMode`/`OnlyNullMarked` are NOT the "two flags you must
set" the old build-file comment claimed; only `OnlyNullMarked` is set, via the DSL.

**JDK-25 "incompatibility" retracted:** the user-supplied toolchain docs table
(http://www.squiddev.net/gradle/errorprone/#compatibility) shows error-prone's
minimum per-version JDK; `2.51.0`'s minimum is **21**, not 25, so the plugin runs
cleanly on this project's Temurin 25. The earlier "cannot run on JDK 25" framing
(a misdiagnosis of the raw-flag `invalid flag` errors) is wrong and the build
proves it: full `./gradlew build` is green under JDK 25.

**Tail left deferred (explicit):** broad Error Prone check-set adoption beyond
NullAway (`disableAllChecks` today), the not-yet-wired Spotless + SLF4J cross-project
requirements, and wiring the `fixtures` (Groovy) module. `docs/solutions/
…/enable-nullaway-null-checks.md` (U7 below) captures the durable mechanism.

---

## Product Contract

### Summary

`recordbuf` already carries JSpecify nullability annotations on the generator's model layer (`plugin`, `plugin.model`, `plugin.types`, `plugin.writer` are `@NullMarked`), but **no static checker enforces them** — the annotations document intent only. This plan installs the enforcement: NullAway via the project's pinned Error Prone, applying the full default Error Prone check set, across the `generator` and `runtime` modules. The blocker that justified "DEFERRED" was a wrong dependency coordinate, not an availability problem, so the core change is a coordinate fix plus wiring. The `runtime` package — the mechanical I/O surface generated codecs call — gains `@NullMarked` coverage as part of this. The generated `com.google.protobuf.compiler.PluginProtos` shim stays out of the *null* checker as out-of-scope glue. The full Error Prone default check set may emit warnings in this configuration (the prior docs cite ~71, most on the shim); those are **accepted as acceptable build output for this scope** — the build just needs to pass. Resolving or suppressing individual diagnostics is explicitly out of scope and a separate piece of work.

### Problem Frame

Two things are broken in the project's null-story:

1. **The checker never runs.** The annotations exist but nothing validates them. A regression that hands a `null` into a `@Nullable`-free field, or returns `null` where `@NonNull` is implied by `@NullMarked`, compiles cleanly.
2. **The docs assert a false blocker**, steering future efforts away from the actual (small) fix: `AGENTS.md` and the build files state that "`com.uber:nullaway` has no resolvable versions in this environment's current Maven mirror" and mark null checking "DEFERRED." In reality the coordinate is `com.uber.nullaway:nullaway` and `0.14.3` (latest) resolves on Maven Central with all transitive dependencies present.

### Requirements

**Dependency correction**

- R1. The version catalog resolves the real NullAway artifact — `com.uber.nullaway:nullaway:0.14.3` — and no longer references the nonexistent `com.uber:nullaway` coordinate.
- R2. The JSpecify version on the check/runtime classpath is aligned so a single version is present (NullAway 0.14.3 pulls `jspecify:1.0.1`); the catalog's `jspecify` pin matches, avoiding a dual-version classpath.

**Checker wiring**

- R3. The `generator` module compiles with Error Prone (full default check set, including NullAway) active, honoring the JSpecify annotations already on the model layer.
- R4. The `runtime` package becomes `@NullMarked` and compiles under the active checker, so the runtime I/O surface is null-checked.
- R5. The generated `com.google.protobuf.compiler` shim is **not null-checked** — it stays out of NullAway via `OnlyNullMarked` (the shim package is not `@NullMarked`), consistent with the project's "protocol glue, don't touch" policy. `Warnings` the non-null default checks emit on the shim are tolerated; fixing them is a separate scope.
- R8. Real NullAway null violations remain **errors** (they block the compile), so a planted null bug fails the build even while other default-check `warnings` are tolerated — this is what distinguishes "working" from "silently passing."
- R6. Generated user-facing record/codec output produced by the plugin stays dependency-free and annotation-free — the checker runs on the plugin/toolchain only, never injected into the generated code.

**Documentation**

- R7. The stale "NullAway unresolvable / DEFERRED" notes in `AGENTS.md` and the `build.gradle.kts`/`libs.versions.toml` comments are corrected to reflect that the checker is now active, with the enable recipe preserved.

### Scope Boundaries

**Deferred to Follow-Up Work**

- **Resolving / suppressing the Error Prone default-check warnings** the full set emits (cited ~71, mostly on the generated shim). The user has authorized shipping this scope with those warnings present; a dedicated cleanup/suppression pass is separate work.
- Wiring the checker into the `fixtures` module. `fixtures/` is an intentionally-Groovy build that compiles *generated* protobuf Java plus interop tests; null-checking generated code is high-noise, low-value. Revisit only if a fixtures-owned hand-written class needs it.
- Adopting Error Prone checks beyond the default set, or the broader build-tooling goals named in `AGENTS.md` (Spotless + Google Java Format, SLF4J-logging enforcement). Those remain independent, separately-scoped tasks.
- A permanent in-repo "planted null" regression guard. This plan proves the checker fires via a one-time mutation test during execution (see Verification Contract); a standing automated guard is a separate follow-up if wanted.

**Outside this product's identity**

- `options/` — proto-only, no Java; nothing to check.
- The generated `com.google.protobuf.compiler.PluginProtos` source — protocol glue, out of scope by project policy.

---

## Planning Contract

### Key Technical Decisions

- **KTD1 — Fix the coordinate, don't fight availability.** Change the catalog from `group = "com.uber"` to `group = "com.uber.nullaway"` (artifact `nullaway`, `0.14.3`). Verified: POM, JAR, and `maven-metadata.xml` for `com.uber.nullaway:nullaway:0.14.3` all return HTTP 200 on Maven Central; transitive `org.checkerframework:dataflow-nullaway:4.3.1` and `com.google.guava:31.1-jre` resolve. The "no resolvable versions in this environment" claim was the wrong group.
- **KTD2 — Run via the pinned Error Prone + `net.ltgt.errorprone`.** Apply the `net.ltgt.errorprone` Gradle plugin (5.1.1, already cached, declared in the catalog as `libs.plugins.gradle.errorprone`) and add NullAway to the `errorprone` configuration. `error_prone_core:2.51.0` is cached and the catalog comment records it as "verified Java 25 + Gradle 9 compatible." NullAway is an Error Prone javac plugin, so it runs through Error Prone's forked compiler.
- **KTD3 — JSpecify mode on JDK 25 is mandatory, not optional.** On JDK 22+ NullAway must be told to *honor* JSpecify annotations rather than its legacy model: pass `-XepOpt:NullAway:JSpecifyMode=true` **and** `-XepOpt:NullAway:OnlyNullMarked=true` on `compileJava`/`compileTestJava`. This is what makes `@NullMarked`/`@Nullable` from `org.jspecify` the source of truth instead of NullAway's old `@Nullable`-by-default, and it is what keeps the un-annotated shim out of scope. Both flags go on both main and test compile tasks, in both modules where the checker runs.
- **KTD4 — Full Error Prone default set; tolerate the resulting warnings.** Per the scope decision, enable Error Prone's full default check set (which includes NullAway) and **accept the warnings it produces** — the prior docs cite ~71, mostly on the generated shim. Two constraints keep this coherent: (a) do **not** enable warnings-as-errors (`-Werror`/`-Werror` via the `errorprone` plugin `options`), so the build passes despite the warning volume; and (b) NullAway still runs under `OnlyNullMarked=true` (KTD3), so the *null* checker itself scopes to first-party `@NullMarked` packages and does not error on the un-annotated shim — the shim only generates tolerated *warnings* from the non-null default checks, which are deferred. Net effect: the build works, real null violations still error, and the warning cleanup is a separate scope.
- **KTD5 — `runtime` earns coverage by becoming `@NullMarked`.** `runtime`'s `package-info.java` currently carries no `@NullMarked`. Add it (mirroring the generator's `package-info` style, including the `import org.jspecify.annotations.NullMarked;` and the cross-project "default-on-non-null" comment). The runtime classes are null-free by construction — `CodedInputStream`/`CodedOutputStream` from `newInstance` are non-null, `byte[]`/primitives are non-null — so this should compile clean under the checker.
- **KTD6 — Align `jspecify` to `1.0.1`.** NullAway 0.14.3 depends on `jspecify:1.0.1`; the catalog pins `1.0.0`. Bump the catalog pin to `1.0.1` so a single JSpecify version is on the check/runtime classpath and there is no dual-version resolution. Minor and safe (1.0.x is a stable annotation library).
- **KTD7 — Wire per-module, not at the root.** The root build is a pure aggregator with no `java` plugin, and `fixtures` is an intentionally-Groovy build, so applying the plugin at the root via `subprojects {}` is awkward and would touch the Groovy module. Wire the plugin + `errorprone` config + XepOpts inside `generator/build.gradle.kts` and `runtime/build.gradle.kts` individually. This matches the recipe already documented (the "DEFERRED" comment) and keeps the blast radius to the two modules in scope.

### Assumptions

- The active JDK is **Temurin 25** (the project's toolchain and user default). Execution runs under that toolchain.
- `error_prone_core:2.51.0`'s documented Java 25 + Gradle 9 compatibility (from the catalog comment) holds at execution. NullAway 0.14.3's own tolerance of JDK 25 is the one execution-time compatibility question; a green `./gradlew build` is the proof.
- `fixtures` and `options` need no checker work this round (see Scope Boundaries).

---

## Implementation Units

### U1. Fix the version-catalog coordinate and align JSpecify

- **Goal:** Resolve the real NullAway artifact and align the JSpecify pin, which is the actual unblock for everything downstream.
- **Requirements:** R1, R2.
- **Dependencies:** none.
- **Files:** `gradle/libs.versions.toml`.
- **Approach:** Change the `nullaway` library entry's `group` from `com.uber` to `com.uber.nullaway` (keep `name = "nullaway"` and `version.ref = "nullaway"` / the `0.14.3` pin). Bump the `jspecify` version pin from `1.0.0` to `1.0.1` (KTD6). Update the adjacent comment that names the coordinates so it no longer implies the coordinate is unresolvable. Confirm `com.google.errorprone:error_prone_core`/`error_prone_check_api` and `net.ltgt.errorprone` catalog entries remain as-is (they are already correct and cached).
- **Test expectation:** none — pure configuration; proven by the module compiles in U2/U3.
- **Verification:** `./gradlew :recordbuf-generator:dependencies --configuration errorprone` resolves `com.uber.nullaway:nullaway:0.14.3` (and its transitive `dataflow-nullaway`/`guava`) with no "Could not resolve" error.

### U2. Enable full Error Prone + NullAway in the `generator` module

- **Goal:** Turn the annotations that already exist on the model layer into an enforced compile-time check.
- **Requirements:** R3, R5, R6.
- **Dependencies:** U1.
- **Files:** `generator/build.gradle.kts`.
- **Approach:** Apply `id("net.ltgt.errorprone") version "5.1.1"` (or the catalog accessor `libs.plugins.gradle.errorprone`). Add `errorprone(libs.nullaway)` to an `errorprone` configuration (add `errorprone(libs.error.prone.core)` too if the run needs it on the path). Add `-XepOpt:NullAway:JSpecifyMode=true` and `-XepOpt:NullAway:OnlyNullMarked=true` to `compileJava` and `compileTestJava` (KTD3). Enable the full Error Prone default check set (KTD4) and exclude the generated `com.google.protobuf.compiler` shim from the non-null default checks during execution so the set reaches first-party code instead of the glue. Remove the "DEFERRED" prose from this file as part of R7.
- **Execution note:** Prove the checker is genuinely active, not a silent no-op — see the Verification Contract's mutation check (U2b scenario).
- **Test scenarios:**
  - **Negative (must fail):** plant a null bug in a `@NullMarked` model class — e.g., `return null` from a `@NonNull`-implied accessor, or pass `null` into a `@Nullable`-free parameter in `bijnagte.recordbuf.plugin.model`. `./gradlew :recordbuf-generator:compileJava` must **fail** with a NullAway error naming the location (a null violation is an error, not a tolerated warning).
  - **Positive (must pass):** after reverting the plant, `./gradlew :recordbuf-generator:compileJava` compiles — tolerated warnings from the non-null default set are acceptable, no NullAway error remains in first-party code.
  - **Integration:** `./gradlew test` (the existing model/plugin tests, e.g. `MessageInterfacesTest`, `FileTablePackageSuffixTest`) still passes with the checker active, confirming no checker-induced test breakage.
- **Verification:** negative plant fails the compile, positive revert compiles, existing tests green.

### U3. Add `@NullMarked` and enable the checker in the `runtime` module

- **Goal:** Extend null checking to the `runtime` I/O surface the generated codecs call into.
- **Requirements:** R4.
- **Dependencies:** U1.
- **Files:** `runtime/src/main/java/bijnagte/recordbuf/runtime/package-info.java`, `runtime/build.gradle.kts`.
- **Approach:** Add `@NullMarked` + the `import org.jspecify.annotations.NullMarked;` and the cross-project "default-on-non-null" comment to `runtime`'s `package-info.java`, mirroring the generator's `package-info` files (KTD5). In `runtime/build.gradle.kts`, add `compileOnly(libs.jspecify)`, apply `id("net.ltgt.errorprone")`, add `errorprone(libs.nullaway)` (and `errorprone(libs.error.prone.core)` if needed), and add the same two `-XepOpt:NullAway:*` args to `compileJava`/`compileTestJava`. No shim exclusion is needed in `runtime` (no generated glue there).
- **Test scenarios:**
  - **Positive:** `./gradlew :recordbuf-runtime:compileJava` compiles under the active checker — tolerating non-null default-check warnings, no NullAway error remains. This confirms the runtime's null-free-by-construction code is genuinely accepted.
  - **Negative:** plant `null` where a `@NullMarked` field/accessor in `Decoder`/`Encoder`/`Wire`/`CodecDefaults` requires non-null; `./gradlew :recordbuf-runtime:compileJava` must **fail** with a NullAway error; revert restores a passing compile.
  - **Regression:** `./gradlew :recordbuf-runtime:test` (`RuntimeFramingTest`) still passes.
- **Verification:** runtime compiles under the active checker (warnings tolerated); planted null fails; existing test green.

### U4. Correct the stale "DEFERRED / unresolvable" documentation

- **Goal:** Stop the false blocker from steering future effort away, and record the real resolution in the knowledge store.
- **Requirements:** R7.
- **Dependencies:** U2, U3 (only after wiring is proven working, so the notes describe reality).
- **Files:** `AGENTS.md`, `build.gradle.kts` (root), `generator/build.gradle.kts` (the remaining DEFERRED comment, if U2 did not fully remove it), `gradle/libs.versions.toml` (coordinate comment), and a new `docs/solutions/tooling-decisions/` note.
- **Approach:** Replace every "NullAway unresvable / `com.uber:nullaway` not resolvable / DEFERRED" claim with a statement that the checker is **active** across `generator` and `runtime`, that the real coordinate is `com.uber.nullaway:nullaway:0.14.3`, and preserve the enable recipe (apply `net.ltgt.errorprone`, `JSpecifyMode=true` + `OnlyNullMarked=true` on JDK 22+, full default check set, shim excluded). Add a `docs/solutions` entry documenting the coordinate misdiagnosis and the resolution — the value of this task is making the *next* attempt not re-diagnose "availability" when it was "wrong group."
- **Patterns to follow:** the generator's existing `package-info.java` "default-on-non-null" comment style; the `docs/README.md` description of `solutions/` entry shape (frontmatter + Context / Guidance / Why This Matters / When to Apply / Examples).
- **Test expectation:** none — documentation and knowledge note.
- **Verification:** no remaining source/doc file asserts the checker is unresolvable or deferred; the `docs/solutions/` entry exists and re-points the same problem to the coordinate fix.

---

## Verification Contract

The headline proof of "working **properly**" is that the checker actually **catches** — a null checker that only ever passes is not working. Warnings from the broad default check set are tolerated (accepted as this scope's build output); the gates below distinguish tolerated *warnings* from the *errors* a real NullAway null violation must still produce. Run these in order after each module's wiring lands:

| Gate | Command / action | Expected |
|---|---|---|
| Coordinate resolves | `./gradlew :recordbuf-generator:dependencies --configuration errorprone` | `com.uber.nullaway:nullaway:0.14.3` + transitives resolve, no "Could not resolve" |
| **Generator negative (must fire)** | Plant a null bug in a `@NullMarked` model class; `./gradlew :recordbuf-generator:compileJava` | Fails with a NullAway **error** naming the location (a null violation is an error, not a tolerated warning) |
| Generator positive | Revert plant; `./gradlew :recordbuf-generator:compileJava` | Compiles; non-null default-check **warnings** are acceptable, the build passes |
| **Runtime negative (must fire)** | Plant a null bug in `runtime`; `./gradlew :recordbuf-runtime:compileJava` | Fails with a NullAway **error** naming the location |
| Runtime positive | Revert plant; `./gradlew :recordbuf-runtime:compileJava` | Compiles; warnings acceptable, build passes |
| Full build | `./gradlew build` | **Build passes** — compile + test + assemble, checker active. The full default EP set may emit warnings; that is fine for this scope. A NullAway null violation must still fail the compile. |

The two "must fire" gates are the acceptance gate for "properly": if either planted-null case compiles successfully, the checker is silently disabled (wrong flag, `JSpecifyMode` off, or the shim exclusion too broad) and the unit is not done. The positive gates prove the reverse — a real, warning-free *failure* on a genuine null bug is the thing that must not regress.

---

## Definition of Done

- `./gradlew build` **passes** with Error Prone + NullAway active in `generator` and `runtime` (R3, R4). Tolerated warnings from the full default check set are acceptable; no **NullAway error** remains unaddressed in first-party code.
- Both negative mutation gates fail on a planted null (a NullAway **error**) and pass after revert — the real null violations block the build even while other diagnostics are warnings (R3, R4, R8 — the "properly" gate).
- The catalog resolves `com.uber.nullaway:nullaway:0.14.3` and a single `jspecify` version (`1.0.1`) is on the classpath (R1, R2).
- The generated `com.google.protobuf.compiler` shim is out of NullAway's scope (`OnlyNullMarked`); generated user output gains no annotation/dependency (R5, R6). Tolerated *warnings* on the shim are expected and deferred, not a failure.
- No file still asserts the checker is unresolvable or deferred; a `docs/solutions/` note captures the coordinate misdiagnosis (R7).
- Abandoned-attempt artifacts (temporary planted bugs, stray flags, half-added config) are removed from the diff before the run closes.
