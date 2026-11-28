---
title: "Java-type format fields: a string field whose record component is a richer Java type"
date: "2026-11-27"
category: docs/solutions/design-patterns
module: "bijnagte.recordbuf.runtime.format + bijnagte.recordbuf.plugin.writer + bijnagte.recordbuf.json"
problem_type: design_pattern
component: tooling
severity: high
applies_when:
  - "Extending the recordbuf.java_type option, its validation, or any surface's handling of a format field"
  - "Adding a new format type (built-in or user-supplied) that generated code must parse and render"
  - "Debugging a generated format field: absent vs empty, registry misses, Jackson contextualization"
related_components:
  - "bijnagte.recordbuf.runtime"
  - "bijnagte.recordbuf.avro"
  - "bijnagte.recordbuf.fixtures"
tags:
  - format-fields
  - java-type
  - registry
  - serviceloader
  - codegen
  - generator
  - jackson
  - avro
  - recordbuf
---

# Java-type format fields: a string field whose record component is a richer Java type

## The pattern

A `string` proto field tagged `option (recordbuf.java_type) = "<FQCN>"` keeps its
**string shape on every surface** — binary wire, proto3 JSON (Interop), Jackson, Avro —
while its **record component becomes the Java type the option names**. The generated
code never hardcodes the conversion: it names the type as a class literal and asks the
runtime registry for the `Format<T>` that renders and parses it.

The durable invariants, in order:

1. **The wire is string.** The option changes only the record's component type and the
   conversion the surfaces apply to/from it. Encoding writes
   `FormatRegistry.format(X.class).format(component)` as a string; decoding parses.
   A new field needs zero codec changes for its value space to grow.
2. **A format field is presentable.** The model makes a `javaType`-bearing scalar
   `Field` report `isNullable()` true — the same clause as `proto3Optional`. Every
   writer already branches on `isNullable()` for the `@Nullable` component, the
   builder null-guards, and the absent-mapping arms, so no writer gains new machinery:
   only the *type* and the *conversion* change per surface. Caveat: the clause also
   fires on oneof-member `Field`s; presence for a oneof member must keep coming from
   the oneof component, and the wrapper's `value` component stays non-null.
3. **Conversion resolves through one registry, per type, at runtime.**
   `bijnagte.recordbuf.runtime.format.FormatRegistry` is a static `ConcurrentHashMap`
   seeded once at class init from `ServiceLoader.load(Format.class)`, keyed by each
   provider's `type()`. Built-ins (`UuidFormat`, `UriFormat`) ship in the runtime jar
   as a `META-INF/services` resource — no hardcoded entries. `format(Class<T>)` fails
   loud naming the type on a miss; `register(Class<T>, Format<T>)` is the programmatic
   override (last-write-wins). Generated code references the registry's FQCN and the
   consumer's class literal — no FQCN string constants in generated code.
4. **`Format<T>` is conversion only.** `type()`, `format(T)`, `parse(String)`
   (unparseable — including `""` — throws; absent is `null`, never an unparseable
   value), and `int wireBytes(T)` with a render-and-measure default. Fixed-size text
   forms (UUID's 36 chars, a ULID's 26) override `wireBytes` with a constant so the
   wire codec's size-before-write pass never renders the value. A format owns no wire
   encoding, no JSON shape, no Avro schema.
5. **Surfaces contain no format logic** — the surface arm is the plain-string arm with
   a registry call inserted:
   - *Wire codec / Interop:* null component → absent (skip); present →
     `format(...)` written as string; decode absent → null, present → `parse`
     (a present `""` on a field with a presence bit fails like the wire; a
     non-optional Interop field has no presence bit, so `""` elides to null — the
     only representable mapping, mirroring plain-string Interop).
   - *Jackson:* one shared, contextualized pair — `JsonFormatField` (json module,
     `tools.jackson.databind.ValueSerializer`/`ValueDeserializer` bases) referenced by a
     constant annotation pair on every format component. Unlike the closed-set temporal
     pairs, format types are open, so the pair learns the field's type from Jackson's
     contextualization contract: `createContextual` captures the `BeanProperty`'s
     declared type; serialize/deserialize call `FormatRegistry.format(type)` on it. A
     context without a `BeanProperty` fails loud (a format field is always a bean
     property on the generated surface).
   - *Avro:* the schema is the plain string-kind mapping (`["null", "string"]`) —
     **no envelope**. The dynamic-WKT envelope exists because a dynamic WKT's payload
     is a Jackson node with no canonical wire shape; a format field's surface value
     *is* a plain string, so the envelope would carry nothing. The converter converts.
     The Avro surface is independent of the jackson toggle.
6. **Validation is load-time and minimal.** Field kind `SCALAR`, wire token `string`,
   singular label, non-empty value; a oneof member passes (singular by definition).
   No syntactic check of the FQCN — the consumer classpath is the authority; a bad
   name fails at the consumer's compile/runtime, loud. A type with no registered
   format is a *runtime* failure when generated code first asks the registry, not a
   codegen failure.

## Fixture family (the executable spec)

`fixtures/src/test/proto/format.proto` (package
`bijnagte.recordbuf.fixture.format`, `.rec` suffix) holds the whole matrix in one
`Sample` message: UUID, URI, ULID (a third-party type via the `f4b6a3:ulid-creator`
test dependency), a plain-string control, an `optional` formatted UUID, an
unregistered `java.time.Duration` (registry-miss branch), and a oneof with two
formatted members. The ULID's `UlidFormat` is declared through
`fixtures/src/test/resources/META-INF/services/…` — the fixture demonstrates the
declarative `ServiceLoader` path, not explicit `register`.

## The dynamic-WKT doc correction that rode along

The pre-envelope docs claimed dynamic WKTs (Struct/Value/ListValue/Any) "fail loud —
they cannot be bridged" under Avro. Current behavior: on a jackson-enabled file they
ride the shared `RecordbufDynamicWkt` envelope; the `WktAvroBridge` dynamic-WKT set
and the registry's fail-loud throw for one are **defensive paths** (jackson-disabled
files are rejected by the loader first; jackson-enabled files are enveloped before
the registry walks them). Javadoc for `AvroRegistry`/`WktAvroBridge` and the
registry error string were rewritten to state that; treat any doc still claiming
"dynamic WKTs are not generatable" as stale.
