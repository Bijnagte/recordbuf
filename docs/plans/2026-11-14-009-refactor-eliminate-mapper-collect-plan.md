# Refactor: eliminate direct `TypeCollector` calls from the mapping classes (step toward the tree-walker)

- **Plan type:** refactor
- **Status:** active
- **Date:** 2026-11-14
- **Supersedes:** — (this is the first incremental step *toward* the tree-walker that 003 U6 designed but
  004–008 deferred; it is not a re-landing of U6)
- **Related:** `docs/plans/2026-11-12-003-refactor-ast-type-positions-plan.md` (U6 tree-walker design);
  `docs/plans/2026-11-13-007-refactor-finish-type-ref-migration-plan.md` (retired the `ci.use(String)` path).

## Context

### Why now

`docs/plans/2026-11-12-003-…-plan.md` (U6) designed a **single-pass tree-walker** that replaces the
current two-pass collect/finalize/render cycle: the renderer's `Pass.collect(Type)` would be the *sole*
collector, reaching every referenced type structurally so no mapper must register a type by hand. U6 was
superseded by 004 (which introduced positional `RawExpr` templating) and never re-landed; the two-pass
machinery remains live, and the mapping classes still call `TypeCollector` **directly** in ~47 places.

The user's framing: rather than land the big-bang U6, make **smaller incremental steps that move toward the
tree-walker**, starting by **eliminating every direct `TypeCollector` call from the mapping classes**. Once
no mapper collects, the only remaining collector is the renderer's structural walk — which is exactly the
state U6 wants, and it unblocks collapsing the `TypeCollector` API (`use`/`register`/`useFqn`/
`finalizeResolution`).

### Current state (verified)

- **Two collect phases today.** (1) *Mapper-time*: while building the AST the mappers call
  `TypeRef.of(…).collect(ci)`, `ci.use(TypeRef.of(…))`, and (in InteropSourceMapping)
  `TypeRef.of(…).useFqn(ci)`, pre-resolving types to short names and inlining them into `RawExpr`/`Fragment`
  strings. (2) *Renderer-time*: `SourceRenderer.Pass.collect(Type)` walks the finished tree and calls
  `Formattable.collect(ci)` on `Component.type`, `Component.initializer`, method `returnType`/`thrown`/
  `params[].type`, body `LocalVar.init`/`ExprStatement.expr`/`If`/`While`/`For`/`Switch` conditions, and
  `RawExpr` positional args (via `RawExpr.collect`). `CodeGenerator` then calls
  `ci.finalizeResolution()` and renders.
- **The mechanism is already proven safe and half-done.** `WktSourceMapping` already passes its types
  (`wktRef`, `castRef`) as `Element.expr`/`RawExpr` positional args — so the renderer's walk already reaches
  them — yet it *also* calls `wktRef.collect(ci)`/`castRef.collect(ci)` explicitly. Those calls are
  **redundant** and can be deleted outright. This is the end-state the migration drives toward.
- **Import order is deterministic.** `TypeCollector.importLines()` sorts into two `TreeSet`s (the
  `java.*` group, then every other package, alphabetical within each; collided names omitted), so **import
  order does not depend on registration timing** — only on the *set* of registered types. Therefore a
  migration that changes *when* a type is registered (from mapper-build to the walk) but not *which* types
  are registered is **byte-stable** by construction.
- **Safety net.** The fixture model produces the import-ordered `.rec` records and exercises them (compile +
  spotless + round-trip); the mapper golden tests (`RecordSourceMappingTest`, `AvroConverterSourceMappingTest`,
  `EnumSourceMappingTest`) pin the emitted source. `./gradlew build` is the gate. The only failure mode is
  *dropping* a type whose import was provided solely by a now-deleted explicit collect — which the golden test
  plus compilation catch immediately.

### Scope of this plan

Eliminate all **47** direct `TypeCollector` calls from the 5 mapping classes
(`WktSourceMapping` 6, `RecordSourceMapping` 15, `InteropSourceMapping` 4, `CodecSourceMapping` 16,
`AvroConverterSourceMapping` 6), grouped **by category** (not by mapper). End state: the mapping classes
contain no `ci.collect`/`ci.use`/`ci.register`/`TypeRef.…collect(ci)`/`TypeRef.…useFqn(ci)` — the renderer's
walk is the sole collector. **No new regression guard is added** (decision); the golden tests are the net.

## Goal

