---
title: RawExpr Positional Templating - Plan
type: feature
date: 2026-11-13
topic: rawexpr-positional-templating
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
status: partially-complete
---

## Goal Capsule

**Objective.** Replace the `%d` placeholder + string-splice + manual `ci.use()`
pattern with a **positional `@N` template** model on `RawExpr`, backed by a
`Formattable` interface that unifies render-time resolution and import-collection
into a single tree-walk. Migration of all 6 writer mapping classes from
`ci.use(FQN)` / string-splice to the new API.

**Product authority.** `docs/plans/2026-11-13-004-feature-rawexpr-positional-templating-plan.md`
(requirements-only brainstorm — D1–D6).

**Open blockers.** None — all design decisions are settled in the requirements doc.

---

## Requirements

| ID | Requirement |
|----|-------------|
| R1 | `Formattable` interface in `codegen/source` with `render(ResolutionContext ctx)` and `collect(TypeCollector ci)`. |
| R2 | `TypeRef` implements `Formattable`: `render(ctx)` delegates to `resolved(...)`; `collect(ci)` calls `ci.use(ref)`. |
| R3 | `RawExpr` updated: `@N` positional syntax, `Object...` args, `collect(ci)` recursively walks args, fail-fast `IllegalArgumentException` at construction when `@N` is out of bounds. |
| R4 | `LocalVar.init` becomes `Formattable`; `LocalVar.builder(type, name)` with `build(String initTemplate, Object... initArgs)` produces the `Formattable` init. |
| R5 | `ExprStatement.expr` becomes `Formattable`. `Element` factories: `expr(String template, Object... args)` and `local(TypeRef fqn, String name, String initTemplate, Object... initArgs)`. |
| R6 | `SourceRenderer.collectBody`: `LocalVar`/`ExprStatement` call `formattable.collect(ci)` instead of `ci.use(lv.type())` / `collectExpr(expr)`. `renderStatement`: `ExprStatement`/`LocalVar` render via `formattable.render(ctx)`. |
| R7 | All 6 writer mappings migrated from `ci.use(FQN)` string registration to `TypeRef` args in `RawExpr`. No `ci.use()` / `ci.useFqn()` calls remaining in writer mappings. |
| R8 | `ci.use(String)` and `ci.useFqn(String)` remain on `TypeCollector` but are no longer called by write mappings after this — they may still be used in `SourceRenderer` for header fields (`extendsFqns`, `implementsFqns`, etc.) that haven't been migrated yet. |
| R9 | `LocalVar` builder: `LocalVar.builder(TypeRef type, String name)` returns builder; builder supports `.annotation(Annotation)` and `.build(String initTemplate, Object... initArgs)`. |
| R10 | Integration test: `--recordbuf_out` round-trips a test proto through the full pipeline and passes GJF. |

---

## Non-Goals

- Migrating `SourceRenderer`'s type-header positions (`extendsFqns`, `implementsFqns`, `permitsFqns`, `throws`) from `TypeRef` to `Formattable.collect` — these are already `TypeRef` in the AST and are resolved at render; not affected by this plan.
- `Return.expr` and `Throw.expr` becoming `Formattable` — separate plan if needed.
- The formatter (GJF) or `ResolutionContext` wiring — already in place.
- `@RecordBuilder` on `Formattable` types — codegen module doesn't use it; hand-written builders only.

---

## Approach

The plan is sequenced as dependency-ordered units. Each unit includes a build
check (`./gradlew build --rerun-tasks` or `./gradlew test --rerun-tasks`) and
an optional `--recordbuf_out` smoke test.

### Unit 1 — `Formattable` interface + `TypeRef` implementation

**Files:**
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Formattable.java` (create)
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/TypeRef.java` (modify)
- `codegen/src/test/java/bijnagte/recordbuf/codegen/source/FormattableTest.java` (create)

**Work:**
- Create `Formattable` interface with `String render(ResolutionContext ctx)` and
  `void collect(TypeCollector ci)`.
- `TypeRef` implements `Formattable`. Add to `sealed interface TypeRef permits
  Named, Primitive, Array`.
