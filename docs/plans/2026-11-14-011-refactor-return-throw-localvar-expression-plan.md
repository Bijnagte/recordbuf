---
title: Return/Throw/LocalVar Expression Migration - Plan
type: refactor
date: 2026-11-14
topic: return-throw-localvar-expression
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
deepened: 2026-11-14
product_contract_source: ce-brainstorm
execution: code
---

# Return/Throw/LocalVar Expression Migration - Plan

## Goal Capsule

**Objective:** Retire the raw `return ` / `throw ` string prefix embedded in generated-source
expression construction, and the string-templated `LocalVar` initializer, across the codegen layer —
migrating to the structured `Return` / `Throw` / `LocalVar` node factories that take an expression (a
`RawExpr`, or a string template + args), so the `return`/`throw` keyword is produced by the node's
renderer rather than baked into a caller-supplied string. `LocalVar` follows the same pattern: its
expression-typed initializer is the mechanism that lets the writer collapse a "declare-then-assign"
pair into a single declaration-with-initializer. Generated output stays byte-identical.

**Product authority:** `main @ e035265`. This is a requirements-only slice of the documented "body-lift"
refactor series ([003](../plans/2026-11-13-003-refactor-generator-body-lift-plan.md) →
[004](../plans/2026-11-13-004-refactor-codegen-ast-closedness-plan.md) →
[008](../plans/2026-11-13-008-refactor-retire-component-initializer-string-plan.md)); 008 retired
`Component.initializer` String→`Formattable` and is the nearest precedent.

**Open blockers:** none. The factory shape and slot type are settled; the one genuine planning fork is
`WktSourceMapping:50` null-safety (see Outstanding Questions). The `var`/wildcard `LocalVar` sites are
deferred to a follow-on slice by decision, not blocked.

---

## Product Contract

### Summary

This refactor stops the writer layer from passing a string that begins with `"return "` (or
`"throw "`) into the generic source AST, and stops `LocalVar` from being initialized with a
string-templated expression. Instead, the `return`/`throw` keyword is emitted by the node's renderer,
and the expression is supplied through a structured factory. The shape mirrors the in-repo precedent
`Element.expr` — two overloads, a string-template form and an already-constructed `RawExpr` form —
extended with a bare `ret()` for `return;`. The `Throw` twin is migrated in the same slice even though
it has zero call sites today.

The `LocalVar` half is the same pattern one level up. `LocalVar` already renders
`<type> <name> = <init>;` and already carries a `Formattable` init, so its expression-typed setter
(`build(Formattable)` — a `RawExpr` is a `Formattable`, so it flows through that setter) is the
enabler; the string-templated seeds are migrated to `build(RawExpr.of(…))`. This slice migrates the
**expressible-now** subset — the 9 "declaration-with-assignment" sites whose types are real
`TypeRef`s; the 6 `var`/wildcard sites (5 `var _x = …` + 1 `List<?> x = …`) defer to a follow-on
because `LocalVar`/`TypeRef` cannot yet express the `var` keyword or a wildcard argument.

Generated output is byte-for-byte identical; the interop/emission tests and the
`:recordbuf-fixtures:compileJava` gate are the guard.

### Problem Frame

The current `Return` / `Throw` / `LocalVar` shape pushes a raw string into the generic AST layer:
`Return`/`Throw` carry a `String expr`, and the writer builds them with the `"return "` keyword already
concatenated in — either as an `ExprStatement` (`Element.expr("return …")`), a `Fragment`
(`Element.of("return …;")`), or directly (`Element.ret("…")`). The `LocalVar` initializer is the same
pattern: `WktSourceMapping` seeds it with a string template + args
(`build("new @0<>()", TypeRefs.ARRAY_LIST)`), and several writers emit a whole "declare-and-assign"
statement as a raw string (`Element.expr("List<@0> @1 = new @2<>();", …)`,
`Element.expr("@0 @1 = null;", …)`, `Element.expr("var _@0 = new @1<>()", …)`).

This is the expression-statement-layer analogue of what plan 008 fixed at the field-initializer layer.
Two costs stand out. First, the keyword / the whole statement lives in the caller's string, so the
renderer cannot treat the expression as a self-contained, structured unit. Second, and more important,
the renderer's type-collection walk (`SourceRenderer#collectBody`) does **not** descend into
`Return`/`Throw` today (they fall through to `default`). That is only safe *because* the current
`Return`/`Throw` usages happen to carry no `TypeRef`; the moment a migrated return expression carries a
`TypeRef` (e.g. `InteropSourceMapping:103` builds `return new @0(@1)` where `@1` is a `CommaList`
containing `RawExpr.of("@0.empty()", TypeRefs.UNKNOWN_FIELDS)`), the `UNKNOWN_FIELDS` import would
silently go unregistered and `:recordbuf-fixtures:compileJava` would break. The collection fix is
therefore load-bearing, not cosmetic.

### Key Decisions

