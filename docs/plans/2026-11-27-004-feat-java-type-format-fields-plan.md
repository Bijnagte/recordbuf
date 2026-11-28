---
title: Java Type Format Fields - Plan
type: feat
date: 2026-11-27
topic: java-type-format-fields
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
deepened: 2026-11-27
---

# Java Type Format Fields - Plan

## Goal Capsule

- **Objective:** Let a singular proto `string` field opt in to holding a named Java type (e.g. `java.util.UUID`) in the generated record, with every serialization surface still carrying the plain string form, converted through a runtime-registered format.
- **Product authority:** The decisions in this Product Contract. `STRATEGY.md` bounds scope: recordbuf stays a models-and-codecs generator.
- **Open blockers:** None.
- **Done when:** `./gradlew build` is green; the v1 petstore sample generates and round-trips typed `id` components; fixture cases for format fields have goldens across the wire, JSON, and Avro adapters; a ULID format — discovered through `ServiceLoader` — round-trips in the fixture tests; the stale dynamic-WKT Avro doc claims are corrected.

---

## Product Contract

### Summary

A new field option, `recordbuf.java_type`, on singular `string` fields names the Java type the generated record holds for that field. The wire stays a proto3 string and every surface — proto3 JSON, Jackson, Avro — carries the string form, converted through a format resolved from a runtime registry that seeds itself from `ServiceLoader` discovery. Built-in formats cover `java.util.UUID` and `java.net.URI`; other types, such as a ULID, are user-provided formats — registered explicitly or discovered from the classpath.

### Problem Frame

Identifier-shaped data — pet ids, order ids, endpoint URIs — moves through schemas as bare `string` fields. A generated record exposes `String id()`, so every consumer parses and validates the identifier itself, and a malformed value is discoverable only at the call site, after it has already crossed a surface. recordbuf has no way to tell a string field that it is a UUID, a URI, or a ULID. The in-repo instance is `samples/petstore`, whose v1 proto carries five `string id` fields — `Pet`, `Order`, `GetPetRequest`, `DeletePetRequest`, `GetOrderRequest` — that every client and handler re-parses by hand.

### Key Decisions

- **The record is the only place that holds the type.** Wire, proto3 JSON, Jackson, and Avro all carry the string form. That keeps the wire format unchanged — existing data decodes as it does today — keeps the generator free of a type catalog and of any wire encoding work, and shrinks a format's contract to String ⇄ T.

  ```mermaid
  flowchart TB
      C["record component — the typed value; the only place a value of T lives"]
      FMT["format for T — parse(String) → T · format(T) → String"]
      REG["format registry — named type → format"]
      WIRE["wire — proto3 string field"]
      JSON["proto3 JSON / Jackson — JSON string"]
      AVRO["Avro — plain nullable string field"]

      WIRE <-->|string| FMT
      JSON <-->|string| FMT
      AVRO <-->|string| FMT
      C <-->|parse / format| FMT
      REG -.->|resolves by named type| FMT
  ```

- **The option is FQCN-only.** The value is the fully-qualified type name; the generator emits it verbatim as the component type. There is no built-in name table on the plugin side and no package whitelist: the consumer classpath supplies the type, the runtime registry supplies the format.
- **Resolution goes through the runtime registry, not a direct format reference.** Generated code names the type and asks the registry for its format; a lookup miss is a loud failure. Formats reach the registry two ways — programmatic registration, or `java.util.ServiceLoader` discovery when a format jar sits on the classpath — which is what keeps a custom format a user-side concern, never a plugin or runtime change.
- **JSON and Avro use the string form; no per-surface representation hooks.** No format in scope (UUID, URI, ULID) needs a non-string JSON or Avro shape, and the dialogue asked for none. Revisit only when a real format does.
- **Avro represents a format field as a plain nullable string field.** The `.avsc` mapping dispatches on the field's proto kind, which stays `string`, so the schema needs no new construct and no envelope: the field is the same `["null", "string"]` any nullable string maps to. The generated converter does the conversion (T ⇄ string through the registry). The dynamic-WKT JSON envelope stays a dynamic-WKT-only mechanism — it exists because a dynamic WKT's payload is a Jackson node, and a format field's surface value is a plain string. Consequence: the Avro surface of a format field works regardless of the jackson toggle.
- **A format field is presentable.** There is no default value of an arbitrary `T`, so a format field carries presence whether or not the field uses the `optional` keyword: the component is `@Nullable`, absent ⇔ `null`. That is the one place a format field departs from the plain string field it overlays.
- **Fail loud on unparseable; absent ⇔ null.** No sentinel values and no implicit nulling on parse failure. A present-but-unparseable value — including the empty string, which is present — fails the conversion.
- **Oneof members are eligible; the wrapper holds the type.** A format field inside a `oneof` keeps presence on the oneof (the component is `null` ⇔ unset) and moves the type into the wrapper: the per-member wrapper record implements `Unwrapable` with the named type and a non-null `value`. Every surface's oneof arm converts through the registry — the codec's per-member encode/size/decode slots, the Interop's centralized `valueFrom`/`valueTo` (oneof member arms already route through it), the Jackson wrapper `value` component (KTD5's pair), and the Avro converter's oneof member arms (the avsc union arm is `"string"` automatically, because the member keeps its string kind).

### Requirements

**Option and generated record**

