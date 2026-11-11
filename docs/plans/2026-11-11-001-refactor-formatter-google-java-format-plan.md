---
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
title: "Formatter: Google Java Format SPI - Plan"
type: refactor
date: "2026-11-11"
product_contract_source: ce-brainstorm
origin: docs/plans/2026-11-11-001-refactor-formatter-google-java-format-plan.md
execution: code
---

# Formatter: Google Java Format SPI - Plan

## Goal Capsule

- **Objective:** Make recordbuf-generated code Google Java Format-clean by routing all
  rendered source through a pluggable `Formatter` SPI — identity no-op default kept as
  the GJF-free `:recordbuf-codegen` baseline; a Google Java Format-backed
  implementation lives only in `:recordbuf-generator`.
- **Bottom line:** Add a `Formatter` SPI to `:recordbuf-codegen` (GJF-free,
  preserves R22) with an identity default and a GJF-backed implementation in
  `:recordbuf-generator`, plus the catalog alias and `--add-exports` JVM wiring GJF
  needs on the plugin's JVM and the generator's test JVM.
- **Decision:** Locked — full default GJF options, fail-loud on unparseable source,
  identity default preserved so the codegen module stays GJF-free.
- **Approach in one line:** Identity default, GJF-backed override; SPI path is the sole
  route from rendered source to file content, with the GJF impl isolated behind a
  checked-exception wrapper.
- **Done when:** `:recordbuf-codegen` tests pass with the GJF-free identity path; a
  small `CodeGeneratorRequest` driven through `CodeGenerator` produces output that
  satisfies `format(format(x)) == format(x)` for every file; `./gradlew
  :recordbuf-generator:installDist` produces a launcher that formats its output;
  `./gradlew build` green.
- **Stop conditions / escalation points:** (1) If GJF's `FormatterException` can't be
  cleanly wrapped in `GoogleJavaFormatter` without leaking checked-exception shape into
  the SPI, re-open the exception-handling KTD. (2) If `--add-exports` on a modern JDK
  causes GJF's reflective calls into `com.sun.tools.javac.*` to fail even with the
  canonical export set, escalate before further work. (3) Any requirement change requires
  going back to `ce-brainstorm`.

---

## Summary

Route recordbuf-generated Java source through a pluggable formatting seam instead of
shelling out to an external tool. The seam is a `Formatter` SPI added to `:recordbuf-codegen`
with an identity no-op default; the Google Java Format-backed implementation and its
runtime dependency live only in `:recordbuf-generator`. This keeps GJF off the
generated code's classpath (R22 held) and off the `:recordbuf-codegen` module's
main classpath, while making the plugin's own output GJF-clean by default.

