# Encode Allocation Fix and Decode Funding Policy - Plan

> **artifact_contract**: ce-unified-plan/v1
> **artifact_readiness**: implementation-ready
> **product_contract_source**: ce-brainstorm

## Goal Capsule

**Objective**: Fix the recordbuf encode allocation gap (25x native on small messages) by changing the generated codec's buffer strategy, preserve unknown fields in the interop path, and establish an explicit funding policy for the decode path (kept but unfunded).

**Product authority**: User (project owner)

**Open blockers**: None. The root cause is identified and the fix strategy is clear.

## Product Contract

### Problem

The recordbuf generated codec's encode path allocates ~4,360 bytes per operation for a 3-field message (wire size: ~14 bytes) because `Encoder.start()` wraps a `ByteArrayOutputStream` in `CodedOutputStream.newInstance(OutputStream)`, which allocates a 4,096-byte internal buffer. This is 25x the allocation of native protobuf-java encode (176 B/op) and 20x the interop+native path (216 B/op).

The decode path is slower than the interop decode path (native protobuf decode + `Interop.from()`) for large messages (0.66M vs 1.12M ops/s on the conformance message), allocates more than native (3,512 vs 1,656 B/op), and carries a security/maintenance liability that native protobuf has spent years hardening against.

The interop `from()` conversion discards the protobuf-java message's unknown fields (passes `UnknownFields.empty()`), so the recommended read path (native decode + `Interop.from()`) loses unknown-field fidelity that the recordbuf decode path preserves.

### Decisions

1. **Encode allocation fix**: Change the generated codec's encode path from `ByteArrayOutputStream` + `CodedOutputStream.newInstance(OutputStream)` to a size-computed `byte[]` + `CodedOutputStream.newInstance(byte[])`. The generated codec already knows every field and its value — it can compute the exact wire size before allocating. This is the same strategy protobuf-java's native messages use.

2. **Decode path — keep but unfunded**: The decode codec and `fromBytes()` stay in the generator output. No breaking change. However, the decode path is explicitly unsupported: no new features, no security fixes, no performance investment. The recommended read path for new code is native protobuf decode + `Interop.from()`.

3. **Read path recommendation**: New code should use `NativeMessage.parseFrom(bytes)` + `Interop.from(nativeMessage)` instead of `Record.fromBytes(bytes)`. This gives security hardening from protobuf-java and better performance on large messages.

4. **Unknown-fields interop fidelity**: The generated `Interop.from()` must preserve the protobuf-java message's unknown fields. A new runtime method `UnknownFields.fromProto(UnknownFieldSet)` converts the protobuf-java carrier to the recordbuf carrier. The generated interop calls it instead of `UnknownFields.empty()`.

5. **FIXED32 wire type gap**: The recordbuf `WireType` enum has no entry for fixed32 (wire code 5). The decode path's `UnknownFields.Builder.add()` throws on fixed32 unknown fields. This is a pre-existing gap that must be fixed before the interop conversion can handle fixed32 unknown fields correctly.

### Success Criteria

- Encode allocation at or below native protobuf allocation: ≤176 B/op (small), ≤1,544 B/op (conformance)
- Encode throughput at or above the current interop+native path after GC pressure drops: ≥6.3M ops/s (small), ≥0.90M ops/s (conformance)
- The generated codec's encode path no longer allocates a 4K buffer
- The decode path is documented as unfunded in the generator output and project docs
- `Interop.from()` preserves unknown fields: a protobuf-java message with unknown fields converts to a recordbuf record with the same unknown fields
- Fixed32 unknown fields round-trip through the recordbuf codec without throwing

### Out of Scope

- Removing `fromBytes()` or the decode codec from the generator output
- Removing the protobuf-java runtime dependency (`CodedOutputStream`/`CodedInputStream`)
- New codec features (new field types, new serialization options)
- Decode performance optimization
- Decode security hardening
- Adding a `size(Message)` method to generated codecs (the nested-message double-encode in the size pass is a known trade-off for the initial fix; a `size()` method is a follow-up optimization)

