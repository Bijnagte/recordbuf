---
title: "The flat oneof JSON discriminator defaults to the oneof name; the @JsonUnwrapped component carries an internal property name to avoid a Jackson model-property collision"
date: "2026-11-26"
category: docs/solutions/design-patterns
module: "bijnagte.recordbuf.plugin.writer.RecordSourceMapping"
problem_type: design_pattern
component: codegen
severity: medium
applies_when:
  - "Naming the flat Jackson discriminator for a oneof whose @JsonUnwrapped component is named after the same oneof"
  - "Defaulting a generated oneof's chosen-member discriminator to the oneof's own name, or changing how it is named in flat JSON"
  - "Debugging a Jackson \"Conflicting getter definitions for property ...\" failure on a generated record that flattens a oneof"
tags:
  - jackson
  - oneof
  - json
  - discriminator
  - jsonunwrapped
  - codegen
  - generator
  - recordbuf
---

# The flat oneof JSON discriminator defaults to the oneof name; the @JsonUnwrapped component carries an internal property name to avoid a Jackson model-property collision

## Context

`recordbuf.jackson` flattens a oneof into the enclosing record's JSON object: the set member appears as a top-level property under its own JSON name, plus a **discriminator** property naming *which* member is set (`{ "choice": "name", "name": "hello" }`). For most of the project's history the discriminator's default JSON property was `<oneofName>Type` (`choiceType`); the `recordbuf.json_oneof` option overrides it (e.g. `kind`).

The goal here was to make the **default** discriminator the bare oneof name (`choice`), dropping the `Type` suffix. That looks like a one-line emitter change — but it is not, and the reason is a Jackson model-property rule that is easy to miss.

## The constraint (the Jackson gotcha)

The flat oneof's **component** is a `@JsonUnwrapped` record component *named after the oneof* (`Choice choice`). In Jackson's model a record component's name is a **model property** even when the component is `@JsonUnwrapped`. So the generated record exposes a model property `choice` (from the component's `choice()` accessor) *and* — if the discriminator also uses `@JsonProperty("choice")` — a second property of the same name. Jackson disambiguates on the property **name**, not the source, and fails:

```
Conflicting getter definitions for property "choice": public ... choice() vs ... choiceType()
```

The naive fix — rename only the discriminator's *Java method* so it differs from the component's accessor — is **not sufficient**. The collision is on the *property name* (`choice`), which the discriminator's `@JsonProperty("choice")` still produces regardless of the method name. Renaming the method (`choiceType()`) leaves `@JsonProperty("choice")` colliding with the component's `choice` model property.

## The fix

Give the `@JsonUnwrapped` **component** a distinct **internal** `@JsonProperty`, freeing the bare oneof name for the discriminator. The generator emits, in `emitOneofJsonSurface`:

```java
public record Compat(
    @JsonProperty("oneofField$") @JsonUnwrapped @Nullable OneofField oneofField,  // internal name; never rendered
    @JsonIgnore @JsonAnyGetter @JsonAnySetter @Nullable Map<String, JsonNode> jsonExtras) {

  @JsonProperty("oneofField")   // bare — the actual discriminator
  @Nullable @JsonCreator
  public static Compat fromJson(@JsonProperty("oneofField") String oneofFieldType, ...) {
    // ... JsonOneof.select(...) over the members, then delegate to the canonical constructor
  }
}
```

`RecordSourceMapping` puts `@JsonUnwrapped` **and** `@JsonProperty(componentName + "$")` on the oneof component (the `$` suffix makes the internal name distinct from the bare oneof name), and keeps the discriminator's `@JsonProperty(discrimProperty)` on the `@JsonCreator` factory param — `discrimProperty` is the oneof name by default, the `recordbuf.json_oneof` override otherwise. The Java method stays `<oneofName>Type` (`choiceType()`).

## Why this works

`@JsonUnwrapped` emits **only the value's** properties into the enclosing object — it never emits the wrapper component's *own* name. So the component's `@JsonProperty("oneofField$")` is pure **model-level** disambiguation: it changes the property name Jackson uses for the collision check (from `oneofField` to `oneofField$`), but that name is never written to the JSON. The rendered output keeps the bare discriminator: `{ "oneofField": "string", "stringValue": "hi" }`. The `$`-suffixed name is safe to reserve because protobuf identifiers cannot contain `$`, so no real field can collide with it.

## What didn't work

- **Drop the `Type` suffix from the discriminator, nothing else** (the "one-line" plan). The `@JsonProperty("<oneof>")` then collides with the `@JsonUnwrapped` component's `<oneof>` model property — the fixtures build failed with `Conflicting getter definitions for property "when"` across the fixture golden and round-trip suite (150+ tests).
- **Rename only the discriminator's Java method.** The collision is on the *property name*, not the accessor name; the `@JsonProperty("<oneof>")` still collided.
- **Polymorphic deserialization** (the `@JsonTypeInfo`/`@JsonSubTypes`/`@JsonTypeName` idiom — the Baeldung approach). It is the standard pattern, but it fights the **flat** layout: the discriminator key would have to double as a *type id*, and the members are records (not subtypes of a shared base), so it needs per-member `@JsonTypeName` wiring and — more fundamentally — changes the flat shape to a type-id-driven one. The flat `@JsonUnwrapped` surface is the design; this idiom does not fit it.

## How to apply

- When a flattened oneof's discriminator uses the same name as the oneof (the default), the `@JsonUnwrapped` component **must** carry a distinct internal `@JsonProperty` — renaming the Java method alone will not clear a Jackson model-property collision.
- Reserve an internal name that cannot appear as a real JSON key (the `$` suffix) for the component; since `@JsonUnwrapped` never renders the component's own name, giving it one purely for model disambiguation is safe.
- Verify against the **rendered** JSON (the fixtures golden matrix), not just that the code compiles — the discriminator key and value are what must be right, and the internal `$` name must not leak into the output.

## References

- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java` — `emitOneofJsonSurface`: the component's `@JsonProperty(componentName + "$")` and the discriminator's `@JsonProperty(discrimProperty)` on the `@JsonCreator` factory.
- `generator/src/test/java/bijnagte/recordbuf/plugin/OneofJsonSurfaceTest.java` — the emitted-surface contract (component carries the `$` internal name; the discriminator is the bare oneof name).
- `docs/plans/2026-11-26-001-feat-oneof-bare-discriminator-name-plan.md` — the planning note, including the as-implemented revision (why "one-line" was infeasible).
- `docs/solutions/design-patterns/recordbuf-jackson-json-surface.md` — the broader flat-surface design (canonical shapes, unknown-property retention) this sits within.
