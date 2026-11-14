---
title: Retire Component.initializer String Escape Hatch - Plan
type: refactor
date: 2026-11-13
topic: retire-component-initializer-string
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
status: complete
updated: 2026-11-13
---

## Goal Capsule

**Objective**. Unify `Component.initializer` (`String`) with `LocalVar.init`
(`Formattable`), retiring the last raw-string escape hatch at the field-declaration
layer. This is the field-layer analogue of the expression-layer migration that
005 did — the positional `@N` system is the single contract for both locals
and fields.

**Product authority.** `main` @ `b8bf6d4`. No user-facing behavior changes; the
generated Java output is byte-identical to pre-plan.

**Open blockers.** None. The positional `RawExpr` system is in place
(004/005/006/007).

---

## Product Contract

### Problem Frame

`Component` is the codegen AST node emitted for a record component and a
type-body class field. `LocalVar` is the codegen AST node emitted for a local
declaration in a statement body. Both "declare a name with a type and
optionally an initializer." The two records stay separate — `Component`
carries `Javadoc` and `Modifiers`, `LocalVar` does not (Java forbids both on
locals), and they live in different containers with different render
punctuation. That split is correct for the Java semantics.

But their *initializer halves* should not differ:

- `LocalVar.init` is a `Formattable` (the positional `@N` templating system
  from 004/005 — the canonical expression form).
- `Component.initializer` is a `String` — a raw escape hatch that 005/006
  did not migrate.

`EnumSourceMapping.java:86-95` already admits this:

```java
// The initializer still needs a string for Component.initializer(String);
// TypeRef.collect handles the import; the RawExpr @0 resolves to the short name at render.
TypeRef mapType = TypeRef.named("java.util", "Map", mapGenerics);
TypeRef hashMapRef = TypeRef.of("java.util.HashMap");
hashMapRef.collect(ci);
result.field(
    Component.type(mapType, "BY_NUMBER")
        .private_()
        .static_()
        .final_()
        .initializer(RawExpr.of("new @0<>()", hashMapRef).render(ci.resolutionContext()))
        .build());
```

The `BY_NUMBER` field *pre-renders* a `RawExpr` to a string precisely because
the initializer field is typed as `String` rather than `Formattable`. The same
`String` contract is used at three more call sites:

| Site | Current initializer | Why it matters |
|------|---------------------|----------------|
| `EnumSourceMapping.java:95` | `RawExpr.of("new @0<>()", hashMapRef).render(ci.resolutionContext())` | Pre-renders a `Formattable` to fit the `String` slot — a known workaround the file's own comment calls out |
| `RecordSourceMapping.java:447-450` | `fieldDefault` (a `String` returned by `defaultFor(f)` at line 581) | Field-default expressions live as `String`, bypassing positional templating |
| `RecordSourceMapping.java:460` | `"null"` | A bare `String` literal — fine, but `RawExpr.of("null")` is the same shape with no escape-hatch tax |
| `RecordSourceMapping.java:470` | `ufShort + ".empty()"` | **String concat** — the exact pattern the positional `@N` system was created to kill |

Each of the four is a small wart; together they are the last `String`-typed
initializer escape hatch in the writer layer. After this plan the only
`Formattable` in the codebase is the positional one (`RawExpr` over the
`@N` system, no `raw(String)` shim — `Formattable` is a `sealed` interface;
`RawExpr.of("null")` is already in the test suite, see `RawExprTest.java:80`).

### Key Decisions

- **`Component.initializer` changes from `String` to `Formattable`**
  (nullable; `null` = no initializer). The compact-ctor `null → ""` seed
  becomes `null → null`; the renderer reads a `null` initializer as "no
  `= <init>` clause," a non-null initializer as "render the `Formattable`
  into the `= <init>` slot," and the collect pass walks the `Formattable`
  tree (via `RawExpr.collect`) to pick up any `TypeRef`s.
- **Migrate the four `.initializer(...)` call sites** to `Formattable`
  literals:
  1. `BY_NUMBER` (EnumSourceMapping) — the `RawExpr.of("new @0<>()",
     hashMapRef)` becomes the initializer itself; the pre-render workaround
     + its `TypeRef.collect(ci)` call-site are both deleted — the renderer's
     collect pass picks up the `TypeRef` automatically.
  2. Builder-field defaults — `defaultFor(f)` stays a `String` function
     (the existing callers are local to `RecordSourceMapping`), wrapped with
     `RawExpr.of(defaultFor(f))` at the call site.
  3. `ufShort + ".empty()"` — becomes `RawExpr.of("@0.empty()", ufRef)`; the
     `ufRef` on line 469 (already used for the type slot) is reused.
  4. `null` literal — becomes `RawExpr.of("null")` (no placeholders, no
     args — just a raw-string `Formattable`), per `RawExprTest.java:80`.