### Constraints

- The protobuf-java runtime (`CodedOutputStream`/`CodedInputStream`) remains a dependency — the codec uses it for wire I/O
- The fix is in the generator (code generation), not the runtime — the generated codec changes, the `Encoder`/`Decoder` runtime classes may change
- The decode path must continue to work (no breaking change) but is explicitly unfunded
- Generated code must remain dependency-free (per the original `record`-output requirement) — the interop conversion uses `com.google.protobuf.UnknownFieldSet` which is already on the classpath when the `protobuf_java` option is set

### Benchmark Evidence

Data from the JMH benchmark suite (`:recordbuf-benchmarks`), run 2026-11-26:

**Small message (3 fields):**

| Method | ops/s | B/op |
|---|---|---|
| `encode` (recordbuf) | 4.53M | 4,360 |
| `protoEncode` (native) | 8.75M | 176 |
| `interopEncode` (convert+native) | 6.34M | 216 |
| `decode` (recordbuf) | 15.84M | 560 |
| `protoDecode` (native) | 14.20M | 384 |
| `interopDecode` (native+convert) | 13.37M | 440 |

**Conformance (TestAllTypesProto3):**

| Method | ops/s | B/op |
|---|---|---|
| `encode` (recordbuf) | 1.54M | 5,784 |
| `protoEncode` (native) | 4.23M | 1,544 |
| `interopEncode` (convert+native) | 0.90M | 11,016 |
| `decode` (recordbuf) | 0.66M | 3,512 |
| `protoDecode` (native) | 2.81M | 1,656 |
| `interopDecode` (native+convert) | 1.12M | 6,464 |

Key findings:
- Recordbuf encode is the slowest and most allocation-heavy path on both message sizes
- Recordbuf decode is slower than interop decode on the conformance message (0.66M vs 1.12M)
- The encode allocation gap is a fixable technical issue (4K buffer), not a structural one

## Architecture Decisions

### AD-1: Two-pass encode (size-compute then write)

The generated codec computes the exact wire size in a first pass, then writes into an exactly-allocated `byte[]` in a second pass. This matches protobuf-java's native strategy and eliminates the 4K buffer entirely.

**Why not single-pass with trim?** A single-pass approach would need a pre-allocated buffer (size unknown without computation) and a trim/copy at the end. The two-pass approach allocates exactly the right size in one shot.

**Cost:** Two loops instead of one (size + write). For small messages this is negligible. For large messages the total work is 2x, but the allocation is exact and there's no copy.

**Nested messages:** The size pass calls the nested codec's `encode()` and takes `.length` to compute the size. The write pass calls `encode()` again to get the bytes. This is 2x the encode work for nested messages. A follow-up optimization would add a `size(Message)` method to each codec that computes the size without encoding, but this is out of scope for the initial fix.

**Generated code shape** (for a 3-field message):
```java
public static byte[] encode(BenchmarkSmall v) {
    int size = 0;
    // -- size pass --
    if (v.id() != 0) size += CodedOutputStream.computeInt32Size(1, v.id());
    if (!v.name().isEmpty()) size += CodedOutputStream.computeStringSize(2, v.name());
    for (String t : v.tags()) size += CodedOutputStream.computeStringSize(3, t);
    size += v.unknownFields().getSerializedSize();
    // -- write pass --
    byte[] buf = new byte[size];
    CodedOutputStream out = CodedOutputStream.newInstance(buf);
    if (v.id() != 0) out.writeInt32(1, v.id());
    if (!v.name().isEmpty()) out.writeString(2, v.name());
    for (String t : v.tags()) out.writeString(3, t);
    Wire.writeUnknown(out, v.unknownFields());
    return buf;
}
```

**Packed scalars:** The packed body is a nested length-delimited group. The size pass computes the body size (sum of element sizes), the write pass allocates a `byte[]` for the body, writes the elements, then writes the length-delimited group with the body bytes. This replaces the current `Encoder.start()` for the inner body.

