---
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
status: requirements-only
date: 2026-11-13
---

# RawExpr Positional Templating — Brainstorm

## Goal

Replace the string-splice + manual `ci.use()` pattern used to embed type FQNs in
generated source with a **positional template** model. A `RawExpr` holds a
template string with `@0`, `@1`, `@2` positional placeholders filled by
positional args. When an arg is a `TypeRef`, it is resolved against a
`ResolutionContext` at render time — never spliced into the template. The field
name and all other dynamic fragments are also positional args, so the template
string is fully self-contained — no string concatenation in call sites.

---

## Current State (before)

`RawExpr` holds a template + `List<TypeRef>` with `%d`/`%%` substitution.
`LocalVar.init` is a `String`. Type FQNs are registered separately via
`ci.use(...)` calls:

```java
// WktSourceMapping — current style:
ci.use("java.util.HashMap");
ci.use("java.util.ArrayList");
ci.use("com.google.protobuf.ByteString");

LocalVar local(TypeRef.of(HASH_MAP), "_m", "new " + HASH_MAP + "<>()");
LocalVar local(TypeRef.of(ARRAY_LIST), "_items", "new " + ARRAY_LIST + "<>()");
```

```java
// InteropSourceMapping — current style:
ci.register(TypeRef.of(ARRAY_LIST));
ci.use("com.google.protobuf.ByteString");

Element.expr(
    ExprStatement.of(rawExpr(
        name + ".add((%d) %d.parse(%d.Wrap.%d, %d))",
        javaShort, wkt, wrapKind(f.typeUrl()), read)));
```

Problems:
- **String concatenation** for field names (`name + ".add(..."`) is hard to read.
- **Manual import registration** (`ci.use("com.google.protobuf.ByteString")`) is
  error-prone and easy to forget.
- **`%d` auto-increment** is ambiguous — you can't reference the same arg twice
  without a named placeholder, and the template is not self-documenting about
  which arg is which.

---

## Target State (after)

```java
// WktSourceMapping — proposed:
LocalVar.builder(HASH_MAP, "_m")
    .build("new @0<>()", TypeRef.of(HASH_MAP));

LocalVar.builder(ARRAY_LIST, "_items")
    .build("new @0<>()", TypeRef.of(ARRAY_LIST));
```

```java
// InteropSourceMapping — proposed:
Element.expr(
    RawExpr.of(
        "@0.add((@1) @2.parse(@2.Wrap.@3, @4))",
        "badge",                      // @0: String — field name
        TypeRef.of(SHORT),            // @1: TypeRef — resolved at render
        TypeRef.of(BADGE),            // @2: TypeRef — resolved at render
        "BadgeWrapValue.FOO",         // @3: String — literal enum constant
        TypeRef.of(FIELD_VALUE_TYPE)  // @4: TypeRef — WKT type
    )
);
```

No `ci.use(...)` calls needed — `TypeRef` args self-register via `collect(ci)`.
Template string is a pure literal; all dynamic content is in positional args.

---

## Design Decisions

### D1. `Formattable` — common interface for renderable expression pieces

```java
public interface Formattable {
  /** Render this piece to source text, resolving any type references against ctx. */
  String render(ResolutionContext ctx);

  /** Contribute any type references to the import collector. */
  void collect(TypeCollector ci);
}
```

Two methods: one for the **render phase**, one for the **import-collection
phase**. Both traverse the same structure. Separating the two is cheaper than a
single combined call — rendering is hot (per generated line), collection is one
pass upfront.

`TypeRef` implements `Formattable`:
- `render(ctx)` delegates to `resolved(ctx.declaringPackage(), ctx.collidedShortNames())`
- `collect(ci)` calls `ci.register(fqn)` or `ci.useFqn(fqn)`

`RawExpr` implements `Formattable`:
- `render(ctx)` applies the template, resolving each arg via `instanceof` dispatch
- `collect(ci)` iterates its `List<Object>` args and dispatches via `instanceof`:
  - `String` → skip (no import needed)
  - `TypeRef` → `ci.register(tr)`
  - `RawExpr` → recurse into `.collect(ci)`
  - `Formattable` → `.collect(ci)`

`String` is **NOT** a `Formattable` — it is handled as a trivial case inside
`RawExpr`'s render/collect via `instanceof String`. No wrapper class.
`RawExpr.of(template, "text", TypeRef.of(F))` works directly.

**Codegen module does not use `@RecordBuilder`** — hand-written builders give
full control over the `build(...)` signature, which is needed for
`build(template, Object... args)`.

### D2. `RawExpr` — template + positional args