- R1. A new `recordbuf.java_type` field option (FieldOptions extension in `options/src/main/proto/recordbuf_options.proto`) carries a fully-qualified Java type name and applies to singular `string` fields. The plugin reads it the way it reads its other options — by tag from the descriptor's unknown fields, without linking the options proto.
- R2. For an opted-in field, the generated record's component is the named Java type instead of `String`, and holds `null` when the field is absent. The generator emits the name verbatim, keeping no catalog of built-in types and no package whitelist. For an opted-in **oneof member**, presence rides the oneof: the oneof component is `null` when unset, and the active wrapper record's `value` is the named type, non-null — the wrapper implements `Unwrapable` with the named type (`Unwrapable<UUID>`), never with `String`.
- R3. The option on a non-`string` field, on a repeated field, or on a map field is a generation error with a clear message. An empty option value is a generation error. A oneof member is eligible — it is singular by definition (R2).

**Serialization surfaces**

- R4. The wire format does not change: the field remains a proto3 `string`. Encode converts the component to its string form before writing, and a `null` component leaves the field absent. Decode maps an absent field to `null`, converts a present value through the field's format, and fails the decode when the format cannot parse the value.
- R5. The proto3 JSON surface renders the field as a JSON string, converted through the same format. Value-from elides an empty reference string to `null` when the field has no presence bit (non-optional) — the reference side cannot distinguish an unset string from `""` there — and uses the has-bit when the field is `optional`: absent → `null`, present → parse, so a present `""` fails the parse exactly as it does on the wire.
- R6. The Jackson surface renders the field as a JSON string through the format; a `null` component emits no property (existing `NON_NULL` behavior).
- R7. The Avro bridge represents a format field as a plain nullable string field (the `.avsc` maps the string kind), and the generated converter converts through the same format; `null` ⇔ Avro `null`.

**Runtime registry**

- R8. The runtime module provides a registry mapping a Java type to its format, where a format is exactly a bidirectional String converter — `parse(String) → T` and `format(T) → String` — plus `type()`, the Java type it converts. The registry seeds itself from `java.util.ServiceLoader` providers, so a format is discovered by being on the classpath (`META-INF/services`) or declared in a module's `provides` directive; explicit registration stays available as the programmatic path and overrides a discovered format for the same type. Generated surface code names the type and delegates the conversion to the registry; it contains no format logic. A lookup for a type with no known format fails loudly.
- R9. Formats for `java.util.UUID` and `java.net.URI` ship with the runtime as discovered `ServiceLoader` providers, so they resolve without any registration code in the consuming project.
- R10. User code can provide a format for any Java type before a record that holds it is encoded or decoded — either by explicit registration with the registry or by declaring a provider (`META-INF/services` on the classpath, or a `provides` directive in a module). A ULID format demonstrates this custom path in tests and docs; it does not ship as a runtime built-in.

**Dogfood**

- R11. The v1 petstore proto's `string id` fields (`Pet`, `Order`, `GetPetRequest`, `DeletePetRequest`, `GetOrderRequest`) opt into `recordbuf.java_type = "java.util.UUID"`. The sample compiles and round-trips with typed `id` components, and the legacy petstore proto is untouched.

### Key Flows

- F1. Decoding a format field
  - **Trigger:** A codec reads a record whose `java_type` field may or may not be present on one of the surfaces (wire, proto3 JSON, Jackson, Avro).
  - **Steps:** The codec reads the field. Absent — the component is `null`, no format involved. Present — the codec resolves the format from the registry by the named type, `parse`s the string, and assigns the typed component. Parse failure — the decode fails.
  - **Covers R4, R5, R6, R8.**
- F2. Registering a custom format
  - **Trigger:** User code starts a process that will encode or decode records holding a custom-typed field (e.g. ULID).
  - **Steps:** User code makes the format available — explicitly, or as a `ServiceLoader` provider on the classpath — before the first conversion that touches it; generated codecs then resolve the format by the type on every subsequent conversion.
  - **Covers R10.**

### Acceptance Examples

- AE1. **Covers R1, R2.** A proto `string id` field with `recordbuf.java_type = "java.util.UUID"` generates a record component of type `UUID` that is `null` when the field is absent; the petstore sample's `Pet.id()` returns a `UUID`.
- AE2. **Covers R4.** Decoding bytes where the field is absent gives a `null` component. Decoding bytes where the field holds a well-formed UUID gives the parsed value. Decoding bytes where the field holds `""` or `"not-a-uuid"` fails the decode.
- AE3. **Covers R5, R6.** Proto3 JSON and Jackson JSON for the record show the field as a JSON string; a `null` value emits no JSON key.
- AE4. **Covers R7.** The Avro representation of the record carries the field as a plain nullable string; the fixture round-trips through the Avro binary and canonical Avro JSON goldens.
- AE5. **Covers R10.** A ULID format, declared as a `ServiceLoader` provider on the fixtures' test classpath, lets a fixture record with a ULID-typed component round-trip on wire, JSON, and Avro. The ULID type is the `ulid-creator` library class on the fixtures' test classpath; the `UlidFormat` adapter and its provider declaration live in test code, never in the runtime module.
- AE6. **Covers R3.** `java_type` on an `int32` field or on a `repeated string` field fails generation with a clear error.
- AE7. **Covers R2, R4.** A format field as a oneof member: the wrapper record implements `Unwrapable<UUID>`/`Unwrapable<URI>` (never `Unwrapable<String>`) with a non-null `value`; the record round-trips with the format member set, a sibling member set, and the oneof unset, across wire, proto3 JSON, Jackson, and Avro; a malformed value inside the oneof fails the decode.

### Scope Boundaries

**In scope**

