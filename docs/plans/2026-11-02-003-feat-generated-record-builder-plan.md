---
title: Generated Record Builder - Plan
type: feat
date: 2026-11-02
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
---

# Generated Record Builder - Plan

## Status: DONE (feat-generated-builder)

**Local commit on branch `feat/generated-builder`; no push / no PR / no CI-watch (no git remote).**

Every generated message record (top-level + nested) now ships a nested static value-only `Builder`
plus a `builder()` factory and a `toBuilder()` accessor, emitted in
`generator/src/main/java/bijnagte/recordbuf/plugin/CodeGenerator.java` (`addBuilder` + `freshDefault`
+ `copyAssignment` helpers). `./gradlew build --rerun-tasks` is green (38 tests):
- `BuilderEmissionTest` (8 source-shape assertions: R1-R6 + enum-negative + top-level+every-message).
- `MessageBuilderTest` (3 end-to-end: `builder()` + fluent setters + `build()`, proto3 defaults,
  `toBuilder()` defensive copy).
- All fixture families (inc. `map`/`repeated`/`bytes`/`optional`) recompiled with a builder and
  round-trip unchanged.

Container convenience adders, child-builder composition, nested builder ergonomics, and `build()`
validation are consciously deferred to follow-up work.

## Goal Capsule

**Objective:** Every `recordbuf`-generated message record ships a fluent nested `Builder` plus `builder()`/`toBuilder()`/`build()`, so consumers no longer hand-wire positional constructor arguments for large messages.

**Authority hierarchy:** This plan > the requirements-only origin > repo conventions. The origin (`docs/plans/2026-11-02-003-feat-generated-record-builder-plan.md`, same file, this run enriched in place) is the Product Contract; KTDs and Units below are the Planning Contract. Generated code must stay dependency-free (only `java.*` + runtime `bijnagte.recordbuf.runtime`).

**Stop conditions:** `./gradlew build` is green (compile + test, including NullAway at error severity in both `:recordbuf-generator` and `:recordbuf-runtime`); the regenerated fixtures with builders compile and round-trip; a builder-driven construction test passes.

---

## Product Contract

### Summary

Generated records get a nested `Builder` with value setters named after each component, a static `builder()` factory, an instance `toBuilder()`, and a terminal `build()`.

### Problem Frame

Generated messages already use a canonical positional constructor. Records with many fields are verbose and error-prone to construct. `toBuilder()` on records is not available; users need a builder to avoid positional constructor call sites.

### Requirements

- **R1. Nested Builder.** Every generated message record (top-level and nested, including the synthetic map-entry record) declares a nested `public static final class Builder`. Enums get no Builder.
- **R2. Fluent value setters.** The Builder exposes one fluent setter per record component, named after the component's `javaName` and typed as the component's Java type (e.g. `int bar`, `List<Item> items`, `Meta meta`, `Optional<String> optional`, `@Nullable String comment`). Each setter assigns the value and returns the Builder so calls chain.
- **R3. Static factory.** Every record with a Builder also declares `public static Builder builder()` returning a fresh Builder.
- **R4. `toBuilder()` copy.** Every record with a Builder also declares `public Builder toBuilder()` which returns a Builder pre-populated with the current instance's values, with defensive copies for mutable components (containers and `byte[]`) so subsequent edits don't mutate the source record.
- **R5. Terminal `build()`.** The Builder declares `X build()`, where `X` is the record's canonical name; it constructs the record via the canonical constructor.
- **R6. Javadoc parity.** Each Builder setter carries the component's Javadoc (if the field has one); `builder()`, `toBuilder()`, and `build()` each carry a one-line Javadoc describing their role.
- **R7. Consistency.** Builders generate on every record (top-level + nested + map-entry). No per-message opt-out. No opt-in proto option.
- **R8. Dependency-free output.** Generated Builder code imports only `java.*` and `bijnagte.recordbuf.runtime.*`.
- **R9. No codec change.** The Builder does not touch encode/decode paths.
- **R10. No source break.** Existing canonical constructor and codec remain usable; `builder()`/`toBuilder()` are purely additive.

### Scope Boundaries

#### In scope
- Nested `Builder` class per record; `builder()`, `toBuilder()`, `build()`, per-component fluent setters; Javadoc; generated-output compile and a round-trip test.
- Applying to nested records and map-entry records (recursive generation).

