# Concepts

Shared domain vocabulary for this project — entities, named processes, and status
concepts with project-specific meaning. Seeded with core domain vocabulary, then
accretes as ce-compound and ce-compound-refresh process learnings; direct edits
are fine. Glossary only, not a spec or catch-all.

## The generated codec

### Codec
The serialize/deserialize logic a generated message record pairs with itself: it
reads and writes the record's fields in the wire format, and it is what the
interop harness locks to the reference runtime by byte- and semantic-equality.
A codec has no opinion on *what data is not modelled* — that is the carrier's job.

### Wide message
A message whose field and oneof slots exceed the chunking threshold (16). Its generated encode, size and decode bodies are split into bounded parts so HotSpot can compile them; decode accumulates into the record's builder instead of locals. Narrow messages keep the single-method forms.

### Unknown-field carrier
The per-record holder for fields a decode saw on the wire that the record's
schema does not model: the field number, its on-wire shape, and the raw value
bytes, captured so they survive an encode without silent loss. The carrier is
always present on a record and free in the common case (no un-modeled fields),
and on encode it is re-emitted so a record read through an older schema survives
a round-trip byte-for-byte. *Avoid:* unknown field set (the carrier is the
recordbuf-side equivalent of a runtime's unknown-field set).

### Wire-honest capture
The rule that an un-modeled field is stored as its exact on-wire bytes read by
its wire shape — never by a guessed proto type — and inspected the same way, so
a wrong-shape read fails loudly rather than silently coercing. It is what keeps
the carrier a faithful copy of the wire instead of a typed guess about it.

### Structural twin
The Avro bridge's contract that its output is a structural twin of the generated
record: every message and enum reachable from the bridged root gets a schema /
specific-record / converter triple, each field shape maps to an exact Avro
representation (maps to Avro maps or key/value pair records, oneofs to unions,
nested messages to nested records, repeated to arrays, enums to strings,
`uint64` to a bit-exact `long`), and the record's unknown-field carrier is an
array of entry records the converter fills in both directions, so unknown fields
survive an Avro round trip. Every singular message field and every proto3
`optional` scalar or enum is a `null`-first union, so an unset one is the null
branch and reads back unset. A file containing any of
these shapes no longer loses its whole Avro output. *Avoid:* opaque envelope wrapper for *structural* shapes — the twin maps everything it represents structurally; the envelope is not how the twin expresses its own shapes (see Opaque envelope for the one field-level return).

### Decode scope
The bound on one binary decode of a generated codec: a `ScopedValue` holds the current nesting depth and a byte budget that every level of the top-level decode shares. A nested decode checks the depth and charges its input's bytes before it runs, so a hostile payload is rejected with an `InvalidProtocolBufferException` instead of overflowing the stack or allocating without bound. Callers change the bounds by binding `DecodeLimits.CURRENT`. *Avoid:* recursion limit (protobuf-java's `CodedInputStream` limit, which a stream opened per nested level never reaches).
### Identity subtype
The oneof case whose member type is itself the sealed interface's subtype rather than a `<Member>Value` wrapper record: the case type declares `implements <Iface>`, the interface's `permits` clause names it, and no wrapper is generated for the case. Eligible only when the member is a generated message or enum, the oneof's file and the member's file are both in this protoc invocation's `file_to_generate`, the member's Java package equals the sealed interface's package, and the member is not the message declaring the oneof (a class cannot implement its own member type). One type may be an identity subtype of several oneofs at once. Every writer evaluates the same per-(oneof, case) predicate, so the case type's `implements` clause and the interface's `permits` clause always agree, even when generation is split across invocations. The sealed interface stays a bare marker: identity members are read as-is, wrapper members through `instanceof Unwrapable<?>` — a wrapper implements `Unwrapable<T>` for the member's concrete type. *Avoid:* "the oneof's value type" (the wrapper *holds* the member's value; an identity subtype *is* the value).

## Well-known types

### Native WKT mapping
The generator's rule that a `google.protobuf.*` well-known type is generated as a
*native* Java type rather than an opaque nested record — `Timestamp` → `Instant`,
`Duration` → `Duration`, the scalar wrappers → boxed primitives, `BytesValue` →
`runtime.Bytes`, `FieldMask`/`Empty` → their `runtime` holders. A `Wkt` helper
surface bridges each native type to and from the WKT wire shape so the record
stays wire-compatible with a protoc reference. *Fail-loud* miss path: a WKT with
no canonical native encoding (e.g. `Struct`, `Any`, `Value`) is rejected at
generation rather than silently emitted as an opaque record.

### Dynamic WKT
The `google.protobuf` WKT family that carries arbitrary, unstructured values — `Struct`, `Value`, `ListValue`, `Any`, `NullValue` (the `DYNAMIC_UNSUPPORTED` kinds) — as opposed to the natively-mapped family. On a file with the Jackson surface (on by default) the record holds the value as a Jackson node type (`Struct` → `ObjectNode`, `Value` → `JsonNode`, `ListValue` → `ArrayNode`, `Any` → `JsonNode` carrying `{typeUrl, value}`) and the wire Codec bridges to and from the standard embedded WKT wire shape; a file that opts out (`recordbuf.jackson = false`) fails generation loudly. `NullValue` is not supported either way. *Avoid:* "JSON WKT" (the family is JSON-native in *shape* but the term is "dynamic" because its values are unstructured).

## Interop adapters

### Avro converter
The per-message converter class that bridges a recordbuf record to and from its
generated Apache Avro specific record, mapping each record field field-by-field.
A converter is generated for each message record that has the Avro convert option
set. It pairs the WKT-Avro bridge surface (converting protobuf WKTs to/from Avro
types) with field-level record↔Avro mapping, enabling Avro-native consumers to use
message data produced by the recordbuf plugin without a protobuf dependency.

### Opaque envelope
The Avro-twin representation for a field the structural twin cannot map — an opaque wrapper instead of a structural type. Shelved when the structural twin landed (no shape then needed it), it returned with the 2026-11-20 Jackson JSON plan (U9) as the shared `RecordbufDynamicWkt` record `{format, contentType, payload}`: a dynamic WKT field on a file with both the Jackson surface and the Avro bridge on rides it (`format` = `"json"`, `contentType` = the WKT name or an `Any`'s `typeUrl`, `payload` = the node's JSON text), so the twin degrades those fields rather than dropping them or failing the file.

## Jackson JSON surface

### Oneof discriminator
The flat JSON property that records which member of a flattened oneof is set. A oneof's members render as top-level properties under their own JSON names; this single added property names which of them is present. It defaults to the oneof's own name and can be overridden per oneof, and an unset oneof contributes neither the discriminator nor a member.

## Compatibility testing

### Golden
A committed copy of one case's record in one format, stored at `fixtures/src/test/resources/golden/<family>/<case>.<ext>`. The five formats are protobuf binary (`pb.hex`), proto3 JSON through the generated `Interop` (`proto3.json`), Jackson JSON (`jackson.json`), Avro binary (`avro.hex`) and canonical Avro JSON (`avro.json`). A golden is seeded once from the library that produced it, then frozen: the seeder writes only missing goldens and the suite never rewrites one, so it changes only by a reviewed hand edit. Jackson goldens have no independent source and need review when seeded. JSON goldens compare after both sides are normalized to pretty-printed tree text, so member order counts and whitespace does not. Binary goldens compare byte-for-byte. *Avoid:* "snapshot" (implies the test may regenerate it).

### Expected-after-trip record
The record that a golden must deserialize to when its format loses information by design. Examples as built: a `json_ignored` field, or a retained unknown JSON property, under Jackson; the unknown-field carrier under proto3 JSON; a timestamp cut to Avro's microseconds. A case declares one per lossy format, which keeps the reverse equality assertion strict. Where a case declares none, the reverse check compares with the built record.

### Gap ledger
The tracked list of constructs and conformance scenarios that recordbuf does not satisfy yet, with the reason for each, in `fixtures/src/test/resources/golden/gap-ledger.md`. A construct row has a `GAP(<id>)` marker in a vendored proto: the construct is commented out there when it breaks generation, or left active with the marker above it when it generates but is wrong in one format (a marker-only gap). A conformance-only row has no marker and is named by a gap row of the [conformance manifest](fixtures/src/test/resources/golden/conformance-manifest.md). Gaps close one at a time: uncomment or fix, seed the goldens, and remove the row and its marker in the same change. A commented-out field still round-trips on the wire through the unknown-field carrier, so the ledger is what keeps such a gap visible.

### Conformance mirror
The official protobuf conformance suite's proto3 scenarios at tag `v36.2`, reproduced as Java tests rather than run by Google's C++ runner. Each scenario group is listed in the conformance manifest as covered, a gap, or not applicable. A binary scenario decodes with recordbuf and compares with protobuf-java. A JSON scenario runs `JsonFormat` into the protobuf-java message, then through the generated `Interop` converter and back. A group that tests only `JsonFormat`'s own parse rejection is not applicable, because that behaviour belongs to Google.