- The `recordbuf.java_type` FieldOptions extension in `options/src/main/proto/recordbuf_options.proto`.
- Generator: reading the option, typing the record component, and the codec, Interop (proto3 JSON), Jackson, and Avro surface mappings for format fields — including their oneof-member arms.
- Runtime: the registry, the `parse`/`format` contract, and the `java.util.UUID` and `java.net.URI` formats.
- Fixtures: format-field cases with seeded goldens across the wire, JSON, and Avro adapters, plus the custom-format (ULID) test path.
- Sample: the v1 petstore `string id` migration.
- Doc-correctness pass: the stale claims that dynamic WKTs "fail loud / cannot be bridged" under the Avro bridge — the `AvroRegistry` class javadoc and the `WktAvroBridge` javadoc/comment predate the U9 JSON envelope, which is the actual behavior on jackson-enabled files — plus one misleading generator error string, plus an audit of the README's Avro section.
- Docs: a README section and a `docs/solutions/` note.

**Out of scope**

- Non-string wire shapes — no format may change what bytes hit the wire.
- ULID as a runtime built-in — it demonstrates the custom-registration path only.
- The legacy petstore proto — no migration there.
- Plugin-side type validation beyond R3 — no package whitelist, no format-name validation, no syntactic check of the option value beyond non-empty.
- Per-surface (JSON/Avro) representation hooks on formats.
- Dated plan artifacts under `docs/plans/` — historical records; the doc-correctness pass touches current-behavior docs only.
- Removing the `WktAvroBridge` v1-era dynamic-WKT fail-set — it is re-documented (KTD8) but kept: it is defensive runtime behavior, not a doc.

### Dependencies / Assumptions

- The consumer classpath must provide the named type and, for custom types, a registered format at runtime. The generator neither links nor validates the type.
- Nullable components are already part of the generated record surface (singular message fields and `optional` scalars are `null`-first), so absent ⇔ `null` for a format field reuses existing nullness handling — with the one extension that a format field is presentable even without the `optional` keyword (KTD2).
- The fixtures test run is the execution data behind the coverage gate scoped to the runtime/json/avro classes, so the new runtime format classes must be exercised by fixture tests (U8), not only by `:recordbuf-runtime` unit tests.

### Sources / Research

- `options/src/main/proto/recordbuf_options.proto` — FieldOptions extensions `jackson_format` (50005), `json_name` (50006), `json_ignored` (50007); OneofOptions 50008–50009; the next free FieldOptions tag is 50010. The file documents each option in a comment block above its extension.
- `generator/src/main/java/bijnagte/recordbuf/plugin/model/ModelLoader` — reads recordbuf options off the descriptor's unknown fields via a shared `readStringFrom(unknownFields, uninterpreted, tag, optionName)` helper; field option tags are named constants; `fieldType(...)` computes a field's `TypeRef`; `Field` is constructed positionally at the field loop.
- `generator/src/main/java/bijnagte/recordbuf/plugin/model/Field` — `@RecordBuilder` record; `isNullable()` grants scalar presence only via the `proto3Optional` flag; canonical 18-arg constructor plus a 17-arg convenience constructor.
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/` — `RecordSourceMapping` (record components, builders, nullability arms), `CodecSourceMapping` (encode/decode arms; the nullable scalar arm is the template), `InteropSourceMapping` (valueTo/valueFrom), `AvroConverterSourceMapping` (converter arms; `envelopeArm` is the dynamic-WKT precedent for a separate arm), `AvroSchemaGenerator` (the WKT mapping table in its class javadoc), `TypeRefs` (runtime FQCN constants).
- `runtime/src/main/java/bijnagte/recordbuf/runtime/` — current runtime surface; no registry or format class exists yet. The module is NullAway-scoped (`@NullMarked` package-infos) and runs Error Prone at error severity.
- `json/src/main/java/bijnagte/recordbuf/json/` — existing Jackson support (`JsonBytes`, `JsonTemporal`, …); the module already depends `api(project(":recordbuf-runtime"))`, so a shared format-field serializer base here can reach the registry.
- `fixtures/src/test/proto/jackson_full.proto` — the test-scope fixture layout to copy: records to a `.rec` `java_package`, Avro specifics take the proto package as namespace, imports `recordbuf_options.proto`.
- `fixtures/src/test/java/bijnagte/recordbuf/fixture/golden/GoldenCaseRegistry` — the explicit `List.of(...)` of `GoldenCaseSource`s the `seedGoldens` task walks; one new entry per family.
- `fixtures/src/test/resources/golden/` — golden store, `<family>/<case>.<ext>`; the five adapters (`pb.hex`, `proto3.json`, `jackson.json`, `avro.hex`, `avro.json`); goldens are frozen — new files fine, content edits a stop condition.
- `samples/petstore/settings.gradle.kts` — the petstore sample is a standalone Gradle build that resolves the recordbuf modules by `includeBuild("../..")` (composite), so it builds against the in-repo generator/runtime directly.
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/AvroRegistry` + `avro/src/main/java/bijnagte/recordbuf/avro/wkt/WktAvroBridge` — the stale dynamic-WKT doc claims the doc-correctness pass fixes (U10).

---

## Planning Contract

### Key Technical Decisions