- `Named.collect(ci)` calls `ci.use(this)` (register + resolve, no return needed).
- `Primitive.collect(ci)` is a no-op (primitives have no imports).
- `Array.collect(ci)` recurses into `element().collect(ci)`.
- `Named.render(ctx)` delegates to `resolved(ctx.declaringPackage(), ctx.collidedShortNames())`.
- Add tests: `TypeRef.Named.collect` registers an import; `TypeRef.Named.render`
  returns the short/FQN form; `TypeRef.Primitive.collect` is a no-op.
- Build check: `./gradlew :codegen:build`.

### Unit 2 — `RawExpr` new positional API

**Files:**
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/RawExpr.java` (modify)
- `codegen/src/test/java/bijnagte/recordbuf/codegen/source/RawExprTest.java` (modify)

**Work:**
- Change `RawExpr` to implement `Formattable`.
- Change `List<TypeRef>` to `List<Object>` args.
- Add `collect(TypeCollector ci)` — iterate args, `instanceof` dispatch:
  `TypeRef` → `ci.use(tr)`; `RawExpr` → `recurse collect`; `Formattable` →
  `.collect(ci)`; `String` → skip.
- Update `render(ResolutionContext ctx)`: parse `@N` (multi-digit supported),
  dispatch on `args[N]`; `@@` → literal `@`.
- Add fail-fast constructor check: at `RawExpr.of` construction, scan template
  for all `@N` positions and verify each index is in range of `args`. Throw
  `IllegalArgumentException("RawExpr: @N is out of bounds")` otherwise.
- Keep `RawExpr.of(String, TypeRef...)` as a convenience overload that delegates
  to `RawExpr.of(String, Object...)`.
- Update `RawExprTest`: add `atNpositional` test, multi-digit test, nested args
  test, `collect` test, fail-fast test.
- Build check: `./gradlew :codegen:build`.

### Unit 3 — `ExprStatement` + `Element` factory migration

**Files:**
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/ExprStatement.java`
  (modify)
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Element.java`
  (modify)
- `codegen/src/test/java/bijnagte/recordbuf/codegen/formatter/ExprStatementTest.java`
  (create)
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/*.java` (compile-fix
  all references to `Element.expr(RawExpr)` / `Element.local(...)` — mechanical)

**Work:**
- `ExprStatement`: change field type `RawExpr expr` to `Formattable expr`.
- `Element.expr(String)`: `return new ExprStatement(RawExpr.of(s))`.
- `Element.expr(String template, Object... args)`: `return new
  ExprStatement(RawExpr.of(template, args))`.
- `Element.local(TypeRef fqn, String name, String initTemplate, Object...
  initArgs)`: `return LocalVar.builder(fqn, name).build(initTemplate, initArgs)`.
- Keep `Element.local(TypeRef fqn, String name, String init)` (no args →
  `RawExpr.of(init)` with no args — trivial `Formattable`).
- `SourceRenderer.renderStatement`: `ExprStatement` case — `es.expr().render(ctx)`.
- `SourceRenderer.collectBody`: `LocalVar` case — `ci.use(lv.type());
  lv.init().collect(ci);`. `ExprStatement` case — `es.expr().collect(ci)`.
- Update `RawExprTest` and `FormatWiringTest` as needed.
- Build check: `./gradlew build --rerun-tasks`.

### Unit 4 — `LocalVar` builder

**Files:**
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/LocalVar.java`
  (modify)
- `codegen/src/test/java/bijnagte/recordbuf/codegen/source/LocalVarTest.java`
  (create)

**Work:**
- Change `LocalVar.init` field type: `String` → `Formattable`.
- Update compact constructor: `init == null` → `RawExpr.of("")` (empty init —
  bare `var x;`).
- Add `LocalVar.builder(TypeRef type, String name)` static factory returning
  `Builder`.
- `Builder` fields: `List<Annotation> annotations`, accumulated `.annotation(a)`.
- `Builder.build(String initTemplate, Object... initArgs)` → sets `init =
  RawExpr.of(initTemplate, initArgs)`, returns `LocalVar`.
- `SourceRenderer.renderStatement`: `LocalVar` case — render `init` via
  `lv.init().render(ctx)`.
- Build check: `./gradlew build --rerun-tasks`.

### Unit 5 — `WktSourceMapping` migration

**Files:**
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/WktSourceMapping.java`
  (modify)
- `generator/src/test/java/bijnagte/recordbuf/plugin/writer/WktSourceMappingTest.java`
  (modify if tests reference internal API — likely not)

