---
title: Structured AST Type Positions - Plan
type: refactor
date: 2026-11-12
topic: ast-type-positions
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
---

## Goal Capsule

**Objective.** Design the prerequisite that makes the type-reference work real: restructure
the source AST so that **every** type position — both the *declaration / type-body*
positions and the *expression-spliced* positions inside statement bodies — holds a
first-class `TypeRef` (the sealed type defined by the `TypeRef` brainstorm,
2026-11-12-002) instead of a pre-resolved short-name string, with resolution to a
short name or a fully-qualified name happening at render time against an aggregated
import set. This doc is **design-only**: the implementation (rewiring the mappers,
embedding `TypeRef`s in the element/statement model, migrating every `ci.use(...)`
site and `List<String>` position) is a later pass.

**Product authority.** The generated output stays byte-for-byte stable — the
`fixtures/` `.rec` outputs remain Google-Java-Format clean and round-trip, and the
plugin's own tests pass. The import resolution behavior (short + import; FQN on
collision; `java.lang`/in-package short and unimported) is preserved exactly, per
2026-11-12-002.

**Open blockers / dependencies.** None inside this design's own scope. The `TypeRef`
model and its single-render resolution contract (2026-11-12-002) is the design this
one realizes in the AST; 2026-11-12-001's `Annotation.type` is the first such
position to hold a `TypeRef` natively, with no later string-to-`TypeRef` migration.

---

## Product Contract

## Summary

Today the source AST stores type positions as **pre-resolved short-name strings**:
each `*Mapper` calls `ci.use(rawFqn)` while building a node and inlines the returned
short name into a `String` field (`Component.type`, `Method.returnType`,
`Method.thrown`, the `implementsFqns`/`extendsFqns`/`permitsFqns` header lists) or
splices it straight into a statement body string (`Element.of(new HashMap<>()
+ ci.use(...))`). Resolution is therefore eager and baked in; a short-name collision is
only knowable after the whole file is walked, which forces a two-pass emit today.

This design restructures the AST so every type position holds a `TypeRef` (a
`sealed interface` with `Named`, `Primitive`, and `Array` — 2026-11-12-002) that
carries no resolved value. A recursive tree-walk over each node collects the
`TypeRef`s in its positions and in the `TypeRef`s of its children, assembles the
imports and the collision map, and a single render resolves each `TypeRef` to its
short or FQN form. An expression-spliced site is a **parameterized `Raw`** — a
format-string template with a `TypeRef` argument list, resolved and substituted at
render — so a type is never carried as a separate side channel, only as the `Raw`'s own
argument. The result is uniform type handling across **both surfaces** —
declarations and expression-spliced types — and a `TypeRef`-based `ci.use` that
resolves lazily. 2026-11-12-001's `Annotation.type` becomes a native `TypeRef`; no
node is built as a string and later migrated.

## Problem Frame

The type surface splits into two **surfaces**:

