---
title: "The recordbuf Jackson JSON surface"
module: generator/src/main/java/bijnagte/recordbuf/plugin/writer
tags: [jackson, json, wire-compat, codegen, recordbuf-jackson]
problem_type: design-decision
source: manual
date_added: "2026-11-22"
---

# The recordbuf Jackson JSON surface

## Context

The `recordbuf.jackson` (file-level, default ON) and `recordbuf.avro` toggles control a Jackson
JSON surface on generated records. Three hard constraints define it:

- **C1** the JSON envelope stays field-name keyed (not tag/number keyed) and is deterministic —
  record-component order, oneofs flattened with a `<oneofName>` discriminator object.
- **C2** the output must be readable *without* any recordbuf runtime module on the classpath:
  only plain Jackson (2.x) plus `java.*` may be referenced.
- **C3** byte-level interop with protobuf-java's canonical JSON is preserved where it can be.

## The design (as built)

- **U4 core:** `@JsonProperty` wire names, canonical `true`/`false`, a per-field Jackson 2.x
  polymorphic subtype registry (`JsonSubTypeRegistry` — the generated converter is registered on
  its `@JsonDeserialize(using = …)` with an identity fallback), a WKT bridge (`JsonWktBridge` —
  one Jackson-2 reader/writer pair per canonical shape: object, string, integer, long, double),
  and the `Any` surface (record field + nested typed `FromUrl`/`ToUrlValue`; the URL is a
  record property, so the envelope stays field-name keyed).
- **U5 canonical shapes:** `Duration` (object canonical, string/integer accepted), `FieldMask`
  (`paths` array; a scalar path is its only member — the array form round-trips byte-for-byte),
  `Timestamp`/`Int64Value`/`FloatValue` (string, numeric accepted), `BoolValue`/`DoubleValue`
  (`true` / a number; the wrapper object is never canonical), `Bytes` (a base64 string).
- **U6 unknowns (the current shape):** the record carries a `jsonExtras` component
  (`@JsonIgnore @JsonAnyGetter @JsonAnySetter @Nullable Map<String, JsonNode>`, no accessors;
  Jackson honors the annotations on the record component) and a compact constructor that builds
  it into a fresh mutable map (or an immutable empty one). **The extras are JSON-only: the codec
  never writes them to the wire and never reads them from it.** The binary unknown-field carrier
  round-trips exactly as before. A JSON-to-wire round trip therefore drops *retained* properties
  (documented behavior); the carrier covers what a binary producer wrote.

## A decision and its history: how retained properties reach the wire (or not)

The U6 design originally *folded* each retained property into the binary unknown-field carrier on
encode — a synthetic length-delimited field whose number was `1 + (name.hashCode() % 0x20000000)`
and whose bytes were the property's JSON text. That design had three defects, in increasing
severity:

1. **Collision with declared numbers** (the hash range starts at 1).
2. **Two names hashing to one number** merge into a single carrier entry and corrupt on decode.
3. **The name is not on the wire** — after a binary round trip the property comes back as an
   anonymous unknown field; only the *text* survives, never under its name.

The fix considered was a single reserved field (the maximum number, 536870911) carrying the whole
map as one JSON object — idempotent, collision-free against any schema (protobuf reserves
19000+), name-preserving across a binary round trip, and still re-emittable against protobuf-java.
**It was rejected on one point: a property *name* would then be written to the wire as its value
bytes** — and for a schema that later declares a field whose name equals an old property, those
bytes are the wrong shape for the new declared type. The collision surface moves from field
numbers to names; it does not disappear, and names are a namespace the schema owner controls far
less predictably than numbers.

So the design settled (2026-11-22) on **JSON-only extras**: the `jsonExtras` map is retained for
the JSON lifecycle (a JSON-to-JSON round trip keeps unknown properties — U6's original purpose)
and is deliberately dropped at the wire boundary. The generator removed the fold entirely: no
`JsonExtras` helper, no synthetic tag derivation; the codec's unknown-field arm is now identical
for jackson and non-jackson output. The trade, stated plainly: unknown *properties* do not
survive a wire round trip (binary carrier fields still do, byte-for-byte).

## Why this is right

- Nothing the generator writes to the wire depends on a name or a hash, so no future schema
  evolution can reinterpret retained-JSON bytes as one of its fields — the invariant C3 needs for
  the *declared* surface, extended to the retained surface.
- The `:recordbuf-json` module lost a public API (`JsonExtras`) whose only consumer was the
  generated codec — less surface to keep compatible.

## How to apply

- The `jsonExtras` component and its compact-constructor/builder wiring remain the *JSON-side*
  retention mechanism; treat it as such in tests (round-trip through Jackson, assert the map).
- Never re-introduce a name-derived or reserved-tag encoding of `jsonExtras` on the wire; if
  durable retention of unknown JSON properties across a binary round trip is needed, it must be an
  explicit schema-level field (a declared `google.protobuf.Struct`/bytes), where its shape is
  under the schema owner's control.

## Examples / references

- `RecordSourceMapping` (`toSourceWithCodec`, the U6 arm) — component, compact constructor,
  builder wiring; `CodecSourceMapping` (the null extras argument on decode; the plain carrier
  arm on encode).
- `JsonAnySurfaceTest` (generator) — the rendered-surface contract, including "the encode never
  reads `v.jsonExtras`".
- `JacksonRoundTripTest#unknownPropertiesSurviveJsonButAreNeverWrittenToTheWire` (fixtures).
- The M4/M6 `JsonAnyGetter`/`@JsonIgnore` record-component mechanics: see the U8 follow-up note
  (a Jackson-2.10+ `@JsonIgnore` on a record component suppresses the implicit creator binding —
  an explicit Jackson module is required to read records with a jackson component).

## Related

- `design-patterns/unknown-field-capture-reemit.md` — the binary carrier this design no longer
  touches for JSON purposes.
- `tooling-decisions/record-component-javadoc-via-param.md` — the record-header Javadoc that
  documents `jsonExtras`.

## Confidence notes (from review)

- The "Jackson honors annotations on the record component without accessors" claim is a
  Jackson-version-dependent contract (Jackson 3 / the U8 module); pin the fixture golden tests to
  it rather than assuming.
- The U6 "extras are JSON-only" behavior change (2026-11-22) is a wire-compatibility decision:
  bytes produced by earlier generated code that folded extras *into* the carrier will still be
  read (as unknown fields) by newer code — nothing here breaks old bytes; only the new write path
  stops emitting them.
