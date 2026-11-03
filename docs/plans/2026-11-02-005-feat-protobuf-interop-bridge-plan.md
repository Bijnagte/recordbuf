---
title: Protobuf-Java Interop Bridge - Plan
type: feat
date: 2026-11-02
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
deepened: 2026-11-02

# Status: IN PROGRESS
---

# Protobuf-Java Interop Bridge - Plan

## Goal Capsule

**Objective:** Give `recordbuf` a performant, low-boilerplate bridge between default
protoc-generated protobuf-java `Message`/`MessageLite` types and recordbuf-generated
records, so that gRPC and other protobuf-java edges are trivial to cross — with
domain logic carried in records (immutable, allocation-light, JIT-friendly) at both
the consume and produce ends.

**Product authority:** This document. Generated records that interop through this
bridge must stay on the same dependency-free footing as today (each record already
pulls in `com.google.protobuf` + `bijnagte.recordbuf.runtime` for its `Codec`); no
new runtime dependency may leak into non-interop records, and `io.grpc` must never
land on the core runtime. The interop surface is additive and does not change the
existing codec/encode/decode contract. See `AGENTS.md` for the generator-free /
only-protobuf-runtime output purity this feature must preserve by default.

**Open blockers:** None. All brainstorm-time design sub-decisions are resolved in
the Planning Contract below. Remaining deferred items (gRPC adapter module,
cross-file nesting, record-valued maps, unknown-field preservation) are
carry-as-follow-on and do not block v1. Product Contract preserved unchanged.

---

## Product Contract

### Summary

Add two layers that let recordbuf records interoperate with protobuf-java
`Message`/`MessageLite` types:

- **Layer A — the gRPC-fast path (always-on, zero-conversion).** Every record gets a
  tiny hook so a record *is* the gRPC message type. Because recordbuf bytes are
  byte-identical to protobuf-java bytes, crossing a controlled/own gRPC boundary
  costs nothing: no structural conversion, no protobuf-java object allocation.
- **Layer B — the fixed-peer structural converter (opt-in, performance path).** For
  a peer that owns its own protobuf-java `X` types you must materialize an `X`/`R`
  at the boundary; an opt-in generated structural converter does field-by-field
  `X↔R`, beating the `toByteArray()`+`parseFrom()` byte bridge on memory and CPU.

### Problem Frame

Records and protobuf-java `Message` types today communicate only through hand-written
`Codec.encode/decode` byte round-trips, and there is no gRPC surface at all. A team
that wants to carry domain logic in records while crossing a protobuf-java boundary
must, per message, either hand-roll a byte bridge (an extra `byte[]` allocation + a
full serialize-or-parse) or hand-write field-by-field converters — boilerplate that
scales with message count and is error-prone. The goal is to make both the common
case (a boundary we control) essentially free, and the harder case (a fixed
third-party peer) a cheap, generated, performance-competitive conversion, with the
heavy mechanical lifting living in `recordbuf-runtime` / a generated artifact
rather than inline on each record.

### Why it matters

The user's stated priority, in order, is **execution-time CPU**, **object
allocation**, **memory**, then **boilerplate**. This reframes "how do we convert"
from "always copy structurally" to "copy only when forced, and make the controllable
boundary zero-cost." The design's central insight — that recordbuf and protobuf-java
bytes are already byte-identical, so a controlled boundary can be crossed with *no
conversion* — collapses most of the perceived problem; the structural converter is
only the escape hatch for genuinely fixed peers.

### Requirements

**Layer A — gRPC-agnostic marshaller (the always-on fast path)**

- **R1. Always-on record hook.** Every generated message record (top-level + nested)
  implements a small `recordbuf-runtime` interface (working name
  `RecordBufMessage`) that lets a generic marshaller reach the record's nested
  `Codec` without reflection. The hook is additive, ~3 lines per record, and adds
  **no new dependency** — the record already depends on `bijnagte.recordbuf.runtime`
  for its `Codec`.
