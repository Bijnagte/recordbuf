---
title: Oneof Sealed Hierarchy - Plan
type: feat
date: 2026-11-03
topic: oneof-sealed-hierarchy
artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
execution: code
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
- **Execution profile:** Code — model extension (a new `Oneof` model record in
  `Message`), a new cross-file aggregation pass feeding into
  `CodeGenerator.generate()`, changes to the record/codec/builder emitters in
  `CodeGenerator`, and one new runtime interface for wrapper accessors. One new
  fixture proto and a round-trip test.
- **Status:** Requirements are settled; ready for `ce-plan` to enrich with units,
  sequencing, and file-level detail. Wire-shape pin for a `proto3 optional`
  scalar set-to-default inside a oneof is flagged as a planning-time comparison
  against protobuf-java.

---

## Product Contract

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
