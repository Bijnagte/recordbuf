---
title: Avro Conversion Bridge - Plan
type: feat
date: 2026-11-05
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
execution: code
---

# Avro Conversion Bridge - Plan

## Goal Capsule

**Objective:** Give `recordbuf` an opt-in **Apache Avro** bridge, built on the
record as the central data-class hub. When enabled for a file, the plugin — driven
by the `.proto` (the single source of truth) — emits **an Avro schema and a
structural `record ↔ Avro SpecificRecord` serializer/deserializer**, plus a
standalone `.avsc` per proto. The generated Java record and the existing
protobuf-java interop layer stay byte-for-byte untouched; Avro is a purely
additive sibling surface, the first of a deliberately extensible family of
per-protocol format bridges.

**Product authority:** This document and `AGENTS.md`. The record stays the central
data class and the proto stays authoritative; no generated record may pull
`org.apache.avro` onto its own classpath (Avro lives in its own generated package
backed by a dedicated runtime module, mirroring how `:recordbuf-runtime` hosts the
protobuf framing). The existing protobuf-java interop (`Layer A` always-on
marshaller, `Layer B` structural converter) is unchanged. The bridge is additive
and is a complete no-op when not enabled.

**Open blockers:** None. All brainstorm-time framing is resolved in **Key
Decisions**; the shape-level forks left to ce-plan are enumerated in **Open
Sub-Decisions for ce-planning**. Deferred follow-ons (Confluent/SR dialect,
dynamic WKTs, `uint64` lossless, map-of-message/enum, per-message toggle,
record-level wire wrappers, cross-file nesting) do not block v1.

---

## Product Contract

### Summary

Add an opt-in **Avro** format bridge to `recordbuf` as an extension of the record
hub, modeled directly on the existing protobuf-java interop. A new extensible
**format-bridge extension point** in the plugin is registered by the Avro bridge
(a future bridge — GraphQL schema, Jackson JSON — is the same registration, not a
core rewrite). When the bridge is enabled for a file, the plugin emits **from the
proto**: an Avro schema, generated **`SpecificRecord`** classes, and a structural
`record ↔ Avro` converter, plus one standalone `.avsc` per proto. The generated
record and the codec/protobuf layers are untouched; `org.apache.avro` is isolated
to the generated Avro package and a new `:recordbuf-avro` runtime module that
also hosts a **well-known-types bridge** (native Avro logical types). The bridge
is two capabilities in two phases: **P1** = converter + specific type + schema
(embedded and `.avsc`); **P2** = lightweight record-level Avro wire wrappers.
Coverage follows the existing interop boundary and **fails loud** at generation on
the lossy/unsupported edges rather than emit a wrong schema.

### Problem Frame

The interop story so far has been a single axis: recordbuf and protobuf-java share
byte-identical wire bytes, so crossing a *protobuf* boundary is cheap. That leaves
the non-protobuf Java ecosystem — **Avro, and by extension the data / streaming
world that speaks Avro** — without a first-class path from recordbuf. A team wants
to carry domain logic in records and cross a boundary where the peer speaks
Avro; today that means hand-writing, per message, both an Avro schema and a
`record ↔ Avro` mapping — boilerplate that scales with the message count and is
error-prone, and that risks drift between a hand-written schema and the
authority that actually *is* the proto.

The structural fact that makes this tractable: the proto already **is** the
authority, and the record is the hub every surface already converts through. So
the proto-to-Avro mapping can be *emitted*, not authored, and the Avro
serializer/deserializer can be a converter on the record rather than a parallel
data model. The remaining cost is the genuinely hard part of any cross-IDL bridge
— mapping proto's type system onto Avro's, losslessly where possible and failing
cleanly where it is not.

### Why it matters

The user's stated direction is that conversion is a **family**: recordbuf will
also generate GraphQL schemas and Jackson-native JSON serialization. Each of those
will arrive as its own protocol-specific runtime module. The Avro work is the
**pilot** that establishes the shared shape a bridge module takes — the option
that turns it on, the extension point a bridge registers against, the runtime
module's structure, and the "record is the hub, proto is the source of truth"
discipline. Getting the *seam* right now — not just the Avro-specific bits — is
what lets GraphQL and Jackson be drop-in later instead of a second, differently
shaped effort.

