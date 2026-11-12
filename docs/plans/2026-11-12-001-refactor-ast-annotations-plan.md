---
title: First-Class Annotations in the Codegen AST - Plan
type: refactor
date: 2026-11-12
topic: ast-annotations
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
execution: code
---

## Goal Capsule

**Objective.** Give the codegen AST a first-class annotation concept. The renderer
today expresses annotations three ad-hoc ways — a `deprecated()` boolean on six node
kinds, an `override()` boolean on methods, and a raw `List<String>` on classes/
interfaces, plus `@Nullable` woven into type strings. Replace all of them with a
single `Annotation` record (a type + a list of `Arg`s) and an `Arg` record, put one
uniform `List<Annotation>` on every node that can carry an annotation, and have
`SourceRenderer` emit each annotation through one shared path.

**Product authority.** The plugin's output contract: generated Java must compile,
round-trip, pass NullAway, and stay Google-Java-Format clean, with the fixtures
re-baselined so any diff a change introduces is visible.

**Open blockers.** None for this refactor's own scope. The import / type-resolution
mechanism is explicitly out of scope and is the subject of a separate future
`/TypeRef` brainstorm (see Outstanding Questions); this refactor uses the current
eager import resolution and defers no blocker on that account.

---

## Product Contract

## Summary

Introduce `Annotation` and `Arg` as codegen AST nodes and give every declaration node
a uniform `List<Annotation>`. Migrate the four annotations currently expressed
ad-hoc — `@Deprecated`, `@Override`, `@SuppressWarnings`, `@Nullable` — to instances
of the new model, rendered through one path. `@Nullable` leaves the type string and
joins its owning node's annotation list. The fixtures are re-baselined to the
result.

## Problem Frame

`@Deprecated`, `@Override`, `@SuppressWarnings`, and `@Nullable` are all Java
annotations, but the codegen AST models them four different ways. `deprecated()` is a
`boolean` repeated across `Type.Record`, `Type.Enum`, `Type.Interface`,
`Type.Class`, `Component`, and `EnumConstant`; `override()` is a `boolean` on
`Method`; `@SuppressWarnings` is a raw `List<String>` on classes and interfaces; and
`@Nullable` is spliced into type-expression strings by the type resolver and several
mappers. The renderer then special-cases each one, and the ordering is inconsistent —
a record renders `@Deprecated` after its javadoc while a class renders
`@SuppressWarnings` before it. Modeling annotations as data makes the renderer uniform
and removes the special cases.

## Key Decisions

- **Full unification.** All four annotation kinds become `Annotation` instances. The
  `deprecated()` booleans, the `Method.override()` boolean, and the raw
  `List<String>` annotation field are removed, not kept alongside the new model.

- **Uniform placement on every annotatable node.** Every node that can carry an
  annotation gets a `List<Annotation>` — records, classes, enums, interfaces,
  components, fields, enum constants, methods, parameters, and local variables. This
  includes `@Nullable` on parameters and local variables, where it today appears as a
  type-string prefix.

- **`Annotation` and `Arg` shapes.** `Annotation` is a record holding a resolvable
  type and a list of `Arg`s. It renders as `@Type` with **no parentheses when the arg
  list is empty**; otherwise `@Type(...)`. Common arg-less annotations
  (`@Deprecated`, `@Override`, `@Nullable`) are exposed as **static final instances**
  (`Annotation.DEPRECATED`, etc.); arg-bearing annotations like `@SuppressWarnings`
  are built through the generic constructor plus `Arg`. `Arg` holds an optional name
  and a set of value expressions: a single value renders as a literal
  (`@X("a")`), multiple values render as an array initializer (`@X({"a","b"})`), and
  a named arg renders `@X(name = ...)`.

- **Rendering order.** A node's annotations render on their own line(s), **before the
  node's javadoc**, uniformly for every node type, replacing the current inconsistency.
  Because the plugin runs Google-Java-Format, exact whitespace positioning is
  formatter-corrected; the renderer's responsibility is to place annotations in the
  AST, not to hand-tune layout.

