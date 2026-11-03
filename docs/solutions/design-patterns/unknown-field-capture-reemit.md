---
title: Never-silent unknown-field capture and byte-exact re-emit via an always-on carrier
date: 2026-11-03
category: docs/solutions/design-patterns
module: bijnagte.recordbuf
problem_type: design_pattern
component: tooling
severity: medium
applies_when:
  - "A generated record/message codec must lose no wire data when read through a schema that predates some fields"
  - "A decode is order-independent (dispatch on tag) and must survive a round-trip byte-for-byte"
  - "Deciding how a record represents data its own schema does not model"
related_components:
  - "bijnagte.recordbuf.runtime"
  - "bijnagte.recordbuf.plugin.writer.CodecGenerator"
tags:
  - protobuf
  - wire-format
  - unknown-fields
  - unknown-field-set
  - codegen
  - round-trip
  - re-emit
  - recordbuf
---

# Never-silent unknown-field capture and byte-exact re-emit via an always-on carrier

## Context

`recordbuf` generates Java records with a hand-rolled `Codec` that reads and
writes protobuf's binary wire format. A decode under a schema that **predates a
field on the wire** — a newer schema's field, or a field the current `.proto`
does not model — would silently drop that field's bytes: decode, do work,
re-encode, and the data was gone, with no error. (The sibling learning
[proto-codegen-wire-compat-interop-harness](../design-patterns/proto-codegen-wire-compat-interop-harness.md)
names the broad class — *silent* wire bugs — and guards it with a byte-equality
oracle against protobuf-java; this learning is the runtime side of that class:
what a codec does with bytes it does *not* model.)

protobuf-java's `UnknownFieldSet` already solves exactly this. The problem was not
"should we capture unknown fields?" — the answer is *yes, always* — but *what
shape does the captured data take on the record*, and *how does a record that is
not its own wire source handle it* (e.g. a structural Java-to-Java converter that
never sees the wire).

## Guidance

### 1. An always-on carrier on the record, not a decode-on-demand helper

The carrier is a last component on **every** generated record:

```java
// generated record (CodecGenerator / CodeGenerator.java:176-182)
public record Compat(
        int i,
        long fx,
        String s,
        byte[] b,
        List<Integer> packed,
        Sub nested,
        List<Sub> subs,
        Map<String, Integer> m,
        Integer presence,
        UnknownFields unknownFields        // <- always-present carrier;
) implements ProtobufMessage { ... }       // empty by default via the builder
// or a no-capture decode (see below).
```

Java records cannot give a component a default on the *canonical* or *compact*
constructor, so the `empty()` default lives on the **builder** and on the
**decode path**, not in the ctor: the builder's slot defaults to `empty()`
(`CodeGenerator.java:270`), and a decode that saw nothing yields `empty()`
through `unknown.build()` (`CodecGenerator.java:365`). Either way `empty()` is the
shared singleton instance, not a fresh collection per record — so the common
"no unknown fields" case adds **no payload and no allocation**.

Decode captures every field the schema does not model, in wire order, into that
carrier (`CodecGenerator.java:333-334` opens the capture, `:352` is the
`default` arm, `:365` closes the ctor with `unknown.build()`):

```java
// generated decode
var unknown = UnknownFields.builder();
... decode loop, dispatching on tag:
default -> Wire.capture(in, unknown, tag);   // CodecGenerator.java:352
...
return new Compat(...known fields..., unknown.build());
```

Encode re-emits the carrier after the known fields, but **only when non-empty**
(`CodecGenerator.java:95-98`):

```java
// generated encode
... write the known fields in ascending field-number order ...
if (!v.unknownFields().isEmpty())
    Wire.writeUnknown(out, v.unknownFields());   // CodecGenerator.java:97-98
return ...;
```

The `!isEmpty()` guard is load-bearing: it keeps the empty path from touching the
stream at all, so the empty carrier is observably free — a record that decoded
no unknown fields re-encodes to **byte-identical** output to a codebase with no
carrier.

### 2. Capture the raw post-tag bytes; never guess the proto type