The mapping classes perform **no direct type registration**; every referenced type reaches the
`TypeCollector` solely through the renderer's structural walk. Generated output is **byte-identical** to
today's (import order included) after each unit. The `useFqn` FQN-only registration is handled as a **distinct
unit** via an import-collision heuristic, so the mechanical `.collect(ci)` eliminations make progress without
waiting on it.

## Progress (2026-11-14)

- **Enabling sub-step — `TypeRefs` centralization: DONE.** Added
  `generator/…/plugin/writer/TypeRefs.java`: a package-private holder of every FQN the mappers emit — the
  recordbuf-runtime helpers (`ENCODER`/`DECODER`/`UNKNOWN_FIELDS`/`CODEC_DEFAULTS`/`WIRE`/`BYTES`/
  `PROTOBUF_MESSAGE`/`UNWRAPABLE`), the WKT helper (`WKT`), the Avro bridge (`WKT_AVRO_BRIDGE`), and the
  common `java.*`/`com.google.protobuf` types (`LIST`/`ARRAY_LIST`/`HASH_MAP`/`BYTE_BUFFER`/`ARRAYS`/
  `BYTE_STRING`). All 5 mappers now reference these constants instead of inline `TypeRef.of("…")` / per-mapper
  `RT` / `WKT_FQN` strings; the `codegen` module still owns only its boxed/array primitives on `TypeRef`
  itself (`TypeRef.INTEGER`, `TypeRef.BYTES`). This keeps recordbuf-specific FQNs out of `codegen` and makes
  the per-mapper `.collect(ci)`/`.useFqn(ci)` sites below mechanical to eliminate. Byte-stable
  (`./gradlew build` green).
- **U1 (Category A — redundant deletes): DONE.** `WktSourceMapping` reaches zero `.collect(ci)`; the 6 Wkt
  sites (`wktRef`/`castRef`/`requireType(entry)`/`java.util.List`/`java.util.ArrayList`) are deleted and the
  golden output is byte-identical.
- **U3 (Category C — two-pass splices): DONE.** The `.render(ctx)` + `collect(ci)` workaround is eliminated
  across `CodecSourceMapping` (BYTES/ArrayList/HashMap literals + the `CODEC_DEFAULTS` concat),
  `RecordSourceMapping` (the field-family initializer refs, incl. the `ufRef` builder carrier), and
  `InteropSourceMapping` (the map key/value `BYTE_STRING` positionals and the `from` unknownFields carrier,
  the last splice, joined via a new `CommaList` `Formattable` that comma-joins its elements at render
  time). All byte-identical. Remaining: the `AvroConverterSourceMapping` parallel pipeline (conditional
  collects L68–83 + their `Element.of` `Fragment` bodies) — the distinct, harder sub-part.
- **U3 (AvroConverter string-inlines): DONE.** `WktAvroBridge`/`Bytes`/`Arrays`/`ByteBuffer`/
  `UnknownFields` are now `Element.expr` positionals in `AvroConverterSourceMapping`; the three conditional
  registrations (`hasWkt`/`hasByteField`/always-`UNKNOWN_FIELDS`) are deleted. The one remaining registration
  is the `!samePkg` FQN — not a string inline but a short-name-vs-FQN resolution, which U4's collision/FQN
  policy addresses (a bare short-name `TypeRef` can't carry its package for the walk to register the import).
- **U4 (Category D — collision policy / retire `useFqn`): DONE.** The `TypeCollector` collision policy now
  tracks in-package short names for collision detection (without making them import-eligible), and
  `resolveNamed` FQN's a collided type only when it is out-of-package — an in-package type occupies the
  short name and stays short. `RecordSourceMapping` registers the record's own FQN when a protobuf-java
  ref is present (occupying its short name), subsuming the hand-forced `TypeRef.of(ref).useFqn(ci)`.
  The `useFqn` bridge (`TypeCollector.useFqn(String)`/`useFqn(TypeRef)` + the `Formattable.useFqn`
  default) is deleted — no callers remain. `TypeCollectorTest.inPackageCollisionFqnsOutOfPackageOnly`
  pins the new behavior. Generated output is byte-identical (verified against the pre-change fixtures).
  The 3-tier policy's tier-3 (shortest-package-name tie-break) is a no-op for the current fixtures (no
  genuine multi-way out-of-package collision), so it is not yet exercised; a fixture to lock it remains a
  deferred follow-up.