```java
public record RawExpr(String template, List<Object> args) implements Formattable {

  /** Create from a template and varargs. */
  public static RawExpr of(String template, Object... args) {
    return new RawExpr(template, List.of(args));
  }

  @Override
  public String render(ResolutionContext ctx) {
    // Scan template char-by-char.
    // `@N` (N is a non-negative integer) → args[N], rendered via render():
    //   - String        → toString()
    //   - TypeRef       → .render(ctx)
    //   - RawExpr       → .render(ctx)
    //   - Formattable   → .render(ctx)
    // `@@` → literal `@` (escape)
  }

  @Override
  public void collect(TypeCollector ci) {
    // Iterate args; no template parsing needed.
    //   - String        → skip
    //   - TypeRef       → ci.register(tr)
    //   - Formattable   → .collect(ci)
  }
}
```

**Positional syntax:**
- `@N` where `N` is a non-negative integer: substitute `args[N]` rendered
  against `ctx`
- `@@` → literal `@` (escape; rare — needed only if generated code emits `@`)
- `@0` can appear multiple times — both resolve to the same arg
- If a template has more `@N` positions than args → fail-fast at construction
- If a template has fewer `@N` positions than args → extras ignored

**Why `@` not `%d`:**
- Does not collide with Java string concatenation or `%%` escape
- Visually distinct from identifiers (`[a-zA-Z_]`)
- Reads clearly: `@0`, `@1` are unambiguously "position zero", "position one"
- No collision with protobuf annotation syntax

### D3. Builder pattern with `build(template, Object... args)`

Types that hold a `Formattable` (or will after migration) use hand-written
builders. The `build` method accepts the format template + args, constructing
the `RawExpr` internally:

```java
// LocalVar — multiple fields, builder makes sense:
public static final class Builder {
  private final List<Annotation> annotations = new ArrayList<>();
  private TypeRef type;
  private final String name;
  private Formattable init;  // ← the field that was "String init"

  public static Builder of(TypeRef type, String name) {
    return new Builder(type, name);
  }

  public Builder annotation(Annotation a) {
    annotations.add(a);
    return this;
  }

  /** Finish: construct the expression from template + positional args. */
  public LocalVar build(String initTemplate, Object... initArgs) {
    this.init = RawExpr.of(initTemplate, initArgs);
    return new LocalVar(List.copyOf(annotations), type, name, init);
  }
}

// Usage:
LocalVar.builder(HASH_MAP, "_m")
    .build("new @0<>()", TypeRef.of(HASH_MAP));
```

For **simple types** with a single `Formattable` field (like `ExprStatement`),
a factory is more ergonomic than a builder:

```java
// ExprStatement — single Formattable field, factory is fine:
// Element.java:
static ExprStatement expr(String template, Object... args) {
  return new ExprStatement(RawExpr.of(template, args));
}
// Usage:
Element.expr("return 42;")
Element.expr("@0.bar", javaShort)
```

**Why different shapes:** `LocalVar` has type + name + annotations + init —
four fields, so a builder is warranted. `ExprStatement` has one field —
`Formattable expr` — so a factory with varargs is cleaner. The builder pattern
is for types where multiple fields need sequential setup; the factory is for
single-field types.

### D4. `ExprStatement` — `Formattable` field

`ExprStatement` already holds a `RawExpr` (from U2 work). The change is that
`RawExpr` becomes `Formattable`, and `ExprStatement` accepts any `Formattable`:

```java
// Current:
public record ExprStatement(RawExpr expr) implements Element {}

// After:
public record ExprStatement(Formattable expr) implements Element {}
```

`Element.expr(...)` factories delegate to `RawExpr.of(template, args...)`
for the common case. The `Formattable` field type is a future-proofing detail —
today only `RawExpr` is passed, but if a new `Formattable` subtype is needed
later, no change to `ExprStatement` is required.

### D5. `LocalVar.init` becomes `Formattable`

`LocalVar` currently holds `init` as a `String` (pre-built initializer
expression). With the `Formattable` model, `init` becomes a `Formattable` —
usually a `RawExpr` with `TypeRef` args so the type FQNs self-register:

```java
// Current:
ci.use("java.util.HashSet");
LocalVar local(TypeRef.of(HASH_SET), "_seen", "new " + HASH_SET + "<>()");

// After:
LocalVar.builder(HASH_SET, "_seen")
    .build("new @0<>()", TypeRef.of(HASH_SET));
```

`Element.local` factory (the `Element.java` static helpers) changes from
`local(fqn, name, init: String)` to `local(fqn, name, initTemplate: String,
initArgs: Object...)` — the same shape as `RawExpr.of`:

```java
// Element.java:
static LocalVar local(TypeRef fqn, String name, String initTemplate, Object... initArgs) {
  return LocalVar.builder(fqn, name).build(initTemplate, initArgs);
}
```

Simple string inits (no `TypeRef`) still work: `Element.local(fqn, name, "true")`.

