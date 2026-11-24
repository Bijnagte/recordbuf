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
`uint64` to a bit-exact `long`), and the record's unknown-field carrier has an
always-present array slot (the converter does not transit its entries yet — it
sets the array empty). A singular message reference that closes a cycle in the
reachable graph is a `null`-first union, so an unset cycle-closing field can be
written; other singular message fields stay non-nullable. A file containing any of
these shapes no longer loses its whole Avro output. *Avoid:* opaque envelope wrapper for *structural* shapes — the twin maps everything it represents structurally; the envelope is not how the twin expresses its own shapes (see Opaque envelope for the one field-level return).

### Decode scope
The bound on one binary decode of a generated codec: a `ScopedValue` holds the current nesting depth and a byte budget that every level of the top-level decode shares. A nested decode checks the depth and charges its input's bytes before it runs, so a hostile payload is rejected with an `InvalidProtocolBufferException` instead of overflowing the stack or allocating without bound. Callers change the bounds by binding `DecodeLimits.CURRENT`. *Avoid:* recursion limit (protobuf-java's `CodedInputStream` limit, which a stream opened per nested level never reaches).

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

## Compatibility testing

### Golden
A committed copy of one case's record in one format, stored at `fixtures/src/test/resources/golden/<family>/<case>.<ext>`. The five formats are protobuf binary (`pb.hex`), proto3 JSON through the generated `Interop` (`proto3.json`), Jackson JSON (`jackson.json`), Avro binary (`avro.hex`) and canonical Avro JSON (`avro.json`). A golden is seeded once from the library that produced it, then frozen: the seeder writes only missing goldens and the suite never rewrites one, so it changes only by a reviewed hand edit. Jackson goldens have no independent source and need review when seeded. JSON goldens compare after both sides are normalized to pretty-printed tree text, so member order counts and whitespace does not. Binary goldens compare byte-for-byte. *Avoid:* "snapshot" (implies the test may regenerate it).

### Expected-after-trip record
The record that a golden must deserialize to when its format loses information by design. Examples as built: a `json_ignored` field, or a retained unknown JSON property, under Jackson; the unknown-field carrier under proto3 JSON; a timestamp cut to Avro's microseconds. A case declares one per lossy format, which keeps the reverse equality assertion strict. Where a case declares none, the reverse check compares with the built record.

### Gap ledger
The tracked list of constructs and conformance scenarios that recordbuf does not satisfy yet, with the reason for each, in `fixtures/src/test/resources/golden/gap-ledger.md`. A construct row has a `GAP(<id>)` marker in a vendored proto: the construct is commented out there when it breaks generation, or left active with the marker above it when it generates but is wrong in one format (a marker-only gap). A conformance-only row has no marker and is named by a gap row of the [conformance manifest](fixtures/src/test/resources/golden/conformance-manifest.md). Gaps close one at a time: uncomment or fix, seed the goldens, and remove the row and its marker in the same change. A commented-out field still round-trips on the wire through the unknown-field carrier, so the ledger is what keeps such a gap visible.

### Conformance mirror
The official protobuf conformance suite's proto3 scenarios at tag `v36.2`, reproduced as Java tests rather than run by Google's C++ runner. Each scenario group is listed in the conformance manifest as covered, a gap, or not applicable. A binary scenario decodes with recordbuf and compares with protobuf-java. A JSON scenario runs `JsonFormat` into the protobuf-java message, then through the generated `Interop` converter and back. A group that tests only `JsonFormat`'s own parse rejection is not applicable, because that behaviour belongs to Google.
