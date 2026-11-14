---
title: Structured TypeRef Import Resolution - Plan
type: refactor
date: 2026-11-12
topic: type-ref-imports
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
execution: code
status: realized
updated: 2026-11-13
---

## Status Update (2026-11-13)

This requirements-only plan was written before structural-AST realization landed. The
requirements below have since been partially implemented across a series of subsequent
plans; the plan's own shape (design-only contract, deferred realization) is now
temporally stale. Per-requirement status:

| Req | Status | Where realized |
|-----|--------|----------------|
| R1  sealed `TypeRef` with `Named`/`Primitive`/`Array` | **Done** | `codegen/.../source/TypeRef.java` |
| R2  `Named` is a record `(pkg, shortName, List<TypeRef> generics)` | **Done** | same file |
| R3  `Primitive` enum incl. `void` | **Done** | same file (`Primitive.VOID` exists) |
| R4  `Array(TypeRef element)` | **Done** | `record Array(TypeRef element)` in `TypeRef.java` |
| R5  Nested generics recurse | **Done** | `Named.generics()` is a `List<TypeRef>` |
| R6  Tree-walk via per-node `collect(ci)` | **Done** | `SourceRenderer.collectBody` recurses; `collect(TypeCollector)` is the contract |
| R7  Aggregate import + collision set | **Done** | `TypeCollector.finalizeResolution()` |
| R8  Single render resolves short vs FQN | **Done** | `TypeRef.render(ResolutionContext)` + `SourceRenderer.render*` |
| R9  Forced-FQN case, `useFqn` | **Done — settled as a default method `TypeRef.useFqn(collector)`** on the sealed interface; **not** a dedicated field/variant/flag (the earlier "open ergonomic choice" was resolved by the planner to a method on the interface with a default). See `TypeRef.useFqn` |
| R10  AST's type positions hold `TypeRef`s | **Done** — done at the AST, mapper, and writer level. The last two `void emit*` helpers in `RecordSourceMapping` (`emitOneof`/`emitBuilder`) were migrated to returning `OneofEmission`/`BuilderEmission` in `007`. No `void emit*` remain in `writer/`. |
| R11  Retire the `TypeImporter`/`TypeCollector` duplication | **Done** — `TypeImports` is deleted; `TypeCollector.use(TypeRef)` is the single structured resolver; the legacy `use(String)` overload and its `stripGenericArguments` helper were retired in `007` (the internal FQN-registration side-effect lives in a private `registerType` helper on the `use(TypeRef)` path). |
| R12  `Annotation.type` is the first `TypeRef` the AST holds | **Done** via 2026-11-12-001 |

**Downstream plans that realize this contract:**

- `2026-11-12-003-refactor-ast-type-positions-plan.md` — the structural-AST prerequisite
  that made R10 real across declaration and expression-spliced positions.
- `2026-11-13-004-feature-rawexpr-positional-templating-plan.md` — positional `@N`
  templating via `RawExpr.of(template, TypeRef...)`; the tree-walk collect contract
  (R6) is what makes positional `TypeRef`s self-register on the `TypeCollector`.
- `2026-11-13-005-feature-call-site-postemplating-plan.md` — migrated `If`/`ElseIf`/
  `For`/`While`/`Switch` `condition`/`iterable`/`subject` to `RawExpr`; **status
  `implementation-ready` label is stale — this is done in code**.
- `2026-11-13-006-refactor-mapper-type-ref-migration-plan.md` — `*SourceMapping` helpers
  migrated to return `TypeRef`/`Element`; two helpers (`emitOneof`/`emitBuilder` in
  `RecordSourceMapping`) were the only `void *` remaining and were retired in `007`.
- `2026-11-13-007-refactor-finish-type-ref-migration-plan.md` — closed out the
  R10 tail (the two `void emit*` helpers) and the R11 second phase (the legacy
  `use(String)` overload + `stripGenericArguments`); **status `complete`**.