- **U2 (B1 — `ci.use` oneof fallback): DONE.** The last `ci.use(TypeRef.of(…))` call — the
  `oneofDecodeRhs` enum branch's `typeResolver == null` fallback — is eliminated. The oneof enum member
  now resolves to its **record FQN** (`ci.declaringPackage() + "." + shortName`) as a `TypeRef`
  positional, so the tree-walker registers it in-package (short, no import) instead of the natural
  protobuf-java reference FQN (out-of-package, which would force a redundant `import`). The
  `typeResolver` param is dropped from the oneof helpers (`emitOneofDecodeCase`/`oneofDecodeRhs`); it
  stays on the non-oneof enum/message decode. This also fixes a latent bug where the nested-message
  recursion's `typeResolver` returned `null` for an enum oneof member (now irrelevant — the oneof path
  uses `ci.declaringPackage()`). Byte-identical; full build green. Per the user, the full `Type`/
  `TypeRef` integration (giving `Type` a package + a `ref()` method) is deferred; this uses the minimal
  "add the record package to the type" form.
- **Next:** with the last `ci.use` gone, the remaining `@Deprecated` `TypeCollector` bridge methods
  (`use`/`register`) can be retired once nothing but the renderer's walk calls them — or begin the full
  tree-walker (003 U6).

## Approach

### The core move

For each site, stop *pre-resolving* the type to a short string and *inlining* it; instead **embed the
`TypeRef` as a positional arg** where it is used, and let the renderer resolve it at render time (and register
it at collect time). Concretely:

- **`String x = X.render(ci.resolutionContext()); … expr("…@0…", x)`** becomes
  **`expr("…@0…", X)`** — delete the `X.collect(ci)` and the `x` local.
- **`"…" + x + "…"` / `X.render(ctx) + ".foo()"`** becomes a `RawExpr` template with `X` as a positional arg:
  `RawExpr.of("@0.foo()", X)`, wrapped in an `ExprStatement`.
- **`Element.of("…literal Type…")`** (a `Fragment` inlining a type name) becomes an `ExprStatement`/`RawExpr`
  with the `TypeRef` as an arg (the `Fragment` escape is retired for that line).

The renderer already collects `RawExpr` args (`RawExpr.collect` → `Formattable.collect` →
`TypeCollector.register`), so embedding the `TypeRef` makes the walk the registrant. Because `importLines()`
sorts, the import block is unchanged.

### Categories (the unit boundaries)

The 47 sites fall into four categories. Each is a unit; per-site classification is confirmed by the golden
test during implementation (a site that breaks the byte-stable gate is promoted to a harder category).

- **Category A — redundant deletes.** The type is *already* a positional arg of the `Element.expr`/`RawExpr`/
  `LocalVar`/`Method` position that uses it, so the walk already registers it. **Delete the `.collect(ci)`**;
  confirm byte-stable. `WktSourceMapping` is the proving ground (`wktRef`/`castRef` are already args). Any
  Category-A site whose deletion changes output is, by definition, not yet reachable and is promoted to B or C.
- **Category B — short-name splices.** `String xShort = X.render(ctx)` is passed as an arg; swap the arg to
  the `TypeRef X`, delete `xShort` + `X.collect(ci)`. Dominant in `CodecSourceMapping`
  (`ENCODER`/`DECODER`/`UNKNOWN_FIELDS`/`CODEC_DEFAULTS`/`WIRE`/`BYTES`, two blocks at L71–82 and L634–644).
  Includes the `ci.use(TypeRef.of(f.typeUrl()))` **typeResolver fallback** (`CodecSourceMapping:861`): the
  `typeResolver != null` branch yields a short-name *string* from a `Function<Field,String>`, so eliminating
  the fallback means making the `typeResolver` yield a `TypeRef` (or routing both branches through a `RawExpr`
  arg) — a small sub-decision inside this unit.
