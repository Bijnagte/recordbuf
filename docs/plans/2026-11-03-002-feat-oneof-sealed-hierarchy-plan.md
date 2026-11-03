---
title: Oneof Sealed Hierarchy - Plan
type: feat
date: 2026-11-03
topic: oneof-sealed-hierarchy
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
artifact_readiness_note: enriched-by-ce-plan
product_contract_preservation: Product Contract unchanged — enrichment added only HOW (KTDs K1–K8, units U1–U5, files, test scenarios, sequencing); no R/A/F/AE rewritten. The single Outstanding Question (the one test fixture's exact proto shape) is resolved inside U4.
---

## Goal Capsule

- **Objective:** A proto `oneof` becomes a nested `sealed` interface on the generated
  message record, with the member types participating directly where they can
  (message/enum members whose type is generated in this request) and via a
  generated wrapper fallback where they can't (scalar members, or external types
  we don't regenerate). Record component per oneof is a single `@Nullable`
  sealed-interface reference: `null` = "no case set", non-null = "case active."
  Wire round-trip preserves last-write-wins.
- **Product authority:** recordbuf owner. This closes the deferral recorded in
  `docs/plans/2026-10-31-001-fix-wire-format-compatibility-plan.md`
  ("Oneof representation — mutually-exclusive `@Nullable` components vs a sealed
  hierarchy"). No backwards-compat with the current degraded output is required.
- **Execution profile:** Code — a new `Oneof` model record + `Message.oneofs`, a
  cross-file `permits`/`implements` aggregation pass feeding
  `CodeGenerator.generate()`, changes to the record/codec/builder emitters, one
  new runtime accessor interface, and one new fixture proto + round-trip test.
  Enriched by `ce-plan` to implementation-ready: KTDs K1–K8, units U1–U5,
  file-level detail, and test scenarios below.
- **Status:** Implementation-ready. The `proto3 optional` scalar-at-default-in-a
  oneof wire-shape concern is resolved in K6: the recordbuf codec always emits
  the active oneof member (presence is the oneof bit, not the scalar default),
  which is self-consistent and diverges from protobuf-java's proto3 default
  omission — acceptable because the interop bridge stays off for oneofs (U4 pins
  this with a recordbuf self round-trip, no protobuf-java comparison).

---

## Product Contract

<!-- UNCHANGED BY ENRICHMENT — the origin's requirements/acceptance examples below are preserved verbatim. Everything after the `## Planning Contract` heading is enrichment-only HOW. -->

### Summary

A `oneof` in proto becomes a nested `sealed interface` on the generated message
record. The record carries one `@Nullable <cap(oneof)>` component per oneof;
`null` means "no case set," and a non-null value means "case is that variant."
Each valid singular member participates by type where possible — a message or
enum member's own generated record/enum implements the sealed interface directly
(via `implements <cap(oneof)>`, set by a cross-file pre-pass) — and falls back
to a nested wrapper record (named `cap(oneof)+cap(member)`) for the two cases
where type-identity is impossible: (a) a scalar member, whose boxed Java type is
not a natural implementation of a per-oneof interface, and (b) a message/enum
member whose type is imported into this request but not re-generated in this
request, so we cannot add `implements` to it. Every generated wrapper variant
implements a single shared runtime accessor interface (a proposed
`bijnagte.recordbuf.runtime.OneOfWrapper<T>` — exact name is a planning
detail) that exposes the underlying value via a generic getter; identity
members do not.

A new pre-pass (an extension of the `FileTable`, or a sibling registry
constructed at the start of `CodeGenerator.generate()`) computes, across every
file in the request — not just `file_to_generate` — two maps: (a) for each
generated type `T`, the union of oneof interfaces `T` must `implements` (from
every oneof that names `T` as a member); (b) for each oneof `O`, the full
`permits` set of its sealed interface, which mixes identity subtypes and
wrapper variants. This is what makes "type B that is a oneof member in two
different imported .proto files implements both `A.O1` and `C.O2`" work, and
what makes the cross-package `permits` clause correct when a permitted subtype
lives in a different file.

### Problem Frame

Oneof is currently completely unmodeled in this generator. The model loader
iterates `DescriptorProto.getFieldCount()`, which *includes* oneof members as
regular fields, and it ignores the `oneof_decl` list and each field's
`oneofIndex` (see `ModelLoader.loadMessage` at
`generator/src/main/java/bijnagte/recordbuf/plugin/model/ModelLoader.java:113`).
The result today is that a proto `oneof` silently degrades into N independent
fields of the parent record — mutual exclusion is not enforced by the type
system, and the "at most one member may be set" invariant that oneof exists to
guarantee is lost. The prior wire-format-compatibility plan
(`docs/plans/2026-10-31-001`) explicitly recorded this as a deferred item and
left the representation open.

The specific scenario the user identified as motivating this work: a member
type that is a oneof member in more than one `.proto` — e.g. `oneof o1 { B b =
1; }` in `A.proto` and `oneof o2 { B b = 2; }` in `C.proto`, both imported into
each other's scopes via `import`. A naive per-oneof sealed interface would
leave `B` unrelated to both `o1` and `o2`'s interfaces; the type-identity
hybrid, with a cross-file pre-pass, makes `B` implement both.

### Key Decisions

- **Sealed interface per oneof, over N mutually-exclusive `@Nullable`
  components.** A sealed interface is the natural Java primitive for "exactly
  one of K types"; the compiler enforces exhaustive `switch`, and it composes
  cleanly with cross-package `permits` (a leaf `final` subtype such as a
  record is legal even across packages in the unnamed module). Chosen over the
  deferred N-components alternative because the alternative leaves mutual
  exclusion to runtime and doesn't compose when a type is a member of multiple
  oneofs — which is the user's stated motivating scenario.

- **Type-identity with wrapper fallback (the "hybrid"), over variant-per-oneof
  and over type-identity-only.** A singular message/enum member whose own
  generated type is emitted in this request *is* the sealed interface's
  permitted subtype; the two cases where type-identity is impossible (scalar
  members; external types we don't regenerate) fall back to a nested wrapper
  record. Chosen over variant-per-oneof because variant generation would
  *hide* the type behind one wrapper-record-per-oneof, defeating the cross-file
  composition the user's scenario needs. Chosen over type-identity-only because
  it would reject valid protos (any scalar in a oneof).

- **Cross-file generation-time pre-pass** over the full request computes two
  maps before per-file codegen: (a) for each generated type, which oneof
  interfaces it must `implements`; (b) for each oneof, its full `permits` set.
  The maps are cross-file, not per-file, because `B` in `C.proto` cannot know
  it's a member of `A.O1` without seeing every oneof declaration in the
  request. The infrastructure already exists: `FileTable` is built from the
  request's full `proto_file` list (including dependencies not in
  `file_to_generate`), so a new pass built *on* `FileTable` is natural. Chosen
  over per-file local resolution which would leave cross-file shared types
  broken.

- **A single `@Nullable <cap(oneof)>` component per oneof on the record.** The
  component's null vs non-null *is* the presence signal; last-write-wins on
  decode is then a property of "assign the new variant to the component; it
  overwrites." Chosen over N components and over an enum-tagged discriminator
  because the sealed interface is itself the discriminator.

- **Single typed builder setter that overwrites; `null` clears.** Per the
  product call, the builder setter for a oneof takes the sealed-interface type
  (`@Nullable <cap(oneof)>`), not per-member setters. Since a oneof is a
  single record component, mutual exclusion falls out of the type system — the
  setter simply overwrites, and `null` is a natural "unset the case" that
  matches `clearOneof` in protobuf-java. Chosen over per-member setters
  because those grow the generated surface and require the caller to name a
  per-member setter even before naming the interface.

- **Naming: `cap(oneof)` for the interface; `cap(oneof)+cap(member)` for
  wrapper variants.** Simple, predictable, no magic suffixes. The user has a
  free hand (no backwards-compat constraint), and a per-oneof interface name
  colliding with a real message/field name is a user-naming-space concern; if
  needed a user can pick a distinct oneof name.

- **A shared wrapper-accessor interface, over per-variant typed accessors.**
  Every generated wrapper-variant record implements one shared runtime
  interface (proposed `bijnagte.recordbuf.runtime.OneOfWrapper<T>`, exact
  name a planning detail) that exposes the underlying value with a generic
  getter. This lets a caller uniformly write `iface instanceof OneOfWrapper<?>
  w ? w.value() : ...` — the underlying type is reachable without naming a
  concrete wrapper class. Identity members (own message/enum implementing the
  interface) do **not** implement the accessor interface: for them `iface`
  *is* the underlying type. Chosen over per-variant accessor methods because
  uniform access is more ergonomic and lets code that has only an `iface`
  reference reach the underlying value generically, without knowing which
  wrapper variant was constructed.

- **Fork: cross-package `permits` clause is generated cross-file.** Because a
  subtype can be declared in a different file (the shared-member scenario),
  its `permits` listing must be computed from the whole request, exactly
  parallel to the `implements` aggregation. Java permits a leaf `final` subtype
  across packages in the unnamed module on the classpath, but the `permits`
  clause must be explicit. If a future strict-mode target makes this infeasible,
  the fallback is a non-sealed interface (loses exhaustiveness) or a shift to
  the variant-per-oneof model for that oneof only.

### Requirements

**Model and oneof entry**

- **R1. Oneof modeling.** `ModelLoader` reads the `oneof_decl` list and each
  field's `oneofIndex`. A message with K oneofs gains K `Oneof` model entries
  nested on the `Message` model; each `Oneof` entry has a name, a list of
  members (their field descriptors), and — for each member — the resolved Java
  type it maps to. The loader stops emitting oneof members as independent
  fields of the parent `Message`; a oneof's members live only inside the
  `Oneof` entry. Non-member fields of the `Message` are unaffected.

- **R2. One nested sealed interface per oneof.** For each `Oneof` entry, the
  generated Java output contains a `sealed interface <cap(oneof)>` nested
  inside the parent message record, in the same scope as nested messages and
  nested enums. Its `permits` clause lists every permitted subtype of the
  oneof — identity subtypes (own message/enum types that implement it) and
  wrapper variants (per the fallback in R4). The `permits` list is computed
  cross-file by the pass in R6; within a single file the list is the union of
  the file's own wrappers and the cross-file identities.

- **R3. One `@Nullable <cap(oneof)>` component per oneof on the parent record.**
  The record has a component for each oneof, typed as the sealed interface (or
  its equivalent wrapper type), annotated `@org.jspecify.annotations.Nullable`.
  `null` means "no case set." The record's canonical constructor, compact
  constructor, and `equals`/`hashCode` handle `null` naturally.

- **R4. Type-identity for message/enum members, with a wrapper fallback.** A
  singular message or singular enum member whose own generated type IS emitted
  in this request implements the sealed interface directly: the type's emitted
  `implements …` clause gains `<cap(oneof)>`. Where type-identity is
  impossible — (a) a scalar member, or (b) a message/enum member whose type is
  imported into this request but not in `file_to_generate` — a nested wrapper
  record is generated in the parent message, named
  `cap(oneof) + cap(member)` (e.g. a oneof `payload` with a member `string s`
  yields a nested `PayloadString` wrapper), that holds a single field of the
  underlying Java type and `implements <cap(oneof)>`. The wrapper is a legal
  permitted subtype of the oneof interface.

- **R5. Wrapper-accessor interface.** Every generated wrapper-variant record
  also implements one shared runtime accessor interface — the concrete
  interface is a new top-level interface in
  `bijnagte.recordbuf.runtime`, proposed name `OneOfWrapper<T>` with a getter
  `T value()`. Each wrapper record parameterizes `OneOfWrapper` over its
  single component's type. A record's canonical constructor returns the
  component through the `value()` default. Identity members do *not* implement
  the accessor interface — for them the sealed iface *is* the underlying type.
  This requirement is the reason the "no new runtime classes" non-goal below
  is narrow: one new interface, no new classes.

**Cross-file resolution**

- **R6. A generation-time cross-file pre-pass** runs at the start of
  `CodeGenerator.generate()`, before the per-file two-pass
  (collect+render) already in place. It computes two maps by iterating *every*
  file in the request (`proto_file` list including dependencies, not just
  `file_to_generate`):
    - Map (a) "implements map": for each generated Java FQN emitted by this
      request, the set of sealed-interface FQNs this type must `implements`,
      aggregated from every oneof in every file of the request that names this
      type as a member. The map is keyed by generated type FQN.
    - Map (b) "permits map": for each oneof, its full `permits` set =
      (identity subtypes: FQNs of member types that ARE generated in this
      request and can implement the interface) ∪ (wrapper variants: the
      generated wrapper record names for members that fall back via R4, scoped
      to the enclosing message).
  Both maps are consumed during emission: map (a) feeds each generated
  type's `implements …` clause; map (b) feeds each oneof's `permits …`
  clause. The pre-pass is a new concern layered on the existing
  `FileTable` (which already indexes every file); whether it lives in
  `FileTable` itself or in a new `OneofRegistry` sibling is a planning
  detail with no observable difference.

- **R7. No cross-request coupling.** No generated code in a single request may
  depend on a sealed-interface `permits` or `implements` set computed by a
  different request. When a request generates `A` with `oneof oa { B b = 1; }`
  and `B` is a type from a .proto that recordbuf does NOT generate in this
  request, `B` cannot implement `A.oa` — R4's wrapper fallback applies:
  `A.PayloadB` is generated as a nested wrapper in `A` that
  `implements A.oa` and carries the `B` value. A *subsequent* request that
  does regenerate `B` (with `B`'s .proto declaring the oneof in question in
  this request) may then emit `B implements A.oa` in `B`'s generated file
  instead. No generated code in a single request requires code generated in
  another request.

**Wire round-trip**

- **R8. Encode.** For each active oneof on a record being encoded (component
  non-null), the codec emits wire bytes for that oneof's active member: a
  wrapper-variant variant unwraps its single field and emits the underlying
  value (scalar writes via the existing scalar writer; message emits via the
  nested `Codec.encode`; enum writes `writeEnum`); an identity variant emits
  itself. On the wire, a oneof member looks like a regular scalar/message/enum
  field — no oneof-specific framing. Last-write-wins on encode is a property
  of "one component holds at most one variant."

- **R9. Decode.** The codec's `fieldNum` switch has a case for each oneof
  member; that case decodes the value and assigns it to the oneof's component
  — overwriting any prior value. Which of the N members of a given oneof
  "wins" depends on the last tag seen on the wire for that oneof group; this
  is a direct consequence of "assign the new variant to the component,
  overwriting." If a schema models a oneof but a different wire shape is
  seen, the field is captured into the record's unknown-field carrier as
  today (no per-oneof handling needed for un-modeled).

- **R10. Proto3 optional presence within a oneof.** A `proto3 optional` scalar
  or enum member in a oneof tracks presence via the non-nullness of the
  oneof component (not via a per-variant presence flag). A set-but-default
  scalar in a `proto3 optional` position is still "set" and survives a
  round-trip. Planning pins the wire-level detail: a proto3 `optional` scalar
  set to its default emits on the wire or not — validated against
  protobuf-java via the interop comparison (see Open Questions).

- **R11. Mutual exclusion on encode is a property of the type.** Since a oneof
  is a single record component, only one variant can be present at a time;
  there is no runtime check to generate, and no per-field invariant to
  assert.

**Builder**

- **R12. Builder setter for oneof.** Each `Oneof` contributes one setter on
  the parent `Builder`, typed `public Builder <cap(oneof)>(@Nullable
  <cap(oneof)> value) { this.<oneof>Component = value; return this; }`.
  The setter overwrites, so calling it with a new value (including `null` to
  clear) is the only call; no per-member builder methods, no "clear X"
  sibling. This is the "single typed setter" product call.

### Non-goals

- **No support for `repeated`-in-oneof or other proto-invalid constructs.**
  protoc rejects those upstream; no test or code path is needed.
- **No `proto2 group` handling** — still a deferred non-goal from
  `docs/plans/2026-10-31-001`; not picked up here.
- **No backwards compat with prior oneof output.** The prior output was N flat
  `@Nullable` fields per oneof; nothing in this plan's requirements tries to
  preserve that shape.
- **No per-oneof interop test against protobuf-java in this plan.** The
  `recordbuf.protobuf_java` interop bridge stays off for oneofs unless a
  message opts in — that's a natural follow-on, not a requirement here.
- **No new proto options.** `recordbuf.protobuf_java` and friends are
  unchanged; oneof handling applies uniformly regardless of opt-in.
- **No new runtime classes beyond the shared wrapper-accessor interface**
  required by R5 (a single new top-level interface in
  `bijnagte.recordbuf.runtime`).
- **The `unknownFields` carrier is unchanged.** A oneof member seen on the
  wire with no matching schema is captured by the unknown-field carrier as an
  un-modeled field, exactly as today.

### Approaches considered, in order

- **Variant-per-oneof (fully localized).** Each oneof generates its own sealed
  interface + a wrapper record per member. Trivial to implement, no cross-file
  pass. Rejected because the user's motivating scenario is a type that is a
  oneof member in two different .proto files — in this model such a type is
  wrapped *twice* (once per oneof) and is not itself a member of either
  interface, defeating the cross-file composition the user asked for.

- **Type-identity, no wrapper fallback.** Every oneof member is its own type;
  scalars and external types are unsupported. Cleanest conceptually but rejects
  valid protos (any scalar in a oneof). Rejected for obvious reasons.

- **Type-identity, hybrid with wrapper fallback (chosen).** As described above.
  Directly supports the cross-file shared-type scenario, and the wrapper
  fallback (plus its shared accessor interface) keeps the "reach underlying
  value generically" ergonomic even for scalars and cross-file externals.

- **Mutually-exclusive N `@Nullable` components** (the deferred option from
  2026-10-31-001). Flat fields with a runtime assertion or validation of the
  "at most one set" invariant. Rejected because the type system would have no
  say and because the shape does not compose when a type is a member of
  multiple oneofs — the user's core use case.

### Scope Boundaries

- In scope: modeling of `oneof`, its members (singular; scalars, messages,
  enums — with `proto3 optional` scalar/enum variants), the sealed interface
  and its `permits`, the record component and `@Nullable` annotation, the
  wrapper fallback, the shared wrapper-accessor interface, the builder setter,
  cross-file pre-pass, and wire round-trip with last-write-wins.
- Out of scope: `repeated`-in-oneof, `group`, proto2 semantics in general,
  new proto options, any change to the `unknownFields` carrier, per-message
  interop of oneof via `recordbuf.protobuf_java`, nested oneof inside a nested
  oneof inside a message (a nested oneof *inside* a nested message is fine;
  oneof-inside-multiple-levels of nesting is fine via the same mechanism; but
  "oneof-of-oneofs" — which is not a legal proto construct — is out of scope
  trivially because it isn't a proto).
- Deferred to a future plan: the exact wire-shape pin for a `proto3
  optional` scalar set to default inside a oneof (see Open Questions); the
  interop bridge for `recordbuf.protobuf_java` messages that carry oneofs.

### Open Questions

- **Exact name and getter signature of the runtime interface**: proposed
  `bijnagte.recordbuf.runtime.OneOfWrapper<T>` with `T value()`. Placement is
  settled — a hand-authored file in `recordbuf-runtime`, imported by generated
  wrappers (R5) — only the name and getter signature are open.
- **Where wrapper variants live in the generated output**: nested in the parent
  message record (current proposal) or in a sibling file. Planner picks;
  nested-in-parent is expected.
- **Naming collision**: when a user names a oneof the same cap'd name as a
  field or message in the same scope, the generator must have a deterministic
  behavior. Simple rule: oneof wins; alternative: error at generation time.
  Planning picks.
- **Wire pin for a `proto3 optional` scalar-in-oneof** set to default value:
  does it emit on the wire and does round-trip recover presence correctly?
  Verified against protobuf-java via the interop comparison — flagged to
  planning because it is the one non-obvious behavior the sealed-hierarchy
  design inherits.
- **Test fixture**: a new `.proto` (or an extension to
  `fixtures/bin/main/compat.proto`) with one or more oneofs — mixing a scalar
  member, a message member, and (to prove R6) a *shared* member across two
  oneofs in two .proto files imported into each other. Planner shapes the test
  and the round-trip assertions.

### Risks

- **Cross-package `permits` clause.** A sealed interface with a permitted
  subtype in another package must explicitly list that subtype in
  `permits`; this is legal on the classpath (unnamed module) but is a Java
  requirement that has historically surprised people. The cross-file pre-pass in
  R6 is designed to produce that clause. If a future strict mode (a Java
  module with cross-module sealed interfaces) requires more, the fallback is
  a non-sealed interface or a per-oneof variant shift.
- **Pass ordering.** The cross-file pre-pass (R6) must run *before* the
  per-file two-pass (collect+render) already in `CodeGenerator.generateFile`,
  since both the type's `implements` list and the interface's `permits` list
  depend on it. If the pre-pass is not in place before generation, the
  generated `implements`/`permits` clauses will be missing cross-file
  entries.
- **Nested oneof coverage.** A oneof inside a nested message must generate the
  interface in the nested message, and its members' `permits`/`implements`
  must resolve through the nested scope. Current test fixtures exercise nested
  *messages* but not nested *oneofs*; planner adds the nested case.
- **Wrapper variant shape.** If the wrapper record ends up carrying more than
  one field (e.g. to keep parity with a specific API), the "single-field
  wrapper" assumption in R4 and the accessor surface in R5 change. Planner
  should keep the wrapper shape minimal unless a specific need forces otherwise.

---

## Planning Contract

> Enrichment-only HOW. The Product Contract (above) is unchanged; the KTDs,
> units, files, and test scenarios below are how it gets built. Decisions that
> would change a product contract item escalate back rather than proceed.

### Key Technical Decisions

K1. **Model: add `Oneof`; stop flattening; skip synthetics.** Add a new model
record `bijnagte.recordbuf.plugin.model.Oneof` (carry `@RecordBuilder` like every
other `model` record, JSpecify-safe): `record Oneof(String name, List<Field> members)`
with a compact-ctor invariant (`members` non-null, `List.copyOf` when non-empty). Add a
`List<Oneof> oneofs` component to `Message` (top-level and every nested message,
built like `nestedMessages`). In `ModelLoader.loadMessage`, a field is a *real oneof
member* iff `field.hasOneofIndex() && !field.getProto3Optional()` — the
`!getProto3Optional()` predicate distinguishes a real oneof member from a proto3
optional scalar's *synthetic* `oneofIndex`. Real oneof members leave the new
`fields()` list and are grouped by `getOneofIndex()` into `Oneof` entries built
from `descriptor.getOneofDeclList()`; a `oneofDecl` whose sole member has
`proto3Optional == true` is a synthetic oneof and is **skipped entirely** (its
member stays a top-level field). This realizes R1 (loader stops emitting oneof
members as independent fields) and R10's "not classified as a oneof member." (API
confirmed against `protobuf-java 4.36.2`: `DescriptorProto.getOneofDeclList()`,
`FieldDescriptorProto.hasOneofIndex()/getOneofIndex()/getProto3Optional()`.)

K2. **One `@Nullable` component per oneof; `null` = no active case.** The record
gets exactly one `@Nullable <Cap(O)>` component per oneof `O`, named
`JavaNames.cap(O.name)` (e.g. `payload`, `oa`), typed as the sealed interface
`Cap(O)`. Absence is `null`; active is any non-null instance (an identity subtype
*or* a wrapper). No active-case sentinel. The canonical constructor, compact ctor,
`equals`/`hashCode`, `build()`, and `toBuilder()` each carry this one component
(not one per member), so N flattened members collapse to 1 sealed component. The
builder gets one fluent setter `Builder <cap(o)>(@Nullable <Cap(o)>)` — a pure
assigner — mirroring the existing `emitBuild`/`addBuilder` per-component
pattern; `null` clears (a natural "unset the case," cf. protobuf-java's
`clearOneof`, R12).

K3. **Cross-file pre-pass in `generate()` (the load-bearing design).** Compute
`permits` and `implements` over the *whole* request before per-file emission,
because a generated type `B` may be a member of oneofs in several files. Restructure
`CodeGenerator.generate`:
  1. `FileTable table.addAll(request.getProtoFileList())` (unchanged — maps every
     proto FQN → Java FQN *including dependencies*).
  2. **Load every model first:** a `Map<String /*fileName*/, ProtoFile>` for all
     files in `file_to_generate` (hoisted out of the per-file loop) plus a set
     `generatedTypes` = the Java FQNs of every top-level and nested type in those
     models (this is the "is it generated in this request" gate).
  3. From the loaded models build a request-scoped `OneofRegistry` holding two maps
     (`permits map`, `implements map`). For each `Oneof O` in each generated
     message `M` (interface FQN = `M.javaFqn + "." + cap(O.name)`), per member `f`:
     - if `f`'s resolved Java type (`table.javaFqn(f.typeUrl())`) ∈
       `generatedTypes` → **identity**: add `<O-interface>` to that type's
       `implements map`, and add the member's Java FQN to `O`'s `permits`.
     - otherwise → **wrapper**: nest `Cap(O)Cap(member)` in `M` (holds the
       underlying value; `implements Cap(O), OneOfWrapper<Underlying>`), add the
       wrapper FQN to `O`'s `permits`; the member's own type implements nothing.
  4. Emit each file, threading `registry` into the writers (request-scoped like
     `table`/`typeImports`). Per R6 the maps iterate the whole request; per R7 the
     "generated in this request" gate keeps a type generated-alone from getting a
     dangling `implements` and keeps the wrapper scoped to the file that owns the
     oneof. (Whether the maps live in `FileTable` or a new `OneofRegistry` is
     immaterial per R6; a separate `OneofRegistry` is the cleaner seam.)

*Ungenerated message/enum oneof members* (a member whose proto file is a
dependency, not in `file_to_generate`): the wrapper holds the **raw nested-message
bytes as `byte[]`** (decode via `CodedInputStream` limit + raw-bytes capture; encode
via length-delimited framing), keeping the output dependency-free and
wire-faithful. This is the documented, secondary case (R4's "external type" / R7).
Scalars and generated messages/enums are the primary cases the fixture exercises.
An ungenerated **enum** member wraps as `int` (its wire form), not `byte[]`.

K4. **Sealed interface + wrapper emission.** For each `Oneof O` in `M`, emit a
nested `public sealed interface Cap(O) {}` in the same region as
`nestedMessages`/`nestedEnums` (before the builder + marshalling hook) with a
`permits` clause listing `permitsFor(O)`: nested wrapper types by **short name**
(same scope), cross-file identity subtypes via `TypeImports.use(fqn)`
(FQN + import). Each wrapper record is
`public record Cap(O)<member>(Underlying value) implements Cap(O),
bijnagte.recordbuf.runtime.OneOfWrapper<Underlying> { @Override public Underlying value(){ return value; } }`
with a one-line Javadoc naming the underlying type + the original proto member
(R5). `Underlying` per member kind: scalar → its boxed Java type; generated
message/enum → that type; ungenerated message → `byte[]`; ungenerated enum →
`int`. A `permits` clause is never empty (a oneof always has ≥1 member → permits ≥1).
Enums follow the message identity/wrapper split, so a generated `enum` that is a
oneof member `implements <O-interface>` (`EnumGenerator` consults the registry),
and an ungenerated one wraps.

K5. **Runtime accessor: `OneOfWrapper<T>`.** New hand-authored
`bijnagte.recordbuf.runtime.OneOfWrapper`: a generic `interface OneOfWrapper<T> { T value(); }`,
`@NullMarked` (the runtime package is `@NullMarked`). Generated wrappers are the
only emitters of a reference to it; the generator names it via
`TypeImports.addFQN(bijnagte.recordbuf.runtime.OneOfWrapper)` so the FQN lands as a
real import (same machinery as `ProtobufMessage`/`Marshalling`, R7). No dependency
is added to the generator build (the runtime is not on the generator's compile
path; generated code references it by text, exactly as it already names
`com.google.protobuf.CodedOutputStream`). Resolves the Open Question "exact name
and getter signature of the runtime interface" → `OneOfWrapper<T> { T value(); }`.

K6. **Codec: oneof members are always-emitted; decode is last-write-wins.** In
`CodecGenerator.emitRecord`:
  - **Encode:** build one `(int number, emit-lambda)` list merging non-oneof
    `fields()` and `oneofs()` then sort ascending by number (the existing loop
    already sorts `fields()` by `field.number()`), and emit. For an *active*
    oneof (component non-null) the lambda dispatches on the component's static
    type: identity → emit the active member via its `writeX(num, value)` path (a
    oneof member's wire bytes *are* the member's own bytes — R8/R9, no oneof
    framing); wrapper → unwrap `((Cap(O)<member>) v.field()).value()` then
    `writeX(num, value)`. **Always emit the active member, even at the wire
default** — no proto3 default-omission guard — because presence is the oneof bit,
    not the scalar default; omitting a default-valued active member would drop the
    active case. This resolves the open `proto3 optional` scalar-at-default shape
    (R10): the recordbuf codec is self-consistent (round-trips a default-valued
    active case) and diverges from protobuf-java's default omission — acceptable
    because the interop bridge stays off for oneofs (non-goal). The encode side
    never sees "two active cases" (one component holds one value), so no
    encode-side disambiguation is needed (R11).
  - **Decode:** for each oneof member's field number add a `case N -> {` into the
    `fieldNum` `switch`, each overwriting that oneof's `@Nullable Cap(O)` local:
    identity → assign the decoded underlying value directly (it already implements
    `Cap(O)`); wrapper → decode the scalar/enum/bytes into a local then
    `local = new Cap(O)<member>(val)`. Because each case overwrites the oneof's
    local, the last tag seen for that group wins — **last-write-wins on decode**
    (the AE). The existing "unknown/un-modeled → carrier" fallthrough (R9) is
    untouched: a oneof member seen with no matching schema still lands in
    `unknownFields`.

K7. **Nullability / wiring.** The sealed interface, the wrapper records, and the
`@Nullable` component are emitted as *text* — the generator references
`bijnagte.recordbuf.runtime.OneOfWrapper` + member-type FQNs through
`TypeResolver`/`TypeImports` (never a runtime type dependency; R4). New generator
code lives in a JSpecify-`@NullMarked` `model` package: `Oneof` carries
`@RecordBuilder` + `@Nullable` where appropriate; `OneofRegistry`'s two maps are
non-null, empty-defaulted. The NullAway error-severity gate stays on for
`:recordbuf-generator` and `:recordbuf-runtime` (both already `@NullMarked`), so a
null regression fails the build, not a warning (R11's "mutual-exclusion-on-encode
is a property of the type," never silently broken).

K8. **Canonical component order.** One deterministic order governs *every*
component-touching emitter (record header, constructor params, `equals`,
`hashCode`, `builder()` fields, `toBuilder()` copy, `build()`, codec-decode locals
+ final construction): `fields()` (as today) → `oneofs()` (one component each) →
the `unknownFields` carrier (as today, last). Declaration order is irrelevant to
the wire (wire is by field number), so this is a pure source-shape choice; no oneof
was modeled before, so there is no byte obligation to a prior layout. R12 stays
testable: when `oneofs()` is empty every oneof emitter is a no-op and existing
output is byte-for-byte identical.

### High-Level Technical Design

```
protoc --> CodeGeneratorRequest (all files incl. deps)
     |
     v
FileTable.addAll(protoFileList)     protoFQN -> javaFqn (+ byName)        [K3.1]
load models for file_to_generate    generatedTypes = {javaFQNs}            [K3.2]
build OneofRegistry                 permits map + implements map            [K3.3]
     |                                                                         
     v  (per generated message M)
ModelLoader: Message.oneofs() = grouped real oneof members (synthetics skipped)   [K1]
RecordGenerator:  @Nullable Cap(O) oa  +  sealed interface Cap(O) + wrappers      [K2,K4]
                  implements clause += registry.implementsFor(M)                  [K3,K4]
                  (EnumGenerator: same, for enum oneof members)                   [K4]
CodecGenerator:   encode merges (fields+oneofs) by #, always-emit active          [K6]
                  decode: case per member -> overwrite oneof local (LWW)          [K6]
Builder/ctor:     one setter + one ctor arg per oneof component                   [K2,K8]
```

### Open Questions — resolution

- **Runtime interface name/getter** → `bijnagte.recordbuf.runtime.OneOfWrapper<T> { T value(); }` (K5/U2).
- **Wrapper placement** → nested in the parent message record (K4/U2).
- **Naming collision** (a `cap(o)` colliding with a field/message) → the generator
  emits it as-is; a collision is a user-naming-space concern and out of scope
  (KTD above already notes it). No generator error is added.
- **`Proto3 optional` scalar-at-default wire shape** → always emit the active oneof
  member even at default; self-consistent round-trip, documented divergence from
  protobuf-java, acceptable because interop is off (K6/U3, pinned by a U4 test).
- **Test fixture** → a `oneof.proto` family + a cross-file pair, shaped in U4.

### Assumptions

- The generated message/enum referenced from a oneof may live in a different
  .proto, which may or may not be generated this request; the whole-request
  pre-pass (K3) is what makes cross-file and cross-request correct.
- The repo is unmodularized (classpath, unnamed module): nested `permits` and
  cross-file sealed `permits`/`implements` compile without `module` declarations.
- `sealed` (Java 17) is within the generated baseline (AGENTS.md targets Java 17+;
  the codec's `fieldNum` dispatch already uses pattern-matched `switch`).
- `proto2` / `group` remain out of scope (K1's synthetic skip is proto3-only).

### Sequencing & risk notes

- **U1–U2 must be output-preserving for every proto that has *no* oneof.** The
  single highest-risk structural change is K3.2's restructure of
  `CodeGenerator.generate()` (from per-file `ModelLoader` + immediate emit to
  "load all models first, build the registry, then emit"). For a proto without a
  oneof the registry is empty and every oneof emitter is a no-op, so the generated
  text must be byte-for-byte identical to today. The entire existing suite is the
  guard: U1 and U2 each assert `./gradlew build` green *with the prior 38+ tests
  unchanged*, which is impossible unless non-oneof output is untouched. This is the
  regression guard for the "no cross-request coupling / no new output when no oneof"
  guarantee (R7/R12).
- **Per-unit seam is source-compileable before the fixture.** U2 is verified by
  generator source-shape tests with no generated oneof to compile yet — that is
  deliberate and matches the repo's idiom (`BuilderEmissionTest`/`RecordHookEmissionTest`
  assert emitted-source substrings, not a compiled generated tree). The first
  *compilation* of real generated oneof code is U4; any compile error surfaces
  there, not in U2.
- **U1 is independently shippable and green on its own** (non-breaking: nothing
  generates an oneof yet). U2 → U3 → U4 are sequential and each green when built
  on the prior. U5 is docs/comments only.

---

## Implementation Units

### U1 — Model + loader: `Oneof` type, `Message.oneofs`, stop flattening, skip synthetics

- **Goal:** Introduce the `Oneof` model + the `oneofs` list on `Message`, and
  change `ModelLoader` so real oneof members leave `fields()` and group into
  `Oneof`, while synthetic proto3-optional oneofs are skipped.
- **Requirements:** R1, R10, K1
- **Dependencies:** none
- **Files:**
  - `generator/src/main/java/bijnagte/recordbuf/plugin/model/Oneof.java` — new
    `@RecordBuilder` + JSpecify-safe `record Oneof(String name, List<Field> members)`
    with a compact-ctor invariant mirroring `Message`, guarding `members` non-null / empty.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/model/Message.java` — add
    `List<Oneof> oneofs` (ordered right after `nestedEnums`, compact-ctor-normalized
    like `interfaces`); update the class Javadoc's `@param fields` line (it says
    "maps and oneofs expanded") to reflect that real oneof members now live in
    `oneofs`, not `fields`.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/ModelLoader.java` — in
    `loadMessage`: accumulate real oneof members by `getOneofIndex()` instead of
    into `fields`; build `oneofs` from `getOneofDeclList()` (skip synthetic
    oneofs where the sole member is `proto3Optional`); thread the same logic
    through the nested-message builder; update the class Javadoc.
  - `generator/src/test/java/bijnagte/recordbuf/plugin/model/OneofLoaderTest.java`
    — new (build `DescriptorProto`/`FileDescriptorProto`, idiom of
    `ImplementsClauseEmissionTest`).
- **Approach:** Group oneof members by `getOneofIndex()`; classify synthetic vs real
  by `getProto3Optional()`; skip synthetics. Leave every emitter that iterates
  `m.fields()` untouched — they simply stop seeing oneof members, a no-op today
  because no proto yet has a oneof (U4 adds the first one). Map handling is unaffected.
- **Test scenarios:**
  - **Group + stop-flatten:** a message with `oneof payload { string a=1; int32 b=2; Meta c=3; }`
    plus plain `int32 n=4`: `Message.oneofs()` has exactly one entry `payload` with
    three members (declaration order); `Message.fields()` holds only `n`.
  - **Synthetic skip:** `optional int32 x=5`: `oneofs()` empty; `x` is a top-level
    field with the existing proto3-optional handling; its synthetic `oneofIndex`
    is ignored.
  - **Nested oneof:** a nested message with its own oneof carries `oneofs()`; the
    parent does not inherit it.
- **Verification:** `./gradlew build` green — no proto yet has a oneof, so every
  emitter sees `oneofs() == []` and produces byte-identical output; the existing
  suite passes unchanged. `OneofLoaderTest` asserts the three scenarios. Because
  U1 is non-breaking it can land fully green on its own.

### U2 — Record/builder surface + sealed interface + wrappers + cross-file `implements`

- **Goal:** Emit, per oneof, the `@Nullable <Cap(O)>` component, the nested
  `sealed interface <Cap(O)>` with a cross-file `permits` clause, the nested
  wrapper records (impl `OneOfWrapper`), the `OneOfWrapper<T>` runtime interface,
  the per-oneof builder setter + constructor wiring, and — via the whole-request
  pre-pass — the `implements <O-interface>` clause on every identity-subtype
  record/enum.
- **Requirements:** R2, R3, R4, R5, R6, R7, K2, K3, K4, K5, K7, K8
- **Dependencies:** U1
- **Files:**
  - `runtime/src/main/java/bijnagte/recordbuf/runtime/OneOfWrapper.java` — new
    `@NullMarked` generic `interface OneOfWrapper<T> { T value(); }` + Javadoc.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/model/OneofRegistry.java` —
    new JSpecify-safe: the two cross-file maps + `permitsFor(oneofId)` /
    `implementsFor(typeFqn)`; built from `(loaded models, generatedTypes, FileTable)`, K3.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/CodeGenerator.java` —
    restructure `generate()`: load all models for `file_to_generate` up front
    (`Map<String,ProtoFile>` + `generatedTypes`), build `OneofRegistry`, then emit,
    threading the registry into `recordAndCodec`/`emitEnum`. Extend `addBuilder`/
    `emitBuild`/`freshDefault`(→`null`)/`copyOf`(→ by-value) for the `@Nullable Cap(O)`
    component + its assigner; extend `recordAndCodec` to call a new
    `RecordGenerator.emitOneofs(ci, types, m, registry)` in the nested-type region.
    Add `OneOfWrapper` to generated imports where a wrapper is emitted.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/RecordGenerator.java` —
    `emitHeader` extends `impls` with `registry.implementsFor(m.fullName())`;
    new `emitOneofs` producing the nested sealed interfaces + wrapper records.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/EnumGenerator.java` —
    extend its `impls` with `registry.implementsFor(e.fullName())` (a generated
    enum oneof member gets the oneof interface).
  - `generator/src/test/java/bijnagte/recordbuf/plugin/OneofEmissionTest.java` —
    new source-shape test (idiom of `ImplementsClauseEmissionTest`/
    `RecordHookEmissionTest`).
- **Approach:** Build the registry once (K3). For each oneof emit a nested
  `sealed interface` in the nested-type region with a `permits` clause =
  `permitsFor(O)` (wrappers short-named; cross-file identity subtypes via
  `ci.use(fqn)`); per wrapper emit the `record Cap(O)<member>(Underlying value)
  implements Cap(O), bijnagte.recordbuf.runtime.OneOfWrapper<Underlying>` with `value()`
  + Javadoc. Emit the `@Nullable Cap(O)` component into the record header + ctor +
  builder via the K8 order. A `permits` clause is never empty. A proto without
  oneofs emits nothing new (R12). Cross-file `B implements A.Oa, C.Oc` is the map
  (a) alone.
- **Test scenarios (source-shape, `OneofEmissionTest`):**
  - **One component:** a message with a oneof generates exactly one `@Nullable Cap(O)`
    component, not N per-member components.
  - **Sealed + permits:** source contains a nested `sealed interface Cap(O)` with a
    `permits` clause naming the wrappers / identity subtypes.
  - **Wrapper + `OneOfWrapper`:** a scalar member `string s` in `oneof payload` yields
    a `record PayloadString(String value)` implementing `Payload` and
    `bijnagte.recordbuf.runtime.OneOfWrapper<String>` with `value()`; an external
    member yields a `byte[]`-holding wrapper; an ungenerated enum yields an
    `int`-holding wrapper.
  - **No wrapper for identity:** a generated message/enum member gets no wrapper; its
    own record/enum `implements Cap(O)`.
  - **proto3-optional not a oneof:** a message with only `optional int32 x` generates
    no `sealed interface` and no oneof component.
  - **Cross-file shared member:** a `CodeGeneratorRequest` with `a.proto`
    (`package a`, `message A { oneof oa { B b=1; } }` + `message B{…}`) and `c.proto`
    (`package c`, `message C { oneof oc { B b=1; } }`, `import a.proto`): generated
    `B` `implements … A.Oa, … C.Oc`; each message's `permits` lists only its own
    interface's members.
  - **R12 no-regression:** a type in no oneof keeps today's `implements` clause
    (record: only `ProtobufMessage`; enum: none).
- **Verification:** `./gradlew :recordbuf-generator:test` (`OneofEmissionTest` +
  all prior generator tests). `./gradlew build` stays green (no proto yet has a
  oneof, so existing generated output is unchanged — a source-shape check, matching
  how `BuilderEmissionTest`/`RecordHookEmissionTest` verify without a fixture).
  Compile of generated oneof output is deferred to U4.

### U3 — Codec emission for oneofs (always-emit encode + last-write-wins decode)

- **Goal:** Wire `CodecGenerator` so oneof components encode/decode round-trip with
  last-write-wins; an active member's bytes are just the member's bytes (no oneof
  framing).
- **Requirements:** R8, R9, R10, acceptance examples (round-trip + last-write-wins), K6, K8
- **Dependencies:** U2 (sealed component + wrappers exist)
- **Files:**
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/CodecGenerator.java`
    — `emitRecord`: (a) encode — merge non-oneof `fields()` + `oneofs()` into one
      `(number, emit-lambda)` list, sort ascending by number, emit; wrappers
      unwrap + `writeX`, identity `writeX` directly, **no default guard on the
      oneof path** (K6); (b) decode — add a `case <member.no> ->` per oneof member
      into the `fieldNum` `switch`, overwriting the shared `@Nullable Cap(O)` local
      (wrap for fallback, assign for identity); extend the final
      canonical-construction to pass the oneof-component locals; (c) `emitMap`/
      `emitEnum` unaffected.
  - Extend `OneofEmissionTest` (or a codec sibling) with source-shape asserts for
    the encode/decode oneof paths + the always-emit-at-default behaviour.
- **Approach:** The existing encode loop is already sorted by `field.number()`. Two
  viable emit shapes both round-trip: (a) emit the regular fields in ascending
  number order, then one guarded block per oneof (`if (v.<cap(o)>() != null) <write
  the active member's tag+value>`); or (b) build a single `(number, emit-lambda)`
  list merging fields + oneof components and sort. Shape (a) is simpler; shape (b)
  interleaves oneof tags among regular fields. **Because the interop bridge is off
  for oneof, exact wire ordering is *not* required** (protobuf decoders — including
  recordbuf's — accept fields in any tag order) — so (a) is the default and (b) is a
  cosmetic nicety, not a correctness requirement. Whichever is chosen, each oneof
  decode case overwrites the oneof's local — last tag wins — and the "unknown →
  carrier" fallthrough (R9) is preserved. Keep the "omit no field tags" guard
  (every oneof case writes `<tag><value>`).
- **Test scenarios (source-shape / where compilable):** the decode `switch` has a
  `case` per oneof member, each rewriting the oneof's local; an active oneof's
  encode is guarded by a non-null check with no `== default` skip; a wrapper member
  on decode is wrapped (`local = new Cap(O)<member>(val)`).
- **Verification:** `./gradlew :recordbuf-generator:test` green; end-to-end
  round-trip is U4.

### U4 — Test fixture + round-trip + last-write-wins + cross-file + default-value guard

- **Goal:** A new `oneof.proto` family proves the feature end-to-end
  (record → encode → decode by content), plus a cross-file shared-member fixture and
  a `proto3 optional`-at-default-in-a-oneof guard. Resolves the "test fixture" Open
  Question.
- **Requirements:** all R + acceptance examples; K8 ordering on generated source
- **Dependencies:** U3
- **Files:**
  - `fixtures/src/main/proto/oneof.proto` — `option java_package =
    "bijnagte.recordbuf.fixture.oneof";` **no** `recordbuf.protobuf_java` (interop
    off — recordbuf-only round-trip; `generateProto` auto-discovers it). A message
    `Payload { oneof choice; }` with `string s=1`, `int32 n=2` (wrapper members),
    a generated message `Point` (nested/in-file, `message Point{…}`) and a
    generated `enum Color` as **identity** members, plus a `proto3 optional`
    scalar member exercised at its default. No `-ref`/protobuf-java twin is needed
    (interop is off, so there is no same-simple-name collision to solve — unlike
    the `compat`/`int`-family harness; just the natural `java_package`).
  - `fixtures/src/main/proto/oneof-shared-a.proto` + `oneof-shared-b.proto` — mutual
    imports; a shared `message B` that is a oneof member in **both** files, to
    exercise cross-file `B implements A.Oa, B.Ob`.
  - Regenerated `fixtures/src/main/java/bijnagte/recordbuf/fixture/oneof/…` (via
    `./gradlew :recordbuf-generator:installDist` + the standard `protoc
    --recordbuf_out=…` run, per AGENTS.md). Assert they **compile** — the compile
    gate U2/U3 deferred to.
  - `fixtures/src/test/java/bijnagte/recordbuf/fixture/oneof/OneofRoundTripTest.java`
    — new class (style of `combined/CompatInteropTest` but recordbuf-only, no
    `Interop`/`toByteArray`): each member case active + assert recovered active case
    + value (`OneOfWrapper.value()` for wrappers, identity by content); a
    **last-write-wins** case (hand-crafted wire bytes carrying two tags for one
    oneof group; later tag is the active case after decode); a **default-value**
    case (`proto3 optional` scalar in a oneof at default → active after round-trip);
    a **cross-file** case (`B` as the active case in both shared files round-trips).
- **Approach:** Use `OneOfWrapper.value()` to read a wrapper's active variant
  (R5); assert `null` for an inactive oneof and a non-null wrapper/identity for a
  set one. Follow the harness doc's per-family self-consistency asserts
  (`roundTrip`, plus here LWW + cross-file). No protobuf-java comparison anywhere
  in this unit (non-goal).
- **Test scenarios:** the four `@Test` methods (mixed round-trip, LWW,
  default-value, cross-file).
- **Verification:** `./gradlew build` green — the `oneof`/`oneof-shared-{a,b}`
  fixtures compile **and** `OneofRoundTripTest` passes. This is the integration
  gate that closes U4.

### U5 — Documentation + deferral-comment update

- **Goal:** Record the design + the "oneof interop off / always-emit default" decision,
  and update stale deferral references so repo prose matches the new behavior.
- **Requirements:** R11 (observable, not silent), project-docs convention
- **Dependencies:** U4
- **Files:**
  - `docs/solutions/design-patterns/` — new entry (frontmatter + body mirroring
    `proto-codegen-wire-compat-interop-harness.md`) capturing the cross-file
    `permits`/`implements` pre-pass, the one-component-per-oneof + `@Nullable`
    presence bit, the wrapper fallback, and the "always-emit active member even at
    default; diverges from protobuf-java; interop off" decision, with a "When to
    Apply" note. Ensure discoverability from the solutions index.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/InteropGenerator.java`
    — the deferral comment (~line 73, "{@code oneof} / {@code group} (record layout
    not yet modelled)") drops `oneof` from the *not-modelled* set (it now is,
    and its interop is unsupported/deferred, not absent); `group` + proto2 remain
    deferred.
- **Approach:** Mirror an existing solution note's shape; keep it short and
  decision-led. No build impact beyond "the comment compiles."
- **Verification:** `./gradlew build` green (comment compiles); prose reviewed
  manually (no automated lint).

---

## Verification Contract

- **Repo test command:** `./gradlew build` (full project — compile both modules +
  all tests + assemble; NullAway error-severity in both `:recordbuf-generator` and
  `:recordbuf-runtime`; runs all fixtures incl. the new `oneof` family).
- **Generator test command:** `./gradlew :recordbuf-generator:test` — `OneofLoaderTest`
  (U1), `OneofEmissionTest` (U2 source-shape: one-component, sealed+permits,
  wrapper+`OneOfWrapper`, no-wrapper-for-identity, proto3-optional-not-a-oneof,
  cross-file `B` implements both, R12), and the codec oneof asserts (U3), plus the
  unchanged prior suite (`BuilderEmissionTest`, `RecordHookEmissionTest`,
  `ImplementsClauseEmissionTest`).
- **Fixture / integration command:** `./gradlew :recordbuf-fixtures:test` —
  `OneofRoundTripTest` (mixed wrappers+identity, LWW, cross-file, default-value)
  — the U4 gate.
- **Codegen smoke (manual, per AGENTS.md):** `./gradlew
  :recordbuf-generator:installDist`, then `protoc --plugin=
  protoc-gen-recordbuf=$PWD/generator/build/scripts/protoc-gen-recordbuf
  --recordbuf_out=… oneof.proto`, then confirm the generated Java compiles (the
  compile gate U2/U3 defer to — auto-covered by the fixtures build once the proto
  lands in U4).
- **NullAway gate:** a nullability defect in the new `Oneof`/`OneofRegistry`
  (`@NullMarked`, model) or runtime `OneOfWrapper` fails the build at error
  severity — R11's "mutual exclusion is a type property, never silently broken."
- **Regression guards:** no existing proto has a oneof, so every oneof emitter is a
  no-op for them; the prior suite passes unchanged and `ImplementsClauseEmissionTest`'s
  "no oneof → unchanged `implements`" + `OneofRoundTripTest` pin that oneof work
  touches no non-oneof path (R12).

---

## Definition of Done

**Per-unit:**
- **U1:** `Oneof` + `Message.oneofs` exist; `ModelLoader` groups real oneof members
  and skips synthetics; `OneofLoaderTest` passes; `./gradlew build` green, prior
  tests unchanged.
- **U2:** each oneof emits exactly one `@Nullable <Cap(O)>` component + a nested
  `sealed interface` with a correct cross-file `permits`; wrappers implement
  `OneOfWrapper`; identity subtypes (record *or* enum) `implements <O-interface>`
  via the whole-request pre-pass; `OneofEmissionTest` passes; `OneOfWrapper` exists
  in `:recordbuf-runtime`.
- **U3:** `CodecGenerator` encodes active oneof members (always, even at default,
  merged ascending by number) and decodes per-member with last-write-wins; generator
  tests assert the encode/decode oneof shape.
- **U4:** a `oneof.proto` fixture (wrappers + generated `Point`/`Color` identity
  members incl. a `proto3 optional` default case) + a cross-file shared-member pair,
  regenerated, compiling, with a passing `OneofRoundTripTest` (self round-trip, LWW,
  cross-file, default-value guard).
- **U5:** the cross-file pre-pass + "interop off / always-emit default" design is
  recorded in `docs/solutions`; `InteropGenerator`'s deferral comment reflects that
  `oneof` is now modelled (interop off, `group`/proto2 still deferred).

**Global:**
- `./gradlew build` is green (compile both modules + all tests + assemble, incl.
  `OneofRoundTripTest`).
- Every oneof produces a nested `sealed interface` + one `@Nullable` component +
  a `permits` clause that compiles in the unmodularized classpath (incl. a cross-file
  case).
- A `oneof` round-trips through `Codec.encode`/`decode` by content/equality and
  preserves last-write-wins; a `proto3 optional` scalar-at-default in a oneof is
  recoverable in a recordbuf self round-trip (documented divergence from
  protobuf-java's default omission, acceptable because interop stays off).
- No oneof machinery emits for protos without oneof fields (R12), confirmed by the
  unchanged prior suite + the "no oneof → unchanged `implements`" asserts.
- No new runtime dependency is added; `OneOfWrapper` is the only new runtime type;
  generated output stays dependency-free (`java.*` + `bijnagte.recordbuf.runtime`).
- The feature is purely additive to generated output and only fires when a oneof is
  present; no backwards-compat surface is broken.
- Abandoned-attempt code from exploratory passes is removed; new code passes the
  NullAway error-severity gate in both modules.