### D6. Collect-phase unification

After D3–D5, `collectBody` in `SourceRenderer` becomes a clean pass over
`Formattable` nodes:

```java
private void collectBody(Element e) {
  switch (e) {
    case LocalVar lv -> {
      ci.use(lv.type());                  // type is TypeRef → register
      lv.init().collect(ci);              // init is Formattable → collect its TypeRefs
    }
    case ExprStatement es -> es.expr().collect(ci);
    case If i -> {
      i.body().forEach(this::collectBody);
      i.elseIf().forEach(ei -> ei.body().forEach(this::collectBody));
      i.elseBody().forEach(this::collectBody);
    }
    case While w -> w.body().forEach(this::collectBody);
    case For f -> f.body().forEach(this::collectBody);
    case Switch sw -> { /* recurse into case/default bodies */ }
    default -> {}
  }
}
```

The `collect(ci)` call on `Formattable` is the universal entry — the same
call works for `LocalVar.init` and `ExprStatement.expr`. No more
`collectExpr` helper — it's just `collect(ci)` on whatever `Formattable`
the statement holds.

---

## Scope

**In scope:**
- `Formattable` interface in `codegen/source/`
- `TypeRef` implements `Formattable`
- `RawExpr` updated: `@N` positional, `Object...` args, `collect(ci)`,
  fail-fast for out-of-bounds `@N`
- Hand-written builders for `LocalVar` (`LocalVar.builder(type, name).build(template, args...)`)
- `LocalVar.init` changed to `Formattable`
- `ExprStatement.expr` changed to `Formattable`
- `Element.expr(template, args...)` and `Element.local(fqn, name, template, args...)` factories
- `SourceRenderer`: remove `collectExpr`, call `collect(ci)` on `Formattable`;
  `renderStatement` calls `formattable.render(ctx)` for `ExprStatement`/`LocalVar`
- `Method.Builder.body()` accepts `Element...` (unchanged — bodies already are
  `List<Element>`)
- Migration of existing `ci.use` / `ci.useFqn` call sites:
  `WktSourceMapping`, `CodecSourceMapping`, `InteropSourceMapping`,
  `EnumSourceMapping`, `AvroConverterSourceMapping`, `RecordSourceMapping`
- Unit tests: `RawExpr.render()` with mixed args, `RawExpr.collect()` with
  mixed args, `LocalVar.builder().build()` factory

**Out of scope (separate plans):**
- `Return.expr` and `Throw.expr` becoming `Formattable` — follow-up if needed
- `ResolutionContext` / `ResolutionProvider` wiring — already in place
- `TypeCollector` API shape — unchanged
- Generator entry-point wiring — unchanged
- Formatter integration — unchanged

---

## Open Questions

1. **Fail-fast for out-of-bounds `@N`:** throw `IllegalArgumentException` at
   `RawExpr.of` (construction time, programmer error) or `IllegalStateException`
   at render (runtime)? → *Recommendation: throw at construction — the template
   and args are co-constrained at the call site.*

2. **`@RecordBuilder` interaction:** the codegen module does NOT use
   `@RecordBuilder` — hand-written builders. The generated-output side uses
   `@RecordBuilder` (from `io.soabase`). The two are independent. No conflict.

3. **Backward compat:** existing `RawExpr.of(template, TypeRef... args)` is
   kept as an overloaded convenience — `RawExpr.of(String, TypeRef...)` delegates
   to `RawExpr.of(String, Object...)`. Not strictly needed but reduces churn
   during migration.

4. **`collect(ci)` on nested `RawExpr`:** when args contain a nested `RawExpr`,
   `collect` recurses — this is a tree-walk through the `args` list. For
   typical generated code the nesting depth is 1–2 (a `RawExpr` with a
   `LocalVar.init` that is another `RawExpr`), so recursion cost is negligible.

---

## Next Steps

1. Implement `Formattable` interface
2. Make `TypeRef` implement `Formattable`
3. Update `RawExpr`: `@N` positional + `Object...` args + `collect(ci)` +
   `render(ctx)` + fail-fast
4. Create `LocalVar.builder(type, name)` with `build(template, args...)`
5. Change `LocalVar.init` to `Formattable`
6. Change `ExprStatement.expr` to `Formattable`
7. Update `Element` factories: `expr(template, args...)`,
   `local(fqn, name, template, args...)`
8. Update `SourceRenderer`: `collectBody` → `Formattable.collect`;
   `renderStatement` → `Formattable.render`
9. Migrate writer call sites: `WktSourceMapping`, `CodecSourceMapping`,
   `InteropSourceMapping`, `EnumSourceMapping`, `AvroConverterSourceMapping`,
   `RecordSourceMapping`
10. Unit tests: `RawExpr` render/collect with mixed args;
    builder factories
