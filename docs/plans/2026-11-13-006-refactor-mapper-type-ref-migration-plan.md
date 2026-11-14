---
id: "2026-11-13-006"
title: "refactor: migrate mapper helpers to return TypeRef / Element AST nodes"
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
type: refactor
status: in_progress
created: 2026-11-13
updated: 2026-11-13
---

# Refactor mapper helpers to return TypeRef / Element AST nodes — Plan

## Goal Capsule

- **Objective**: Eliminate the double round-trip (`TypeRef` → `String` → `TypeRef`) in the codegen
  pipeline by having type-string helpers in the `*SourceMapping` classes return `TypeRef` directly,
  and `void emit*` methods return `Element` or `List<Element>` instead of mutating a passed-in list.
  Also introduce static constant `TypeRef`s for the boxed primitive types so the three duplicate
  `boxedScalarType` / `boxedScalarComponent` / `boxedScalarShortName` switch statements in
  `RecordSourceMapping` and `CodecSourceMapping` collapse into a single `TypeRef.boxedFor(typeUrl)`
  factory.
- **Product authority**: `main` @ `ffda521`.
- **Open blockers**: none.

## Product Contract

1. **Goal.** Every type-string helper in the `*SourceMapping` package returns a `TypeRef`
   (or `Formattable`), not a `String`. Every `void emit*(...)` method returns the `Element`
   (or `List<Element>`) it builds instead of mutating a passed-in accumulator. All call sites
   use the `TypeRef`-accepting overloads of `Component` (`Component.type(ref, name)`) and `Param`
   (`Param.param(ref, name)`), not the `String` FQN overloads. After migration no
   `*SourceMapping` helper returns a bare type string.
2. **Users.** The generator's own build (`./gradlew build`) — 282 existing tests must all pass
   unchanged. No new behavior. The fixture codegen output must be byte-identical to what is
   generated today (all 13 `.proto` files produce the same `.java` as before except for any
   import changes introduced by the `TypeRef` collection path).
3. **Scope in.**
   - Add static `TypeRef` constants to `TypeRef` itself: `INTEGER`, `LONG`, `FLOAT`, `DOUBLE`,
     `BOOLEAN`, `SHORT`, `BYTE`, `CHARACTER`, `STRING`, `OBJECT` — each a `Named` in `java.lang`.
   - Add `TypeRef.boxedFor(String typeUrl)` — a single factory that replaces
     `RecordSourceMapping.boxedScalarType`, `RecordSourceMapping.boxedScalarComponent`, and
     `CodecSourceMapping.boxedScalarShortName`; deletes those three methods.
   - `RecordSourceMapping.scalarComponentType` returns `TypeRef`; `elementComponentType` returns
     `TypeRef`; all call sites use `Component.type(ref, name)` or `Param.param(ref, name)`.
   - `RecordSourceMapping.defaultFor(Field f)` returns `Formattable` (or `RawExpr` / `Literal`
     as appropriate — the default is a literal expression, not a type).
   - `CodecSourceMapping.decodeLocalType(Field f)` returns `TypeRef`.
   - `CodecSourceMapping.emitEntryCodec`, `emitMapDecodeCase`, `emitMessageDecode`,
     `emitRepeatedScalar`, `emitScalar`, and all other `void emit*(...)` methods return the
     `Element` / `List<Element>` they build.
   - `CodecSourceMapping.emitEncodeCase` and `emitDecodeCase` (singular and WKT) return `Case`
     or `List<Case>`.
   - `WktSourceMapping.emitEntry`, `emitDecodeCase`, `emitEncode` return the `Element` they build.
   - `InteropSourceMapping.emitOneofFrom`, `emitOneofTo`, `fromLocal`, `toLocal`,
     `toStmt` return an `Element` (or `List<Element>` where they build multiple bodies).
   - `AvroConverterSourceMapping` `void emit*(...)` methods return their built `Element`.
   - `EnumSourceMapping` / `AvroSchemaGenerator` — audit for helpers that return type strings
     and migrate if applicable.
   - `Component` / `Param` — audit call sites for residual `String` FQN usages that should use
     the `TypeRef` overloads.