- **R2. gRPC-agnostic core marshaller.** `recordbuf-runtime` ships a minimal,
  gRPC-neutral marshalling surface that keys off the R1 hook: write a record out as
  its codec bytes, and read bytes back into a record via its codec. It holds no
  `io.grpc` dependency. The exact shape (an instance `toBytes()`/static `fromBytes`
  pair, a `Codec` accessor, or a 2-method interface) is a planning decision; the
  constraint is: no reflection, JIT-friendly, no new runtime dependency.
- **R3. Optional gRPC adapter.** A thin, clearly-separated adapter module
  (working name `:recordbuf-grpc`) is the *only* place `io.grpc` is depended on.
  It adapts the R2 agnostic marshaller to `io.grpc.MethodDescriptor.Marshaller<R>`
  (and any convenience for building server/client method descriptors). The core
  `:recordbuf-runtime` and the generated output must remain `io.grpc`-free.
- **R4. Zero-conversion at a controlled boundary.** Registering a `Marshaller<R>` for
  a record means crossing that gRPC boundary performs no structural field copy and no
  protobuf-java object allocation — the write is the record's codec encode and the
  read is the record's codec decode on the wire's own bytes.

**Layer B — opt-in structural converter (the fixed-peer performance path)**

- **R5. Opt-in via a protobuf option.** A new protobuf option (in the spirit of the
  existing `java_interface`) opts a message into also generating a structural
  converter. Output for a message that does **not** opt in is byte-for-byte
  unchanged. The option is a per-message opt-in only — never per-field, never on
  nested types the user did not opt in.
- **R6. Separate generated converter type.** The structural converter is emitted as a
  **separate generated artifact** (a sibling type, not members on the record), so the
  record itself stays clean and the coupling to protobuf-java `X` does not appear on
  the record. Working shape: a converter exposing `R from(X)` and `X toX(R)` (or a
  `Builder`/`newBuilder`-driven variant) — exact shape is a planning decision.
- **R7. Structural, not byte-driven.** The converter copies field-by-field from the
  protobuf-java type to the record and back (nested messages recursed, repeated by
  iteration, maps by entry iteration, enums mapped by number) and must **not**
  implement its body as `X.toByteArray()` + `R.Codec.decode(...)` or
  `R.Codec.encode(...)` + `X.parseFrom(...)`. The byte bridge is strictly slower and
  more-allocating (an extra `byte[]` plus a full serialize/parse) and is what this
  layer exists to avoid.
- **R8. `X`-location is configurable.** The converter must reference the
  protobuf-java `X` by its fully-qualified name, whose package recordbuf does not
  otherwise know (fixtures place the reference in a `.ref`/suffix package via
  `--java_out`). How recordbuf is told that FQN (a new option vs reusing the
  `java_package`/`package_suffix` knobs and their sibling) is a planning decision;
  the requirement is that the mapping is explicit and per-file.
- **R9. Enum mapping is by number.** A record's generated enum is mapped to/from the
  protobuf-java enum by wire number; the protobuf-java `UNRECOGNIZED` case maps to
  the record's designated "unknown" handling (the record's enum `forNumber`, which
  currently has no unknown — a behavior gap to surface in planning).

**Cross-cutting**

- **R10. No unknown-field preservation in scope.** Both current paths drop fields
  present in the source but absent from the `.proto` (recordbuf's decode `default`
  arm calls `Wire.skip`; protobuf-java keeps an unknown-field bag). Preserving
  unknown fields across the bridge is a documented follow-on, not a v1 requirement.
- **R11. Interop tests extend the existing harness.** Both layers are locked in the
  `:recordbuf-fixtures` interop suite. **Byte-equality is the orange**: e.g. the
  protobuf-java `X` produced by `X toX(r)` must `.toByteArray()`-equal
  `R.Codec.encode(r)`; a record read from a protobuf-java `X` must equal the original
  record; a record round-tripped through a `Marshaller<R>` must equal the original.
- **R12. proto3 only.** Consistent with the existing interop harness (which is
  proto3-only); proto2 presence is out of scope.