The brainstorm confirmed feasibility: GJF `2.0.0` is already pinned in the version
catalog (the Spotless pin), its in-process API `com.google.googlejavaformatter.Formatter`
runs on any JDK >= 21 (the project's Temurin 25 satisfies), and the renderer's
output already compiles — a trivially satisfiable precondition for GJF, which only
reformats *parseable* Java. The one cost is wiring GJF's `--add-exports` into the
launched plugin's JVM and the generator's test JVM.

## Problem Frame

Today the renderer emits Java source with its own layout rules. A prior refactor
(`javapoet`→`writer`, plan `2026-11-07-002`) already established the renderer's
contract. The next step — make *that* output Google Java Format-clean by default —
was deferred because it requires an external, JVM-level formatter (GJF) that would
otherwise leak into the generated code's classpath.

The brainstorm confirmed GJF runs in-process on Temurin 25; the only open cost is
the `--add-exports jdk.compiler/...` wiring. This is now in scope.

The renderer's `SourceRenderer.render(JavaFile)` returns a `String` today; the
codegen module is `java-library` with `JSpecify` `compileOnly` and *no main
runtime dependency*. That constraint is why the formatting seam is an SPI, not a
direct dependency: GJF (a Guava/CommonMark-carrying runtime jar) stays out of
`:recordbuf-codegen` entirely.

## Planning Contract

### Key Technical Decisions

**KTD-1 — Formatter SPI split by module.** The `Formatter` interface and an
`IdentityFormatter` default live in `:recordbuf-codegen` (which is GJF-free; its
`build.gradle.kts` has no main runtime dependency). The GJF-backed implementation
(`GoogleJavaFormatter`) and the `google-java-format` dependency live only in
`:recordbuf-generator`. Rationale: R22 (the generated `.rec` surface stays
dependency-free) is held transitively because `:recordbuf-codegen` has no
GJF import, and the generated codec types never carry GJF on their consumer
classpath. Alternatives considered: a `--format` flag on the plugin (rejected —
per `docs/README.md`, plugin-CLI parsing is out of scope), and a build-time
external `spotlessApply` step (rejected — it wouldn't reach the plugin-launch
context where the operator runs `protoc`). See KTD-5 for the SPI package location.

**KTD-2 — Formatter threaded as per-request state.** `CodeGenerator` already
holds `table`, `oneofRegistry`, `emittedPackageInfos`, and `bridgeContext` as
per-request fields. The GJF-backed `Formatter` joins that set — a single
construction site per `CodeGenerator.generate(request)` call, threaded into every
`SourceRenderer.render(...)` site in the same request. Rationale: matches the
existing statelessness contract — each request constructs fresh formatter state,
and the plugin itself is a single-call entry point (`Main` → one
`CodeGenerator.generate(request)` per invocation), so there's no shared
accumulator to reason about. Alternatives considered: a static default (rejected —
`CodeGenerator.generate(request)` is already the natural construction site and
per-request state mirrors every other field on the class).

**KTD-3 — Formatter interface shape.** `public interface Formatter { String format(String source);
}` — single method, unchecked, no checked exception. Rationale: identity path is
`return source;` (one-line, no throw), and the GJF impl isolates
`com.google.googlejavaformatter.Formatter.FormatterException` as a checked
exception by wrapping it in an `IllegalStateException` at the `GoogleJavaFormatter`
boundary. This keeps the SPI shape small (one method, no error type), and the
`IdentityFormatter` default is trivially testable. Alternatives considered: a
`Formatter` interface carrying a checked `FormatterException` (rejected — forces
checked propagation through `render` and every caller, and there's no recovery
path, so the checked shape is overhead with no value).

**KTD-4 — `render(JavaFile)` preserves the identity default.** Add an overload
`public static String render(JavaFile file, Formatter formatter)` to
`SourceRenderer`; `render(JavaFile)` stays as a thin delegator that passes
`Formatter.IDENTITY` (a `public static final Formatter` on the SPI type;
`{ return source; }`). Rationale: no caller of `render(JavaFile)` —
`:recordbuf-codegen`'s own tests and downstream modules — is forced to pass a
formatter; the GJF-backed override is opt-in to the caller (only `CodeGenerator`
wires it today). Alternatives considered: making `render(JavaFile)` always use GJF
(rejected — would leak GJF into `:recordbuf-codegen`'s main classpath and break
its GJF-free test-only dependency posture).

**KTD-5 — SPI location: `bijnagte.recordbuf.codegen.source`.** `Formatter` and
`IdentityFormatter` live in the same package as `SourceRenderer` (`bijnagte.recordbuf.codegen.source`),
so `SourceRenderer`'s `Formatter` reference is package-private (not a new
public API surface on `codegen`). Rationale: `:recordbuf-codegen` already
exposes a public `SourceRenderer.render` contract; widening its package with a
new `format` subpackage would grow the module's external surface for no gain, and
keeps the codegen module's public API minimal. Alternatives considered: a new
`bijnagte.recordbuf.codegen.format` subpackage (rejected — no benefit over
in-package placement, and a new package would need its own `@NullMarked`
`package-info.java`).