**Work:**
- Migrate all `ci.use(...)` calls:
  - `ci.use("java.util.HashMap")` + `local(HASH_MAP, "_m", "new " + HASH_MAP +
    "<>()")` → `local(HASH_MAP, "_m", "new @0<>()", TypeRef.of(HASH_MAP))`.
  - `ci.use("java.util.ArrayList")` + `local(ARRAY_LIST, "_items", "new " +
    ARRAY_LIST + "<>()")` → `local(ARRAY_LIST, "_items", "new @0<>()",
    TypeRef.of(ARRAY_LIST))`.
  - `ci.use("com.google.protobuf.ByteString")` + `local(BYTE_STRING, "_bs", "new
    " + BYTE_STRING + "<>()")` → similar.
  - `ci.use(f.type().fqnFor())` + `local(f.type().fqnFor(), "msg", "new " +
    f.javaName() + ".Builder();")` → `local(f.typeRef(), "msg", "new @0.Builder();",
    TypeRef.of(f.javaName()))`.
- Remove `ci.useFqn(...)` calls (these are for `useFqn` — "always fully-qualified"
  types). Migrated to `TypeRef` args with `.collect(ci)` or left as-is via
  `ci.useFqn(TypeRef)` if the type must be emitted FQN (e.g.
  `com.google.protobuf.Timestamp` in a field expression). Note whether each
  `useFqn` case still needs the separate registration or if the `TypeRef` in
  the `RawExpr` handles it.
- Build check: `./gradlew :generator:test --rerun-tasks -Dtest=WktSourceMappingTest`.

### Unit 6 — `InteropSourceMapping` migration

**Files:**
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/InteropSourceMapping.java`
  (modify)

**Work:**
- Migrate the 8 `ci.register(TypeRef.of(...))` calls and `ci.useFqn(...)` calls
  to `TypeRef` args inside `RawExpr.of(...)`.
- Example: `ci.register(TypeRef.of(BYTE_STRING))` +
  `RawExpr.of("_b.addByteString((ByteString) @0)", read)` → keep
  `ci.register` OR move to the `RawExpr` arg list.
- The field-name + expression pattern: `name + ".add" + cap + rawExpr(...)`
  becomes `RawExpr.of("@0.add@1(@2...", javaName, capitalizedAdd, args...)`.
- Build check: `./gradlew :generator:test --rerun-tasks -Dtest=InteropSourceMappingTest`.

### Unit 7 — Remaining writer mappings (`EnumSourceMapping`, `AvroConverterSourceMapping`, `RecordSourceMapping`)

**Files:**
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/EnumSourceMapping.java`
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/AvroConverterSourceMapping.java`
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java`

**Work:**
- Migrate all remaining `ci.use(...)`, `ci.useFqn(...)`, `ci.register(TypeRef.of(...))`
  call sites to `TypeRef` args in `RawExpr.of(...)`.
- In `RecordSourceMapping`: the string-splice helpers
  (`elementComponentType`, `boxedScalarComponent`, etc.) return `String` —
  convert to returning `Formattable` or `RawExpr`, and update callers.
- In `AvroConverterSourceMapping`: the `ci.use("com.google.protobuf.Message")`
  style calls → `TypeRef.of(com.google.protobuf.Message)` as args.
- In `EnumSourceMapping`: the `LocalVar(typeRef(HASH_MAP), "_lookup", "new " +
  ci.use("java.util.HashMap") + "<>()")` → `local(typeRef(HASH_MAP), "_lookup",
  "new @0<>()", TypeRef.of(HASH_MAP))` (if builder available) or direct
  `RawExpr.of(...)` factory.
- Build check: `./gradlew :generator:test --rerun-tasks -Dtest=Enum, AvroConverter,
  Record`.

### Unit 8 — `SourceRenderer` cleanup