- **R13. JSpecify / NullAway clean.** Any new `recordbuf-runtime` code participates
  in the module's `@NullMarked` scope; generated interop output stays in sync with
  the generated-code nullability style used today.

### Scope Boundaries

#### In scope
- The always-on record hook (`RecordBufMessage` or equivalent) and the gRPC-agnostic
  core marshaller in `recordbuf-runtime`.
- An optional gRPC adapter module that is the sole `io.grpc` dependency.
- An opt-in protobuf option and the generated *separate-type* structural converter
  (`X↔R` field-by-field) it drives.
- Wire the two layers into the existing `:recordbuf-fixtures` interop harness.

#### Deferred to Follow-Up Work
- **Unknown-field preservation** across the bridge (both paths currently drop them).
- **Proto2** presence handling.
- **Stream-direct marshalling** as a first-class mode (encode into gRPC's
  `OutputStream` / decode from its `InputStream`, eliminating the intermediate
  `byte[]`). Raised as a planning-time challenger; may graduate to core if
  allocation profiling on the controllable boundary shows the intermediate buffer
  matters. Not required for v1.
- gRPC *streaming* (server/client streaming, bidirectional) beyond unary marshaller
  behavior — the v1 target is the unary marshalling surface.
- Additional ergonomics on the gRPC adapter (retry, deadline helpers, interceptor
  hooks) — adapter is a thin 1:1 mapping for v1.

### Explicit Non-Goals

- A new dependency on `io.grpc` anywhere except the optional adapter module.
- Any structural-coupling of the record itself to protobuf-java `X` types.
- Changing the existing `Codec` / `Encoder` / `Decoder` / `Wire` / `CodecDefaults`
  contract: interop is additive and layered on top.
- A `recordbuf`-generated `X` type (recorduf does not generate protobuf-java code;
  it consumes the `--java_out` output already produced for the fixture).

---

## Key Decisions (from the brainstorm dialogue)

- **Boundary control** is the primary fork. A *controlled/own* boundary crosses with
  zero conversion because recordbuf bytes == protobuf-java bytes; a *fixed third-party*
  peer must be served by a generated structural converter. Both are wanted, so both
  layers ship.
- **Layer A hook is always-on.** It is cheap (no new dependency; the record already
  pulls in `recordbuf-runtime`) and it is what makes gRPC "just work" for any record.
  The structural converter (Layer B) is opt-in because — and only because — it couples
  to protobuf-java `X` and needs per-file `X`-location config.
- **Marshalling surface stays gRPC-agnostic at the core.** `io.grpc` lands only in
  the optional adapter module so the runtime and generated output remain clean.
- **Reaching the `Codec` is a tiny generated hook, not reflection.** JIT-friendliness
  and CPU are the top three user priorities; reflection at the crossing point would
  undercut them. The hook (a ~3-line addition to each record) is the trade:
  marginal generated surface, in exchange for zero reflection and a hot, JIT-cached
  crossing.
- **Structural converter is a separate generated type.** Keeps the record clean and
  confines the protobuf-java `X` coupling to the converter's own source, mirroring the
  "opt-in, no-op when not opted in" discipline of the existing `java_interface` option.
- **Unknown-field preservation is deferred**, per the user's explicit signal that
  it is not needed now.

---

## Assumptions