**Surface A — declaration / type-body positions.** Clean `String` / `List<String>`
fields that the AST's Javadoc already calls "pre-resolved short form": `Component.
type`, `Method.returnType`, `Method.thrown`, `Param` types, and the `Type.Record /
Class / Enum / Interface` header lists `implementsFqns` / `extendsFqns` /
`permitsFqns`. Turning these into `TypeRef` / `List<TypeRef>` is a bounded, mechanical
change; it is also exactly what 2026-11-12-001 (`Annotation.type`) and 2026-11-12-002
("the AST holds `TypeRef`") need, because both are declaration positions.

**Surface B — expression-spliced positions inside bodies.** Throughout the
`CodecSourceMapping`, `EnumSourceMapping`, `InteropSourceMapping`, and
`AvroConverterSourceMapping` mappers, `ci.use(rawFqn)` results are spliced directly
into statement and initializer strings — `ci.use(RT + ".Bytes")`, `new HashMap<>()`,
`.empty()`, `new ArrayList<>()` — inside `Element.of(...)`. A `TypeRef` here must
embed into the `Element` / statement model and resolve at render time, which touches
a larger surface and requires the element layer to carry and resolve `TypeRef`s.

The design's central tension: Surface A is needed by everything and is tractable;
Surface B is larger and invasive but yields a resolver that reaches every type
reference and gives a `TypeRef`-based `ci.use`. The two are both in scope so that
resolution is genuinely uniform and the old `ci.use` string contract is fully
replaced, not partially.

## Key Decisions

- **Both surfaces are in scope.** Surface A (declaration / type-body positions) and
  Surface B (expression-spliced positions in bodies) both hold `TypeRef`s and resolve
  at render time. The old `ci.use(rawFqn) -> String` contract is fully replaced,
  leaving no mixed string/`TypeRef` positions.

- **`TypeRef` is the single type-reference node.** It is the `sealed interface
  TypeRef` with three permutations from 2026-11-12-002: `Named` (a `package`/`short`
  /generic `TypeRef` list), a `Primitive` enum, and an `Array(TypeRef)`. No other
  type-reference shape coexists.

- **Resolution is lazy and single-pass-render.** A recursive tree-walk over each top-
  level `Type` collects the `TypeRef`s in every position — Surface A and B — and in
  the children of every `TypeRef`. From that aggregate the imports are deduplicated
  and grouped and a collision map (short name to set of distinct FQNs) is built; the
  render of each position then resolves its `TypeRef` against that map. The file is
  built once; the collision check is discovered from the collected set, not by
  re-emitting the file (as in today's two-pass `TypeCollector` design).

- **`Annotation.type` becomes a native `TypeRef` (forward-link to 2026-11-12-001).**
  When the annotation work introduces `Annotation`, its `type` field carries a
  `TypeRef` (a `Named`); the later string-to-`TypeRef` migration that would otherwise
  be needed after this design lands is therefore not needed. The `@Nullable` prefix
  (`@Nullable X field`) and the `@SuppressWarnings` / `@Override` / `@Deprecated`
  cases all carry `TypeRef`s consistently through the resolution.

- **Surface B is a lazy, escape-hatch-first `ExprPiece` model — the main design
  surface.** An `ExpressionStatement` is a `List<ExprPiece>` terminated by `;`, where
  `ExprPiece` is the head of a sealed hierarchy of renderable expression pieces. The
  **first implementation is escape-hatch-first**: a `Raw`/escape-hatch piece that holds
  today's raw string content (so present `Element.of(...)` statements, *including*
  invocations and other fine-grained syntax, map mechanically into it), alongside a
  single type-embedding piece that resolves a `TypeRef` (002) against the aggregate
  collision map at render time. The **only structured piece this effort commits to is
  the `TypeRef`/type-embedding one**; the finer per-syntax kinds (an `Invocation` /
  identifier / value-returning piece, and the per-statement *positional* grammar that
  constrains where each kind may sit) are a *future, aspirational* direction, not the
  committed target — it is unlikely to reach the granularity of modeling an invocation as
  a structured piece, and that is acceptable because the escape-hatch covers it.
  A builder-ergonomics layer wraps a raw `String` into the appropriate `ExprPiece`
  (today the escape-hatch kind; a specific structured kind when that kind lands), so
  callers stop reasoning about the sealed kind as each raw piece is later contracted
  into a structured one.

- **Migration is a three-phase parameterization, canonical — and it decouples the two
  hard problems.** The escape-hatch-first story kept *single-pass import resolution* (the
  real win: retiring the two-pass re-emit) entangled with *fully-structured expressions*
  (the big, least-valuable part, incl. the aspirational `Invocation` modeling). Building
  each landing as a **parameterized `Raw`** (a format-string template plus its `TypeRef`
  arguments) collapses the naive "raw string + side set" pair into a single structure, and
  separates the work into three independently-shippable phases:
  - **Phase A — parameterize.** At *every* `ci.use(x)` call — not just raw-string sites
    but wherever a type flows into a position — build the landing as a **parameterized
    `Raw`**: a format-string *template* plus an argument list whose entry is the
    corresponding `TypeRef` (or a literal), instead of inlining the resolved name into a
    flat string. A structured landing already holds the `TypeRef` directly; on a `Raw` it
    is an **arg entry**, not a separate side set. Output is unchanged: a `TypeRef` arg is
    resolved and substituted at render, reproducing today's bytes — so the carrier is the
    arg *itself*, nothing duplicated.
  - **Phase B — switch to the tree-walk render.** Production being universal, *every
    imported type already has a `TypeRef` by construction* (the complete-mirror invariant
    holds for free), so the renderer assembles the import set and collision map by one
    tree-walk that collects `TypeRef`s from structured positions **and** `Raw`
    argument lists, then formats each `Raw` by substituting resolved names for its
    `TypeRef` placeholders. The **two-pass re-emit is retired**.
  - **Phase C — rebuild the expressions.** Decompose each `Raw` template into a fully
    structured `List<ExprPiece>` — the type placeholder becomes a structured type
    embedding piece, the rest a structured piece per syntax — retiring the template
    itself. This phase — and the finer per-syntax kinds it enables — is the permanent,
    deferrable tail; **Phase B has already shipped the win**.
  Because the parameterized `Raw` resolves to today's bytes, fixtures stay GJF-identical
  at every step and each phase is independently shippable: A and B require no full
  expression rewrite, and C can be deferred indefinitely.

- **Implementation is deferred.** This doc fixes the contract; it does not perform the
  migration. Phases A–C are an implementation pass, planned separately (the ordering and
  the parameterization contract are fixed here).

## Requirements

### The type-reference model

**R1.** The AST's type positions carry a `TypeRef` (the sealed type from 2026-11-12-
002): `Named`, `Primitive`, or `Array`; no other shape coexists, and no type
position holds a pre-resolved short-name string.

**R2.** The `TypeRef` resolution returns a name to emit at a position — a short name
pre-finalize, a fully-qualified name only when the short name is shared by another
distinct `TypeRef` — and no `TypeRef` is resolved to a string while its node is
constructed.

### Surface A — declaration / type-body positions

**R3.** `Component.type`, `Method.returnType`, `Method.thrown`, and `Param` types
each hold a `TypeRef` (Surface A); `Component.type` and `Method.returnType /
thrown` are not pre-resolved short strings.

**R4.** The `Type.Record implementsFqns`, `Type.Class extendsFqns / implementsFqns`,
`Type.Enum implementsFqns`, and `Type.Interface extendsFqns / permitsFqns` header
lists hold `List<TypeRef>` (Surface A); appending a type in the builder takes a
`TypeRef`, not an FQN string.

**R5.** `Type.TypeRecord`, `EnumConstant`, and any other AST node that today carries
a type string likewise holds a `TypeRef`; there are no remaining string type
positions.

### Surface B — expression-spliced positions in bodies

**R6.** Every site that today splices a `ci.use(rawFqn)` result into an
`Element.of(...)` string (Phase C's target) becomes an `ExpressionStatement`, a
`List<ExprPiece>` terminated by `;`. The escape-hatch `Raw` `ExprPiece` is a
**format string + a placeholder-argument list**, not a flat, fully-built string: a
`Raw` holds a template with placeholders and a list of args, each a **literal**
(rendered as-is) or a **`TypeRef`** (002), resolved to short/FQN at render and
substituted into the template. So the `TypeRef`'s carriage is uniform, not a separate
side set — on a `Raw` it is an **arg entry**, on a structured position it is the rendered
value — and the `ci.use -> TypeRef` production generalizes to *every* `ci.use` call site.
The aspirational extension: an arg that is neither literal nor `TypeRef` is itself a
nested `ExprPiece`, so a `Raw`'s arg list is really a `List<ExprPiece>` — the bridge
toward full structuring.

**R7.** `ExprPiece` is the head of a sealed hierarchy of renderable expression pieces;
the first implementation commits to only two kinds — an escape-hatch `Raw` piece (today's
raw string content, incl. invocations) and the type-embedding piece that resolves a
`TypeRef`. Phasing is the three-phase parameterization of the key decision: **Phase A**
universal `ci.use -> TypeRef` augmentation, each landing built as a parameterized `Raw`
(an arg entry on a `Raw`, the rendered value on a structured position) — output unchanged,
complete mirror by construction; **Phase B** switch to the single tree-walk render and
retire the two-pass re-emit; **Phase C** rebuild the templates into structured
`ExprPiece`s (finer per-syntax kinds aspirational and out of scope). A builder takes a
raw `String` and wraps it in the appropriate `ExprPiece`, so today's `Element.of(...)`
sites migrate mechanically and only type positions become structured. Phasing is
escape-hatch-first throughout: structured kinds layer in *later, not up front*.

### Resolution contract

**R8.** Each AST node that can hold a `TypeRef` exposes a recursive collection method
that yields the `TypeRef`s in its own positions and in the positions of its children;
the top-level type's collection method assembles a single aggregate set for the file
covering both surfaces and **every carrier** — structured type positions *and* the
`TypeRef` entries in a `Raw`'s placeholder-argument list alike — and the set is
**complete by construction** because `ci.use` is the universal chokepoint: every
imported type has a `TypeRef`.

**R9.** Resolution to short / FQN at render time applies the rules that 2026-11-12-002
preserves: `java.lang.*` and in-package `Named` short with no import; every other
`Named` imported; a shared short name rendered fully-qualified and imported nowhere;
`Primitive` and `Array` rendered by name / as element-plus-`[]`. The file is built
once; the collision discovery is a structural pass over the collected set, not a
second full emit.

**R10.** The present `ci.use(rawFqn)` string contract is fully replaced by a
`TypeRef`-producing API on the equivalent mappers; `useFqn(ref)` (the present always-
FQN marker for cross-compilation-unit shadowing such as a `recordbuf.
protobuf_java`-opted reference type) is preserved as a `TypeRef`/marker, not a
string.

### Coupling

**R11.** `Annotation.type` (introduced by 2026-11-12-001) carries a `TypeRef`
(`Named`) natively, so the later string-to-`TypeRef` migration is not needed.

## Scope Boundaries

**Deferred for later.**

- **The actual three phases of the `ci.use -> TypeRef` migration (R6–R7).** Phase A
  (parameterize each landing into a parameterized `Raw`) and Phase B (switch to the
  single tree-walk render, retire the two-pass re-emit) ship the import-resolution win
  *without* any expression rewrite; Phase C (rebuild the templates into structured
  `ExprPiece`s, finer kinds aspirational) is the deferrable tail. The design fixes the
  ordering and contract; it does not perform the rewrite.
- **The finer `ExprPiece` kinds and the per-statement positional grammar.** The
  escape-hatch `Raw` piece stays the dominant piece; layering in structured kinds
  (an `Invocation` / identifier / value-returning piece and the positional grammar
  that constrains where each may sit) is a *future, aspirational* direction that is
  out of scope for this effort and not expected to reach `Invocation`-level
  granularity.
- **The data structures of the collision map** and the per-node collection method
  composition (one method per node vs a visitor) are implementation details an
  `/ce-plan` pass will settle.
- **Wiring the new `TypeRef`-based `ci.use`** into the `CodeGenerator` and the
  formatter.

**Outside this effort's identity.**

- **Changing the import grouping or collision rules.** Those carry over from `2026-
  11-12-002` unchanged.
- **The `com.google.protobuf.compiler` protocol shim.** Never touched.
- **Re-baselining the `fixtures/`** — the generated output stays byte-for-byte.

## Implementation Units

Each unit builds on the previous one. Phase A units carry output stability: fixtures stay
GJF-identical at every step because the `TypeRef` resolves to today's short/FQN at every
site. Phase B retires the two-pass emit and requires no change to any mapper after Phase A
is complete.

### U1 — `TypeRef` sealed interface (model)

- **Status:** not started
- **R:** R1, R2
- **File:** `codegen/src/main/java/bijnagte/recordbuf/codegen/source/TypeRef.java` (new)
- **Change:**
  - `sealed interface TypeRef permits Named, Primitive, Array`
  - `record Named(String pkg, String shortName, List<TypeRef> generics)` — factory:
    `TypeRef.of(String fqn)` parses a FQN string into `(pkg, short, [])` (the current
    `ci.use()` string-parsing logic, lifted into a factory for ergonomic construction from
    a raw FQN; `TypeRef.of("java.util.Map")` → `Named("java.util","Map",[])`) and
    `TypeRef.of(String fqn, TypeRef... typeArgs)` for the generic case (`List<T>` →
    `Named("java.util","Map",[T])`). An empty `generics` list means a bare type.
  - `enum Primitive { VOID, INT, LONG, FLOAT, DOUBLE, SHORT, CHAR, BOOLEAN, BYTE }` —
    renders as the lowercase name (`void`, `int`, etc.)
  - `record Array(TypeRef element)` — factory `TypeRef.array(Named)` / `TypeRef.array(Primitive)`;
    renders as `element + "[]"`.
  - `String resolved(String declaringPackage)` — the resolution method: for `Named` returns
    short name when not collided / `java.lang.*` / in-package, or FQN when collided; for
    `Primitive` / `Array` returns the bare form. This method is **pure** — it takes the
    declaring package and a collision map, not a mutable `TypeCollector`.
  - `String fqn()` — returns the canonical fully-qualified string (for the `useFqn` /
    `InteropSourceMapping` always-FQN path).
  - `String shortName()` — returns just the short class name (for collision detection).
- **Test:** `codegen/src/test/java/bijnagte/recordbuf/codegen/source/TypeRefTest.java` (new)
  - `Primitive` renders as lowercase (`INT` → `"int"`)
  - `Array` renders as `element + "[]"`
  - `Named.of(...)` round-trips through `fqn()`
  - `resolved(...)` with empty collision map returns short for `java.lang.*` / in-package, FQN otherwise
  - `resolved(...)` with a collision map returns FQN for collided short names
- **Depends on:** nothing
- **Output stability:** no output change — new files only; no mapper or renderer touches `TypeRef` yet.

### U2 — `ExprPiece` sealed interface + `RawExpr` + `ExprStatement` migration

- **Status:** not started
- **R:** R6, R7
- **Files:**
  - `codegen/src/main/java/bijnagte/recordbuf/codegen/source/ExprPiece.java` (new)
  - `codegen/src/main/java/bijnagte/recordbuf/codegen/source/RawExpr.java` (new)
  - `codegen/src/main/java/bijnagte/recordbuf/codegen/source/ExprStatement.java` (modify)
- **Change:**
  - `sealed interface ExprPiece permits RawExpr` — the sealed head. Phase C extends this.
  - `record RawExpr(String template, List<ExprPiece> args)` — a parameterized template.
    - A **placeholder token** renders the corresponding arg by index. The implementation
      decides the exact token and escape; the contract is one `ExprPiece` per arg and a
      render-time substitution that produces identical output to today's resolved short string.
    - Factory: `RawExpr.of(String template, ExprPiece... args)` (ergonomic, matches the
      builder style).
  - `ExprStatement` migrates from `record ExprStatement(String expr)` to
    `record ExprStatement(List<ExprPiece> pieces)`. The `ExprStatement.of(String raw)`
    factory wraps a bare raw string as a single-element list containing
    `RawExpr.of(raw, [])` so all existing mappers that call `ExprStatement.of("expr;")` work
    unchanged. The renderer renders each `ExprPiece` then joins them; a `RawExpr` template
    with zero args renders the template verbatim — byte-compatible with today.
  - `RawExpr` render: substitute each placeholder position with the result of `arg.resolved()`
    (for `TypeRef` args) or `arg.render()` (for the escape-hatch / literal future kinds).
    An `ExprPiece` needs a `String render()` default; `RawExpr`'s default is its substitution;
    a `TypeEmbedding` (added later) delegates to `resolved()`.
- **Test:**
  - `codegen/src/test/java/bijnagte/recordbuf/codegen/source/RawExprTest.java` (new):
    - `RawExpr.of("new %d<>()", TypeRef.of("java.util.HashMap"))` with the placeholder token
      substitutes the short name at render time
    - `RawExpr.of("literal", [])` renders as `"literal;"` — identical to current
      `ExprStatement.of("literal")`
  - `codegen/src/test/java/bijnagte/recordbuf/codegen/source/ExprStatementRenderingTest.java`
    (new or updated from existing test file): `ExprStatement.of("x")` renders `x;` unchanged.
- **Depends on:** U1 (`TypeRef` must exist before `RawExpr` can use it as an arg type)
- **Output stability:** no output change — `RawExpr.of(anyString, [])` renders the template
  verbatim, identical to today's `ExprStatement(String)`.

### U3 — Surface A: type positions become `TypeRef`

- **Status:** not started
- **R:** R3, R4, R5
- **Files to change (all in `:recordbuf-codegen`):**
  - `Component.java` — `String type` → `TypeRef type`; `Component.builder()` and
    `ComponentBuilder.of(typeRef, name)` factory; keep `Component.named(String, String)` for
    backward compat by parsing the string via `TypeRef.of(fqn)` internally (or add a
    `Component.of(TypeRef, String)` and remove the `named()` overload — implementation
    decision; the contract is the public `TypeRef` field).
  - `Method.java` — `String returnType` → `TypeRef returnType`; `List<String> thrown` →
    `List<TypeRef> thrown`; add `Method.of(returnType, name)` and `thrown(TypeRef...)` /
    `thrown(List<TypeRef>)` builder methods.
  - `Param.java` — `String type` → `TypeRef type`; `Param.of(String, String, ...) ` becomes
    `Param.of(TypeRef, String, ...)` with a `fromFqn(String fqn, String name, ...)` factory for
    string-calling code paths.
  - `LocalVar.java` — `String type` → `TypeRef type`.
  - `Type.java` — all `List<String> implementsFqns`, `extendsFqns`, `permitsFqns` in
    `Record`, `Class`, `Enum`, `Interface` variants → `List<TypeRef>`; add `implementsType`
    / `extendsType` / `permitsType` builder methods; keep `implementsFqn(String)` factory that
    parses via `TypeRef.of(fqn)`.
  - `SourceRenderer.java`:
    - `Pass.collect(Type t)` no longer takes string FQNs; it walks `TypeRef`s from each
      variant to register them with `TypeCollector`. Since `TypeCollector.use(String)`
      still exists, this phase only changes how the renderer *collects*: it calls
      `ci.use(typeRef.fqn())` where it previously called `ci.use(stringFqn)` — behavior is
      unchanged for collection, but the source of the string is now the `TypeRef`'s FQN.
    - `renderMethod` / `renderField` / `joinResolved` / `renderParams` resolve via
      `ci.use(typeRef.fqn())` where they previously called `ci.use(stringFqn)` — same logic,
      just sourced from `TypeRef.fqn()` instead of a stored string. No output change yet.
- **Tests to update:**
  - `JavaSourceTest.java` — update `Component`, `Method`, `Type.*` construction to build `TypeRef`
    fields (or use the `fromFqn` factory). Output assertions unchanged.
  - `SourceAstClosednessTest.java` — add a check that no `Type.*` FQN list type parameter is
    `String` (sealed-hierarchy test that verifies the type parameters are `TypeRef` not `String`).
- **Depends on:** U1 (`TypeRef`); U2 is parallel (no dependency on each other)
- **Output stability:** unchanged. `ci.use(typeRef.fqn())` produces the same short/FQN as
  `ci.use(fqn)`. The migration is purely a data-shape change — no behavior change.

### U4 — `Annotation.type` becomes `TypeRef` (R11)

- **Status:** not started
- **R:** R11
- **File:** `Annotation.java` (modify)
- **Change:**
  - `record Annotation(TypeRef type, List<Arg> args)` — `String type` → `TypeRef type`.
  - Constants: `DEPRECATED = new Annotation(TypeRef.of("java.lang", "Deprecated", []), [])`,
    `OVERRIDE = new Annotation(TypeRef.of("java.lang", "Override", []), [])`,
    `NULLABLE = new Annotation(TypeRef.of("org.jspecify.annotations", "Nullable", []), [])`.
  - `Annotation.of(String fqn, Arg... args)` — parses the FQN to `TypeRef.of(fqn)`.
  - `SourceRenderer.renderAnnotations()` — change `ci.use(a.type())` to
    `ci.use(a.type().fqn())` (no behavioral change yet; U6 handles the tree-walk).
- **Test:** `AnnotationTest.java` (new or updated)
  - `DEPRECATED.type().fqn()` returns `"java.lang.Deprecated"`
  - `DEPRECATED.type().shortName()` returns `"Deprecated"`
  - `NULLABLE.type().fqn()` returns `"org.jspecify.annotations.Nullable"`
- **Depends on:** U1 (`TypeRef`)
- **Output stability:** unchanged — `ci.use(typeRef.fqn())` returns the same short/FQN as
  `ci.use(fqn)`.

### U5 — Mappers build `TypeRef` and `RawExpr` at all `ci.use` sites

- **Status:** not started
- **R:** R6, R7
- **Files to change (all in `:recordbuf-generator`):**
  - `CodecSourceMapping.java` — for every `ci.use(x)` call, the result is either:
    - inlined into a Surface A position (`Component`, `Method`, `Param`, `LocalVar`, `Type` header)
      → pass the `TypeRef` directly (from U3); no `ci.use(x)` call needed at mapper level
    - spliced into an `ExprStatement.of(...)` / body string → build a `RawExpr` with the
      `TypeRef` as the corresponding `ExprPiece` arg: e.g.,
      `ExprStatement.of(List.of(RawExpr.of("new %d<>()", typeRef)))` where `typeRef =
      TypeRef.of("java.util.HashMap")`.
  - `EnumSourceMapping.java` — same migration: `ci.use("java.util.Map")` → `TypeRef.of("java.util.Map")`
    embedded in a `RawExpr`.
  - `InteropSourceMapping.java` — `ci.useFqn(ref)` calls are replaced with a
    `TypeRef` + always-FQN flag (or a `Named` with a `true` marker) so the FQN is emitted
    directly with no import — `useFqn` becomes a `TypeRef.useFqn` factory (a `Named` that
    carries a no-import flag, or a distinct `TypeRef.UseFqn` subtype — implementation
    decision). Keep the `useFqn` semantics on the TypeRef (no import, emit FQN).
  - `AvroConverterSourceMapping.java` — same migration.
  - `CodeGenerator.java` — update any `ci.use` call in the `main` generation flow (the
    `rtUse` lambda / `RT` references).
- **Tests to update:**
  - `CodeGeneratorTest.java` (fixture round-trip) — no output change; assertions stay.
  - `CodecSourceMappingTest.java` / any mapper-level tests — update to assert `TypeRef` fields
    are built correctly when they exist; otherwise rely on fixture round-trip.
- **Depends on:** U1, U2, U3, U4
- **Output stability:** unchanged — `RawExpr` substitutes resolved names at render; every
  fixture GJF-clean output is identical to today. This is the invariant that allows Phase A
  to ship independently of Phase B.

### U6 — Tree-walk `TypeRef` collector (Phase B)

- **Status:** not started
- **R:** R8, R9, R10
- **Files to change (all in `:recordbuf-codegen`):**
  - `SourceRenderer.java`:
    - Remove `file.ci().finalizeResolution()` from `render()`. Instead, do a single-pass
      collect-then-render: collect all `TypeRef`s from the tree (including `RawExpr` args)
      before the render loop, build the collision map from that aggregate, then render in one
      pass. The `TypeCollector` two-pass emit is retired.
    - Add a `collectTypeRefs(Type t, Set<TypeRef> out)` walk that recurses:
      - Surface A positions: `Component.type`, `Method.returnType`, `Method.thrown`,
        `Param.type`, `LocalVar.type`, `Type.*` header FQN lists
      - `RawExpr` args: `RawExpr.args()` — each `ExprPiece` that is a `TypeRef` contributes
        that `TypeRef` (a `TypeRef` arg is not an `ExprPiece`; it is a `RawExpr` arg, and
        needs its own collection path in `collectTypeRefs`)
      - `Annotation.type` — `Annotation.type` is a `TypeRef`, collected at every position
    - The collector aggregates `TypeRef`s into a single set + builds a `shortName → Set<FQN>`
      collision map. Each `TypeRef` render call (in `renderRecord`, `renderMethod`, etc.)
      looks up the resolved name via `typeRef.declaringPackage(declPkg, collisionMap)`.
  - `JavaFile.java` — update the Javadoc to document the single-pass contract:
    "render() does a single tree-walk collect then render; no two-pass emit."
- **Tests to update:**
  - `TypeCollectorTest.java` — keep the import-resolution rules (the behavior stays the same,
    only the mechanism changes). Rename to `ImportResolutionTest` or add coverage for
    the tree-walk path directly.
  - New: `TypeRefResolvingTest.java` — a record with `TypeRef` components and a colliding
    `TypeRef` in a `RawExpr` arg renders FQN at both sites, no import line for the collision.
- **Depends on:** U1–U5 (all phases A units must be complete first)
- **Output stability:** unchanged — the resolution rules from `002` are preserved exactly
  (short + import; FQN on collision; `java.lang.*` / in-package short, unimported). The
  two-pass vs. single-pass distinction has no observable output difference.

### U7 — (Deferred, not planned) Phase C: decompose `RawExpr` into structured `ExprPiece`s

- **R:** R7, future
- **Scope:** Decompose each `RawExpr` template into a structured `List<ExprPiece>` — the
  type-embedding piece (a structured `TypeEmbedding` `ExprPiece` that resolves a `TypeRef` at
  render), and one structured piece per remaining syntax token (an `Identifier`, `Invocation`,
  etc.). This is the permanent, deferrable tail; **Phase B has already shipped the win**
  (single-pass import resolution; two-pass retired). Phase C is not required for any product
  goal — it improves code-structurality but not output.
- **Depends on:** U6
- **Note:** If Phase C is started, it should be its own ce-plan; the placeholder token and
  `ExprPiece` sub-kinds (beyond `RawExpr`) need their own design pass.

## Acceptance Examples

**AE1.** A record component `Component` built via `Component.Builder` holds its
`type` as a `TypeRef` (a `Named`) rather than a short string; at render time the
`TypeRef` resolves to its short name (or FQN on collision). *Covers R1, R3, R9.*

**AE2.** A method built via `Method.Builder` has its `returnType` and, where
present, its `thrown` types as `TypeRef`s (Surface A); the renderer resolves
them. *Covers R3, R9.*

**AE3.** A statement that today splices `ci.use("java.util.HashMap")` into an
`Element.of(...)`, such as a `new HashMap<>()` initializer, embeds a `TypeRef` that
the renderer resolves at render time — the `Element` / statement model carries the
embeddable reference, not the short string. *Covers R6, R7, R9.*

**AE4.** An `Annotation.type` (from 2026-11-12-001) is constructed as a `TypeRef`
(`Named`) at build time, with no later migration to `TypeRef` — the string-to-
`TypeRef` migration that would have been needed after the 2026-11-12-001 refactor is
absent. *Covers R11.*

**AE5.** A shared short name between two distinct `TypeRef`s (one in a declaration
header, one spliced into an expression body) renders fully-qualified at every site
across both Surface A and B uniformly, and no import is contributed for it.
*Covers R8, R9, R10.*

**AE6.** The `fixtures/` `.rec` outputs stay byte-for-byte after this design
realizes: the GJF-clean gate and the round-trip tests pass, and the generated
output changes only in the sense that the AST internally holds `TypeRef`s instead
of pre-resolved strings, with no observable output change. *Covers R9, R10.*

## Outstanding Questions

**Deferred to planning / a later pass.**

- **The granularity *within* each migration phase.** The ordering A -> B -> C is
  fixed by design; the per-phase granularity (which `ci.use` site / mapper / surface each
  phase handles first) is a `ce-plan` decision not fixed here. Phase B can ship before C
  begins, and C (and its finer kinds) may never be started.
- **The `ExprPiece` / `ExpressionStatement` realization** is resolved-by-design:
  `ExprPiece` is the sealed head, `ExpressionStatement` is a `List<ExprPiece>`
  terminated by `;`, escape-hatch-first (R7) with a builder that wraps raw strings (R7).
  Open for the implementation pass: the **placeholder token** and its escape — it must not
  collide with Java syntax that can appear in a template body (`<>`, `{}`, `%` are all
  risks; a distinctive sentinel such as a Unicode guillemet pair, a `MessageFormat`-style
  `{{n}}`, or a `printf` scheme, with an escape for a literal occurrence — and how that
  token is parsed at render; the **nested-`ExprPiece` arg form** (an arg that is itself a
  piece, the bridge to full structuring) when it lands; and the migration granularity for
  contracting a `Raw` into a structured piece over time.
- **The per-node collection method shape** (a `TypeRefs()` method on each node
  kind, a shared visitor, or something else) — the implementation decides.
- **The collision-map data structure and the `useFqn` marker's exact form on a
  `TypeRef`** — an `errorprone` / design call the implementation pass can settle.
- **How the single-pass render replaces today's two-pass `finalizeResolution()`** — the
  *direction* is decided (Phase B retires the two-pass re-emit in favour of a single
  structural collect-then-render); the mechanics (a per-file collect pass, the aggregate
  set's data structure, render-time resolution) are an implementation detail.

## Sources

- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Type.java` — the source
  AST type declarations; their `List<String>` `implementsFqns` / `extendsFqns` /
  `permitsFqns` / `throws` positions (Surface A) and the body `Element.of(...)`
  statement lists (Surface B entry point).
- `codegen/.../source/Component.java`, `Method.java`, `EnumConstant.java` — the
  `Component.type`, `Method.returnType`, `Method.thrown`, and `Param` type
  positions (Surface A), all pre-resolved short strings today.
- `generator/.../writer/CodecSourceMapping.java`, `EnumSourceMapping.java`,
  `InteropSourceMapping.java`, `AvroConverterSourceMapping.java` — the
  `ci.use(rawFqn)` call sites that splice resolved short names into `Element.of(...)`
  statement strings (Surface B), the main rework target.
- `docs/plans/2026-11-12-002-refactor-type-ref-imports-plan.md` — the `TypeRef`
  model and its single-render, collision-aware resolution contract this design
  realizes in the AST (this doc is that plan's R10 — "make the AST store `TypeRef`s"
  — fleshed out across both surfaces).
- `docs/plans/2026-11-12-001-refactor-ast-annotations-plan.md` — the `Annotation`
  model whose `Annotation.type` becomes a native `TypeRef` under this design,
  foreclosing the string-to-`TypeRef` migration that plan otherwise anticipated.
