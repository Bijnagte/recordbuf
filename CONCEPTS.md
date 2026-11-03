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