- **`Return` / `Throw` hold a `RawExpr` (nullable for `Return`), mirroring `ExprStatement`.** The slot
  type is `RawExpr` (not `Formattable`), matching the closest precedent `ExprStatement`/`Element.expr`
  and the "an already constructed `RawExpr`" phrasing. A `return (switch …)` structured expression is
  not a current case; widening to `Formattable` later is a one-line change if it arises.
- **Two factory overloads per node, mirroring `Element.expr`, plus a bare `ret()`.** `ret(String
  template, Object... args)` and `ret(RawExpr expr)` both return `new Return(…)`; `ret()` returns
  `new Return(null)` for the bare `return;`. `throwOf(String template, Object... args)` and
  `throwOf(RawExpr expr)` mirror it; `throw` has no bare form (`throw;` is not valid Java) and no call
  sites, so no `throwOf()`.
- **Keep the string-templating overloads as retained conveniences.** The string-template overload *is*
  the convenience; there is no separate "raw string" overload to delete. The migration moves the writer
  call sites to the structured form but does not remove the string form (per the request's Q2
  decision), so `ret(String, …)` and `throwOf(String, …)` stay available.
- **`Throw` is migrated now, in the same slice.** It is the exact structural twin of `Return` (same
  slot, same factory shape) and has **zero** call sites in `main` source (verified), so migrating it is
  a pure type + factory change with no migration cost — it keeps the `return`/`throw` pair symmetric.
- **`LocalVar` keeps its single `build(Formattable)` setter as the expression-typed enabler.** It
  already exists and a `RawExpr` flows through it (a `RawExpr` is a `Formattable`), so no dedicated
  `build(RawExpr)` overload is required; `build()` (bare) and `build(String, Object...)` (string
  templating, per Q2) remain alongside. The call chain `LocalVar.builder(TypeRef, String).build(…)`
  already reads as "TypeRef, name, then expression" — the desired shape the request asks for.
- **LocalVar scope = the expressible-now subset only; `var`/wildcard defer to a follow-on slice.** Of
  the 15 "declaration-with-assignment" sites, 9 are expressible as `LocalVar.builder(TypeRef, name)
  .build(<expr>)` today (their types are real `TypeRef`s). The other 6 — 5 `var _x = …`
  (`InteropSourceMapping:124/128/157/164/170`) and 1 `List<?> x = …` (`CodecSourceMapping:198`) —
  cannot be expressed because `LocalVar`'s type is a `TypeRef` and `var` is a keyword `TypeRef` cannot
  represent, and `TypeRef.of` does not parse a wildcard `?` argument. These defer to a follow-on that
  adds `var`/wildcard support.

### Requirements

**Node types**

R1. `Return` changes from `record Return(String expr)` to `record Return(RawExpr expr)`; a `null`
`expr` denotes a bare `return;` (the current `ret()` / `Element.ret()` path).

R2. `Throw` changes from `record Throw(String expr)` to `record Throw(RawExpr expr)`; `expr` is
non-null (`throw;` is not valid Java, and there are no bare-throw call sites).

**Factories**

R3. `Element.ret(String template, Object... args)` returns `new Return(RawExpr.of(template, args))` —
mirroring `Element.expr(String, Object...)`.

R4. `Element.ret(RawExpr expr)` returns `new Return(expr)` — the "already constructed" overload.

R5. `Element.ret()` returns `new Return(null)` for the bare `return;` (unchanged from today).

R6. `Element.throwOf(String template, Object... args)` returns `new Throw(RawExpr.of(template, args))`;
`Element.throwOf(RawExpr expr)` returns `new Throw(expr)`.

R7. The two `ret(String)` call sites (`EnumSourceMapping:78`, `:107`) keep their exact call text —
after R3 they bind to the new varargs overload with empty args; no textual change is required.

**LocalVar initializer**

R8. `LocalVar` keeps `build()` (bare declaration), `build(Formattable init)` (the expression-typed
enabler — a `RawExpr` binds to it), and `build(String template, Object... args)` (string templating,
retained per Q2). No new `build(RawExpr)` overload is required: `RawExpr` is a `Formattable`, so
`build(RawExpr.of(…))` binds to `build(Formattable)`.

R9. The 9 expressible-now "declaration-with-assignment" sites migrate to
`LocalVar.builder(TypeRef, name).build(<expression>)`, dropping the string-templated / raw-string form:

| # | Site (pre-migration) | Post-migration |
|---|---|---|
| L1 | `WktSourceMapping:47` `LocalVar.builder(namedList, name).build("new @0<>()", TypeRefs.ARRAY_LIST)` | `…build(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST))` |
| L2 | `WktSourceMapping:50` `LocalVar.builder(type, name).build(entry.zeroDefault())` | guarded seed — `build(RawExpr.of(requireNonNull(entry.zeroDefault())))` or keep the string overload for this one (see OQ1) |
| L3 | `WktSourceMapping:53` `LocalVar.builder(type, name).build("null")` | `…build(RawExpr.of("null"))` |
| L4 | `WktSourceMapping:44–45` builds `namedList` = `TypeRef.named("java.util","List",[…])`; consumed at L1 | unchanged (already a `TypeRef`) |
| L5 | `CodecSourceMapping:179` `Element.expr("List<@0> @1 = new @2<>();", typeShort, f.javaName(), TypeRefs.ARRAY_LIST)` | `LocalVar.builder(TypeRef.named("java.util","List",List.of(TypeRef.of(f.typeUrl()))), f.javaName()).build(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST))` |
| L6 | `CodecSourceMapping:182` `Element.expr("@0 @1 = null;", typeShort, f.javaName())` | `LocalVar.builder(TypeRef.of(f.typeUrl()), f.javaName()).build(RawExpr.of("null"))` |
| L7 | `InteropSourceMapping:142` `Element.expr(RawExpr.of("@0<@2> _@3 = new @1<>()", TypeRefs.LIST, TypeRefs.ARRAY_LIST, TypeRef.of(recSib), f.javaName()))` | `LocalVar.builder(TypeRef.named("java.util","List",List.of(TypeRef.of(recSib))), "_" + f.javaName()).build(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST))` |
| L8 | `CodecSourceMapping:200` `Element.expr("Object @0 = null;", f.javaName())` | `LocalVar.builder(TypeRef.of("java.lang.Object"), f.javaName()).build(RawExpr.of("null"))` |
| L9 | `CodecSourceMapping:192/194` `Element.expr("List<@0> @1 = new @2<>();", enumShort, …)` / `Element.expr("@0 @1 = null;", enumShort, …)` | `LocalVar.builder(<enum `TypeRef`>, name).build(…)` — building the enum/`List<enum>` `TypeRef` from the `typeResolver` short name is a planning detail; byte-verify, drop to the follow-on if not clean |

R10. `LocalVar` factory chain reads as "TypeRef, variable-name string, then expression":
`LocalVar.builder(TypeRef, String).build(<RawExpr>)` — the shape the request asks for.

**Rendering and collection**

R11. `SourceRenderer#renderStatement` renders `Return` as `return;` when `expr` is null, else
`return ` + `expr.render(ctx)` + `;` (stripping a trailing `;` from the rendered expression if present,
defensively, so a migrated `new @0(key, value)` template that retains a trailing `;` stays
byte-identical). `Throw` renders as `throw ` + `expr.render(ctx)` + `;`.