---


## Goal Capsule

**Objective.** Replace the string-based, two-pass import resolver with a structured,
lazily-evaluated one. Design a `sealed interface TypeRef` as the AST's type
representation, with a single-render resolution contract: collect every type
reference by recursing the node tree, build the import set and collision map from
that aggregate, then resolve each reference to a short name or a fully-qualified
name at render time. This doc captures the node design, the resolution contract,
and the migration roadmap; it is **design-only** because making the AST store
`TypeRef`s instead of resolved strings is a later structural-AST pass.

**Product authority.** The same import-resolution behavior the plugin's
`TypeCollectorTest` pins: `java.lang.*` and in-package types resolve to their short
name with no import line; every other referenced type is imported; a short name
shared by two or more distinct types is emitted in fully-qualified form and imported
nowhere. The generated files stays Google-Java-Format clean and the fixtures
round-trip.

**Open blockers / dependencies.** None inside this design's own scope, but the design
cannot be *realized* until the AST's type positions hold `TypeRef`s instead of
resolved strings — the structural-AST prerequisite, deferred to a later pass. The
annotation work (2026-11-12-001) is the natural first site to hold a `TypeRef` once
that prerequisite lands.

---

## Product Contract

## Summary

Introduce a `sealed interface TypeRef` with exactly three implementations — `Named`
(a record of `package` + short name + a list of generic `TypeRef`s), a `Primitive`
enum (the Java primitive types), and an `Array(TypeRef element)` wrapper — as the
AST's type representation. Resolution becomes lazy and single-render: a recursive
tree-walk collects every `TypeRef` in a node's type positions, the imports and
collision map are built from that aggregate, and each reference resolves to a short
name or FQN during a single render, replacing the string-parsing `use(String)` and
the emit-into-throwaway-then-re-emit two passes today. Making the AST store
`TypeRef`s (instead of resolved strings baked in by the mappers) is deferred to a
later pass; the `TypeImporter`/`TypeCollector` duplication is folded into one
resolver.

## Problem Frame

Today two parallel classes — `TypeCollector` (in `:recordbuf-codegen`) and
`TypeImports` (in `:recordbuf-generator`) — do the same job by an identical design.
Each takes a raw type *string* that may carry generics (`java.util.List<java.lang.
Integer>`), parses it by string surgery (`indexOf('<')` for the generic boundary,
`lastIndexOf('.')` for package vs short name), and records the reference. A short-name
collision depends on the whole referral set, which is only known once a file is walked
end to end, so the file is **emitted twice**: once into a throwaway collector to
gather the set, then after `finalizeResolution()`, emitted **again** with collision-
aware names. The type decision is therefore baked into the AST as resolved *strings*
while the `*Mapper` classes build nodes. This is fragile (string slicing on generics),
costs a double emit, lives in two places, and makes types impossible to inspect or
reuse as data.

## Key Decisions

- **One sealed hierarchy, three variants.** `sealed interface TypeRef` permits
  `Named`, `Primitive`, and `Array`, and no other. Primitives and arrays model the
  "no package, no import" cases directly rather than being inferred as empty-package
  `Named`s; only the `Named` variant participates in import/short-vs-FQN resolution.

- **`Named` is the import-bearing record.** It carries `package`, a short name, and a
  list of generic `TypeRef`s so nested generics recurse (`List<Integer>`,
  `Map<String,Integer>`) without string slicing.

- **Lazy, single-render resolution.** A node's type positions contribute their
  `TypeRef`s via a recursive collection (a type-reference method on each node that
  recurses its children). The imports and collision map are built once from that top
  aggregate; resolution to short or FQN happens as each `TypeRef` is met in a single
  render. The whole AST is built once — collision is discovered from the collected
  set, not from a second full emit.