- Generated-code consumers already carry `com.google.protobuf:protobuf-java`
  (already required by every record's `Codec`) and the recordbuf runtime on the
  classpath.
- The protobuf-java `X`/`X.Builder`/`XOrBuilder` types exist on the target
  classpath for Layer B — the user must also run `protoc --java_out` for the same
  `.proto` (already the case in `:recordbuf-fixtures`).
- gRPC's `MethodDescriptor.Marshaller<T>` is the target surface for the optional
  adapter; exact version and any adapter ergonomics are planning details.
- The intermediate `byte[]` allocation in the core marshaller is acceptable for v1 —
  a stream-direct extension exists on the follow-on list if profiling shows it
  matters.
- `UNRECOGNIZED` enum mapping may need to be surfaced for v1; the exact policy is a
  planning decision.
- No proto2 support. The interop harness is proto3-only today.

---

## Open Sub-Decisions for ce-planning

These are deliberately not resolved in brainstorm; they are the shape-level
questions for the next artifact.

- **R2 marshaller shape.** An instance `toBytes()` + static `fromBytes(byte[])`
  pair on each record vs a `Codec` accessor exposed via the `RecordBufMessage`
  interface vs a 2-method interface the record implements. All three avoid
  reflection; the choice affects codegen and the marshaller's generic dispatch.
- **R8 `X`-location config.** A new option (working name
  `recordbuf.protobuf_java_package` or a file-level option like
  `recordbuf.protobuf_java_fqn_prefix`) vs reusing the `java_package`/`package_suffix`
  knobs the fixtures already use, with a suffix-based convention (e.g. the
  protobuf-java `X` lives in `java_package` with no suffix, in the same package as
  the record minus any `.rec`-like suffix).
- **R6 converter shape.** `R from(X)` / `X toX(R)` static pair on a generated
  `SamplePbInterop` type vs a builder-driven variant (`R.Builder fromBuilder(X)`
  composing the `toBuilder()` machinery) vs both.
- **R9 `UNRECOGNIZED` policy.** For the record's enum (which has no unknown member
  today), map protobuf-java `UNRECOGNIZED` to a sentinel, to throw, or to the
  record's enum default. This may require a small generator-side addition to
  give each enum an `UNRECOGNIZED`-shaped fallback in the generated output, or a
  runtime-side guardrail in the converter.
- **R3 adapter scope.** Only `MethodDescriptor.Marshaller<R>`, or also a
  `ServerMethodDefinition` / client-call convenience. v1 target is the marshaller;
  convenience is a follow-on.
- **R11 test matrix.** One interop family per layer, or one per field-type family
  layered on the existing per-family harness (`i32`, `fixed`, `string`, `bytes`,
  `message`, `packed`, `optional`, `map`) plus the end-to-end `compat.proto`.
---

# Planning Contract

**Status:** implementation-ready. Resolves the "Open Sub-Decisions for
ce-planning" above; Product Contract unchanged.

## Assumptions

- **A1 — Output shape stays additive.** Adding a marker interface + two small
  methods to every generated record is a byte-identical addition that does not
  change wire output or the record's fields; existing fixture value/byte tests
  keep passing.
- **A2 — Generated output stays runtime-only.** Generated records keep importing
  only `bijnagte.recordbuf.runtime.*`; no new third-party dependency (io.grpc,
  protobuf) reaches the *record* classpath — the io.grpc binding lives in a
  separate optional adapter module.
- **A3 — Converter targets the same-file nesting v1 needs.** v1 handles the
  fixture shape (siblings like `Sample`+`Item`, `Compat`+`Sub` that are
  generated as one record family per top-level message). Cross-file message
  references are a carry-as-follow-on.

## Technical Decisions (resolved)

- **TD1 — Layer A marker + hook (resolves R2).** Runtime type
  `bijnagte.recordbuf.runtime.ProtobufMessage` with one method:
  `byte[] toByteArray() throws java.io.IOException`. Every generated record
  (`Message`, not enums) `implements ProtobufMessage` and adds:
  - `@Override public byte[] toByteArray() throws java.io.IOException { return Codec.encode(this); }`
  - a **static** `public static R fromBytes(byte[] in) throws java.io.IOException { return Codec.decode(in); }`
    (a static cannot be polymorphic through an interface, so the decode half is
    reached by a `Function<byte[], R>` method reference the marshaller holds —
    no reflection).
  Emit location: after the builder, before the codec (codec stays the last
  member so the type can reference itself). The `implements` clause gains
  `bijnagte.recordbuf.runtime.ProtobufMessage`; the writer's `use(...)` collector
  resolves/imports it like any type.
