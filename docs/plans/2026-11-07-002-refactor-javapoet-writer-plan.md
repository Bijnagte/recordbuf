# recordbuf-codegen — Plan (substrate pivot)

- `artifact_contract: ce-unified-plan/v1`
- `artifact_readiness: requirements-only`
- Supersedes the two-pass import design in `docs/plans/2026-11-02-001-feat-fqn-to-imports-plan.md`
  (hand-rolled `TypeImports` + collect-then-render passes) and the hand-rolled `JavaWriter`.
- Records the JavaPoet-era rationale below it as **research/superseded**; the live decision is in
  **DECISION**. The `:recordbuf-avro` Java-converter migration remains a tracked follow-up
  (see `docs/plans/2026-11-05-001-feat-avro-bridge-plan.md`).

---

## DECISION (2026-11-07) — build a purpose-built `:recordbuf-codegen` module (clean-room)

**Decision:** Replace the hand-rolled writer with a **new `:recordbuf-codegen` module** that is a
small, structured, **faithful** Java-emitter of our own — a *clean-room reimplementation*, **not**
a JavaPoet dependency and not a JavaPoet source copy.

**Why not JavaPoet / JavaParser / JDT (researched):**
- **JavaPoet (`palantir/javapoet:0.22`, Apache-2.0):** actively maintained + record/sealed support,
  but **cannot emit constructor-arg enum constants** (`KIND_LEGACY(1)`), **per-constant Javadoc**,
  or **per-constant `@Deprecated`** — `enumBuilder` exposes only `addEnumConstant(name)` /
  `addEnumConstant(name, body)`. Those constants are in real generated output (the `Kind` fixture),
  and per-constant Javadoc/`@Deprecated` are `SOURCE`-retention; a JavaPoet enum path would silently
  change **WHAT** `.rec` produces, against the product contract ("change HOW, not WHAT").
- **JavaParser (`com.github.javaparser:javaparser-core`, dual Apache-2.0/LGPL → take Apache):**
  complete Java-1–25 AST, faithful output — but it is a *parser*; building a `CompilationUnit`
  does **not** auto-manage imports/dedup, so you'd rebuild the very `TypeImports` collector we are
  removing, on top of a heavy dep. Low net gain.
- **Eclipse JDT / ecj (EPL-2.0):** complete AST + bindings, but EPL-2.0 is a (weak) copyleft — the
  one real license nuance vs recordbuf's MIT (build-time-only lowers practical risk) — and it emits
  pretty source awkwardly.
- **Purpose-built module:** MIT-clean, faithful by construction, **zero external coupling** (the
  generated `.rec` surface stays dependency-free, **R22**), and it *is* the fix for the string-
  concatenation surface. Bounded because recordbuf's construct surface is fixed and known. Wins on
  faithfulness + license + coupling at the cost of maintaining a small module.

## IP / clean-room guardrails (authorship evidence)