- **KTD1 — Option shape and tag.** `optional string java_type = 50010;` in the FieldOptions extension, documented in a comment block matching the file's convention. The plugin reads it by tag via the existing `readStringFrom` helper — no linking, no new descriptor API. Load-time validation is exactly R3: kind `SCALAR`, wire token `string`, label singular, non-empty value — a oneof member passes (it is singular by definition, R2). No syntactic check of the FQCN itself — the consumer classpath is the authority, and a bad name fails at the consumer's compile/runtime, loud.
- **KTD2 — A format field is presentable.** `Field` gains a `@Nullable String javaType` component; `isNullable()` becomes `proto3Optional || javaType != null` for scalar/enum kinds. Every writer already branches on `isNullable()`, so the `@Nullable` component, the builder null-guards, and the absent-mapping arms fall out without new writer machinery — only the *type* and the *conversion* change per surface. The 17-arg convenience constructor and the positional `new Field(...)` call sites are updated (the compiler finds them all). The clause also fires on oneof-member `Field`s (they are `Field`s): the oneof arms must keep deriving presence from the oneof component and keep the wrapper's `value` component non-null (it is built without a null annotation) — audit any `isNullable()` branch a oneof member reaches.
- **KTD3 — Registry public API.** New package `bijnagte.recordbuf.runtime.format`, `@NullMarked`:
  - `Format<T>` — `Class<T> type()`, the Java type it converts; `String format(T value)`; `T parse(String text)`, throwing a `RuntimeException` on unparseable input (no `Optional`, no null return). `type()` is what lets the registry key a discovered provider to its Java type.
  - `FormatRegistry` — a static `ConcurrentHashMap` seeded by its static initializer from `ServiceLoader.load(Format.class)`: each discovered provider contributes `format.type() → format`, so the built-ins ship as a `META-INF/services/bijnagte.recordbuf.runtime.format.Format` resource in the runtime jar (public no-arg `UuidFormat` / `UriFormat`) rather than as hardcoded entries. `static <T> Format<T> format(Class<T> type)` fails loudly (naming the type) on a miss; `static <T> void register(Class<T> type, Format<T> format)` stays for programmatic use and overrides a discovered entry (last-write-wins). Discovery is one-shot at class init — a provider added to the classpath afterwards is reachable only via `register`. The seed loop needs one scoped `@SuppressWarnings("unchecked")` for the raw wildcard iteration (EP/`-Werror` clean).
  - Generated code names the type by class literal (`UUID.class`, `com.github.f4b6a3.ulid.Ulid.class`) and inlines `FormatRegistry.format(X.class).parse(...)` / `.format(...)` — no FQCN string constants in generated code, and the only new `TypeRefs` constant is the registry's FQCN. This resolves the planning-deferred registry-API question: lookup by `Class<T>` (not FQCN string) because generated code already has the type imported; registration by the same key; thread-safety = concurrent map, last-write-wins.
