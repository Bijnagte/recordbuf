---
title: Finish TypeRef Migration - Plan
type: refactor
date: 2026-11-13
topic: finish-typeref-migration
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
status: draft
---

## Goal Capsule

**Objective.** Close out 2026-11-12-002 R10 and R11's second phase. Two small,
self-contained items remain after the structural-AST realization landed:

1. **R10 tail** — `RecordSourceMapping.emitOneof` and `RecordSourceMapping.emitBuilder`,
   the only two `void emit*` methods left in the `*SourceMapping` classes, both mutating
   a passed-in `Type.Record.Builder`. Migrate both to returning methods (`void` →
   `Type.Record` / `List<Type>` etc.), per 006's north-star of zero `void emit*`.
2. **R11 second phase** — `TypeCollector.use(String)` is a legacy overload whose only
   remaining callers are `TypeCollectorTest` assertions. Migrate those to
   `use(TypeRef.of("..."))` and retire the overload.

**Product authority.** `main` @ `01bb1ef`. No user-facing behavior changes.

**Open blockers.** None.

---

## Product Contract

### Problem Frame

`RecordSourceMapping.emitOneof` and `emitBuilder` are the last two `void emit*`
helpers in the writer package. Their scope-out justification in 006 ("structural
Fragment bodies, not type positions") doesn't hold any longer — the positional
`RawExpr` system (004/005) and the returning-method pattern (006 U3/U4/U5) already
demonstrate that complex multi-statement bodies compose as returning
`List<Element>` or nested `Type`/`Type.Class`/`Type.Interface` builders. The
`void emit*` contract is a north star; the two helpers are the last deviation.

`TypeCollector.use(String)` is the legacy path from the two-pass string-based
resolver described in 002. Its `use(TypeRef)` overload (added when the
structural-AST realization landed) supersedes it; the string overload has no
main-source callers today, only `TypeCollectorTest` assertions. Retiring it
closes 002's R11 ("`TypeImporter` and `TypeCollector` duplication is replaced by
a single resolver implementing R6–R9; the duplicate is retired") — no more
string-parsing in the resolver.

### Key Decisions

- **`emitOneof` returns `void` → `Type.Record`** (or equivalent). The enclosing
  `record` parameter is already a `Type.Record.Builder`; the method builds a
  nested sealed interface and calls `record.nested(iface)`. To make it returning:
  return the nested `Type.Interface` (or the list of nested `Type` nodes it
  emits), and the caller adds them.
- **`emitBuilder` returns `void` → `Type.Class`**. The nested `Builder` class is
  a `Type.Class`; the method also builds the per-component setters and the
  `build()` terminal — all part of the same `Type.Class` body. Return the
  finished `Type.Class` and the caller nests it.
- **`TypeCollectorTest` → `use(TypeRef.of("..."))`**. The test suite's
  string-string-parsing assertions become `TypeRef`-based. `TypeRef.of("...")`
  is the one-shot factory; the collision and java.lang rules are now asserted
  against `TypeRef` nodes rather than FQN strings, which also matches how the
  production code uses the resolver.
- **Delete `TypeCollector.use(String)`** after the test migration. The
  internal `parseReference`/`resolve` helpers that only this overload used
  are also deleted unless they are still referenced by `use(TypeRef)`.

### Requirements

**R-tail-1.** `RecordSourceMapping.emitOneof` returns the nested type nodes it
builds (one `Type.Interface`) instead of mutating `record`. The caller
`recordOneofComponent` (or wherever `emitOneof` is called) adds them. Zero
`void emit*` remains in `RecordSourceMapping`.

**R-tail-2.** `RecordSourceMapping.emitBuilder` returns the nested `Type.Class`
(the `Builder` class) instead of mutating `record`. The caller nests it. Zero
`void emit*` remains.

**R-tail-3.** `TypeCollectorTest` assertions call `ci.use(TypeRef.of(fqn))` rather
than `ci.use("...")`. No production code changes required — `use(TypeRef)` already
exists.

**R-tail-4.** `TypeCollector.use(String)` is deleted. Its internal helpers
(`parseReference`, `resolve`, `finalizedState`) are deleted if unused; kept if
shared with `use(TypeRef)`.

### Scope Boundaries

**Deferred.** No changes to the positional `@N` system further; no further `void
emit*` helpers exist after this plan. The `WktSourceMapping` helpers still
accept `String out, String v` — these are expression fragments, not type
positions, and out of scope (consistent with 006's scope-out).

### Acceptance Examples

**AE1.** `grep -rn 'void emit' generator/src/main/java/bijnagte/recordbuf/plugin/writer/` returns
no matches. (Covers R-tail-1, R-tail-2.)

**AE2.** `grep -rn '\.use("[^"]*")' --include="*.java"` (excluding `build/`)
returns no matches. (Covers R-tail-3.)

**AE3.** `grep -n "use(String\|public.*use(.*String" codegen/.../TypeCollector.java` returns
no `use(String)` method. `TypeCollector` class compiles without `use(String)`.
(Covers R-tail-4.)

**AE4.** `./gradlew build` green; `./gradlew :recordbuf-fixtures:compileJava`
green. Fixture codegen output is byte-identical to pre-plan. (Covers R-tail-1..R-tail-4.)

### Outstanding Questions

None at planning time. If `use(String)` deletion turns out to require a
non-trivial refactor of internal helpers that are actually shared by `use(TypeRef)`,
the plan should surface that in U2 and re-decide whether to keep shared helpers
or fully migrate.

---

## Units

### U1 — `emitOneof` → returning method

**Scope.** `RecordSourceMapping.emitOneof(Type.Record.Builder record, Oneof oneof,
TypeCollector ci)` currently mutates `record`. Change signature to
`static Type.Interface emitOneof(Oneof oneof, TypeCollector ci)` (return the
sealed interface it builds), or `static List<Type> emitOneof` if it emits
multiple nodes. In the loop, the `record.component(...)` and `record.nested(iface)`
calls must either be moved to the caller (if it's a single `Type.Interface`
returned) or accumulated as `List<Type>`.

**Test scenarios.**
1. Regenerate all 13 `.proto` fixtures; generated output is byte-identical (or
   import-order-equivalent) to pre-plan output.
2. `Unit`-level: if a new test is warranted for the returning-method shape,
   mirror the shape used in `WktSourceMapping.emitEncode(Field, Entry, String,
   String, TypeCollector)` (which already returns `Element`).

### U2 — `emitBuilder` → returning method

**Scope.** `RecordSourceMapping.emitBuilder(Type.Record.Builder record, Message m,
TypeCollector ci, String ufFqn)` returns `void` and mutates `record`. Change to
return the nested `Type.Class` (the `Builder`). If the builder also emits the
static `builder()` factory method onto the enclosing record, that method should
be returned as part of a `List<Type>` alongside the nested `Type.Class`, or the
caller should add the `builder()` factory method directly.

**Test scenarios.**
1. Regenerated fixtures are byte-identical.
2. `Type.Class`/`Type.Record` builders have a fluent `.nested(...)` and
   `.method(...)` API already — no new builder features required.

### U3 — Retire `TypeCollector.use(String)`

**Scope.** Migrate `TypeCollectorTest` assertions from `ci.use("...")` to
`ci.use(TypeRef.of("..."))` (or the equivalent). Then delete
`TypeCollector.use(String)`, its `String` parsing helpers
(`parseReference`, `resolve`, `finalizedState` etc.) if unused. Verify
`use(TypeRef)` is unchanged.

**Test scenarios.**
1. `./gradlew :recordbuf-codegen:test` — `TypeCollectorTest` asserts the same
   collision / java.lang rules, now via `TypeRef.of(...)`.
2. `./gradlew build` — no compilation errors.
3. No production code in `*SourceMapping` calls `ci.use("...")` (grep returns
   zero results).