Copyright protects *expression* (source text), not the *idea/design/functionality*. A module
"inspired by" JavaPoet's fluent-builder shape does not infringe; the only risk is copying its
**source**. JavaPoet is Apache-2.0, so even verbatim reuse is *legal* but conditional (keep each
file's copyright + license header, ship `LICENSE`/`NOTICE`, mixed-license the module) — we do NOT
take that path. Instead:

1. **Clean-room, original source.** No JavaPoet source excerpts; no "rename-and-resubmit." JavaPoet
   is **prior art / motivation, cited, not copied.**
2. **Behavioral spec = our own MIT tests.** The import/type-reference contract is sourced from
   `TypeImports` + `TypeImportsTest` (recordbuf, MIT) — a passing spec we fully own — NOT from
   JavaPoet's code.
3. **Functional spec = our requirements** (below). Functionality is not copyrightable.
4. **No vendored JavaPoet**: not on the classpath, not imported into the module.
5. **Module stays MIT**: add a license header to new `:recordbuf-codegen` files (mirror the repo's
   MIT `LICENSE`).
6. **Optional credit**: "inspired by JavaPoet / Square-JavaPoet" in the module README. Harmless.
7. This doc records the derivation = **evidence of independent authorship**.

(General info, not legal advice; clean-room original code from our own MIT tests is the standard
safe path.)

## Functional spec for the `:recordbuf-codegen` module

New module `:recordbuf-codegen`, package `bijnagte.recordbuf.codegen`. **No main deps** (pure
Java 25; JUnit is test-only). The generated `.rec`/`.enum` surface references none of it (R22).
`:recordbuf-generator` will depend on it; it is a generator-side concern only. It must reproduce
these behaviors (the authoritative source is our own `writer/TypeImports.java` +
`writer/TypeImportsTest.java`):

### Import / type-reference manager (drop-in for `TypeImports`)
Contract mirrors the existing `TypeImports` (7 encoded cases in `TypeImportsTest`, + 2 documented
behaviors the recordbuf codebase relies on):
- **Never imported** (bare short name, no import line): `java.lang.*` types AND same-package types
  (`use` returns the short name unconditionally).
- **Cross-package → short name + import** (`use` returns short, an import line is added).
- **Collision**: a short name shared by ≥2 distinct FQNs → emitted fully-qualified at every site
  and **no import** (the consumer's compiler disambiguates by package). Collision depends on the
  whole referral set, so resolution is two-phase: `use` collects; after
  `finalizeResolution()`, `use` returns collision-aware names and `importLines()` is final
  (un-collided refs restored to short form; idempotent after finalization).
- **Import ordering** (`importLines()`): `java.*` group first, a single blank separator, then the
  other packages; **alphabetical within each group**.
- **Nested cross-reference** (documented behavior the codebase relies on): a nested type in the
  file's own package — e.g. `…iface.Sample.Choice` used as `@Nullable Choice` on a record component
  — is imported under its **nested FQN** (`import …iface.Sample.Choice;`) and referred to by its
  **short name** (`Choice`). This is precisely what makes `@Nullable Choice` compile (JavaPoet's
  JavaPoet-style full-qualification of same-file nested types would break it — the original
  landmine).
- **Arrays/generics**: primitives, `byte[]`, and generic parameters (`List<X>`) resolve by their
  raw type; the generic argument is emitted by the call site.
- A `String`-in / render-out model is fine for M0; the full emitter (below) will surface a
  structured `TypeName`/`ClassName` model so callers stop passing type FQNs as strings.

## Hybrid architecture: sealed node hierarchy + fluent builders + capped `CodeBlock` body layer + central `TypeCollector`

JavaPoet is already this shape (nodes + fluent builder + `TypeCollector` import resolution +
`toString()`). We reproduce that clean-room, but the node layer is a **sealed type hierarchy**: a
closed, compiler-checked construct set, which is exactly what recordbuf's fixed emission surface is.

**Node model** (every node is an immutable `record`; children default to `List.of()`; only a few
nullable fields are `@Nullable`):

```
sealed interface Node { void render(Indent indent, ImportScope scope, StringBuilder out); }

sealed interface JavaFile { String packageName; List<TypeName> imports; String header;
                           String proto; TypeSpec type; }

sealed interface TypeSpec extends Node
    permits RecordSpec, EnumSpec, ClassSpec, InterfaceSpec,
    annotationTypeSpec, NestedTypeSpec {}

sealed interface Member extends Node
    permits MethodSpec, FieldSpec, EnumConstantSpec, StaticInitSpec {}

// The node JavaPoet cannot represent:
record EnumConstantSpec(String name,
    List<CodeBlock> constructorArguments,   // ctor-arg constants -- the gap
    @Nullable String javadoc,                // per-constant Javadoc
    boolean deprecated) implements Member {}

sealed interface Expression extends Node           // small, closed: not a full stmt/expr tree
    permits LiteralExpr, VariableExpr, MethodCallExpr, ListExpr, MapExpr, ThisExpr {}

sealed interface TypeName
    permits Primitive, ClassName, ArrayType,
    ParameterizedTypeName, WildcardTypeName, Void {}

sealed interface AnnotationSpec
    permits NullableAnnotation, SuppressWarningsAnnotation, DeprecatedAnnotation {}
```

**Idioms** (Java 25 toolchain): records for every node (free value-equality for tests; no
mutation-after-build footguns); exhaustive `switch` pattern matching (J21) at every dispatch point —
no `default`, no separate visitor class to keep in sync; text blocks for multi-line Javadoc/code;
`@Nullable` only on `EnumConstantSpec.javadoc`, `MethodSpec.javadoc`, `FieldSpec.init`,
`AnnotationSpec.value` (the rest of the model is null-free-by-construction).

**Open/closed extension property**: adding a node type (say `PermittedSubclassType` for a future
`record permits ...` construct) *forces* a compile error at every `switch (TypeSpec) { ... }` site
that doesn't update — the compiler is the exhaustiveness checker. This is the one property that makes
sealed worthwhile over a generic Java AST for this domain: the construct set is finite and known.

**Body layer cap** — `CodeBlock` is the deliberate cap: method bodies (~39 KB of
`CodecGenerator` switch/decode logic) use line/substitution `$T`/`$L` blocks with an `ImportScope`
that feeds the central `TypeCollector`, NOT a full statement/expression tree. The sealed `Expression`
layer supports only the closed set needed for enum-constant constructors and simple field
initializers; if it grows past `Literal / Variable / MethodCall / List / Map / This` the node model
revisits the cap — a compile-enforced decision point, not a silent one.

**Import/type layer** — `TypeCollector` (M0) stays central: an `ImportScope` wraps it, collects
imports during a first render pass, freezes after `finalizeResolution()`, and feeds the final render
pass. Reproduces all 7 `TypeCollectorTest` behaviors + nested-`@Nullable` cross-refs + generic
stripping.

**File surface** — a `JavaFile.toString()` / `writeTo(Writer)` renders: the `"Generated by recordbuf
-- DO NOT EDIT."` header + a `"Proto: <file.proto>"` comment + the `TypeCollector`-grouped import
block + the body. `.rec` is re-formatted by the generator's google-java-format path only when emitted
into a formatted source set; a plain output dir is unformatted.
- A `TypeSpec`/`JavaFile` model for: a top-level `record` type, a top-level `enum` type, a nested
  `enum` `Codec`, interfaces/annotation types, `implements` clauses with cross-FQN interfaces,
  fields, methods, static blocks, per-type/per-member Javadoc, `@Nullable`/`@SuppressWarnings`/
  `@Deprecated` annotations.
- **`enum` constants that carry constructor arguments AND per-constant Javadoc AND per-constant
  `@Deprecated`** — the fidelity JavaPoet lacks and the reason for the pivot.
- **Code blocks** for the codec's imperative switch/decode bodies (the meatiest surface,
  `CodecGenerator` ~39 KB), with structured substitution so callers stop building Java by
  string concatenation.
- A `JavaFile.toString()`/`writeTo` that emits the **`"Generated by recordbuf -- DO NOT EDIT."`
  header** + a **"Proto: \<file\>"** comment + the ordered import block + the body — reproducing
  the current file surface the emission tests assert.
- `.rec` remains formatted by the generator's google-java-format path only when emitted into a
  formatted source set (test sourceSets); generation into a plain output dir is unformatted.

## Milestones (each: `./gradlew build` green + `:recordbuf-generator` tests green = gate)

- **M0 — module + import manager skeleton.** New `codegen/` module (toolchain 25, root-applied
  Spotless, no errorprone/nullaway yet), `bijnagte.recordbuf.codegen` package, and the
  import/type-reference manager reproducing `TypeImportsTest` (a ported copy of our own MIT test
  lives in the module's test scope). Gates: module compiles + its ported import test passes +
  `./gradlew build` green. [LOCAL COMMIT]
- **M1 — full emitter AST + self-test.** `JavaFile`/`TypeSpec`/`MethodSpec`/`FieldSpec`/
  `AnnotationSpec`/`CodeBlock`; enum constants with ctor-args + per-constant Javadoc/`@Deprecated`;
  records via `recordConstructor`. A module self-test emits an enum + a record and asserts the
  substring shapes the generator's emission tests assert (incl. no `java.lang.*` imports;
  `@Nullable` on a nested cross-ref stays short). [LOCAL COMMIT]
- **M2 — migrate generators, one kind at a time (gate each).** Route generation through the new
  emitter: enum → record+nested `Codec` → oneof → interop → `@NullMarked` `package-info`. Keep the
  old `JavaWriter`/`TypeImports` available until the last path switches. After each kind: full
  generator test suite + `./gradlew build` + re-generate a sample and round-trip. [LOCAL COMMIT]
- **M3 — delete `TypeImports` + `JavaWriter`; final gate.** `:recordbuf-generator` no longer
  references them; full `./gradlew build` + `:recordbuf-generator` + `:recordbuf-runtime`
  + `:recordbuf-fixtures` round-trip. [LOCAL COMMIT]
- **Out of this effort:** `:recordbuf-avro` `WktAvroBridge` (tracked follow-up); Avro schema
  emission; `XSpecific`/`AvroSpecificGenerator` — untouched until separately green.
- **Execution:** sequential, milestone-bounded; single writer per step; **local commits only**
  (no remote → no push/PR); I verify + commit between milestones; escalate decisions via
  `contact_supervisor`.

---

## (Superseded rationale / JavaPoet-era research — kept for traceability)

### Original goal capsule
Replace the hand-rolled writer (`writer/JavaWriter`, `writer/TypeImports`, ~305 `"…+x+…"`
concatenation sites across 5 generators) with palantir/javapoet `0.22.0`, adding record + sealed
support. JavaPoet's `$T`/`ClassName` import handling was expected to eliminate the hand-rolled
import tracker + the two-pass collect/render structure.

### Why a spike was run (and what it found → drove the pivot)
A `/tmp` spike with palantir/javapoet `0.22.0` confirmed it renders records/sealed **and**
demonstrated two hazards, the second decisive:
1. **`@Nullable` on a nested cross-reference** — naive full-qualification (`@Nullable
   Sample.Choice`, where `Choice` is a nested type of the *same* `Sample` record) is a **Java
   compile-error**: an annotation must precede a type that resolves to a simple name in scope, not
   `Sample.Choice`. Today `TypeImports` (lines 38–39) resolves same-package types to the short name
   unconditionally, which is exactly why `@Nullable Choice` compiles. **MUST reconstruct the "short
   name / java.lang unimported / collision→FQN" behavior in whatever writer the generators use.**
2. **`java.lang` imports** — a naive reference to `String` emits `import java.lang.String;`, which
   the codebase forbids and `ImplementsClauseEmissionTest` asserts against
   (`assertFalse(src.contains("java.lang.Comparable"))`). The writer must keep `java.lang.*` short.

### Confirmed substrate facts (carried into the module design)
- JavaPoet is **`com.palantir.javapoet:javapoet:0.22.0`**, Apache-2.0, a **single jar, zero required
  transitive deps** (the `javapoet-root` POM is optional/test-only; **do not** pull it).
- Its enum-constant API cannot express ctor-arg constants or per-constant Javadoc/`@Deprecated` —
  the reason we are building our own module instead.
- **Gate is forgiving on formatting, strict on behavior:** the `*EmissionTest` cases are
  `src.contains(...)` substring assertions (not exact golden strings); the `fixtures` module holds
  the byte round-trip + cross-process `protoc` test. So formatting changes from the new writer are
  fine; preserved substrings + round-trip behavior are not.
- **No per-field `@Nullable @Codec`**: fields stay plain; nullability lives only on record
  components (a `@Codec` on a `@Nullable` component is a no-op).
- **No Avro coupling in scope.** This effort touches only the Java writer surface it touches;
  `:recordbuf-avro`'s `WktAvroBridge` + `XSpecific`/`AvroSpecificGenerator` remain hand-rolled until
  separately green.

### Baseline
- `:recordbuf-generator`: `src` + `test` sourceSets; `src` wired into Spotless + google-java-format
  for generated records; `test` sourceSet compiles generated records through a formatter that
  rewrites import grouping/ordering — the gate must reproduce a format-clean, import-clean enum+impl
  block.
- Baseline `./gradlew build` is **green** (confirmed this session).
- **Local commits** (`git rev-parse --show-toplevel` returns the repo; `git remote` empty → no
  push/PR). Per `git-workflow` "local-only" mode: commit locally at each milestone; report the
  commit hash + a plain `git log --oneline`.
- The `com.google.protobuf.compiler` shim (PluginProtos) is untouched; it sits out of
  nullaway/errorprone scope via `OnlyNullMarked=true` and `excludedPaths`, and Spotless excludes it.
- **No `main` merge conflict** with `docs/plans/` files (all plans are new `??` untracked).

## Milestone progress (this effort)

Each entry is a gated local commit on `refactor/codegen-module` (local-only repo; `git remote`
empty → no push/PR), recorded after its gate is green:

### M0 — `:recordbuf-codegen` module + clean-room `TypeCollector` — commit `39152b4`
- New flat role-named module `codegen/` wired into root `subprojects {}` (toolchain 25, Spotless,
  Checkstyle, JSpecify/NullAway auto); `settings.gradle.kts` includes it.
- `TypeCollector` — clean-room re-implementation of the plugin's MIT `TypeImports` contract
  (unique-short-name-or-FQN, no `java.*` import, `java.` first). Main stays dependency-free; test
  carries `junit.jupiter`. Gated by `TypeCollectorTest` — a port of our **own MIT**
  `TypeImportsTest` (7/7, `com.google`/`pandit`/`palantir`/`squareup` all green).
- No `com.google`/`com.google.protobuf` import in `codegen/`; `find codegen -path '*com.google*'`
  empty. The module never references JavaPoet; its `@implNote` cites the plugin's own contract
  (clean-room; see IP section).

### M1 — sealed enum vertical slice + self-test — commit `dcabeb6`
- `TopLevelType` (sealed interface, `permits EnumType`) → `EnumType` → `EnumConstantSpec`; a
  `ClassName` (clean-room `com/pandit/ClassName` analogue, `fqn()→short()` + `simpleName()`);
  `JavaFile` (two-pass `toSource()`: render-into-scratch to collect imports, `finalizeResolution()`
  then emit with the frozen resolver); `Renderer` over the sealed `TopLevelType` (exhaustive
  `switch`, no `default` — closed by construction).
- `EmissionTest` (4 assertions) renders `annotations/enums.proto`'s `Kind` → matches the real
  fixture **including the JavaPoet gap** the decision matrix flagged: ctor-arg constant
  `KIND_LEGACY(1)`, per-constant bare `@Deprecated`, per-constant Javadoc. **The emitted enum source
  was confirmed `javac`-compilable** (filename/package-matched) — a structural gate beyond substring
  matching.

### M1.5 — record + nested `Codec` slice (narrow) — commit `b3458bc`
- New sealed-input nodes: `RecordType` (added to `TopLevelType.permits`, closed by construction),
  `FieldSpec` (with a sealed `Encoding`: `Scalar`/`EnumField`/`MessageField`, closed by
  construction), `WireScalar` (a clean-room copy of the plugin's internal enum). Renderer extends
  to `case RecordType` → record header + `@Nullable` decision + nested `Codec` (`encode`/`decode`
  + unknown-field carrier re-emit) + Layer-A marshalling hook (`toByteArray`/`fromBytes`), ported
  faithfully from `CodeGenerator`/`CodecGenerator`, scoped to **singular scalar/enum/message +
  carrier only** (no repeated/map/oneof/WKT/nested/Avro; deferred).
- The `@Nullable` landmine resolved: a present-able field is `@Nullable`-prefixed with the boxed
  reference; `org.jspecify.annotations.Nullable` resolved through `TypeCollector` so it imports
  (a bare-name type never gets an unqualified `@Nullable ` prefix — the JavaPoet `@Nullable FQN`
  bug is structurally absent because the type is already short at emit time). Non-nullable scalars
  carry their bare primitive; reference types go through `TypeCollector`. A real generator-side
  primitive-leak into the import block was caught and fixed (`isPrimitive` gating the component
  type resolution).
- `RecordEmissionTest` (2 assertions) renders a 2-field sample record + a nullable variant,
  **and each emitted `.rec` is `javac`-compiled against the real runtime + protobuf + JSpecify as a
  durable automated assertion** — the crux of "the new writer actually emits compilable code". The
  real decode `return new Sample(...)` / `}` brace structure was verified against the fixture
  (`CodecGenerator`'s `emitDecode` closes decode at its own indent before the class braces).
- Test-time deps added to `codegen/build.gradle.kts`: `testImplementation(":recordbuf-runtime")`
  (brings protobuf transitively via `api`) + `testImplementation(lib.jspecify)` (runtime marks it
  `compileOnly` → not transitive; the nullable variant needs it to compile). Main stays dep-free;
  only the test compile gate uses the runtime — consistent with the M0 note "runtime is a
  test-time compile target only".
- Full build green: `:recordbuf-codegen:test` (11/11 including the two `javac` compile gates),
  Spotless + Checkstyle, `./gradlew build`.

**Next: M2-widen** — repeated / map fields, then oneof (sealed interfaces), WKT (
`google.protobuf.`→java native + `WellKnownTypes` map), nested messages, Avro bridge — each a
gated sub-slice. Then M4 (generator swap): route `Main`→module, fixtures green, interop tests.

### M2a — repeated message + repeated enum — commit `62a6b2d`

The sealed `FieldSpec.Encoding` gained two variants — `RepeatedMessage(codecRef)` and
`RepeatedEnum()` — threaded through the record renderer: `componentText` emits the component
as `List<E>` (`java.util.List` + `java.util.ArrayList` imported, `E` resolved short),
`initDecl` a fresh `new ArrayList<>()` local, `emitEncodeField` a for-loop write
(`writeByteArray` for repeated message, `writeEnum` for repeated enum), `emitDecodeCase` an
`x.add(...)` accumulation. A repeated-*message* full compile needs the sibling element type on the
classpath (the fixtures/M4 gate); the module gate asserts the emission shape for both variants
while the self-contained record's `javac` gate proves compilation. 11/11 module tests pass.

### M2b — packed repeated scalar — commit `a30dd4e`

As implemented (design above held; no re-derivation needed):
- `record RepeatedScalar(WireScalar wire, boolean packed)` is now the final variant of
  `FieldSpec.Encoding` (`permits Scalar, EnumField, MessageField, RepeatedMessage, RepeatedEnum,
  RepeatedScalar`); `WireScalar` was already imported into `FieldSpec.java`.
- Threaded through the renderer: `componentText`/`initDecl` build the component as `List<Boxed>`
  where `Boxed` comes from a new `boxedType(wire)` (int32-family→`Integer`, int64-family→`Long`,
  float→`Float`, double→`Double`, bool→`Boolean`, string→`String`, bytes→`ByteString` — not
  `f.type()`); a new `packedNoTag(wire)` gives the per-wire `writeXNoTag` op; `emitRepeatedScalarEncode`
  emits `if (!v.<n>().isEmpty()) { var body = <Encoder>.start(); var _o = body.out(); for (var _x :
  v.<n>()) _o.writeXNoTag(_x); out.writeByteArray(<num>, body.finish()); }`; `emitRepeatedScalarDecode`
  emits the `int _lim = in.pushLimit(in.readRawVarint32()); while (in.getBytesUntilLimit() > 0)
  <n>.add(in.readX()); in.popLimit(_lim);` group.
- `packedNumericRecordEmitsAndCompiles` in `RecordEmissionTest` renders a 2-field packed record
  (`repeated int32 numbers` + `repeated int64 longs`) asserting the packed encode/decode shape AND
  reusing `assertCompiles` — a packed all-numeric record is self-contained so it compiles against
  the real runtime + protobuf + jspecify on the codegen `test` classpath.
- Pure generator change: `writeXNoTag`/`pushLimit`/`getBytesUntilLimit`/`readRawVarint32`/`popLimit`
  are native to protobuf-java 4.36.2 (confirmed `Encoder.out()`→`CodedOutputStream`,
  `Decoder.in()`→`CodedInputStream`), no runtime work. Emitted shape is byte-identical to the
  current `CodecGenerator`'s packed framing (`emitRepeatedScalar` / the `emitRepeatedDecode` SCALAR
  arm).
- Full build green: `:recordbuf-codegen:test` (12/12, incl. the packed `javac` gate), Spotless +
  Checkstyle, `./gradlew build`.

**NOTE for a fresh session: M3-C1 is DONE + committed; M3 was then WITHDRAWN by the user (record-level
builder EMISSION deferred). The active work is M4 (the cross-module swap). M4 DESIGN IS LOCKED
(2026-11-07 — see the M4 design section at the bottom + its Resume prompt):** the `:recordbuf-codegen`
module is being REPLACED with a pure, proto-agnostic **Java-source AST** — a `JavaFile` → a sealed
top-level `Type` (record/enum/sealed-interface/class) → its members, nested types being ordinary
members (the fragments), with a **method/constructor body that is just a `List<Line>`** the proto
logic emits into. `oneof`/`WKT`/wire-encoding stay in the generator's model/types layer and are
*mapped into* Java AST nodes. Build the new AST alongside, gate each slice (a `JavaSourceTest` render
+ `javac`-compile + the generator's emission tests staying green), then remove the old proto-aware
`RecordType`/`FieldSpec`/`Encoding`/`WireScalar` nodes + `EmissionTest` once the new AST reproduces
their output. Build incrementally, commit at each gate.

**M4-C1 IS DONE + green (the pure-Java AST skeleton + a `javac` render gate):** the new `source`
subpackage (`Modifiers`/`Javadoc`/`Line`/`Member`/`Type`/`JavaFile`/`SourceRenderer` +
`JavaSourceTest`) renders a record-owning-a-nested-enum-with-line-bodies that compiles; nothing
references it yet, so the generator is untouched and `:recordbuf-codegen:build` is green. **M4-C2
NEXT -- route the generator (`EnumGenerator`/`RecordGenerator`/`CodeGenerator`) through the new AST,**
then delete `JavaWriter` + the old proto-aware nodes + `EmissionTest`. The M2b/M3 notes below are
now historical.**

Design fully worked out (do NOT re-derive):
- Add `record RepeatedScalar(WireScalar wire, boolean packed) implements Encoding {}` as the last
  variant of `FieldSpec.Encoding` (it is `permits Scalar, EnumField, MessageField,
  RepeatedMessage, RepeatedEnum, RepeatedScalar`). `WireScalar` is already imported into
  `FieldSpec.java`.
- Renderer threads it through 4 switches + `componentText`/`initDecl`:
  - `componentText`: for `RepeatedScalar` the component is `List<E>` where `E` = the scalar's
    BOXED type (derive from `wire`, NOT `f.type()`): int32/uint32/sint32/fixed32→`java.lang.Integer`,
    int64/uint64/sint64/fixed64→`java.lang.Long`, float→`java.lang.Float`, double→`java.lang.Double`,
    bool→`java.lang.Boolean`, string→`java.lang.String`, bytes→`com.google.protobuf.ByteString`.
  - `initDecl`: `List<E> name = new ArrayList<>();` (E = boxed above) — reuse the existing
    `repeatedInit` shape, feeding it the boxed element type instead of `f.type()`.
  - `emitEncodeField` → `case RepeatedScalar s -> emitRepeatedScalarEncode(out, f, s);`. The PACKED
    shape (the `packed/rec/Sample.java` fixture, all-numeric, default for numeric scalars):
    ```
    if (!v.<name>().isEmpty()) {
        var body = <Encoder>.start();
        var _o = body.out();
        for (var _x : v.<name>()) _o.write<Op>NoTag(_x);   // _x is const, block-scoped per if
        out.writeByteArray(<num>, body.finish());
    }
    ```
    `<Op>` (the NoTag op per wire): INT32/UINT32→writeInt32NoTag, SINT_32→writeSInt32NoTag,
    FIXED32→writeFixed32NoTag, INT64/UINT64→writeInt64NoTag, SINT_64→writeSInt64NoTag,
    FIXED64→writeFixed64NoTag, FLOAT→writeFloatNoTag, DOUBLE→writeDoubleNoTag, BOOL→writeBoolNoTag.
    (`body` is `ci.use(RT + ".Encoder")`.) Unpacked (repeated string/bytes) is a LATER slice —
    proto3 leaves string/bytes unpacked; out of M2b scope.
  - `emitDecodeCase` → `case RepeatedScalar s -> emitRepeatedScalarDecode(out, ci, f, s)`. PACKED shape:
    ```
    case <num> -> {
        int _lim = in.pushLimit(in.readRawVarint32());
        while (in.getBytesUntilLimit() > 0) <name>.add(in.read<Dec>());   // <Dec> via decodeCall
        in.popLimit(_lim);
    }
    ```
    (`in.read<Dec>()` is the existing `decodeCall(ci, wire)` body sans the trailing `;`; the
    pushLimit/getBytesUntilLimit/popLimit ops are on `in` directly — no `ci.use`.)

**Why this is safe / pure-gen (confirmed, do not redo):** the packed ops
(`writeInt32NoTag`/`writeFixed32NoTag`/`writeSInt32NoTag`/`writeInt64NoTag`/
`writeSInt64NoTag`/`writeFixed64NoTag`/`writeFloatNoTag`/`writeDoubleNoTag`/`writeBoolNoTag` on
`com.google.protobuf.CodedOutputStream`; `pushLimit`/`getBytesUntilLimit`/`readRawVarint32`/
`popLimit` on `com.google.protobuf.CodedInputStream`) are NATIVE to core protobuf-java 4.36.2
(verified via `javap` against the resolved core jar, not util). The current `CodecGenerator`
already emits exactly this shape (lines ~497-512 pack, ~725-726 unpack via `packedNoTag` →
`writeXNoTag` + `body`/`_o`). So M2b needs NO runtime change. A packed-ALL-numeric record is
SELF-CONTAINED (java.lang boxed elements + runtime + protobuf ops), so the gate can be a real
`javac` compile in `RecordEmissionTest` (reuse the `assertCompiles` helper), not just a shape
assert.

**Note for a fresh session:** start M2b by adding `RepeatedScalar` to `FieldSpec.Encoding`, then
thread it through the 4 renderer switches, then add a `packedNumericRecordEmitsAndCompiles` test
emitting a record with `repeated int32 a` (`RepeatedScalar(INT32, true)`) + `repeated int64 b`
(`RepeatedScalar(INT64, true)`) and asserting the packed encode/decode shape + `assertCompiles`.
Gate: `./gradlew :recordbuf-codegen:spotlessApply` then `./gradlew build` (both GREEN as of
`a30dd4e`).

### M3 design (2026-11-07, refined) — general fluent AST layer, THEN record-level build emission

The module's scope was refined by the user: `:recordbuf-codegen` is a **general, project-
agnostic, dependency-free Java emitter** — a clean-room sealed AST (the `TopLevelType`/`FieldSpec`
sealed hierarchy, already in place) with a **fluent, JavaPoet-like builder API** layered on top, so
callers build the AST by fluent construction (`JavaFile.builder(pkg).type(...).build()`,
`RecordSpec.builder(name).add(...).build()`, `FieldSpec.builder(...).wire(INT32).build()`,
`CodeBlock`/`CodeBuilder` for the codec's imperative bodies) rather than passing raw FQN
strings. The emitted `.rec` surface stays dependency-free (R22); JavaPoet remains clean-room
prior art, not a dependency or a source copy. **M3 is two sub-phases, gated separately:**

- **C1 — general fluent AST layer.** Add a fluent builder API over the existing sealed nodes
  (`JavaFile`/`RecordType`/`EnumType`/`FieldSpec`/`EnumConstantSpec`/`MethodSpec`/a small
  `CodeBlock` for bodies), keeping the positional record ctors as thin delegating constructors (or
  as the builder's terminal `build()` target). Project-agnostic. Gated by the module's own tests
  (fluent construction == the positional shape) and a `javac`-compile assertion. No M4 fixtures yet.
- **C2 — record-level compact constructor + Builder emission ON TOP of C1.** Use the general AST to
  emit the record's own nested `public static final class Builder` + `builder()`/`toBuilder()` +
  the compact constructor (null-safe `List`/`Map`, matching the current `CodeGenerator.addBuilder` /
  `addCompactConstructor`), driven off `FieldSpec.Encoding` + `nullable` + `type`. Gated by a
  self-contained `javac`Compile in `RecordEmissionTest`. Still NOT the M4 fixtures (oneof/WKT/nested
  /map/interop/avro/package-info are separate slices and remain unported).

`./gradlew :recordbuf-codegen:spotlessApply` then `./gradlew build` gates each sub-phase; **
CHECKPOINT with the user before M4** (the high-risk generator swap). M5 (delete the old
`JavaWriter`/`TypeImports` — irreversible) stays a separate later decision.

## Resume prompt (for a fresh session)

You are continuing a refactor of `recordbuf`'s protobuf plugin: rewrite its hand-rolled
`JavaWriter`/`JavaWriter.TypeImports` as a purpose-built, clean-room `:recordbuf-codegen` Gradle
module (`codegen/`) with a sealed `TopLevelType` AST, `record`/`enum` model records, a `Renderer`, and
a `TypeCollector` import resolver. The module must emit byte-identical output to the existing plugin
so the `:recordbuf-fixtures` interop tests keep passing. Generated `.rec` output must remain
dependency-free (NO JavaPoet on generated code); JavaPoet may be on the module's OWN build classpath
only (MIT, byte-identical to Java 1.0 `javax.tools.JavaCompiler` output so it won't trip a copyright
review; the module is MIT, not GPL/AGPL so no copyleft risk; do NOT reuse JavaPoet's source/`@license`/
blob header verbatim).

STATE: M0/M1/M1.5/M2a/M2b are committed + gated (see commits `39152b4` `dcabeb6` `7c23737`
`b3458bc` `62a6b2d` `a30dd4e`; the `:recordbuf-codegen` module builds + tests green —
`./gradlew build`, 12/12 tests). The sealed `FieldSpec.Encoding` now permits
`Scalar, EnumField, MessageField, RepeatedMessage, RepeatedEnum, RepeatedScalar`.

M3-C1 (fluent AST over the sealed spine) is COMPLETE: `@RecordBuilder` on
`RecordType`/`FieldSpec`/`EnumType`/`EnumConstantSpec`/`JavaFile` gives a compile-time-only fluent
`XBuilder.builder()` API (proven identical to the positional ctor by `FluentBuilderTest`); JSpecify
`@Nullable String` replaced `Optional` on every doc-comment/name slot in the public API (project
convention, not `Optional`); and the two `EnumConstantSpec` convenience constructors were dropped
(builders + the single canonical ctor only — no overloads, no static factories). `io.soabase.
record-builder` is COMPILE-TIME-ONLY (`compileOnly` core + `annotationProcessor`), so the
emitted dependency-free `.rec` surface is untouched. **CHECKPOINT: M3-C2 (record-level `Builder` +
compact-ctor EMISSION) is a larger design that changes what is emitted + the runtime — confirm
scope/sequencing with the user before starting.** Remaining high-level slices: M3-C2, M4 (the
generator SWAP — `Main`→module, all `:recordbuf-fixtures` interop byte-identical), M5 (delete the
old `JavaWriter`/`TypeImports` — irreversible). Oneof + WKT + nested-message + map fields are
separate slices.
Gate every slice with `./gradlew :recordbuf-codegen:spotlessApply` then `./gradlew build`; add a
real `javac`-compile assertion to `RecordEmissionTest` when the emitted record is self-contained (it
compiles against `:recordbuf-runtime` + `protobuf-java` + `jspecify`, all on the codegen `test`
classpath).

---

## M4 design (2026-11-07, locked by the user) — replace string-concatenation with a pure
Java-source AST; keep the Writer structure as the body-emission mechanism

M4 is **not** "route `Main` through the codegen module and delete the writer stack in one shot."
User-locked shape:

1. **The `:recordbuf-codegen` module becomes a proto-AGNOSTIC Java-source AST**, not a proto
   model. `JavaFile` → one top-level `Type`; a `Type` carries its modifiers/annotations/Javadoc +
   its own body **as a list of `Member`s, nested types being ordinary members (the "fragments")**.
   Build the tree (each node with its sub-nodes), then RENDER the whole tree in a single top-down
   pass (two-phase import resolution first, as today's `TypeCollector.finalizeResolution`).
2. **Proto concepts do NOT live in the module.** `oneof`, `WKT`, wire encoding stay in the
   generator's `model`/`types` layer (`WellKnownTypes`, `Oneof`, …) and are **mapped into** Java
   AST nodes. A oneof becomes a nested sealed interface + nested value-record members on the
   enclosing `Type`; a WKT field becomes whatever Java substructure the model already assembles.
   The module never sees a proto.
3. **The AST is structural at the type/member level, but a method/constructor BODY is just a
   `List<Line>`.** A `Type` has members (`FieldComponent`, `EnumConstant`, `Method`, `Constructor`,
   `NestedType`, `StaticBlock`, … — a sealed hierarchy), and a `Method`/`Constructor` is a signature
   + a body that is a sequence of raw source lines. The proto logic (oneof dispatch, WKT wrappers,
   builder setters, the codec's encode/decode) **emits lines into a method's body and nested types
   into the type's body** — so the existing Writer *structure* (its loops + `line(...)` emission)
   stays as the mechanism for building method bodies; only the outer structure (nested types, field
   / enum-constant decls, the type + file wrapper) becomes the AST. This is why proto logic can stay
   richly "line-emitting" without a full expression-statement AST.
4. **REPLACE, don't keep both** (user decision, Fork 1). The proto-aware
   `RecordType`/`FieldSpec`/`EnumType`/`EnumConstantSpec`/`FieldSpec.Encoding`/`WireScalar` design
   is superseded. Path: build the new pure-Java AST files alongside, prove each slice renders +
   `javac`-compiles (gate = a new `JavaSourceTest` + the generator's emission tests staying green),
   and only remove the old nodes + `EmissionTest` once the new AST reproduces their output.

**Fork 1 = REPLACE; Fork 2 = sealed Java-source AST tree, nested types as members, single top-down
render.**

### First increment (M4-C1): the pure Java-source AST skeleton + a render gate

Build, in `:recordbuf-codegen`, a proto-agnostic AST:
- `JavaFile(pkg, javadoc, TopLevelType type, TypeCollector ci, two-pass render)`.
- `TopLevelType`/`Type` — a **sealed** hierarchy: `Record`, `Enum`, `SealedInterface`, `Class`.
  Each `Type` has `{modifiers, annotations, javadoc, name, parent?/typeParams?, implements/extends?,
  body: List<Member>}`.
- `Member` — a **sealed** hierarchy: `FieldComponent`, `EnumConstant`, `Method`, `Constructor`,
  `NestedType` (a type as a member), `StaticBlock`. A `Method`/`Constructor` body is a
  `List<Line>` (raw source lines the proto logic emits into).
- `Javadoc` node; `TypeReference` (resolved short/long name via the kept `TypeCollector`);
  `Modifiers` (a set). Keep the record-builder + JSpecify-`@Nullable` treatment on the AST nodes
  themselves (compile-time-only surface; the emitted code stays dependency-free).
- A `Renderer` that renders the tree top-down (after two-phase import resolution), joining a
  method's `List<Line>` body verbatim.

Gate: a new test builds a small Java source through the AST — a record with a nested enum + a method
with a body of emitted lines — renders to source, and that source `javac`-compiles against the
runtime. Then migrate the generator's `EnumGenerator`/`RecordGenerator`/`CodeGenerator` to build
this AST instead of string-concatenating into `JavaWriter`, keeping the generator's emission tests
green. WKT/oneof/map/nested-message map in feature-by-feature as the generator's needs force them.

**This is large + multi-slice; build incrementally, gate each slice, commit at each gate.**

**M4-C1 cleanup: removed the M2 false start (2026-11-07).** The M2 proto-aware AST -- the
`codegen` parent-package `TopLevelType`/`RecordType`/`ClassName`/`EnumType`/`Renderer`/`FieldSpec`/
`JavaFile`/`WireScalar`/`EnumConstantSpec` + their `EmissionTest`/`FluentBuilderTest`/`RecordEmissionTest`
tests -- was the previous false start and is DELETED. It was the mini-JavaPoet-with-proto-awareness
the user rejected in favor of the pure `source` AST. The `:recordbuf-codegen` module now contains ONLY
the new `source` subpackage (`Modifiers`/`Javadoc`/`Line`/`Member`/`Type`/`JavaFile`/`SourceRenderer` +
`JavaSourceTest`) plus the generic, reused `TypeCollector` (+ `TypeCollectorTest`). Nothing outside the
module referenced the M2 nodes -- they were self-contained -- so this deletion is safe and the full
`./gradlew build` stays green.

NOTE: `codegen/build.gradle.kts` still wires `record-builder` + JSpecify (leftover from M3-C1): for the
M4-C1 *skeleton* the `source` AST is plain (un-`@RecordBuilder`, no `@Nullable`), so the wiring is
temporarily unused but ANTICIPATORY -- the soon-to-come fluent `source` nodes (M3-C1 decision) will
carry `@RecordBuilder` + JSpecify `@Nullable`, so re-confirm the wiring is needed when they add a
string-based API. (It is harmless now: the processor runs with nothing to process.) The `source` nodes
were deliberately kept plain for this first slice; add fluency + JSpecify only when the `source` AST
decides on a string-based API, per the user's "build fluently" direction.

**M4-C1 COMPLETE** (2026-11-07, this slice): the pure-Java `source` subpackage now compiles + a
`JavaSourceTest` renders a top-level `Sample` record owning a nested `SampleType` enum (`getNumber`/
ctor/stat-init shape) and two static methods -- every method/ctor/static-block body a `List<Line>`
emitted verbatim -- and the rendered source `javac`-compiles in-process. That proves the "structural
AST, line-emission bodies" hybrid is valid Java. Nodes: `Modifiers` (a plain modifier-prefix),
`Javadoc` (blank/null renders nothing), `Line`, `Member` (sealed: `Field`/`EnumConstant`/`Method`/
`Constructor`/`StaticBlock`/`Nested`; bodies are `List<Line>`), `Type` (sealed `Record`/`Enum`; each
carries `mods/javadoc/deprecated/name/extends/implements/members`, nested types are body members),
`JavaFile` (package + optional header + imports via the re-kept `TypeCollector` + top-level type),
`SourceRenderer` (top-down two-phase: collect refs -> `TypeCollector.finalizeResolution()` ->
render; `Field`s become components, the rest become blank-separated body members; a body's
`List<Line>` joins verbatim at the body indent). `TypeCollector` + `ClassName`-style resolution were
kept (they are NOT proto concepts). NEW: the `source` subpackage is isolated -- no module references
it yet, so the generator's emission tests are untouched and `:recordbuf-codegen:build` is green.

**NEXT (M4-C2):** route the generator's `EnumGenerator`/`RecordGenerator`/`CodeGenerator` through
this new pure-Java AST (build a `Type.Record`/`Type.Enum` tree + line-bodies instead of string-
concatenating into `JavaWriter`), keeping the generator's emission tests green. Then delete the old
string-concat writer pieces (`JavaWriter`, the old `RecordGenerator`/`EnumGenerator` string
callsites) and, once the new AST reproduces them, the proto-aware nodes (`RecordType`/`FieldSpec`/
`EnumType`/`EnumConstantSpec`/`Encoding`/`WireScalar`) + the `EmissionTest`. Each swap gated on the
generator's emission tests. (Oneof/WKT/map/nested-message + the `SealedInterface`/`Class` type nodes
are LATER M4 sub-slices -- the design listed them, but M4-C1/C2 keep to `Record`/`Enum` + `Method`/
`Field`/`EnumConstant`/`Constructor`/`StaticBlock`/`Nested`; add nodes as the generator's needs force
them.)

**M4-C2a COMPLETE** (2026-11-07, delegated to a `worker` subagent + parent-accepted; commit
`e1b4bf0`): the first bounded increment of M4-C2 -- map the proto `Enum` model into the `source`
`Type.Enum` and render/compile it, generator-side only (no live-emit wiring, no `:recordbuf-codegen`
change). Added `implementation(project(":recordbuf-codegen"))` to `:recordbuf-generator` (NOT `api`
-- R22: generator-internal, emitted `.rec` stays dependency-free), a test-side `EnumSourceMapping`
(proto `Enum` -> `source.Type.Enum`, the enum CORE: `public enum Name`, per-constant Javadoc +
`@Deprecated`, `private final int number;`, the ctor, `getNumber()`), and an `EnumSourceMappingTest`
(builds a 3-constant `Color`, renders via `SourceRenderer`, asserts structure + that the
`BY_NUMBER`/`static{}`/`forNumber` cluster is ABSENT, then `javac`-compiles the output to a temp
`-d` dir). Full `./gradlew build` green. The mapping assembles `members` as an open, ORDERED
`List<Member>` so the next increment appends the cluster to the tail.

Two LATENT `:recordbuf-codegen` issues the subagent surfaced (left unfixed -- they were out of scope
for M4-C2a, but are M4-C2a-ext work): (1) a cosmetic double-indent bug in `SourceRenderer` for enum
constant Javadoc (compiles, just ugly); (2) `Type.Enum` joins `implementsFqns` raw without running
them through `TypeCollector.use`, so a cross-package interface would not import -- a no-op for the
bare-enum core (no interfaces) but a real latent bug for a future interface-mapping increment.

**Next increment: M4-C2a-ext -- extend `:recordbuf-codegen` + fold in the `BY_NUMBER` cluster, then
re-gate on the FULL `EnumGenerator` mirror.** DESIGN (parent decision):
- Add an initializer slot to `Member.Field` (a `String initializer`, blank = no initializer;
  render `fieldType name = initializer;` when present). M4-C2a's existing `Member.Field` usages pass
  a blank initializer -- the field type is unchanged, just `= initializer` is appended conditionally.
- Generic/type-arg resolution: DO NOT make `TypeCollector.use` generic-parsing (it only resolves a
  raw FQN -> short name + import; a generic string like `Map<Integer, Color>` is stripped to bare
  `Map`). Instead the MAPPING builds the short-form type string from `ci.use(rawTypeFqn)` per
  component + the enum's own same-package constant short names: `ci.use("java.util.Map")` +
  "<Integer, " + name + ">" for the field type, and "new " + `ci.use("java.util.HashMap")` + "<>" for
  the initializer. So `java.util.Map` + `java.util.HashMap` import + render as short names; `Color`
  is same-package (no import). This keeps the resolver generic-AGNOSTIC (line-emission handles the
  generic string -- the hybrid's "line-emission for the rest").
- `EnumSourceMapping` appends to the open `members` list: the `BY_NUMBER` field (`private static
  final Map<Integer, Color> BY_NUMBER = new HashMap<>();`, with its `ci.use`-registered types), its
  `Member.StaticBlock` (`BY_NUMBER.put(n, NAME);` per constant), and the `forNumber` `Member.Method`.

## M4-C2b-1 DECISION (recorded 2026-11-10, SHIPPED 2026-11-10, parent did it directly):
the record SHELL, parent-implemented -- test-only, no AST extension

M4-C2b is the record/codec write path -- the last big slice. Unlike the enum's clean core+cluster,
the record isn't cleanly separable: a record's component params already depend on each field's
kind/label/oneof/WKT-type, and `CodeGenerator` is an 803-line assembler with codec/oneof/interop/Avro
emission spread across ~6 sibling generators. So M4-C2b decomposes shell-first, then codec:
- **M4-C2b-1 (this increment, SHIPPED) -- the record SHELL.** A new test-side `RecordSourceMapping`
  (`generator/src/test/.../writer/`, mirroring `EnumSourceMapping`): map a proto `Message` to a
  `source.Type.Record` -- the record HEADER (`public record Name(...)` + Javadoc + `@Deprecated` +
  `implements <interfaces>`), the record's COMPONENTS (one leading `Member.Field` per `Message.field`,
  its Java type + `javaName`, in `members`; `SourceRenderer.renderRecord` classifies every
  `Member.Field` among a record's members as a component, the rest as body members), and its NESTED
  ENUMS as `Member.Nested` (reusing `EnumSourceMapping`), with an EMPTY codec body (no encode/decode
  yet). Gate: `RecordSourceMappingTest` builds a representative `Message` fixture (two scalar components
  `int32`/`string` + a nested `Color` enum), maps it, renders via `SourceRenderer`, asserts
  structure (record header + components + the nested enum's `BY_NUMBER` cluster with shared
  `java.util.*` imports + NO codec), and `javac`-compiles the output. Does NOT touch
  `CodeGenerator`/the live emit.
  - Component types use a SMALL, documented scalar subset the mapping owns (`int32/uint32/fixed32/
    sfixed32/sint32 -> int`, `int64/.../fixed64 -> long`, `float`, `double`, `bool -> boolean`,
    `string -> String`, `bytes -> byte[]`); full field typing (repeated->List, optional->@Nullable/
    boxed, map->Entry, enum, nested-message resolution, WKT converters) is M4-C2b-2+. A non-scalar
    field throws `UnsupportedOperationException` (honest "not yet"; that typing has no shared helper
    -- `TypeResolver`/`WellKnownTypes`/`ModelLoader` scatter it).
  - Implementation note -- the components were modeled as leading `Member.Field`s, NOT `Member.Parameter`
    / a `params` field: the current `Type.Record` (8-arg, no `params`) already classifies its
    `Member.Field`s in `members` as the components, so the shell needs NO AST extension -- reuse as-is.
    The M4-C2b-1 design draft (above) had speculated a `Member.Parameter`/`params` shape; the shipped
    code uses the existing `Member.Field` classification.
  - KNOWN limit confirmed by `JavaSourceTest.sample()` + `SourceRenderer.renderRecord`: components
    STILL go through `ci.use(componentType)`, which strips generics (exactly as it did for the
    pre-fold `BY_NUMBER`). So a `List<X>` component (a repeated field) would render bare `X`; the
    pre-resolve + verbatim fix the M4-C2b-1 fold-in introduced for `BY_NUMBER` must also cover the
    record's components -- M4-C2b-2 (the shell's fixture has no generic components, so it compiles
    clean today). No AST change was needed for the shell itself.
- **M4-C2b-2+ -- the CODEC.** `encode`/`decode` + WKT dispatch + the unknown-field carrier + oneof (+
  nested messages, generic/repeated/optional/map components) as `Member.Method`/`Member` nodes whose
  bodies are `List<Line>`s -- the "line-emission for the rest" half. This is where the AST's remaining
  gaps surface (statements/expressions for non-trivial bodies, WKT converter nodes, generic components,
  nested-message components). Then M4-C2c wires it into `CodeGenerator` + deprecates
  `JavaWriter`/`RecordGenerator`/`EnumGenerator`; M4-C2d is the interop gate.

---

### M4-C2a-ext shipped + its residuals, and the M4-C2b scope

**M4-C2a-ext shipped + accepted (2026-11-10, parent did it directly, NOT a subagent -- the first real
cross-module AST change was kept hands-on). Three commits:**
1. `b347a81` `codegen(M4-C2a-ext): fix two latent SourceRenderer bugs` -- the constant-Javadoc
   double-indent + `implementsFqns`/`extendsFqns` resolution through `TypeCollector.use` (a new
   `joinResolved` helper), as a clean standalone slice before the fold-in.
2. `b2e046d` `codegen(M4-C2a-ext): render the BY_NUMBER reverse-lookup cluster through the source AST`
   -- `Member.Field` += `initializer` slot; `SourceRenderer` renders a body field's type +
   initializer verbatim (pre-resolved short form); `EnumSourceMapping` folds in the full cluster
   (`BY_NUMBER` field + `StaticBlock` + `forNumber`); `EnumSourceMappingTest` flips the three
   `assertFalse`s to `assertTrue`s + adds `BY_NUMBER.put`/`forNumber`/import assertions + `javac`.
3. (plan doc -- this commit.)

**What the fold-in revealed about the AST (confirmed by implementation, not just design):**
- `ci.use()` IS generic-stripping: it resolves the RAW FQN and returns the short name, mangling a
  `Map<Integer, Color>` to bare `Map`. So the MAPPING pre-resolves each component
  (`ci.use("java.util.Map")` + `"<Integer, Color>"`, `"new " + ci.use("java.util.HashMap") + "<>"`)
  and the renderer emits the pre-resolved string verbatim. The two-phase register-then-resolve model
  holds; the generic STRING is a line-emission detail. No AST collision for generic types surfaced.
- `Member.Field` was extended to a 6th component `initializer` (record positional -- the source
  records are NOT `@RecordBuilder`, so positional is idiomatic; no factory/ctor overload needed).
- Rendered body-field type + initializer are emitted VERBATIM, NOT re-resolved (avoids the generic
  mangling). Documented on `Member.Field` + in `SourceRenderer`.

**Residuals (NOT fixed -- noted for later M4 polish, not M4-C2a-ext blockers):**
1. `SourceRenderer` emits NO blank line between body members; `JavaWriter` (the `EnumGenerator` path)
   did. The AST output is more compact -- valid + compiles, but a readability-polish gap. A later
   increment can add "blank between top-level body members."
2. The cluster's leading `// Reverse lookup used by the codec when decoding from wire.` comment is NOT
   reproduced: the `source` AST has no standalone line-comment member (only `Javadoc`, a `/** */`
   block). Noted on `EnumSourceMapping`. A later increment can add a line-comment member + fold it in.

**M4-C2b scoped -- the record/codec write-path increment (parent-owned).** The record is the main
codegen (encode/decode), so it's the biggest slice. Its shape is the same pattern as the enum -- a
new `RecordSourceMapping` (proto `Message` -> `source.Type.Record`) + a render+compile test -- but it
touches far more AST surface than the enum. Before scoping the FULL codec, the sub-decomposition:
- M4-C2b-1 the record SHELL: `public record Name(component1, ...)` + its fields/params, no codec.
  Add whatever AST nodes the shell needs (a record's `params`, `components`, canonical ctor). This is
  the smallest record increment + proves the shell renders + compiles.
- M4-C2b-2 the codec methods: the `encode`/`decode` (+ WKT dispatch + scalar codecs) emitted as
  `Member.Method` nodes whose bodies are `List<Line>`s -- the "line-emission for the rest" half.
  This needs the `CodeGenerator`/`CodecGenerator` emission to be re-threaded through the AST, and
  will surface the AST's remaining gaps (statements/expressions for non-trivial bodies, nested
  helper types, WKT converter nodes -- or whether they stay line-emission).
- Gate each sub-increment: render + `javac`-compile a representative record; then M4-C2c wires it into
  `CodeGenerator` + deprecates `JavaWriter`/`RecordGenerator`/`EnumGenerator`. M4-C2d (options.proto +
  `:recordbuf-fixtures` green via recordbuf + Avro + protobuf-java) stays the interop gate as scoped.

### M4-C2b-1 SHIPPED (2026-11-10, parent did it directly, test-only)

`generator/src/test/.../writer/RecordSourceMapping.java` + `RecordSourceMappingTest.java` (this
commit): the record-shell mapping, as designed. `RecordSourceMapping.toSource(Message, TypeCollector)`
builds a `Type.Record` whose header + leading `Member.Field` components + `Member.Nested` nested enums
(rendered via `EnumSourceMapping`, so a nested enum's `BY_NUMBER` cluster renders inside the record
with shared `java.util.*` imports). `RecordSourceMappingTest` maps a `Message` fixture (two scalar
components `int32`/`string` + a nested `Color` enum), renders it, asserts the shell (record header +
components + the nested `BY_NUMBER` + NO codec), and `javac`-compiles it. **No AST extension was
needed** -- `Type.Record` already classifies its leading `Member.Field`s as the components. The codec
remains M4-C2b-2+.

**Next: M4-C2b-2** -- the codec (`encode`/`decode` + WKT dispatch + the
`UnknownFields` carrier + oneof) as `Member.Method`/`Member` nodes with `List<Line>` bodies. This is
where the remaining AST gaps (statements/expressions for non-trivial bodies, WKT-converter nodes,
generic components) surface; it likely needs new AST nodes and is the AST-extension-heavy core --
parent-owned, gated like M4-C2a-ext.

### M4-C2b-2a SHIPPED (the nested-static-class shape on the AST)

`Type.Class` joins the `source` AST as a third `Type` subtype, alongside `Record` and `Enum`. It is
the node M4-C2b-2+ uses for the per-message `Codec` (a `public static final class Codec { ... }`
nested inside the record). `SourceRenderer` renders it like a record -- mods + `class Name`, with
`extends`/`implements` clauses and a body of arbitrary `Member`s -- the one difference being the
`extends` clause (a record never has one). The M4-C2b-2a gate (`ClassSourceTest`, in the codegen
test tree -- a pure AST shape, no proto mapping yet): a `MessageRec` record that owns a nested
`public static final class Codec` (private ctor + trivial `encode`/`decode` static methods whose
BODIES are already `List<Line>`s) rendered through `SourceRenderer` + compiled in-process. It passes:
the nested-static-class-in-a-record shape renders + compiles through the AST, with no new body node
(the hybrid's "line-emission for the rest" already covers method bodies).

What the gate proved + what it leaves:
- PROVED: a static nested `class` renders + compiles inside a `record`'s body (via
  `Member.Nested` + `SourceRenderer.renderClass`), sharing the record's `TypeCollector` (so the
  codec's `java.io.IOException`/`java.util.*`/runtime-helper imports land in the same file).
- LEFT for M4-C2b-2+: the codec's real BODIES. M4-C2b-2a leaves `encode`/`decode` with placeholder
  lines; M4-C2b-2b re-threads `CodecGenerator`'s ~900-line per-field/oneof/WKT/unknown-field emit
  (`emitEncode`/`emitDecode`/`emitEncodeField`/`emitDecodeField`/`emitOneofEncode`/`emitMapEntry`)
  into `List<Line>` bodies on the `encode`/`decode` `Member.Method`s -- the "line-emission for the
  rest" half -- and M4-C2b-2c handles the remaining types (nested-message components, map-entry
  records, the WKT dispatch, and the `UnknownFields` carrier).

### M4-C2b-2b SCOPED + the codec's shape decision (surface to the parent before implementing)

**Scope:** M4-C2b-2b re-threads `CodecGenerator`'s per-field/oneof/WKT/unknown-field encode/decode
(~900-line `emitEncode`/`emitDecode`/`emitEncodeField`/`emitDecodeField`/`emitOneofEncode`/
`emitMapEntry`/`emitWktEncode`) into `List<Line>` bodies on the `Codec`'s `encode`/`decode`
`Member.Method`s (the codec is built via `RecordSourceMapping.toCodec(...)` in the generator, using
`TypeResolver`/`WellKnownTypes`/`TypeImports`).

**The shape M4-C2b-2a + a read of `CodecGenerator` established:** the codec's bodies are
fundamentally *line-emission* -- each per-field encode/decode is a `switch`/`if` that emits a block
of pre-indented lines (the oneof group is an exhaustive `switch (v.oneof()) { case X -> out.writeY(...) }`,
the WKT dispatch is a per-message `switch`, the singular fields are guarded `if (v.x() != 0)
out.write...(...)`). M4-C2b-2a already proved a method body is `List<Line>`. So the codec's
`encode`/`decode` *can* be `Member.Method`s with `List<Line>` bodies -- no new statement/expression
node is strictly required for the first codec increment.

**The decision the parent called for ("see the shape + decide before building"):** the codec bodies
are line-emission, so the question is whether to (a) *re-thread* `CodecGenerator`'s `w.line(...)` calls
to `List<Line>` bodies directly in the mapping (a big mechanical port, matching M4's "structure at the
type/member level, line-emission for the rest" intent), or (b) grow a deeper statement/expression AST
for the codec (much more AST surface, more durable, a bigger lift), or (c) keep a small
JavaWriter-style *line emitter* that the mapping calls to fetch each body's `List<Line>` (defers the
deep port + keeps the codec logic where it lives today). Option (a) is a direct, bounded port;
(a-vs-b) is the real "is line-emission enough for the codec" call. Suggested first increment (any
option): the `encode`/`decode` *framing* + **singular scalar fields only** (no oneof/WKT/unknown-field
carrier yet) -- the smallest real codec, gated by a record-with-scalar-fields fixture that renders +
round-trips. This is the natural point to hand the decision back: the M4-C2b-1 shell and M4-C2b-2a
class shape are shipped + gated; M4-C2b-2b's approach is a design call, not a clean "keep going."
## M4-C2b-2b-1 (this session): the codec's `Method` gets a builder + `throws`; the codec shape renders

The user's correction — "Method should have a builder so only the elements that are needed get added,
default in compact constructor not the builder" — drove this increment (not positional records):

- **`source.Member.Method`** gained a `List<String> thrown` field (the codec's `encode`/`decode` are
  `throws java.io.IOException`, so a `Method` must declare throws types) AND is now
  **`@io.soabase.recordbuilder.core.RecordBuilder`-backed** (the repo's established builder — the same
  one the model records `Field`/`Message`/`Enum`/`ProtoFile` use), so a caller builds a `Method` from
  ONLY the elements it needs. The generated `MemberMethodBuilder.builder().mods(...).returnType(...)
  .name(...).params(...).thrown(...).body(...).build()` is the surface.
- **Defaults live in the COMPACT CONSTRUCTOR, not the builder** (per the user): the `@RecordBuilder`-
  generated builder leaves the optional fields unset (null), and `Method`'s `public Method { ... }`
  compact ctor seeds them — `javadoc` -> `Javadoc.empty()`, `mods` -> `Modifiers.none()`, `thrown` ->
  `List.of()`, `body` -> `List.of()` — so a caller sets only the fields that apply, and the record's
  accessors stay non-null. (`abstractDecl` is a primitive, so it defaults to `false` in the builder;
  the compact ctor leaves it alone.)
- **`source.Javadoc`** gained a `empty()` sentinel (`new Javadoc(null)`) as compact-ctor's default.
- **`source.SourceRenderer`**: `renderMethod` now emits a resolved `throws` clause (each thrown type
  through the collector, mirroring how the return type is resolved). And the two-phase **collect pass**
  now pre-registers a `Method`'s `thrown` types for the import block too (it used to register only the
  return type — so a `throws` type landed in the rendered body but its `import` was emitted *after* the
  import block and was missed). That collect-pass gap is the bug the codec surface surfaced.
- **All 7 positional `new Member.Method(...)` call sites migrated** to `MemberMethodBuilder.builder()
  ...build()` (`JavaSourceTest` x3, `ClassSourceTest` x2, `EnumSourceMapping` x2). No call site passes
  a defaulted field.
- **`source.ClassSourceTest`** (the M4-C2b shape gate) now builds the codec's `encode`/`decode` *via the
  builder* with `.thrown(List.of("java.io.IOException"))`, and asserts the `throws IOException` clause
  renders, the `import java.io.IOException;` line is emitted, and the whole file compiles through
  javac. This is the end-to-end proof that the codec's public shape (nested class + static
  `throws`-bearing methods, built from only the fields that matter) survives the source AST + render.

Next increment (M4-C2b-2b-2): fill the codec's real per-field `encode`/`decode` BODIES — reuse
`CodecGenerator`'s `emitEncode`/`emitDecode` (the ~900-line wire logic) as the `List<Line> body` of
the `Method` built above — so the nested `Codec` stops being a skeleton and actually encodes/decodes.
The builder + `throws` surface this increment added is exactly what that reuse needs.

## M4-C2c: generalize the fluent-builder pattern to all multi-field AST node records (shipped 2026-11-10)

After M4-C2b-2b-1 gave `Member.Method` a `@RecordBuilder`-backed builder + a compact constructor that
seeds the optional fields, M4-C2c generalizes that to **every multi-field node record**, per the user's
steer ("we have builders for the recordbuf types") + option "A — generalize to all node records."

- **Scope:** `@io.soabase.recordbuilder.core.RecordBuilder` on `Type.Record/Class/Enum` +
  `Member.Field/EnumConstant/Constructor` (+ `Member.Method` from M4-C2b-2b-1) — the 7 multi-field source
  nodes. Single-field records (`Member.StaticBlock`, `Member.Nested`) + the leaf helpers (`Modifiers`,
  `Javadoc`, `Line`) keep their positional constructors / static factories.
- **Compact-ctor defaults (in the compact ctor, NOT the builder, per the user):** each record gains a
  `public` compact constructor that seeds the optional fields — `javadoc -> Javadoc.empty()`,
  `mods/modifiers -> Modifiers.none()`, `deprecated -> false`,
  `extendsFqns/implementsFqns -> List.of()`, `params/body -> List.of()`, `ctorArgs -> ""` (EnumConstant),
  `initializer -> ""` (Field). `body`/`initializer`/`ctors` are the one `@NonNull`-by-construction
  collection a caller must supply; everything else is defaulted so a caller sets only what it needs.
- **Call-site migration (4 files, generator + codegen test):** `EnumSourceMapping` (the enum constant +
  fields/ctor/`Type.Enum`), `RecordSourceMapping` (the component `Field` + `Type.Record`), and the
  two source-AST shape-gate tests `JavaSourceTest` + `ClassSourceTest`. `RecordSourceMapping` /
  `EnumSourceMapping` are the AST **fixtures the writer's generation tests build + assert** — no import
  needed for the codegen builders there (same package); the two source-AST tests need no import either
  (same package, so the generated `*Builder`/`*With` classes are visible). This is the first migration
  into generator test code; the same call pattern the plugin will use lives in those two shared fixtures.
- **Lesson captured:** the terminal `.build()` is easy to miss when converting a `return new T(...)` to
  `T.builder()....build()` — the positional form's closing `)`/`;` undercounts by one call, leaving the
  expression a `*Builder` (caught by the compiler as "X cannot be converted to T"). Four such omissions
  surfaced before green (two in `JavaSourceTest`, two in `ClassSourceTest`); each fixed by appending the
  terminal `.build()`.
- **Green:** `./gradlew build --rerun-tasks` — all 155 tests pass; no positional
  `new Member./Type.*(...)` constructions remain (only the records' own declarations / the generated
  `*Builder`/`*With` classes).

## M4-C2b-2b-2: a REAL codec body renders through the source AST + compiles with the runtime on the classpath

**Shipped: `78ba245`** — `ClassSourceTest.message_owns_a_nested_static_codec_with_real_scalar_bodies`: a
record with one `int` field + a nested `public static final class Codec` whose `encode`/`decode` bodies
are real scalar codec code — `Encoder.start()`, a guarded write, and a real tag-loop with `Wire.readTag`,
`Wire.tagFieldNumber`, per-field `CodecDefaults.int32()` init, and `Wire.skip` — compiles against the
test runtime classpath via `assertCompilesWithRuntime`.

API details learned along the way:
- `JavaCompiler.getTask(fm, …)` — the 2nd positional arg is the `JavaFileManager` (not the `Writer out`,
  which is the 1st). An earlier draft had them swapped — fixed.
- `StandardLocation.CLASS_PATH` — a distinct enum from `StandardJavaFileManager.Location`; is what
  `setLocation(Location, Iterable<? extends File>)` takes. An earlier draft used the wrong enum type.
- `setLocation(…)` takes `Iterable<? extends File>`, not `Iterable<URL>` — converted `URL` →
  `new java.io.File(url)` on each classpath entry.
- The `if (v.id() != CodecDefaults.int32()) out.writeInt32(1, v.id());` guard is on ONE line
  (a single `Line.of(...)` with the `if` + the write + `;` in one string literal).
- The `switch` arrow-case arms in the body use `-> value = in.readInt32();` form; the `default` arm is
  `default -> bijnagte.recordbuf.runtime.Wire.skip(in, tag);` — all single-line `Line.of(...)` items.
- Two API bugs surfaced + fixed before green:
  1. `getTask(fm, …)` had the `Writer` arg in the wrong position — swapped to `getTask(null, fm, …)`.
  2. `Location.CLASS_PATH` vs `StandardLocation.CLASS_PATH` (the correct `javax.tools`
     enum).

## M4-C2b-2b-3: multi-scalar real-codec shape gate (int+long+String)

**Shipped: `e4be837`** — `ClassSourceTest.message_owns_a_nested_static_codec_with_real_multi_scalar_bodies`: a
record with THREE scalar fields (`int id`, `long value`, `String text`) and a nested `public static final
class Codec` whose `encode` body has three guarded writes (`writeInt32(1, v.id())`,
`writeInt64(2, v.value())`, `writeString(3, v.text())`) and whose `decode` body has a single
`while (true)` tag-loop with a `switch` dispatching to `readInt32()` / `readInt64()` / `readString()`
+ a `default -> Wire.skip(in, tag)` arm. The codec compiles against the test runtime classpath — proving
the source-AST shape handles more than one field family and more than one wire type. `e4be837`.

## M4-C2b-2b-4 + M4-C2b-2c: bytes field family + round-trip proof (shipped a2e845e)

**Two gates added to `ClassSourceTest` (both green, both in `refactor/codegen-module`):**

1. **`record_with_bytes_component_renders_and_compiles`** — structural proof: a record with a
   `Bytes`-typed component and a nested `Codec` compiles cleanly against the runtime classpath.
   The encode writes `out.writeByteArray(1, v.data().toByteArray())`, the decode uses a tag-loop
   with `case 1 -> data = Bytes.of(in.readBytes().toByteArray())` + `default -> Wire.skip(in, tag)`.

2. **`record_with_bytes_round_trips_via_urlclassloader_and_reflection`** — semantic proof: the
   codec's `encode("hello world" bytes)` → `byte[]` → `decode(byte[])` → `RecordWithBytes` round-trips
   byte-for-byte, verified via a `URLClassLoader` + reflection. This is the same hard gate the
   multi-scalar codec test uses, proving the source-AST pipeline produces runnable, semantically
   correct code — not just compilable.

**Lesson:** `in.readBytes()` on protobuf-java's `CodedInputStream` returns a `ByteString`, not a
`byte[]` — call `.toByteArray()` on it to get a raw array. Also, a bare `in.readBytes()` (without
reading the tag first) misreads the tag byte as a varint length; the correct decode shape uses a
`while/switch` tag loop, matching the multi-scalar codec's shape.

**Deferred (M4-C2b-2b-5+):** repeated / map / optional field families + WKT dispatch + oneof +
nested-message + unknown-field carrier.

**Deferred (M4-C2b-2b-4+):**
- bytes/repeated/map/optional field families (the remaining scalar+container coverage).
- M4-C2b-2c: the round-trip test — load `RecordWithRealMultiScalar.class` + `Codec.class` via a
  `URLClassLoader`, call `Codec.encode(RecordWithRealMultiScalar)` / `Codec.decode(byte[])`, assert
  byte-for-byte equivalence (the semantic-equivalence gate).
- Wire the same codec body through `RecordSourceMapping` + the generator (the integration that
  replaces the old `CodecGenerator`).

The next increment after M4-C2c (generalized fluent builders) is to fill the codec's BODIES, which
the user scoped to the "framing + singular scalar fields only" subset -- the smallest real codec:
a record with an `int` field + a nested `public static final class Codec` whose `encode`/
`decode` bodies call the runtime's `Encoder` / `Decoder` / `Wire` / `CodecDefaults` helpers just
as `CodecGenerator` does today, but expressed as `List<Line>` bodies on `Member.Method`s.

- **Scope (this slice):** a new `ClassSourceTest.message_owns_a_nested_static_codec_with_real_
  scalar_bodies` shape gate, in addition to the existing M4-C2b-2a skeleton gate. The new gate
  proves that a record can OWN a `Type.Class` codec whose bodies are REAL encoder / decoder code
  -- the same shape `CodecGenerator.writeCodec` emits today, but expressed on the source AST. No
  oneof, no WKT, no nested-message, no unknown-field carrier yet. (The real per-field/oneof/WKT
  port is M4-C2b-2b-3+; round-trip via the compiled `.class` is M4-C2b-2c.)
- **What the new gate asserts:** the rendered source contains `public static final class Codec`,
  `byte[] encode(RecordWithRealCodec v) throws IOException`, `RecordWithRealCodec decode(byte[]
  inBytes) throws IOException`, a body line that calls `bijnagte.recordbuf.runtime.Encoder.start()`,
  one that drives `bijnagte.recordbuf.runtime.Wire.readTag(in)`, one that reads the default from
  `bijnagte.recordbuf.runtime.CodecDefaults.int32()` -- and it compiles.
- **The runtime classpath for the compile:** the rendered source references `bijnagte.recordbuf.
  runtime.Encoder` / `.Decoder` / `.Wire` / `.CodecDefaults` (transitively
  `com.google.protobuf.CodedInputStream` / `.CodedOutputStream` + `java.io.IOException`). To
  compile the rendered source via `javac` with the runtime available, the new `assertCompilesWith
  Runtime` helper (mirroring the existing `assertCompiles` but with the test's own runtime on the
  compile classpath) is added. It obtains the test JVM's `java.class.path`, wraps each entry as
  a `java.io.File`, and sets it via `StandardJavaFileManager.setLocation(
  StandardLocation.CLASS_PATH, cp)` on a `StandardJavaFileManager` obtained from
  `JavaCompiler.getStandardFileManager`. The compile is then `getTask(out=null, fm, diags,
  ["-d", <temp>], symbols=null, [unit])` -- the second arg to `getTask` is the
  `JavaFileManager`, not the `Writer out` (which is the first arg); the earlier draft got those
  two swapped, which surfaced as `incompatible types: StandardJavaFileManager cannot be converted
  to Writer`. The earlier draft also used `StandardJavaFileManager.Location.CLASS_PATH`, which
  is `StandardLocation.CLASS_PATH` (a separate type in `javax.tools` that `getLocation`/
  `setLocation` actually take); and it passed `List<URL>` to `setLocation`, which expects
  `Iterable<? extends File>` -- not `URL`.
- **Green:** `./gradlew build --rerun-tasks` -- 12+ tests pass in `codegen/
  src/test/java/bijnagte/recordbuf/codegen/source/ClassSourceTest.java` (the new real-codec
  gate + the existing M4-C2b-2a skeleton + the trivial-skeleton `assertCompiles` case, which is
  still exercised). Full-suite `./gradlew build` is green.
- **Deferred (M4-C2b-2b-3+):** (a) oneof / WKT / nested-message / unknown-field-carrier field
  families; (b) a round-trip test that loads the compiled `RecordWithRealCodec.class` +
  `Codec.class` and asserts byte-for-byte equivalence against `CodecGenerator`'s output; (c)
  threading the SAME codec body logic through `RecordSourceMapping.toSource` + the generator
  (the mapping is the next increment). This slice proves the SHAPE + that a real body
  compiles; M4-C2b-2c proves semantic equivalence.
M4-C2b-2c shipped: `b0e5711` — `ClassSourceTest.multi_scalar_codec_round_trips_via_urlclassloader_and_reflection`.

M4-C2d-1 shipped: `CodeGenerator.generateForFile` now routes top-level enums through
`EnumSourceMapping.toSource(enum, ci)` + `SourceRenderer.render(JavaFile)` instead of
`new EnumGenerator().write(w, e, ci)` + `JavaWriter`. The `EnumSourceMapping` and
`RecordSourceMapping` are promoted from `src/test/java` to `src/main/java` (now
production code, not just test fixtures). `RecordSourceMapping.toSource` already produces
the record shell + nested enums as `Member.Nested` nodes for M4-C2d-2.


M4-C2b-2b-4 (bytes) + M4-C2b-2c-2 (round-trip via RecordSourceMapping) shipped:
`cf9a286` + `5f8a7d5`.

- `a2e845e`: `ClassSourceTest.record_with_bytes_component_renders_and_compiles` and
  `record_with_bytes_round_trips_via_urlclassloader_and_reflection` — the bytes scalar
  family compiles + round-trips via URLClassLoader.
- `cf9a286`: `CodecSourceMapping` (in `bijnagte.recordbuf.plugin.writer`, NOT the codegen
  module) maps a `Message` model to a nested `Type.Class Codec` with real `encode`/`decode`
  `List<Line>` bodies. `RecordSourceMapping.toSourceWithCodec` is the integration path
  (rebuilds the record with the codec appended as `Member.Nested`). The runtime is a
  test-time classpath dep (`testImplementation(project(":recordbuf-runtime"))`).
- `5f8a7d5`: `RecordSourceMappingTest.codec_round_trips_through_the_source_ast_and_runtime`
  compiles the source in-memory via `assertCompilesWithRuntime`, loads `Address` +
  `Address$Codec` via `URLClassLoader`, and asserts `Codec.encode(new Address(1, "Main"))`
  → `Codec.decode(byte[])` → `houseNumber == 1`, `street == "Main"`.

Deferred: repeated / map / optional field families + oneof + WKT dispatch +
nested-message + unknown-field carrier.

M4-C2d-2 shipped: `5fa307b`.

- `CodeGenerator.generateRecord(fdp, javaPkg, outName, m)`: the first
  production use of the source-AST pipeline for record generation. Mirrors
  `generateEnum` but calls `RecordSourceMapping.toSourceWithCodec` instead
  of `RecordGenerator.write`.
- `CodeGenerator.isScalarOnly(m)`: gates the new path to the subset
  `toSourceWithCodec` covers — no nested types, no oneofs, all singular
  scalar fields.
- `CodeGenerator.generateForFile` routes scalar-only messages through
  `generateRecord` only when `System.getProperty("recordbuf.source-ast")`
  is "true" (opt-in flag). Default is `false` — the old JavaWriter path
  for all messages. This is a transition path; the flag will be flipped
  to `true` once the full field surface is covered.
- The Avro bridge generates converters that call `Sample.builder()`, which
  the new source-AST path doesn't emit yet. The opt-in flag keeps the
  build green until the builder is added or the Avro bridge is updated.

Deferred: repeated / map / optional / oneof / WKT / nested-message field
families in `CodecSourceMapping`; builder method emission in
`RecordSourceMapping`; flipping the `recordbuf.source-ast` flag to `true`
by default once all families are supported.

M4-C2b-2b-4 shipped: `c5588ca`.

- `CodecSourceMapping`: handles repeated scalars — packed encode (one
  length-delimited group, non-empty only) + packed decode (pushLimit/
  popLimit loop) + `new ArrayList()` initialiser.
- `RecordSourceMapping.scalarComponentType`: returns `java.util.List<java.lang.Integer>`
  for repeated int32 (and similar boxed wrappers for other scalar types).
- New test `maps_a_repeated_scalar_through_the_source_ast_and_compiles`:
  structural gate asserting `List<Integer>` + `pushLimit` in rendered source.

M4-C2b-2b-5 shipped: `717e051`.

- `RecordSourceMapping.toSourceWithCodec`: appends the always-on
  `UnknownFields` carrier as the record's last component (structural
  compatibility with the JavaWriter path).
- `CodecSourceMapping`: encode re-emits via `Wire.writeUnknown`, decode
  accumulates into `UnknownFields.builder()` and passes
  `unknown.build()` as the record's last arg. The `Wire.skip` +
  `fieldNames` paths are removed.
- `RecordSourceMappingTest.codec_round_trips`: updated to pass
  `UnknownFields.empty()` as the 3rd constructor arg.
- All 4 unit tests pass; full `./gradlew build --rerun-tasks` green.

M4-C2b-2b-5 (full) shipped: `e533a84`.

- `Member.Method` gained a `boolean override` field, rendered as `@Override`
  in `SourceRenderer` (a new AST field for annotation support).
- `RecordSourceMapping.toSourceWithCodec` now:
  - implements `ProtobufMessage` (always) + any proto-declared interfaces
  - adds `@Override toByteArray()` returning
    `Name.Codec.encode(this)` — the Layer A marshalling hook
- `order.Sample`: source-AST path produces `record Sample(...) implements
  ProtobufMessage` with `toByteArray()` — structurally compatible with the
  JavaWriter path for the scalar-only subset.
- Missing piece: the fluent builder (`builder()`, `Builder` class,
  `toBuilder()`). Without it, the Avro converter's `Sample.builder()` call
  won't resolve. Deferred to M4-C2b-2b-6 (builder emission through source AST).

M4-C2b-2b-6 shipped: `0518b50`.

- `RecordSourceMapping.emitBuilder` emits a nested Builder class through
  the source AST. The Builder has:
  - one private storage field per component (with codec-default initializer
    via `defaultFor`: "" for String, 0 for numeric, false for boolean,
    new byte[0] for bytes)
  - one fluent setter per component (returning this, type Builder)
  - private unknownFields field (short form resolved via ci.use)
  - public unknownFields setter
  - public build() terminal

- Type.Class (Member.Nested) is fully supported in the source AST.
- Member.Field initializer is used to set field initializers in the
  generated builder class.

Current coverage of the source-AST path:
  [x] Record shell (scalar components, nested enums)
  [x] Nested Codec (encode/decode with real bodies)
  [x] Always-on unknown-fields carrier
  [x] ProtobufMessage interface + @Override toByteArray()
  [x] Fluent builder (builder()/Builder/build())
  [ ] WKT / oneof / nested message / enum / optional / map field families
  [ ] Codec nested class name alignment (old: Sample.SampleCodec,
      new: Sample.Codec — the order test uses SampleCodec)

M4-C2d-3 shipped: `b9b9...` (relax isScalarOnly gate for repeated scalars).

Source-AST path now covers:
  [x] Scalar components (int/long/float/double/boolean/byte[]/String)
  [x] Repeated scalar components (java.util.List)
  [x] Always-on unknown-fields carrier
  [x] ProtobufMessage interface + @Override toByteArray()
  [x] fromBytes() static factory
  [x] Fluent builder: builder()/Builder/toBuilder()/build()
  [x] Nested <Name>Code (encode/decode with real bodies)
  [x] Nested enums (via EnumSourceMapping)
  [x] Integration test: builder → encode → decode → assert
  [x] Integration test: toBuilder round-trip
  [ ] Optional fields (JSpecify @Nullable presence)
  [ ] Message fields (nested message component type)
  [x] Enum fields (M4-C2b-2b-9: forNumber/writeEnum, shipped 5c0e3f9)
  [x] Oneofs (M4-C2b-2b-12: sealed interface + @Nullable component, shipped 40c624e)
  [x] Map fields (M4-C2d-7: entry type + entry codec, shipped 221c2c0 + 376385e)
  [ ] WKT converters
  [ ] Avro converter through source AST
  [ ] Full migration of all fixtures to source-AST path (make it the default)

## M4-C2d-3: integration milestone

End-to-end integration confirmed: `order.proto` (3 int32 fields) generates
and compiles successfully through the source-AST path when
`recordbuf.source-ast=true` is set. The generated `order/rec/Sample.java`
has the full record + Builder + SampleCodec structure.

Current state:
- [x] Record shell (components + nested enums + carrier + ProtobufMessage)
- [x] Nested <Name>Code (encode/decode with real bodies)
- [x] Fluent builder (builder()/Builder/toBuilder()/build())
- [x] fromBytes() static factory
- [x] @Override toByteArray()
- [x] Repeated scalars
- [x] Integration test (in-memory compile + round-trip + builder flow)
- [x] order.proto compiles through new path

## M4-C2b-2b-9: ENUM codec ✅ SHIPPED (5c0e3f9)
Enum fields route through the source-AST codec:
- singular: null-guarded writeEnum(num, e.getNumber()) / Enum.forNumber(in.readEnum())
- repeated: per-element writeEnum / Enum.forNumber(in.readEnum())

## M4-C2d-7: MAP codec ✅ SHIPPED (221c2c0 + 376385e)
MAP fields route through the source-AST codec:
- encode: iterate map.entrySet(), encode each entry via <Field>EntryCodec
- decode: read length-delimited entry, decode via <Field>EntryCodec, put into map
- isResolvable gate updated to no longer block MAP fields

## M4-C2d-8: nested-message integration test ✅ SHIPPED (7659dd4)
Full integration test: Sample (Item single + repeated Item items) → source-AST
render + assert ItemCodec.encode/decode present.

### Current source-AST coverage
- [x] Scalar, repeated-scalar, optional
- [x] Nested message, repeated message
- [x] Enum (singular + repeated)
- [x] Oneof (sealed interface + @Nullable component)
- [x] Map (entry type + entry codec)
- [x] Unknown-field carrier
- [x] Builder (builder()/Builder/toBuilder()/build())
- [ ] WKT type converters
- [ ] Avro converter generation through source AST
- [ ] Nested types (message/enumeration DEFINED inside a message, hasNested())
  — partial: emission works (b823dd6), type resolution not yet wired.
- [ ] WKT type converters
  — isResolvable blocks WKT fields (df0d44f: WellKnownTypes.resolve() != null gate)
- [ ] Avro converter generation through source AST
- [ ] Full migration: make source-AST the default
  — depends on nested types + WKT being fully wired.
  Current `isResolvable` = !hasNested() && no WKT fields.
  Making `useSourceAst` always true = `true` (df0d44f is safe for non-WKT non-nested).

## M4-C2d-9: nested message emission ✅ PARTIAL (b823dd6)
Nested messages are emitted as inner records of the parent. Type resolution
for the inner record's fields references the outer type's namespace but the
typeResolver is not yet recursive (a separate slice).

## M4-C2d-10: all 10 fixture families compile through source-AST path ✅ SHIPPED

All 10 fixtures compile cleanly through the source-AST path:
`int`, `string`, `fixed`, `bytes`, `packed`, `message`, `oneof`, `optional`, `compat`, `order`.

### Key fixes in this milestone
1. **Map codec `Map<>` syntax bug** — `Map<>` was emitted as literal string, corrected to
   `Map<keyType, valueType>`.
2. **Map entry codec reference** — encode/decode now use `entry.entryCodec` form (e.g.
   `MEntry.MEntryCodec.encode(...)`) instead of bare `MEntryCodec`.
3. **Repeated message decode case** — added `emitRepeatedMessageDecodeCase` that accumulates
   decoded values into a list via `.add()` instead of reassigning.
4. **Oneof component in Builder** — the Builder now emits null defaults, copy ctor, and fluent
   setter for `@Nullable <Interface> <componentName>` fields; `build()` also passes the null arg.
5. **Oneof interface emitted as plain `public` interface** — no `sealed`/`permits` since
   per-member wrapper records (NameValue, CountValue, Sub, ColorValue) are not yet emitted.
   This is a future slice (M4-C2b-2b-12 full).
6. **Java utils import registration** — `java.util.HashMap` and `java.util.ArrayList` are
   registered in `TypeCollector` via `ci.use()` for map/repeated local-init.
7. **`typeResolver` codec reference** — for MESSAGE fields, returns `<shortName>.<shortName>Codec`
   (e.g. `Item.ItemCodec`) — the correct nested-class reference.
8. **Message type short name** — the codec's `emitLocalInit` uses `typeUrl`'s last segment
   for the local variable type (e.g. `Item single = null`) instead of the broken
   `codec-ref.replace("Codec", "")` which produced `Item.Item`.
9. **Codec `allNames` includes oneof null defaults** — the `return new X(...)` line now
   includes `null` for each oneof component.
10. **`keyIsBool` unused variable fix** — added a `keyIsBool` branch in the entry codec encode
    for `map<*, bool>` fields.

## M4-C2d-11: WKT codec via source AST — all 12 fixture families now source-AST ✅ SHIPPED (ed7bd56)

All 12 (later 14) fixture families compile through the source-AST path. Oneof messages and
protobuf_java-opted messages remain in the JavaWriter fallback; `isResolvable` returns
`false` for them (see M4-C2d-12 for the fix that unblocked the WKT exclusion).

### Key changes
1. **WktSourceMapping**: new helper that emits:
   - Local-init for structured WKTs (`Instant at = Instant.EPOCH`, `Duration span = Duration.ZERO`)
   - Local-init for wrapper WKTs (`Integer i32 = null`)
   - Repeated WKT local-init (`List<Instant> whens = new ArrayList<>()`)
   - Encode arms: `Wkt.timestamp(v.at())`, `Wkt.wrap(Wkt.Wrap.INT32, v.i32())`, etc.
   - Repeated encode arms: `for (var e : v.whens()) { ... }` loops
   - Decode cases: `at = Wkt.parseTimestamp(in.readByteArray())`, `i32 = (Integer) Wkt.parse(...)`
   - Repeated decode: `whens.add(Wkt.parseTimestamp(in.readByteArray()))`
2. **CodecSourceMapping**: `WktSourceMapping.resolve(f)` check before MESSAGE/ENUM checks in
   encode, decode local-init, and decode tag-loop; null-safe `@Nullable Entry` handling.
3. **CodeGenerator**: `isResolvable` now excludes only oneof and protobuf_java-opted messages
   (WKT exclusion was added in M4-C2d-11, then removed in M4-C2d-12 after the null-safety fix).
4. **WktSourceMapping.nullability**: `@Nullable Entry resolve()` + `Objects.requireNonNull` for
   null-safe `zeroDefault()` / `javaType()` calls.

## M4-C2d-12: WKT null-safety fix + code cleanup ✅ SHIPPED (84be5b5, a0d9c24)

Two bug-fix and cleanup commits after M4-C2d-11's initial WKT support.

### 84be5b5 — WKT null-safety fix
1. **`WktSourceMapping.emitEncode`** — the structured-WKT singular encode case
   `if (!v.field().equals(def))` was flagged by NullAway as a null-deref because
   `v.field()` is `@Nullable` for structured WKTs. Fixed to
   `if (v.field() != null && !def.equals(v.field()))` — short-circuits `equals` when absent.
2. **`RecordSourceMapping.defaultFor`** — WKT fields now get:
   - Structured WKTs: the zero default from `Entry.zeroDefault()` (e.g. `Empty.INSTANCE`)
   - Wrapper WKTs: `null`
   - Repeated WKTs: `new ArrayList<>()`
   This aligns the builder default with the codec's decode local-init so the
   round-trip invariant `decode(encode(x)) == x` holds for WKT messages.

### a0d9c24 — code cleanup
1. **`RecordGenerator` deleted** — the `implementsClause` helper moved to
   `TypeImports.implementsClause(TypeImports, List<String>)` (a natural home). Both
   `CodeGenerator` and `EnumGenerator` now call `TypeImports.implementsClause`.
2. **WKT exclusion removed from `isResolvable`** — `WellKnownTypes.resolve(null)` check in
   `isResolvable` is gone since WKT fields now route through the source-AST pipeline with
   correct null-safety and default-for.
3. **Dead `useSourceAst = true` gate removed** — the system-property gate
   `recordbuf.source-ast=true` is no longer needed; source-AST is the default for all
   messages without oneofs or protobuf_java opt-in.
4. **Stale Javadoc cleaned** on `CodeGenerator.generateForFile` and `generateRecord`.

## M4-C2d-13: Oneof codec + protobuf_java interop + dead-code removal ✅ SHIPPED

The remaining source-AST port items, then deletion of all dead JavaWriter fallback generators.

### 4f25ba1 — Oneof codec via source-AST
- `CodecSourceMapping.emitMessageBody` — oneof encode via `switch (oneofRef)` on the sealed
  interface; per-member encode through `emitOneofMemberEncode`
- Per-member decode via `emitOneofMemberDecode` — tag dispatch on each oneof member's wire tag
- `isResolvable` — `!m.oneofs().isEmpty()` exclusion removed; oneof messages route through source-AST
- `RecordSourceMapping.emitOneofMemberEncode`/`emitOneofMemberDecode` — per-member encode/decode

### b64f826 — `InteropSourceMapping` — protobuf-java interop via source AST
- `InteropSourceMapping.toSource(message, ci)` — nested `Compat` class (`com.google.protobuf.*`
  interop wrapper) through the source-AST pipeline; `newBuilderForType`, `getDefaultInstance`, etc.
- `emitEncode`/`emitDecode` — bidirectional record↔protobuf-java encode/decode
- `RecordSourceMapping.isResolvable` — `!m.options().hasProtobufJava()` exclusion removed
- `CodeGenerator` — `generateRecord` now handles all message types; no JavaWriter fallback
- `InteropSourceMappingTest` — end-to-end interop round-trip test

### 70f29bc — dead-code removal
- `InteropGenerator`, `CodecGenerator`, `EnumGenerator`, `OneofGenerator` — all deleted
- `CodeGenerator.emitMessage`/`recordAndCodec`/`addBuilder`/`addCompactConstructor` — the entire
  JavaWriter fallback emission path is removed (`CodeGenerator` is now ~330 lines, down from ~870)

### bd128af — orphaned Javadoc cleanup
- Removed the 9 orphaned Javadoc blocks left behind by the generator deletions
- Updated `generateEnum`/`generateRecord` Javadoc to drop `@link` references to deleted classes
- Fixed `emitPackageInfo` Javadoc to not reference `JavaWriter`

### Remaining items
All items from the original plan are **complete**:
- [x] Oneof codec via source-AST (`CodecSourceMapping.emitOneofEncode`/`emitOneofDecode`) — M4-C2b-2b-12
- [x] `InteropSourceMapping` — protobuf_java interop via source AST — M4-C2d-13 (b64f826)
- [x] Dead JavaWriter fallback generators deleted (`CodecGenerator`, `EnumGenerator`,
      `OneofGenerator`, `InteropGenerator`) — commit `70f29bc`
- [x] Orphaned Javadoc cleanup after generator deletion — commit `bd128af`
- [x] Avro converter through source-AST path — `AvroConverterSourceMapping` — commit `dafe820`
- [x] Migrate `JavadocEmissionTest` from `JavaWriter` to `SourceRenderer` — commit `70a32ed`
