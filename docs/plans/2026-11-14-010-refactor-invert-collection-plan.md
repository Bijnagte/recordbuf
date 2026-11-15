# Refactor: invert `Formattable` collection from push to pull

- **Plan type:** refactor
- **Status:** done (all three units landed: U1 f9e4d0c; U2/U3 this commit)
- **Date:** 2026-11-14
- **Supersedes:** —
- **Related:** `docs/plans/2026-11-14-009-refactor-eliminate-mapper-collect-plan.md` (eliminated
  every *mapper-side* direct `TypeCollector` call — its "next" step is this one);
  `docs/plans/2026-11-12-003-refactor-ast-type-positions-plan.md` (U6 tree-walker design, which this
  is the concrete realization of for the `Formattable` contract).

## Context

### Why now

009 eliminated every *mapper-side* direct `TypeCollector` call, leaving the renderer's structural walk
as the sole collector **except** for two side-effect registrations the mappers still perform:
- **The collision-seed** (`RecordSourceMapping:191`): `TypeRef.of(ci.declaringPackage() + "." +
  m.name()).collect(ci)` — registers the record's own FQN so its short name *occupies* the declaring
  package, forcing any out-of-package same-named type (the protobuf-java reference type) to FQN.
- **The Avro import-seed** (`AvroConverterSourceMapping:68`): `TypeRef.of(specificRecordPkg + "." +
  avroSpecific).collect(ci)` — registers the Avro specific record's FQN for an import, conditional on
  `!samePkg`.

Both are **load-bearing** (verified this session): `TypeCollector.resolveNamed` only registers an FQN
for collision when `n.pkg()` is non-empty, but the AST expresses the record's own type and the Avro
type as *bare short names* (`TypeRef.of(m.name())`), which are **not** registered. So the seeds are
the *only* sites that register those two FQNs. They cannot be deleted — but they *can* be given a
clean home instead of leaking the `TypeCollector` through a `Formattable.collect(ci)` bridge.

The `Formattable` contract today is **push**: `void collect(TypeCollector)` — every AST node *mutates*
an external collector that is threaded through the entire tree. This is the leak the user identified:
"the `TypeCollector` leaks out of the `SourceRenderer`" because it is *passed into* `collect` rather
than *returned from* the AST. This plan inverts it to **pull**: `Set<TypeRef> referencedTypes()` —
each node *returns* the type refs it references (unioning its children), and the renderer aggregates
them. The two mapper seeds migrate to a side-channel `Set<TypeRef>` on `JavaFile` (the mappers
contribute at construction; the renderer aggregates at collect time). `TypeCollector.register` (the
`@Deprecated` bridge whose sole caller is the `Formattable.collect` override) is retired.

### Current state (verified)

- **4 `Formattable` implementers:** `TypeRef` (sealed: `Named`/`Primitive`/`Array`), `RawExpr`,
  `CommaList`, `SwitchExpr`. Each implements `collect(TypeCollector)` by pushing into the collector.
- **The renderer's collect pass** (`SourceRenderer.Pass.collect`) mixes two registration styles:
  *direct* `ci.use(ref)` for the renderer's own traversal (field/method/param/annotation types,
  `implementsFqns`, `thrown`, etc.) and *push* `f.collect(ci)` for the `Formattable` subtrees
  (initializers, control-flow conditions, for-iterables, switch subjects, and `RawExpr` args via
  `collectExpr`).
- **The two-phase contract is preserved:** the collect pass registers the whole referral set into the
  `ci`, then `ci.finalizeResolution()`, then the render pass resolves each position to its collision-
  aware short/FQN form via `ci.use(ref)`. The two passes stay (import order is encounter-stable; the
  user explicitly wants the two passes — collapsing is out of scope).
- **Safety net:** `importLines()` sorts into two `TreeSet`s (java.* first, then all other packages,
  alphabetical within each; collided names omitted), so import order is *deterministic regardless of
  registration order* — a migration that changes *how* a ref is registered (push→pull) but not
  *which* refs are registered is **byte-stable** by construction. The fixture `.rec` golden output
  plus `./gradlew build` are the gate.

## Goal

Invert the `Formattable` collection contract from **push** (`void collect(TypeCollector)`) to **pull**
(`Set<TypeRef> referencedTypes()`). After the change:
- No `Formattable` implementation mutates an external collector; each *returns* the `TypeRef`s it
  references.
- The renderer's collect pass *aggregates* refs (its own traversal + the `Formattable` subtrees + the
  side-channel) into one set, then resolves that set in a single batch — it no longer threads a
  collector through the tree.