**KTD-6 — GJF is applied to *all three* rendered output surfaces.** Every file
emitted through a `CodeGenerator` request — top-level record files
(`generateRecord`), top-level enum files (`generateEnum`), and the per-package
`package-info.java` (`emitPackageInfo`, currently a raw string-concat in
`CodeGenerator`, not a `JavaFile` render) — passes through the formatter before
the content is added to the response. Rationale: the brainstorm's R2 lists
"package-info files" as a consumer; if the record/enum output is GJF-clean but
`package-info.java` isn't, the whole generated tree isn't GJF-clean and the
operator's `spotlessCheck` would still reformat it. Alternatives considered:
format only `record`/`enum` output (rejected — leaves `package-info` out of
scope and R2's consumer set incomplete).

**KTD-7 — GJF exception isolation in `GoogleJavaFormatter`.** `Formatter.formatSource`
throws `FormatterException` (checked) and may also surface an `IOException`-like
type. `GoogleJavaFormatter.format(String source)` catches `FormatterException` and
re-throws as `IllegalStateException("Failed to Google-Java-Format generated source: " +
e.getMessage(), e)`. Rationale: the SPI's `String format(String)` is unchecked, so
the wrapping happens at the GJF boundary; a propagation failure is a hard failure
that the `CodeGenerator` surfaces as a plugin-level error (a `catch` or a natural
JVM exit via an uncaught exception — the current `Main` already exits with a
non-zero status for any uncaught exception). Alternatives considered: propagate the
`FormatterException` (rejected — forces checked-exception handling on the SPI),
and swallowing the exception and formatting *best-effort* (rejected — violates the
brainstorm's locked "fail loud" decision).

**KTD-8 — `--add-exports` JVM wiring scope.** GJF's `Formatter` class pokes
`com.sun.tools.javac.api`, `com.sun.tools.javac.tree`, `com.sun.tools.javac.util`,
and `com.sun.tools.javac.main` (or subsets thereof) via reflection. These packages
are not exported from the JDK's `jdk.compiler` module by default. The canonical
export set for GJF on modern JDKs is:

```
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
```

(plus, on JDK 16+, the equivalent `--add-opens java.base/java.lang=ALL-UNNAMED`
that the project already sets in `JUNIT_VM_ARGS`; if the launched plugin's JVM
needs it too, add it there as well). Rationale: the exact subset is a runtime
concern — GJF itself documents its JVM requirements, and the project can start
from this canonical set and trim if a subset suffices. Deferring the *exact*
subset to implementation (U4) keeps the plan unblocked. Alternatives considered:
hard-coding the set now (rejected — the exact set is a runtime observation, not
a fixed contract, and the canonical set above is a reasonable starting point).

**KTD-9 — Package-info.java formatter routing.** `CodeGenerator.emitPackageInfo`
is a `private static` method today and emits a raw string. To route it through the
formatter (KTD-6), it must become an *instance* method (or take a `Formatter`
argument). The simplest correct choice is: make it an instance method that reads
`this.formatter` (the per-request field from KTD-2) and applies it to the
concatenated string. Rationale: minimal change to a static helper with no shared
state, and consistent with the other per-request fields on `CodeGenerator`.
Alternatives considered: pass `Formatter` as an extra parameter (rejected — the
other per-request fields are instance fields, and this one matches the pattern).

### Assumptions

- **Assumptions** (each is load-bearing; any that turns out to be false changes
  the plan materially):
  - A1. The project's Temurin 25 satisfies GJF 2.0.0's minimum JDK 21 — verified
    by the brainstorm's primary-source check of the GJF 2.0.0 POM.
  - A2. The `--add-exports jdk.compiler/...` wiring is sufficient for GJF to run
    in-process on the JVM that `protoc-gen-recordbuf` launches — to be verified
    by U2/U4 (see Deferred Implementation Notes).
  - A3. The `google-java-format` `implementation` dependency added to
    `:recordbuf-generator` does not transitively pull any class that a
    generated-code consumer would compile against (i.e., GJF stays a
    generator-only concern, not a generated-code dependency). Verified by
    inspecting GJF's `POM` and confirming the generated codec types have no import
    reference to `com.google.googlejavaformatter.*`.
- **Non-goals** (explicit from the brainstorm's Scope Boundaries):
  - No plugin-CLI flag for `--format` (per `docs/README.md`, plugin-CLI parsing
    is out of scope for this project).
  - No build-time external formatters on the plugin's output (e.g., a
    post-codegen `spotlessApply` step).
  - No reformatting of the plugin's first-party source (it's already GJF-clean via
    `spotlessCheck`); this plan is about *generated code only*.
- **Sequencing** (dependency order): U1 → U2 → U3 → U4. U1 lands the SPI and the
  identity default in `:recordbuf-codegen` with no other consumer changes
  (safe, self-contained). U2 adds the catalog alias, the GJF dependency, and the
  `GoogleJavaFormatter` impl in `:recordbuf-generator`, plus the JVM wiring
  (build-time, isolated). U3 wires the formatter into `CodeGenerator` (the only
  render site). U4 is the end-to-end launch verification.

### Deferred to Implementation

These are genuine execution-time unknowns, not planning-time guesses:

- **D1. Exact `--add-exports` subset.** Start from the canonical set in KTD-8 and
  trim as GJF reveals its actual runtime needs (e.g., `com.sun.tools.javac.main`
  may not be needed). Verified by U2/U4 with a runtime smoke test.
- **D2. Formatter field name on `CodeGenerator`.** A name like `formatter`
  (or `sourceFormatter`); a trivially local decision.
- **D3. FormatterException message prefix.** The `IllegalStateException` message
  in `GoogleJavaFormatter.format` is a trivial string; it exists to make a
  GJF-format failure actionable, not to encode a contract.

---

## Product Contract

### Requirements

**Packaging & Module Layout**
- R1. `:recordbuf-codegen` gains a `Formatter` SPI with an identity no-op
  default, and `SourceRenderer.render(JavaFile)` keeps its current no-op behavior
  (identity path) so the module compiles and tests pass with no GJF dependency.
  The SPI is the sole path from rendered source to file content — a GJF-backed
  override is the only consumer today, so no other code path is affected.
- R2. The `:recordbuf-generator` supplies a Google Java Format-backed
  `Formatter` (the default for the plugin's runtime) and wires it into every
  rendered surface — top-level record files, top-level enum files, and the
  per-package `package-info.java` — so GJF sees the full output surface.
- R3. The `Formatter` SPI is pluggable: an implementation is a constructor/
  static-factory choice, not a build-time hardcode. `IdentityFormatter` is the
  default when no formatter is wired; a GJF-backed implementation is opt-in
  (chosen by `CodeGenerator` today). `render(JavaFile)` never forces GJF on a
  caller.

**Formatter Behavior**
- R4. Generated output is Google Java Format-clean under the *standard default*
  GJF options (no custom options passed). This is the "spotless parity" bar:
  `format(format(x)) == format(x)` for every generated file, including
  `package-info.java`.
- R5. When the generated source is not GJF-parseable, the plugin fails the build
  with a clear error message naming the file and the GJF exception, rather than
  emitting unformatted source. This is the "fail loud" behavior.

**JVM Wiring**
- R6. The `--add-exports jdk.compiler/...` wiring is added wherever GJF runs in
  the plugin's JVM: the launched plugin (`application`/`installDist` and any
  `JavaExec` the generator uses), and the generator's test JVM. First-party
  Spotless/Checkstyle JVMs are out of scope for this change.
- R7. The exact `--add-exports` subset is deferred to implementation (D1); the
  plan gives a canonical starting set that GJF documents.
- R8. The JVM wiring touches only the JVM where GJF runs (the launched plugin
  and any test that exercises it); it does not alter the first-party
  `spotlessCheck`/`checkstyleMain` JVMs.

**Test Coverage**
- R9. The new `GoogleJavaFormatter` is tested for idempotency (format twice
  equals format once), the error path (a malformed-source input throws), and one
  realistic input (a small record source). The existing plugin tests (`writer`
  `*Test`, `model/*Test`, `OneofEmissionTest`, etc.) must all pass after formatting
  is wired — their `contains`-based assertions are format-independent, so no
  change is expected there.

### Acceptance Examples

- **AE1 (happy path):** A small `Sample.proto` with nested types, WKT fields,
  oneofs, and a proto3-optional field produces a `Sample.java` record that is
  Google Java Format-clean (re-running `./gradlew spotlessCheck` after running
  the plugin is a no-op).
- **AE2 (error path):** If a generated file is not parseable by GJF (a future
  bug in `SourceRenderer`, not a current-state concern), the plugin fails the
  build with a clear error message naming the source. This is a regression
  *guard*, not a current-state behavior — today's renderer output is known to
  compile.
- **AE3 (package-info consistency):** A `.proto` file that emits a
  `package-info.java` (a package with top-level types) produces GJF-clean
  `package-info.java` content.

### Scope Boundaries

**Deferred to Follow-Up Work:**

- **D1. Formatter options** — GJF today is applied with defaults; a future
  options surface (e.g., a `--formatter-options` flag) is out of scope.
- **D2. Formatter selection UI** — a `--format=none|gjf` flag is a plugin-CLI
  change that this project has explicitly declined (`docs/README.md`), and is out
  of scope. The SPI seam makes a future flag trivial to add, without this change.
- **D3. Non-Java output formatting** — Avro-generated Java is not in scope for
  this change; it's a separate concern (the Avro bridge is wired via R1/R2 only
  for the proto3/recordbuf path).