- **TD2 — gRPC-agnostic core marshaller (resolves R8 partial, R3).** Runtime
  `bijnagte.recordbuf.runtime.ProtobufMarshaller<T>` — a small class that holds
  a `Function<byte[], T>` decoder:
  - `write(T value, OutputStream out) throws IOException { out.write(value.toByteArray()); }`
  - `read(InputStream in) throws IOException { return decoder.apply(in.readAllBytes()); }`
  - static `<T extends ProtobufMessage> ProtobufMarshaller<T> of(Function<byte[],T> decode)`.
  Its two-method shape `(read InputStream, write T→OutputStream, both throw
  IOException)` is signature-identical to `io.grpc.MethodDescriptor.Marshaller`,
  so the optional adapter is a thin ~10-line bridge (TD5). A v1 `byte[]`
  intermediate is acceptable per the Product Contract; **stream-direct** encode/
  decode is the named follow-on.
- **TD3 — Structural converter opt-in via a new option (resolves R8, R6).**
  A new `recordbuf.protobuf_java` **MessageOptions** extension (singular string,
  extension **tag 50002**), read by `ModelLoader` exactly like
  `recordbuf.java_interface`: a compiled unknown field at `tag 50002` (with the
  uninterpreted-option fallback by name `recordbuf.protobuf_java`). Its
  `value` is the protobuf-java **java_package** that hosts the `X` types; when
  blank, derive it from the record's java_package by stripping the run-wide
  `package_suffix` (the fixture convention: record = `…message.rec`, `X` = `…message`).
  Presence ⇒ **opt-in** and generate, for that message (and its same-file nested
  messages reachable by message fields — closure, recursion through their own
  converters), a **distinct nested type** `X.Interop` (a separate generated
  *type*, satisfying R6's "separate type that keeps the record/codec clean" —
  nested keeps one source file; a per-type top-level file is a carry-variant),
  with two static methods:
  - `public static R from(<Xpkg>.X x)` — X→record, field by field.
  - `public static <Xpkg>.X to(R r)` — record→X via `X.newBuilder()…build()`.
  The `Interceptor`/converter is the *only* generated code that references `X`.
- **TD4 — per-field conversion rules (resolves R9).**
  - **scalar:** `x.getId()` ↔ `b.setId(r.id())` (direct getters/setters;
    repeated → `x.getItemsList()`/`addAllItems(List)`).
  - **enum (by number, resolves R9):** X→R `RecEnum.forNumber(x.getKind().getNumber())`;
    R→X `XBuilder.setKind(X.Enum.forNumber(r.kind().getNumber()))`. `forNumber`
    on a missing/unknown tag yields the protobuf-java `UNRECOGNIZED` member, so
    **no record-side `UNRECOGNIZED` sentinel is required for v1** — unknown
    protobuf numbers round-trip as `UNRECOGNIZED` on the `X` side and are
    absorbed by the record's `forNumber(int → known-or-? )` default; record
    enums have no unknown member, so a value with no matching record constant is
    a genuine mismatch the test surfaces. Document this asymmetry.
  - **singular message:** recurse `XSub.Interop.from(x.getSub())` /
    `…to(r.sub())`, guarded on presence (a null singular record component →
    leave the `X` sub at its default/absent).
  - **repeated message:** iterate `x.getSubList()` → `Interop.from`; build with
    `addAllSub(List)` / `addSub(…)` per element.
  - **map of scalars:** `x.getMMap()` → record `Map` via per-entry put; R→X via
    `putAllM(…)` / `buildMap`. Map-of-message / map-of-enum **carry-variant**.
  - **bytes:** `x.getB().toByteArray()` (X→R); R→X `ByteString.copyFrom(r.b())`
    / `ByteString.EMPTY` when the record's `byte[]` is empty.
  - **presence/nullable:** a presence-tracked (nullable) record component maps to
    the protobuf-java field's presence (`hasX()`-guarded where relevant).