- The two mapper seeds (collision-seed, Avro-seed) live in a side-channel `Set<TypeRef>` on
  `JavaFile` (`externalRefs()`), not in a `Formattable.collect` bridge.
- `TypeCollector.register(TypeRef)` (the `@Deprecated` bridge) is **retired** — its only caller is the
  `Formattable.collect` override, which the inversion removes.
- Generated output is **byte-identical** to today's after each unit (the two walks are preserved; only
  the registration mechanism changes).

## Approach

### The core move

Replace `void collect(TypeCollector)` with `Set<TypeRef> referencedTypes()` on the `Formattable`
interface, and implement it on all 4 implementers by *returning* the refs each contributes:

- **`TypeRef.Named`** — returns `Set.of(this)` when `pkg` is non-empty (a qualified ref is itself the
  registration target; its generics are resolved recursively by the collector when the set is
  resolved), else the empty set (a bare short name contributes nothing — mirrors today's early return).
- **`TypeRef.Primitive`** — the empty set (no package, no import).
- **`TypeRef.Array`** — the union of `element().referencedTypes()` (mirrors today's delegate).
- **`RawExpr` / `CommaList`** — the union of each `Formattable` arg/element's `referencedTypes()`.
- **`SwitchExpr`** — the union of its subject/arms/defaultArm `RawExpr`s' `referencedTypes()`
  (mirrors today's `instanceof RawExpr` guards).

The renderer's collect pass changes from *pushing* `f.collect(ci)` to *aggregating*: every `ci.use(ref)`
and `f.collect(ci)` becomes a contribution into a single local `Set<TypeRef>` (a `LinkedHashSet`),
then one batch resolution call into the `ci` at the end of the walk, then `ci.finalizeResolution()`.
This makes the collect pass a pure aggregation — the renderer no longer threads a collector through the
tree.

### The side-channel for the two seeds

`JavaFile` gains `externalRefs(): Set<TypeRef>` (an immutable, deduplicated set, empty by default). The
two mapper seeds *contribute* to it at construction instead of calling `TypeRef.of(…).collect(ci)`:
- `RecordSourceMapping` adds `TypeRef.of(ci.declaringPackage() + "." + m.name())` to the
  `JavaFile`'s external refs (the collision-seed).
- `AvroConverterSourceMapping` adds `TypeRef.of(specificRecordPkg + "." + avroSpecific)` when
  `!samePkg` (the Avro-seed).

The renderer's collect pass aggregates `file.externalRefs()` alongside the AST walk. The seeds stay
**outside** the AST (a property of the current type model: short vs qualified refs), but they now
travel through a clean, named channel instead of a `Formattable` bridge.

### Why the two walks stay

The two-pass structure (collect the whole tree, `finalizeResolution()`, render again) is load-bearing:
import order is encounter-stable across the *collect* pass, and a collision depends on the *whole*
referral set, which is only known once the tree is walked end to end. Collapsing to one pass is
explicitly out of scope — the user wants the two passes preserved. This plan changes *how* refs are
registered (push→pull), not *when*.

### Categories (the unit boundaries)

- **U1 — the `Formattable` contract.** Invert the interface + all 4 implementers to
  `referencedTypes(): Set<TypeRef>`. The collector gains a batch resolution entry point; `register`
  becomes the batch implementation (single-ref logic extracted to a private helper). The renderer's
  collect pass aggregates into a local set and resolves once. Byte-stable.
- **U2 — the side-channel.** `JavaFile` gains `externalRefs(): Set<TypeRef>`; the two mapper seeds
  migrate to it. The renderer aggregates `file.externalRefs()` with the AST walk. Byte-stable.
- **U3 — retire the bridge + migrate tests.** Delete `TypeCollector.register(TypeRef)` (the
  `@Deprecated` public bridge; its sole caller is now gone); migrate the 8 test call sites
  (`FormattableTest` ×6, `RawExprTest` ×2) from `ref.collect(ci)` to the pull shape
  (`ci.accumulate(ref.referencedTypes())` or the new batch API). Confirm no `.collect(` remains on any
  `Formattable` (a `rg` over the codebase, excluding `java.util` stream `.collect`).

## Units

### U1 — Invert the `Formattable` contract — DONE (f9e4d0c)

Replace `void collect(TypeCollector)` with `Set<TypeRef> referencedTypes()` on the interface and all 4
implementers. Add a batch resolution entry point on `TypeCollector` (e.g. `accumulate(Set<TypeRef>)`
resolving each ref; the single-ref registration logic stays private). Rework the renderer's collect
pass to aggregate a `LinkedHashSet<TypeRef>` and resolve it once before `finalizeResolution()`.

- **Do:** interface + `TypeRef`/`RawExpr`/`CommaList`/`SwitchExpr`; `TypeCollector` batch API;
  `SourceRenderer.Pass.collect` aggregation.
- **Verify:** `./gradlew build` green; fixture `.rec` output byte-identical.

### U2 — Side-channel for the two seeds — DONE
Add `externalRefs(): Set<TypeRef>` to `JavaFile` (empty by default; an `empty()`-friendly
constructor/default). Migrate the collision-seed (`RecordSourceMapping:191`) and the Avro-seed
(`AvroConverterSourceMapping:68`) to contribute to it. The renderer aggregates `file.externalRefs()`
alongside the AST walk.

- **Do:** `JavaFile.externalRefs()`; `RecordSourceMapping` + `AvroConverterSourceMapping` seed
  migration; renderer aggregation.
- **Verify:** `./gradlew build` green; the `order` fixture (collision) and the Avro converter
  byte-identical.

### U3 — Retire the bridge + migrate tests — DONE

Delete `TypeCollector.register(TypeRef)` (the `@Deprecated` public bridge — its sole caller, the
`Formattable.collect` override, is gone). Migrate the 8 test call sites to the pull shape.

- **Do:** delete `register`; migrate `FormattableTest` + `RawExprTest`.
- **Verify:** `./gradlew build` green; `rg '\.collect\('` over `codegen/src`/`generator/src` shows no
  `Formattable` collect remaining (only `java.util` stream `.collect`).

## Verification (every unit)

1. `./gradlew build` — compile + spotless + JUnit, including the fixture module that regenerates the
   import-ordered `.rec` records and round-trips them.
2. **Byte-stability:** the import-ordered generated records are identical to pre-change output; the
   mapper golden tests (`RecordSourceMappingTest`, `AvroConverterSourceMappingTest`,
   `EnumSourceMappingTest`) + the fixture module pin the emitted source. A diff means a ref was dropped
   or added — a regression to fix, not to accept.
3. `TypeCollectorTest` / `RawExprTest` / `FormattableTest` remain green after migration (collector and
   expression semantics intact).
4. After **all** units: `rg '\.collect\('` over the two source roots shows no `Formattable` collect
   call remaining.

## Risks

- **Dropped ref (the one real risk).** A node whose ref was registered *solely* by a now-inverted
  `collect` that does not return it in `referencedTypes()` would lose its import. **Mitigation:** the
  byte-stable golden test + compilation catch it; the migration is a 1:1 push→pull per implementer, so
  each `collect` that pushed a ref now returns it.
- **Generic-ref double-registration.** Today `Named.collect` registers the base *and* its generics
  (via `resolveTypeRef`). If `referencedTypes()` returns *both* the `Named` and its generics, the
  batch resolution could register the base twice. **Mitigation:** return `Set.of(this)` for a qualified
  `Named` (the collector resolves the base *and* its generics when it resolves the set entry) — a `Set`
  dedups, and the single-ref logic is unchanged, so the net registration is identical.
- **Side-channel ordering.** The two seeds are now aggregated *with* the AST walk; if aggregation
  order shifted the import set it could change order. **Mitigation:** `importLines()` sorts
  deterministically regardless of registration order, so aggregation order is irrelevant to output.
- **Renderer aggregation correctness.** Moving the renderer's own `ci.use` traversal into the local
  set must register exactly the same refs. **Mitigation:** each `ci.use(ref)` becomes `refs.add(ref)`
  and each `f.collect(ci)` becomes `refs.addAll(f.referencedTypes())`; a single batch resolve at the end
  reproduces today's registration.

## Deferred

- **Collapsing the two walks into one.** Out of scope — the two passes are load-bearing for
  encounter-stable import order; the user wants them preserved.
- **Eliminating the two seeds.** Load-bearing (verified): the record's own FQN (collision) and the
  Avro type's FQN (import) are not registered by any bare-short-name AST position. They stay, as the
  side-channel — a future change that makes the AST express those positions as *qualified* refs would
  remove the need for the side-channel.
- **Tier-3 collision tie-break (shortest-package-name).** A no-op for the current fixtures (009 U4);
  remains a deferred fixture, unchanged by this plan.