4. **Scope out.**
   - No new `TypeRef` variants, no new `Formattable` implementations, no behavior changes.
   - No fixture proto changes; no new tests (existing 282 must pass unchanged).
   - `InteropSourceMapping` oneof `from`/`to` complex multi-line `Element.of("return new ... (")`
     fragments — deferred; those are structural `Fragment` bodies, not type positions.
   - `RecordSourceMapping.Component` initializer expressions (the `= new HashMap<>()` style
     initializers) — deferred; those are expression strings, not type positions.
5. **Decisions.** (Record each resolved decision below as the plan or U1 lands.)
   - D1: Boxed primitive constants live **in `TypeRef` itself** as `public static final TypeRef`.
   - D2: `TypeRef.boxedFor(String)` is a single factory that replaces the three duplicate
     `boxedScalar*` switch statements; deletes those three methods.
   - D3: Helpers that build **expression** strings (e.g. `fromExpr`, `mapKeyFrom`, `defaultFor`)
     return `Formattable` (or `RawExpr`); helpers that build **type** strings return `TypeRef`;
     `void emit*` methods return `Element` / `List<Element>` / `Case`. Each helper's return-type
     choice is dictated by the semantic of its output.
6. **Constraints.**
   - Generated fixture output must remain valid Java and byte-identical in semantic content.
   - `./gradlew build` (compile + spotless + checkstyle + test) must stay green after each unit.
   - No changes to the `codegen` module's public API surface — only internal helper signatures
     in the `generator` module's `*SourceMapping` classes change.
7. **Done when.**
   - `./gradlew build` green; `./gradlew :recordbuf-fixtures:compileJava` green.
   - `grep -rn 'private static String.*TypeRef\|String scalarComponentType\|String elementComponentType\|String decodeLocalType\|String boxed'
     generator/src/main/java/bijnagte/recordbuf/plugin/writer/` returns no results.
   - `grep -rn 'void emit' generator/src/main/java/bijnagte/recordbuf/plugin/writer/` returns
     no results (all `emit*` methods are non-`void` now).
8. **Outstanding Questions.** Resolved 2026-11-13:
   - ~~Q1~~ Resolved: `defaultFor` / `fromExpr` → `RawExpr.of("0")` / `RawExpr.of(\"x.getName()\")` — no new `Literal` node needed.
   - ~~Q2~~ Resolved: single `Element` per `emit*`; caller composes the `List`. Methods that build single statements (e.g. `emitEncodeCase` → one `Case`) return one `Element`. Methods that build multiple (e.g. `emitBody` → N statements) return `List<Element>`. The split is explicit per-method: single-body emitters return `Element`, multi-body emitters return `List<Element>`. Callers use `.addAll()` for the list case, spread for the single case.
   - ~~Q3~~ Resolved: `TypeRef.boxedFor` is a simple switch that returns the shared constant — `switch(typeUrl) { case "int32","uint32" -> INTEGER; ... }`. No lookup table, simplest form of D1. Reference identity preserved; no per-call allocation.

## Task / Unit Breakdown

**U1 — `TypeRef` constants + `boxedFor` factory**
- Add `public static final TypeRef INTEGER/LONG/FLOAT/DOUBLE/BOOLEAN/SHORT/BYTE/CHARACTER/STRING/OBJECT`
  as `Named("java.lang", "<name>")` constants to `TypeRef` (or to an inner class `TypeRef.BuiltIn`
  if the namespace gets crowded).
- Add `public static TypeRef boxedFor(String typeUrl)` that dispatches to the right constant
  using the same switch as `boxedScalarType` / `boxedScalarShortName`.
- Delete the three duplicate methods: `RecordSourceMapping.boxedScalarType`,
  `RecordSourceMapping.boxedScalarComponent`, `CodecSourceMapping.boxedScalarShortName`.
- Update call sites in `RecordSourceMapping` and `CodecSourceMapping` to call
  `TypeRef.boxedFor(typeUrl)`.
- **Gate**: `./gradlew build` green.