R12. `SourceRenderer#collectBody` descends into the migrated nodes — `case Return r -> { if
(r.expr() != null) collectExpr(r.expr()); }` and `case Throw t -> collectExpr(t.expr());` — so a
`TypeRef` carried inside a `return`/`throw` expression (e.g. `TypeRefs.UNKNOWN_FIELDS` inside
`InteropSourceMapping:103`'s `CommaList`) still registers its import. This is the load-bearing
correctness fix; without it a migrated return-with-`TypeRef` silently drops its import.

**Call-site migration (the 17 `return` prefix sites)**

R13. The 17 writer-layer `"return "`-prefixed string sites migrate to the `ret` factory, dropping the
`"return "` (and any trailing `";"`) from the template. The migration is a pure textual
`"return X"` → `ret("X")` rewrite across `RecordSourceMapping`, `InteropSourceMapping`,
`AvroConverterSourceMapping`, and `CodecSourceMapping` (see the migration table below); the
`@N`/args are unchanged, so each migrated `RawExpr` renders identically to the string it replaced.

**Migration table — `return` prefix sites (R13)**

| # | Site (pre-migration) | Post-migration |
|---|---|---|
| 1 | `RecordSourceMapping:155` `Element.expr("return new @0.Builder(this)", m.name())` | `Element.ret("new @0.Builder(this)", m.name())` |
| 2 | `RecordSourceMapping:164` `Element.expr("return @0.decode(in)", codecName)` | `Element.ret("@0.decode(in)", codecName)` |
| 3 | `RecordSourceMapping:175` `Element.expr("return @0Codec.encode(this)", m.name())` | `Element.ret("@0Codec.encode(this)", m.name())` |
| 4 | `RecordSourceMapping:509` `Element.expr("return new @0(@1)", recName, String.join(", ", ctorArgs))` | `Element.ret("new @0(@1)", recName, String.join(", ", ctorArgs))` |
| 5 | `RecordSourceMapping:528` `Element.expr("return new @0.Builder()", recName)` | `Element.ret("new @0.Builder()", recName)` |
| 6 | `InteropSourceMapping:103` `Element.expr("return new @0(@1)", rec, CommaList.of(ctorArgs))` | `Element.ret("new @0(@1)", rec, CommaList.of(ctorArgs))` — carries `TypeRefs.UNKNOWN_FIELDS` (R12) |
| 7 | `CodecSourceMapping:268` `Element.expr("return new @0(@1)", m.name(), joinCommas(allNames))` | `Element.ret("new @0(@1)", m.name(), joinCommas(allNames))` |
| 8 | `CodecSourceMapping:669` `Element.expr("return new @0(key, value);", entryName)` | `Element.ret("new @0(key, value)", entryName)` — trailing `;` dropped |
| 9–12 | `RecordSourceMapping:372/471/482/491` `Element.of("return this;")` | `Element.ret("this")` |
| 13 | `InteropSourceMapping:218` `Element.of("return _b.build();")` | `Element.ret("_b.build()")` |
| 14 | `AvroConverterSourceMapping:109` `Element.of("return b.build();")` | `Element.ret("b.build()")` |
| 15 | `AvroConverterSourceMapping:149` `Element.of("return s;")` | `Element.ret("s")` |
| 16 | `CodecSourceMapping:134` `Element.of("return enc.finish();")` | `Element.ret("enc.finish()")` |
| 17 | `CodecSourceMapping:641` `Element.of("return enc.finish();")` | `Element.ret("enc.finish()")` |

### Scope Boundaries

- **In scope:** `Return`/`Throw` slot type + `Element.ret`/`throwOf` factories; the `SourceRenderer`
  render + collection changes; the 17 `return` prefix sites; the `Throw` twin (zero call sites); the 9
  expressible-now `LocalVar` "declaration-with-assignment" sites (R9); confirming the
  `LocalVar.build(Formattable)` expression-typed setter (R8).
- **Deferred to a follow-on slice (by decision, not blocked):** the 6 `var`/wildcard
  "declaration-with-assignment" sites — `InteropSourceMapping:124/128/157/164/170` (5 `var _x = …`)
  and `CodecSourceMapping:198` (`List<?> x = …`). These need `LocalVar`/`TypeRef` to express the `var`
  keyword and a wildcard argument, which they cannot today.
- **Out of scope:** `Element.expr` / `ExprStatement` (the non-`return`/`throw` expression-statement
  path — unchanged); any widening of `Return`/`Throw` to a `Formattable` slot (a future concern, no
  current case); any `throw` call-site creation (there are none — `Throw` migration is structural only).
- **Not a behaviour change:** generated `.rec`/`.codec`/interop/avro output is byte-identical; this is
  a pure internal shape migration. No generated-code semantics, ordering, or imports change.

### Acceptance Examples

**AE1.** The `return` keyword is rendered by the node, not the caller. **Covers R1, R11.** *Given* the
migration is applied, *when* `SourceRenderer` renders any `Return`/`Throw` node, *then* the emitted
`return `/`throw ` prefix comes from the `renderStatement` arm, and no caller-supplied template in the
writer layer begins with `"return "` or `"throw "`.

**AE2.** A return carrying a `TypeRef` still registers its import. **Covers R12.** *Given*
`InteropSourceMapping:103` is migrated to `Element.ret("new @0(@1)", rec, CommaList.of(ctorArgs))`
where `ctorArgs` contains `RawExpr.of("@0.empty()", TypeRefs.UNKNOWN_FIELDS)`, *when* the generator
emits that message, *then* the generated source contains `import
bijnagte.recordbuf.runtime.UnknownFields;` and `:recordbuf-fixtures:compileJava` is green.

**AE3.** Byte-for-byte output invariant holds. **Covers R11, R13, R9.** *Given* the migration is
applied, *when* the full test suite runs, *then* the existing emission assertions
(`RecordHookEmissionTest`, `BuilderEmissionTest`, `EnumSourceMappingTest`,
`AvroConverterSourceMappingTest`) that assert `src.contains("return …")` stay green, and the
`:recordbuf-fixtures` generated code compiles and round-trips. Each migrated `LocalVar` site (R9)
renders byte-identically to the string it replaced; a site that proves non-byte-stable drops to the
follow-on rather than forcing a behaviour change.

**AE4.** `Throw` is symmetric with `Return` despite having no call sites. **Covers R2, R6.** *Given*
`Throw` is migrated to `record Throw(RawExpr)` with `throwOf(String, Object...)` / `throwOf(RawExpr)`,
*when* the module compiles, *then* no call site breaks (there are none) and the `return`/`throw` pair
shares one shape; a future `throw` site can be added via the factory with no further shape change.

**AE5.** The `var`/wildcard sites are untouched. **Covers the Scope Boundaries deferral.** *Given*
this slice migrates only the expressible-now subset, *when* `InteropSourceMapping:124/128/157/164/170`
and `CodecSourceMapping:198` are inspected, *then* they still use their current raw-string /
`var` form, unchanged.

### Outstanding Questions

- **OQ1 — `WktSourceMapping:50` null-safety on the migrated seed (resolve before planning).** The
  current `build(entry.zeroDefault())` passes a `@Nullable String` guarded by `hasZeroDefault()`.
  `RawExpr.of(String)`'s template is `@NonNull`, so a naive `RawExpr.of(entry.zeroDefault())` would
  trip NullAway (error severity) on the `@Nullable` receiver. Planning must choose the mechanism: keep
  the `hasZeroDefault()` guard and call `RawExpr.of(requireNonNull(entry.zeroDefault()))`, or keep the
  retained `build(String, Object...)` path for this one site.
- **OQ2 — enum `TypeRef` construction for `CodecSourceMapping:192/194` (resolve before planning).**
  These sites use a short name from `typeResolver.apply(f)`; building `List<enum>` / `enum` as a
  `LocalVar` type needs the enum's `TypeRef`, which the `typeResolver` returns only as a short-name
  string. Planning must source the `TypeRef` (e.g. resolve the enum FQN) or keep these two as the
  retained string form; byte-verify either way.
- **OQ3 — add a `ReturnThrowRenderingTest` (deferred to planning).** No dedicated render test pins the
  new `Return`/`Throw` path today (byte-stability is enforced indirectly by the emission tests).
  Recommended: add a small `ReturnThrowRenderingTest` pinning bare `return;`, `return <expr>;`,
  `throw <expr>;`, and the trailing-`;`-strip defensive case, so the new arm is covered directly.
- **OQ4 — dedicated `build(RawExpr)` on `LocalVar` (deferred to planning).** R8 settles on the single
  `build(Formattable)` setter; `RawExpr` flows through it. Confirm no explicit `build(RawExpr)`
  overload is wanted for symmetry with `ret(RawExpr)` — recommended: no, keep the single
  `Formattable` setter.

### Sources / Research

- `codegen/.../source/Return.java`, `Throw.java`, `ExprStatement.java`, `LocalVar.java`, `Element.java`
  — current node shapes and factories; `Element.expr` (two overloads) and `LocalVar.build(Formattable)`
  are the precedents the new `ret`/`throwOf`/LocalVar migration mirror.
- `codegen/.../source/SourceRenderer.java` — `renderStatement` (the `Return`/`Throw` arms),
  `renderLocalVar` (`<type> <name>[ = <init>];`), and `collectBody` (the type-collection walk that
  must gain `case Return`/`case Throw`); the `RawExpr` arg-collection at the `collectExpr` `RawExpr`
  arm is what registers the `UNKNOWN_FIELDS` import.
- `codegen/.../source/TypeRef.java` — `TypeRef.of(String)` parses `[pkg.]Short<args>` (parameterized
  types are expressible) but does not parse the `var` keyword or a `?` wildcard argument — the reason
  the 6 `var`/wildcard sites defer.
- `codegen/.../source/SourceAstClosednessTest.java` — the byte-stability guard the migration must keep
  green.
- `generator/.../writer/TypeRefs.java` — the `TypeRef` constants the call sites reference
  (`ARRAY_LIST`, `HASH_MAP`, `UNKNOWN_FIELDS`, …); note the constant lives on `TypeRefs` (plural), so
  the call shape is `Element.ret("new @0<>()", TypeRefs.ARRAY_LIST)`, not `TypeRef.ARRAY_LIST`.
- `generator/.../writer/RecordSourceMapping.java`, `InteropSourceMapping.java`,
  `AvroConverterSourceMapping.java`, `CodecSourceMapping.java`, `EnumSourceMapping.java`,
  `WktSourceMapping.java` — the 17 `return` prefix sites, the 9 expressible-now `LocalVar` sites, the 6
  deferred `var`/wildcard sites, and the 2 `ret(String)` sites (see the migration tables).
- Precedent plans: `docs/plans/2026-11-13-008-refactor-retire-component-initializer-string-plan.md`
  (the field-initializer-layer analogue), `…/003-…-body-lift-plan.md` and `…/004-…-ast-closedness.md`
  (the series this continues).

## Implementation

**Product Contract preservation:** the Product Contract above (Requirements, Scope Boundaries,
Acceptance Examples) is unchanged by this enrichment — only the *how* is made implementation-ready.
Generated output stays byte-identical (AE3); the only observable effect is that `Return`/`Throw`/`LocalVar`
carry a structured expression instead of a string. No new runtime dependency; `codegen` stays
dependency-free.

**OQ resolutions (settled at planning):**

- **OQ1 — `WktSourceMapping:50` null-safety → resolve via `Objects.requireNonNull`.** `entry.zeroDefault()`
  is a `@Nullable String`, guarded at the call site by `hasZeroDefault()`; `RawExpr.of(String)`'s template
  is `@NonNull`, so a naive `RawExpr.of(entry.zeroDefault())` would trip NullAway at error severity.
  `WktSourceMapping` already calls `Objects.requireNonNull(entry.zeroDefault())` at `:70`/`:93` and imports
  `java.util.Objects`, so the migration is `build(RawExpr.of(Objects.requireNonNull(entry.zeroDefault())))`
  — consistent with the file's existing idiom, no new import. The retained `build(String, Object...)` path
  is *not* kept for this site (the guard is already the norm here).
- **OQ2 — enum `TypeRef` construction for `CodecSourceMapping:192/194` → resolve via `TypeRef.of(f.typeUrl())`.**
  Both message (`:179/182`) and enum (`:192/194`) sites derive their short name from `f.typeUrl()` (message:
  `.substring(f.typeUrl().lastIndexOf('.') + 1)`; enum: `typeResolver.apply(f)`). `f.typeUrl()` yields the
  FQN for both, so `TypeRef.of(f.typeUrl())` is the `LocalVar` type, and the repeated forms wrap it as
  `TypeRef.named("java.util", "List", List.of(TypeRef.of(f.typeUrl())))`. `CodecSourceMapping` already
  imports `RawExpr`, `TypeRef`, `java.util.List`, and `java.util.Objects`. The renderer's
  `ci.resolutionContext()` resolves `TypeRef.of(f.typeUrl())` to the same short name the string path used,
  so byte-stability holds *if* the import is already present — byte-verify via the fixtures gate (R-2).
- **OQ3 — `ReturnThrowRenderingTest` → add it.** A small test in
  `codegen/src/test/java/bijnagte/recordbuf/codegen/` pinning bare `return;`, `return <expr>;`,
  `throw <expr>;`, and the trailing-`;`-strip defensive case, so the new render arm is covered directly
  (byte-stability is otherwise enforced only indirectly by the emission tests).
- **OQ4 — dedicated `build(RawExpr)` on `LocalVar` → no.** Keep the single `build(Formattable)` setter;
  a `RawExpr` binds to it (a `RawExpr` *is* a `Formattable`). A dedicated `build(RawExpr)` overload would
  be a no-op duplicate — skip it for symmetry with `ret(RawExpr)`; the `build(Formattable)` enabler is
  sufficient and already reads as "TypeRef, name, then expression."

**Pre-migration baseline (verified at planning, 2026-11-14):** `./gradlew build` is green on `main @
e035265`; `:recordbuf-generator:test` and `:recordbuf-fixtures:compileJava` pass. The migration is a
refactor with a byte-identical-output invariant (AE3), so the baseline is the regression guard.

### Implementation Units

**IU-1 — Node type + factory + renderer/collector (the structural core).**

- `Return.java`: `record Return(String expr)` → `record Return(RawExpr expr)` (R1); `null` denotes bare
  `return;`. `RawExpr` is in the same package (`bijnagte.recordbuf.codegen.source`) — no new import.
- `Throw.java`: `record Throw(String expr)` → `record Throw(RawExpr expr)` (R2); `expr` non-null (no bare
  `throw;`). No call sites today — pure structural change, keeps the pair symmetric (AE4).
- `Element.java` factories (replacing the current `ret(String)`/`ret()`/`throwOf(String)` at `:32`/`:36`/
  `:40`):
  - `static Return ret()` → `new Return(null)` (bare `return;`, unchanged, R5).
  - `static Return ret(String template, Object... args)` → `new Return(RawExpr.of(template, args))` (R3);
    the two `ret("…")` call sites (`EnumSourceMapping:78`/`:107`) bind to this with empty args — no
    textual change (R7).
  - `static Return ret(RawExpr expr)` → `new Return(expr)` (R4, the "already constructed" form).
  - `static Throw throwOf(String template, Object... args)` → `new Throw(RawExpr.of(template, args))`; and
    `static Throw throwOf(RawExpr expr)` → `new Throw(expr)` (R6). No `throwOf()` (no bare throw, no call
    site).
- `SourceRenderer#renderStatement` (`:525`):
  - `case Return r ->` — keep the existing null-check (`r.expr() == null ? "return;" : …`) but render the
    `RawExpr` via `ctx`: `if (r.expr() == null) out.append("return;\n"); else { String rendered =
    r.expr().render(ctx); strip a trailing `;` (mirror the `ExprStatement` arm at `:533`-`:541`); out.append
    ("return ").append(rendered).append(";\n"); }`.
  - `case Throw t ->` — `String rendered = t.expr().render(ctx); strip trailing `;` (defensive, for
    symmetry); out.append("throw ").append(rendered).append(";\n");`.
  - Note: the current arm already null-checks `Return` (line `:529`-`:530`), so there is **no** latent
    `return null;` bug to fix — the change is purely `"return " + r.expr() + ";"` (string concat of a `String`)
    → `r.expr().render(ctx)` (rendering a `RawExpr`), retaining the null-check and adopting the
    trailing-`;`-strip the `ExprStatement` arm already has.
- `SourceRenderer#collectBody` (`:168`) — add the two arms so a `TypeRef` carried inside a migrated
  `return`/`throw` still registers its import (R12, **load-bearing**):
  - `case Return r -> { if (r.expr() != null) collectExpr(r.expr()); }`
  - `case Throw t -> collectExpr(t.expr());`
  - `collectExpr(Formattable)` (`:203`) already accepts a `RawExpr` (a `RawExpr` *is* a `Formattable`), and
    `RawExpr#referencedTypes()` unions its `Formattable` args' types (skipping `String` args) — so this is
    what registers e.g. `TypeRefs.UNKNOWN_FIELDS` carried inside `InteropSourceMapping:103`'s `CommaList`.
  - Without these arms, the migrated `InteropSourceMapping:103` `return` (moved from `ExprStatement`,
    *which* is collected, to a `Return` node, which is *not* today) would silently drop the
    `bijnagte.recordbuf.runtime.UnknownFields` import and break `:recordbuf-fixtures:compileJava` (AE2).

**IU-2 — Migrate the 17 `return`-prefix call sites (R13).**

Pure textual `"return X"` → `ret("X")` rewrite; args unchanged, so each migrated `RawExpr` renders
identically. `ExprStatement`→`ret` (drop `"return "`): `RecordSourceMapping:155/164/175/509/528`,
`InteropSourceMapping:103`, `CodecSourceMapping:268`. `Fragment` `Element.of("return …;")` → `ret(…)` (drop
`"return "` *and* the trailing `";"`): `RecordSourceMapping:372/471/482/491` (`return this;` →
`ret("this")`), `InteropSourceMapping:218` (`return _b.build();`), `AvroConverterSourceMapping:109`/`:149`,
`CodecSourceMapping:134`/`:641` (`return enc.finish();`), `CodecSourceMapping:669` (`return new @0(key,
value);` → `ret("new @0(key, value)", entryName)` — trailing `;` dropped). The two `ret("…")` sites
(`EnumSourceMapping:78`/`:107`) are **left textually unchanged** — they bind to the new `ret(String,
Object...)` overload with empty args (R7).

**IU-3 — Migrate the 9 expressible-now `LocalVar` "declare-then-assign" sites (R9).**

Each migrates to `LocalVar.builder(type, name).build(RawExpr.of(…))`, byte-identical to the string it
replaced (AE3):

- `WktSourceMapping:47` → `…build(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST))` (was `build("new @0<>()",
  TypeRefs.ARRAY_LIST)`).
- `WktSourceMapping:50` → `…build(RawExpr.of(Objects.requireNonNull(entry.zeroDefault())))` (OQ1).
- `WktSourceMapping:53` → `…build(RawExpr.of("null"))` (was `build("null")`).
- `CodecSourceMapping:179` (repeated message) →
  `LocalVar.builder(TypeRef.named("java.util", "List", List.of(TypeRef.of(f.typeUrl()))), f.javaName())
  .build(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST))` (was `Element.expr("List<@0> @1 = new @2<>();",
  typeShort, f.javaName(), TypeRefs.ARRAY_LIST)` — note the `ExprStatement`→`LocalVar` shape change).
- `CodecSourceMapping:182` (singular message) →
  `LocalVar.builder(TypeRef.of(f.typeUrl()), f.javaName()).build(RawExpr.of("null"))` (was
  `Element.expr("@0 @1 = null;", typeShort, f.javaName())`).
- `CodecSourceMapping:192` (repeated enum) →
  `LocalVar.builder(TypeRef.named("java.util", "List", List.of(TypeRef.of(f.typeUrl()))), f.javaName())
  .build(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST))` (was `Element.expr("List<@0> @1 = new @2<>();",
  enumShort, …)`; OQ2 — the enum `TypeRef` comes from `TypeRef.of(f.typeUrl())`, not the `typeResolver`
  short-name string).
- `CodecSourceMapping:194` (singular enum) →
  `LocalVar.builder(TypeRef.of(f.typeUrl()), f.javaName()).build(RawExpr.of("null"))` (was
  `Element.expr("@0 @1 = null;", enumShort, f.javaName())`; OQ2).
- `CodecSourceMapping:200` →
  `LocalVar.builder(TypeRef.of("java.lang.Object"), f.javaName()).build(RawExpr.of("null"))` (was
  `Element.expr("Object @0 = null;", f.javaName())`).
- `InteropSourceMapping:142` →
  `LocalVar.builder(TypeRef.named("java.util", "List", List.of(TypeRef.of(recSib))), "_" + f.javaName())
  .build(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST))` (was the `Element.expr(RawExpr.of("@0<@2> _@3 =
  new @1<>()", TypeRefs.LIST, TypeRefs.ARRAY_LIST, TypeRef.of(recSib), f.javaName()))` form).

The `WktSourceMapping` seeds (`:47`/`:50`/`:53`) are already `LocalVar` nodes (only the *init* form changes
from string-templated to a constructed `RawExpr`), so they register the same types as before — unconditionally
byte-stable. The `CodecSourceMapping`/`InteropSourceMapping` sites change shape from `ExprStatement` to
`LocalVar`, which introduces the type-registration difference in R-2.

**IU-4 — Test coverage (OQ3).**

- Add `ReturnThrowRenderingTest` (`codegen/src/test/…`): bare `return;`, `return <expr>;`, `throw <expr>;`,
  and the trailing-`;`-strip defensive case — covers the new render arm directly (AE1/AE4).
- Keep the existing emission tests green (`RecordHookEmissionTest`, `BuilderEmissionTest`,
  `EnumSourceMappingTest`, `AvroConverterSourceMappingTest`) — they assert `src.contains("return …")` and
  stay green because the rendered output is unchanged (AE3).
- `:recordbuf-fixtures:compileJava` (the import-registration guard for R12/AE2) stays green.

### Sequencing & dependencies

- IU-1 → IU-2/IU-3 are **one atomic module edit**: changing `Return`/`Throw` from `String` to `RawExpr`
  ripples to every `ret`/`throwOf` call site at once, so the module cannot compile half-migrated. Land
  IU-1 (node + factory + renderer/collector) and IU-2/IU-3 (call-site migration) together, then
  `./gradlew build`.
- IU-2 and IU-3 are independent of each other (disjoint call-site sets) but both depend on IU-1.
- IU-4 lands with the change (the new test compiles only against the migrated `Return`/`Throw` shape).
- No worktree or branch decision needed: the slice is small and single-module; the implementer commits on
  `main` per the user's earlier `main` choice. (If the user prefers a branch, that is a git-workflow
  decision at commit time, not a plan-level fork.)

