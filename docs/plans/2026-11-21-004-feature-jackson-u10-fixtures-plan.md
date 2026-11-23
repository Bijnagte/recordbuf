# Jackson U10 — fixtures, acceptance, docs — implementation plan

**Date:** 2026-11-21 · **Status:** DONE 2026-11-22 (sequence 4 of 5; U7 oneofs lands after this
unit per the owner's reordering — `2026-11-21-005`; the AE2 oneof round-trip therefore lands with
U7, not here). Verification: `./gradlew build` green; the three suites (12 tests) pass. As built:
(c) `jackson_off.proto` drops the dynamic WKTs and the `jackson_format` knob — a jackson-off file
cannot declare either (both fail generation); the three test protos use a `.rec` `java_package` so
the un-annotated Avro specifics stay out of the `@NullMarked` record package (the test source set
has no `package_suffix`); `JacksonInvarianceTest` checks the surface by reflection over
runtime-retained annotations. The suites surfaced three pre-existing bugs, each fixed in its own
commit first: `repeated string`/`bytes` could not be generated (codec + Avro converter,
`9556453`); `FieldMask`/`Empty` could not round-trip through Jackson (`08ab106`); converter-built
Avro records could not be binary-encoded (null carrier array, `6ca863f`). Retained JSON properties
survive the wire as unknown fields (the fold keys by a name hash), not as JSON properties after a
decode — the test asserts that, and the `RecordSourceMapping` javadoc claiming a decode
"re-seeds" them is corrected. `CONCEPTS.md`: Dynamic WKT (default-ON, `NullValue`), Opaque
envelope (landed as `RecordbufDynamicWkt`), Structural twin (carrier transit is still a TODO).
**Origin:** origin unit U10 of `2026-11-20-001-feature-jackson-json-plan.md` (the integration
gate — 2–3 test-set protos + 2–4 JUnit classes + fixtures deps + CONCEPTS.md), re-sequenced after
U8 → U9 → module/bytes so its shapes are covered.
**Related:** `-001` (U8 temporals), `-002` (U9 envelope), `-003` (module + bytes base64 — lifts
the origin's AE3 bytes caveat: a bare mapper now base64s `Bytes`, so bytes *can* ride round-trip
tests), `-005` (U7 oneofs — supplies AE2).

## Problem frame

U1–U9 leave the surface **unproven end-to-end**: the generator unit tests prove emitted *shape*
(golden text), `:recordbuf-json` unit tests prove *module behavior*, but nothing yet drives a
generated record through a real pinned Jackson mapper, through an Avro codec, and back — the
acceptance table (AE1–AE5) has no executor. This unit builds the fixture protos + JTest classes
that make the origin doc's acceptance table true.

## Current state (verified against the tree)

- Fixture main protos (13): `annotations`, `bytes`, `compat`, `fixed`, `int`, `map`, `message`,
  `oneof`, `optional`, `order`, `packed`, `string`, `wellknown` — **all now jackson-surface under
  default-ON** (the main golden set already carries the U2–U6 + U8/`-003` surfaces). Test-set
  protos: only `iface_marker.proto`.
- `fixtures/build.gradle`: already carries `implementation project(":recordbuf-json")` and
  `:recordbuf-avro` (plus the generated-code `com.fasterxml.jackson.annotation` api edge) — the
  dep-side of the origin's "add jackson-databind + :recordbuf-json (+jspecify) as test deps" is
  mostly landed; **no `:recordbuf-json` test-scope gap exists** (a generated-code consumer of the
  json module types flows through the main source set; if a *test* needs it at test scope, add
  `testImplementation` — verify at implementation, do not add preemptively).
- `CONCEPTS.md` already has the Dynamic-WKT and Opaque-envelope entries (the 3.x migration
  landed them); the Jackson JSON surface entry needs a default-ON accuracy pass.

## Decisions

1. **New test-set protos (3 files), per the origin with the default-ON inversion applied:**
   - **(a) `jackson_full.proto`** — full non-oneof shape: scalar (string/int64/bool/double/bytes),
     `optional` scalar, a nested message field, singular + repeated `Value`/`Struct`/`ListValue`
     dynamic WKTs, `Timestamp` (default format) + `Duration` (default + a field with the 50005
     `"canonical"` knob), a field to exercise unknown-property retention. **No explicit
     `recordbuf.jackson`** — the default-ON surface is the point (one field group may declare an
     explicit `= true` to prove the explicit form parses identically). **No oneof** — oneof JSON
     shape is U7's (`-005`); (a) stays oneof-free.
   - **(b) `jackson_avro.proto`** — the jackson+avro variant: same dynamic-WKT family plus `Any`
     (the envelope shape: all four dynamic WKTs, with `recordbuf.avro`), proving the U9 envelope
     through the generated bridge.
   - **(c) `jackson_off.proto`** — the invariance twin of (a) with an explicit `recordbuf.jackson
     = false` (the post-inversion spelling of the origin's "opt-in file generates nothing"): the
     same shapes, no surface.
2. **Test classes (4, in `fixtures/src/test/java/bijnagte/recordbuf/fixture/`):**
   - `JacksonRoundTripTest` (AE3/AE4, against (a) + the main `wellknown`/`bytes` fixtures):
     record → `new JsonMapper()` → JSON → assert **contract shapes** (ISO temporals, canonical
     under the knob, bytes base64 strings, extras retention: unknown property in ⇒ property out
     with value/type intact) → parse back → **record equals** the input.
   - `JacksonDynamicWktTest` (AE4a/b, against (a) + (b)): node-typed dynamic fields round-trip
     through the mapper as nodes; the `Any` payload round-trips base64; (b)'s envelope values
     carry the `contentType` and survive record → avro → record with the node intact (AE4c).
   - `JacksonInvarianceTest` (AE1/AE5, against (c) + the main golden set): (c)'s generated
     record compiles, round-trips through its **codec** only, and its generated source carries no
     `com.fasterxml` import; the main fixtures' existing codec/golden tests remain green (the
     default-ON surface is additive).
   - *(AE2's `JacksonOneofRoundTripTest` lands in `-005` against the main `oneof.proto` fixture.)*
3. **Docs:** refresh the `CONCEPTS.md` Jackson entry for default-ON (opt-out spelling); no other
   doc changes (the origin plan's "Current state" + R1 annotation already carries the inversion;
   the origin unit boxes themselves are left as a point-in-time record, per the 3c9ffa5
   convention).

## Changes

| File | Change |
|---|---|
| `fixtures/src/test/proto/jackson_full.proto` + `jackson_avro.proto` + `jackson_off.proto` | New, per Decision 1. |
| `fixtures/src/test/java/bijnagte/recordbuf/fixture/` — `JacksonRoundTripTest`, `JacksonDynamicWktTest`, `JacksonInvarianceTest` | New, per Decision 2. |
| `fixtures/build.gradle` | Test-scope dep only if Decision 1's shapes need it (verify first). |
| `CONCEPTS.md` | Jackson entry default-ON pass. |

## Gates & checkpoint

- `./gradlew spotlessApply && ./gradlew build` fully green — **AE5: all three suites green**
  (in addition to the existing binary-interop suites).
- AE1 (invariance) is proven twice: the generator's byte-identity golden (unit) + (c) here
  (fixture-level, no jackson imports, codec-only round-trip).
- Explicit `git add` of exactly the files above; commit message:
  `J (2026-11-20 Jackson plan) U10: the jackson fixture protos and the round-trip/dynamic/invariance acceptance suites`