- **Resolution rules carry over unchanged.** `java.lang.*` and in-package `Named`s
  resolve to their short name with no import; every other `Named` is imported; a short
  name shared by two or more distinct `Named`s renders fully-qualified at every site
  and is imported nowhere. The today-forced "always FQN, never import" case
  (`useFqn`, used for cross-compilation-unit shadowing such as a `recordbuf.
  protobuf_java`-opted reference type) is preserved as a marker on `Named`.

- **Design-only; the structural-AST prerequisite is deferred.** Capturing the
  `TypeRef` node and the resolution contract is this doc's deliverable. Actually
  storing `TypeRef`s in the AST's type positions and wiring the `*Mapper` classes to
  produce them instead of resolved strings is a later pass — the "more structured AST"
  the design anticipates.

- **The annotation work is the coupling point.** The 2026-11-12-001 annotation
  brainstorm introduces `Annotation` carrying a `type`; that `type` is the natural
  first `Named`/`TypeRef` to hold once the AST stores type references. The two
  efforts are cross-referenced.

## Requirements

### The TypeRef model

**R1.** The AST gains a `sealed interface TypeRef` permitting exactly three types:
`Named`, `Primitive`, and `Array`; no other implementation may be added without
updating the permits clause.

**R2.** `Named` is a record holding a package, a short name, and a list of generic
`TypeRef`s. A `Named` with no generics is a bare type reference.

**R3.** `Primitive` is an enum of the Java primitive types (including `void` where
used as a return type); it contributes no import.

**R4.** `Array` wraps a single element `TypeRef` and renders as the element's
rendering followed by `[]`; it contributes no import of its own (its element, if a
`Named`, contributes its own import).

**R5.** Generic arguments of a `Named` are themselves `TypeRef`s, so nested generics
resolve recursively.

### Resolution contract

**R6.** Each AST node that can hold a type exposes a way to yield the `TypeRef`s it
contains, recursing into child nodes that also hold type references.

**R7.** Starting from a file's top node, that recursion assembles a single aggregate
set of `TypeRef`s; from it the imports are deduplicated and grouped, and the
collision map (short name → set of distinct fully-qualified names) is computed.

**R8.** During a single render, each `TypeRef` resolves to a short name or a fully-
qualified name against the aggregate and collision map — never by re-emitting the
file — using the rules carried over today: `java.lang.*` and in-package `Named`s
short with no import; every other `Named` imported; a `Named` whose short name is
shared by two or more distinct `Named`s rendered fully-qualified and imported
nowhere; `Primitive` and `Array` rendered by name / as their element plus `[]`.

**R9.** A "fully-qualified, never imported" reference (the present `useFqn`
behavior, for cross-compilation-unit shadowing) is represented as a marker on
`Named` and preserved.

### Migration and roadmap
**R10.** **Done** (2026-11-13 via `007`). The AST's type positions — record
component types, field types, method return and parameter types, and nested
generic arguments — hold `TypeRef`s; the last two `void emit*` helpers in
`RecordSourceMapping` were retired. The 2026-11-12-003 / `004` / `005` / `006`
plans realized this contract across declaration and expression-spliced positions.

**R11.** **Done** (2026-11-13 via `007`). The `TypeImporter`/`TypeImports`
duplicate is retired; `TypeCollector.use(TypeRef)` is the single structured
resolver (R6–R9). The legacy `use(String)` overload and its
`stripGenericArguments` helper were deleted; the FQN-registration side-effect is
now a private `registerType` on the `use(TypeRef)` path.

**R12.** **Done** via 2026-11-12-001. The first `TypeRef` the AST holds is
`Annotation.type`.

## Scope Boundaries

**Realized.**
- The structural-AST prerequisite that made R10 real (the `*Mapper` classes
  produce `TypeRef`s and the AST stores them instead of resolved strings) is
done across `003` / `004` / `005` / `006` / `007`.

**Outside this effort's identity.**

