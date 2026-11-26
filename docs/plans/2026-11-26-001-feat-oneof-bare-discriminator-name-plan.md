---
title: Oneof JSON discriminator defaults to the bare oneof name
type: feat
date: 2026-11-26
topic: oneof-bare-discriminator-name
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
execution: code
---

# Oneof JSON discriminator defaults to the bare oneof name — implementation plan

## Goal Capsule

- **Objective:** On a Jackson-enabled file, the flat oneof discriminator's **JSON property default** becomes the bare oneof name (`choice`) instead of `<oneofName>Type` (`choiceType`). The `recordbuf.json_oneof` override is unchanged. The generated Java **method** name is left as-is (`choiceType()`, stepping to `choiceJsonType()` on a Java-name collision).
- **Authority:** This plan, then `AGENTS.md` (repo root), then its *Fixtures: golden matrix* rules.
- **Open blockers:** None (both forks resolved with the user: method name kept, hard break).
- **Execution profile:** 4 units. Units 1-3 must land together for a green `./gradlew build` (the golden matrix only passes once generated output and goldens agree); Unit 4 is docs and does not affect the build.
- **Stop conditions:** Stop and surface, do not proceed, if:
  - a golden outside the enumerated set in Unit 3 needs to change (the list is meant to be complete);
  - the change forces a new `@SuppressWarnings`, NullAway exclusion, or Checkstyle suppression in generated code;
  - the JaCoCo minimums would have to be lowered;
  - a oneof member's **Java** name would collide with the bare-name discriminator property (a property-collision failure now, not a method-collision) in a way that requires generation to fail on an existing fixture proto.

**Revisions (as implemented, 2026-11-26).** The "one-line" premise of R3/D1 was incomplete. The
discriminator's **JSON property** (`@JsonProperty("<oneof>")` on the getter and factory param)
collides with the `@JsonUnwrapped` oneof **component accessor** `<oneof>()` in Jackson's model —
`InvalidDefinitionException: Conflicting getter definitions for property "<oneof>"`. R3/D1 assumed
only the Java *method* name had to differ from the component; they did not account for the property
colliding with the accessor. **Fix (additive to Unit 1):** the generator now emits
`@JsonProperty("<oneof>$")` on the `@JsonUnwrapped` component — an internal, never-rendered name
(`@JsonUnwrapped` emits only the value's children, never the wrapper's own name) — freeing the bare
`<oneof>` for the discriminator. Verified: Jackson accepts `@JsonProperty` on a `@JsonUnwrapped`
property and does not render that name; the bare discriminator round-trips on every oneof fixture
(forward + reverse) and `./gradlew build` is green. Unit 2's
`componentIsUnwrappedAndWrappersCarryTheMemberNameAndPair` now asserts
`@JsonUnwrapped @JsonProperty("<oneof>$") @Nullable`. The method name (`<oneof>Type()`) and all
other decisions are unchanged.

---

## Product Contract

### Summary

recordbuf's flat Jackson oneof renders the active member as a top-level property under its JSON name, plus a discriminator property naming the active member. Today the discriminator property defaults to `<oneofName>Type`:

```json
{ "name": "blue", "choiceType": "name" }   // before
{ "name": "blue", "choice": "name" }        // after
```

This change drops the `Type` suffix from the **default** discriminator JSON property so it is the oneof's name. The `recordbuf.json_oneof` oneof option still overrides the property name (`Event`'s `kind` is untouched), and the generated Java getter keeps its current name (`choiceType()`) because it must differ from the `@JsonUnwrapped` record component accessor of the same name.

### Problem Frame

`<oneofName>Type` was a deliberate default set by the U7 plan (`docs/plans/2026-11-21-005-feature-jackson-u7-oneof-plan.md`) to keep the discriminator key distinct from any member's JSON name in flat output. The intent, per the existing generator test name `discriminatorDefaultsToTheOneofNameAndHonorsTheOverride`, was for the discriminator to "default to the oneof name" — the `Type` suffix is the gap. Making the default the bare oneof name aligns the implementation with that intent and produces a cleaner contract (`choice`, not `choiceType`). The `Type` suffix is still safe to drop because the discriminator key only needs to be distinct from the **members'** JSON names (which are the member field names, never the oneof name) — a oneof named `choice` has members like `name`/`count`, not `choice`.

This is a **breaking change to the JSON wire contract** (pre-1.0, accepted). A property collision with a **field** (not a member) now also fails generation when the field's JSON name equals the bare oneof name.

### Requirements

