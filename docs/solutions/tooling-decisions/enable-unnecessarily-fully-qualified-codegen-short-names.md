---
title: "Satisfy Error Prone UnnecessarilyFullyQualified by making the code generator emit short, imported names"
date: "2026-11-07"
category: "docs/solutions/tooling-decisions"
module: "bijnagte.recordbuf.generator + bijnagte.recordbuf.fixtures"
problem_type: "tooling_decision"
component: "tooling"
severity: "medium"
applies_when:
  - "Enabling a style lint (e.g. Error Prone UnnecessarilyFullyQualified, or a linter that flags fully-qualified names) on generated code and/or hand-written tests"
  - "A generated-code emitter reaches for a fully-qualified type name (java.util.Map, bijnagte.recordbuf.runtime.Bytes) instead of an imported short name"
  - "Two types share a SimpleName in the same file, so one reference must stay fully qualified on purpose"
tags:
  - "error-prone"
  - "errorprone"
  - "unnecessarilyfullyqualified"
  - "imports"
  - "codegen"
  - "generator"
  - "recordbuf"
  - "gradle"
root_cause: "incomplete_setup"
resolution_type: "code_fix"
---

# Satisfy Error Prone `UnnecessarilyFullyQualified` by making the generator emit short, imported names

## Context

`recordbuf`'s `:recordbuf-generator` emits Java **generated code** (the `.rec` records
+ codecs) *and* emits a sibling protobuf-java **reference codec** for interop
(see `docs/solutions/design-patterns/proto-codegen-wire-compat-interop-harness.md`).
The project already enforces Error Prone on the `:recordbuf-fixtures` module —
NullAway, wired the same way as the generator/runtime
(`docs/solutions/tooling-decisions/enable-nullaway-null-checks.md`). This learning
turns on a **style** check in the same DSL:

```kotlin
tasks.withType<JavaCompile>().configureEach {
  options.errorprone {
    error("UnnecessarilyFullyQualified")            // flag FQNs that a short, imported name would do
    // the module also enables other style checks:
    error("MissingFinal")
    ...
  }
}
```

Enabling it on `compileJava` and `compileTestJava` of `:recordbuf-fixtures`
surfaced **dozens** of findings in *two* places:

1. **Generated code** the generator emitted (`fixtures/src/main/java/`,
   `.../test/java/`, `.../wire/java/`) used fully-qualified names for
   `java.util.Map`, `java.util.HashMap`, `java.util.Arrays`, `java.nio.ByteBuffer`,
   and `bijnagte.recordbuf.runtime.Bytes`.
2. **Hand-written test sources** used FQN for the nested generated codec enums /
   records (`bijnagte.recordbuf.fixture.order.Order$Line`, `...$rec.OrderSample`,
   `...$rec.Sample.SampleCodec`, …).

The durable move was **not** to suppress the check in tests, or to leave the FQNs,
and **not** to hand-edit regenerated output — it was to fix the **emitter** so
generated code is clean, and to refactor the *test* suite's FQN usage to imported
short names, while carefully preserving the *forced* fully-qualified references
that must stay qualified.

## What Didn't Work

- **Suppress per site.** 30+ FQNs in ~12 fixtures tests — a mess and a lost quality
  bar. Rejected.
- **Leave the generator as-is and only fix tests.** Generated code would keep
  shipping FQNs forever; every future fixture would be noise. Rejected — fix the
  source (the emitter), not the artifacts.
- **Blind global replace of `fully.qualified.Name` → `Name` for every occurrence.**
  A first regex pass *looked* done but the build still flagged FQNs. Two causes:
  (a) new FQNs surfaced after the first pass (a `Bytes.of(...)` in a second
  generator arm; an enum's own `Map`/`HashMap` `BY_NUMBER` index the first pass
  missed); (b) the *forced* record-vs-reference **SimpleName collisions** were
  wrongly collapsed — see below. Rejected as a one-shot; the real win was
  **iterating until the check was silent**, plus protecting the collisions.

## The durable mechanism

### 1. Fix the emitters to register-and-emit short names

Each generator that produced an FQN registers the import (via its
`TypeImports`) and emits the short name instead:

| Generator | Before (FQN) | After (imported short) |
|---|---|---|
| `EnumGenerator` | `java.util.Map` / `java.util.HashMap` (the `BY_NUMBER` index) | `Map` / `HashMap` (+ `import java.util.HashMap; import java.util.Map;`) |
| `CodecGenerator` | `bijnagte.recordbuf.runtime.Bytes.empty()` / `.of(...)` | `Bytes` (+ `import ...Bytes;`) |
| `AvroConverterGenerator` | `Bytes` / `Arrays` / `java.nio.ByteBuffer` | `Bytes` / `Arrays` / `ByteBuffer` (+ imports) |
| `InteropGenerator` | `Bytes.of(...)` inline | `Bytes` via the same `ci.use`/registry |

