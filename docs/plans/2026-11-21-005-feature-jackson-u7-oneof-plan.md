# Jackson U7 — oneof rendering (`@JsonUnwrapped` + discriminator + creator) — implementation plan

**Date:** 2026-11-21 · **Status:** DONE 2026-11-22 (sequence 5 of 5 — the last unit; it closes
the 2026-11-20 Jackson plan). Verification: `./gradlew build` green. The P2 risk flag resolved in
favor of the primary mechanism: a spike against the pinned 3.1.8 confirmed `@JsonUnwrapped` on a
sealed-interface component serializes the runtime wrapper, and that the `@JsonCreator` factory
keeps both the `@JsonAnySetter` sponge and the components' (de)serializer pairs (Jackson merges a
property's annotations across component and factory parameter) — no serializer companion. As
built: the member selection lives in a tested `:recordbuf-json` helper, `JsonOneof.select`, which
the factory calls (smaller generated code than an inline body); the discriminator method is
`<oneof>Type()` and fails generation if its method or JSON name collides with a field or member;
member factory parameters repeat the wrapper's temporal / runtime-value pair. Tests:
`OneofJsonSurfaceTest` (generator, 5), `JsonOneofTest` (json, 4), `JacksonOneofRoundTripTest`
(fixtures, 7, against `oneof.proto` and a new test-set `jackson_oneof.proto` carrying the 50008
override + Timestamp/bytes members, golden JSON under `json/oneof/`). The 50008 guard was already
correct under default-ON and is covered by `JacksonOptionsTest`. Found on the way and fixed first
in `ebddafe`: oneof members that are `bytes` or a well-known type generated code that did not
compile (codec only; byte-compat fixture `oneof_wkt.proto`). Follow-up (same day): the Avro twin
now maps well-known-type and dynamic-WKT oneof members too -- they ride the shared `{tag, value}`
wrapper in their Avro form (a Timestamp as epoch micros, as for a regular field; Duration / Empty /
the JSON envelope as extra union branches) -- so both fixtures run with Avro on.
**Origin:** origin unit U7 + decision P2 of `2026-11-20-001-feature-jackson-json-plan.md`
(flat oneof rendering: `@JsonUnwrapped` + per-member rename + generated discriminator getter +
`@JsonCreator` factory binding the U6 sponge), re-sequenced **last** so U8/U9/module/bytes/U10
land first and the U10 fixture suite already exists for AE2.
**Related:** `-004` (U10: its fixture proto (a) is oneof-free *on purpose*; the main
`oneof.proto` fixture is AE2's material), `-001`/`-003` (a oneof *member* that is a temporal WKT
or `bytes` needs their pair on the wrapper's `value` component — this unit consumes those
landing first).

## Problem frame

The oneof's current JSON behavior (default-ON, no U7) is a **bean dump**: the record component
serializes as `{"choice":{"value":…}}` — the member value under a generic `value` key, the
property name `choice`, no way to distinguish the active member from the shape. The proto3-JSON
contract (AE2) is flat and discriminated: `{"id":7,"a":"blue","choiceType":"a"}` — the active
member's **member name** at the top level plus a discriminator property; unset ⇒ neither appears;
discriminator/member mismatch ⇒ a loud failure. R7: the wire **codec** (the sealed-hierarchy
decode arm, K4/KTD5) is untouched; R10: the structural-twin's avro oneof mapping is untouched.

## Current state (verified against the tree)

- Model: `Oneof(name, iface /* sealed-interface TypeRef nested in the record */, descriptor,
  parentDescriptor, members /* Fields routed out of the flat list */, @Nullable String jsonOneof
  /* the 50008 discriminator-name override, U2 — currently read but unused by the writers */)`.
  `jsonOneof` default = `<oneofName>Type` (e.g. oneof `choice` ⇒ property `choiceType`).
- Emission (`RecordSourceMapping.emitOneof`): a `@Nullable` record component of the sealed
  interface type; the interface is `public sealed ... extends Unwrapable<Object>` with
  `default Object value()`; **per member**: a nested `public record <Cap>Value(<valType> value)`
  implementing the interface (`Choice.AValue(String value)` — for `oneof.proto`'s `Compat`:
  `NameValue`/`CountValue`/`ItemValue`/`ColorValue`); the builder already carries the oneof
  component (null default). The **codec** and **avro twin** already map oneofs (their own
  carriers); both stay untouched.
- **Owner research** (supplied this session): the Baeldung *Jackson Polymorphic Deserialization*
  piece and the `@JsonUnwrapped` javadoc. Conclusion: Jackson's standard polymorphism
  (`@JsonTypeInfo`/`@JsonSubTypes`) cannot express the AE2 shape — it either wraps the value in a
  typed sub-property or nests a type wrapper; the flat "member property at top level + separate
  discriminator" contract needs exactly P2's combination: **`@JsonUnwrapped`** (inlines the
  active member's renamed property) + a **generated creator factory** (reconstructs from
  discriminator + member params).

## Decisions

