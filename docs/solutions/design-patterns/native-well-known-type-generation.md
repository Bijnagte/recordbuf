---
title: "Native well-known-type generation for protoc-gen-recordbuf"
date: "2026-11-07"
category: "docs/solutions/design-patterns"
module: "bijnagte.recordbuf.generator + bijnagte.recordbuf.runtime"
problem_type: "design_pattern"
component: "tooling"
severity: "medium"
applies_when:
  - "Generating records from a .proto that uses google.protobuf.* well-known types (Timestamp/Duration/FieldMask/Empty/wrappers)"
  - "Deciding how a well-known type maps to a generated Java type"
  - "Extending the generator to recognise another WKT or another mapping"
tags:
  - "protobuf"
  - "well-known-type"
  - "protoc-gen-plugin"
  - "code-generation"
  - "java-time"
  - "interop"
  - "recordbuf"
---

# Native well-known-type generation for `protoc-gen-recordbuf`

## Context

`protoc-gen-recordbuf` (the `:recordbuf-generator` plugin) turns a `.proto` into
immutable Java records + codecs, and for interop it also emits a sibling
protobuf-java **reference codec** that round-trips byte-for-byte against a
protoc-generated class (see
`docs/solutions/design-patterns/proto-codegen-wire-compat-interop-harness.md`).

Before this change, every `google.protobuf.*` well-known type (WKT) in a proto
would be generated as an **opaque nested record** (a message that happens to be a
WKT is just another message). That is *correct* but *ugly* for the user: a
`google.protobuf.Timestamp` field becomes an opaque nested record instead of a
`java.time.Instant`. The goal: **generate native Java types for WKTs** without
breaking wire-compat or interop. This is a design decision, not a bug fix.

## Guidance

The pattern is **three coordinated layers**:

### 1. A generator-side WKT registry (the single source of truth)

Each `WellKnownTypes.Entry(fqn, kind, javaType, wire, zeroDefault)` declares how one WKT is handled; entries fall into three `Kind`s:

| WKT | Kind | Native Java type | zero default |
|-----|------|------------------|--------------|
| `Timestamp` | STRUCTURED | `java.time.Instant` | `Instant.EPOCH` |
| `Duration` | STRUCTURED | `java.time.Duration` | `Duration.ZERO` |
| `FieldMask` | STRUCTURED | `bijnagte.recordbuf.runtime.FieldMask` | `FieldMask.empty()` |
| `Empty` | STRUCTURED | `bijnagte.recordbuf.runtime.Empty` | `Empty.INSTANCE` |
| `BoolValue` / `FloatValue` / `DoubleValue` / `Int32Value` / `UInt32Value` / `SInt32Value` / `Fixed32Value` / `SFixed32Value` / `Int64Value` / `UInt64Value` / `SInt64Value` / `Fixed64Value` / `SFixed64Value` / `StringValue` | WRAPPER | native `Boolean`/`Float`/`Double`/`Integer`/`Long`/`String` (≤ `Integer`/`Long` for the 32/64-bit int variants) | `@Nullable` (proto3 presence) |
| `BytesValue` | BYTES_WRAPPER | `bijnagte.recordbuf.runtime.Bytes` | `null` |
| `Struct` / `Value` / `ListValue` / `Any` / `NullValue` | DYNAMIC_UNSUPPORTED | _(none)_ | fail loud at generation |

- **STRUCTURED** WKTs carry a non-null zero value when absent; `zeroDefault` is that expression, built from the field's *imported* simple type name.
- **WRAPPER / BYTES_WRAPPER** fields are `@Nullable` presence — proto3 singular-wrapper semantics: null when the wrapper is absent on the wire.
- **DYNAMIC_UNSUPPORTED** WKTs have no canonical native encoding; a reference to them **fails loud at generation** (rule R11) rather than silently emitting an opaque nested record.

