# Jackson U8 — temporal fields: ISO default + `canonical` knob — implementation plan

**Date:** 2026-11-21 · **Status:** DONE 2026-11-22 (sequence 1 of 5; U7 oneofs is deliberately
last — `2026-11-21-005`). Verification: `./gradlew build` and `./gradlew test --rerun-tasks`
green. The fixture test lives on the `jackson_format` proto (`JacksonFormatTest`, the three
renderings); the native-WKT `wellknown` fixture (`Sample` / `WktInteropTest`) is the U8-adjacent
WKT family matrix.
**Origin:** unit U8 of `2026-11-20-001-feature-jackson-json-plan.md` (R8, P4, §11 canonical-format
risk) + the 3.x handoff's remaining item 3 ("the 50005 `jackson_format` knob loads into the model
but is unused; the generator never emits `@JsonSerialize`/`@JsonDeserialize`").
**Related:** `2026-11-07-001` native WKT (the java.time mapping this unit rides), the sibling plans
`2026-11-21-002` (U9 envelope), `-003` (module + bytes), `-004` (U10 fixtures/acceptance), `-005`
(U7 oneofs).

## Problem frame

`recordbuf.jackson` is default-ON, so **every** generated record already carries the Jackson
bean surface. A `google.protobuf.Timestamp`/`Duration` field maps (per the native-WKT registry)
to `java.time.Instant`/`java.time.Duration` components. With a bare Jackson 3.x mapper those
components serialize as *beans* (or via the 3.x built-in java.time handlers — unverified for the
pinned 3.1.8) — but the 50005 `jackson_format` knob the plan promised (R8: ISO default,
`canonical` selectable per field) is loaded, validated (`ModelLoader` fails loud when set on a
jackson-off file) and then **never consumed**. U8 closes the loop.

## Current state (verified against the tree)

- `Field.jacksonFormat` (`@Nullable JacksonTemporalFormat`, plugin.model) is populated from the
  50005 tag / `recordbuf.jackson_format` name by `ModelLoader.jacksonFormatFor`; invalid values
  fail at load. Nothing downstream reads it.
- `:recordbuf-json` `JsonTemporal` already contains **eight** public 3.x `Value*`
  (de)serializer classes — `Timestamp{Iso,Canonical}{Serializer,Deserializer}` and
  `Duration{...}` — **operating on `com.google.protobuf.Timestamp`/`Duration` messages** — plus a
  `Coords forType(String wktFqn, JacksonTemporalFormat)` resolver. None of it is referenced by
  generated code (the generator never imports `JsonTemporal`); only `JsonModuleTest` exercises it.
  The class's own javadoc already describes the intended U8 emission ("a generated temporal field
  pairs its accessor with a Jackson (de)serializer class from here, selected by the … format
  knob").
- The WKT registry maps `Timestamp → java.time.Instant` (`TypeRefs.INSTANT`, zero-default
  `Instant.EPOCH`) and `Duration → java.time.Duration` (`TypeRefs.DURATION`, `Duration.ZERO`).
  The codec arms (`WktSourceMapping` → `Wkt.parseTimestamp` etc.) convert `Instant`/`Duration` ↔
  wire and are **untouched by this unit** (R7).
- The generator's `RecordSourceMapping` hooks a component's Jackson annotations in one place —
  `jacksonAnnotation(Component.Builder, Field, boolean)` (after `@JsonProperty` attachment,
  ahead of `@Nullable`) — in both `toSource` and `toSourceWithCodec` loops.
- The catalog pins Jackson 3.1.8 (`tools.jackson` BOM) + the 2.21 `com.fasterxml.jackson`
  annotations jar; databind 3.x reads the 2.21 annotation package and instantiates `using =`
  classes against its 3.x `Value*` bases. `JsonTemporal`'s classes extend those bases and carry
  no-arg constructors.

## Decisions