### Key Decisions

- **Record is the hub; proto is the source of truth.** The Avro schema and the
  converter are *emitted from the proto*; the record is the central data class the
  bridge converts through (`proto ⇄ record ⇄ avro ⇄ record ⇄ proto`). The record
  layer and the protobuf-java interop are unchanged; Avro handling is a pure
  Avro-layer concern. Chosen over a record-as-`SpecificRecord` approach, which
  would couple the record to Avro and force Avro semantics onto the central type.
- **Specific, not generic.** `SpecificRecord` classes are the v1 target, not
  `GenericRecord`/`Map`. Specific gives a concrete, IDE-navigable type, a
  compile-time schema, and the cleanest generated converter; generic is a
  deferred variant.
- **Extensible bridge family, built as a thin generic extension point first.**
  A future bridge registers its option + emitter + runtime module against one
  seam; Avro is the pilot. Chosen over an Avro-only minimal build now and a
  generalization later — the upfront cost is the seam, and it is what makes the
  promised family not a second rewrite.
- **Native Avro logical types, Confluent/SR deferred but not shut off.** v1 uses
  Avro's *native* logical types (`timestamp-micros`, etc.). A Confluent
  Schema-Registry-compatible variant is an explicit **later feature**, kept open
  because native logical types are a subset of Confluent's and the
  `SpecificRecord` shape is portable — "add the Confluent dialect" is a runtime
  addition on the same bridge, not a regeneration (a "don't cut off the path").
- **Well-known types via a runtime bridge, not a hard fork.** recordbuf does not
  *recognize* WKTs today (it treats `google.protobuf.*` as ordinary nested
  messages), and a naive proto→Avro mapping is silently lossy at the edges. So a
  **WKT bridge lives in `:recordbuf-avro`** — canonical Avro schema fragments +
  conversion per known WKT, reused by every generated converter — exactly the
  shape `:recordbuf-runtime` already takes for the protobuf framing. Unmapped
  types **fail loud at generation** (a clear error), which is the mechanism that
  keeps a path closed-to-now but open-for-later, not permanently forbidden.
- **WKT v1 set, with a structured `Duration`.** Covered natively: scalar
  wrappers, `Timestamp` → `timestamp-micros`, `FieldMask` (bytes), `Empty`,
  `NullValue`, and **`Duration` → `{ seconds: long, nanos: int }`** (a record,
  *not* logical `duration`, which only carries micros and would lose
  > 1 s of nanos). Deferred: the dynamic types `Struct`/`Value`/`ListValue`/`Any`.