- **TD5 — optional gRPC adapter module (resolves R3).** A small new module
  `:recordbuf-grpc` (Kotlin DSL, `java-library`) with `implementation
  io.grpc:grpc-core` + `implementation project(':recordbuf-runtime')` exposing
  `bijnagte.recordbuf.grpc.GrpcMarshaller.of(ProtobufMarshaller<T>)` →
  `io.grpc.MethodDescriptor.Marshaller<T>` (a 2-method delegate; both throw
  `IOException`, so no exception wrapping). **No gRPC integration test in v1** —
  the module compiles against grpc-core; an integration test is a carry-variant.
  **Gate this unit on io.grpc resolving at build time; if it cannot fetch, drop
  the module and record the decision rather than break the build.**
- **TD6 — test scope (resolves R10, R11).** v1 reuses the **message** family
  (`message.proto`: singular + repeated nested messages + scalars) and adds an
  **interop layer** on top of the existing per-family harness: one `X↔R`
  round-trip per field family the fixtures already cover (i32/fixed/string/
  bytes/message/packed/optional/map + `compat` end-to-end), each asserting
  `assertEquals(r, R.Interop.from(X))` and
  `assertArrayEquals(sameProtoX().toByteArray(), R.Interop.to(r).toByteArray())`.
  Layer A gets a `toByteArray`/`fromBytes`/`ProtobufMarshaller` round-trip check
  in `runtime`. No new field-type families.

## Open Questions

None blocking v1 (see Deferred / Follow-on below).

## Implementation Units

- **Unit 21 — Layer A runtime surface (no new dep).**
  - `runtime/src/main/java/bijnagte/recordbuf/runtime/ProtobufMessage.java` (marker).
  - `runtime/src/main/java/bijnagte/recordbuf/runtime/ProtobufMarshaller.java` (core, TD2).
  - Tests: `runtime/src/test/java/bijnagte/recordbuf/runtime/ProtobufMarshallerTest.java`
    — build a small hand-written `ProtobufMessage` impl (or use the fixtures
    `rec.Sample`) and assert `write`/`read` round-trip and byte-identity.
  - **Test scenarios:** write+read round-trips a populated value; read of an
    empty buffer yields the encoded default; byte-identity between
    `value.toByteArray()` and the record's `Codec.encode(value)`.
- **Unit 22 — Layer A generator hook (always-on).**
  - `generator/…/model/Message.java` — add a `ProtobufMessage` marker emission flag or simply
    always emit; no new model field required (always-on).
  - `generator/…/CodeGenerator.java` / `RecordGenerator.java` — add
    `bijnagte.recordbuf.runtime.ProtobufMessage` to the record's `implements`
    clause (via the `use(...)` collector) and emit `toByteArray()` (instance,
    `@Override`) and `fromBytes(byte[])` (static), after the builder, before the codec.
  - Regenerate fixtures: `./gradlew :recordbuf-fixtures:generateProto` then
  `:recordbuf-runtime:test` + fixture tests still green.
  - **Test scenarios:** a generated record implements `ProtobufMessage`; its
  `toByteArray()` equals `Codec.encode(this)`; `fromBytes(bytes)` equals
  `Codec.decode(bytes)`; the `implements` clause renders the marker short +
  imported (extend `ImplementsClauseEmissionTest` / add a `RecordHookEmissionTest`).

- **Unit 23 — Layer B option + model + reader.**
  - `options/src/main/proto/recordbuf_options.proto` — add the `recordbuf.protobuf_java`
    **MessageOptions** extension at **tag 50002** (singular string), with the
    same Javadoc/rationale style as `java_interface`.
  - `generator/…/model/Message.java` — add a `@Nullable String protobufJava`
    slot (blank/null = "not opt-in / derive"); keep null-safety per the
    `interfaces` pattern.
  - `generator/…/model/ModelLoader.java` — read tag 50002 like `interfacesFor`
    (compiled unknown field + uninterpreted fallback), populate `Message.protobufJava`.
  - Tests: `ModelLoaderTest` / options-emit test asserting tag 50002 is parsed
    and threaded; a `recordbuf_options.proto` round-trip still emits.
  - **Test scenarios:** a message carrying `(recordbuf.protobuf_java) = "…"`
  resolves `protobufJava`; an absent option leaves it null (no converter);
  duplicates de-dup in declaration order like `java_interface`.