- **R1.** On a Jackson file, a oneof with no `recordbuf.json_oneof` override renders its discriminator JSON property as the bare oneof name: oneof `choice` ⇒ property `choice`; oneof `oneof_field` ⇒ property `oneofField`.
- **R2.** `recordbuf.json_oneof = "<name>"` still overrides the discriminator JSON property (`Event` keeps `kind`). No change to the override path.
- **R3.** The generated Java discriminator getter/factory-parameter name is **unchanged**: `<oneofName>Type()`, stepping to `<oneofName>JsonType()` on a Java-name collision. It must never be the bare oneof name (that collides with the `@JsonUnwrapped` component accessor `choice()`).
- **R4.** A discriminator JSON property that collides with a **field's** JSON name still fails generation loudly (now comparing against the bare oneof name instead of `<oneofName>Type`).
- **R5.** An unset oneof renders neither the member nor the discriminator; a malformed combination (discriminator names an absent/unknown member, or a second member is present) still fails loudly.
- **R6.** The wire **codec**, the Avro bridge, and the Interop (protobuf-java) converter are untouched — only the Jackson flat surface names a discriminator.

### Scope

**In scope**
- The Jackson flat oneof surface default discriminator JSON property name.
- The generated `.rec` records (regenerated at build) and the `.jackson.json` goldens that pin them.
- The generator/fixture tests that assert the default name.
- Docs that state the `<oneofName>Type` default.

**Out of scope**
- The sealed-interface name (`Choice`), the record component name (`choice`), and the per-member value records (`NameValue`) — already "just the oneof name", unchanged.
- The generated Java method name (kept, per R3).
- The `JsonOneof.select` runtime helper's **code** — it takes the oneof name + discriminator value, never a `Type` suffix; only its javadoc example changes.
- The reserved `recordbuf.json_oneof_values` option and any v2 discriminator-value-style work.
- Any backward-compat acceptance of the old `<oneofName>Type` key (hard break, confirmed).

### Key decisions

