# Jackson U9 — the Jackson × Avro opaque envelope (`RecordbufDynamicWkt`) — implementation plan

**Date:** 2026-11-21 · **Status:** DONE 2026-11-22 in `eabaf1b` (sequence 2 of 5; U7 oneofs is
deliberately last — `2026-11-21-005`). Verification: `./gradlew build` green. As built: the
facade exposes `DynamicWkt.contentType`/`payload`/`node` (the converter's `JsonPayload` path);
`Any` encode reuses the `any`/`anyFrom` inverse pair; `NullValue` stays out (owner decision); a
dynamic WKT as a map value or oneof member fails loud in the bridge; the codec decode arm gained
the missing `Struct`/`ListValue` node cast; `fixtures/build.gradle` excludes the new
`RecordbufDynamicWkt` SpecificCompiler output from EP like the other carriers; the
`fixtures/.../dynamic.proto` fixture compiles the surface (its Avro bytes round-trip is U10/AE4).
**Origin:** unit U9 / R10 / P1 of `2026-11-20-001-feature-jackson-json-plan.md` (the J6 envelope;
"exactly the structural-twin plan's shelved `{format, content-type, payload}` shape — it returns
unmodified") + the 3.x handoff's remaining item 5 + commit `bcce1fa` (U5) which promised: "Any/
NullValue still fail loud by default, with an **opt-in escape the J9 envelope unit will flip**."
**Related:** `2026-11-17-012` Avro structural twin (shelved the envelope, line 37/112; the
carrier-emission + `SERIALIZABLE_PACKAGES` machinery), `2026-11-21-001` (U8 temporals, lands
first), `-003` (module + bytes), `-004` (U10 fixtures/acceptance), `-005` (U7 oneofs).

## Problem frame

The record side now carries node-typed dynamic WKT fields on jackson files (U5: `Struct →
ObjectNode`, `Value`/`Any → JsonNode`, `ListValue → ArrayNode`), so the structural-twin plan's
shelving condition ("the envelope returns only if the record side ever starts carrying dynamic
WKTs") is met. Today a file with **both** `recordbuf.avro` and `recordbuf.jackson` enabled and a
dynamic-WKT field **fails loud** in the Avro bridge: the field's Java type is a Jackson node (a
`tools.jackson.databind.node.*` reference) that the schema generator cannot map, and the registry
walk has no WKT avsc for the dynamic family. R10: such a file must instead render the field as an
opaque envelope, with every other field keeping the structural-twin mapping.

## Current state (verified against the tree)

- **P1 shape (origin doc, resolved):** a generated Avro record `RecordbufDynamicWkt { format:
  string, contentType: string, payload: string }` — `format = "json"`, `contentType` = the WKT
  proto FQN (`google.protobuf.Value`, …; **for `Any`, the `type_url`**), `payload` = the node's
  JSON text. `JsonNode → string → JsonNode` is content-lossless (node equality is content-based).
- **`JsonPayload`** (`:recordbuf-json`) is the U3 host of the envelope payload:
  `record JsonPayload(String jsonText)` with `@JsonValue`/`@JsonCreator`, `bytes()`, `of(...)` —
  its own javadoc names "the J6 Jackson×Avro envelope's `payload` property". **Nothing
  constructor- or read-sites it in generated code yet** (U9 is its consumer).
- **Loader (`ModelLoader.referenceType`):** dynamic WKTs resolve to the registry's node TypeRef
  only on jackson files; `Struct`/`Value`/`ListValue` work. **`Any` and `NullValue` throw**
  (unconditionally, even on jackson files) — and the explaining comment is mangled (a
  duplicated, mid-sentence line from the migration). `Any` is the only one the origin doc puts in
  v1 (lines 47–48: "Any → JsonNode record field (lifts the R11 fail-loud for opted-in files); Any
  retains its type URL through the carrier"); `NullValue` (zero-arity, always JSON null) stays
  out.
- **`DynamicWkt` facade (`:recordbuf-json`):** `encode(wktFqn, node)` / `decode(wktFqn, bytes)`
  dispatch **on FQN over the three supported WKTs** (javadoc: "the generator only emits these
  three") — `Any` needs arms added (the primitives exist: `any(Any)` keeps `typeUrl` + a
  `value` that is the decoded node or the **base64 of the raw payload** — lossless; `anyFrom`
  rebuilds). The class javadoc's "else null" sentence is stale (the code does base64) — fix in
  this unit.
- **Codec arms (`WktSourceMapping`)** are entry-driven (`entry.nodeType() != null` →
  `dynamicEncode/dynamicDecodeCase`) and call the facade with the WKT FQN — so lifting the loader
  guard for `Any` gives the codec arm for free once the facade gains the `Any` dispatch case.
- **Avro side:** `AvroRegistry` owns the shared-carrier fixed names (`UNKNOWN_FIELDS_ENTRY =
  "RecordbufUnknownField"`, `ONEOF_VALUE_ENTRY = "RecordbufOneofValue"`) and per-file name
  disambiguation; `AvroSchemaGenerator` maps field types (fail-loud
  `AvroUnsupportedException` for anything unmappable) and builds the per-file `.avsc` document
  (carriers included); `AvroBridge` writes the `.avsc`, runs `SpecificCompiler` under
  `SERIALIZABLE_PACKAGES` trust, and emits the converters. The envelope slots in as a **third
  shared carrier**. `ProtoFile` carries the resolved `jackson` boolean + `AvroToggle avroToggle`
  — everything the writers need to branch.

## Decisions

1. **Envelope schema = the P1 shape, as a shared carrier.** `AvroRegistry` gains
   `DYNAMIC_WKT_ENTRY = "RecordbufDynamicWkt"` next to the two carrier constants, with the same
   disambiguation treatment (fixed name in the document's avsc namespace, prefix on collision).
   Emitted **once per bridged file that has ≥1 node-typed field** — in the document exactly like
   the unknown-field carrier — and its `SpecificRecord` comes out of the existing
   `SpecificCompiler`/`SERIALIZABLE_PACKAGES` pass (no new trust surface). Fields map to it as:
   singular → `["null", record]` null-first union (the twin's message-field rule; the record
   component is `@Nullable JsonNode`-family), `repeated` → array. **Always a non-null
   `format = "json"`** constant string in the converter (a `string`, not an Avro enum — P1).
2. **Schema/converter branch on the file's `jackson` flag.** Node-typed field
   (`WellKnownTypes.nodeType(typeUrl) != null`) + jackson-on ⇒ envelope; jackson-off ⇒ the
   **existing fail-loud is untouched** (R11; the origin U9 verification case "jackson-off ⇒
   today's fail-loud skip, unchanged"). Structured WKTs keep their structural-twin avro mapping in
   both cases (R10: "all other fields keep the structural-twin mapping").
3. **Converter goes through `JsonPayload`.** Record → specific: `new <Envelope>(
   "json", <contentType>, new JsonPayload(MAPPER.readTree-style text of the node).jsonText())`
   (null node ⇒ null field). Specific → record: `DynamicWkt.MAPPER`-style parse of
   `payload` (the json module's mapper) back into the node. `contentType` = the WKT proto FQN;
   for an `Any` field it is the node's `typeUrl` property (the node carries it, per `any()`).
   The generated converter imports `bijnagte.recordbuf.json.*` **only in jackson+avro files**
   (R22's dependency-free rule is for the record/codec surface; the twin already imports avro).
4. **Flip the `Any` escape (the U5-commit promise).** The loader guard keeps throwing for
   `NullValue`; `Any` resolves to `JsonNode` on jackson files (R11 fail-loud preserved for
   jackson-off files, with the existing hint). Consequences wired in this unit: the `DynamicWkt`
   facade `encode`/`decode` dispatch gains the `Any` case (lossless via the base64 `value`
   fallback); the mangled loader comment and the stale "else null" facade javadoc get repaired in
   the same pass. Non-avro jackson files with `Any` now generate (the feature, not a regression —
   nothing existing uses `Any`).

## Changes

| File | Change |
|---|---|
| `generator/.../model/ModelLoader.java` | Drop the `Any` clause of the guard (keep `NullValue`); repair the mangled comment. |
| `json/.../json/DynamicWkt.java` | `encode`/`decode` dispatch: add the `Any` arm (round-trips via `any`/`anyFrom`); correct the stale class javadoc; javadoc "the generator only emits …" updated to four. |
| `generator/.../writer/AvroRegistry.java` | `DYNAMIC_WKT_ENTRY` constant + registry entry/namespace wiring when the file has a node-typed field (jackson-on). |
| `generator/.../writer/AvroSchemaGenerator.java` | Envelope `typeNode` branch (node-typed + jackson-on ⇒ the carrier; jackson-off ⇒ today's fail-loud); carrier schema in the document (next to the unknown-field carrier). |
| `generator/.../writer/AvroConverterSourceMapping.java` | Envelope field arm: build/read `RecordbufDynamicWkt` via `JsonPayload` + the json mapper; `contentType` = WKT FQN / `Any`'s `typeUrl`; imports gated on jackson+avro. |
| `generator/.../bridge/AvroBridge.java` | Only if the carrier-avsc/specific pass needs a third-name awareness (verify against the `ONEOF_VALUE_ENTRY`/`UNKNOWN_FIELDS_ENTRY` handling; likely a constant reference, no logic change). |
| Tests (generator + json) | See below. |

## Tests

**generator** (unit): jackson+avro file with a singular `Value`, a repeated `Value`, and an
`Any` field ⇒ the envelope in the `.avsc` (once, in the document namespace) + the field union/
array mappings + the converter builds/reads `RecordbufDynamicWkt` via `JsonPayload` (assert the
emitted source; `assertCompilesWithRuntime` where the twin's SpecificRecord is in scope) —
including the `Any` `typeUrl` as `contentType`. Same file jackson-off ⇒ the **existing
fail-loud** (unchanged message). Avro-only files (jackson default-ON caveat: use an explicit
`recordbuf.jackson = false` file) ⇒ byte-identical to pre-U9 output. Jackson-only files with a
`Value` field ⇒ unchanged by U9 (avro off); with `Any` ⇒ now generates (new capability).

**`:recordbuf-json`**: the facade's `Any` arm round-trips (wire bytes → node → bytes; structural
payload and non-JSON/base64 payload cases).

**Invariants:** jackson-off twins byte-identical across the board; codec wire bytes untouched
(R7); the structural-twin avro output for non-dynamic fields untouched. The Avro **bytes**
round-trip of the envelope is proven in U10/AE4 (fixture) — this unit proves the rendered
artifacts + module behavior.

## Gates & checkpoint

- `./gradlew spotlessApply && ./gradlew build` fully green (Spotless, Checkstyle, EP/NullAway,
  `-Werror`, tests) — including the fixtures' avro tests (existing avro fixture files have no
  dynamic WKTs, so their output is invariant; if any fixture gains an envelope, its generated
  `.avsc`/specific/converter must compile under the fixtures' full EP scope).
- Explicit `git add` of exactly the files above; commit message:
  `U9 (2026-11-20 Jackson plan): jackson+avro files render dynamic WKTs as the RecordbufDynamicWkt envelope; the Any escape is live`

## Open (owner-decidable)

- `NullValue`: the origin doc keeps it out of v1 (zero-arity; its node is always JSON null). This
  plan preserves that. If the owner wants it in, it is the same one-clause loader change plus a
  trivial envelope `contentType` constant — but a `NullValue` *field* is always JSON null, so its
  utility is thin.
