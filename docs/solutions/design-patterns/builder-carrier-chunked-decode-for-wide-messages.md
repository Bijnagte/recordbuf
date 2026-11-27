---
title: Decode wide messages through the builder carrier in chunked parts
date: 2026-11-27
category: docs/solutions/design-patterns
module: bijnagte.recordbuf.plugin.writer
problem_type: design_pattern
component: tooling
severity: high
applies_when:
  - "A generated method must be split but its state lives in method locals"
  - "A record has too many components for one tag-switch method to stay under HotSpot's huge-method limit"
  - "Removing a defensive copy changes iteration order that a golden depends on"
tags: [codegen, decode, builder, hotspot, chunking, compact-constructor, goldens]
---

# Decode wide messages through the builder carrier in chunked parts

## Context
Encode was split into `writeBodyPartN` helpers because it only reads the record (see `hotspot-huge-methods-in-generated-codecs.md`). Decode could not be split the same way: `decodeBody` kept every field in a method local, and locals cannot be shared across methods. For the conformance message `decodeBody` was 8,323 bytes, over HotSpot's 8,000-byte limit, so it never compiled. Profiling (`./gradlew :recordbuf-benchmarks:profile`) put about 88% of CPU samples and 95% of allocation pressure there.

## Guidance
Give the decode a mutable carrier that outlives one method: the record's generated `Builder`.

- **Shape (wide messages only):** `decodeBody` is a short loop that calls `decodeField(in, fieldNo, carrier)`, which routes by field-number range to `decodePartN`. Each arm reads the builder field into a local, runs the ordinary arm, and writes it back. Unknown tags still go to the unknown-field carrier. The code is in `generator/src/main/java/bijnagte/recordbuf/plugin/writer/CodecSourceMapping.java` (`decodeWideBody`, `decodeParts`). "Wide" is the same `CHUNK_SLOTS` (16) threshold as encode (`isWide`).
- **Narrow messages are untouched.** They keep the local-variable path (small-message decode runs at about 15.6M ops/s, ahead of native protobuf), so no builder allocation is added there.
- **Collections stay null until used.** Repeated and map builder fields have no initializer and are `@Nullable`; the arm allocates on the first element. The record's compact constructor (emitted by `RecordSourceMapping` whenever a record has a collection) turns null into an immutable `List.of()`/`Map.of()`. So the builder neither allocates nor defensively copies collections, and `toBuilder()` shares the record's.
- **Names:** the part-method parameters are `decodeFieldNo` and `decodeCarrier`. A leading underscore fails the project's `ParameterName` Checkstyle rule, and short names could collide with a proto field of the same name.
- **Lint suppressions:** each arm's initial read is deliberately unused when the arm only assigns, so `decodePartN` carries `@SuppressWarnings("UnusedVariable")`. `build()` passes nullable collection storage on purpose and carries `@SuppressWarnings("NullAway")`.

## Why This Matters
Result on `ConformanceBenchmark.decode`: 643K to 4.65M ops/s and 3,512 to 1,600 B/op, against protobuf-java at 2.66M ops/s and 1,656 B/op (`benchmarks/results/post-decode-builder-carrier.md`). The record's own `<init>` (about 150 arguments) still fails C2 but C1 compiles it, so it is not the bottleneck.

## When to Apply
- Any generated method over roughly 8,000 bytecodes whose state cannot be passed as arguments.
- Not for narrow messages: a builder there is pure overhead.

## Examples
Removing the `HashMap` copy from the builder's copy constructor changed map iteration order. The corpus cases build a record with `LinkedHashMap`s, then call `toBuilder()`; the old copy scrambled that order, and 8 `corpus/all-types-*.pb.hex` goldens had been seeded from the scrambled order. They were re-seeded after review: same length and same content at every nesting level, only map-entry order differs (checked with a throwaway wire-format canonicalizer). Treat a defensive copy as part of the observable order when goldens encode maps, and review any golden that moves.

## Related
- `docs/solutions/performance-issues/hotspot-huge-methods-in-generated-codecs.md`
- `docs/solutions/performance-issues/profiling-jmh-benchmarks-with-jfr-and-async-profiler.md`
- `benchmarks/results/post-decode-builder-carrier.md`
