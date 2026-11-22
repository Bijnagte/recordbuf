---
name: recordbuf
last_updated: 2026-11-22
---

# recordbuf Strategy

## Target problem

Java teams that carry one domain across several transports (protobuf, Avro, JSON) have to hand-write and maintain adapters between each format's generated classes and their own domain model, because the native generated classes are poor domain models. Those adapters drift silently when a schema changes, and having an LLM generate them doesn't fix this: the output is non-deterministic and inefficient.

## Our approach

The `.proto` is the single source of truth, and the generated idiomatic Java record **is** the domain model. Every transport binding (protobuf wire, Avro, JSON) is generated deterministically from that schema, so there's no hand-written adapter left to drift. Unknown fields carry through so services can evolve without upgrading in lockstep. Proto is the base because options can customize the output, it covers most of the abstractions we need, and its compiler-plugin model lets us generate in Java instead of a templating language.

## Who it's for

**Primary:** Backend Java developers integrating with a variety of systems and transport types, with data models that share a common set of abstractions. They're hiring recordbuf to keep one schema-defined model they can put on any of those wires without writing mappers.

## Key metrics

- **Round-trip fidelity** - the share of fixture schemas that round-trip byte- or value-identical across proto ↔ record ↔ Avro/JSON, including unknown fields. Measured by the `:recordbuf-fixtures` tests.
- **Wire compatibility vs. reference** - zero divergences from protobuf-java / Avro / protobuf-JSON output. Measured by the `:recordbuf-fixtures` interop tests.
- **Codec performance vs. protobuf-java** - encode/decode throughput and allocation. Measured by JMH; not measured yet.
- **Generated-code cleanliness** - generated output passes NullAway, Checkstyle, GJF, and `-Werror` with no suppressions. Measured by `./gradlew build` on `:recordbuf-fixtures`.
- **Proto feature coverage** - the share of proto3/editions constructs supported without a fallback. Tracked qualitatively against the protobuf language spec.

## Tracks

### Transport bindings

Generating every wire from the one schema: protobuf codec, protobuf-java interop, the Avro bridge, and Jackson/JSON.

_Why it serves the approach:_ each binding the generator owns is one adapter users no longer hand-write.

### Schema-evolution safety

Keeping every binding wire-compatible and lossless as schemas change: unknown-field passthrough, round-trip and reference-interop fixtures.

_Why it serves the approach:_ services can only evolve without upgrading in lockstep if no binding silently drops or corrupts data.

### Domain-model ergonomics

Making the generated records good enough to use directly as the domain model: idiomatic types, sealed oneofs, builders, nullability, well-known types as `java.time`, and user interfaces via options.

_Why it serves the approach:_ if the record is awkward to use, users write a mapper to their own model, and the adapter problem comes back.

<!-- Generator internals (codegen AST, type refs, quality gates on generated output) are folded into these tracks rather than tracked separately. -->

## Not working on

- Target languages other than Java.
- Direct integration with the systems that use these transports, or client/server generation. recordbuf stays limited to models.
