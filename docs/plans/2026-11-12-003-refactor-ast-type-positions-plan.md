---
title: Structured AST Type Positions - Plan
type: refactor
date: 2026-11-12
topic: ast-type-positions
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
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
short or FQN form. The result is uniform type handling across **both surfaces** —
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

- **Implementation is deferred.** This doc fixes the contract; it does not perform the
  migration. The actual rewire of the mappers, the embed API for the element
  language, and the per-node collection method composition are an implementation pass,
  planned separately.

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

**R6.** Bodies and initializers — every site that today splices a `ci.use(rawFqn)`
result into an `Element.of(...)` string — become an `ExpressionStatement`, a
`List<ExprPiece>` terminated by `;`. Non-type content stays an escape-hatch `Raw`
`ExprPiece`; the type position is a type-embedding `ExprPiece` that carries a
`TypeRef` (002) the renderer resolves at render time.

**R7.** `ExprPiece` is the head of a sealed hierarchy of renderable expression
pieces; the first implementation commits to only two kinds — an escape-hatch `Raw`
piece (today's raw string content, incl. invocations) and the type-embedding piece
that resolves a `TypeRef`. Phasing is **escape-hatch-first**: a body starts
overwhelmingly as escape-hatch content with a `TypeRef`-embedding piece at each type
position, and more structured `ExprPiece` kinds are layered in site by site *later,
not up front*. A builder takes a raw `String` and wraps it in the appropriate
`ExprPiece`, so today's `Element.of("...")` sites migrate mechanically and only type
positions become structured. The finer per-syntax kinds (notably an `Invocation`-level
piece) and the per-statement positional grammar are a *later, aspirational* direction
— out of scope for this effort (see Scope Boundaries).

### Resolution contract

**R8.** Each AST node that can hold a `TypeRef` exposes a recursive collection method
that yields the `TypeRef`s in its own positions and in the positions of its children;
the top-level type's collection method assembles a single aggregate set for the file
covering both Surface A and B.

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

- **The actual migration of every `ci.use(...)` / `List<String>` type position.**
  This is the implementation of R3–R7 — rewriting the `*Mapper` call sites and the
  `ExprPiece` / `ExpressionStatement` layer; the design fixes the contract but does
  not perform the rewrite.
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

- **The migration order and granularity for the actual rewrite** (by type position,
  by mapper, by surface) — a `ce-plan` decision not fixed here.
- **The `ExprPiece` / `ExpressionStatement` realization** is resolved-by-design:
  `ExprPiece` is the sealed head, `ExpressionStatement` is a `List<ExprPiece>`
  terminated by `;`, escape-hatch-first (R7) with a builder that wraps raw strings
  (R7). Open for the implementation pass: the *exact* escape-hatch `Raw` piece shape,
  how a `TypeRef`-embedding piece composes with the `Raw` content it resolves into, and
  the migration granularity for contracting an escape-hatch piece into a structured one
  over time.
- **The per-node collection method shape** (a `TypeRefs()` method on each node
  kind, a shared visitor, or something else) — the implementation decides.
- **The collision-map data structure and the `useFqn` marker's exact form on a
  `TypeRef`** — an `errorprone` / design call the implementation pass can settle.
- **Whether the two-pass `finalizeResolution()` pattern of `TypeCollector` is
  retained** or fully replaced by the structural collect-then-render design — an
  architecture choice the implementation plans.

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