### Test scenarios

- **TS-1 — byte-stability (AE3).** `./gradlew build` green; the emission tests asserting
  `src.contains("return …")`/`throw …` stay green; `:recordbuf-fixtures` generated code compiles and
  round-trips. Diff generated output for `WktSourceMapping`, `CodecSourceMapping`, `InteropSourceMapping`
  before/after where a site changed shape (`ExprStatement`→`LocalVar`).
- **TS-2 — import registration (AE2 / R12).** Regenerate the interop path carrying `TypeRefs.UNKNOWN_FIELDS`
  in a `return` expression (`InteropSourceMapping:103`); assert the generated source contains
  `import bijnagte.recordbuf.runtime.UnknownFields;` and `:recordbuf-fixtures:compileJava` is green — the
  regression IU-1's `collectBody` arms prevent. A targeted check: temporarily revert the `collectBody` arms
  and confirm the test *fails* (proves the arms are load-bearing), then restore.
- **TS-3 — `LocalVar` byte-stability (R9 / AE3).** Each migrated `LocalVar` site renders byte-identically
  to the string it replaced; verified by the `:recordbuf-fixtures` round-trip + TS-1's output diff. The
  `WktSourceMapping` seeds are unconditionally safe (same types registered); the `CodecSourceMapping`/
  `InteropSourceMapping` sites are gated by R-2.