- **D4. GJF version bump** — the project pins GJF 2.0.0 via the version catalog;
  a bump is a trivial catalog edit, not a code change.
- **D5. Formatter for the plugin's own source** — first-party code is already
  GJF-clean via `spotlessCheck`; this change is for *generated code only*. No
  reformat of `src/main/java` or `src/test/java` is in scope.

**Outside this Product's Identity:**

- The plugin is a `protoc` code-gen plugin. It produces Java source as text,
  not bytecode. The formatter produces *text* — the operator's build compiles
  that text. No formatter runs at *runtime* of a generated codec, and no
  formatter is on the generated code's classpath. Generated-code GJF-cleanness
  is a *compile-time* property, not a runtime one — the formatter runs in the
  plugin's JVM, not in the codec's consuming JVM.
- The plugin does not (and should not) ship a `Formatter` implementation on the
  generated code's *runtime* classpath — that would violate R22 (generated types
  stay dependency-free). The SPI lives in the codegen module; the GJF impl lives
  in the generator module; neither is on the generated code's classpath.

### Open Questions

**Blocking for this plan:** none. All three brainstorm decisions are locked (full
default GJF, fail loud, accept runtime distribution + `--add-exports` wiring).

**Deferred items (with defaults):**
- D1 — the exact `--add-exports` subset — start from the canonical set in KTD-8
  and trim after a runtime smoke test; do not block on it.
- D2 — the `formatter` field name on `CodeGenerator` — a trivially local naming
  choice in U3.
- D3 — the `FormatterException`→`IllegalStateException` message prefix — a
  trivial string in U2.

### Sources / Research

- The brainstorm's feasibility check — GJF 2.0.0 is on Maven Central, pinned in
  the project's catalog (already the Spotless `googleJavaFormat` version ref),
  its in-process API is `com.google.googlejavaformatter.Formatter.formatSource(String)`
  (a rename of the pre-2.0 `format`), and its runtime deps are Guava + commonmark
  (0.29.0, pinned in the catalog). No secondary web research was needed.
- The brainstorm's verification that this JVM's `--add-exports jdk.compiler/...`
  wiring is the only remaining cost; no runtime behavior depends on it being
  perfect — a runtime smoke test in U4 will reveal and correct any subset gaps.

---

## Implementation Units

### U1. Formatter SPI + Identity Default in `:recordbuf-codegen`

