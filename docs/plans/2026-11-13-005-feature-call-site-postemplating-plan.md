---
title: Call-Site Pos-Templating Migration - Plan
type: feature
date: 2026-11-13
topic: call-site-postemplating-migration
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
status: implementation-ready
---

## Goal Capsule

**Objective.** Migrate all remaining Java `String`-concatenation in the writer mapping files to the `@N` positional system. By the end, zero lines in `*Mapping.java` use `+ "..." ` to build expression templates, conditions, or iterables; every dynamic value is a positional `@N` arg.

**Product authority.** Pure internal refactor — generated output must be byte-identical before and after. No new user-facing behavior.

**Open blockers.** None. The three brainstorm decisions are resolved below (see Assumptions).

**Product Contract preservation.** Product Contract unchanged.

---

## Assumptions

1. **`If`/`ElseIf` `condition` becomes `RawExpr`.** The record field changes from `String condition` to `RawExpr condition`; `If.condition(String)` delegates to `new Builder(RawExpr.of(s))`. `SourceRenderer.renderIf` calls `condition.render(ctx)` and `collectBody` calls `condition.collect(ci)`.

2. **`For.enhanced` iterable and `Switch.on` subject** also get positional overloads: `For.enhanced(String pattern, String iterTemplate, Object... iterArgs)` and `Switch.on(String subjectTemplate, Object... subjectArgs)`. `While.condition` has no write-path caller in the mapping files today but is included for API completeness. The iterable/subject fields change from `String` to `RawExpr`.

3. **`WktSourceMapping` `outExpr`** — the nested prefix (`out + ".writeByteArray(" + num + ", "`) becomes a single positional expression: `Element.expr("@0.writeByteArray(@1, @2.method(e));", out, num, wktRef)`. The intermediate `outExpr` string variable is deleted; the positional call builds it in one expression.

---

## File map

| File | Change scope |
|------|-------------|
| `codegen/.../source/If.java` | `condition`: `String` → `RawExpr`; add `condition(String, Object...)` overload |
| `codegen/.../source/ElseIf.java` | `condition`: `String` → `RawExpr` |
| `codegen/.../source/For.java` | `iterable`: `String` → `RawExpr`; add `enhanced(String, String, Object...)`; fix `collectBody` to collect from iterable |
| `codegen/.../source/While.java` | `condition`: `String` → `RawExpr`; add `condition(String, Object...)`; no current callers |
| `codegen/.../source/Switch.java` | `subject`: `String` → `RawExpr`; add `on(String, Object...)`; fix `collectBody` to collect from subject |
| `codegen/.../source/SourceRenderer.java` | `collectBody`: add `.collect(ci)` calls for `If.condition`, `For.iterable`, `Switch.subject`; `renderIf/renderFor/renderSwitch`: call `.render(ctx)` |
| `generator/.../writer/CodecSourceMapping.java` | Migrate all `If.condition(...)` and `For.enhanced(...)` string-concatenation; `Element.of(a+b)` → `Element.expr("@0@1", a, b)` |
| `generator/.../writer/InteropSourceMapping.java` | Same |
| `generator/.../writer/WktSourceMapping.java` | Migrate `outExpr` chain; `If.condition` positional; delete intermediate string variables |
| `generator/.../writer/RecordSourceMapping.java` | Migrate `Element.of(a+b)` to `Element.expr("@0@1", a, b)` |
| `generator/.../writer/AvroConverterSourceMapping.java` | Migrate `Element.of(a+b)` to `Element.expr("@0@1", a, b)` |
| `generator/.../writer/EnumSourceMapping.java` | Migrate remaining 4 concat lines |

**Test files:**
- `codegen/src/test/.../FormatterWiringTest.java` — verify byte-identical output for `If`/`For`/`Switch` positional nodes
- `codegen/src/test/.../source/SourceRendererTest.java` — add `If.condition(template, args)` render + collect test
- `codegen/src/test/.../source/RawExprTest.java` — verify `If`/`For`/`Switch` positional arg resolution

---

## Units

### U1 — Prerequisite: `If`/`ElseIf`/`For`/`While`/`Switch` positional overloads

**Scope.** Change `condition`/`iterable`/`subject` fields from `String` to `RawExpr` in the 5 control-flow records; add the positional factory overloads; update `SourceRenderer` (`collectBody` + `renderIf`/`renderFor`/`renderSwitch`); update `FormatterWiringTest` expectations.

**Test scenarios:**
1. `If.condition("@0 != null", "g")` renders `if (g != null) {…}` byte-identically to `If.condition("g != null")`.
2. `If.condition("!@0.isEmpty()", "g")` renders `if (!g.isEmpty()) {…}`.
3. `For.enhanced("var e", "r.@0()", "items")` renders `for (var e : r.items()) {…}`.
4. `Switch.on("w.@0(t)", "Tag")` renders `switch (w.Tag(t)) {`.
5. TypeRefs in condition/iterable args: `If.condition("!@0.equals(ZERO)", TypeRef.of("java.lang.Integer"))` — collect registers `Integer` for import, render resolves to `Integer`.
6. Build (`./gradlew build`) is green.
7. `FormatterWiringTest` passes.

