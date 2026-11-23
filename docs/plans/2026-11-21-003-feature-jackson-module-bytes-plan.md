# Jackson module auto-registration + `bytes` base64 mapping — implementation plan

**Date:** 2026-11-21 · **Status:** DONE 2026-11-22 (sequence 3 of 5; U7 oneofs is deliberately
last — `2026-11-21-005`). Verification: `./gradlew build` green. **Deviation — discovery is
opt-in:** the "Current state" claim below is wrong for 3.1.8: `ServiceLoader` is reached only via
`MapperBuilder.findAndAddModules()` (`javap`-verified; no constructor or `build()` path calls it),
so a bare `new JsonMapper()` does **not** pick the module up. The service file is shipped and
proven with `JsonMapper.builder().findAndAddModules()`; generated records are unaffected (the
per-field `JsonBytes` pair works under a bare mapper, also proven). A hand-written bean holding
`Bytes` needs discovery or `addModule(new RecordbufJsonModule())`. The module extends
`SimpleModule` (a `JacksonModule`) rather than overriding `setupModule` by hand.
**Origin:** the 3.x handoff's remaining item 1 (owner's steer 3: "add auto registration of a
module `META-INF/services/tools.jackson.databind.JacksonModule` so a bare `new JsonMapper()` Just
Works") + the origin doc's **known gap** (lines 307–312: "the plan never assigns the proto3-JSON
base64 string mapping for `bytes` fields, and a plain `ObjectMapper` serializes the `Bytes` object
as a Bean (`{\"empty\":true}`)") resolved by the owner's **"option 1": per-field annotations +
module default**.
**Related:** `2026-11-21-001` (U8 temporals — consumes nothing from this unit), `-002` (U9
envelope — `JsonPayload` already exists), `-004` (U10 fixtures/acceptance: AE3's bytes caveat is
lifted by this unit), `-005` (U7 oneofs — a oneof *member* that is `bytes` needs the same pair on
the wrapper's `value` component; U7 is written against this unit landing first).

## Problem frame

Two holes in the default-ON Jackson surface, both consumer-ergonomics holes rather than contract
holes: (1) a consumer who writes a **hand-rolled bean** holding `bijnagte.recordbuf.runtime.Bytes`
and binds it through a bare `new JsonMapper()` gets a bean dump `{"empty":true}` — the proto3-JSON
contract for `bytes` is a **base64 string**, and nothing on the classpath teaches the mapper that;
(2) the origin doc never assigned the `bytes` field mapping at all (its U6 unit notes: "a Jackson
module or per-field (de)serializer pair is the natural home … in `:recordbuf-json`"). The owner's
option 1 closes both at once: per-field annotations pin the contract on generated records, and the
auto-registered module covers everything else on the classpath.

## Current state (verified against the tree)

- `:recordbuf-json` hosts `DynamicWkt`, `JsonExtras`, `JsonPayload`, `JsonTemporal`,
  `package-info` — **no module class, no `META-INF/services` resource**. `JsonPayload` already
  self-describes via `@JsonValue`/`@JsonCreator` (works module-free).
- **3.x auto-discovery exists and is the default:** the 3.1.8 `MapperBuilder` bytecode
  (`javap`-verified) runs `ServiceLoader.load(JacksonModule.class, …)` during mapper construction —
  a `META-INF/services/tools.jackson.databind.JacksonModule` entry in any jar on the classpath is
  picked up by a bare `new JsonMapper()`. The module base class is
  `tools.jackson.databind.JacksonModule` (with `setupModule(SetupContext)`). The databind jar's
  own `META-INF/services` registers `JsonMapper` as an `ObjectMapper` factory — unrelated, don't
  confuse the two.
- `bytes` field typing: scalar `bytes` fields and the `google.protobuf.BytesValue` wrapper resolve
  to `bijnagte.recordbuf.runtime.Bytes` (`TypeRefs.BYTES`; `WellKnownTypes` `BYTES_WRAPPER`
  entry). `:recordbuf-json` **already** carries `api(project(":recordbuf-runtime"))` (added for
  U6's unknown-field folding) — the `Bytes` type is on the module's compile classpath today; no
  new dependency edge is needed.
- `RecordSourceMapping` attaches per-component Jackson annotations in the `jacksonAnnotation`
  hook (the U8 doc's `temporalAnnotations` sibling lands next to it in sequence 1); the `bytes`
  pair slots in as a third sibling in the same loops.

## Decisions

1. **The module registers exactly the `Bytes` base64 pair (v1).**
   `bijnagte.recordbuf.json.RecordbufJsonModule extends tools.jackson.databind.JacksonModule`:
   `setupModule` registers a `Bytes` serializer (`Bytes` → base64-encoded UTF-8 **string**) and
   deserializer (base64 string → `Bytes`; `null`/absent → the existing presence semantics, i.e.
   the deserializer returns `null` for JSON `null`). Nothing else is registered: temporals ride
   the 3.x built-in or the U8 explicit pairs; `JsonPayload` is self-annotating; the `DynamicWkt`
   family is conversion helpers, not mapper types. (If U8's verification lands in the
   "built-in diverges" branch, the module gains the ISO pair registration *in U8*, here only if
   the owner wants it — default: no.)
   - `json/src/main/resources/META-INF/services/tools.jackson.databind.JacksonModule` lists the
     module FQN. Service discovery therefore activates for **every** consumer of `:recordbuf-json`
     (which is every jackson file's consumer, directly or via generated code) — exactly the
     "bare `new JsonMapper()` Just Works" guarantee. The 2.21 `com.fasterxml` annotations jar is
     unaffected (different coordinate; the service file names the 3.x interface).
2. **Per-field pair = owner's option 1 first half.** A new `JsonBytes` class in `:recordbuf-json`
   (`B64Serializer`/`B64Deserializer`, the same base64 shape, 3.x `Value*` bases) + emission in
   `RecordSourceMapping` for any `bytes`-family field (scalar `bytes`, `BytesValue` wrapper):
   singular → `using =`; `repeated` (`List<Bytes>`) and map *values* → `contentUsing =`
   (element-wise, the same nuance as the U8 temporals). The annotation pins the contract even
   under a mapper that skipped discovery; the module covers the rest. Both are the **same**
   `Bytes ↔ base64` function (the module delegates to the pair class — one implementation, two
   registration paths).
3. **Invariance:** jackson-off files stay byte-identical; the codec (R7), the avro twin, and the
   `:recordbuf-runtime` module are untouched. The `:recordbuf-json` jar gains one class + one
   resource file; its existing API surface is unchanged apart from the new types.

## Changes

| File | Change |
|---|---|
| `json/src/main/java/bijnagte/recordbuf/json/JsonBytes.java` | New: the `Bytes` ↔ base64-string 3.x `Value*` pair (the single implementation). |
| `json/src/main/java/bijnagte/recordbuf/json/RecordbufJsonModule.java` | New: the `JacksonModule` registering the `Bytes` pair via `setupModule`. |
| `json/src/main/resources/META-INF/services/tools.jackson.databind.JacksonModule` | New: the service file (module FQN). |
| `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java` | `bytesAnnotations(...)` sibling in both component loops: `bytes`-family field ⇒ the `JsonBytes` pair (`using`/`contentUsing` by cardinality). |
| Tests | See below. |

## Tests

- **`:recordbuf-json`**: (a) **discovery proof** — a bare `new JsonMapper()` (no manual
  `registerModule`) serializes a hand-written bean holding `Bytes` as a base64 string and reads
  it back (this is the owner's exact "Just Works" ask, proven against the pinned 3.1.8);
  (b) the pair's table: empty `Bytes`, non-UTF8 payload bytes, round-trip, JSON-null handling.
- **generator**: a jackson file with a `bytes` scalar + a `BytesValue` + a `repeated bytes` + a
  `map<string, bytes>` renders the pair with `using`/`contentUsing` respectively; the
  `recordbuf.jackson = false` twin is byte-identical; the oneof-member case is exercised by U7
  (`-005`), not here.
- **fixtures-level**: none added in this unit (the `bytes.proto` main fixture + a new test class
  live in U10, `-004`; the module-coverage story for generated records is proven by the generator
  render tests + the U8/`-001` behavior pattern).

## Gates & checkpoint

- `./gradlew spotlessApply && ./gradlew build` fully green (the `:recordbuf-json` jar now ships
  the service resource; fixtures' generated `bytes` output gains the two annotations under
  default-ON — expected).
- Explicit `git add` of exactly the files above; commit message:
  `J (2026-11-20 Jackson plan): the RecordbufJsonModule auto-registers (META-INF/services) and bytes fields map to proto3 base64`

## Open (owner-decidable)

- If U8 (`-001`) lands in its "built-in diverges" branch, the ISO temporal pair can ride this
  module instead of per-field annotations. Default plan: no (per-field annotations there are the
  deterministic pin; the module stays `Bytes`-only).
