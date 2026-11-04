---
title: Oneof Interop Support - Plan
type: feat
date: 2026-11-03
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
execution: code
deepened: 2026-11-03

# Status: NOT STARTED
---

# Oneof Interop Support - Plan

## Goal Capsule

**Objective:** Make the `recordbuf.protobuf_java` interop bridge (Layer B
structural converter, `generator/.../writer/InteropGenerator.java`) actually
convert `oneof` members in both directions, closing the deferral the oneof
sealed-hierarchy plan (`docs/plans/2026-11-03-002`) recorded. Today the bridge
*stubs* oneofs — `Interop.from(X)` emits `Choice _choice = null;` and
`Interop.to(R)` omits them — so byte-compat for oneofs is proven only by
hand-built protobuf-java references, not *through* the converter. After this
work, `Interop.to(rec).toByteArray()` equals the record's own codec bytes and
`Interop.from(ref)` recovers the record, for every oneof member family.

**Product authority:** recordbuf owner. The change is additive to the existing
interop bridge: it must not alter the codec wire contract, the record shape, or
the `Interop` surface for non-oneof fields. Generated output for a message that
opts in via `(recordbuf.protobuf_java) = "…"` gains *oneof-aware* `from`/`to`;
everything else is unchanged.

**Open blockers:** None. The oneof record model (`Oneof`, `Message.oneofs()`,
the sealed-hierarchy `OneofGenerator`) already exists and is shipped; the oneof
fixture (`fixtures/src/main/proto/oneof.proto`) already opts in, so the stub
`Interop` type is already generated. All design forks are resolved in the
Planning Contract below.

---

## Product Contract

### Problem frame

The interop bridge and the codec were built at different times against the same
`.proto`, but oneof handling diverged:

- The **codec** (`generator/.../writer/CodecGenerator.java`) fully supports
  oneofs: encode/decode switch on each oneof member's tag, recovering a
  `@Nullable` nested sealed-interface component per oneof.
- The **interop converter** (`generator/.../writer/InteropGenerator.java`)
  deliberately *stopped* at oneofs: its Javadoc names oneof handling as
  "documented as future work," `emitFrom` writes a `null` oneof local with the
  comment `// oneof not yet supported in Interop`, and `emitTo` has no
  oneof block at all.

The consequence: the bridge is a no-op for oneof data. A record whose active
oneof member carries real data converts to a protobuf-java reference with the
oneof empty, so `Interop.to(rec).toByteArray()` is *not* byte-identical to
`rec.toByteArray()` whenever a oneof is active — the core Layer B invariant is
violated precisely for the oneof family.

The fix is a bounded one because oneof members are **singular only** (proto
forbids repeated/map members), so the converter's per-member matrix is strictly
smaller than the full field matrix the converter already handles.

### Requirements

- **R1. `Interop.from(X)` maps the active oneof case to the record's
  wrapper/identity subtype** for every singular member family the record sealed
  hierarchy already emits — scalar (incl. boxed/nullable), `bytes`, `enum`
  (by number), and singular `message` (recursed through the sibling record's own
  `Interop.from`). An absent oneof (`CHOICE_NOT_SET`) recovers as `null` on the
  record's `@Nullable` component.
- **R2. `Interop.to(R)` maps the record's oneof component back to the
  protobuf-java builder** — each permitted subtype sets the corresponding
  protobuf-java oneof member via its `setX`; a `null` component sets nothing
  (proto3 omits when absent).
- **R3. Byte-identity invariant preserved through the converter:** for a fully
  or partially populated record,
  `Interop.to(rec).toByteArray() == rec.toByteArray()` and
  `Interop.from(ref) == rec` (content equality), for a reference built by hand
  from the same proto — the same orange the rest of the interop bridge uses.
- **R4. Full member family, no arbitrary gaps.** v1 supports the complete
  singular family (scalar + bytes + enum + message). An `enum` oneof member and
  an enum type added to the oneof fixture are in scope so the family is locked,
  not merely hand-verified for the string/int cases the fixture happens to
  exercise today.