#### Deferred to Follow-Up Work
- Container convenience adders (`addX(T)`, `addAllX(Iterable<T>)` on repeated; `putX(K, V)` on map) — v1 provides the assigner `name(List<T>)` / `name(Map<K, V>)` only. Revisit if users report the assigner shape as painful for repeated/map fields.
- Child-builder composition / nested-builder sugar. v1 is strictly value-only.
- Per-message opt-out (e.g. a `skip_builder` annotation or field-level option).
- Builder equality / hashcode (a Builder is a construction aid, not an equality citizen).
- Immutable snapshot view returned from `build()` (build is terminal and returns the record itself).
- Builder validation hooks (`onBuild()`, `validate()`).
- Generated `toString()` on the Builder.
- Kotlin/Gradle DSL Builder support (the generator emits Java today; other languages out of scope).
- Runtime Builder support (the Builder is generated per-record; no central runtime helper is required).

#### Explicit Non-goals
- No proto option. No runtime dependency. No changes to codec / encoder / decoder / canonical constructor.

---

## Planning Contract

### Key Technical Decisions

**K1. Builder shape = nested static class with mutable fields.** A `Builder` nested inside each record holds one mutable field per record component (containers start as fresh `ArrayList`/`HashMap`; singular fields start at the component's default: Java default for primitives, empty string for singular strings, `null`/`Optional.empty()` for optional/present-able). This matches the generated `Message` model's `@RecordBuilder` philosophy already in use but is *not* the runtime `io.soabase.recordbuilder.core.RecordBuilder` itself — that's an internal plugin-tool only; the generated output stays dependency-free (R8).

**K2. Setter = one fluent method per component, named after the component.** For each record component `f` with Java type `T`, emit `public Builder f(T f) { this.f = f; return this; }`. Containers use `List<T>`/`Map<K, V>` (assigner only; adders deferred per KTD). The value-only contract is preserved: the setter takes the component's own type, so there's no recursion and no composition.

**K3. `build()` calls the canonical constructor** `new X(c1, c2, …, cn)` in the record's component order. The generated record's canonical constructor is the only construction surface for Builder output — no reflection at runtime.

**K4. `toBuilder()` deep-copies mutable components.** For a Builder created via `toBuilder()`, lists/maps/byte[] are copied to fresh instances so subsequent edits to the returned Builder don't affect the source record. The implementation can be a simple `new ArrayList<>(this.items)` / `new HashMap<>(this.map)` per container; scalars copy by value.

**K5. Generated builders are unconditional and recursive.** All records including nested and map-entry get a Builder. The generator walks `Message` + `nestedMessages` + the map-entry record produced by `CodeWriter.writeNestedMessages()` / `writeMessageEntry()`. No opt-out.

**K6. Javadoc propagation.** Each setter's Javadoc block is the raw comment (if any) of the field — no transformation. Class-level and factory Javadoc are boilerplate ("@apiNote This builder is generated by recordbuf. Use {@link #build()} to construct an instance.", etc.).

**K7. Generated builders emit in the same source file as the record.** Builders live inside the record body via `codeWriter.writeRecord()`; no additional source file per message.

### High-Level Technical Design

```
┌─────────────────────────┐   ┌──────────────────────┐
│  record X {              │   │  Builder (nested)     │
│    int bar;              │──▶│  int bar;             │
│    Item meta;            │   │  Item meta;           │
│    List<String> items;   │   │  ArrayList<String>    │
│    Builder X();          │   │    items;             │
│    static Builder        │   │  Builder bar(int b);  │
│      builder();          │   │  Builder meta(Item m); │
│    Builder toBuilder();  │   │  Builder items(List); │
│  }                       │   │  X build();           │
│                          │   │  ...                 │
│  // (existing codec,     │   └──────────────────────┘
│  encode/decode, etc.)    │
└─────────────────────────┘
```

### Assumptions

- The generated `Builder` class is the only user-facing construction API added by this feature; existing positional constructors coexist.
- Generated-code consumers already use Java 17+ (the generator's target; the plugin's own toolchain is Java 25, but generated output targets a wider baseline — see `AGENTS.md` "Java level").
- `builder()` defaults match codec defaults (0/empty/null) — verified by the existing fixture round-trips, which will now exercise `builder()` on every message.

---

## Implementation Units

### U1. Builder core generation (scalar, message, enum, optional, repeated, map entry records)

- **Goal:** Emit the nested `Builder` class + `builder()` + `toBuilder()` + `build()` + per-component fluent setters + Javadoc inside every generated record (including nested + map-entry).
- **Requirements:** R1, R2, R3, R4, R5, R6, R7, K1–K7
- **Dependencies:** none
- **Files:**
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/CodeWriter.java` — add a private `writeBuilder(Message msg, TypeMapper types)` method (or inline into `writeRecord`), emitting the Builder class, `builder()`, `toBuilder()`, `build()` inside the record body via `out.classBody(BuilderClass::class.java) { … }` or `out.rawBlock()` (matching existing style).
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/*` — extend any helper needed for the builder (per-component default-value logic; setter generation for each `Field` shape).
  - `generator/src/test/java/bijnagte/recordbuf/plugin/writer/*` — unit test asserting generated output contains `Builder`, `builder()`, `toBuilder()`, fluent setters, `build()`, and Javadoc where a field has a comment.
- **Approach:**
  - Generate the nested `Builder` as `out.classBody("Builder") { … }` (or a new `CodeWriterBuilder` helper).
  - For each record component:
    - Emit a field: `private T type name;` for singular; for repeated `private List<T> items = new ArrayList<>(4);` (or `List.of()`); for map `private Map<K, V> map = new HashMap<>();`.
    - Emit a fluent setter: `public Builder name(T name) { this.name = name; return this; }` — for repeated a `Builder items(List<T> items)` (assigner only; no `addX` in v1 per KTD); for map `Builder map(Map<K, V> map)`.
  - Emit `static Builder builder() { return new Builder(); }` (public).
  - Emit `Builder toBuilder() { Builder b = builder(); b.bar = this.bar; … return b; }` with defensive copy for containers/byte[].
  - Emit `X build() { return new X(this.bar, this.meta, this.items == null ? List.of() : items, …); }` — canonical ctor.
  - Emit Javadoc on setters from the field's `comment` (via the existing comment helper, see `writer/*` comment-rendering path).
- **Patterns to follow:** `CodeWriter.writeRecord()` already generates the canonical constructor and codec fields; the Builder is added inside the same `out.classBody("X") { … }` block or immediately after it via `out.rawBlock("public static final class Builder { … }")`.
- **Test scenarios:**
  - Given `Sample(String foo, int bar, optional String baz)`: generated `Builder.sample(int bar)` sets `bar` and returns the builder; `Sample.builder().foo("a").bar(42).build()` yields a `Sample` with those values.
  - Given `Sample foo(int)`, `bar(int)`: `toBuilder()` followed by `build()` yields an equal `Sample`.
  - Given repeated field `List<Item> items`: `Sample.builder().items(List.of(a, b)).build()` produces a `Sample` whose `items()` equals `List.of(a, b)`.
  - Given map field `Map<String, Item> items`: builder accepts a `Map<K, V>` and `build()` produces a `Sample` whose `items()` equals a copy of that map (defensive copy at `toBuilder()`; `build()` passes through to canonical ctor which does `copyOf`).
  - Javadoc: a field with a comment produces a setter with the comment rendered above it; a field without a comment has a bare setter.
- **Verification:** `./gradlew :recordbuf-generator:test` green; a generated sample record with builders compiles under `./gradlew :recordbuf-fixtures:compileJava` (after `installDist` + regenerate).

### U2. Fixture proto + integration verification

- **Goal:** Add a fixture exercising a builder across scalar / repeated / optional / nested / map components. Regenerate the fixture's Java. Run a builder-driven round-trip test.
- **Requirements:** R9, R10, and integration proof of R1–R8
- **Dependencies:** U1
- **Files:**
  - `fixtures/src/main/proto/*` — add or extend an existing message to cover: scalar `String foo`, integer `int bar`, singular message `Meta meta`, repeated `repeated Item items`, optional `optional String baz` (proto3 optional), and a map `map<string, Item> byName`.
  - `fixtures/src/main/java/bijnagte/recordbuf/fixture/*` — regenerated.
  - `fixtures/src/test/java/bijnagte/recordbuf/fixture/*BuilderTest.java` — new test class exercising `builder()`, `toBuilder()`, all five component shapes, and `build()`.
- **Approach:**
  - Install `:recordbuf-generator` (`./gradlew :recordbuf-generator:installDist`).
  - Regenerate fixtures via protoc `--recordbuf_out=…` (the standard command shown in `AGENTS.md`).
  - Write a `BuilderTest` that constructs a fixture message via the Builder, encodes it via the existing codec, decodes it, and asserts equality (and/or that `toBuilder().build() == original`).
  - Also exercise a nested-message builder (a message field whose value is built via the *nested type's* Builder) — but strictly value-only: the nested value is passed by value, no recursive Builder composition.
- **Approach continued:**
  - Assert that a fresh `Sample.builder().build()` equals the canonical default (0, "", empty lists/maps, `Optional.empty()` for optional fields).
  - Assert `toBuilder()` returns a Builder whose `build()` equals `this`.
- **Patterns to follow:** Existing `fixtures/src/test/java/bijnagte/recordbuf/fixture/*Test.java` files (codec round-trip style).
- **Test scenarios:**
  - **Happy path** — `Sample.builder().foo("a").bar(42).meta(Meta.builder().x(1).build()).items(List.of(new Item(...), new Item(...))).byName(Map.of("a", new Item(...))).build()` produces a `Sample` that round-trips through `encode`/`decode`.
  - **`toBuilder` immutability** — `Sample.builder().items(List.of(a, b)).toBuilder().items(List.of(c)).build()` yields a Sample with items `[c]` while the source sample's items() are unchanged (the source was built, not held as a Builder; the test asserts the original Sample's `items()` are `[a, b]`, not `[c]`).
  - **`builder()` fresh defaults** — `Sample.builder().build()` equals `new Sample("", 0, null, List.of(), Map.of(), Optional.empty())` (or whatever the canonical default shape is after U1).
  - **Nested builder composition (value-only)** — building a nested `Meta` via `Meta.builder()...build()` and passing that value into `Sample.builder().meta(m).build()` produces a `Sample` whose `meta()` is `m`.
- **Verification:** `./gradlew :recordbuf-fixtures:test` green.

### U3. Documentation

- **Goal:** Mention the Builder API in user-facing docs (README "Getting started" example using `builder()`; a one-line note in any "generated API surface" section).
- **Requirements:** R6 (Javadoc parity on generated code), and user-facing discovery
- **Dependencies:** U1 (so the example code is real)
- **Files:**
  - `README.md` — add a `### Builders` subsection under "Generated API Surface" listing `builder()`, `toBuilder()`, `build()`, and the fluent setter shape.
  - Optionally `docs/README.md` (the doc index) if it enumerates user guides.
- **Approach:**
  - Add a `## Builders` subsection to README with a short example (`Sample.builder().foo("a").bar(42).build()`) and a one-liner explaining value-only / no dependency / generated per record.
- **Test expectation: none — pure docs.**

---

## Verification Contract

- **Repo test command:** `./gradlew build` (full project: compile both modules + tests + assemble).
- **Generator test command:** `./gradlew :recordbuf-generator:test` — the Builder generator unit test lives here.
- **Fixture round-trip:** `./gradlew :recordbuf-fixtures:test` — the Builder-driven integration test lives here.
- **Codegen smoke:** `./gradlew :recordbuf-generator:installDist` then a `protoc --plugin=$(./gradlew …) --recordbuf_out=...` run on a sample `.proto` (mirroring the existing `AGENTS.md` recipe), then `./gradlew :recordbuf-fixtures:compileJava` — proves generated Builders compile.
- **NullAway gate:** Both `:recordbuf-generator` and `:recordbuf-runtime` run NullAway at error severity (wired per `AGENTS.md`); any null-handling bug in the Builder generator is caught by the gate.
- **Behavioral skill eval:** N/A — no runtime behavior change beyond generated output; the `ce-simplify-code` and `ce-code-review` steps of the pipeline cover the quality gate.

---

## Definition of Done

**Per-unit:**
- **U1:** The Builder generator unit test passes; a generated sample (in U2) compiles with its Builder, `builder()`, `toBuilder()`, `build()`, and per-component setters.
- **U2:** The fixture Builder integration test passes; the generated fixtures with Builders compile.
- **U3:** README contains the Builders subsection with a working example.

**Global:**
- `./gradlew build` is green.
- The generated fixtures with Builders round-trip through `encode`/`decode` in the test.
- `builder()` / `toBuilder()` / `build()` / fluent setters are exercised on all five component shapes (scalar, repeated, optional, singular message, map).
- Javadoc parity holds for commented fields.
- No new dependency is added to runtime or generated code.
- No existing API (canonical constructor, codec, decoder, encoder) is broken.
- Abandoned-attempt code from exploratory generation passes is removed.