- **`Component.Builder.initializer(String)` is deleted.** No shim — the four
  call sites are migrated in this unit, not left as a backwards-compat
  bridge.
- **`Component.initializer` Javadoc** no longer mentions "raw escape-hatch
  string rendered verbatim"; the new wording says the field is a `Formattable`
  rendered through the positional-template contract.
- **No new `Component` factory methods** are added. `Component.type(type,
  name)` and `Component.named(fqn, name)` already seed; only the
  `Builder.initializer` setter and the renderer's `null` handling change.

### Requirements

**R8-init-1.** `Component.initializer` is a `Formattable` (nullable); the
compact-ctor no longer coerces `null`/blank. The renderer's `renderField`
reads the initializer as:

| `Component.initializer` | Rendered output |
|---|---|
| `null` | `<type> <modifiers> <name>;` — no `= <init>` clause |
| non-null `RawExpr.of("...")` | `<type> <modifiers> <name> = <rendered Formattable>;` |

during the collect pass the renderer calls `initializer.collect(ci)` (via
`RawExpr.collect`, which already recursively registers `Formattable` args
and `TypeRef`s).

**R8-init-2.** The `Builder.initializer(String)` overload is deleted. A
`Builder.initializer(Formattable)` setter is added.

**R8-init-3.** The four call sites are migrated to `Formattable`:

| Site | New shape |
|------|-----------|
| `EnumSourceMapping.java:90-95` `BY_NUMBER` | Delete the `// The initializer still needs a string` comment + the `hashMapRef.collect(ci)` workaround; the initializer becomes `RawExpr.of("new @0<>()", hashMapRef)` — no `.render(...)` call |
| `RecordSourceMapping.java:447-450` | `RawExpr.of(defaultFor(f))` wraps the `String` field-default |
| `RecordSourceMapping.java:460` | `RawExpr.of("null")` — a plain string in the positional shell |
| `RecordSourceMapping.java:470` | `RawExpr.of("@0.empty()", ufRef)` — kills the string concat (`ufRef` on line 469 reused) |

**R8-init-4.** `Component`'s Javadoc is rewritten for the `Formattable`
initialiser; no mention of "escape-hatch raw string."

### Scope Boundaries

**Deferred.** No change to `LocalVar` (its `init` is already the target
shape). No change to the positional `@N` system, `RawExpr`, or the
renderer's `@N` resolution — they are unchanged. No change to record-component
syntax: a `Component` rendered as a record component still omits modifiers/
initializer (Java's record-component grammar puts initializers on the
canonical constructor); this plan touches only the *type-body field* render
path. No cross-project changes — this is a self-contained writer-layer
refactor; the plugin's output and import grouping are unchanged.

**Explicitly out of scope.** No new `Formattable.raw(String)` factory;
`RawExpr.of("...")` already produces a raw-string `Formattable`
(`RawExprTest.java:80`).

### Acceptance Examples

**AE1.** `grep -nE 'initializer\("' generator/src/main/java/bijnagte/recordbuf/plugin/writer/`
returns zero matches — the `.initializer("...")` String call sites are gone.
(Covers R8-init-3.)

**AE2.** `Component.initializer(String)` is gone:
`grep -n "Builder initializer" codegen/src/main/java/bijnagte/recordbuf/codegen/source/Component.java`
returns only the `Formattable` overload.
(Covers R8-init-2.)

**AE3.** `EnumSourceMapping.java` no longer contains the pre-render
workaround — the `// The initializer still needs a string` comment (line 86)
is gone, and the `hashMapRef.collect(ci)` call on line 90 is gone (the
`RawExpr` tree carries the `TypeRef`, so the renderer's collect pass sees it
automatically).
(Covers R8-init-1 + R8-init-3.)

**AE4.** Generated output is byte-identical after `spotless`.
`./gradlew :recordbuf-fixtures:compileJava` green. A fresh
`installDist` + `protoc --recordbuf_out` on `int.proto`/`string.proto`
produces the same Java files as `main` @ `b8bf6d4` — no new imports, no
re-ordered imports.
(Covers R8-init-1 through R8-init-4.)

### Outstanding Questions

**Q1.** `defaultFor(f)` — keep the `String`-returning function and wrap with
`RawExpr.of(defaultFor(f))` at the call site (smallest diff) or add a twin
`Formattable defaultForFormattable(f)`? Decision: **wrap with `RawExpr.of`** —
the twin adds a second API surface for no gain. Future work can flip
`defaultFor` itself if another call site joins.