- **Imports and type resolution are out of scope.** `Annotation.type` resolves through
  the current eager import mechanism (the type collector's `use(...)`), exactly as
  types resolve today. A lazy, whole-set import resolution via a new `TypeRef` node
  (see Outstanding Questions) is a separate future effort.

## Requirements

### Annotation model

**R1.** The AST gains an `Annotation` node carrying a resolvable type identifier and a
list of `Arg`s, with a builder initialized to a non-null type.

**R2.** The AST gains an `Arg` node with a single static factory
`Arg.of(@Nullable String name, String... values)`: a null or absent name means a
positional argument. Rendering: an empty arg list omits the parentheses; within a list,
a single value renders as a literal and multiple values render as an array initializer.
An arg renders its `name = value` (or `name = {values}`) form **only when the name is
present and is not the conventional single-element name `value`** — an arg whose name
is absent or is `value` renders positionally (no `name =` prefix); any other name
produces the `name = ...` form.

**R3.** The common arg-less annotations are exposed as static final instances on
`Annotation`.

### Migration of existing annotation handling

**R4.** Every node that can carry an annotation gains a `List<Annotation>`:
`Type.Record`, `Type.Enum`, `Type.Interface`, `Type.Class`, `Component`, fields,
`EnumConstant`, `Method`, parameters, and local variables. Each carries annotations
through the new model; a node with no annotations has an empty list.

**R5.** The `deprecated()` booleans on the record, class, interface, enum, component,
and enum-constant nodes are removed in favor of an `Annotation.DEPRECATED` instance in
the node's annotation list, and the `Method.override()` boolean is removed in favor of
an `Annotation.OVERRIDE` instance.

**R6.** The raw `List<String>` annotation field on classes and interfaces is replaced by
the shared `List<Annotation>`, built for `@SuppressWarnings` via the generic factory and
`Arg`.

**R7.** `@Nullable` is produced as an `Annotation.NULLABLE` instance on the owning node's
list instead of being spliced into a type-expression string; the type expression stays
bare. This covers components, fields, return types, parameters, and local variables.

### Rendering

**R8.** `SourceRenderer` emits a node's annotations through a single shared path — one
annotation per line — instead of per-annotation special cases, and renders them before
the node's javadoc.

**R9.** `Annotation.type` resolves through the current eager import mechanism, so
`@Nullable` imports as it does today and `@Deprecated`/`@Override`/`@SuppressWarnings`
require no import.

### Migration and verification gates

**R10.** The migration is verified by regenerating the fixtures and comparing against a
captured baseline; the permitted diffs are the annotation placement, form, and
ordering changes this refactor introduces (the re-baseline), and no other change.

**R11.** Build, Spotless, Checkstyle, and all unit tests pass, and the fixtures
remain Google-Java-Format clean after regeneration.

## Scope Boundaries

**Deferred for later.**

- **Imports / `TypeRef` lazy resolution** (a separate future brainstorm). Types are
  eagerly evaluated and resolved through the type collector inside the `*Mapper`
  classes today; a whole-set import pass that decides short-name vs FQN at render time
  (via a lazily-evaluated `TypeRef` node carrying the package, short type, and
  generics as separate fields) is a distinct effort that may have to wait for a more
  structured AST. This refactor uses the current eager resolution.
- **New annotation kinds beyond the four already emitted.** The model is general
  enough to add them; none are in scope here.
- **Any new structured AST-node work beyond giving nodes an annotation list.**

**Outside this refactor's identity.**

- Rewriting how types resolve, import, or are collected — the subject of the future
  `TypeRef` work.

## Acceptance Examples

**AE1.** An arg-less annotation renders without parentheses: `@Deprecated`, `@Override`,
and `@Nullable` each render as `@Deprecated`, `@Override`, `@Nullable`. *Covers R2, R3.*

**AE2.** `@SuppressWarnings({"Foo","Bar"})` renders with array syntax because its single
`Arg` carries two values; `@SuppressWarnings("Foo")` (one value) renders as a bare
literal inside the parentheses. An arg named `value` (or unnamed) renders positionally
(`@X("Foo")`) just like an unnamed one; an arg with a non-`value` name renders as
`@X(other = "Foo")` (or `@X(other = {...})`). *Covers R2, R6.*

**AE3.** A nullable record component such as `@Nullable Long i64` moves from the type
string to the component's annotation list; the component's type is `Long` and its
annotation list contains `Annotation.NULLABLE`. A nullable parameter and a nullable
local variable behave the same way. *Covers R4, R7.*

**AE4.** A declaration node with several annotations renders them in its list order,
each on its own line, before the node's javadoc. *Covers R8.*

**AE5.** Regenerating the fixtures after the migration produces output equal to a
captured baseline except for the intended annotation placement, form, and ordering
diffs; the re-baselined output is Google-Java-Format clean and the build, Spotless,
Checkstyle, and tests are green. *Covers R10, R11.*

## Outstanding Questions

**Deferred to planning.**

- **Imports / `TypeRef` are a separate brainstorm, now captured at `docs/plans/
  2026-11-12-002-refactor-type-ref-imports-plan.md`**, and design-only for now. This
  refactor uses the current eager import resolution and does not touch it. That doc
  covers a lazily-evaluated `TypeRef` node — built from the same content currently
  passed to the type collector's `use(...)`, structured as a sealed interface with
  `Named`, `Primitive`, and `Array` variants, with package and short type as distinct
  fields and generics as recursive `TypeRef`s — resolved to a short name or FQN
  against a whole aggregated import set assembled by recursing the node tree. Because
  types are eagerly evaluated in the `*Mapper` classes today, its realization needs a
  later more-structured AST pass; the `Annotation.type` this refactor introduces is
  the first `TypeRef` to hold. Positioning is corrected downstream by the formatter.

## Sources

- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/SourceRenderer.java` — the
  per-node renderers that special-case `@Deprecated` (records/classes/enums/interfaces/
  components/enum constants), `@Override` (`Method`), and the raw `List<String>`
  `@SuppressWarnings`.
- `codegen/.../source/Type.java`, `Component.java`, `EnumConstant.java`,
  `Method.java` — the nodes carrying `deprecated()`, `override()`, and the raw
  `List<String>` annotation field (records, classes, enums, interfaces, components,
  enum constants, methods).
- `generator/.../writer/TypeResolver.java` (`resolveNullable`) and
  `RecordSourceMapping.java`, `InteropSourceMapping.java` — the `@Nullable` type-string
  prefix producer sites (components, oneof components, oneof parameters, local
  variables).
- `generator/.../writer/WktSourceMapping.java` — the `@SuppressWarnings` producer.
- `docs/plans/2026-11-11-001-refactor-formatter-google-java-format-plan.md` — the
  Google-Java-Format integration this refactor relies on for positioning and the
  fixture re-baseline gate.
- `docs/plans/2026-11-12-002-refactor-type-ref-imports-plan.md` — the `TypeRef`
  import-resolution design this refactor feeds; that doc names `Annotation.type`
  (from here) as its first `TypeRef` to hold once the AST stores type references.
- `docs/plans/2026-11-12-003-refactor-ast-type-positions-plan.md` — the AST-structuring
  prerequisite that makes `Annotation.type` a native `TypeRef` across both declaration
  and expression-spliced positions, foreclosing the string-to-`TypeRef` migration this
  doc otherwise anticipated.