- **R5. Output purity unchanged.** Generated oneof-aware `Interop` references
  the protobuf-java reference types by FQN (as today) and recurses message
  members through their sibling `Interop`; the record and codec are untouched.
- **R6. NullAway / JSpecify clean on the new generated code.** The `from` oneof
  local is `@Nullable`-assigned; no new nullability holes are introduced in the
  generated output.

### Scope Boundaries

#### In scope
- `InteropGenerator.emitFrom` / `emitTo`: oneof-aware, both directions, full
  singular family.
- Factoring the per-family scalar/bytes/enum/message **from-expression** and
  **builder-set-statement** out of the existing singular-field logic so the
  oneof path reuses them rather than re-implementing (avoids a second copy of
  the scalar/bytes/enum/message rules).
- `fixtures/src/main/proto/oneof.proto`: add an enum type and an `enum` oneof
  member so the enum family is exercised end-to-end through the converter;
  regenerate fixtures.
- Behavioral tests folded into the existing `OneofRoundTripTest` (Layer B,
  oneof family) — see Unit 3.

#### Deferred to Follow-Up
- **Unknown-field preservation through the bridge** — carries forward the
  existing interop-bridge deferral (that plan's R10 / "Risks / Deferred").
  `Interop.from` leaves the record's `UnknownFields` carrier empty as today;
  `to` does not merge a record's captured unknown fields back onto `X`. A oneof
  cannot carry a wire-unknown field number anyway, so this is not a oneof-specific
  gap.
- **Proto2 / `oneof` semantics and `group {}`** — proto3 only, matching the
  whole interop harness and the codec.
- **Nested oneof on a nested message that opts in on its own** — the message-member
  recursion already composes through the sibling `Interop`, but a oneof declared
  *inside* a nested message that independently opts in is a carry-variant like the
  bridge's existing same-file-closure limitation.
- **Builder-driven converter variant** and **gRPC adapter** — carry forvards from
  the interop-bridge plan; untouched here.

### Explicit Non-Goals
- No change to the `Codec` / `Encoder` / `Decoder` / `Wire` / `UnknownFields`
  wire contract, the record shape, the builder, or the `Oneof` model.
- No new protobuf option; the existing `(recordbuf.protobuf_java)` opt-in is the
  only trigger.
- No repeated/map oneof handling — impossible in proto.
- No new runtime dependency or new generated type on the record itself.

---

## Key Decisions

- **`from` switches on `get<XxxCase()`; `to` pattern-matches the sealed
  component.** This mirrors the two sides' own idioms: protobuf-java *exposes*
  a oneof through a `getXxxCase()` discriminator + per-member `getX`/`hasX`, so
  `from` switches on that discriminator; the record *is* a sealed interface, so
  `to` pattern-matches it exactly the way the `Codec` already does
  (`case null -> {}` then one case per permitted subtype). Per-member `hasX()`
  guards were considered and rejected: a `get<XxxCase>()` switch is exhaustive
  in one place and matches protobuf-java's own representation.
- **Reuse the singular-field rules, parameterized by the source expression.**
  The oneof families are the existing scalar/bytes/enum/message families verbatim,
  so the converter should factor a `fromExpr` (RHS-only) and a `toSet`
  (builder-set, parameterized by the source expression) out of `emitFrom`'s /
  `emitTo`'s current singular logic. For `from`, the source is
  `x.getCap()`; for a oneof `to`, the source is `v.value()` for a scalar/enum
  `XxxValue` wrapper or the message value `v` itself. This keeps one
  authoritative copy of each family's rule.
- **Naming derives from the existing model, no new model fields.** Member
  getter/setter/has reuse `cap(f.javaName())`; the oneof case getter is
  `get` + `cap(oneof.name())` + `Case`; per-member case constants are
  `snakeUpper(f.name())` (`name`→`NAME`, `count`→`COUNT`, `item`→`ITEM`,
  `color`→`COLOR`); the record-side wrapper for a non-message member is
  `<iface>.<cap(f.name())>Value`; message/enum member siblings and the ref enum
  resolve by `recordSiblingFqn(f)` / `refSiblingFqn(refPkg, f)` as the existing
  converter already does. No change to `Oneof` / `Message` / `Field`.
- **v1 covers the full singular family including `enum`.** Chosen over the
  "string/int + message only" alternative so the family has no arbitrary gap;
  the enum gap is closed by adding a single enum oneof member to the fixture.
- **Tests fold INTO the existing `OneofRoundTripTest` — no new class.** Per
  direction, Layer A (record ↔ bytes, the class's "Layer A") and Layer B
  (record ↔ proto, the class's "byte-compat" section) are both oneof byte-
  compatibility, so a single converter-aware class covers both without a new
  file. Each family's `*IsByteCompatibleWithProtobufJava` test drives the
  generated `rec.Compat.Interop` in both directions via a shared
  `assertConvertsByteIdentically(tag, record, ref)` helper; a new
  `enumMemberIsByteCompatibleAndRecoversKnownConstant` covers the 4th family +
  the known-value mapping; `absentOneofIsNull` gains the zero-byte ⟷ `null`
  checks. (Originally the plan specified a separate `OneofInteropTest`; the
  user directed folding in instead.)

---

## Assumptions

- **A1 — Case-constant naming is `UPPER(protoName)`.** protobuf-java names the
  `<Xxx>Case` enum constants from each member's proto field name uppercased (and
  the not-set constant `<ONEOF_UPPER>_NOT_SET`). The fixture keeps all oneof
  member names single-word (`name`, `count`, `item`, and the new `color` or
  similar), so `snakeUpper(name)` is trivially the constant; multi-word behavior
  is noted as a verify point, not relied on in v1.
- **A2 — Reference protobuf-java types already exist for the fixture.** The
  fixture's `protoc --java_out` output (`fixtures/build/.../oneof/Compat.java`)
  is regenerated alongside the record via `generateProto`, and the record opts in
  via `(recordbuf.protobuf_java)` already.
- **A3 — Reused field logic is singular-only.** The factored `toSet`/`fromExpr`
  handle only singular fields; oneof members cannot be repeated/map, so no
  repeated/map path is needed on the oneof route.
- **A4 — Generated-code nullability is advisory, not build-enforced.** Per
  `AGENTS.md`, NullAway is wired only into the generator/runtime modules over
  `@NullMarked` source; generated output (the fixture `Interop`) is not
  NullAway-checked. The `@Nullable` local is still emitted for cleanliness.

---

## Open Questions

None blocking. (Case-constant naming for multi-word oneof members is a verify
point in A1, not a blocker; v1 keeps names single-word.)

---

# Planning Contract

**Status:** implementation-ready. Resolves the design forks; Product Contract
unchanged. The two directional skeletons below are **guidance, not
implementation spec** — they fix the shape and the reuse boundary, not the exact
Java. All paths are repo-relative.

## Directional codegen

Per `Oneof o` of message `m` (component = `o.componentName()`, the `@Nullable`
record component), with its nested sealed interface `iface = o.ifaceName()`.

### `from` — replace the `null` oneof local with a `get<XxxCase>()` switch

For the local, assign an `@Nullable` `iface` from `x.get<Cap(o.name())>Case()`:

```
// directional only
@Nullable <iface> _<o.componentName()> = switch (x.get<Cap(o.name())>Case()) {
    // per NON-message member f of o:
    case <snakeUpper(f.name())> -> new <iface>.<Cap(f.name())>Value(<fromScalar(f)>);
    //   <fromScalar(f)>: scalar -> x.get<Cap(f.javaName())>(...) ;
    //   bytes   -> x.get<Cap>(f.javaName)().toByteArray() ;
    //   enum    -> <recEnum>.forNumber(x.get<Cap(f.javaName())>().getNumber());
    // per MESSAGE member f of o (identity subtype):
    case <snakeUpper(f.name())> -> <recSibling>.Interop.from(x.get<Cap(f.javaName())>());
    default -> null;   // CHOICE_NOT_SET
};
```

The constructor call already passes the oneof locals after the fields (existing
`emitFrom`), so only the *local declaration* changes from `= null; // ...` to the
switch above.

### `to` — after the existing field loop, add a per-oneof pattern switch

For the builder `_b`, after the `m.fields()` loop, per oneof:

```
// directional only
switch (r.<o.componentName()>()) {
    case null                       -> {}
    // per NON-message member f:  <iface>.<Cap(f.name())>Value v -> <toSet(f, v.value())>
    //   <toSet> reuses the singular-set families:
    //     scalar -> _b.set<Cap(f.javaName())>(v.value()) ;
    //     bytes  -> _b.set<Cap(f.javaName())>(<BYTE_STRING>.copyFrom(v.value())) ;
    //     enum   -> _b.set<Cap(f.javaName())>(<refEnum>.forNumber(v.value().getNumber())) ;
    // per MESSAGE member f:  <recSibling> v -> _b.set<Cap(f.javaName())>(<recSibling>.Interop.to(v));
}
```

Exhaustiveness: `iface` is sealed and permits exactly these subtypes, so the
switch is exhaustive (`case null` + one arm per permitted); the `Codec` already
uses this exact pattern, so it compiles.

### Reuse boundary

Factor the per-family **from RHS** out of `fromLocal` (currently declares a local
var) into a `fromExpr(Field f, ...)` returning the RHS string, and factor the
per-family **builder set statement** out of `toStmt` (currently reads
`r.<javaName>()` as the source) into a `toSet(Field f, <source-expr>, ...)`.
Singular fields call `toSet(f, "r.<javaName>()", ...)`; oneof scalar/enum wrappers
call `toSet(f, "v.value()", ...)`; oneof message members delegate via
`<recSibling>.Interop.to(v)`. No duplicated scalar/bytes/enum/message rule.

## Technical decisions

- **TD1 — `from` via `get<XxxCase>()`; `to` via sealed pattern switch.** Per Key
  Decisions. Rejects per-member `hasX()` guards.
- **TD2 — Parameterized reuse** of the singular families (`fromExpr` / `toSet`).
  Prevents a second, drift-prone copy of the scalar/bytes/enum/message rules.
- **TD3 — No model changes.** `Oneof` / `Message` / `Field` are used as-is;
  naming derives from `oneof.name()`, `f.name()`, `f.javaName()`,
  `f.typeUrl()` via `JavaNames`.
- **TD4 — Nullability of the `from` local.** Emit `@Nullable <iface> _<component>`
  so the possibly-null switch result (the `default`/`CHOICE_NOT_SET` arm) assigns
  cleanly and generated output stays nullability-clean.
- **TD5 — Fixture: add the enum family.** Add one enum type plus one `enum`
  oneof member to `oneof.proto` (single-word name so A1 holds) with
  `(recordbuf.protobuf_java)` opt-in on the owner message (already set). Regenerate.

## Naming reference (all derived, no new fields)

| thing | source | e.g. |
|---|---|---|
| oneof record component | `o.componentName()` | `choice` |
| nested sealed iface | `o.ifaceName()` | `Choice` |
| protobuf-java case getter | `get` + `cap(o.name())` + `Case` | `getChoiceCase()` |
| per-member case constant | `snakeUpper(f.name())` | `NAME`, `COUNT`, `ITEM`, `COLOR` |
| not-set constant | `snakeUpper(o.name()) + "_NOT_SET"` | `CHOICE_NOT_SET` (covered by `default`) |
| member get/set/has | `cap(f.javaName())` | `getName`, `setName` |
| non-message wrapper | `<iface>.` + `cap(f.name())` + `Value` | `Choice.NameValue` |
| message/enum member record sibling | `recordSiblingFqn(f)` | `Sub` |
| ref enum | `refSiblingFqn(refPkg, f)` | `<refPkg>.Color` |

---

## Implementation Units

### Unit 1 — `InteropGenerator`: oneof-aware `from`/`to` + reuse factorization

- **Files:** `generator/src/main/java/bijnagte/recordbuf/plugin/writer/InteropGenerator.java`.
- **Change:**
  - Replace the oneof-stub branch in `emitFrom` (the `Choice _choice = null; //
    oneof not yet supported` loop) with a per-oneof `get<XxxCase>()` switch
    producing the `@Nullable` wrapper/identity local, `default -> null`.
  - After the field loop in `emitTo`, add the per-oneof sealed pattern switch.
  - Factor `fromExpr(Field, …)` out of `fromLocal` and `toSet(Field,
    <source-expr>, …)` out of `toStmt`; singular fields and oneof arms both go
    through them.
  - Remove the "oneof not yet supported" language from the class/field Javadoc;
    replace the "Not covered … `oneof` members" Javadoc block with the new
    supported shape (scalar / bytes / enum / message, both directions; absent →
    null).
- **Test scenarios (generator emission + behavioral via regenerated fixture —
  the behavioral lock lives in Unit 3; a light generator-side emission check is
  acceptable if the project keeps one, matching `RecordHookEmissionTest` style):**
  - For `oneof.proto`'s `Compat`, generated `Interop.from` contains a
    `getChoiceCase()` switch and each member's wrapper/`Interop.from`; generated
    `Interop.to` contains a `switch (r.choice())` with a `null` arm, scalar/enum
    `setX(v.value())`/`setX(RefEnum.forNumber(v.value().getNumber()))`, and a
    message `setX(Sub.Interop.to(v))` arm.
  - A message *without* oneofs (e.g. `combined.Compat`) generates byte-for-byte
    the same `Interop` as before this change (no incidental churn from the
    refactor).

### Unit 2 — Fixture: add the `enum` oneof family + regenerate

- **Files:** `fixtures/src/main/proto/oneof.proto`.
- **Change:** add one enum type (e.g. `enum Color { UNSET = 0; RED = 1; BLUE = 2; }`)
  and one `enum` member in `choice` (single-word name, e.g. `color = 4`), keeping
  `option (.recordbuf.protobuf_java) = "…"` on the owner. No record-side behavior
  change — the sealed hierarchy already emits a `ColorValue(Color value)`
  wrapper and the codec already handles enum members.
- **Regenerate:** `./gradlew :recordbuf-fixtures:generateProto` (and the
  protobuf-java reference in the same task via the `java` builtin), then confirm
  the regenerated `Interop` compiles.
- **Test scenarios:** regenerated `oneof/Compat.java` (record) and
  `oneof/Compat.java` (protobuf-java ref) both compile; the record `Choice`
  now permits `ColorValue` and the ref now has a `getColor()`/`Color` accessor.

### Unit 3 — Interop tests folded into `OneofRoundTripTest` (Layer B, oneof family)

- **Files:** `fixtures/src/test/java/bijnagte/recordbuf/fixture/oneof/OneofRoundTripTest.java`
  (edited, NOT a new class — per direction the interop checks fold into the
  existing oneof test class whose "byte-compat" section already compares the
  record codec to a hand-built protobuf-java ref). Reference the record types by
  FQN (`bijnagte.recordbuf.fixture.oneof.rec.*`) and the protobuf-java refs by
  short name, mirroring `combined/CompatInteropTest.java`. A shared
  `assertConvertsByteIdentically(tag, record, ref)` helper drives `to(record)` and
  `from(ref)`; a new `enumMemberIsByteCompatibleAndRecoversKnownConstant`
  covers the 4th family + the known-value mapping; `absentOneofIsNull` gains the
  zero-byte ⟷ `null` checks. No new test class was created.
- **Test scenarios (the orange, per R3):**
  - **Each family byte-identical through the converter, vs a hand-built ref:**
    for `name` (string), `count` (int, incl. a zero-valued *active* member),
    `color` (enum), and `item` (singular message), assert
    `Interop.to(rec).toByteArray()` `assertArrayEquals` the hand-built
    protobuf-java reference's `toByteArray()`.
  - **`from` recovers the record:** for each active member,
    `Interop.from(ref)` equals the source record (content equality); the active
    member is the right permitted subtype with the right value.
  - **Round-trip identity:** record → `Interop.to` → `Interop.from` equals the
    original record, for each family.
  - **Absent oneof:** an all-default record converts to a reference that
    serializes to zero bytes and `Interop.from` recovers the record with a
    `null` oneof component.
  - **Enum by number:** an enum member with a *recognized* number round-trips;
    (optionally) an unrecognized number on the ref recovers via the record enum's
    `forNumber` default — surfaced, not a sentinel, matching the bridge's
    documented `UNRECOGNIZED` asymmetry.
  - **Active zero-valued scalar:** `count = 0` set in the oneof still round-trips
    byte-identically (the codec writes it because the case is active;
    protobuf-java agrees).

### Unit 4 — Verification

- `./gradlew build` green (compile + `:recordbuf-generator:test`,
  `:recordbuf-runtime:test`, `:recordbuf-fixtures:test`).
- Because codegen *is* touched: run `./gradlew :recordbuf-generator:installDist`,
  regenerate the fixtures (`:recordbuf-fixtures:generateProto`), and confirm the
  regenerated oneof `Interop` compiles and the folded-in `OneofRoundTripTest`
  interop tests pass — i.e. a from-repo-clean end-to-end, not only the committed
  generated snapshot.
- Spot-check that non-oneof `Interop` output (e.g. `combined`) is unchanged
  (Unit 1's no-churn scenario) so the factorization did not perturb existing
  families.

## Dependencies / Sequencing

1. **Unit 1** (generator `from`/`to` + reuse) — standalone; lands first and can
   compile against the current fixture (string/int/message; the `default` arm
   means no compile break if an enum member is not yet present).
2. **Unit 2** (fixture enum member + regenerate) — depends on 1's codegen being
   correct for enum members; regenerate and compile.
3. **Unit 3** (interop tests folded into `OneofRoundTripTest`) — depends on 2
   (needs the enum family in the
   regenerated fixture) and 1.
4. **Unit 4** (verify) — after all three; the `installDist` + regenerate +
   `build` gate.

`./gradlew build` after Unit 1, after Unit 2, and after Unit 3; the full
clean-regenerate gate is Unit 4.

## Risks / Deferred / Follow-on

- **Multi-word oneof case constants** — A1 assumes `snakeUpper(f.name())` matches
  protobuf-java for single-word names (v1 keeps names single-word). If a member
  name has `_`, verify the generated constant matches protobuf-java's `UPPER` of
  the proto name; carry as a verify point, not a v1 dependency.
- **Zero-valued active scalar** — a `count = 0` that is the *active* case must
  still write; both sides do, but it is the easiest to get subtly wrong, so it is
  an explicit interop scenario in `OneofRoundTripTest`.
- **Unknown-field preservation through the bridge** — deferred (existing interop
  bridge R10); `from` keeps the carrier empty and `to` does not merge it. Not
  oneof-specific.
- **Nested oneof on an independently opted-in nested message** — deferred,
  mirrors the bridge's same-file-closure limitation; message-member recursion
  already composes for the fixture shape.
- **Builder-driven converter variant / gRPC adapter** — carry-forwards from the
  interop-bridge plan; untouched.