Capture is wire-honest and type-agnostic (`bijnagte.recordbuf.runtime.UnknownFields.Builder.add`,
`UnknownFields.java:132`): it reads each unknown field's value by its on-wire
`WireType` and stores the raw bytes — a varint field its varint bytes, an
`I64` its 8 raw bytes, a length-delimited field its length prefix plus content.
It does **not** infer that this field "was a string" or "was a nested message".
The two group wire types (`START_GROUP`/`END_GROUP`) are rejected — the `default`
arm of the capture throws, and proto3 never emits group fields anyway.

On inspection, the carrier offers **wire-honest read-shapes** that never assert
the proto type (`bijnagte.recordbuf.runtime.UnknownField`): `asLong()`/`asInt()`
read a varint/fixed value, `asString()`/`asBytes()` read a length-delimited
value's **content** as UTF-8/raw bytes. A mis-shaped call throws
`IllegalStateException` rather than coercing a wrong value.

Because a length-delimited field is stored as *[length varint][content]* for a
byte-exact re-emit, the content-stripping accessors must peel the length prefix:
`asBytes()` skips the length varint and returns the content, so a captured
`bytes`/`string` field inspects as its content, not a length-prefixed blob. The
raw storage keeps the prefix so encode stays byte-exact; only the accessor
surfaces content. This split is the one non-obvious part of the feature.

### 3. What to NOT re-emit: map entries

A `map<K,V>` is a `repeated` length-delimited entry message; on the wire a
stray entry has no field number of its own, so it cannot be re-emitted as a real
field. A map entry's decoder therefore has **no carrier** and keeps the old
`Wire.skip` behavior for un-modeled entry sub-fields — re-emitting a stray entry
as a bare tag would corrupt a *modeled* field number sharing that tag. The
carrier reaches every message record; map entry records are the deliberate
exception (their ctor is `(key, value)` — `CodecGenerator.java:196, 280`).

### 4. A Java-to-Java converter never sees the wire — it seeds empty

Layer B interop (`bijnagte.recordbuf.plugin.writer.InteropGenerator`, the
`Interop.from(X)` structural converter, which builds a record from the equivalent
protobuf-java message `X`) **cannot** produce a populated carrier: it constructs a
record field by field from a Java object, never reading a stream. So it seeds
`UnknownFields.empty()` explicitly (`InteropGenerator.java:150-154`):

```java
// InteropGenerator.emitFrom — Layer B `from(X)` seeds an empty carrier
...
bijnagte.recordbuf.runtime.UnknownFields.empty()   // InteropGenerator.java:154
```

The comment there states the reasoning: *unknown fields do not cross the
protobuf-java boundary* — they are a wire-only phenomenon, and a converter that
never touches a stream has none to carry.

### 5. Ergonomics: keep the single canonical ctor; use the builder in callers

Adding the carrier changes the **canonical constructor** — every caller must now
pass it. The chosen ergonomic answer is *not* a convenience constructor (that would
multiply ctors and hide the invariant), but the **fluent builder**, whose
`unknownFields(...)` setter defaults to `empty()` so the common call site writes
nothing:

```java
// before (no carrier)
Sample s = new Sample(42, "x");
// after — the builder's unknownFields slot defaults to empty(); no caller change needed
Sample s = Sample.builder().i(42).label("x").build();
```

Migrating call sites to the builder makes them order-independent and resilient to
future component additions. The record itself is untouched — only the caller's
construction path changes.

## Why This Matters

The bug class this pattern exists for is **silent**: a wrong or missing
re-emit produces bytes the reader does not understand — corruption, not an
exception. Locking the invariant that *a record read through an older schema
survives a round-trip byte-for-byte* turns silent data loss into an invariant a
round-trip test can hold. The always-on-but-empty carrier is the only shape that
makes "loss-free on the wire" a property of the type rather than a discipline a
caller must remember: the carrier is part of the record, `empty()` is free, and
the encode guard makes the empty path observably free.

## When to Apply

- Any codec that decodes an order-independent, tag-dispatched binary format and
  can lose data for fields its schema does not model (this is the universal case
  for versioned binary formats — anything that is ever read older-than-newest).