**Goal —** Add a `Formatter` SPI and an `IdentityFormatter` default to `:recordbuf-codegen`,
and thread the formatter through `SourceRenderer.render` as an overload. The
`render(JavaFile)` no-arg-formatter path stays a default-identity delegator. This
is a self-contained change that compiles and tests green with zero GJF on the
codegen module's classpath.

**Requirements —** R1, R3.

**Dependencies —** None.

**Files —**
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Formatter.java`
  (new)
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/IdentityFormatter.java`
  (new)
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/SourceRenderer.java`
  (modify)
- `codegen/src/test/java/bijnagte/recordbuf/codegen/source/FormatterTest.java`
  (new)

**Approach —**
- `public interface Formatter { String format(String source); }` in
  `bijnagte.recordbuf.codegen.source`. One method, unchecked, no error type (KTD-3).
  Also a `public static final Formatter IDENTITY = source -> source;` constant on
  the interface (a trivial one-liner that's the identity default; `CodeGenerator`
  doesn't need to `new` anything for the identity path).
- `public final class IdentityFormatter implements Formatter { ... }` — a trivial
  identity. (The `IDENTITY` constant on the interface is the primary path; this
  class exists for a future *named* identity, e.g., a test helper or a
  `--format=none` override. It's a one-liner; keep it tiny.)
- `SourceRenderer.render(JavaFile file, Formatter formatter)` — a new overload
  that applies `formatter.format(...)` to the string returned by the existing
  logic. `render(JavaFile)` delegates to `render(file, Formatter.IDENTITY)`.
- (No new `package-info.java`.) `:recordbuf-codegen` currently carries no
  `package-info.java` and is not NullAway-scoped; adding a `@NullMarked`
  marker is a separate concern for a future NullAway rollout and is out of
  scope here. The SPI is placed in-package and unannotated, matching the
  module's current convention.

**Patterns to follow —**
- `:recordbuf-codegen`'s existing `SourceRenderer.java` (the file being
  modified) — the render contract is two-phase; the formatter is a third pass
  over the whole output string.
- `:recordbuf-codegen`'s existing `Javadoc.java`/`JavadocBuilder.java`
  (a builder pattern in the same package) for the style of a tiny helper class
  with a `build()` method.
- `:recordbuf-codegen`'s `source/` package has no `@NullMarked` marker yet
  (the module is not NullAway-scoped); the new `Formatter`/`IdentityFormatter`
  are plain unannotated types, consistent with the rest of the module.
  A future `@NullMarked` rollout for `:recordbuf-codegen` is out of scope for
  this formatter work.

**Test scenarios —**
- `FormatterTest`: `Formatter.IDENTITY.format("abc")` returns `"abc"` unchanged
  (identity). `Formatter.IDENTITY.format("")` returns `""` (empty input).
- `SourceRendererFormatTest`: render a small `JavaFile` with a `Formatter.IDENTITY`
  produces the *same* output as rendering it with no formatter (i.e., identity is
  a no-op pass).
- `SourceRendererFormatTest`: render a small `JavaFile` with a `source ->
  source.toUpperCase()` (a trivial custom Formatter) produces the uppercased
  output (proving the overload wires correctly and the formatter runs post-render).

**Verification —**
- `:recordbuf-codegen` compiles with no GJF on its main classpath
  (`./gradlew :recordbuf-codegen:compileJava` — no new dependency added there).
- `:recordbuf-codegen` tests green (`./gradlew :recordbuf-codegen:test`),
  including the existing `WriterTest`/`RecordTest`/etc., which use
  `render(JavaFile)` and get the identity path for free.
- `./gradlew :recordbuf-codegen:spotlessCheck` stays green — the new
  files are GJF-clean by the same rule this plan imposes (the module's
  existing `spotlessCheck` gate formats its `src/main/java`).

**Execution note —** Prefer a minimal change: the SPI is one small interface,
the identity impl is a one-liner, and the `SourceRenderer` overload is a
two-line add. No other code changes. Keep `:recordbuf-codegen`'s
`java-library` with zero main runtime deps — no GJF added here.

---

### U2. GJF-backed `Formatter` impl + catalog alias + JVM wiring in `:recordbuf-generator`

**Goal —** Add `GoogleJavaFormatter` in `:recordbuf-generator` (implementing
the SPI from U1), wire the `google-java-format` dependency, and add the
`--add-exports jdk.compiler/...` JVM args to the generator's `Test` task and the
launched plugin's JVM. This unit is isolated to `:recordbuf-generator` and its
build config; it doesn't touch `:recordbuf-codegen` or downstream modules.

**Requirements —** R2, R4, R5, R6, R7.

**Dependencies —** U1 (the SPI).

**Files —**
- `gradle/libs.versions.toml` (modify: add a `[libraries]` alias
  `google-java-format = { group = "com.google.googlejavaformat", name =
  "google-java-format", version.ref = "google-java-format" }` — the
  `google-java-format = "2.0.0"` version ref already exists; only the `[libraries]`
  entry is new).
- `generator/build.gradle.kts` (modify)
- `generator/src/main/java/bijnagte/recordbuf/plugin/GoogleJavaFormatter.java`
  (new)
- `generator/src/test/java/bijnagte/recordbuf/plugin/GoogleJavaFormatterTest.java`
  (new)

**Approach —**
- `GoogleJavaFormatter` implements `Formatter` from U1. Its `format(String source)`
  calls `com.google.googlejavaformatter.Formatter.createDefault().formatSource(source)`
  (or `new Formatter().formatSource(source)` — check the API for 2.0.0; the
  constructor form is likely `Formatter()` with a `createDefault()`
  factory; the actual API is to be confirmed in U2). Wrap
  `FormatterException` (checked) as `IllegalStateException` (KTD-7).
- `generator/build.gradle.kts` adds:
  - `implementation(libs.google.java.format)` — the GJF jar on the main classpath
    (it doesn't leak to generated-code consumers: R22 holds because `CodeGenerator`
    is generator-internal only and generated types never import
    `com.google.googlejavaformatter.*`).
  - A `--add-exports` wiring for the `Test` task (extend the existing
    `tasks.withType<Test> { jvmArgs(...) }` or `applicationDefaultJvmArgs`
    block in the file — the project already sets `JUNIT_VM_ARGS` for the
    `--add-opens java.base/java.lang` that error-prone needs, so GJF's export
    set joins that list) and the `application`/`installDist` tasks (via
    `application { applicationDefaultJvmArgs(...) }` or
    `tasks.withType<JavaExec> { jvmArgs(...) }`).
- The exact `--add-exports` subset is deferred to D1 (start from the canonical
  set in KTD-8; trim after a runtime smoke test).

**Patterns to follow —**
- `CodeGenerator.java`'s per-request state pattern for `GoogleJavaFormatter`'s
  construction (U3 will use it here).
- `generator/build.gradle.kts`'s existing `errorprone`/`NullAway` `jvmArgs`/
  `compilerArgs` block for the shape of a JVM-args configuration.

**Test scenarios —**
- `GoogleJavaFormatterTest`: a small GJF-formatted input is idempotent
  (`format(format(x)) == format(x)`).
- `GoogleJavaFormatterTest`: a malformed input (e.g., a missing closing brace)
  throws `IllegalStateException` (wrapping the underlying GJF exception).
- `GoogleJavaFormatterTest`: a small record source (e.g., a 20-line Java record
  with a nested codec) is formatted without error and the output is
  GJF-clean (idempotent format).
- `GoogleJavaFormatterTest`: the `format` method is thread-safe (a formatter
  instance shared across multiple `format()` calls produces consistent output —
  GJF's `Formatter` is stateless per call, so this is a trivial check).

**Verification —**
- `./gradlew :recordbuf-generator:compileJava` succeeds (the GJF jar is on
  the classpath, and `--add-exports` is wired for the *compile* JVM — GJF's
  `Formatter` class itself compiles without `--add-exports`; the export is only
  needed at *run* time when GJF's reflective calls into `com.sun.tools.javac.*`
  fire).
- `./gradlew :recordbuf-generator:test` succeeds — the new `GoogleJavaFormatterTest`
  passes, and the `--add-exports` (or its subset) allows GJF's reflective calls
  to fire in the test JVM.
- If the `--add-exports` subset needs trimming (D1), the subset is discovered
  here and fixed in U4.

**Execution note —** The `--add-exports` subset can be discovered iteratively:
run the test, read the `IllegalAccessError`/`NoSuchMethodError` message, add
the missing export, repeat. Start with the full canonical set from KTD-8; trim
if a subset suffices. The exact subset is D1 (deferred to implementation).

---

### U3. Wire the Formatter into `CodeGenerator`

**Goal —** Route every rendered output surface through the GJF-backed formatter:
`generateRecord`, `generateEnum`, and `emitPackageInfo`. The formatter is a
per-request field on `CodeGenerator` (matching the existing `table`,
`oneofRegistry`, `emittedPackageInfos`, `bridgeContext` pattern).

**Requirements —** R2 (the "wire into every rendered surface" requirement).

**Dependencies —** U1 (SPI), U2 (GJF impl + wiring).

**Files —**
- `generator/src/main/java/bijnagte/recordbuf/plugin/CodeGenerator.java`
  (modify)
- `generator/src/test/java/bijnagte/recordbuf/plugin/FormatterWiringTest.java`
  (new)

**Approach —**
- Add `private Formatter formatter = new GoogleJavaFormatter();` on
  `CodeGenerator` (initialized in `generate(request)` or as a
  `private final Formatter formatter = new GoogleJavaFormatter();` field;
  per-request vs. final is a trivial D2 decision — a per-request field mirrors
  the other per-request state, but a `final` field is equally correct since
  `GoogleJavaFormatter` is stateless).
- `generateRecord` and `generateEnum` thread `this.formatter` into
  `SourceRenderer.render(javaFile, this.formatter)`.
- `emitPackageInfo` — make it an *instance* method (or take a `Formatter`
  argument). The raw string it builds is `this.formatter.format(content)` before
  adding to the response. See KTD-9.
- The per-request `formatter` field is constructed once per
  `CodeGenerator.generate(request)` call, like the existing per-request state
  (`table`, `oneofRegistry`, `emittedPackageInfos`, `bridgeContext`).
- No change to `CodeGeneratorResponse.File` or the response shape — the
  formatter is applied before the file content is added to the response.

**Patterns to follow —**
- `CodeGenerator`'s existing per-request state pattern (see
  `CodeGenerator.java` fields `table`, `oneofRegistry`, `emittedPackageInfos`,
  `bridgeContext`, `bridgeRegistry`).
- The `bridgeRegistry.emitAll` / `BridgeContext.parse` patterns for a
  per-request object built in `generate`.

**Test scenarios —**
- `FormatterWiringTest`: a small `CodeGeneratorRequest` with a minimal
  `Sample.proto` (a single message, no nested types, no WKT) produces a
  `Sample.java` whose content is GJF-clean (`format(format(x)) == format(x)`).
- `FormatterWiringTest`: the `package-info.java` for that request's package is
  GJF-clean.
- `FormatterWiringTest`: a `CodeGeneratorRequest` with a nested type and a WKT
  field (a realistic small proto) produces output that is GJF-clean (a superset
  of the first scenario).
- Existing plugin tests (`writer/*Test`, `model/*Test`, `OneofEmissionTest`,
  etc.) — no change expected (their `contains`-based assertions are
  format-independent), but a run-through confirms no regression.

**Verification —**
- `./gradlew :recordbuf-generator:test` — the new `FormatterWiringTest`
  passes, and the existing plugin test suite (`writer/*Test`, `model/*Test`,
  etc.) all pass.
- A manual check: run `protoc` against a real `.proto` with `--recordbuf_out`
  pointed at a temp dir, compile the output, and confirm `./gradlew build` is
  green (the generated file is on the classpath and compiles).

**Execution note —** The `emitPackageInfo` change is the one that touches
non-trivial code: it's a `private static` method today that builds a raw
string. Making it an instance method (or passing `Formatter` as an extra
parameter) is a small change, but it's the one that requires care to get right
(KTD-9). Prefer the *instance method* form (minimal change, matches the
per-request state pattern).

---

### U4. End-to-end Launch Verification (JVM wiring confirmation)

**Goal —** Confirm that the `--add-exports jdk.compiler/...` wiring works when
the plugin is *launched* (via `installDist` + `protoc`), not just when its
tests run. This is a runtime smoke test — the JVM wiring is inherently a
launch-time concern that a unit test can't fully prove.

**Requirements —** R6, R7 (the JVM wiring is verified end-to-end).

**Dependencies —** U2 (GJF impl + JVM wiring), U3 (wiring into `CodeGenerator`).

**Files —**
- `generator/build.gradle.kts` (modify if D1 trimming is needed — see note below)
- (no code changes expected; this is a verification unit)

**Approach —**
- Run `./gradlew :recordbuf-generator:installDist` to produce
  `generator/build/scripts/protoc-gen-recordbuf`.
- Run `protoc --plugin=protoc-gen-recordbuf=$PWD/generator/build/scripts/protoc-gen-recordbuf
  --recordbuf_out=/tmp/gjf-smoke src/main/proto/hello.proto` (or a similar
  small proto that exists in the project's `src/main/`).
- Confirm that the generated output compiles (e.g., via
  `javac -d /tmp/gjf-smoke-out /tmp/gjf-smoke/**/*.java` — or by pointing a
  small test at the generated output).
- Confirm that the generated output is GJF-clean (re-run
  `spotlessCheck` on the generated output — it should be a no-op).
- If the `--add-exports` subset from U2 needs trimming (D1), adjust
  `generator/build.gradle.kts` and re-run.

**Patterns to follow —**
- The plugin's manual launch doc (the `AGENTS.md` "How to run / verify"
  section — `./gradlew :recordbuf-generator:installDist` + `protoc` invocation).
- The project's `spotlessCheck` gate for the "GJF-clean" confirmation.

**Test scenarios —**
- No new test class: this is a verification step, not a code change.
- The `FormatterWiringTest` from U3 is the *unit-level* proof that the formatter
  is wired into `CodeGenerator`; this unit is the *launch-level* proof that the
  `--add-exports` wiring works end-to-end.

**Verification —**
- `./gradlew :recordbuf-generator:installDist` succeeds.
- The `protoc` invocation above produces output that compiles and is GJF-clean
  (the "GJF-clean" check is a no-op `spotlessCheck` on the generated output, or
  a `format(format(x)) == format(x)` check via a small script).
- `./gradlew build` succeeds (the full build: compile + test + assemble).

**Execution note —** This is a runtime smoke test, prefer a real `protoc`
invocation over a unit test. The JVM wiring is the one thing that can't be
proven by a unit test — the `--add-exports` set on the *launched* JVM is what
GJF's reflective calls fire into, and that's only observable at launch time.

---

## Verification Contract

- Build gate: `./gradlew build` (compile + `test` + `assemble`) is green
  after U1–U4 land.
- Specific test commands:
  - `./gradlew :recordbuf-codegen:test` — U1's `FormatterTest` /
    `SourceRendererFormatTest` pass.
  - `./gradlew :recordbuf-generator:test` — U2's `GoogleJavaFormatterTest`
    and U3's `FormatterWiringTest` pass.
  - `./gradlew :recordbuf-generator:installDist` + a `protoc` invocation
    against a small `.proto` — U4's launch-level smoke test.
- `./gradlew spotlessCheck` — the first-party `src/main/java` is GJF-clean
  (unchanged gate; no first-party code changes expected).
- `./gradlew checkstyleMain` — the first-party `src/main/java` passes
  Checkstyle (unchanged gate).
- No manual reformatting that the project doesn't otherwise enforce.

---

## Definition of Done

- `:recordbuf-codegen` has a `Formatter` SPI and an `IdentityFormatter`
  default; `SourceRenderer.render(JavaFile)` has a `render(JavaFile,
  Formatter)` overload. No GJF dependency is on `:recordbuf-codegen`'s main
  classpath. (`./gradlew :recordbuf-codegen:test` green.)
- `:recordbuf-generator` has a `GoogleJavaFormatter` that implements the
  SPI and wraps GJF's `FormatterException` as
  `IllegalStateException`. `CodeGenerator.generate(request)` routes every
  rendered output (record, enum, package-info) through the GJF-backed formatter.
  (`./gradlew :recordbuf-generator:test` green, including the new
  `FormatterWiringTest` and `GoogleJavaFormatterTest`.)
- The `--add-exports jdk.compiler/...` set is wired on the generator's Test task
  and the launched plugin's JVM — start from the canonical set in KTD-8, trim
  after the U4 runtime smoke test.
- `./gradlew :recordbuf-generator:installDist` produces a launcher that
  formats its output; the generated code compiles and is GJF-clean (a
  re-run of `spotlessCheck` on the generated output is a no-op).
- `./gradlew build` is green (the full build: compile + test + assemble).
- `docs/README.md` and `AGENTS.md` do not need updating (no new plugin-CLI
  flag, no new build task, no new module; the change is to the plugin's
  internal formatter seam). If the `AGENTS.md` "How to run / verify" section
  needs a note about `--add-exports` for the `protoc` invocation, add it —
  but only if that's where the operator runs the plugin (it's the project's
  only launch-path doc).

### Cleanup

- The `--add-exports` set is a one-time addition to `generator/build.gradle.kts`
  (not a per-call runtime cost). No cleanup is expected after the change is
  merged — the wiring is a permanent part of the build's JVM arg set.
- No experimental code (the SPI, the GJF impl, the JVM wiring) is a dead-end
  approach; all three are load-bearing for the formatter's function.
- The `IdentityFormatter` class (from U1, KTD-4) is a trivial helper; it's
  the identity default that `render(JavaFile)` delegates to. It's not a
  dead-end — it's the GJF-free baseline that the codegen module's tests use.

---

## Appendix

### Primary sources consulted (from the brainstorm)

- **GJF 2.0.0** — on Maven Central (the project's `google-java-format = "2.0.0"`
  pin is the Spotless version ref). Its in-process API is
  `com.google.googlejavaformatter.Formatter.formatSource(String)` (a rename of
  the pre-2.0 `format` method).
- **GJF 2.0.0 runtime deps** — Guava + CommonMark 0.29.0 (pinned in the
  project's catalog via the Spotless dependency chain; GJF's own `POM`
  declares them).
- **Minimum JDK** — 21; the project's Temurin 25 toolchain satisfies this.
- **`--add-exports`** — the canonical set is documented in GJF's own
  README/docs; the exact subset the project needs is a runtime observation
  (D1, deferred to implementation).

### KTD index

- KTD-1 — Formatter SPI split by module.
- KTD-2 — Formatter as per-request state on `CodeGenerator`.
- KTD-3 — Formatter interface shape (one method, unchecked).
- KTD-4 — `render(JavaFile)` preserves the identity default.
- KTD-5 — SPI package location: `bijnagte.recordbuf.codegen.source`.
- KTD-6 — GJF applied to all three rendered surfaces (record, enum, package-info).
- KTD-7 — GJF exception isolation in `GoogleJavaFormatter`.
- KTD-8 — `--add-exports` JVM wiring scope.
- KTD-9 — `package-info.java` formatter routing via an instance method.

### Deferred (D1–D3)

- D1 — exact `--add-exports` subset (runtime observation).
- D2 — `formatter` field name on `CodeGenerator` (local naming).
- D3 — `FormatterException`→`IllegalStateException` message prefix (trivial string).