**Files:**
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/SourceRenderer.java`
  (modify)

**Work:**
- Remove `collectExpr(RawExpr node)` — replaced by `Formattable.collect(ci)`.
- `collectBody`:
  ```java
  case LocalVar lv -> {
    ci.use(lv.type());
    lv.init().collect(ci);
  }
  case ExprStatement es -> es.expr().collect(ci);
  ```
- `renderStatement` `ExprStatement` case:
  ```java
  case ExprStatement es -> out.append(es.expr().render(ci.resolutionContext())).append(";\n");
  ```
- `renderStatement` `LocalVar` case:
  ```java
  case LocalVar lv ->
      out.append(ci.use(lv.type())).append(' ').append(lv.name())
          .append(lv.init().render(ci.resolutionContext()))
          .append(lv.init() instanceof RawExpr re && re.template().isEmpty() ? ";" : ";\n");
  ```
  (Adjust rendering of `init` — previously `lv.init() == null` check; now
  `init` is always a `Formattable`, check if the rendered string is empty.)
- Build check: `./gradlew :codegen:build`.
- `--recordbuf_out` smoke test for a known-good `.proto`.

### Unit 9 — `TypeCollector` legacy method cleanup

**Files:**
- `codegen/src/main/java/bijnagte/recordbuf/codegen/TypeCollector.java`
  (review, possibly modify)

**Work:**
- `ci.use(String)` — still called by `SourceRenderer` for header fields
  (`extendsFqns`, `implementsFqns`) — keep.
- `ci.useFqn(String)` — check if any writer callers remain after U5–U7. If none,
  deprecate.
- `ci.register(TypeRef)` — check if any caller remains. If none, deprecate.
- Add `@Deprecated` annotations to any method that becomes unused but is kept for
  API stability.
- Build check: `./gradlew build --rerun-tasks`.

### Unit 10 — `--recordbuf_out` integration smoke test

**Files:** create or extend an integration test (existing `IntegrationTest` or
similar).

**Work:**
- Run a known proto through the full `--recordbuf_out` pipeline.
- Verify GJF passes on the output — byte-stable.
- Verify round-trip (encode→decode) works.
- Build check: `./gradlew test --rerun-tasks` (full suite).

---

## Dependencies

```
U1 (Formattable + TypeRef)
  |
  v
U2 (RawExpr new API)
  |
  +---U3 (ExprStatement + Element factory) ---> U5 (WktSourceMapping) ---> U9 (cleanup) ---> U10 (smoke)
  |
  +---U4 (LocalVar builder)
  |
  +---U6 (InteropSourceMapping)
  |
  +---U7 (Enum + Avro + Record)
  |
  +---U8 (SourceRenderer cleanup)
```

U1 → U2 → U3 → U4 is the core chain. U5–U7 are independent (can be parallel) but
each depends on U1–U4. U8 depends on U3–U7 (all `ci.use` sites migrated). U9 + U10
are final.

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| `SourceRenderer` `LocalVar` `init` null-check changes after `LocalVar.init` becomes `Formattable` | Generated code breaks | U4 handles the null-check; U8's `renderStatement` checks `init().render(ctx)` is empty rather than `init() == null`. |
| Fail-fast `@N` range check in `RawExpr.of` is too aggressive | Overly strict, forces refactoring | The check runs at construction; the only call sites are in the 5 writer mappings, all known. If the check fires, it means a bug — fail loudly. |
| `ci.use(String)` for header fields (`extendsFqns`, `implementsFqns`) still uses the raw FQN path | Mixed model — two ways to register | Accept as-is for now; a future plan can migrate header positions. R8 documents this. |
| `LocalVar.init` `Formattable` — the `render` output includes a trailing newline or not | Generated code has spurious newlines, GJF fails | Test in U4 and U10 — the `LocalVar` case must render `init` inline with the declaration, not on a new line. |

---

## Success Criteria

1. `TypeRef` implements `Formattable` — verified in U1 unit test.
2. `RawExpr.of(template, args...)` with `@N` placeholders and mixed `String`/`TypeRef` args — verified in U2 unit test.
3. No `ci.use(...)` / `ci.useFqn(...)` / `ci.register(...)` calls remain in any writer mapping after U7 — grep check.
4. `SourceRenderer.collectBody` uses `Formattable.collect(ci)` — verified by inspection.
5. `--recordbuf_out` produces byte-stable GJF-clean output — U10 integration test.
6. `./gradlew build --rerun-tasks` green (compile + test + spotless + checkstyle).

---

## Next Steps

1. Run `ce-doc-review` or commit this plan.
2. Execute U1–U10 sequentially or in parallel where dependencies allow.
3. After all units: run `ce-plan`'s mandatory completion contract (Phase 5.4 handoff menu).
