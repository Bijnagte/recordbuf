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
`uint64` to a bit-exact `long`), and the record's unknown-field carrier transits
1:1 as an always-present array. A file containing any of these shapes no longer
loses its whole Avro output. *Avoid:* opaque envelope wrapper (the twin has no
`{format, content-type, payload}` carrier; that idea is shelved until the record
side can hold dynamic WKTs).

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

## Interop adapters

### Avro converter
The per-message converter class that bridges a recordbuf record to and from its
generated Apache Avro specific record, mapping each record field field-by-field.
A converter is generated for each message record that has the Avro convert option
set. It pairs the WKT-Avro bridge surface (converting protobuf WKTs to/from Avro
types) with field-level record↔Avro mapping, enabling Avro-native consumers to use
message data produced by the recordbuf plugin without a protobuf dependency.