1. **Re-seat `JsonTemporal` on `java.time` — replace, don't duplicate.** The field mapping is
   `java.time.Instant`/`java.time.Duration` (registry), so a serializer `handledType()`-ed to
   `com.google.protobuf.Timestamp` can never bind to a generated field. The eight
   message-typed classes and `forType` have zero production references (module tests only), so
   they are **replaced** by eight java.time-typed classes with identical class names and shape:
   `Instant{Iso,Canonical}{Serializer,Deserializer}`, `Duration{Iso,Canonical}{Serializer,Deserializer}`,
   each `extends ValueSerializer<T>`/`ValueDeserializer<T>` with a no-arg constructor.
   (`forType`/`Coords` are deleted — the generator selects the class statically at generation
   time; if the rework leaves the `api(project(":recordbuf-codegen"))` dep in `json/build.gradle.kts`
   otherwise unused, remove it.)
   - **ISO (the `"json"` format, the default) — keep the U3 renderings, re-based on java.time:**
     `Instant` → RFC 3339, `Z`, 9-digit zero-padded fraction when nanos ≠ 0 (the current
     `toIso`; still proto3-valid); `Duration` → `java.time.Duration.toString()` (`PT…` form —
     the current `toIso`).
   - **Canonical (the `"canonical"` format) — the exact proto3-JSON spec** (origin §11:
     "3/6/9-group fractional, `Z`, `Ns` for Duration"; the origin U3 line 250: "canonical =
     3/6/9-digit fractional + `Z`, Duration `Ns` text"). **Correction vs the current U3
     implementation, which renders canonical as a `"{seconds,nanos}"` object — a deviation from
     the origin; replaced.** `Instant` → RFC 3339 `Z` with the fraction in the **smallest
     sufficient 3/6/9-digit group** (proto3's tightened serialization); `Duration` →
     `"Ns"` text (e.g. `1.5s`, `-1.5s`), fraction 0/3/6/9-group, **floor sign convention**
     (sign on the seconds part — java.time's truncation `getSeconds`/`getNano` must go through
     `floorDiv`/`floorMod`, the same pitfall the avro WKT-`Duration` pair in AGENTS.md warns
     about).
   - **Both deserializers accept both renderings** (both formats are strings; a value written
     with either parser reads back under either deserializer) — the current U3 object-acceptance
     is dropped with the object form. The readers normalize through
     `Instant.ofEpochSecond`/`Instant.parse` and the `Ns`/`PT` parse rules as appropriate.
2. **Format routing — the 3.x built-in is verified, and the explicit pair is emitted for BOTH
   formats.** The 3.x line merged jsr310 into `jackson-databind` core, so a verification step in
   `:recordbuf-json` first pins what the pinned 3.1.8 does with a bare `new JsonMapper()`:
   `Instant` renders via `Instant.toString()` — 3/6/9-group fraction + `Z` (**matches the
   canonical proto3-JSON form**, but not ISO's 9-digit padding, and `Instant.parse` rejects
   `±HH:MM:SS` offsets the proto3 spec accepts); `Duration` renders via `Duration.toString()`
   (`PT…` — **not** the `Ns` proto3 form, and parse is `PT`-only). So the built-in can stand in
   for neither format (and steer 4's "no custom codecs for types we don't own" is satisfied in
   the only place it helps: **nothing is registered in the module for these types** — the pair
   classes are instantiated solely through the generated per-field annotations, and a bare mapper
   with an unannotated `Instant`/`Duration` bean is out of the generated contract's scope). The
   built-in behavior is recorded in `:recordbuf-json` tests for the record; emission is always
   the explicit pair.
3. **Emission site:** `RecordSourceMapping` gains a `temporalAnnotations(Component.Builder,
   Field, boolean jackson)` sibling called next to `jacksonAnnotation` in both component loops.
   It fires when `WellKnownTypes.resolve(f.typeUrl())` is the `Timestamp`/`Duration` entry
   (resolve via the registry, don't string-match in the writer) and `jackson` is true:
   - singular → `@JsonSerialize(using = <Pair.class>)` + `@JsonDeserialize(using = <Pair.class>)`
   - `repeated` (`List<Instant>`/`List<Duration>`) → the same pair on **`contentUsing =`**
     instead (element-wise; a `using =` on the list property would bind to the list itself)
   - the pair class = `f.jacksonFormat() == JacksonTemporalFormat.CANONICAL ? *Canonical* : *Iso*`
     (`null` knob ⇒ ISO). FQNs: `bijnagte.recordbuf.json.JsonTemporal.<NestedClass>` — the
     renderer imports them; generated code thereby depends on `:recordbuf-json` on a jackson
     file (already accepted by the origin plan's R2; the fixtures already depend on the module).
   - **Out of scope here:** oneof *members* that are temporal WKTs (U7, plan `-005`, owns the
     member-emission path and must account for these pairs there) and map *values* (no fixture
     or contract shape uses a temporal map value; if one appears, extend the same helper).
4. **Invariance:** jackson-off files render byte-identical (no annotation, no import). The codec,
   the `.avsc`/Avro converter, and the `:recordbuf-avro` surface are untouched (R7; the envelope
   is U9). Jackson-ON files' existing annotations (`@JsonProperty` on temporals, the U6 extras
   component) are unchanged — the pair is additive.

## Changes

| File | Change |
|---|---|
| `json/src/main/java/bijnagte/recordbuf/json/JsonTemporal.java` | Replace the 8 message-typed `Value*` classes with java.time-typed ones (`Instant`/`Duration`; same class names); delete `Coords`/`forType`; rewrite the static render/parse helpers for the two string formats (`toIso*` kept as-is re-based on java.time; `toCanonical*` = 3/6/9-group `Z` / `Ns`; the readers accept both renderings); update the class javadoc. |
| `json/build.gradle.kts` | Drop `api(project(":recordbuf-codegen"))` **if** the rework leaves the module free of codegen references. |
| `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java` | New `temporalAnnotations(...)` helper + its call in both component loops (after `jacksonAnnotation`). |
| `generator/build.gradle.kts` | `testImplementation(project(":recordbuf-json"))` — lets generator tests run the rendered source through the *real* Jackson + `JsonTemporal` (see Tests). Test-scope only; the generated `.rec` surface stays dependency-free per R22. |

## Tests

**`:recordbuf-json`** (rework the temporal half of `JsonModuleTest`; the WKT/`JsonPayload`
sections move untouched):
- **Mechanism first:** a hand-written bean carrying `@JsonSerialize(using =
  JsonTemporal.InstantIsoSerializer.class)` + `@JsonDeserialize(using = JsonTemporal.InstantIsoDeserializer.class)`
  (2.21 annotations, 3.1.8 mapper) round-trips — proves the `using =` class-reference path
  against the pinned Jackson *before* the generator is wired (the origin plan's §11 seam risk).
- Built-in baseline: what a bare `new ObjectMapper()` emits for an unannotated `Instant`/`Duration`
  (informational assertion or comment — pins the 3.1.8 behavior for the record).
- Format tables (both formats are strings): ISO — whole-second (`…T05:01:36Z`), 9-digit-padded fraction, `PT…` duration;
  canonical — 3/6/9-group `Z` timestamp, `Ns` duration (`1.5s`, `-1.5s`, `0.5s`); negative
  `Duration` under the **floor** convention; **cross-format acceptance** (the canonical writer's
  output parses under the ISO deserializer and vice versa).

**generator** (`RecordSourceMappingTest` / `JacksonSurfaceMappingTest` patterns):
- Shape: a jackson file with a singular `Timestamp` + a singular + repeated `Duration` renders the
  ISO pair by default; the same model with the 50005 knob set on one field renders the canonical
  pair on that field only; `repeated` renders `contentUsing`; the jackson-off twin is
  byte-identical to the pre-U8 golden.
- Behavior: `assertCompilesWithRuntime` (the existing generator-test pattern) over the rendered
  source, then a real `JsonMapper` round-trip: record → JSON (expect the selected rendering) →
  record, for ISO and canonical, including the sub-second and negative cases.

## Gates & checkpoint (unit 1 of 5; the plan closes with `-005` U7)

- `./gradlew spotlessApply && ./gradlew build` fully green (Spotless, Checkstyle, EP/NullAway,
  `-Werror`, tests) — the fixtures' `wellknown` main output gains the two annotations on its
  temporal fields (expected under default-ON; no EP fallout beyond the new, `@NullMarked`-scoped
  imports).
- Explicit `git add` of exactly the files above; commit message:
  `U8 (2026-11-20 Jackson plan): temporal fields render ISO by default, proto3-canonical under recordbuf.jackson_format`

## Open (owner-decidable)

- Decision 1 deletes the eight message-typed classes. They are test-only today, but if the owner
  wants them kept as a general-purpose `com.google.protobuf` WKT codec (for hand-written beans),
  re-home them in a small sibling class instead of deleting.