### AD-2: Bypass `Encoder` in generated code

The generated codec uses `CodedOutputStream` directly, not the `Encoder` runtime class. The `Encoder` class is marked `@Deprecated` with a note that generated code no longer uses it.

**Why not change `Encoder`?** Changing `Encoder` to support two-pass allocation would require adding a `start(int size)` method and changing the `finish()` contract. This affects any hand-written code that uses `Encoder`. Bypassing it in generated code is a cleaner separation: the generated code is self-contained, and `Encoder` remains available for hand-written use (deprecated but functional).

### AD-3: Decode unfunded — javadoc note, no `@Deprecated`

The `fromBytes()` method and the decode codec get a javadoc note in the generated code: "This decode path is unfunded. For new code, prefer native protobuf decode + `Interop.from()`." No `@Deprecated` annotation — the method still works, it's just not the recommended path. Adding `@Deprecated` would trigger `-Werror` warnings in consumer code that still uses `fromBytes()`, which is a breaking change.

### AD-4: Unknown-fields interop conversion

A new runtime method `UnknownFields.fromProto(com.google.protobuf.UnknownFieldSet set)` converts the protobuf-java unknown field set to a recordbuf `UnknownFields`. The generated `Interop.from()` calls it instead of `UnknownFields.empty()`.

**Conversion logic:**
1. If the set is empty, return `UnknownFields.empty()`
2. For each `com.google.protobuf.UnknownField` in the set:
   a. Get the field number and wire type
   b. Re-encode the typed value back to raw post-tag bytes:
      - VARINT (wire 0): `varint64Bytes(field.getVarint64())`
      - FIXED64 (wire 1): 8-byte little-endian from `field.getFixed64()`
      - FIXED32 (wire 5): 4-byte little-endian from `field.getFixed32()`
      - LENGTH_DELIMITED (wire 2): `varint32Bytes(field.getLength())` + the field's content bytes
   c. Map the protobuf wire type to recordbuf `WireType`
   d. Create a recordbuf `UnknownField(fieldNumber, wireType, rawBytes)`
3. Build the recordbuf `UnknownFields` via `UnknownFields.of(...)`

**Placement:** The conversion logic lives in the runtime (`UnknownFields.fromProto()`), not in the generated code. The generated interop code just calls `UnknownFields.fromProto(x.getUnknownFields())`.

**Wire type mapping:**
- protobuf `WIRE_TYPE_VARINT` (0) → recordbuf `WireType.I32`
- protobuf `WIRE_TYPE_FIXED64` (1) → recordbuf `WireType.I64`
- protobuf `WIRE_TYPE_LENGTH_DELIMITED` (2) → recordbuf `WireType.LENGTH_DELIMITED`
- protobuf `WIRE_TYPE_FIXED32` (5) → recordbuf `WireType.FIXED32` (added in AD-5)

### AD-5: FIXED32 wire type (pre-existing gap fix)

The recordbuf `WireType` enum currently has `I32` (wire 0), `I64` (wire 1), and `LENGTH_DELIMITED` (wire 2). Wire code 5 (fixed32) is not handled — `UnknownFields.Builder.add()` throws on it. This is a pre-existing gap in the decode path.

**Fix:** Add `FIXED32` to the `WireType` enum with wire code 5. Update:
- `WireType.fromTag(int tag)` to handle wire code 5
- `UnknownFields.Builder.add()` to handle `FIXED32`: `value = in.readRawBytes(4)`
- `UnknownField.asLong()` to handle `FIXED32`: 4-byte little-endian read
- `UnknownFields.writeTo()` already writes `out.writeRawBytes(f.value())` which works for any wire type (the raw bytes are type-agnostic)

This also fixes the pre-existing decode gap: a message with a fixed32 unknown field can now be decoded and re-encoded by the recordbuf codec.

### AD-6: `UnknownFields.getSerializedSize()` for the encode size pass

The encode size pass needs to compute the size of the unknown fields carrier. Add a `getSerializedSize()` method to `UnknownFields` that sums the tag + value sizes for each field.