**Q2.** `null` literal at `RecordSourceMapping.java:460` — `RawExpr.of("null")`
or a named `Formattable.NULL` constant? Decision: `RawExpr.of("null")` —
smallest change; a named constant is a future ergonomic nicety.

---

## Units

### U1 — `Component.initializer` String → `Formattable` + delete the `String` overload

**Scope.**

- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Component.java`:
  - Change the record component `String initializer` to `Formattable
    initializer` (nullable, `null` = no initializer). Drop the
    `null → ""` compact-ctor coercion.
  - Delete `Builder.initializer(String)`; add
    `Builder.initializer(Formattable)`.
  - Rewrite the class Javadoc — replace "The initializer string is an
    escape-hatch raw string rendered verbatim" with the positional-template
    language; note `null` = "no initializer emitted."
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/SourceRenderer.java`
  (`renderField` + collect phase):
  - `if (f.initializer() == null)` → return without the `= <init>` clause;
    otherwise render via `f.initializer().render(ctx)` and append.
  - The collect pass calls `f.initializer().collect(ci)` (the `RawExpr`
    contract already walks its arg tree and registers `TypeRef`s).
- `codegen/src/test/java/bijnagte/recordbuf/codegen/source/ComponentTest.java`
  and any `RecordSourceMapping`/`EnumSourceMapping` renderer test that pins
  `Component.initializer(...)` returning a `String` — update to assert on the
  `Formattable` (or on `render(...)` for the source string).

**Test scenarios.**

1. `./gradlew :recordbuf-codegen:compileJava` green — no test references the
   deleted `String` slot.
2. No `Component` test pins the empty-string default: any test that
   previously asserted `.initializer() = ""` on a fresh
   `Component.builder().build()` should now assert `.initializer() == null`.

### U2 — Migrate the four `.initializer(...)` call sites + dead-code sweep

**Scope.**

Migrate each of the four known call sites per the table in R8-init-3. Then a
sweep for leftovers:

```bash
grep -rn '\.initializer(' generator/src/main/java/bijnagte/recordbuf/plugin/writer/
```

should show only `Formattable`-typed callers. Also a sweep for the `String`-only
initialiser contract:

```bash
grep -rnE '\.initializer\("' generator/
```

should return zero matches.

**Test scenarios.**

1. `./gradlew build` green — spotless, checkstyle, NullAway, all tests,
   `:recordbuf-fixtures:compileJava` green.
2. `./gradlew :recordbuf-generator:installDist` green; a fresh `protoc
   --recordbuf_out` on `int.proto` / `string.proto` produces the same Java
   files as `main` @ `b8bf6d4` (byte-identical after `spotless`).
3. The `EnumSourceMapping BY_NUMBER` test (if any) renders the same source
   post-plan as pre-plan.
4. AE1–AE4 hold.

### U3 — Commit

**Scope.** A single commit for U1+U2 with the `008` plan status flipped to
`complete`. The `008` plan is the last of the field-layer migration series;
after it the writer package is fully positional.

**Test scenarios.**

1. `./gradlew build` green.
2. AE1–AE4 all pass.
3. The `008` plan `status: complete` + `updated: <today>` in its header.

---

## Sources

- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Component.java` — the
  field-declaration node today; `Component.initializer` is the only thing
  changing in this plan.
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/LocalVar.java` —
  the *target* shape: `LocalVar.init` is a `Formattable` (the contract we're
  aligning `Component` to).
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/RawExpr.java` +
  `RatExprTest.java` — the positional-templating `@N` system that the new
  call sites use directly; `RawExpr.of("...")` with no args is already used
  as a raw-string `Formattable` (test at `RawExprTest.java:80`).
- `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Formattable.java`
  + `RawExprTest.java` — the `Formattable` interface, its positional
  `@N`/`@@` syntax, and the fail-fast out-of-bounds check.
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/EnumSourceMapping.java:80-110`
  — the `BY_NUMBER` pre-render workaround this plan deletes; the comment at
  line 86 explicitly names `Component.initializer(String)` as the reason for
  the workaround.
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java:440-475`
  — the three field-initialiser call sites (field default, oneof storage
  `null`, UF carrier `empty()`), the last of which is a string concat the
  positional `@N` system was created to kill.
- `2026-11-13-004-feature-rawexpr-positional-templating-plan.md` — the `@N`
  system this plan completes for the field layer.
- `2026-11-13-005-feature-call-site-postemplating-plan.md` — the positional
  `RawExpr` adoption for the expression layer; the pattern this plan applies
  to fields.
- `2026-11-13-006-refactor-mapper-type-ref-migration-plan.md` — the
  `*SourceMapping` migration that retired the *String* type positions in
  favor of `TypeRef`; this plan is the field-initialiser analogue.