- **Changing the import grouping or collision rules** — those carry over unchanged
  (java-first grouping, a single blank between java and other groups, alphabetical
  within a group, collided names omitted).
- **Adding type kinds beyond `Named`/`Primitive`/`Array`** without a concrete need.

## Acceptance Examples

**AE1.** A component typed `List<Integer>` is a `Named(java.util, List, [Named(
java.lang, Integer, [])])`. Its short rendering is `List<Integer>`; the
`java.lang.Integer` argument resolves short and contributes no import, and `java.
util.List` contributes one import. *Covers R2, R5, R8.*

**AE2.** `byte[]` is an `Array(Primitive.BYTE)`, renders as `byte[]`, and contributes
no import. *Covers R3, R4, R8.*

**AE3.** Two distinct `Named`s sharing a short name (e.g. `a.Foo` and `b.Foo`) render
as their fully-qualified forms at every site and contribute no import, so the
consumer's compiler disambiguates them. *Covers R7, R8.*

**AE4.** A nested generic such as `Map<String, Integer>` resolves recursively: both
generic arguments are `TypeRef`s, each resolving short (java.lang) or imported as the
rule dictates. *Covers R2, R5, R8.*

**AE5.** Resolution is single-pass in the sense that the file's AST is built once;
collision is discovered from the aggregated `TypeRef` set, not by emitting the file a
second time. *Covers R6, R7, R8.*

**AE6.** A cross-compilation-unit reference that would collide under a short import
(the present `useFqn` case) renders fully-qualified and contributes no import.
*Covers R9.*

## Outstanding Questions

**Mostly resolved; remaining items are historical context.**

- **Realization of R10.** **Resolved.** The `*Mapper` classes now produce `TypeRef`
  and the AST stores them (done across `003` / `004` / `005` / `006` / `007`).
  `RecordSourceMapping.emitOneof`/`emitBuilder` were the last `void emit*` helpers
  and are now returning `OneofEmission`/`BuilderEmission`.
- **The collision map's storage and the order of import groups** are inherited from
  the present resolver's behavior and pinned by `TypeCollectorTest`; the planner may
  confirm the exact data structures.
- **The forced-FQN marker on `Named`** (R9): a dedicated field, a distinct
  `Named`-variant, or a flag is an open ergonomic choice the planner can settle.
- **The per-node `typeRefs()` contract's exact signature** — one method per node kind
  vs a shared visitor — is an implementation detail the planner can settle, as
  long as it composes to the top aggregate (R6, R7).

## Sources

- `codegen/src/main/java/bijnagte/recordbuf/codegen/TypeCollector.java` — the current
  canonical resolver (clean-room, from the javapoet-writer plan): string-parsing
  `use(String)`, the two-pass `finalizeResolution()` design, `useFqn`, the grouped
  import lines, and the collision map. `codegen/src/main/java/bijnagte/recordbuf/
  codegen/TypeCollectorTest.java` pins its behavior.
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/TypeImports.java` —
  **deleted** (the duplicate `TypeCollector` consolidation from R11).
- `docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md` — the plan that
  introduced `TypeCollector` in place of the `javapoet`-style writer; context for
  why resolution is string-based and phased today.
- `docs/plans/2026-11-02-001-feat-fqn-to-imports-plan.md` — the always-FQN → imported
  feature that established the "short + import, FQN on collision" rules this effort
  preserves.
- `docs/plans/2026-11-12-001-refactor-ast-annotations-plan.md` — the annotation
  brainstorm whose `Annotation.type` is the coupling point and the natural first
  `TypeRef` to hold in the AST once R10 lands.
- `docs/plans/2026-11-12-003-refactor-ast-type-positions-plan.md` — the AST-structuring
  prerequisite that *realizes* this plan's R10 ("the AST holds `TypeRef`s") across both
  declaration and expression-spliced positions; the surface-by-surface migration that
  makes this doc's lazy resolution concrete.