The `writeTo` method writes `out.writeTag(fieldNumber, wireType.code())` + `out.writeRawBytes(value)`. The tag size is `CodedOutputStream.computeTagSize(fieldNumber, wireType.code())` and the raw bytes size is `value.length`. So:

```java
public int getSerializedSize() {
    int size = 0;
    for (UnknownField f : fields) {
        size += CodedOutputStream.computeTagSize(f.fieldNumber(), f.wireType().code());
        size += f.value().length;
    }
    return size;
}
```

## Implementation Units

### Unit 1: `WireType.FIXED32` + `UnknownFields` fixed32 support

**Goal:** Add `FIXED32` wire type to the recordbuf `WireType` enum and handle it in `UnknownFields` and `UnknownField`. This fixes a pre-existing gap where fixed32 unknown fields would throw in the decode path, and is a prerequisite for the interop conversion (AD-4).

**Files:**
- `runtime/src/main/java/bijnagte/recordbuf/runtime/WireType.java`
- `runtime/src/main/java/bijnagte/recordbuf/runtime/UnknownFields.java`
- `runtime/src/main/java/bijnagte/recordbuf/runtime/UnknownField.java`

**Steps:**
1. Add `FIXED32` to `WireType` enum with wire code 5
2. Update `WireType.fromTag(int tag)` to handle wire code 5
3. Update `UnknownFields.Builder.add()` to handle `FIXED32`: `value = in.readRawBytes(4)`
4. Update `UnknownField.asLong()` to handle `FIXED32`: 4-byte little-endian read
5. Verify: decode a message with a fixed32 unknown field, encode it, confirm byte-identical round-trip

**Verification:**
- `./gradlew :recordbuf-runtime:test` — existing tests still pass
- Manual: decode bytes with a fixed32 unknown field, confirm no throw
- Manual: encode the decoded record, confirm the fixed32 field re-emits identically

### Unit 2: `UnknownFields.fromProto(UnknownFieldSet)` + `getSerializedSize()`

**Goal:** Add a static method to `UnknownFields` that converts a protobuf-java `UnknownFieldSet` to a recordbuf `UnknownFields`. Add `getSerializedSize()` for the encode size pass. This is the interop fidelity fix (AD-4) and the size-pass prerequisite (AD-6).

**Files:**
- `runtime/src/main/java/bijnagte/recordbuf/runtime/UnknownFields.java`

**Steps:**
1. Add `public static UnknownFields fromProto(com.google.protobuf.UnknownFieldSet set)` to `UnknownFields`
2. If the set is empty, return `UnknownFields.empty()`
3. For each `com.google.protobuf.UnknownField` in the set, convert it to a recordbuf `UnknownField` per AD-4
4. Build the recordbuf `UnknownFields` via `UnknownFields.of(...)`
5. Add `getSerializedSize()` method per AD-6
6. The `varint64Bytes` and `varint32Bytes` helpers are currently private in `UnknownFields.Builder` — make them accessible (package-private or extract to `UnknownFields` as private static methods) so `fromProto` can use them

**Dependencies:**
- Unit 1 (FIXED32 wire type must exist)

**Verification:**
- Unit test: create a protobuf-java `UnknownFieldSet` with varint, fixed64, fixed32, and length-delimited fields, convert it, verify the recordbuf `UnknownFields` has the same fields with the same raw bytes
- Unit test: empty set → `UnknownFields.empty()`
- Unit test: `getSerializedSize()` matches the actual encoded size for a carrier with known fields
- Round-trip: convert a protobuf-java `UnknownFieldSet` to recordbuf `UnknownFields`, encode it, decode it with the recordbuf codec, confirm the unknown fields are identical

### Unit 3: Two-pass encode in `CodecSourceMapping`

**Goal:** Change the generated codec's encode method from `Encoder.start()` + `ByteArrayOutputStream` to a two-pass size-compute-then-write into an exactly-allocated `byte[]`. This is the main fix (AD-1).

