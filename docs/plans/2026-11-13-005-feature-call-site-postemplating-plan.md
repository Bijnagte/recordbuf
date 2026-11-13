---
title: Call-Site Pos-Templating Migration - Plan
type: feature
date: 2026-11-13
topic: call-site-postemplating-migration
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
status: brainstorming
---

## Goal Capsule

**Objective.** Migrate all remaining Java `String`-concatenation call sites in the `*Mapping.java` files to the `@N` positional system we built in 004. By the end of this plan, zero lines in the writer mappings use `+ "..." ` or `"... + "` to build expression templates — every dynamic value is a positional `@N` arg, resolved at render time via `RawExpr.render(ctx)`.

**Product authority.** This is a pure refactor of internal call sites — no new user-facing behavior. Generated code must remain byte-identical before and after.

**Open blockers.**
- `If.condition(String)` and `ElseIf.condition(String)` have no positional-arg overload; 005 must add them.
- `RawExpr.of(template + f.javaName() + " ...", TypeRef.of(FQN))` patterns concatenate *non-type* values into the template string before `of()` — those must become additional `@N` args.
- A few `Element.of(...)` calls create `Fragment` nodes (not `ExprStatement`); migrating them requires deciding which are genuine fragments vs. expression-statement-shaped lines that should become `Element.expr(...)`.

---

## Product Contract

### Problem Statement

004 built the `@N` positional system — `RawExpr.of(template, Object... args)` with `TypeRef`/`String`/`Formattable` arg dispatch, `Element.expr(template, args)`, `LocalVar.Builder(type, name, initTemplate, args)` — and migrated type *resolution* (`ci.use(FQN)` → `TypeRef.of(FQN).collect(ci).render(ctx)`). But the *expression templates themselves* are still built by Java string concatenation:

```java
// Before — dynamic values concatenated into the template string:
RawExpr.of("var _" + f.javaName() + " = new @0<>()", TypeRef.of(HASH_MAP));
//                    ^^^^^^^^^^^^ non-type value spliced into template
//                             ^@0 TypeRef (already positional)

Element.of("var enc = " + encoderShort + ".start();");
//         no @N at all — pure string concat

If.condition(!" + def + ".equals(" + g + ")" + " && !".concat(g + " == null").body(...));
```

This means the `@N` positional system is only *half* used: type resolution is positional, but variable names, accessor strings, and condition expressions are still spliced in *before* `of()` is called. The benefit of positional args — collision detection, type-aware rendering, fail-fast out-of-bounds — only reaches the `TypeRef` slot.

### Decision: Add positional overloads to control-flow nodes

`If.condition(String)` and `ElseIf.condition(String)` must accept `(String template, Object... args)` like `Element.expr`. This is the one new API surface 005 introduces. The internal representation changes from `String condition` to `RawExpr condition` in `If`/`ElseIf` records, with a `collect(ci)` and `render(ctx)` that delegate to the underlying `RawExpr`.

### Decision: All `Element.of(...)` calls evaluated for `Element.expr(...)`

Each `Element.of(...)` call site is reviewed:
- **Expression-shaped** (e.g. `"var enc = " + X + ".start();"`) → `Element.expr("@0.start();", X)` — a proper `ExprStatement` node.
- **Fragment-shaped** (control-flow lines, comments, multi-line syntax the closed set hasn't modeled) → stay as `Element.of(...)` or be migrated to `If`/`While`/`For`/`Switch` idioms if a closed node exists.

### File order (by frequency)

| Unit | File | Concat Lines | Status |
|------|------|:---:|------|
| U1 | `If`/`ElseIf` positional overload | — | Prerequisite |
| U2 | `CodecSourceMapping` | 111 | Largest |
| U3 | `InteropSourceMapping` | 70 | |
| U4 | `WktSourceMapping` | 24 | |
| U5 | `RecordSourceMapping` | 24 | |
| U6 | `AvroConverterSourceMapping` | 20 | |
| U7 | `EnumSourceMapping` | 4 | Smallest |
| U8 | Build verification: spotless + full `./gradlew build` + `--recordbuf_out` smoke test | — | |

### Definition of Done

- `grep '" + "\|+ "' generator/src/main/java/bijnagte/recordbuf/plugin/writer/*.java` returns zero dynamic-concatenation lines (remaining `+ "` occurrences may be module-level constant FQN like `RT + ".Encoder"` — these are static string constants, not expression templates).
- `./gradlew build` is green (compile + test).
- `--recordbuf_out` from a managed protoc produces byte-identical generated output to pre-005 baseline.
- Spotless is clean after `./gradlew spotlessApply`.

### Outstanding Questions

1. **`if-condition-with-TypeRef`** — Some `If.condition(...)` expressions embed a `TypeRef` (e.g., a WKT wrap condition). Does the `@N` positional for `Type` args in `If.condition` require `collect(ci)` + `render(ctx)` like `If`'s `ExprStatement`, or can we resolve the type *at call time* (when `If.condition` is being built)? Recommendation: resolve at call-time since conditions are rarely collision-ambiguous, and `If.condition` is built before `ci` is finalized.

2. **`FQN-building constants** — `TypeRef.of(RT + ".Encoder")` and similar are static constants. Should these become module-level `private static final TypeRef` fields to avoid the `TypeRef.of()` call-per-use? Recommendation: yes for frequently-used ones (`Encoder`, `Decoder`, `Bytes`), no for one-off usages.

3. **`Element.of` vs `Element.expr` in `InteropSourceMapping`** — Many `InteropSourceMapping` call sites use `Element.of("return new " + rec + "(")` for multi-line constructors. These are `Fragment`-shaped (partial expressions). Should these become `Element.expr(RawExpr.of("return new @0(", rec))` or stay as Fragments (they're incomplete expressions)? Recommendation: stay as Fragments — they can't be a syntactically complete `ExprStatement`.

---

## Next Steps

**After brainstorm: run `ce-plan`.** 005 is a requirements-only brainstorm. Run `ce-plan` to produce the implementation-ready plan with unit-level task breakdown.