- **TS-4 — new render test (OQ3).** `ReturnThrowRenderingTest` passes: bare `return;`, `return <expr>;`,
  `throw <expr>;`, trailing-`;`-strip.

### Risk register

- **R-1 — (cleared at planning) no `return null;` latent bug.** The current `renderStatement` `case
  Return` (`:529`-`:530`) already null-checks (`r.expr() == null ? "return;" : …`), so migrating to a
  `RawExpr` slot needs no null-handling fix — only `"return " + r.expr() + ";"` → `r.expr().render(ctx)`
  with the null-check retained. No behaviour risk here.
- **R-2 — (the real one) `ExprStatement`→`LocalVar` registration delta may add an import.** Migrating a
  site from `Element.expr("List<@0> …;", typeShort, …)` (a `String` short-name, *not* registered in
  `collectBody`) to `LocalVar.builder(TypeRef.of(f.typeUrl()), …)` registers that type in
  `SourceRenderer#accumulated` via `collectBody`'s `LocalVar` arm. If that type is *not* already imported
  in the generated output, a new `import` line appears — a byte-diff against the pre-migration output.
  The renderer's `ci.resolutionContext()` resolves `TypeRef.of(f.typeUrl())` to the same short name the
  string path emitted, so the *body* stays identical; only a possible new import line is the risk.
  **Mitigation:** byte-diff the `:recordbuf-fixtures` generated output before/after; if a site adds an
  import that the fixtures gate rejects or that diverges, drop *that* site to the follow-on (AE3's
  "a site that proves non-byte-stable drops to the follow-on" clause) rather than force a behaviour change.
  The `WktSourceMapping` seeds are unaffected (already `LocalVar`, same types registered).
- **R-3 — `throw` has no call sites.** The `Throw` migration is structural only (AE4); no generated output
  exercises the new `throw ` arm, so it is covered only by `ReturnThrowRenderingTest` (OQ3), not by the
  fixtures. Acceptable — it keeps the pair symmetric for a future `throw` site, and the test pins the
  render.
- **R-4 — NullAway at error severity.** `RawExpr.of`'s template is `@NonNull`; the only nullable input is
  `entry.zeroDefault()` (`WktSourceMapping:50`), guarded by `Objects.requireNonNull` per OQ1. No other
  migrated site introduces a nullable receiver. Verify `./gradlew build` (NullAway error-severity) is
  green after the change.