- **KTD4 — Surfaces contain no format logic.** All four surfaces (wire codec, Interop, Jackson, Avro) resolve the format from the registry and call it; a format field's surface arm is the plain-string arm with a conversion inserted. Wire: null → absent, present → `format(component)` written as a string; decode absent → null, present → `parse`. Interop valueTo: null component → skip; else `x.setField(FormatRegistry.format(X.class).format(component))`. Interop valueFrom: an `optional` field uses its has-bit — absent → `null`, present → `parse`, so a present `""` fails like the wire; a non-optional field has no presence bit, so `""` elides to `null` (the only representable mapping, mirroring how the existing Interop treats plain strings). Avro: the schema is untouched (`["null", "string"]` via the existing string-kind mapping); the converter arm converts.
- **KTD5 — The Jackson surface uses one shared, contextualized pair in the json module — no generated serializer classes.** `JsonFormatField` (json module, mirroring the `JsonBytes`/`JavaTimeTemporal` shape: public final class with public no-arg nested serializer/deserializer on the Jackson 3 bases `tools.jackson.databind.ValueSerializer`/`ValueDeserializer`) is referenced by a *constant* annotation pair the writer emits on every format component — top-level components and the oneof wrapper's `value` component — via the existing `serializerPair` helper. Unlike the temporal pairs, whose type set is closed (`JavaTimeTemporal.forType` pre-builds a class per type), format types are open, so the shared pair learns the field's type from Jackson's contextualization contract: `createContextual` captures the `BeanProperty`'s declared type at bean (de)serializer construction, and serialize/deserialize call `FormatRegistry.format(type)` on it (verify the exact 3.1.8 signatures on the `ValueSerializer`/`ValueDeserializer` bases — `JsonFormatField` is the json module's first contextualizer). A context without a `BeanProperty` fails loud (a format field is always a bean property on the generated surface). Nulls: the serializer is never called for null (record-level `NON_NULL` inclusion); an explicit JSON `null` deserializes to a `null` component; a present `""` fails the parse, wrapped as a deserialization failure; a registry miss fails loud naming the type (R8).
- **KTD6 — Avro is a plain string, not the envelope.** The dynamic-WKT envelope (`RecordbufDynamicWkt` with `format`/`contentType`/`payload`) exists because a dynamic WKT's payload is a Jackson node that has no canonical wire shape. A format field's surface value *is* a plain string, so the envelope would carry nothing: the field maps through the existing string-kind avsc mapping, the converter converts, and `contentType` has no role. Consequences: no JSON-text escaping machinery, no avsc document change, and the Avro surface of a format field is independent of the jackson toggle. The earlier brainstorm lean ("runtime-resolved schema — the dynamic-WKT mechanism") is superseded by this decision.
- **KTD7 — Fixture shape.** Test-scope family: `fixtures/src/test/proto/format.proto` (package `bijnagte.recordbuf.fixture.format`, `.rec` java_package, imports `recordbuf_options.proto`, following the `jackson_full.proto` layout) with one `Sample` message: `string id` → `java.util.UUID`; `string uri` → `java.net.URI`; `string uid` → `com.github.f4b6a3.ulid.Ulid` (the `f4b6a3:ulid-creator` library type, on the fixture's test classpath); `string plain` → control plain string; `optional string optId` → `java.util.UUID` (optional + format); `string dur` → `java.time.Duration` (never registered — the registry-miss test field; golden cases leave it absent); `oneof contact { string owner_uri → java.net.URI; string alt_id → java.util.UUID; }` (the oneof-member surface, R2). The `Ulid` value type comes from the `com.github.f4b6a3:ulid-creator` library (`testImplementation` on `:recordbuf-fixtures` only — the module is never published). `UlidFormat` (test code, public, no-arg: `type() → Ulid.class`, `parse → Ulid.from`, `format → toString`) is declared in `fixtures/src/test/resources/META-INF/services/bijnagte.recordbuf.runtime.format.Format` — the fixture demonstrates the declarative `ServiceLoader` path, not explicit registration. A `FormatCases` golden source + one `GoldenCaseRegistry` entry; a decode-failure test covers the parse-failure and registry-miss branches in the fixtures execution (which feeds the coverage gate). No gap-ledger rows: format fields are covered constructs in a non-vendored proto.
- **KTD8 — Doc-correctness pass boundary.** Current-behavior docs only: (a) the `AvroRegistry` class javadoc "WKTs:" paragraph (says dynamic WKTs "fail loud — it cannot be bridged"; the envelope constant five lines below is the actual behavior on jackson-enabled files — rewrite the paragraph to state: static-schema WKTs inline their fragment, dynamic WKTs ride the shared envelope record on jackson-enabled files, and fail loud on jackson-disabled files); (b) the `WktAvroBridge` javadoc and its `DYNAMIC_WKT_URLS` comment (v1-era "out of scope / generation error" — re-document as: dynamic WKTs are not handled here, they ride the envelope; the set is a defensive v1 failure path, kept); (c) the one `AvroSchemaGenerator` error string that says dynamic WKTs are "not generatable" — reword to the jackson-disabled context and update its asserting test; (d) an audit of the README's Avro section (its WKT table is accurate about the record side; fix only if a stale claim is found). Dated `docs/plans/` artifacts are untouched.

### Assumptions

- ULID: the fixture uses the `com.github.f4b6a3:ulid-creator` library (`com.github.f4b6a3.ulid.Ulid`) as a `testImplementation` dependency of `:recordbuf-fixtures` — the `java_type` FQCN points at a third-party library type, and `UlidFormat` is a thin test-code adapter (`Ulid.from` to parse, `toString` to format). No published module gains the dependency; pin the latest GA in the version catalog at execution time (the project README currently cites 5.2.4).
- The petstore sample is verified by its own Gradle build (composite `includeBuild`), not by the root `./gradlew build`; the exact invocation (wrapper vs. system Gradle) is confirmed at execution time.
- The Interop (proto3 JSON) surface is default-on with no per-file opt-out; the format field rides it automatically like every other field. Confirm in `ModelLoader` at execution time if a toggle exists.

### Sequencing

U1 (option + model) is the foundation; U2 (runtime) is independent of it and parallel-safe. U3–U7 (the five surface mappings) each need U1's model and U2's registry, and are independent of one another — they can land in any order, each with its own writer test. U8 (fixtures + goldens) and U9 (petstore) need the surfaces in place. U10 (docs pass) is independent. U11 (README/solutions docs) lands last, when the behavior it describes is final.

---

## Implementation Units

| Unit | Title | Primary files | Depends on |
|---|---|---|---|
| U1 | Option, model, loader | `options/src/main/proto/recordbuf_options.proto`, `generator/.../model/Field.java`, `generator/.../model/ModelLoader.java` | — |
| U2 | Runtime format registry | `runtime/.../runtime/format/*.java` | — |
| U3 | Record surface | `generator/.../writer/RecordSourceMapping.java`, `generator/.../writer/TypeRefs.java` | U1 |
| U4 | Wire codec | `generator/.../writer/CodecSourceMapping.java` | U1, U2 |
| U5 | Interop proto3 JSON | `generator/.../writer/InteropSourceMapping.java` | U1, U2 |
| U6 | Jackson surface | `json/.../json/JsonFormatField.java`, `generator/.../writer/RecordSourceMapping.java` | U1, U2 |
| U7 | Avro converter | `generator/.../writer/AvroConverterSourceMapping.java` | U1, U2 |
| U8 | Fixtures + goldens | `fixtures/src/test/proto/format.proto`, `fixtures/.../fixture/format/*`, `GoldenCaseRegistry.java` | U1–U7 |
| U9 | Petstore dogfood | `samples/petstore/src/main/proto/petstore/v1/petstore.proto`, petstore sample code | U1–U7 |
| U10 | Doc-correctness pass | `generator/.../writer/AvroRegistry.java`, `avro/.../wkt/WktAvroBridge.java`, `generator/.../writer/AvroSchemaGenerator.java`, `README.md` | — |
| U11 | Docs | `README.md`, `docs/solutions/design-patterns/recordbuf-java-type-format-fields.md` | U8, U9 |

### U1. Option, model, loader

- **Goal:** The `recordbuf.java_type` option exists, is read and validated by the model loader, and lands on the `Field` model.
- **Requirements:** R1, R2 (model half), R3.
- **Files:**
  - `options/src/main/proto/recordbuf_options.proto` — add `optional string java_type = 50010;` to the FieldOptions extension with a comment block in the file's style.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/model/Field.java` — add the `@Nullable String javaType` component; update `isNullable()` (KTD2); update the convenience constructor.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/model/ModelLoader.java` — tag constant `FIELD_JAVA_TYPE_TAG = 50010` + option name; read via the existing `readStringFrom` helper; R3 validation (kind/label/non-empty — a oneof member passes, it is singular) failing loud with a clear message; store on the constructed `Field`; `fieldType(...)` resolves a format field's `TypeRef` to the verbatim named type.
- **Approach:** Follow the `jackson_format` handling end-to-end (tag constant → read → validate → model) — it is the closest existing option. Oneof members are descriptor fields with their own options and ride the same field-construction path, so the option is read for them too; routing into `Oneof` is unchanged.
- **Test scenarios:** `java_type` on `int32` fails; on `repeated string` fails; on a `map<string,string>` field fails; empty value fails; on a oneof member succeeds (the `Field` carries the type; presence stays on the oneof); on a singular `string` (with and without `optional`) succeeds and the `Field` carries the type + presentable nullability.
- **Verification:** `./gradlew :recordbuf-generator:test`.

### U2. Runtime format registry

- **Goal:** `bijnagte.recordbuf.runtime.format` exists with the `Format`/`FormatRegistry` API and the built-in UUID/URI formats.
- **Requirements:** R8, R9.
- **Files:** `runtime/src/main/java/bijnagte/recordbuf/runtime/format/{Format,FormatRegistry,UuidFormat,UriFormat,package-info}.java`; `runtime/src/main/resources/META-INF/services/bijnagte.recordbuf.runtime.format.Format` (lists the two built-ins; the resource may create `src/main/resources`); (+ unit tests under `runtime/src/test/java/bijnagte/recordbuf/runtime/format/`).
- **Approach:** KTD3 verbatim. `@NullMarked` package-info so the package is in NullAway scope. `UuidFormat`/`UriFormat` are public top-level classes with public no-arg constructors (the `ServiceLoader` provider contract), listed in the services resource; the registry seeds from `ServiceLoader.load(Format.class)` in a static initializer and keeps `register` for programmatic overrides.
- **Test scenarios:** `UUID.class`/`URI.class` resolve without any explicit registration (discovery); unknown type fails loud naming the type; `register` makes a custom type resolvable and overrides a discovered entry for the same type; `UUID` round-trips a well-formed value; `parse("")` and `parse("not-a-uuid")` throw; `URI` round-trips and wraps `URISyntaxException`; each format's `type()` matches its key.
- **Verification:** `./gradlew :recordbuf-runtime:test`.

### U3. Record surface

- **Goal:** The generated record component for a format field is the named type, `@Nullable`, with builder null-guards — everything else about the record unchanged.
- **Requirements:** R2, R6 (component half).
- **Files:** `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java`; `generator/src/main/java/bijnagte/recordbuf/plugin/writer/TypeRefs.java` (registry FQCN constant); writer test.
- **Approach:** The component `TypeRef` for a format field is the verbatim named type (it arrives from the model); the nullability arms already key on `isNullable()` (KTD2), so the `@Nullable` annotation, the compact-constructor (no `requireNonNull`), and the builder setter guards follow from U1. The component's javadoc is emitted via the existing parameter-doc tooling. Oneof members: the wrapper's value type arrives through `member.type()` and `TypeRefs.boxed`, which passes named reference types through unchanged (verify at execution — `TypeRefs.java`); the wrapper implements `Unwrapable<NamedType>` with a non-null `value` component — the `isNullable()` clause (KTD2) must not add a null annotation to it (presence rides the oneof).
- **Test scenarios:** a singular string field with `java_type` renders a `@Nullable` typed component; an `optional` string field with `java_type` renders identically nullability-wise; a plain string field (no option) renders unchanged; a format field as a oneof member renders a wrapper record `...Value(NamedType value)` implementing the oneof interface and `Unwrapable<NamedType>` (never `Unwrapable<String>`).
- **Verification:** `./gradlew :recordbuf-generator:test`.

### U4. Wire codec

- **Goal:** Encode writes the string form (absent for null); decode maps absent → null and parses present values through the registry, failing loud on parse failure.
- **Requirements:** R4, R8.
- **Files:** `generator/src/main/java/bijnagte/recordbuf/plugin/writer/CodecSourceMapping.java` (+ writer test).
- **Approach:** The format field's wire arms are the nullable string arms with conversions: encode `id != null ? out.writeString(num, FormatRegistry.format(X.class).format(id)) : skip`; decode absent → `null`, present → `FormatRegistry.format(X.class).parse(in.readStringRequireUtf8())` — the parse exception propagates (the decoder has no catch). Size computation uses the formatted string's length where the existing string arm computes size. Oneof members: `oneofEncodeSlots`, `oneofSizeSlots`, and `emitOneofDecodeCase` dispatch on the member's kind — add the format arm: encode `FormatRegistry.format(X.class).format(x_.value())` on the active wrapper, size on the formatted string, decode `parse(readString)` into the wrapper's constructor.
- **Test scenarios:** encode arm for a format field guards on null and formats; decode arm maps absent to null and present through `parse`; a non-format string field's arms are unchanged; a oneof with a format member: the encode slot fires on the active wrapper, the size slot measures the formatted string, and the decode case builds the wrapper from the parsed value, while sibling members are unchanged.
- **Verification:** `./gradlew :recordbuf-generator:test`.

### U5. Interop (proto3 JSON) surface

- **Goal:** The protobuf-java reference side stays `string`; the generated Interop converts T ⇄ string through the registry, with empty-string ⇔ null on the way in.
- **Requirements:** R5, R8.
- **Files:** `generator/src/main/java/bijnagte/recordbuf/plugin/writer/InteropSourceMapping.java` (+ writer test).
- **Approach:** valueTo: null component → skip; else `x.setField(FormatRegistry.format(X.class).format(component))`. valueFrom: an `optional` field maps absent → `null` and present → `FormatRegistry.format(X.class).parse(s)` (a present `""` fails the parse, matching the wire); a non-optional field has no presence bit, so `s.isEmpty() ? null : parse(s)` (elision, mirroring how the existing Interop treats plain non-optional strings). Oneof member arms already route through the centralized `valueFrom`/`valueTo` (see the class-level comment) — verify the format conversion applies to them with no separate arm.
- **Test scenarios:** valueTo formats a present value and skips null; valueFrom maps `""`/absent to null and a present value through `parse`; a plain string field converts unchanged.
- **Verification:** `./gradlew :recordbuf-generator:test`.

### U6. Jackson surface

- **Goal:** A format field's Jackson surface renders/parses the string form through the registry, via the json module's shared `JsonFormatField` pair — a constant annotation on every format component, no generated serializer classes.
- **Requirements:** R6, R8.
- **Files:** `json/src/main/java/bijnagte/recordbuf/json/JsonFormatField.java` (the shared pair); `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java` (the constant annotation pair via `serializerPair`, including the oneof wrapper's `value` component); writer test.
- **Approach:** KTD5. `JsonFormatField` mirrors `JsonBytes`: a public final class with public no-arg nested `FormatSerializer`/`FormatDeserializer` on the Jackson 3 bases (`ValueSerializer`/`ValueDeserializer`), plus the contextualization that captures the field's declared type from the `BeanProperty` (verify the exact 3.1.8 signatures — the json module's first contextualizer). The record writer emits `serializerPair(false, JsonFormatField.FormatSerializer, JsonFormatField.FormatDeserializer)` on each format component and on the oneof wrapper's `value` component (`oneofValueComponent` already attaches type-specific annotations), so the annotation is constant across all format fields — no per-field class is generated.
- **Test scenarios:** a format field on a jackson-enabled record renders the constant `@JsonSerialize`/`@JsonDeserialize` pair (the same shared classes on every format field — no per-field class); the shared pair resolves the declared type from the `BeanProperty` context and (de)serializes string ⇄ T through `FormatRegistry`; an explicit JSON `null` maps to a `null` component; a present `""` fails the parse, wrapped; an unregistered type fails loud naming the type; a jackson-disabled file emits no annotations.
- **Verification:** `./gradlew :recordbuf-json:test :recordbuf-generator:test`.

### U7. Avro surface

- **Goal:** A format field's `.avsc` is the plain nullable string; the generated Avro converter converts T ⇄ string through the registry.
- **Requirements:** R7, R8.
- **Files:** `generator/src/main/java/bijnagte/recordbuf/plugin/writer/AvroConverterSourceMapping.java` (+ writer test); `AvroSchemaGenerator` — verify only (the string-kind mapping already produces `["null", "string"]`; no schema change expected).
- **Approach:** A format field's converter arms mirror the nullable string arm with conversions in both directions (`toAvro`: null → null else `format(component)`; `fromAvro`: null → null else `parse(s)`). The arm's dispatch key is the model's `javaType() != null` predicate, the same shape the existing `envelopeArm` uses for dynamic WKTs (KTD6 — no envelope, no registry interaction with the schema side). Oneof member arms (`oneofValueToRecord` and the record→avro direction): the format member's value converts through the registry on the way into and out of the shared oneof-value wrapper; the avsc union arm for the member is `"string"` automatically (the member keeps its string kind — no schema-side change).
- **Test scenarios:** the converter arm formats/parses through `FormatRegistry`; the `.avsc` field for a format field is `["null", "string"]`; a plain string field's arm and schema are unchanged; a oneof with a format member round-trips through the avsc union while a sibling member is unchanged.
- **Verification:** `./gradlew :recordbuf-generator:test`.

### U8. Fixtures + goldens

- **Goal:** A format-field fixture family round-trips across all five golden adapters, including the custom ULID path and the failure branches.
- **Requirements:** R4–R10 (fixture coverage), AE2–AE5.
- **Files:** `fixtures/src/test/proto/format.proto`; `fixtures/src/test/java/bijnagte/recordbuf/fixture/format/{UlidFormat,FormatFieldTest}.java`; `fixtures/src/test/resources/META-INF/services/bijnagte.recordbuf.runtime.format.Format` (declares `UlidFormat`); `gradle/libs.versions.toml` + `fixtures/build.gradle` (the `ulid-creator` test-scope dependency); `fixtures/src/test/java/bijnagte/recordbuf/fixture/format/FormatCases.java` (golden source); `fixtures/src/test/java/bijnagte/recordbuf/fixture/golden/GoldenCaseRegistry.java` (one entry); `fixtures/src/test/resources/golden/format/*` (seeded).
- **Approach:** KTD7. `format.proto` follows the `jackson_full.proto` test-layout (`.rec` java_package, proto-package Avro namespace, options import). `FormatCases` builds the golden records (all-fields-set with the `contact` oneof on a format member, the oneof on the sibling member, the oneof unset, id-only, nulls-only-plain; `dur` always absent). `UlidFormat` — a thin adapter over `Ulid.from`/`toString` — is declared in the test resources' services file, so the registry discovers it at class init — no explicit registration anywhere in the fixture. `FormatFieldTest` covers the decode-failure branches: wire bytes with `"not-a-uuid"` / `""` on the UUID field fail the decode, and a present `dur` value fails loud on the registry miss. Seed with `./gradlew :recordbuf-fixtures:seedGoldens` (writes missing goldens only); the new Jackson goldens are recordbuf's own output and need human review before commit; existing goldens are frozen — any change to one is a stop condition.
- **Test scenarios:** forward+reverse golden matrix for the format family (wire, proto3 JSON, Jackson, Avro binary, canonical Avro JSON); ULID round-trip on all surfaces with the ULID format discovered from the fixture's services file; the `contact` oneof round-trips through all five adapters with the format member set, the sibling set, and unset; decode failure on malformed UUID and on the unregistered `java.time.Duration` field; the plain-string control field round-trips unchanged.
- **Verification:** `./gradlew :recordbuf-fixtures:test :recordbuf-fixtures:jacocoTestCoverageVerification` (the coverage gate must stay green — the new runtime/avro/json format classes are in its scope and are exercised here).

### U9. Petstore dogfood

- **Goal:** The v1 petstore proto's five `string id` fields hold `UUID` in the generated records; the sample compiles and round-trips.
- **Requirements:** R11, AE1.
- **Files:** `samples/petstore/src/main/proto/petstore/v1/petstore.proto`; the petstore sample code under `samples/petstore/src/main/java/` that touches `id` components.
- **Approach:** Add `[(recordbuf.java_type) = "java.util.UUID"]` to the five fields (`Pet`, `Order`, `GetPetRequest`, `DeletePetRequest`, `GetOrderRequest`). The v1 proto already imports `recordbuf_options.proto` and the build already wires `optionsProtoDir` into the protobuf task's `includeDirs`, so no import or path work is needed. Update sample code whose `id` components became `UUID` (e.g. id generation becomes `UUID.randomUUID()`, lookups parse the REST string path parameter); the built-in UUID format is discovered via `ServiceLoader` from the runtime jar, so no registration code enters the sample. The REST/JSON layer stays string-based — that is the surface contract (R5/R6). The legacy petstore proto is untouched.
- **Test scenarios:** the sample build generates typed `id` components and compiles; the sample's round-trip (its existing tests) passes with `UUID` components; the legacy proto still generates plain `String` ids.
- **Verification:** `cd samples/petstore && gradle build` (standalone composite build — confirm wrapper availability at execution time).

### U10. Doc-correctness pass

- **Goal:** Current-behavior docs no longer claim dynamic WKTs fail under the Avro bridge.
- **Requirements:** none (maintenance) — serves the doc-correctness item in Scope Boundaries.
- **Files:** `generator/src/main/java/bijnagte/recordbuf/plugin/writer/AvroRegistry.java` (class javadoc "WKTs:" paragraph); `avro/src/main/java/bijnagte/recordbuf/avro/wkt/WktAvroBridge.java` (class javadoc + `DYNAMIC_WKT_URLS` comment); `generator/src/main/java/bijnagte/recordbuf/plugin/writer/AvroSchemaGenerator.java` (the "not generatable" error string + its asserting test); `README.md` (Avro-section audit).
- **Approach:** KTD8. Rewrite the `AvroRegistry` WKTs paragraph to state the envelope behavior (static-schema WKTs inline; dynamic WKTs ride the shared envelope on jackson-enabled files; fail loud on jackson-disabled files). Re-document `WktAvroBridge` as a v1 structural bridge whose dynamic-WKT set is a defensive failure path (kept, per Scope Boundaries). Reword the generator error string to the jackson-disabled context and update its test. Fix README claims only if the audit finds them stale.
- **Test scenarios:** the updated error string's test passes; no test asserts the old wording elsewhere (grep for the old phrasing).
- **Verification:** `./gradlew build` (javadoc/doclint clean).

### U11. Docs

- **Goal:** The README documents `recordbuf.java_type` and the registry; a design note captures the mechanism.
- **Requirements:** R10 (docs half).
- **Files:** `README.md` (options section); `docs/solutions/design-patterns/recordbuf-java-type-format-fields.md`.
- **Approach:** README: the option, the presentable-component rule, the registry with both provider paths (explicit `register`, and `ServiceLoader` discovery — `META-INF/services` on the classpath, `provides`/`uses` in `module-info` for modular deployments) with a custom-format (ULID-style) example for each, and the surface behavior (string everywhere, absent ⇔ null). Solutions note: the KTDs as the durable pattern — presentable format field, registry-resolved conversion in every surface arm, `ServiceLoader` seeding + `type()` keying, plain-string Avro, jackson base pairs, the fixture family layout, and the U10 doc fixes.
- **Test scenarios:** n/a (prose); cross-check every code reference against the merged code.
- **Verification:** `./gradlew build` (doclint on any javadoc-adjacent changes).

---

## Verification Contract

- **Root build:** `./gradlew build` — compiles all modules, runs all tests (including the fixtures golden matrix and the new writer/loader tests), and enforces the quality gates: Error Prone + NullAway at error severity, `-Werror`, Spotless (`spotlessCheck`), Checkstyle, and the fixtures JaCoCo gate (LINE 0.8 / BRANCH 0.7 — must not be lowered).
- **Golden seeding:** `./gradlew :recordbuf-fixtures:seedGoldens` — writes missing goldens for the new `format` family only, never overwrites. The Jackson goldens it writes are recordbuf's own output: review them by hand before commit.
- **Dogfood build:** `cd samples/petstore && gradle build` — the petstore sample is a standalone composite build (`includeBuild("../..")`), not part of the root build.
- **Codegen regeneration:** the fixtures module generates, compiles, and round-trips the format family as part of the root build, which satisfies the "regenerate a sample `.proto` and confirm the output compiles + round-trips" rule for touched codegen.
- **Stop conditions (repo rules):** a golden content change (not a new golden file) is a stop condition; lowering the JaCoCo minimums is a stop condition.

---

## Definition of Done

**Global**

- `./gradlew build` is green, including the fixtures golden matrix and the coverage gate at its existing minimums.
- The petstore sample build is green with typed `id` components; the legacy petstore proto is untouched.
- The `format` golden family exists for all five adapters; the Jackson goldens were human-reviewed; no pre-existing golden was modified.
- README and the solutions note are updated and accurate against the merged code; the U10 doc fixes are in.
- No abandoned-attempt code remains in the diff; no new dependencies were added to any published module.

**Per unit**

- The unit's test scenarios pass under the unit's verification command.
- Writer units (U3–U7): the rendered-output assertions cover both the format-field arm and the unchanged plain-string arm (regression guard).
- U8: goldens are committed only after the human review of the Jackson ones; `FormatFieldTest` exercises the parse-failure and registry-miss branches.
- U10: no current-behavior doc claims dynamic WKTs "fail / cannot be bridged" under the Avro bridge (grep-verify the old phrasing is gone from `generator/`, `avro/`, and `README.md`).