1. **Serialization side — `@JsonUnwrapped` + per-member rename + null suppression.**
   - The oneof component: `@JsonUnwrapped` **and** `@JsonInclude(NON_NULL)` (an unset oneof must
     contribute nothing — bare `@JsonUnwrapped` on a `null` value still emits the `null`
     property under default inclusion).
   - Each member wrapper record's single `value` component: `@JsonProperty("<memberName>")` — so
     `NameValue` renders `{"name":…}`, and unwrapping inlines it at the record top level.
   - A **generated `<oneofName>Type()` getter** on the record (a plain record method, excluded
     from codec/builder concerns): returns the active member's camelCased proto name or `null`;
     annotated `@JsonInclude(NON_NULL)`; its **property name** is `oneof.jsonOneof()` when set,
     else the default `<oneofName>Type`. The 50008 knob is thereby wired (it was read but
     unused); the loader's existing "knob without the toggle" guard must be re-verified under
     default-ON (a 50008 on an explicit `recordbuf.jackson = false` file must still fail loud —
     the knob implies the surface the file opted out of).
   - **Risk flag (P2's own fallback):** `@JsonUnwrapped` against a *sealed-interface declared
     type* relies on Jackson using the runtime value class for property introspection. The
     pinned-Jackson behavior test (below) settles it in sequence 5; if 3.1.8 misbehaves, the
     **serialization side** switches to a generated serializer companion for the oneof — the
     emitted JSON shape must be **byte-identical** between the two mechanisms (P2 requirement).
     (The deserialization factory is unaffected either way.)
2. **Deserialization side — a generated `@JsonCreator` static factory** on any record that has
   one or more oneofs: parameters = every non-oneof field (by Jackson property name) + one
   `@Nullable` parameter per oneof **member name** + one `@Nullable` parameter per oneof
   **discriminator** + one `@JsonAnySetter Map<String, JsonNode>` sponge (bound to the U6
   `jsonExtras` component — unknown properties ride AE3). Body per oneof: discriminator present
   ⇒ the named member param must be present and all other member params absent (a mismatch —
   incl. an unknown discriminator name — throws a clear Jackson error, AE2); discriminator
   absent ⇒ exactly one member param present binds it, none present ⇒ the oneof stays `null`.
   The factory delegates to the existing canonical constructor (the compact ctor, builder, and
   codec keep their current paths — the factory is an **additional** static member).
3. **Member-level annotation interactions** (consumes `-001`/`-003` landing first): a wrapper's
   `value` component also gets the unit-specific pair when the member's type demands it —
   temporal WKT member ⇒ the U8 `JsonTemporal` pair (50005 knob applies to oneof members: the
   loader's existing per-field 50005 validation already routes members through it), `bytes`
   member ⇒ the `-003` `JsonBytes` pair. A **dynamic-WKT member** (`Value`/`Struct`/`ListValue`
   in a jackson file; `Any`/`NullValue` in jackson+avro) is node-typed and rides the `value`
   component as-is (no pair, `contentType` not applicable on the record side).
4. **Untouched (invariants):** codec oneof arm (R7), the structural twin's avro oneof mapping,
   the builder (component already null-defaulted), jackson-off files byte-identical, the main
   `oneof.proto` fixture's *existing* binary tests.

## Changes

| File | Change |
|---|---|
| `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java` | `emitOneof` gains the Jackson surface: component `@JsonUnwrapped`+`@JsonInclude(NON_NULL)`; per-wrapper `@JsonProperty(memberName)` (+ the unit pair when the member type demands it); the record gains the `<oneofName>Type()` getter (50008-aware) and, when the record has ≥1 oneof, the `@JsonCreator` static factory (fields + member params + discriminator params + the U6 sponge) delegating to the canonical ctor. Gated on `jackson` like the other surfaces. |
| `generator/src/main/java/bijnagte/recordbuf/plugin/loader/…` | Re-verify (and, if stale, fix) the 50008 "knob without toggle" guard under default-ON semantics. |
| Generator tests | Shape: a 4-member oneof (string/int32/enum/message) renders the component annotations, the per-wrapper renames, the discriminator getter (default name **and** a 50008 override), and the creator factory param list incl. the sponge; a oneof member that is a `Timestamp`/`bytes` carries the U8/`-003` pair on its `value`; `recordbuf.jackson = false` twin byte-identical; 50008 on a jackson-off file fails loud. Behavior (pinned Jackson, via the existing `assertCompilesWithRuntime` pattern): AE2 shapes — set member `{"name":"blue","choiceType":"name"}` round-trips; unset ⇒ neither property; mismatch (discriminator `name` + `count` present) ⇒ loud error. |
| `fixtures/src/test/java/.../JacksonOneofRoundTripTest` | AE2 end-to-end against the main `oneof.proto` fixture (the `Compat` record: scalar/enum/message members; with and without a 50008 override if the main fixture can't carry one, a test-set oneof proto does). |

## Gates & checkpoint

- `./gradlew spotlessApply && ./gradlew build` fully green, incl. the new AE2 suite.
- Explicit `git add` of exactly the files above; commit message:
  `J (2026-11-20 Jackson plan) U7: oneofs render flat per the proto3-JSON contract (unwrapped member property + discriminator + creator factory) — the Jackson plan is now complete`
- **Closing step:** update the origin plan doc's "Current state"/unit status to record U7
  complete (all units landed: U1–U6, U8, U9, module/bytes, U10, U7) and mark the plan DONE.