The registry lives in `generator/.../plugin/types/WellKnownTypes.java`; field resolution reads it so a field *of* a recognised WKT type is declared with the native type above. Living in one place is what keeps the design coherent — adding a WKT is a one-row change, and the codec + interop code both read the same table.

### 2. A runtime helper surface that bridges native ⇋ the WKT wire shape

Each WKT needs a helper that translates between **the native Java type** and the
protobuf WKT's **wire representation** (`Timestamp` is an `int64 seconds +
int32 nanos` pair, `Duration` likewise). The runtime carries these in
`bijnagte.recordbuf.runtime.wkt` (`.../wkt/Wkt.java` + a per-package `package-info.java`). The
helpers take **content bytes**, not a live stream — the codec frames one WKT's
payload as length-delimited bytes:

```java
// runtime/.../wkt/Wkt.java  (content-style helpers; IOException only on the encode path)
public static byte[]   timestamp(Instant v)         throws IOException;   // -> int64 seconds + int32 nanos
public static Instant  parseTimestamp(byte[] content);
public static byte[]   duration(Duration v)         throws IOException;
public static Duration parseDuration(byte[] content);
public static byte[]   fieldMask(FieldMask v)      throws IOException;
public static FieldMask parseFieldMask(byte[] content);
public static byte[]   empty(Empty v);
public static Empty    parseEmpty(byte[] content);

// scalar wrappers + the bytes wrapper handled generically via a WireScalar-tagged `Wrap`:
public static byte[]   wrap(Wrap wire, Object value)   throws IOException;   // e.g. Wrap.DOUBLE -> native Double
public static Object   parse(Wrap wire, byte[] content);
```

The same surface covers each wrapper and `FieldMask`/`Empty`. The generated codec
**calls** `Wkt.*` (registered as imports — see
`docs/solutions/tooling-decisions/enable-unnecessarily-fully-qualified-codegen-short-names.md`
for why the imports are clean, not FQN) so native Java types round-trip on the
WKT wire shape. This is the core trick: **the record is typed natively, but the
codec speaks WKT bytes** — so the native record stays byte-compatible with the
protobuf reference runtime.

### 3. A generated reference codec that locks interop

For each WKT the generator also emits a **protobuf-java reference codec** (the
`.ref` codec) that reads/writes via `com.google.protobuf`'s generated messages
(`com.google.protobuf.Timestamp`, `com.google.protobuf.Duration`, …). The
reference codec's bytes must equal the native record's bytes, which is the
`WktInteropTest` invariant. The generated record + its native codec + the
reference codec form a closed loop: encode a record → encode a reference → assert
byte equality; and encode a reference → decode a record → assert semantic
equality. This is how the pattern proves native-typed records are
**wire-interoperable** without depending on the WKT messages' Java types in the
record's API.

In the fixtures this is realised by `fixtures/src/main/proto/wellknown.proto`
+ `wkt.proto`, generated into `fixtures/build/generated/...` as `*.proto.rec.java`
records + reference codecs under the same fixture package.

## Why This Matters

- **Usability.** A `google.protobuf.Timestamp` field becomes a
  `java.time.Instant`; a `google.protobuf.Int32Value` becomes an `Integer`. This
  matters: WKTs are the most commonly-used protobuf types; generating native
  types makes the generated API pleasant for Java consumers rather than leaking
  the WKT messages through.
- **Wire-compat preserved.** Because the codec arms call `Wkt.*` to bridge
  native ⇋ wire, the **bytes** a native-typed record produces are identical to
  the bytes a protobuf-generated class produces. Byte-equality against the
  reference is a tested invariant (`WktInteropTest`), not just an assertion.
- **Extension is a one-row change.** Adding a new WKT is a row in
  `WellKnownTypes` + a pair of `Wkt.*` helpers (encode/decode) + a generated
  reference codec. The registry pattern keeps the three layers (generator
  registry, runtime helpers, reference codec) in lock-step.
- **No protobuf dependency in the record's API.** The `Wkt.*` helpers isolate
  the wire shape inside the runtime; a record of `Timestamp → Instant` carries no
  `com.google.protobuf` type in its fields — it's a clean Java record.

## When to Apply

- Any generated-code emitter that supports a **well-known type catalogue**
  (Protobuf WKTs, gRPC canonical structs, or an internal domain catalogue of
  "canonical" types) and wants to generate **domain-native** Java types.
- Any design where a generated record must be **API-clean and dependency-light**
  at the type level, but **wire-compatible** with a reference runtime.
- Any time the generator adds a WKT — the registry + Wkt helper + reference codec
  loop is the three-part checklist.

## When NOT to Apply

- When the consumer wants **the WKT message as a nested record** (e.g.
  `google.protobuf.Any` whose payload is arbitrary bytes). Then the mapping
  stays an opaque nested record and the registry's miss path falls through to
  the normal "unresolved message" resolution. Add the WKT to the registry only
  when the native mapping is a **good** native type, not just any.
- When the project's interop contract explicitly wants **protobuf messages
  through the record's API** (e.g. because the record needs to hand the WKT
  message to a protobuf consumer). Then native would leak a dependency the
  caller did not expect.

## Examples

Native-typed WKT fields in the generated output — **illustrative** shape
(verbatim `.rec.java` files are regenerated by the build; the registry above is
the source of truth):

```java
// A message that references well-known types ends up holding native Java types:
public record Widget(
    java.time.Instant                created,    // google.protobuf.Timestamp
    java.time.Duration               timeout,    // google.protobuf.Duration
    @Nullable java.lang.Double        ratio,      // google.protobuf.DoubleValue (nullable presence)
    bijnagte.recordbuf.runtime.Bytes  payload,    // google.protobuf.BytesValue
    bijnagte.recordbuf.runtime.FieldMask fields   // google.protobuf.FieldMask
) { /* ... codec arms call Wkt.* to bridge native <-> WKT wire shape ... */ }
```

Generator registry entry (verbatim shape, abbreviated):

```java
// generator/.../types/WellKnownTypes.java
private static final Entry TIMESTAMP =
    new Entry(TS, Kind.STRUCTURED, "java.time.Instant", null, "Instant.EPOCH");