- **Category C — `Fragment`/concat + registration-only + conditional.** The type is inlined as a *literal* in
  a `Fragment` or string concat, or registered for a body position the walk does not currently reach; convert
  that position to a `RawExpr`/`ExprStatement` with the `TypeRef` as an arg.
  - `CodecSourceMapping`: `BYTES`/`ArrayList`/`HashMap` literals (`new Bytes.of(…)`, `new ArrayList<>()`,
    `new HashMap<>()`) and `codecDefaultsShort + "." + defaultMethod(f) + "()"` (L228).
  - `RecordSourceMapping`: the field-family initializer refs
    (`mapRef`/`listRef`/`wktRef`/`sibRef`/`bytesRef`/`wktElemRef`/`sibElemRef`/`valType`/`ufRef`/
    `ArrayList`/`HashMap`, L223–454) — the `Element.expr("…@0…", xType, …)` patterns where `xType` is a
    pre-resolved string.
  - `InteropSourceMapping`: `BYTE_STRING.copyFrom(…)` (`L367`, `L377`) → `RawExpr("@0.copyFrom(_e.getKey())",
    BYTE_STRING)`; `UNKNOWN_FIELDS` (`L116`).
  - `AvroConverterSourceMapping` (the **parallel pipeline**: it builds its own `TypeCollector` and renders
    directly via `SourceRenderer.render`, with **conditional** registrations): the conditional collects
    (`L68` when `!samePkg`, `L73` when `hasWkt`, `L79–81` when `hasByteField`, `L83` always) plus the
    `Element.of(…)` `Fragment` bodies that inline `UnknownFields`/`WktAvroBridge`/`Bytes`/`ByteBuffer`/
    `Arrays`/`avroSpecific`. Hardest: the conditionality must be preserved via **conditional arg-inclusion**
    in the `RawExpr`, not turned unconditional.
- **Category D — generalize `TypeCollector` collision resolution and retire `useFqn` (DISTINCT unit).**
  Today `TypeCollector` resolves a collision on a short name by emitting **every** distinct FQN sharing that
  short name fully-qualified. `InteropSourceMapping:68` calls `TypeRef.of(ref).useFqn(ci)` to pre-empt a
  special case: the protobuf-java reference type (e.g. `fixture.order.ref.Sample`) shares its short name
  `Sample` with the **enclosing** record (`fixture.order.rec.Sample`), which is the declaring type and so is
  skipped from imports — the collision is never detected, and `useFqn` forces the ref FQN by hand. The user's
  policy generalizes this: **for a colliding short name, in priority order — (1) a type in the current
  (declaring) package stays short (imported); (2) any type not in the current package renders fully-qualified;
  (3) if a tie remains, prefer FQN for the type with the *shortest* package name (a deterministic tie-break).**
  This subsumes the `useFqn` case (the ref is out-of-package → FQN; the record is in-package → short) and
  resolves genuine multi-way collisions deterministically, so `useFqn` can be deleted once the walk registers
  the ref. It is a distinct unit because it changes *collision-resolution semantics* in `TypeCollector` (a
  behavior change from "FQN all colliding" to the 3-tier policy), not a mechanical arg swap; it is sequenced
  last and may be deferred without blocking A/B/C. It must be output-neutral for the current fixtures, which
  require verifying no genuine 2/3-way in-package collision exists today (or adding a fixture to lock it).

### Why grouping by category (not by mapper)

Category A is the trivial confidence builder (deletes only); B is mechanical arg-swaps; C is the
`Fragment`-to-`RawExpr` conversion plus the conditional Avro pipeline; D is a resolution-semantics change.
Sweeping by difficulty (A → B → C → D) keeps each unit's risk bounded and its verification sharp, even though
each category touches more than one mapper.

### Invariants and guardrails

- **Byte-stability per unit.** After every unit, the import-ordered `.rec` records are byte-identical to
  today's (the fixture import-ordering tests + mapper golden tests), and `./gradlew build` is green.
- **No import set change.** A unit must not add or drop a registered type; it only changes *when* registration
  happens (mapper-build → walk). `importLines()` sorting makes order deterministic; the set must be preserved.
- **Conditionality preserved.** AvroConverter's conditional registrations must remain conditional
  (register a type only when its `Fragment`/arg is emitted).
- **No new guard** (decision). Golden tests are the net; no lint/test is added that forbids direct collection.
- **`useFqn` stays until D.** A/B/C do not touch `InteropSourceMapping`'s `useFqn`; that is D's job.

## Units

### U1 — Category A: delete redundant `.collect(ci)` — **DONE** (`WktSourceMapping` at 0)

Delete every `.collect(ci)` whose type is already a positional arg of the position that uses it; `WktSourceMapping`
is the proving ground (`wktRef`/`castRef`/`requireType(entry)`/`java.util.List` at L67, 68, 74, 130, 140, 156).
For each, delete the call and confirm the golden output is byte-identical; a site whose deletion changes output
is promoted to U2/U3 (it was not actually reachable).