**Files:**
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/CodecSourceMapping.java`

**Steps:**
1. Modify `encode(Message m)` to emit two-pass code:
   - **Size pass:** For each field, emit `size += CodedOutputStream.compute*Size(...)` expressions. The size computation mirrors the write logic:
     - Singular scalar (non-default): `size += CodedOutputStream.computeInt32Size(number, v.field())` (or the appropriate compute method for the wire scalar)
     - Repeated scalar (non-packed): `for (var x : v.field()) size += CodedOutputStream.computeStringSize(number, x)`
     - Repeated scalar (packed): compute body size (sum of `compute*SizeNoTag(x)`), then `size += CodedOutputStream.computeBytesSize(number, bodySize)`
     - Singular message: `if (v.field() != null) size += CodedOutputStream.computeBytesSize(number, NestedCodec.encode(v.field()).length)`
     - Repeated message: `for (var x : v.field()) size += CodedOutputStream.computeBytesSize(number, NestedCodec.encode(x).length)`
     - Unknown fields: `size += v.unknownFields().getSerializedSize()`
   - **Write pass:** Allocate `byte[] buf = new byte[size]`, create `CodedOutputStream out = CodedOutputStream.newInstance(buf)`, write each field (same logic as current encode, but using `out` directly), return `buf`
2. **Packed scalar encode:** Replace `Encoder.start()` for the inner body with: size pass computes body size, write pass allocates `byte[] body = new byte[bodySize]`, creates `CodedOutputStream bodyOut = CodedOutputStream.newInstance(body)`, writes elements, then `out.writeByteArray(number, body)`
3. **Remove `Encoder` usage:** The generated encode method no longer references `Encoder` or `TypeRefs.ENCODER`
4. **Map entry codec:** `emitEntryCodec` also uses `Encoder.start()` — apply the same two-pass treatment

**Known trade-off:** Nested messages are encoded twice (once for size, once for write). A follow-up would add a `size(Message)` method to each codec to avoid this.

**Verification:**
- `./gradlew :recordbuf-generator:test` — generator tests pass
- `./gradlew :recordbuf-fixtures:build` — fixtures compile and all golden matrix tests pass
- `./gradlew :recordbuf-benchmarks:jmh` — benchmark shows encode allocation dropped to ≤176 B/op (small), ≤1,544 B/op (conformance)
- Golden matrix: all encode goldens are byte-identical (the wire format doesn't change, only the buffer strategy)

### Unit 4: `InteropSourceMapping.emitFrom()` unknown-fields conversion

**Goal:** Change the generated `Interop.from()` to preserve unknown fields by calling `UnknownFields.fromProto(x.getUnknownFields())` instead of `UnknownFields.empty()`.

**Files:**
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/InteropSourceMapping.java`

**Steps:**
1. In `emitFrom(Message m, TypeRef ref, TypeRef rec, boolean jackson)`, find where the constructor arguments are built
2. Replace the `UnknownFields.empty()` argument with `UnknownFields.fromProto(x.getUnknownFields())`
3. The generated code changes from:
   ```java
   return new BenchmarkSmall(_id, _name, _tags, null, UnknownFields.empty());
   ```
   to:
   ```java
   return new BenchmarkSmall(_id, _name, _tags, null, UnknownFields.fromProto(x.getUnknownFields()));
   ```
4. For Jackson records (with `jsonExtras`), the argument order is: `null` (extras) + `UnknownFields.fromProto(x.getUnknownFields())`

**Dependencies:**
- Unit 2 (`UnknownFields.fromProto` must exist)