private static final Entry DURATION =
    new Entry(DUR, Kind.STRUCTURED, "java.time.Duration", null, "Duration.ZERO");
private static final Entry DOUBLE_VALUE =
    entry(Kind.WRAPPER, "DoubleValue", "java.lang.Double", WireScalar.DOUBLE);
// ... one entry per WKT; Struct/Value/ListValue/Any/NullValue are dynamic(...).
```

Runtime helper (verbatim shape, abbreviated):

```java
// runtime/.../wkt/Wkt.java
public static byte[]  timestamp(Instant v) throws IOException { /* int64 seconds + int32 nanos */ }
public static Instant parseTimestamp(byte[] content) { /* ... */ }
```

The `DYNAMIC_UNSUPPORTED` miss path — `Struct`/`Value`/`ListValue`/`Any`/
`NullValue` — **fails loud at generation** (rule R11) because they have no
canonical native encoding; a reference to them is rejected rather than silently
mapped to an opaque record.

## Related

- `docs/solutions/design-patterns/proto-codegen-wire-compat-interop-harness.md`
  — the generated reference codec that locks the native record's interop to
  protoc.
- `docs/solutions/tooling-decisions/enable-unnecessarily-fully-qualified-codegen-short-names.md`
  — companion tooling decision: the generator emits the `Wkt` import cleanly
  rather than FQN.
- `docs/solutions/tooling-decisions/enable-nullaway-null-checks.md` — NullAway
  enforcement on the same codebase.
- Commit `86c6bce` (`feat: native well-known-type generation + enable
  UnnecessarilyFullyQualified`): the landing commit; `WktTest` (9/9) and
  `WktInteropTest` (9/9) are the interop invariants that lock the design.