**U2 — `scalarComponentType` / `elementComponentType` → `TypeRef`**
- Change `RecordSourceMapping.scalarComponentType(Field, TypeCollector)` to return `TypeRef`.
- Change `RecordSourceMapping.elementComponentType(Field, TypeCollector)` to return `TypeRef`.
- The `Map<K,V>` case: build `TypeRef.named("java.util", "Map", List.of(keyTypeRef, valueTypeRef))`
  (a `Named` with generic args) and return it.
- The `List<X>` case: build `TypeRef.named("java.util", "List", List.of(elementRef))`.
- Update all call sites that currently do `Component.named(scalarComponentType(f, ci), f.javaName())`
  to `Component.type(scalarComponentType(f, ci), f.javaName())`.
- Same for `Param.of(scalarComponentType(f, ci), name)` → `Param.param(scalarComponentType(f, ci), name)`.
- **Gate**: `./gradlew build` green.

**U3 — `decodeLocalType` → `TypeRef`**
- Change `CodecSourceMapping.decodeLocalType(Field)` to return `TypeRef`.
- For primitive scalar cases, return the `Primitive` enum constant (`TypeRef.Primitive.INT`, etc.).
- For boxed cases, use `TypeRef.boxedFor(f.typeUrl())`.
- For `Bytes`, return `TypeRef.of(RT + ".Bytes")`.
- For `WKT` elements, return the WKT `TypeRef` (via a new `WktSourceMapping.decodeLocalType`
  or direct `WellKnownTypes.resolve` lookup).
- Update the `LocalVar` call site to use `LocalVar.of(ref, name)` or the equivalent
  `TypeRef`-accepting factory.
- **Gate**: `./gradlew build` green.

**U4 — `void emit*(...)` → returning in `CodecSourceMapping`**
- Convert `emitEncodeCase`, `emitDecodeCase`, `emitMessageDecode`, `emitRepeatedScalar`,
  `emitScalar`, `emitEntryCodec`, `emitMapDecodeCase` to return `Element` / `Case` /
  `List<Element>` instead of mutating a passed-in list.
- Update callers to use the returned value.
- **Gate**: `./gradlew build` green.

**U5 — `void emit*(...)` → returning in `WktSourceMapping`, `InteropSourceMapping`, `AvroConverterSourceMapping`**
- Same pattern for `WktSourceMapping` and `InteropSourceMapping`.
- `AvroConverterSourceMapping` `emitBody`, `emitFromStmts`, `emitToStmts` → `List<Element>`.
- **Gate**: `./gradlew build` green.

**U6 — `defaultFor` / expression helpers → `Formattable`**
- `RecordSourceMapping.defaultFor(Field f)` → `Formattable` (returns `RawExpr.of("0")` etc.).
- `InteropSourceMapping.fromExpr(Field)` → `Formattable` or `RawExpr`.
- `InteropSourceMapping.mapKeyTo`, `mapValueTo`, etc. → `RawExpr` / `Formattable` where they
  carry expression content; keep `String` for pure name/FQN helpers (`recordSiblingFqn`, `refFqn`,
  `lastSegment`, `cap`).
- Update call sites to use the `Formattable`-accepting overloads of `Element.expr`,
  `LocalVar.init`, `If.condition`, etc.
- **Gate**: `./gradlew build` green.

**U7 — Dead-code sweep + final verification**
- `grep -rn 'private static String\|void emit' generator/src/main/java/bijnagte/recordbuf/plugin/writer/`
  and confirm no remaining type-string helpers or void emit methods.
- `grep -rn '\.named\(' generator/src/main/java/bijnagte/recordbuf/plugin/writer/ | grep -v 'Component.named\|Method.named\|EnumConstant.named\|CaseArm.named'`
  — confirm no residual `Component.named(String, String)` or `Param.of(String, String)` usages
  that should use the `TypeRef` overloads.
- `./gradlew :recordbuf-generator:installDist` + regenerate all 13 proto files; verify all
  compile clean; diff output against pre-migration output to confirm semantic byte-identity.
- `./gradlew build --rerun-tasks` green.
- Delete any now-dead helper methods.
- **Gate**: full build green; 13/13 proto files generate clean; fixture output semantically
  unchanged.