- **Unit 24 — Layer B converter emitter.**
  - New `generator/…/writer/InteropGenerator.java` (or a section on
    `CodeGenerator`) emitting the nested `X.Interop` type when a message has
    `protobufJava`, per TD4 field rules, with `from(X)` + `to(R)`.
  - Wire it in `CodeGenerator.emitMessage`/`recordAndCodec` so an opted-in message
    (and its same-file nested closure) emit `X.Interop`.
  - Regenerate the fixtures with the option set on `message.proto`
    (`Sample` + `Item`) and `compat.proto` (`Compat` + `Sub`) and assert the
    generated `Interop` compiles and round-trips.
  - **Test scenarios (per family, extends `CompatibilityTest` style):**
    - `X↔R`: `assertEquals(sample, Sample.Interop.from(sampleX))`;
    - byte-identity: `assertArrayEquals(sampleX.toByteArray(), Sample.Interop.to(sample).toByteArray())`;
    - default/empty: a default `X` and default record interop both encode to
    zero bytes; singular-message null ↔ absent;
    - enum: each `Kind` constant round-trips by number through `X.Interop`;
    - map: `Map<String,Integer>` round-trips X↔R; 
    - bytes: `ByteString`↔`byte[]` including empty and non-empty.

- **Unit 25 — optional gRPC adapter (best-effort, gated).**
  - New module `grpc/` (`build.gradle.kts`, `java-library`,
    `implementation libs.grpc.core` — add a `grpc` version to the catalog),
  `implementation project(':recordbuf-runtime')`;
    `grpc/src/main/java/bijnagte/recordbuf/grpc/GrpcMarshaller.java`
    (TD5). Register in `settings.gradle.kts`.
    - If `io.grpc:grpc-core` does not resolve at build time, drop this unit and
    record a `--` decision note instead of breaking `./gradlew build`.
  - **Test scenario:** `GrpcMarshaller.of(ProtobufMarshaller.of(Sample::fromBytes))`
    yields a `MethodDescriptor.Marshaller<Sample>` that round-trips a value
    through `ByteArrayOutputStream`/`ByteArrayInputStream` (compile + a
    stream round-trip test, no live gRPC channel).

## Dependencies / Sequencing

1. **Unit 21** (runtime surface) — standalone, no deps.
2. **Unit 22** depends on 21 (needs `ProtobufMessage`/marshaller at runtime).
3. **Unit 23** depends on nothing new (options proto + model).
4. **Unit 24** depends on 23 (needs the option + `protobufJava` slot).
5. **Unit 25** depends on 21 (needs the core marshaller); gated on io.grpc fetch.

Verify with `./gradlew build` after 21→22, after 23→24, and after 25.

## Risks / Deferred / Follow-on

- **Stream-direct marshalling** (encode into/decode from the stream, no
  `byte[]`) — named follow-on; v1 uses the intermediate byte[].
- **Cross-file message references** in a converter — carry-variant; v1 is same-file closure.
- **Map-of-message / map-of-enum** in a converter — carry-variant; v1 is scalar/enum maps.
- **Unknown-field preservation** through `X↔R` — carry-variant; v1 round-trips
  the *modeled* fields; unknown fields on the `X` side are not carried into the
  record and are not written back.
- **`UNRECOGNIZED` asymmetry** — X-side unknown numbers survive as
  `UNRECOGNIZED`; a number with no matching record enum constant is a surfaced
  mismatch (TD4).
- **gRPC ServerMethodDefinition / client-call convenience** and a live gRPC
  integration test — carry-variant; v1 delivers the `MethodDescriptor.Marshaller`.
- **Builder-driven converter variant** (`R.Builder fromBuilder(X)`) — carry-variant;
  v1 emits the static `from(X)`/`to(R)` pair (TD6).