**Verification:**
- `./gradlew :recordbuf-generator:test` — generator tests pass
- Unit test: create a protobuf-java message with unknown fields, call `Interop.from()`, verify the record's `unknownFields()` matches
- Golden matrix: interop tests still pass (the interop goldens don't include unknown fields, so no golden changes)

### Unit 5: `Encoder` deprecation + `fromBytes()` unfunded javadoc

**Goal:** Mark `Encoder` as `@Deprecated` and add a javadoc note to the generated `fromBytes()` method indicating the decode path is unfunded.

**Files:**
- `runtime/src/main/java/bijnagte/recordbuf/runtime/Encoder.java`
- `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java` (or wherever `fromBytes()` is emitted)

**Steps:**
1. Add `@Deprecated` to `Encoder` class with javadoc: "Generated codecs no longer use this class. Hand-written codecs should use `CodedOutputStream` directly."
2. Find where `fromBytes()` is emitted in the generator (likely `RecordSourceMapping`)
3. Add a javadoc note to the generated `fromBytes()` method: "This decode path is unfunded. For new code, prefer native protobuf decode + `Interop.from()`."
4. No `@Deprecated` annotation on `fromBytes()` (would trigger `-Werror` in consumer code)
5. If the runtime's own tests use `Encoder`, add `@SuppressWarnings("deprecation")` to those test methods (or leave them — the runtime module compiles with `-Werror` but the deprecation warning only fires when a deprecated API is *used*, and the tests are in the same module, so they should be fine)

**Verification:**
- `./gradlew build` — full build passes (the `@Deprecated` on `Encoder` doesn't trigger warnings because no generated code uses it anymore)
- Generated code inspection: `fromBytes()` has the javadoc note

### Unit 6: Benchmark verification

**Goal:** Run the JMH benchmark suite and confirm the encode allocation is fixed.

**Steps:**
1. `./gradlew :recordbuf-generator:installDist`
2. `./gradlew :recordbuf-benchmarks:jmh` (with `-prof gc`)
3. Compare results against the baseline:
   - Small encode: expect ≤176 B/op (matching native), ≥6.3M ops/s
   - Conformance encode: expect ≤1,544 B/op (matching native), ≥0.90M ops/s
4. If the allocation is still above native, investigate:
   - The size pass may be allocating (e.g., `encode().length` for message fields allocates the nested message's byte array)
   - The packed scalar body allocation may be suboptimal
   - The `CodedOutputStream.newInstance(byte[])` itself may allocate (it shouldn't — it wraps the provided array)

**Verification:**
- Benchmark output shows the expected allocation reduction
- The encode throughput meets or exceeds the success criteria

## Implementation Order

1. **Unit 1** (WireType.FIXED32) — prerequisite for Unit 2
2. **Unit 2** (UnknownFields.fromProto + getSerializedSize) — prerequisite for Unit 3 and Unit 4
3. **Unit 3** (Two-pass encode) — main fix
4. **Unit 4** (Interop unknown-fields) — depends on Unit 2
5. **Unit 5** (Deprecation + javadoc) — independent, can be done anytime
6. **Unit 6** (Benchmark verification) — after all code changes

Units 3 and 4 can be done in parallel (they touch different files). Unit 5 is independent.

## Risk Assessment

- **Wire format compatibility:** The two-pass encode produces identical bytes to the single-pass encode (same field order, same wire format). The golden matrix tests verify this.
- **Nested message double-encode:** The size pass calls `encode()` on nested messages to get their size. This is 2x the encode work for nested messages. Acceptable for the initial fix; can be optimized with a `size()` method in a follow-up.
- **FIXED32 wire type:** Adding `FIXED32` to the `WireType` enum is a new public API surface. It's backward-compatible (existing code doesn't use it) and fixes a pre-existing gap.
- **Interop fidelity:** The `UnknownFields.fromProto()` conversion must produce byte-identical re-emit. The golden matrix and unit tests verify this.
- **`-Werror` gate:** The `@Deprecated` on `Encoder` must not trigger warnings in the project's own code. Since no generated code uses `Encoder` anymore, and the runtime's own code doesn't use it (or the tests suppress the warning), this should be clean.

## Stop Conditions

- A golden file content change is required (golden files are frozen)
- The coverage gate minimums would need to be lowered
- The two-pass encode produces different bytes than the current encode for any golden case
- The interop unknown-fields conversion produces a different byte sequence than the protobuf-java `UnknownFieldSet` would produce on re-encode