---

### U2 — `CodecSourceMapping` (111 lines)

**Scope.** Migrate all 111 concat lines:
- `If.condition(getter + " != null")` → `If.condition("@0 != null", getter)`
- `If.condition("!" + getter + ".isEmpty()")` → `If.condition("!@0.isEmpty()", getter)`
- `For.enhanced("var _e", "v." + f.javaName() + "().entrySet()")` → `For.enhanced("var _e", "v.@0().entrySet()", f.javaName())`
- `Switch.on(wireShort + ".tagFieldNumber(tag)")` → `Switch.on("@0.tagFieldNumber(tag)", wireShort)`
- `Element.of("var enc = " + encoderShort + ".start();")` → `Element.expr("var enc = @0.start();", encoderShort)`
- `TypeRef.of(RT + ".Encoder")` → `private static final TypeRef ENCODER = TypeRef.of(...)`, used by name

**Test scenarios:**
1. `./gradlew build` is green.
2. `grep '" + "\|+ "' CodecSourceMapping.java` returns only static-constant FQN lines (`RT + ".Encoder"`).
3. `--recordbuf_out` produces byte-identical generated codec to pre-U2 baseline.

---

### U3 — `InteropSourceMapping` (70 lines)

**Scope.** Migrate 70 concat lines — `Element.of(a+b)`, `For.enhanced("var _e", "x.get" + cap + "List()")`, `If.condition(src + " != null")`, `Switch.on("r." + o.componentName() + "()")`.

**Test scenarios:**
1. `./gradlew build` is green.
2. `--recordbuf_out` with protobuf interop produces byte-identical output.
3. `grep '" + "\|+ "' InteropSourceMapping.java` returns only module-level constants.

---

### U4 — `WktSourceMapping` (24 lines)

**Scope.** Migrate `outExpr` chain + `If.condition` cases. The `outExpr` string variable is deleted; each positional call substitutes `out`, `num`, and the `wktRef` directly.

**Test scenarios:**
1. `./gradlew build` is green.
2. `--recordbuf_out` with `--recordbuf_wkt` flag produces byte-identical WKT codec output.
3. `grep '" + "\|+ "' WktSourceMapping.java` returns no dynamic-concatenation lines.

---

### U5 — `RecordSourceMapping` (24 lines)

**Scope.** Migrate `Element.of(a+b)` to `Element.expr("@0@1", a, b)`.

**Test scenarios:**
1. `./gradlew build` is green.
2. `--recordbuf_out` produces byte-identical record output.
3. `grep '" + "\|+ "' RecordSourceMapping.java` returns no dynamic-concatenation lines.

---

### U6 — `AvroConverterSourceMapping` (20 lines)

**Scope.** Migrate `Element.of(a+b)` to `Element.expr("@0@1", a, b)`.

**Test scenarios:**
1. `./gradlew build` is green.
2. `--recordbuf_out` with `--recordbuf_avro` produces byte-identical output.
3. `grep '" + "\|+ "' AvroConverterSourceMapping.java` returns no dynamic-concatenation lines.

---

### U7 — `EnumSourceMapping` (4 lines)

**Scope.** Migrate the 4 remaining concat lines.

**Test scenarios:**
1. `./gradlew build` is green.
2. `--recordbuf_out` produces byte-identical enum output.

---

### U8 — Verification round-trip

**Scope.** `rm -rf fixtures/build/generated/ && ./gradlew spotlessApply --rerun-tasks build --rerun-tasks && ./gradlew :recordbuf-generator:installDist && protoc --recordbuf_out=<tmp>/…` from managed protoc; diff generated output against pre-U2 baseline. Also `spotlessCheck` clean.

**Test scenarios:**
1. `./gradlew build --rerun-tasks` is green.
2. Generated output from managed protoc is byte-identical to captured baseline.
3. `spotlessCheck` passes; `spotlessApply` makes no changes.

---

## Risks

1. **Byte-identical output.** The `@N` positional system was verified in 004 for type resolution, but `If`/`For`/`Switch` conditions and iterables are a new use. The key risk: `RawExpr.of(template)` with `@N` that *also* embeds dynamic variable names (not just `TypeRef`s) — render must produce exactly the same output string. Mitigation: `U6` test for each file compares against a captured baseline before the migration.

2. **`SourceRenderer.collectBody` missing a collect call.** If `collectBody` for `If` doesn't call `condition.collect(ci)`, a `TypeRef`-bearing condition's import won't be registered. This was the bug pattern in 004. Mitigation: U1 test explicitly covers the `TypeRef`-in-condition case.

3. **`While` has no current caller — API completeness but no test coverage.** Acceptable; the U1 test covers `While.condition` via a unit test even without a write-path caller.

---

## Next Steps

**Start with U1** — it's the prerequisite for all subsequent units. `U1..U7` each commit independently after a green build. `U8` verifies the full round-trip.