- Adding or changing the un-modeled-field behavior of a generated codec: this
  is the reference for the carrier shape, the byte-exact split, and the two
  deliberate exceptions (map entries, Java-to-Java converters).

## When NOT to apply

- A format with no version skew (read newest-on-newest only): there is no
  un-modeled field to lose, and an always-on carrier is dead weight.
- A schema that is *not* forward-compatible by tag (e.g. a positional,
  no-tag format): an un-modeled "field" has nothing to re-emit by, so capture is
  meaningless.

## Rejected alternatives

- **Decode-on-demand carrier (decode the captured bytes into a typed structure
  only when a getter asks for it):** rejected. It trades a small always-on cost
  for a larger one only *some* callers ever pay, but it also makes the record's
  contents depend on call order and time, and it cannot round-trip byte-exactly
  without re-storing the raw bytes anyway. The always-on raw carrier keeps the
  bytes exactly as seen on the wire.
- **Decode into the raw stream bytes directly (carry the whole message as a
  `byte[]`):** byte-exact but opaque and a per-message allocation, and it throws
  away the order in which decode saw the bytes the reader needs to re-emit them
  in order.
- **A convenience constructor `new Sample(...fields)` with `unknownFields`
  defaulted at the ctor default:** rejected — it multiplies ctors and hides the
  "carrier is always present" invariant behind a second entry point. The builder
  gives the same ergonomics with a single ctor.
- **Auto-infer the proto type of a captured field:** rejected on principle —
  wire-honest capture stores bytes, and typed guesses are exactly the wrong kind
  of silent coercion this feature exists to prevent.

## Examples

**End-to-end round-trip — the guard that proves the invariant** (a record that
decoded unknown bytes re-encodes byte-for-byte; this is the shape
`UnknownFieldRoundTripTest` holds, and it belongs in the per-family interop
harness next to `byteEqual`/`interopDecodesProtobuf`/`roundTrip`):

```java
@Test void unknownFieldRoundTripsByteForByte() throws Exception {
    byte[] known = Compat.Codec.encode(Sample.builder().i(42).build());
    byte[] withUnknown = concat(known, appendVarintField(99, 123456)); // a newer field
    var parsed = Compat.Codec.decode(withUnknown);
    // The unknown field is captured, inspectable in raw-wire shape:
    var captured = parsed.unknownFields().fields().get(0);
    assertEquals(99, captured.fieldNumber());
    assertEquals(WireType.I32, captured.wireType());
    assertEquals(123456, captured.asLong());
    // ...and re-emits byte-for-byte:
    assertArrayEquals(withUnknown, parsed.toByteArray());
}

// A length-delimited unknown (a newer string/bytes field) inspects as content:
var s = Compat.Codec.decode(concat(known, appendLengthDelimitedField(98, "future-data")));
assertEquals("future-data", s.unknownFields().fields().get(0).asString());
```

The empty path is observably free:

```java
@Test void decodeWithoutUnknownFieldsIsByteIdentical() throws Exception {
    var parsed = Compat.Codec.decode(Sample.builder().i(42).build());
    assertTrue(parsed.unknownFields().isEmpty());
    assertArrayEquals(Sample.builder().i(42).build().toByteArray(),
                      parsed.toByteArray());
}
```

## Related

- [proto-codegen-wire-compat-interop-harness](../design-patterns/proto-codegen-wire-compat-interop-harness.md)
  — the sibling learning: the *test* side of the same silent-wire-corruption
  class. The per-family `byteEqual`/`roundTrip` oracles in that harness are where
  the unknown-field round-trip guard above lands.
- `docs/plans/2026-11-03-001-feat-unknown-fields-plan.md` — the (re-grounded)
  implementation plan for this feature, with the KTDs (KTD1 multi-module flat
  layout, KTD3 single canonical ctor).
- `docs/plans/2026-11-02-005-feat-protobuf-interop-bridge-plan.md` — the prior
  "Layer B interop bridge" plan this feature's Layer B `empty()` seeding
  (`InteropGenerator.java:154`) extends.