Short name = last segment (FQN.substring(FQN.lastIndexOf('.')+1)). The import is
added **only when actually used**, de-duplicated per file.

**Dead constant caught:** `CodecGenerator.BYTES_TYPE` was still the fully-qualified
`bijnagte.recordbuf.runtime.Bytes` FQN — *but it is `TYPE_IMPORTS`-imported by
design*. A fully-qualified import is "a no-op that adds a dead import" (exactly
what `UnnecessarilyFullyQualified` flags the FQN for) — so the constant and its
import registration were removed, and the two bytes defaults now register
`runtime/src/main/java/bijnagte/recordbuf/runtime/Bytes.java` + emit `Bytes`/`Bytes.empty()`/`Bytes.of(...)` instead. This
is the *right* shape: a fully-qualified import is always a code smell.

### 2. Keep the forced-SimpleName collisions fully qualified

For every record, the generated `.rec` codec **and** a sibling protobuf-java
**reference codec live in the same classpath**. They share SimpleNames
(`Sample`, `SampleCode`, `Color`/`Color$ColorCodec`, `Order`/`OrderCode`, …). The
record-vs-reference distinction in a `switch`/`instanceof` **cannot be expressed
with short names** — one side must stay fully qualified. So the forced
record-vs-reference `SimpleName` collisions stay FQN **by design** and are **not**
auto-shortened. Everything else gets imported.

This is the key subtlety the blind global replace broke: it collapsed a
force-qualified reference to its short name, producing a *compile error* (two
types with the same short name now clash), not just a lint pass.

### 3. Refactor the test suite safely with a scripted, iterated rewrite

For ~20 FQNs across ~14 `fixtures/src/test/...` files, a one-off Python rewrite:

1. **Process occurrences longest-FQN first** (so `java.util.Map$Entry` is handled
   before `java.util.Map`, preventing prefix corruption of a shorter FQN).
2. For each file, **import-add** the short name for every FQN it uses that is
   **not** a forced-collision name (collision names stay FQN), de-duplicating
   imports already present.
3. **Replace only the FQN substring** in the source body — skip any line that is
   an *import declaration* (don't turn `import a.b.C;` into `import C;`) and skip
   the import block region.
4. Then `spotlessApply` to format.

**Iterate until the check is silent.** New FQNs surface only after each pass
(a missed generator arm, an enum's `Map`/`HashMap`, a `Bytes.of` in a 2nd arm, a
`java.util.Arrays`/`ByteBuffer` in the AVRO converter). Re-run the rewrite with the
new FQNs until `compileTestJava` is clean. One generator unit test
(`AvroConverterGeneratorTest`) also asserted the old FQN output — update the
**assertion** to the new short-name output (the *expected*, not the generator).

## Why This Works

- The root cause was **incomplete_setup**: the generator's `TypeImports`
  machinery was used for *most* names but a few arms bypassed it and emitted FQNs
  inline. Routing every emit through the import registry (and removing the one
  dead FQN import) makes the generated output uniformly clean.
- `UnnecessarilyFullyQualified` is a *style* bar; the fix is to **teach the
  emitter to produce conforming output**, which is permanent for every future
  fixture (vs. suppression, which is per-site churn).
- Protecting the forced collisions (a compile-time fact of the interop harness)
  and iterating the rewrite until silent are what make the test-suite refactor
  safe *and* complete in one pass.

## Prevention

- **Emit via the import registry**, never an inline FQN. When a generator needs a
  JDK / runtime type, look it up in `TypeImports` / `ci.use` and emit `ci.use(...)`,
  so the import is registered and the short name emitted — one code path.
- **Treat a fully-qualified *import* as always wrong.** Remove FQN import
  constants (like the old `BYTES_TYPE`) rather than carrying them.
- **When satisfying a style lint, iterate the rewrite to silence, not one-shot.**
  Longer-FQN-first ordering + skip-import-lines + then re-run until `compile*` is
  clean avoids both prefix-corruption and missed occurrences.
- **Preserve forced name collisions.** Where two types share a `SimpleName` in one
  file (interop harness reference+record), keep that reference FQN and don't let a
  global shortener collapse it — that is a compile error, not a lint violation.
- **Update the generator's own assertion tests** when their *expected* output
  changes — the fix is the generator, and its unit tests pin the output shape.

## Related

- `docs/solutions/tooling-decisions/enable-nullaway-null-checks.md` — sibling:
  wiring a dataflow Error Prone check (NullAway) via the same `options.errorprone`
  DSL; this learning is the *style-check* companion and the emitter-side fix.
- `docs/solutions/design-patterns/proto-codegen-wire-compat-interop-harness.md` —
  the generated reference codec that creates the record-vs-reference **SimpleName
  collision** these FQNs must survive.
- `docs/solutions/design-patterns/native-well-known-type-generation.md` — the WKT
  feature whose generated bytes fields (`Bytes.empty()`/`Bytes.of(...)` for `null`
  / `[]`) were converted to short names here.