- **Standalone schema, proto-package namespace.** Embedding the schema on each
  `SpecificRecord` is mandatory (Avro's `getSchema()` contract); a standalone
  `.avsc` per proto (all of that proto's records nested; oneof as a union;
  generated-header'd) is added as a shareable, registry-ready artifact. The
  Avro **namespace is the proto `package`** — consistent with the
  proto→`java_package` rule.
- **Hardened Avro reader/writer API.** `GenericDatumReader`/`GenericDatumWriter`
  + `SpecificData` on the schema-driven API, on a pinned recent Avro (1.12.x GA
  line), *not* the legacy class-name `SpecificDatumReader`, whose no-arg
  constructor is deprecated and unsafe.
- **Opt-in toggle = global param + per-file tri-state, default off.** A global
  plugin flag plus a per-file option that can force `ON`/`OFF`/`INHERIT`, with the
  per-file value beating the global in either direction — mirroring the
  `package_suffix` global param and the per-message option pattern. Per-message
  granularity is deferred.
- **Two serializers, phased.** Both a `record ↔ Avro` converter (**P1**, first)
  and lightweight record-level Avro wire wrappers (**P2**, additive on P1) are
  wanted; P1 makes the record the schema's native carrier (the thing Avro is
  optimized for) while P2 keeps an `encode`/`toAvroBytes`-style ergonomics
  symmetric with the protobuf `Codec`.

### Requirements

**Bridge architecture and source of truth**

- R1. **Extensible bridge family.** `recordbuf` gains a generic *format-bridge*
  extension point that a per-protocol bridge registers against (option that turns
  it on, emitter, runtime module). A future bridge — GraphQL schema, Jackson JSON,
  or another — is added by registering against the same seam, not by rewriting the
  plugin core. Avro is the pilot bridge.
- R2. **Specific target.** The v1 Avro representation is Avro *Specific*
  (`SpecificRecord` classes), not `GenericRecord`/`Map` or a `SpecificRecord`
  stand-in on the Java record.
- R3. **Record stays the hub.** The `record ↔ Avro` path is a converter *on* the
  record. The record layer, its `Codec`, and the existing protobuf-java interop
  are unchanged; Avro handling is a pure Avro-layer concern.
- R4. **Proto is authoritative.** The Avro schema and the converter are emitted
  **from the proto**; no hand-authored `*.avsc` is the basis for generated code
  (a standalone `.avsc` may be *emitted* for sharing, per R6, but it is not the
  input).
- R5. **Java-package isolation.** Generated Avro types live in their **own java
  package** (analogous to how the protobuf-java reference `X` types sit in a
  `.ref`/suffix package), so `org.apache.avro` is confined to the Avro types'
  classpath and **never reaches a generated record's classpath**.
- R6. **Schema output is both embedded and standalone.** Each generated
  `SpecificRecord` carries its Avro schema (mandatory for specific classes), and
  each `.proto` produces **one standalone `.avsc`** containing that proto's
  records (nested messages nested, oneof as a union), with a
  `// Generated by recordbuf -- DO NOT EDIT` header.
- R7. **Namespace = proto package.** The Avro namespace is the proto's `package`
  (or `java_package`, consistent with the existing proto→Java mapping).

**Well-known-types bridge**

- R8. **WKT bridge in the Avro runtime.** `:recordbuf-avro` hosts the WKT
  mapping — canonical Avro **schema fragments** plus **conversion** per known
  WKT (Wrappers, `Timestamp`, `FieldMask`, `Empty`, `NullValue`, `Duration`) —
  reused by every generated Avro converter rather than re-open-coded per record.
  WKTs map to **native Avro logical types**, not Confluent extensions.
- R9. **`Duration` is structured.** A proto `Duration` maps to Avro `{ seconds:
  long, nanos: int }` (a record), *not* logical `duration`; the structured form
  preserves full nanos and is lossless across the `record ↔ Avro` bridge.
- R10. **WKTs are recognized, not authored.** The generated Avro converter
  recognizes WKT fields on a message (by the WKT type they carry) and delegates to
  the runtime bridge; the **record layer does not learn about WKTs** — its
  generated fields stay untouched.
- R11. **Dynamic WKTs are out of scope and fail loud.** `Struct`, `Value`,
  `ListValue`, and `Any` are not mapped in v1 and are reported as generation
  errors, not silently coerced.

**Opt-in toggle**

- R12. **Global toggle, default off.** A global plugin parameter (a direct
  analogue of `package_suffix`) enables the Avro bridge; absent it, no Avro output
  is produced.
- R13. **Per-file override, bidirectional.** A per-file option is tri-state
  (`ON`/`OFF`/`INHERIT`); a set per-file value beats the global in **either**
  direction.
- R14. **No-op when off.** Output for a bridge that is off is byte-for-byte
  unchanged; enabling it adds only the Avro files.

**Type coverage, mapping, and fail-loud**

- R15. **Coverage follows the interop boundary.** The generator handles the proto
  constructs the existing interop already supports: scalars, `bytes`, `repeated`,
  nested `message`, `enum`, `oneof`, and `map<K,V>` with a string key and a
  scalar/enum value.
- R16. **Enum / presence / default rules.** A proto enum maps to an Avro `enum`
  whose **`default` is the number-0 constant**. A presence-tracked (nullable)
  scalar maps to `["null", T]`; a non-present scalar takes the Avro **default
  (zero-value)**.
- R17. **Fail loud at generation.** A type with no clean proto→Avro mapping —
  `uint64` (lossy; the record holds it as `long`), non-string map keys,
  `map` of message/enum values, and the deferred dynamic WKTs — is reported as a
  **generation error with a clear message** rather than emitting a silently wrong
  schema. Fail loud is the mechanism that keeps a path *deferred, not closed*.
- R18. **proto3 only.** v1 targets proto3, consistent with the existing interop
  harness; proto2 presence is out of scope.

**Reader/writer API and phasing**

- R19. **Schema-driven, hardened read/write.** Generated converters use
  `GenericDatumReader`/`GenericDatumWriter` + `SpecificData` on a pinned recent
  Avro (1.12.x GA), **not** the legacy class-name `SpecificDatumReader` (its
  no-arg constructor is deprecated and unsafe).
- R20. **Two serializers, in two phases.** **P1** ships the `record ↔ Avro`
  converter, the generated `SpecificRecord` types, and the schema (embedded +
  `.avsc`). **P2** adds lightweight record-level Avro wire wrappers
  (`toAvroBytes`/`fromAvroBytes`, throwing `IOException` like the protobuf
  `Codec`), strictly additive on P1. Both capabilities are wanted; P1 first.

**Runtime module and purity**

- R21. **Dedicated Avro runtime module.** A new module — working name
  `:recordbuf-avro` — holds `org.apache.avro` and the WKT bridge and is the **only**
  place the Avro dependency is declared. It mirrors `:recordbuf-runtime`
  (co-versioned from the root `subprojects {}` block; the Avro version pinned in
  the version catalog).
- R22. **Purity preserved.** No generated *record* may import `org.apache.avro`;
  the dependency is confined to the generated Avro types' package via R5/R21. New
  first-party code participates in the project's Spotless + NullAway/@`NullMarked`
  wiring, and generated Avro output stays format-clean.
- R23. **Confluent/SR stays a runtime seam.** Confluent/SR compatibility is not a
  hard fork of the bridge: v1 uses native logical types, and the seam to add a
  Confluent dialect later is preserved on the WKT bridge, not foreclosed.

**Interop and verification**

- R24. **Locked in the harness.** Avro interop is locked in
  `:recordbuf-fixtures` alongside the existing protobuf family: a
  `record → Avro → record` round-trip and a **reference/parity** check (against
  Avro's own serialization of the same data for the same fields, or a committed
  reference) — the orange being byte/field identity and round-trip equality.
- R25. **No change to the protobuf layers.** Enabling the Avro bridge changes
  nothing about Layer A/Layer B behavior or output; the protobuf and Avro paths
  are independent and coexist.

### Key Flows

- F1. **Record-hub Avro exchange.** proto bytes → record (protobuf decode) →
  Avro `SpecificRecord` (`record → Avro` converter) → Avro wire bytes → Avro
  `SpecificRecord` → record (`Avro → record` converter → protobuf encode).

  ```mermaid
  flowchart LR
    P[proto bytes] -->|protobuf decode| R[record]
    R -->|record -> Avro| A[Avro SpecificRecord]
    A -->|Avro serialize| W[Avro wire bytes]
    W -->|Avro deserialize| A2[Avro SpecificRecord]
    A2 -->|Avro -> record| R2[record]
  ```
  **Covers R3, R8, R10, R19, R24.**

- F2. **Toggle evaluation.** For a given file, is the bridge enabled? Per-file
  value, else the global.

  ```mermaid
  flowchart TB
    start[Generate Avro bridge for a file?] --> per{Per-file option set?}
    per -->|ON| on[Emit Avro bridge]
    per -->|OFF| off[Skip — no Avro output]
    per -->|INHERIT or absent| glob{Global toggle on?}
    glob -->|true (default: false)| on
    glob -->|false / absent| off
  ```
  **Covers R12, R13, R14.**

**Source-of-truth fan-out** (one proto feeds every bridge surface; the record is
the hub converting between them):

```mermaid
flowchart TB
  proto[".proto file<br/>source of truth"]
  proto -->|recordbuf plugin| rec["Java record<br/>the hub (immutable)"]
  proto -->|protobuf-java protoc| pb["protobuf-java X<br/>Layer B — existing"]
  proto -->|recordbuf plugin, opt-in| avro["Avro SpecificRecord + .avsc<br/>Layer B — new"]
  rec <-->|Codec.encode/decode, byte-identical| pb
  rec <-->|record <-> Avro converter (new)| avro
  rec -.-> future["future bridges<br/>(GraphQL / Jackson ...)"]
  avro --> rt[":recordbuf-avro<br/>WKT bridge + org.apache.avro"]
```
**Covers R1, R3, R5, R21, R23.**

**proto → Avro type mapping (v1)**

| Proto | Avro | Notes |
|---|---|---|
| `int32`/`fixed32`/`sfixed32` | `int` | |
| `int64`/`fixed64`/`sfixed64` | `long` | |
| `sint32`/`sint64` | `int` / `long` | |
| `uint32` | `int` | |
| `uint64` | — | **fail-loud** (lossy as `long`; R17) |
| `double`/`float` | `double` / `float` | |
| `bool` | `boolean` | |
| `string` | `string` | |
| `bytes` | `bytes` | |
| `enum` | `enum` | `default` = number-0 constant (R16) |
| scalar wrapper | `["null", logical]` | |
| `Timestamp` | `date`/`timestamp-micros` | bridge (R8) |
| `Duration` | `{seconds: long, nanos: int}` | structured, not logical duration (R9) |
| `FieldMask` / `Empty` / `NullValue` | `bytes` / `{"type":"record","fields":[]}` / `int` | bridge |
| `message` | `record` | nested message nested |
| `oneof` | `["null", …members]` union | |
| presence / optional | `["null", T]` or default | R16 |
| `repeated<T>` | `array` | |
| `map<K,V>` | `map` | string key + scalar/enum value; else out of scope (R15/R17) |
| `Struct`/`Value`/`ListValue`/`Any` | — | **out of scope, fail-loud** (R11) |
| `map` of message/enum value | — | **out of scope** (matches the codec/Interop boundary) |

### Acceptance Examples

- AE1. **`Timestamp` round-trips micros.** A `Timestamp` field encodes to
  `timestamp-micros` and decodes back to the same seconds+nanos. **Covers R8.**
- AE2. **`Duration` round-trips as a pair.** `{seconds,.nanos}` survive
  `record → Avro → record` exactly, including a value whose nanos exceed 1 s.
  **Covers R9.**
- AE3. **Wrapper field maps to `[null, logical]`.** A scalar wrapper field is
  present as a union and absent as `null`. **Covers R8, R16.**
- AE4. **Non-present scalar → zero-value.** A non-present scalar decodes to its
  Avro default (zero); a present one carries its value. **Covers R16.**
- AE5. **Enum default round-trips.** Each enum constant round-trips by number and
  the number-0 constant is the declared Avro default. **Covers R16.**
- AE6. **Off is a no-op.** A bridge that is off emits nothing new; the record,
  its `Codec`, and the protobuf Layer A/B output are unchanged byte-for-byte.
  **Covers R14, R25.**
- AE7. **Per-file beats global both ways.** Global ON + per-file OFF yields no
  Avro for that file; global off + per-file ON yields Avro for that file.
  **Covers R12, R13.**
- AE8. **Fail-loud on unsupported.** A field using `uint64`, a non-string-map-key
  or message/enum-valued map, or a dynamic WKT (`Struct`/`Value`/`ListValue`/`Any`)
  surfaces a clear generation error; no partial or wrong schema is emitted.
  **Covers R11, R17.**
- AE9. **Schema is emitted both ways.** Each generated `SpecificRecord` exposes its
  schema, and a valid standalone `.avsc` (with the DO-NOT-EDIT header, namespace =
  proto package) is produced per proto. **Covers R6, R7.**
- AE10. **`.avsc` + specific types interop.** The standalone `.avsc` and the
  generated `SpecificRecord` classes load and round-trip the same data
  (the `.avsc` and the embedded schema are in agreement). **Covers R6.**
- AE11. **P1 converter round-trips.** `record → SpecificRecord → record` is
  field-identical for every supported field family; P2's `toAvroBytes`/`fromAvroBytes`
  round-trip on top of it. **Covers R2, R20.**
- AE12. **Hardened reader/writer.** The generated converters run on the
  schema-driven `GenericDatumReader/Writer` + `SpecificData` API; no legacy
  class-name `SpecificDatumReader` is exercised. **Covers R19.**
- AE13. **Record purity.** No generated record imports `org.apache.avro`; the
  dependency is confined to the generated Avro package and `:recordbuf-avro`.
  **Covers R5, R21, R22.**
- AE14. **proto3 only.** A proto2 file is not a v1 target. **Covers R18.**
- AE15. **Clean first-party code.** New `:recordbuf-avro` source and the generated
  Avro output pass Spotless and participate in NullAway/`@NullMarked` consistently
  with the project wiring. **Covers R22.**

### Scope Boundaries

**In scope (v1)**

- The record-based **record ↔ Avro `SpecificRecord` converter** and the generated
  `SpecificRecord` types, emitted from the proto.
- The standalone `.avsc` per proto (plus the mandatory embedded schema).
- The **WKT bridge** in `:recordbuf-avro` for the R8/R9 set, native logical types.
- The **opt-in toggle** (global param + per-file tri-state, default off).
- The **`GenericDatumReader/Writer` + `SpecificData`** reader/writer (P1).
- Locking the above in `:recordbuf-fixtures` with a reference/parity check.

**Deferred for later (paths kept open)**

- **Confluent / Schema-Registry dialect** on WKTs — a later feature on the bridge
  seam (R23), not foreclosed.
- **Dynamic WKTs** — `Struct`, `Value`, `ListValue`, `Any` (fail-loud by error
  until supported; R11).
- **`uint64` lossless** — deferred as a `byte[]`/logical variant; v1 fails loud
  (R17).
- **Map of message/enum** — out of scope for v1, matching the codec/Interop
  boundary (R15/R17).
- **Per-message toggle granularity** — v1 is global + per-file (R13); per-message
  is deferred.
- **P2 record-level Avro wire wrappers** (`toAvroBytes`/`fromAvroBytes`) — the
  additive second phase on P1 (R20).
- **Other bridges** — GraphQL schema generation and Jackson JSON serialization,
  built on the same extension point as future registrations (R1).
- **Avro `GenericRecord`/`Map`** representation — a deferred variant; v1 is
  specific.
- **proto2** presence (R18).
- **Stream-direct** Avro encode/decode (into/out of a stream, no intermediate
  `byte[]`) — a carry-variant if P2 profiling shows the intermediate matters.
- **Cross-file WKT/message closure** beyond the same-file shape the interop
  supports today.

**Outside this product's identity**

- A new data model that *is* the Avro type (v1 keeps the record as the type;
  Avro is a converter *on* it).
- Any change to the existing codec/`Encoder`/`Decoder`/`Wire` or the protobuf-java
  interop contract.
- Schema Registry *deployment* (registration, versioning, lookup) — v1 produces
  schemas compatible with later registration; it does not do registry I/O.

### Explicit Non-Goals

- No `org.apache.avro` on any generated *record* classpath (R5/R21/R22).
- No change to Layer A / Layer B / `Codec` semantics or output (R25).
- No hand-authored schema as the source of recordbuf output (R4).
- No silent coercion of an unmappable type — fail loud instead (R17).
- No `GenericRecord`/`Map` representation in v1 (R2).
- No proto2 in v1 (R18).
- No Schema-Registry runtime integration (registration/lookup) in v1.
- No GraphQL or JSON bridge in v1 — the seam exists for them, the modules do not
  yet (R1).

---

## Assumptions

- Generated Avro-output consumers will carry `org.apache.avro` on their classpath
  for the *Avro* surface — the same way a protobuf consumer already carries
  `com.google.protobuf:protobuf-java` — while the record's classpath stays free of
  `org.apache.avro` (R5/R21).
- The Avro version is pinned at a recent GA (1.12.x as of this writing; the 1.11.x
  line is a maintained LTS) and stays a **stable wire + schema resolution**, so
  emitted schemas and generated read/write stay compatible (see **Sources /
  Research**).
- The proto is proto3 (R18); proto2 fields are not present.
- The fixture/interop harness already provides a per-family structure the Avro
  round-trip + reference parity can layer onto, mirroring the protobuf family.
- A WKT on a message is recognizable at generation time (the field carries a WKT
  type), so the converter can delegate to the bridge without changing the record
  layer (R10).
- No cross-file WKT/message nesting beyond the same-file shape in v1.

---

## Open Sub-Decisions for ce-planning

These are deliberately not resolved in brainstorm; they are the shape-level
questions for the next artifact.

- **R5 / R7 package + namespace mechanism.** How the generator is told the
  generated Avro types' target package: a new `FileOptions` knob, an Avro-bridge
  analogue of `package_suffix`, or a sibling of `java_package`. The namespace =
  proto package is assumed, but the exact wiring is a ce-planning decision.
- **R1 extension-point shape.** What "a thin generic extension point" looks like in
  the model — an enum of bridge kinds, a registration interface, or a config — and
  where Avro registers. The requirement is extensibility (a future bridge is a
  registration, not a core change), not a specific shape.
- **R12 / R13 option wiring.** Reuse the existing `FileOptions` channel for the
  per-file tri-state and a new **global** param for the on/off default (the
  `package_suffix` analogue), vs a single new flag; the exact tag and key are
  planning.
- **R16 / R9 enum + WKT mapping details.** Exact Avro `enum` default policy and the
  per-WKT schema fragments the bridge emits (e.g. exact `timestamp-micros`
  `type`/logical-type encoding; `FieldMask` as `bytes` vs its own representation).
- **R20 converter/wrapper shape.** The exact converter type shape
  (`fromAvro(SpecificRecord) → R` / `toAvro(R) → SpecificRecord` vs a builder-driven
  variant vs both) and the P2 wrapper signatures.
- **R21 module name + layout.** Final name of the Avro runtime module
  (`:recordbuf-avro`) and how it hosts the WKT bridge + `org.apache.avro` while
  staying out of the record's classpath.
- **R8 WKT recognition mechanism.** How the generator recognizes a WKT field
  (a type-name check against the known WKT set that the bridge names) without the
  record layer learning about WKTs.
- **R24 reference/parity oracle.** Parity against the same-proto protobuf-java
  reference, a committed Avro reference fixture, or Avro's own runtime
  serialization of the same data — the exact oracle and per-family matrix.
- **Reader/writer scope (P1).** Whether `GenericDatumReader/Writer` alone suffices
  for v1 or whether the generated `SpecificRecord` needs a custom codec beyond
  what `SpecificData` provides.

---

## Sources / Research

- **Apache Avro current version.** Download index as of this writing shows
  **1.12.2** (latest GA) and a separate **1.11.5** LTS line — both postdate
  Jan 2025. Pin on the stable GA line (1.12.x) for v1.
- **Avro 2.0.** The current major effort reworks schema syntax toward JSON-Schema
  compatibility; it is *not* the pin target — v1 uses the classic schema syntax and
  the 1.11/1.12 runtime.
- **Wire/spec stability.** The wire format and binary encoding are defined by the
  Avro 1.0 spec and have not changed across 1.x releases; **schemas remain the
  authoritative source of the schema** and schema resolution governs
  read-compatibility. This means emitted schemas and generated read/write are
  stable, and schema-driven read/write (R19) is the correct API stance.
- **Avro Java runtime API.** In 1.10/1.12, `SchemaBuilder` (programmatic
  `Schema.create*`) and `GenericDatumReader`/`GenericDatumWriter` are the
  supported paths; the class-name `SpecificDatumReader` is a legacy shim whose
  no-arg constructor is deprecated/unsafe (a class-scan / `AVRO-3659` family of
  issues). Prefer `GenericDatumReader`/`Writer` + `SpecificData`.
- **Avro logical types** (native, non-Confluent): `timestamp-micros` for
  `Timestamp`; `Duration` has a logical form but it is micros-only, so v1 uses the
  structured `{ seconds: long, nanos: int }` (R9). Confluent's
  `org.apache.avro:avro-confluent` (or a SR connector) is the *later* path for
  Confluent `logicalType` extensions and `Timestamp` with `CONFLUENT.WELL_KNOWN`
  semantics.
- **recordbuf's existing bridge pattern.** The protobuf-java interop
  (`Layer A` always-on `ProtobufMessage`/`ProtobufMarshaller`, `Layer B`
  `InteropGenerator` structural converter on an opt-in `recordbuf.protobuf_java`
  option, `:recordbuf-runtime` hosting the framing, `:recordbuf-fixtures` as
  the orange) is the exact analogue the Avro bridge mirrors.
- **Proto WKT typeUrl → Java record mapping** (from `:recordbuf-runtime`'s
  `WktFieldMappings`): `Timestamp` → `Instant`, `Duration` →
  `Duration`, `FieldMask` → `Set<String>`, nulls/wrappers per type. The Avro
  bridge translates *these* record field types to Avro schema fragments
  (native logical types, structured duration).
- **recordbuf toggle knobs.** `package_suffix` is the global-param analogue; the
  per-message `recordbuf.protobuf_java` option is the per-file override analogue.