- **Do:** per-mapper delete-and-verify; `WktSourceMapping` reaches zero `.collect(ci)` first.
- **Verify:** `./gradlew build` green; the Wkt coverage is `RecordSourceMappingTest` (it drives the record
  pipeline through the Wkt/Codec/Interop mappers, asserting `src.contains(…)` + `assertCompiles`) plus the
  Wkt fixture round-trip. (There is no dedicated `WktSourceMappingTest`/`InteropSourceMappingTest`/
  `CodecSourceMappingTest` — only `RecordSourceMappingTest`, `AvroConverterSourceMappingTest`, and
  `EnumSourceMappingTest` exist, with the fixtures exercising the rest.)

### U2 — Category B: short-name splices — **B1 (`ci.use` oneof fallback) DONE; rest pending**

Swap each `String xShort = X.render(ctx)` passed as a `RawExpr`/`Element.expr` arg to the `TypeRef X`; delete the
`xShort` local and the `X.collect(ci)`. Primary target `CodecSourceMapping` (`ENCODER`/`DECODER`/
`UNKNOWN_FIELDS`/`CODEC_DEFAULTS`/`WIRE`/`BYTES` at L71–82, L634–644, L870). Resolve the **typeResolver
fallback** (`L861`): **DONE** — the oneof enum member resolves to its record FQN
(`ci.declaringPackage() + "." + shortName`) as a `TypeRef` positional, so the walk registers it
in-package (short, no import) rather than the natural reference FQN; the `typeResolver` is dropped from
the oneof helpers (kept on the non-oneof enum/message decode). The rest of Category B remains.

- **Do:** arg-swap + delete; settle the `typeResolver` sub-decision to keep the enum/codec-ref path byte-stable.
- **Verify:** `./gradlew build` green; codec fixture + `RecordSourceMappingTest` byte-identical.

### U3 — Category C: `Fragment`/concat + registration-only + conditional — **two-pass splices DONE**; AvroConverter pipeline remains

Convert positions that inline a type as a literal into `RawExpr`/`ExprStatement` with the `TypeRef` as an arg;
retire the `Fragment` escape on those lines; delete the now-redundant collects. Covers
`CodecSourceMapping` (`BYTES`/`ArrayList`/`HashMap` literals, `codecDefaultsShort + "."` concat at L228),
`RecordSourceMapping` (field-family initializer refs L223–454), `InteropSourceMapping` (`BYTE_STRING.copyFrom`
L367/377, `UNKNOWN_FIELDS` L116), and `AvroConverterSourceMapping` (conditional collects L68–83 + their
`Element.of` `Fragment` bodies). Preserve AvroConverter conditionality via conditional arg-inclusion.

- **Do:** `Fragment`-to-`RawExpr` per site; conditional args for AvroConverter; delete each collect; the
  `AvroConverterSourceMapping` parallel pipeline is the last and hardest sub-part.
- **Verify:** `./gradlew build` green; Avro + record + interop fixtures byte-identical;
  `AvroConverterSourceMappingTest` byte-identical.

### U4 — (distinct) generalize `TypeCollector` collision resolution; retire `useFqn` — **DONE**

Today `TypeCollector` resolves a collision on a short name by emitting **every** distinct FQN sharing that
short name in fully-qualified form. `InteropSourceMapping:68` (`TypeRef.of(ref).useFqn(ci)`) pre-empts a
special case: the protobuf-java reference type (e.g. `fixture.order.ref.Sample`) shares its short name
`Sample` with the **enclosing** record (`fixture.order.rec.Sample`), which is the declaring type and is
skipped from imports — the collision is undetected and `useFqn` forces the ref FQN by hand.

**Policy (user-defined), applied to a colliding short name in priority order:**

1. A type **in the current (declaring) package** stays short (imported).
2. Any type **not in the current package** renders fully-qualified.
3. If a tie still remains, **prefer FQN for the type with the shortest package name** (deterministic
   tie-break).

This generalizes the `useFqn` case (ref out-of-package → FQN; record in-package → short) and resolves genuine
multi-way collisions deterministically, replacing today's "FQN *all* colliding" with the 3-tier policy. After
the policy lives in `TypeCollector`, delete `TypeRef.of(ref).useFqn(ci)` at `InteropSourceMapping:68`, letting
the walk register `ref` and the policy force its FQN form.