- **D1 (method name kept; component renamed in Jackson's model).** The discriminator getter stays `<oneofName>Type()` — a Java method, never the bare `<oneofName>()` (a second accessor of the component name would be a Java compile error). The discriminator's **JSON property** is the bare `<oneof>`; that collides with the `@JsonUnwrapped` component accessor `<oneof>()` in Jackson's model (`Conflicting getter definitions`), so the generator emits `@JsonProperty("<oneof>$")` on the component — an internal, never-rendered name (see *Revisions*). Property and method already diverge whenever an override is set, so diverging in the default case is not novel. Chosen over rebasing the method to the bare name (which would force it to always resolve to the uglier `<oneofName>JsonType()` and require reserving the component name in the collision set).
- **D2 (hard break).** Old `<oneofName>Type` keys are not accepted on read. Consequence (known, accepted): old JSON read by new code has its `<oneofName>Type` discriminator park in the `jsonExtras` sponge and the oneof reads as "no discriminator + members present" — `JsonOneof.select` still resolves it when exactly one member is present, but the old key is silently retained rather than interpreted. No `@JsonAlias`, no `:recordbuf-json` runtime change.
- **D3 (golden hand-edit, not seeder).** The affected goldens are updated by reviewed hand edit — the seeder writes missing goldens only and never overwrites. The exact set is enumerated in Unit 3.

---

## Current state (verified against the tree)

- **Core** — `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java`:
  - `discriminatorProperty(Oneof)` returns `override != null ? override : oneof.componentName() + "Type"`. **This is the one-line change.**
  - `oneofJsonSurface(Message)` builds the Java getter name separately: `String method = oneof.componentName() + "Type";` stepping to `oneof.componentName() + "JsonType"(n)` on a Java-name collision, and binds the factory parameter via `jsonProperty(property)`. The property-vs-collision check (`jsonNames.contains(property)`) uses the discriminator's **JSON** name. **The method naming stays; only the property default changes.**
- **Model docs** — `model/Oneof.java` ("the default is `<oneofName>Type`"), `model/ModelLoader.java` ("the default discriminator is `<oneofName>Type`").
- **Option proto** — `options/src/main/proto/recordbuf_options.proto`: the `recordbuf.json_oneof` comment ("the default is `<oneofName>Type`, e.g. `choiceType`") and the `recordbuf.jackson` file-option comment ("flat + `<oneofName>Type` discriminator oneofs").
- **README** — the `json_oneof` options table row ("default `<oneofName>Type`") and the Jackson overview line ("flattens oneofs with a `<oneofName>Type` discriminator").
- **Runtime doc** — `json/src/main/java/bijnagte/recordbuf/json/JsonOneof.java` javadoc example `{"name":"blue","choiceType":"name"}`.
- **Solutions doc** — `docs/solutions/design-patterns/recordbuf-jackson-json-surface.md` references the oneof discriminator.
- **Generator test** — `generator/src/test/java/bijnagte/recordbuf/plugin/OneofJsonSurfaceTest.java`: `discriminatorDefaultsToTheOneofNameAndHonorsTheOverride` (asserts the default `@JsonProperty("whenType")`), `factoryTakesFieldsMembersDiscriminatorAndExtras` (asserts `@JsonProperty("whenType")` on the factory param), `discriminatorCollidingWithAFieldFailsGeneration` (uses a field `when_type` whose JSON name is `whenType` — that only collided under the old default). `javaOnlyCollisionRenamesTheDiscriminatorMethodInsteadOfFailing` is unaffected (method naming unchanged).
- **Fixture test literals** — `fixtures/src/test/java/bijnagte/recordbuf/fixture/jackson/JacksonOneofRoundTripTest.java` uses `choiceType` inline in `unknownPropertiesAreRetainedNextToTheOneof` and `malformedOneofJsonFails` (for the default-discriminator `Compat` record).
- **Fixture generated code** — regenerated at build (`protoc` → recordbuf plugin), not committed, so no hand edit.

---

## Implementation Units

### Unit 1 — Generator: default discriminator JSON property is the bare oneof name

**Files**
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java` (change + in-file docs)

**Change**
- `discriminatorProperty(Oneof)`: `oneof.componentName() + "Type"` → `oneof.componentName()`.
- Leave the `oneofJsonSurface` method-name block (`<oneofName>Type` → `<oneofName>JsonType`) and its collision check untouched.
- Update the in-file comments/javadoc that name the old default (the `discriminatorProperty` doc, the `oneofJsonSurface` doc example `choiceType()`, and the collision comment) so the property default reads "the oneof name" and the method is still described as `<oneofName>Type`.

**Test scenarios (asserted in Unit 2's generator test)**
- Default oneof (no override): the getter is `@JsonProperty("<oneof>") @Nullable public String <oneof>Type()` — bare property, suffixed method.
- Override (`kind`): property `kind`, method still `<oneof>Type()`.
- Factory parameter bound to `@JsonProperty("<oneof>")` and passed to `JsonOneof.select("<oneof>", <oneof>Type, ...)`.

**Done when:** `:recordbuf-generator` compiles; `OneofJsonSurfaceTest` (updated in Unit 2) passes.

### Unit 2 — Tests (generator + fixture literals)

**Files**
- `generator/src/test/java/bijnagte/recordbuf/plugin/OneofJsonSurfaceTest.java`
- `fixtures/src/test/java/bijnagte/recordbuf/fixture/jackson/JacksonOneofRoundTripTest.java`

**Changes**
- `OneofJsonSurfaceTest.discriminatorDefaultsToTheOneofNameAndHonorsTheOverride`: default assertion `@JsonProperty("whenType") @Nullable public String whenType()` → `@JsonProperty("when") @Nullable public String whenType()` (method name stays `whenType`); override assertions unchanged.
- `OneofJsonSurfaceTest.factoryTakesFieldsMembersDiscriminatorAndExtras`: factory param `@JsonProperty("whenType")` → `@JsonProperty("when")` (param name `whenType` stays); the `JsonOneof.select("when", whenType, ...)` assertion stays.
- `OneofJsonSurfaceTest.discriminatorCollidingWithAFieldFailsGeneration`: the extra field that collides with the **property** changes from `when_type` (JSON `whenType`) to `when` (JSON `when`) so it collides with the new bare default. Intent (property collision ⇒ loud failure) preserved.
- `OneofJsonSurfaceTest.javaOnlyCollisionRenamesTheDiscriminatorMethodInsteadOfFailing`: **unchanged**.
- `JacksonOneofRoundTripTest`: rename the inline `choiceType` literals to `choice` in `unknownPropertiesAreRetainedNextToTheOneof` and in each `malformedOneofJsonFails` case that carries a discriminator (`{"name":"blue","choiceType":"count"}` → `{"choice":"count"}`, `{"choiceType":"name"}` → `{"choice":"name"}`, `{"name":"blue","choiceType":"shade"}` → `{"choice":"shade"}`). The no-discriminator case (`{"name":"blue","count":1}`) and the `oneof choice` message assertion stay.

**Test scenarios**
- Generator: as in Unit 1, plus method-only collision still steps to `<oneof>JsonType()` (unchanged behavior).
- Fixture `JacksonOneofRoundTripTest`: each member round-trips through the plain mapper with the bare discriminator; an unset oneof round-trips; a masked member reads back without its discriminator; unknown properties retained next to the oneof; the four malformed shapes still fail.

**Done when:** both test classes compile and pass. (The build is not fully green yet — the goldens in Unit 3 still pin the old key.)

### Unit 3 — Golden hand-edits (flagged: intended, reviewed content change)

**This is the stop-condition step from `AGENTS.md` *Fixtures: golden matrix* — the goldens below change by design; they are edited by hand (the seeder will not overwrite) and the diff is reviewed.** Do not touch any golden not listed here.

Renaming is mechanical (drop the trailing `Type` from the discriminator key, keep the member key and value). The complete set, by family, keyed to the old key:

- `oneofFieldType` → `oneofField` (15): `avrooneof/{bytes,enum,nested-message,uint32}`, `corpus/all-types-oneof-{bytes,message}`, `oneofnames/{bool,bytes,double,enum,float,nested-message,string,uint32,uint64}` — all `.jackson.json` under `fixtures/src/test/resources/golden/<family>/`.
- `choiceType` → `choice` (11): `interopenum/populated`, `jackson-oneof/compat-{color,count,item,name}`, `oneof/{color-green,count-42,count-999,item-seven,name-hello,name-x}`.
- `pick2wayType` → `pick2way` (7): `interoponeof/{bytes,enum,message,scalar,struct,timestamp,timestamp-epoch}`.
- `oType` → `o` and `pType` → `p` (4 files): `oneoforder/{big-zero-only (p), empty-s-with-tail (o), n-and-big (o+p), s-and-blob (o+p)}`.
- `vType` → `v` (17): every `oneofwkt/*.jackson.json`.

**Verification (completeness guard):** after editing, a repo search for a oneof discriminator key matching `"[^"]*Type"` inside `fixtures/src/test/resources/golden/*.jackson.json` returns nothing except the Avro dynamic-WKT envelope's `contentType` key (a different concept — do not edit those).

**Done when:** `GoldenMatrix` forward+reverse passes for every affected family; `./gradlew build` is green.

### Unit 4 — Docs (no build impact)

**Files**
- `options/src/main/proto/recordbuf_options.proto` — `json_oneof` comment ("the default is `<oneofName>Type`, e.g. `choiceType`" → "the default is the oneof name, e.g. `choice`") and the `recordbuf.jackson` comment ("`<oneofName>Type` discriminator" → bare oneof-name discriminator).
- `README.md` — the `json_oneof` table row and the Jackson overview line.
- `generator/src/main/java/bijnagte/recordbuf/plugin/model/Oneof.java` — the `jsonOneof` param doc.
- `generator/src/main/java/bijnagte/recordbuf/plugin/model/ModelLoader.java` — the discriminator doc.
- `json/src/main/java/bijnagte/recordbuf/json/JsonOneof.java` — the javadoc example `{"name":"blue","choiceType":"name"}` → `{"name":"blue","choice":"name"}`.
- `docs/solutions/design-patterns/recordbuf-jackson-json-surface.md` — the oneof discriminator mention.

Leave `docs/plans/2026-11-21-005-feature-jackson-u7-oneof-plan.md` as a historical record (do not rewrite a DONE plan); this plan references it.

**Done when:** no doc still states the `<oneofName>Type` default.

---

## Verification

1. `./gradlew build` green (compile + `test` + checkstyle + spotless + JaCoCo gate) — after Units 1-3.
2. `:recordbuf-generator:installDist`, regenerate a oneof `.proto`, and confirm the generated `.rec` JSON surface uses the bare discriminator (`@JsonProperty("<oneof>")`) and the getter is still `<oneof>Type()`.
3. Spot-check one golden per family changed in Unit 3 (e.g. `oneof/name-hello.jackson.json`, `oneofwkt/at-epoch.jackson.json`, `corpus/all-types-oneof-bytes.jackson.json`).
4. Confirm the `kind` override fixture (`jackson_oneof.proto` `Event`) still renders `kind` (regression guard for R2).

## Backlog / follow-ups

- If a future release must accept pre-change JSON, that is a separate change (e.g. an `@JsonAlias` on the discriminator or a `JsonOneof` fallback) — deliberately out of scope here.
- The `recordbuf.json_oneof_values` v2 option remains reserved/unwired; this change does not touch it.