- **Do:** implement the 3-tier policy in `TypeCollector`'s collision resolution; delete the `useFqn` call; the
  ref then renders FQN and its short name contributes no import. **Output-neutrality check:** confirm the
  current fixtures contain no genuine in-package 2/3-way collision that today's "FQN all" handled differently
  than the new policy (if one exists, add a fixture that locks the new behavior).
- **Verify:** `./gradlew build` green; the `order` fixture (the `ref.Sample` ↔ `Sample` collision) byte-identical;
  `TypeCollectorTest` covers the 3-tier policy; add a genuine multi-way-collision fixture to pin the tie-break.
- **Sequencing:** last; may be deferred independently — A/B/C progress without it.
## Verification (every unit)

1. `./gradlew build` — compile + spotless (`spotlessCheck`) + JUnit, including the fixture module that
   regenerates the import-ordered `.rec` records and round-trips them.
2. **Byte-stability:** the import-ordered generated records are identical to pre-change output (the fixture
  import-ordering tests + `RecordSourceMappingTest`/`AvroConverterSourceMappingTest`/`EnumSourceMappingTest`
  — the three dedicated mapping golden tests, which exercise the Wkt/Interop/Codec mappers through the record
  pipeline (`src.contains(…)` + `assertCompiles`) and the Avro converter; the fixture module supplies the
   golden assertions). A diff here means a type was dropped or added — a regression to fix, not to accept.
3. `TypeCollectorTest` / `RawExprTest` / `TypeRefTest` remain green (collector and expression semantics intact).
4. After **all** units: confirm no `ci.collect`/`ci.use`/`ci.register`/`TypeRef.…collect(ci)`/
   `TypeRef.…useFqn(ci)` remains in any `writer/*.java` (a `rg` over `generator/src/main/…/writer`), i.e. the
   walk is the sole collector — the state U6 wanted.

## Risks

- **Dropped type (the one real risk).** A site whose type was registered *solely* by a now-deleted explicit
  collect, used as a literal in a `Fragment`/concat that was not converted, would lose its import → compile
  error or a wrong short name. **Mitigation:** the byte-stable golden test + compilation catch it; convert the
  position to a `RawExpr` arg rather than deleting the collect.
- **Conditionality lost (AvroConverter).** Turning a conditional registration unconditional would import a type
  that some inputs never emit (unused import → spotless/checkstyle failure, or a behavioral diff).
  **Mitigation:** conditional arg-inclusion in the `RawExpr`; the `AvroConverterSourceMappingTest` catches an
  unused import.
- **`typeResolver` fallback (U2).** The `typeResolver != null` branch returns a pre-resolved short-name
  *string*; merging it with a `TypeRef`-arg path changes that string's origin. **Mitigation:** keep the two
  branches byte-equivalent; settle the sub-decision against the golden test.
- **Collision-policy behavior change (U4).** Today `TypeCollector` FQN-ifies *every* type sharing a colliding
  short name; the 3-tier policy instead keeps an in-package type short and FQN-ifies out-of-package types
  (tie-break: shortest package name FQN). This is output-changing for any genuine multi-way collision the
  current fixtures already exercise. **Mitigation:** confirm no such collision exists today (or add a fixture
  that locks the new behavior); `TypeCollectorTest` + a genuine multi-way-collision fixture guard the
  tie-break; the `order` fixture pins the `ref.Sample` ↔ `Sample` case.
  `order` fixture byte-stable); `TypeCollectorTest` + the `order` fixture guard it.
- **Parallel pipeline (AvroConverter).** `AvroConverterSourceMapping` renders via its own `TypeCollector` +
  `SourceRenderer.render`, not through `CodeGenerator`; its collect sites are conditional and its bodies are
  `Fragment`s. **Mitigation:** sequence it last in U3; verify with `AvroConverterSourceMappingTest`.

## Deferred

- **The full tree-walker (003 U6).** Making the renderer's walk the *sole* collector across **all** surfaces —
  including `RawExpr` args, which are the remaining escape hatch where types are spliced — and collapsing the
  `TypeCollector` API (`use`/`register`/`useFqn`/`finalizeResolution`) to a single walk-only contract. This
  plan is the **enabling prerequisite**: it removes every *mapper-side* direct call, leaving only the walk.
- **Retiring the remaining `@Deprecated` `TypeCollector` bridge methods** (`use`/`register`) once nothing
  but the renderer's walk calls them. `useFqn` was retired in U4 (no callers remain); `use`/`register`
  stay until the tree-walker (003 U6) makes the walk the sole collector.
