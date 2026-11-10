---
artifact_contract: ce-unified-plan/v1
execution: code
origin:
date: 2026-11-10
updated_at: 2026-11-10
status: ready
artifact_readiness: implementation-ready
planning_complete: true
---

# Refactor — Structured source AST for recordbuf codegen (hand-written builders + closed-idiom body AST)

# status: ready
# This is an **implementation-ready** plan. It states why this work matters, what the new, improved
# consumer-facing AST must provide, and the concrete structure -- node shapes, the hand-written
# builder/modifier surface, the closed-idiom body AST, and the ordered implementation units -- resolved
# in the Concrete Design Decisions + Implementation Units sections below. The executor
# (ce-work / ce-code-review) MAY refine a pinned decision as execution demands (recorded as a tracked
# KTD in docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md, per KTD1), but need not invent it.

## Summary

Today the `:recordbuf codegen` module renders Java source from a small AST: a sealed `Type`
(`Record`/`Class`/`Enum`/`Interface`) + sealed `Member` (`Field`/`EnumConstant`/`Method`/
`Constructor`/`StaticBlock`/`Nested`) spine, plus `Modifiers` (a `record Modifiers(String prefix)`
built from `Modifiers.of("public", "static", "final")`), `Line`, and `Javadoc`. Two seams make the
generator's own code noisy to write and to read:

1. **The builder surface is the soabase `@RecordBuilder`-generated `*Builder`** (8 node records carry
   `@RecordBuilder` -- the four `Type` variants + the four `Member` variants `Field`/`EnumConstant`/
   `Method`/`Constructor`; `StaticBlock`/`Nested` are un-decorated; each record generates a `*Builder`
   type, e.g. `TypeClassBuilder`/`MemberMethodBuilder`, used *uniformly* by the six generator source-
   mappings + the `:recordbuf-codegen` test sources via their `...builder()...build()` fluents). It is
   uniform, generated, and bare: a caller faces raw `isFinal(true)`-style booleans, must pre-assemble
   `List.of(a, b)` for collection fields, and cannot get ergonomic named toggles. We want **hand-
   written builders** that
   give sensible *defaults* plus self-documenting *conveniences* -- `final()`/`static()`/`sealed()`
   toggles that read as intent (default off), and `member(x)`/`field(x)`/`extends(x)` **accumulators**
   so a caller does not assemble collections at the call site.
2. **Method/constructor/static-init bodies are a flat `List<Line>`** (line-emission inside an
   otherwise-structured AST -- the deliberate "hybrid" of R8/R20). The generator therefore keeps
   **manual indent bookkeeping** (`Line.of("    _id,")`, `Line.of("  case ...")`, `Line.of("}")`),
   concatenates strings and **magic-string** fragments (`"    "`, `"  "`, `");"`), and writes the
   control flow (if / for / foreach / switch-with-`->` / return / throw) as string fragments. This
   is the heaviest part of the heaviest mapping (`:recordbuf-codegen`'s `InteropSourceMapping`
   field-by-field converters, oneof `switch`, map loops).

This work lifts both seams: **phase one** replaces the soabase builders on the 8 node records with
**hand-written, YAGNI-guarded ergonomic builders** and reshapes `Modifiers` from a joined string into
a **structured modifier type**; **phase two** lifts the flat `List<Line>` bodies into a **small,
extensible closed idiom set** of structured statements + recurring expression shapes the renderer
lays out -- taking ownership of indentation, brace-balancing, and line-joining -- with a **raw
text-fragment** as the explicit escape for irregular idioms. The emitted `.rec` output stays
**behavior-preserving and format-clean** (M9/M10/M11 invariants, M4 "forgiving on formatting, strict
on behavior"); the change is unobservable in the emitted surface.

## Decision & Scope (settled in brainstorm, 2026-11-10)

- **Scope: `:recordbuf-codegen`'s consumer-facing AST only.** Hand-written builders + structured
  modifiers + the closed-idiom body AST are added to / reshaped within `:recordbuf-codegen`, and its
  consumers (the six generator source-mappings, the four :recordbuf-codegen test sources -- ClassSourceTest, JavaSourceTest, SourceRendererJavadocTest, TypeCollectorTest -- and the CodeGenerator/Main dispatch entry, all uniformly on the soabase *Builder surface) are migrated to them. **Deferred/known residual:** dropping soabase `@RecordBuilder`
  from the generator's *internal model records* (`Field`/`Message`/`Enum`/… in `:recordbuf-generator`/
  `:recordbuf-runtime`) and removing soabase repo-wide -- a follow-up that *reuses this same
  hand-written-builder pattern*; not in scope here.
- **No new emission behavior.** This is HOW, not WHAT -- the emitted `.rec` surface is unchanged.
- **Output parity: behavior-preserving, not byte-identical.** M4's stance: the output may reformat
  cleanly (format-clean, M9/M10/M11 invariants intact, round-trip + source-of-truth compile gates green)
  but need not be byte-identical. That is what *frees* the indent-bookkeeping / magic-string cut, since
  the change is unobservable downstream.
- **One internally-phased change, not one big bang.** M4-C2b-6 -- the source-AST entry milestone -- is
  already shipped (git `86b5058`; the 002 plan's M4 is closed), so its gate is the verified entry
  checkpoint, not a task to finish. Then phase one
  (declaration layer: builders + modifiers), then phase two (body layer: closed-idiom set). Each phase
  is independently buildable + verifiable; nothing in scope blocks a phase from landing green.
- **Forward-looking posture, not forward scope.** The new API is kept decoupled from recordbuf-specific
  concerns, the idiom set is *extensible* (the raw-fragment escape is the "add a new idiom later" lever,
  not a hard "closed" fence), and the ergonomic surface is *additive-friendly* so future code-generation
  projects can build on it -- but this plan does **not** add forward-looking features or widen scope;
  module positioning stays as-is (`java-library`, co-versioned `bijnagte.recordbuf:0.0.1`, decoupled),
  and "publish when ready" is a later step, not now.

## Why this matters

- **The two payoffs pull in opposite directions, and this work resolves both.** *Removing indent
  bookkeeping* pushes structure *up* into the body (the renderer owns indent); *reducing string
  concatenation and magic strings* pushes structure out of the *declarations* (builders + a structured
  modifier). One coherent design -- a richer, well-bounded AST the renderer lays out -- is the
  convergence of both, and it is why this is one coordinated change phased internally, not two.
- **The emitter's job is *structure*, not string-emission.** Today the generator writes Java as
  concatenated `Line` strings with hand-chosen indent and brace fragments. Lifting control flow into
  an AST means the emitter *describes what to emit* and the *renderer* owns *how it is laid out*
  (indent level, brace pairing, newline joining) -- eliminating an entire class of string/indent bugs
  (mismatched braces, indentation typos) that the renderer's current "indent each line to the
  enclosing block's indent" contract has to defend against.
- **A clean, ergonomic, reusable API is the product.** `:recordbuf-codegen` is already positioned as a
  decoupled emitter library (`java-library`, no main-scope runtime dep, R22 -- the generated `.rec`
  surface stays dependency-free). Its consumer API is the thing the user will reuse across future
  code-generation projects. Polishing that API (named toggles, accumulators, a small extensible
  idiom set) is the durable value; dropping an external dep is one by-product, not the goal.
- **The migration risk is bounded and concentrated.** The construct surface the emitter uses is fixed
  and known (the six mappings emit a finite, recurring idiom set). The heaviest concentration is
  `InteropSourceMapping` (field-by-field from/to converters, oneof `switch`-with-`->`, map loops) --
  where the closed idiom set must hold or that body forces fragments. Risk is visible, not invisible,
  so it can be planned around.

## Inherited Context from Prior Work

This build is not isolated. It extends work already present in the codebase (see also
`docs/plans/2026-11-07-001-feat-well-known-types-native-plan.md` for the WKT work it rides on):

- **The `:recordbuf-codegen` module itself + the writer migration -- `docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md`.**
  This is the source plan that created the `:recordbuf-codegen` clean-room module and migrated the
  generator's writer to it (M0–M14, with M4 phased M4-C1 … M4-C2b). **M4-C2b-6 -- the final M4-C2b
  task -- is already shipped** (git `86b5058`; "make the `:recordbuf-codegen` test suite compile
  green + wire the source-of-truth compile gate"); **its green gate is the entry checkpoint to this
  work** and the first milestone below. R22 (the generated `.rec` surface stays dependency-free) is a *product contract* this work
  must keep. The plan's open items M9/M10/M11 (Javadoc + `@Deprecated` into the AST; generated record
  builders; protobuf-java interop) are the *substantive* follow-ups -- they are **out of scope here**;
  this work is the *structural* follow-up that improves the API shape those will build on.
- **The WKT-native emit -- M4-C1.** The generator now emits the 10 WKT record types + the 20
  reference-only type-alias stubs as pure-Java source-AST via the same mappings this work reshapes.
  The closed idiom set must remain a superset of what those mappings already use, or the WKT output
  breaks a green gate.
- **NullAway generated-code scope -- `docs/plans/2026-11-04-001-feat-nullaway-generated-code-plan.md`.**
  The generated `.rec` output now falls in NullAway's `OnlyNullMarked` scope. The new statement/expression
  AST the renderer lays out produces that same output, so whatever non-nullability the new AST
  propagates into the emitted `.rec` must continue to hold (e.g. the byte-field `new byte[0]`
  null-default from that slice).
- **The checkstyle gate -- `docs/plans/2026-11-06-002-feat-add-checkstyle-plan.md`.** The
  `checkstyle` quality gate is wired as part of `check`/`build`. The hand-written builders become real
  code under *all* the quality gates (Spotless + Checkstyle + NullAway) -- a surface the soabase-
  generated `*Builder`s were **excluded** from (`excludedPaths` in the error-prone wiring). The new
  builders therefore must pass those gates; that is a real (small, bounded) carry.
- **Wiring to remove -- the soabase `@RecordBuilder` on 8 codegen node records.** `:recordbuf-codegen`'s
  `codegen/build.gradle.kts` carries `compileOnly(libs.record_builder.core)` +
  `annotationProcessor(libs.record_builder.processor)`; both are dropped as a natural consequence, and
  the soabase `*Builder` import sites (`TypeClassBuilder`/`MemberMethodBuilder`, etc.) are all in
  `:recordbuf-codegen` test sources + the six generator mapping sources (not the emitted `.rec` output). `compileOnly(libs.jspecify)` stays (the AST records' `@Nullable` doc-comment
  field needs it).
- **The `:recordbuf-codegen` test suite** (`ClassSourceTest`, `JavaSourceTest`,
  `SourceRendererJavadocTest`, `TypeCollectorTest`) is itself a *consumer* of the soabase `*Builder`
  surface and must be migrated to the hand-written builders; its assertions are the **behavior-preservation
  oracle** for phases one + two -- they must stay green unchanged in intent.

## Requirements

> **Conventions for this block.** Each requirement is a "why + what" with an observable outcome, not a
> node signature or builder field layout (the executor designs those). "In scope" items are the
> behavioral / structural claims that must hold; "Out of scope" lists the deliberate boundaries. R-labels
> that collide with 002's R1–R22 refer to those; new requirements here get `A#` (API) / `B#` (body)
> labels to keep them distinct.

### A — Consumer-facing builder + modifier surface (phase one)

**A1.** The 8 `@RecordBuilder`'d node records -- `Type.Record`/`Type.Class`/`Type.Enum`/`Type.Interface`
and `Member.Field`/`Member.EnumConstant`/`Member.Method`/`Member.Constructor` -- drop `@RecordBuilder`
and gain a **hand-written, ergonomic builder**. The soabase `record_builder.core` /
`record_builder.processor` dependency is removed from `:recordbuf-codegen`'s `build.gradle.kts`. (The
`Member.StaticBlock` + `Member.Nested` records already have no `@RecordBuilder`; they may gain a
builder for consistency, but are not required to.)
*Why:* A generated uniform builder cannot express the ergonomic conveniences the emitter (and future
consumers) want, and soabase is an external dep we'd rather not carry on a library whose public API is
the product. Hand-written builders are a small, self-owned surface.
*Observable outcome:* `./gradlew build` green with no `io.soabase` references in
`codegen/build.gradle.kts` / the node records; the generated `*Builder` types no longer exist; the
emitter constructs nodes via the hand-written builders in place of the soabase `*Builder` fluents
(`TypeClassBuilder.builder()...build()` and peers) -- uniformly across all six mappings; there is no
generator-local builder layer to consolidate.

**A2.** Each hand-written builder provides **sensible defaults** (no raw "must-pass-a-boolean" for
things that default off) and **fluent / named setters that read as intent**: per the user's 2026-11-10
refinement, the **visibility dimension is a single `Visibility` enum + a `visibility(Visibility)`
setter** (where `Visibility` = `PUBLIC` / `PROTECTED` / `PACKAGE_PRIVATE` / `PRIVATE` — those are the four
visibility *keywords*; `default` and `abstract` are *method-modifier* keywords, not visibilities, so they
stay orthogonal flags), plus **named flag toggles** `static()` / `final()` / `sealed()` / `abstract_()` /
`default_()` / `deprecated()`, each setting its boolean to `true` (a bare `isX(true)` being the anti-
pattern this replaces), and collection **accumulators** such as `field(x)` / `member(x)` / `extends(x)` /
`implements(x)` that *append* to the underlying collection, plus batch helpers `fields(List<…>)` /
`members(List<…>)`.
The concrete method set is **YAGNI-guarded**: every convenience is justified by a current or clearly
near-term emitter need and documented as such -- **no full overload matrix is built**.
*Why:* The user named `final()` (default off, no bare boolean) and list/map/set accumulators as the
point of hand-written builders; this is the ergonomic improvement over soabase's bare surface. YAGNI-
guarding keeps the surface small + maintainable, honoring the "scope is what's needed now" side of the
forward-looking posture.
*Observable outcome:* A representative emitter path (pick the WKT-native record path from M4-C1 as the
demonstration) reads as builder-fluent calls with named toggles + accumulators, no `isX(true)` raw
booleans and no pre-assembled `List.of(...)` for collection fields.

**A3.** The `Modifiers` type is reshaped from `record Modifiers(String prefix)` + `Modifiers.of(String...)`
into a **structured modifier model**, guided by the user's 2026-11-10 refinement: a **single
`Visibility` enum** — `PUBLIC` / `PROTECTED` / `PACKAGE_PRIVATE` / `PRIVATE` — set on the builder via a
`visibility(Visibility)` setter (replacing the per-visibility `public_()`/`private_()`/`protected_()`
toggles with one enum setter; `default` and `abstract` are *method-modifier* keywords, not visibilities,
so they stay orthogonal flags), plus flag fields for `static` / `final` / `sealed`, and the orthogonal
**method-modifier flags** `abstract` / `default` / `@Deprecated` (the `deprecated`/`abstract` ones are
currently the separate `deprecated` / `Method.abstractDecl` booleans; the executor decides whether to
subsume those into the modifier model or keep them separate) -- either is acceptable so long as the
emitter stops writing modifier strings).
The renderer joins the structured modifiers in a canonical keyword order (the existing renderer
contract -- "modifiers in keyword order" per `Type.mods()` -- is preserved). The known `Modifiers.of(
"public", "static")` **space-delimited quirk at `RecordSourceMapping:162` is fixed** as part of this
(it currently produces a single `"public static"` keyword rather than two).
*Why:* `Modifiers.of("public", "static", "final")` is a *string* the renderer has to re-join +
a magic-string surface; a structured modifier both models intent (visibility + flags) and removes the
re-join. Subsuming `deprecated` / `abstract` into the modifier model (if the executor deems it cleaner)
unifies one more "modality" concept.
*Observable outcome:* No `Modifiers.of("...")` call remains in either source trees; the emitter sets
modifiers via the structured model + named toggles; the emitted output is keyword-order-equivalent to
today's (behavior-preserving) with the `RecordSourceMapping:162` quirk resolved to split `public`/`static`.

### B — Body AST (phase two, the closed idiom set)

**B1.** Flat-body leaves (`Member.Method.body` / `Member.Constructor.body` /
`Member.StaticBlock.body`, currently `List<Line>`) and `Member.EnumConstant`'s ctor-args expression
lift into a **small, extensible closed idiom set** of structured statements + recurring expression
shapes, that the renderer lays out recursively -- owning **indentation level, brace-balancing, and
line-joining** -- with a **raw text-fragment** node as the explicit escape for irregular idioms that
do not cleanly fit. The body of `Method`/`Constructor`/`StaticBlock` becomes a `List<Statement>` (or
the executor's chosen root), where `Statement` is the user's "sealed type hierarchy" over the recurring
idioms and `Expression` covers the expression shapes the mappings actually emit.
*Why:* The user's central aim -- "remove the need to keep track of indent in lines" + "reduce string
concatenation and magic strings" -- is *only* achievable by lifting control flow into a structure the
renderer lays out. A flat `List<Line>` cannot own indent structurally; only a statement AST can.
*Observable outcome:* A body that today is `Line.of("if (...")` / `Line.of("  ...")` / `Line.of("}")`
with hand-chosen `"    "`/`"  "` indents becomes a structured `ifStatement`/`block`/`…` the renderer
renders into the same source lines -- with the indent no longer hand-joined at the emitter.

**B2.** The **closed idiom set** is the finite, recurring control-flow + expression shapes the six
mappings actually emit today (the executor confirms the exact set by reading the mappings, but the
known baseline is): `if` (with optional `else`/`else-if` chain), `for` / `foreach` (including
`for (var _e : x.entrySet())` and indexed forms), `switch`-with-`->` (the oneof `switch`-with-`case
... -> ... default -> ...`), `return` (`return expr;` / `return;`), `throw`, simple
`var x = expr;` / `x = expr;` **assignment**, `field-access` / **call** / `new X(...)` / **literal** /
**parenthesized** / simple **ternary**/**conditional**, plus a **raw text-fragment** node for the
rare idiom that does not fit. The set is **extensible**: adding a new idiom later is a new
`Statement`/`Expression` case the renderer learns, not a rewrite -- the raw-fragment escape means a
generator can *always* emit an irregular body via a fragment without a new AST node. "Zero magic
strings" is **asymptotic, not absolute**: some irregular idiom stays a raw fragment (the accepted
residual).
*Why:* The closed-idiom-over-full-tree decision (settled in brainstorm; the "closed idiom set" option)
holds cost bounded because the emitted surface is finite and known; a full expression/parse tree is
overkill + unbounded cost. The extensible-framing (forward-looking) is delivered by the raw-fragment
escape, which also makes the "closed" set a *design choice* rather than a *restriction*.
*Observable outcome:* Six mappings' bodies (read: emitted) use the closed set + fragments; the heaviest
mapping (`InteropSourceMapping`) demonstrates the lift; no `Line.of("    ")`/`Line.of("  ")`/
`Line.of("}")` manual-indent strings remain on a migrated path (the renderer's join owns indent).

**B3.** The renderer (`JavaRenderer` or its renamed successor) **lays out the statement + expression
AST recursively**, taking ownership of (a) **indentation** (each nested block increments the indent
level; the renderer joins it), (b) **brace-balancing** (a `block`/`method body`/`if body`/`for
body`/`switch body` is a *structured* node with explicit open/close, not hand-joined `"{"`/`"}"`
`Line`s), and (c) **line-joining** (the renderer owns how a statement list joins -- newline or
semicolon-per-statement, with the trailing semicolon rule the emitter's current `Line` contract encodes
for `if/for/switch` "render the body lines verbatim" statements). **Existing renderer contracts hold
unchanged:** M9 (Javadoc + `@Deprecated` rendering on type/member lines), M10 (generated record
builders), M11 (protobuf-java interop) -- the output is **behavior-preserving and format-clean**, not
byte-identical.
*Why:* This is where the "remove indent bookkeeping" + "reduce magic strings" payoff actually lands --
the renderer is where indent/brace/line-join logic must live, not the emitter.
*Observable outcome:* `JavaRenderer` (or successor) renders a `Statement`/`Expression` tree to source
lines with indent + braces + joins owned by the renderer; the M9/M10/M11 invariant tests
(`ClassSourceTest`/`JavaSourceTest`/`SourceRendererJavadocTest`) stay green in intent.

**B4.** The **raw text-fragment** node is the explicit, documented escape. It is *only* reachable when
an idiom does not fit the closed set -- it is never used as a "I don't want to model this" shortcut
for a *known-recurring* idiom. (The executor documents the raw-fragment node's contract + which
irregular idioms (if any) currently force it, so future generators know the escape's scope.) It carries
verbatim source text (no indent bookkeeping at the emitter for it, but the renderer still renders it
inside the block's indent level as today's `Line.of` does for a verbatim statement).
*Why:* The forward-looking "extensible" posture is delivered by the escape -- it means a new generator
can *always* emit an irregular body without a new AST node, so the closed set is a design choice, not
a restriction. It also bounds the "zero magic strings" claim to asymptotic: the escape is where the
rare irregular idiom lives.
*Observable outcome:* Every use of the raw-fragment node is justified + documented; no *known-
recurring* idiom is forced into a fragment (the six mappings' bodies are fully modeled by the closed
set at migration end).

### C — Migration completeness + behavior preservation

**C1.** **All consumers migrate in scope, no partial migration:** the six generator source-mappings
(`RecordSourceMapping`, `EnumSourceMapping`, `WktSourceMapping`, `CodecSourceMapping`,
`AvroConverterSourceMapping`, `InteropSourceMapping`), the `:recordbuf-codegen` test sources
(`ClassSourceTest`, `JavaSourceTest`, `SourceRendererJavadocTest`, `TypeCollectorTest`), and the
dispatch entry (`CodeGenerator`/`Main`, which route to the mappings) are all on the soabase
`*Builder` surface **uniformly today** (the mappings import `TypeClassBuilder`/`TypeEnumBuilder`/
`TypeInterfaceBuilder`/`TypeRecordBuilder` + the `Member*Builder` types -- there is *no* generator-
local builder layer to consolidate), and all migrate to the new hand-written builders by plan end. No
consumer is left on the old surface after a phase lands green.
*Why:* A half-migrated consumer API is the classic "two styles alive at once" trap -- the whole point
of this work is that there is *one* API shape at the end, not two.
*Observable outcome:* No consumer source (mappings or test) references the old soabase `*Builder`
classes, `Modifiers.of("...")`, flat-body `Line.of(...)`; every
consumer reads via the new builders + structured modifiers (+ body AST by phase two end).

**C2.** **Behavior-preservation is the migration's gate.** After each phase lands, the emitted `.rec`
source is **behavior-preserving and format-clean** -- same imports/types/identifiers/control-flow/field
order/semantics as the current output, modulo the format-clean latitude M4 already grants. The
invariant suite (`:recordbuf-codegen`'s `ClassSourceTest`/`JavaSourceTest`/`SourceRendererJavadocTest`/
`TypeCollectorTest` + the fixtures/Avro round-trip gate + the source-of-truth compile gate from
M4-C2b-6) stays green. The emitted source for a fixture (e.g. `greeter.proto` with its `Kind` enum,
map fields, oneof, WKT usage) is **semantically identical** to today's output.
*Why:* This is the product contract (R3 "HOW not WHAT"; "faithful by construction, no behavior change")
-- the work is unobservable in the emitted `.rec` surface.
*Observable outcome:* M4-C2b-6 + M9 + M10 + M11 invariant gates green; the `./gradlew build` (compile +
`spotlessCheck` + `checkstyle` + NullAway) stays green; the source-of-truth compile gate compiles the
newly-generated `.rec` output; the round-trip parity holds.

**C3.** **Soabase `@RecordBuilder` is removed from `:recordbuf-codegen` only.** The
`compileOnly(libs.record_builder.core)` + `annotationProcessor(libs.record_builder.processor)`
wiring in `codegen/build.gradle.kts` is dropped. **The generator's internal model records
(`:recordbuf-generator`) + `:recordbuf-runtime` stay on soabase `@RecordBuilder` -- explicitly out of
scope here**, a known follow-up that reuses this same hand-written-pattern from this slice.
*Why:* The user scoped this work to the codegen AST; the generator model-records are a separate,
later concern (their builders are only used by generator code, so they don't affect the emitted
`.rec` surface + don't block any forward-looking reuse).
*Observable outcome:* `codegen/build.gradle.kts` has no soabase dependency; the generator's
`:recordbuf-runtime`/`:recordbuf-generator` build still uses `record_builder.core` (unchanged); a
future follow-up slices that migration.

**C4.** **Module positioning is preserved, not widened.** `:recordbuf-codegen` stays
`java-library`, co-versioned `bijnagte.recordbuf:0.0.1`, decoupled from recordbuf-specific concerns
(no references to `:recordbuf-generator` / `:recordbuf-runtime` / `:recordbuf-options` / `:recordbuf-avro`
in main scope; those stay test-scope only). **No `maven-publish` wiring is added now** ("publish when
ready" is a later step). **The new API is kept decoupled + extensible + additive-friendly** for future
code-generation projects -- that is a *design stance* the executor holds for the new nodes + builders,
not a scope expansion.
*Why:* Forward-looking posture is a *constraint on how this is designed* (the new API must be clean
+ decoupled + extensible), not a *scope expansion* (we don't add forward-looking features or widen
the module to publishable-now).
*Observable outcome:* The new API's public surface has no recordbuf-specific coupling; the idiom set
is open for extension via the raw-fragment escape; no new module dependencies or publish config are
added.

### D — Phasing + entry

**D1.** The work is **one single, internally-phased change**, not one big bang. **Milestone 1:**
verify M4-C2b-6 is green -- it is already shipped (git `86b5058`; 002 §0 "make the `:recordbuf-codegen`
test suite compile green + wire the source-of-truth compile gate"), so its gate is the verified entry
checkpoint, not a task to finish. **Phase one** (on top of M4-C2b-6):
A1 + A2 + A3 (hand-written builders + structured modifiers + consumer migration). **Phase two:** B1 +
B2 + B3 + B4 (the closed-idiom body AST + renderer layout + raw-fragment escape + consumer migration).
Each phase is independently buildable + verifiable against the invariant gate; no work in scope
necessarily blocks a phase from landing green.
*Why:* An internal phasing respects the brainstorm's "one coordinated effort" decision while giving
the executor a checkpoint (green M4-C2b-6, green phase one, green phase two) so a regression on a
mid-phase never blocks the whole.
*Observable outcome:* Each phase lands with `./gradlew build` green + the invariant gate passing;
the executor logs a checkpoint per phase.

## Concrete Design Decisions (executor-resolved; these make the plan implementation-ready)

The "executor decides" items below are closed here, grounded in the live
`codegen/src/main/.../source` package as of this plan. Any decision a heavier unit outgrows is a
tracked KTD (KTD1) recorded in `docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md`.

**DEC-1 — Structured `Modifiers` model + canonical render (closes A3 + the modifier open item).**
Replace `record Modifiers(String value)` with the structured record. The user's 2026-11-10 refinement
*pins the visibility dimension* (a `Visibility` enum + `visibility(Visibility)` setter); the decision
closes the **remaining** open question (how the orthogonal flags render on the builder), grounded in
the census -- the six modifier tokens that appear as a joined string today are exactly
`public/private/static/final/sealed/default` across all six mappings (no `protected`/`abstract`/
package-private string appears), so:
```java
public record Modifiers(
    Visibility visibility,           // null == no visibility keyword (package-private default)
    boolean isStatic,
    boolean isFinal,
    boolean isSealed,
    boolean isDefault) {
  public enum Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }
  /** Canonical Java keyword order: [visibility] static final sealed default. */
  public String render() { /* join set tokens in fixed order; none() -> "" */ }
}
```
- `@Deprecated`/`abstractDecl`/`override`/`permits`/`extends` are **kept at their existing separate
  locations** on the Type/Member records -- DECISION: resolve the "fold into the model or keep
  separate" open item by *keeping them separate* (YAGNI: they're already structured + orthogonal and
  none is part of the six-token string surface today; folding would bloat the model and touch the
  records that don't need it).
- Render is canonical keyword order `[visibility] static final sealed default`; `Modifiers.none()`
  renders `""`. The lone space-join quirk `Modifiers.of("public static")` (`RecordSourceMapping` ~162)
  becomes `Modifiers(PUBLIC, isStatic=true, ...)` -> "public static" structured, with no pre-joined
  argument string anywhere (A2/A3: "no `Modifiers.of(...)` call site").
- No raw/escape modifier list initially (the six tokens cover 100% of use -- YAGNI). If a seventh
  token ever appears, add it as a typed flag + KTD, not a raw string (mirrors the idiom escape).
- `SourceRenderer` renders the modifier via `m.mods().render()` (replacing the single string-field
  render `" " + m.mods().value`); ~3 renderer call sites, preserving its indent/join/type-collection
  contract.

**DEC-2 — Hand-written builders + consumer migration + soabase dropped from `:recordbuf-codegen` (closes A1 + A2 + C1 + C3 + the phase-one migration-order open item).**
- Delete the eight `@RecordBuilder`/`With` surfaces from the codegen AST; hand-write, per family,
  a builder + fluent setters (a small shared broker for the repetitive getters is a KTD-tracked
  follow-up per KTD2, not a new dependency). The canonical record constructors are preserved so
  `new Type.Record(...)` / `new Member.Field(...)` callers still work during and after the migration.
- Each builder carries individual modifier toggles `public()/private()/static()/final()/sealed()/
  default()` (A2) that accumulate DEC-1's `Modifiers`, plus the existing `name()`/`body()`/
  `javadoc()`/`throws()`/`abstractDecl()`/`override()`/`deprecated()`/`permits()` setters. After
  migration, `Modifiers.of(...)` appears at **no** call site (A2/A3).
- Drop `io.soabase.record-builder:record-builder` from `:recordbuf-codegen/build.gradle.kts` (both
  `implementation` + `testImplementation`) at the same change that introduces the hand-written
  builders (C3). **soabase stays on the `generator/src/main/.../model` records** (`Message`, `Oneof`,
  `Field`, `Enum`, `Enum.Constant`, `ProtoFile`) -- explicitly out of scope; they're the generator
  module's model, not the codegen AST.
- Consumer migration is complete-in-unit: the six `*SourceMapping` + `CodeGenerator`/`Main` + the four
  `codegen` test sources move to the new builders in the same change; no consumer is left half-
  updated (C1).
- **Phase-one order:** (1) DEC-1 structured `Modifiers` (small, self-contained, unblocks the build),
  then (2) DEC-2 hand-written builders + consumer migration + soabase removal. Phase two starts from
  the green end of DEC-2.

**DEC-3 — Body layer: `List<Statement>` root + closed `Statement`/`Expression`/`Fragment` + light-first migration order (closes B1-B4 + the body-root open item + the closed-set open item + the phase-two migration-order open item).**
- Root shape: `Method`/`Constructor`/`StaticBlock` `body` changes from `List<Line>` to
  `List<Statement>` (B1). `Line` is the single leaf; the root `List<Statement>` sits above it and is
  rendered through `SourceRenderer`'s existing indent/join -- the renderer stays the home of
  indent/brace/paren/join (B2). "JavaRenderer (or successor)" resolves to **extend the existing
  `SourceRenderer`** with a body-layout pass (no new class -- the successor hedge closes to "extend").
- `Statement` (closed set = the Open Items idiom baseline, census-confirmed; `Fragment` is the
  escape/superset): `If(cond, then, else)`, `For(decl, cond, step, block)`,
  `Foreach(type, var, iterable, block)`, `While(cond, block)`, `Switch(selector, List<CaseClause>)`
  (`case e -> ...` / `default -> ...`), `Return(expr)`, `Throw(expr)`,
  `LocalVar(type, name, init)` (covers `var x = ...` + typed assignment),
  `ExprStatement(Expression)` (bare `b.add(x)` / `BY_NUMBER.put(...)` / `out.write*`),
  `Block(List<Statement>)`, `Comment(String)`, `Empty`, `Fragment(String)` (raw escape).
- `Expression` (closed set from the baseline's value-idioms): `Member(receiver, name)` (dotted chain,
  no parens -- `field.getTs`), `Call(receiver, List<Expression>)` (supplies the parens -- **the
  `WktAvroBridge.fromAvro(field.getTs())` fix from `complete-javawriter-to-source-ast-migration.md`:
  parens come from `Call`, never a raw string**), `New(type, args)`, `Literal(String)`,
  `Parens(inner)`, `Ternary(cond, a, b)`, `Cast(type, expr)`, `Binary(op, l, r)`,
  `FieldAccess(receiver, name)` (`this.x`), `Fragment(String)` (raw escape for genuinely-
  unmappable lines).
- Each `Statement` renders to a `List<Line>` via `SourceRenderer` (walking the root, owning
  indent/brace/paren/join). The `Fragment` escape (B4) emits verbatim under the renderer's indent/
  brace/join, is counted + logged per mapping, and trends to zero. The closed set is a *superset of
  current use* (verified per KTD3 -- assert it before landing), not a hard `permits` fence; a new
  idiom later = add a node + renderer case + fragment->node test (KTD2 -- the standard "migrate an
  escaped line" procedure, tracked + KTD'd, not a new unit in this plan).
- **Phase-two order: lightest first** -- `Enum` -> `Record` -> interface/`Wkt` -> then the heavier
  `Codec` / `Interop` / `AvroConverter` last -- per the Open Items recommendation. The Risks-note
  mitigation asserts the closed set is a *superset* of each mapping's uses *before* it lands, which
  holds regardless of order, so a heavy mapping that would force an escape is found pre-landing
  (not a last-mile compile break); each heavy mapping decomposes into independently buildable
  sub-units (KTD4).

**DEC-4 — `Member.EnumConstant.ctorArgs` stays `String` (closes the ctorArgs open item).**
Census: `ctorArgs` is a bare number `KIND_LEGACY(1)` or empty (bare constant) -- a literal that
*could* fit an `Expression`, but the marginal gain is not worth the surface now (YAGNI). Deferred
follow-up (a tracked unit later if a body ever emits a non-literal ctor arg); no unit in this plan
promotes it.

## Out of scope

- **Dropping soabase `@RecordBuilder` from the generator's internal model records (`:recordbuf-generator`
  / `:recordbuf-runtime`)** + removing soabase repo-wide -- a known follow-up that *reuses this same
  hand-written-builder pattern* from this slice. This work touches `:recordbuf-codegen`'s AST layer only
  (C3).
- **M9/M10/M11 -- the substantive follow-ups** from 002 §0 (Javadoc + `@Deprecated` into the AST;
  generated record builders in the emitted `.rec`; protobuf-java interop). Out of scope here; they
  *converge on* the cleaner API this work creates but are not delivered by this slice.
- **New emission behavior / features.** This is HOW-not-WHAT: the emitted `.rec` surface is unchanged
  modulo format-clean. No new proto constructs, no new WKT, no new mappings.
- **Full expression / parse tree.** The closed-idiom + raw-fragment-escape posture is the
  brainstorm-settled decision; a full tree is overkill + unbounded cost, out of scope.
- **Rewriting strings *outside* body emission** -- the Javadoc *text* (a `@Nullable String doc`
  field), the component *typing* in the WKT/record mappings, and any literal the user wants rendered
  as a string are not "magic strings" we chase here; the user's aim is *body-emission* string-concat
  / magic-string reduction (control flow + expression shapes), which the body AST (B1–B4) addresses.
- **Module-positioning change / `maven-publish` wiring.** Forward-looking is a design posture, not a
  scope expansion; publishing is a later step.
- **The generated code's NullAway-scope re-decisions.** NullAway is already `OnlyNullMarked=true` on
  the generated `.rec` (per `docs/plans/2026-11-04-001-feat-nullaway-generated-code-plan.md`); the
  new AST's emission of the same output must *continue* to satisfy that scope -- it is a carry, not a
  new decision. The executor may verify + lock in an existing invariant, not re-open the decision.

## Open Items and Assumptions -- all five are **resolved by the Concrete Design Decisions below (DEC-1...DEC-4)**; they remain as the settled brainstorm inputs that the decisions close

- **The exact closed idiom set is to be confirmed by the executor reading the six mappings** -- the
  baseline (if / for / foreach / switch-with-`->` / return / throw / assignment / field-access /
  call / new / literal / parenthesized / conditional / raw-fragment) is a known, *not exhaustive*
  starting point; the executor confirms the final set by enumerating what each mapping emits. The
  raw-fragment escape (B4) means any idiom that does not fit the confirmed set can still be emitted
  -- so the closed set must be a *superset* of the six mappings' current uses, not a guess.
- **The structured modifier's exact *flag* representation** -- per the user's 2026-11-10 refinement the
  **visibility dimension is pinned**: a `Visibility` enum + `visibility(Visibility)` setter (public /
  protected / package-private / private), with `static`/`final`/`sealed`/`abstract`/`default`/`@Deprecated`
  as orthogonal flags. The *open* question is only how the flags render on the builder -- a named toggle
  method (`static()`/`final()`) vs. a `boolean isStatic`/`isFinal` field, vs. a `record
  Modifiers(Visibility visibility, boolean isStatic, ...)` vs. a sealed `Modifier` + `List<Modifier>`,
  vs. folding `deprecated`/`abstract` into the model or keeping them separate -- the executor designs
  this; the requirement (A3) is that the `Modifiers.of("...")` string + the `deprecated`/`abstractDecl`
  booleans' modality *intent* is expressed structurally and joined in canonical keyword order.
- **Whether `Method` / `Constructor` / `StaticBlock` bodies become a `List<Statement>` root or the
  executor's chosen root** -- the requirement (B1/B3) is that the body is a *structured statement
  tree*, not a flat `List<Line>`; the concrete root + node names are the executor's design.
- **`Member.EnumConstant.ctorArgs` (a `String`)** -- whether this lifts to an `Expression` (so enum
  constant ctor args can carry a small expression) or stays a `String` -- the executor decides;
  known use is a bare number `KIND_LEGACY(1)` or empty (bare constant), which *could* fit a
  literal expression node.
- **Migration order within the six mappings** -- the executor picks a sequence (probably the lightest
  mappings first: record/enum/interface, then proto, then the heaviest interop/avro/codec last so
  the risk-concentrated ones are the last to land green) -- no cross-mapping dependency forces any
  particular order, so the executor's choice is free.
- **What "format-clean latitude" tolerates between phase one and phase two** -- a format-clean
  *reordering* of output (e.g. brace alignment) *is* granted (M4 stance), but the executor should
  log any such *format-only* delta for review (a *behavior* delta is out of scope; a *format* delta
  is in scope but must be logged).

## Implementation Units

Ordered; each is independently `./gradlew build`-green and verifiable. UIDs are `U<n>` (a fresh
plan). A heavy unit sub-splits via KTD4 into `U<n>.1`, `U<n>.2`, ... if it outgrows a single build.

---

**U0 — Entry checkpoint: verify M4-C2b-6 already green (verification only).**
- *Goal:* confirm the source-AST entry is green before phase one. M4-C2b-6 is **already shipped**
  (git `86b5058`); no code change -- a verification gate.
- *Deps:* none. *Files:* none (read-only check).
- *Approach:* `./gradlew :recordbuf-codegen:test --rerun-tasks`; confirm the four test sources
  (`ClassSourceTest`, `JavaSourceTest`, `SourceRendererJavadocTest`, `TypeCollectorTest`) pass + the
  source-of-truth compile gate holds.
- *Test scenarios:* (1) the four test sources pass after `--rerun-tasks`. (2) the source-of-truth
  compile gate compiles a generated fixture reference with the runtime on the classpath.
- *DoD:* green; recorded as the entry point for phase one.

**U1 — Structured `Modifiers` model + canonical render (DEC-1 / A3).**
- *Goal:* replace `record Modifiers(String value)` with the structured model of DEC-1; migrate the
  ~33 `Modifiers.of(...)` call sites to structured construction; remove the space-join quirk; render
  via `SourceRenderer` calling `.render()`.
- *Deps:* U0. *Files:* `codegen/.../source/Modifiers.java` (rewrite -> structured + `render()`),
  `codegen/.../source/SourceRenderer.java` (`mods().render()`), the six `*SourceMapping.java`
  (call-site migration).
- *Approach:* implement the structured record + `render()` first with unit tests, then migrate each
  `Modifiers.of(...)` call site (grep-verified count ~33) to structured construction, building green
  after each mapping.
- *Test scenarios:* (1) `Modifiers.none()` renders `""`. (2) `Modifiers(PUBLIC, static)` renders
  `"public static"`. (3) `Modifiers(PUBLIC, static, final)` renders `"public static final"`. (4) the
  legacy `Modifiers.of("public static")` quirk is gone; the same declaration renders identically to
  today. (5) `Modifiers(PUBLIC, sealed)` (the oneof sealed interface) renders `"public sealed"`.
  (6) a unit asserts canonical-order render for each of the six tokens + the combinations present in
  the codebase.
- *DoD:* `./gradlew build` green; no `Modifiers.of(` in source anywhere; `Modifiers.value` gone.

**U2 — Hand-written builders + consumer migration + soabase removal from codegen (DEC-2 / A1 + A2 + C1 + C3).**
- *Goal:* delete the eight `@RecordBuilder`/`With` surfaces; hand-write builders + fluent setters
  (with DEC-1's modifier toggles); migrate the six mappings + `CodeGenerator`/`Main` + the four test
  sources; drop soabase from `:recordbuf-codegen`.
- *Deps:* U1. *Files:* `codegen/.../source/{Type,Member,StaticBlock}.java` (drop `@RecordBuilder`),
  new `*Builder.java`/`*With.java` per family, the six `*SourceMapping.java`, `CodeGenerator.java`,
  `Main.java`, the four `codegen` test sources, `:recordbuf-codegen/build.gradle.kts` (drop soabase).
- *Approach:* introduce one hand-written builder per node record (preserving the canonical
  constructor), migrate consumers mapping-by-mapping (lightest first), each a buildable sub-unit,
  then verify the generator `model` soabase is untouched (its `Message/Oneof/Field/Enum/ProtoFile`
  build stays green on soabase -- C3 boundary).
- *Test scenarios:* (1) each of the four test sources builds + passes on the new builder fluent API
  (the invariant oracle -- intent unchanged, mechanically re-expressed). (2) `grep -rn io.soabase
  recordbuf-codegen/src --include=*.java | grep -v build/` is empty while `grep -rn io.soabase
  generator/src/main/.../model --include=*.java` still matches (soabase removed from codegen, present
  in generator model). (3) an end-to-end `sample.proto`/`greeter.proto` round-trip via the new
  builders renders byte-identical to the pre-refactor reference (behavior-preserving -- R14). (4) a
  builder with `public()/static()/final()/sealed()/default()` toggles renders the corresponding
  declaration header.
- *DoD:* `./gradlew build` green; no `io.soabase` import in `:recordbuf-codegen`; generator model
  still on soabase; all six mappings + dispatch + test sources migrated; output byte-identical.

**U3 — Body-layer scaffold: closed `Statement`/`Expression`/`Fragment` + renderer body pass (DEC-3 / B1 + B2).**
- *Goal:* introduce the `Statement`/`Expression`/`CaseClause`/`Fragment` node set of DEC-3; change
  `Method`/`Constructor`/`StaticBlock` `body` from `List<Line>` to `List<Statement>`; add a
  `SourceRenderer` body-layout pass owning indent/brace/paren/join; initially wrap each current line
  as a `Statement`/`Fragment` so output is byte-identical.
- *Deps:* U2. *Files:* new `codegen/.../source/{Statement,Expression,CaseClause}.java` (or a single
  `Body` sealed interface -- executor's call, KTD'd if it lands as one file),
  `codegen/.../source/{Member,StaticBlock}.java` (body type), `SourceRenderer.java` (layout pass).
- *Approach:* define the sealed sets + a `render(Indent) -> List<Line>` contract; wrap today's flat
  bodies as `Fragment` (behavior-preserving by construction); land the `SourceRenderer` layout pass
  that walks the root `List<Statement>` and owns the brace/paren/join; prove `ClassSourceTest`/
  `JavaSourceTest` still pass output-for-output.
- *Test scenarios:* (1) `ClassSourceTest` / `JavaSourceTest` pass with bodies as fragment-wrapped
  statements (byte-identical output). (2) a `Block`/`If`/`For`/`While`/`Switch` statement renders
  with correct indent + braces (brace/paren/join owned by the renderer, not string-concatenated in
  the mapping). (3) the oneof sealed interface's `Switch(selector, caseClauses)` renders the switch
  correctly.
- *DoD:* `./gradlew build` green; `Method/Constructor/StaticBlock.body` is `List<Statement>`; the
  renderer owns brace/paren/join; output byte-identical.

**U4 — Idiom coverage for the mappings + raw-fragment escape (DEC-3 / B3; `Enum` -> `Record` -> `Interop`/`Codec`/`AvroConverter`).**
- *Goal:* replace `Fragment` bodies with real closed-idiom `Statement`/`Expression` nodes per
  mapping, light-first; keep only genuinely-unmappable lines as `Fragment`; add the `WktAvroBridge`
  two-arg-call parens acceptance test.
- *Deps:* U3. *Files:* the six `*SourceMapping.java` (body emission -> `Statement`/`Expression`
  trees), a new/expanded per-mapping test scenario, a doc/const listing the closed set + the logged
  `Fragment` count.
- *Approach:* migrate mapping-by-mapping (KTD4 sub-units per heavy mapping); after each, that
  mapping's `Fragment` count drops; assert the closed set is a *superset* of the mapping's uses
  before landing (KTD3). The `WktAvroBridge.fromAvro(field.getTs())` idiom is built as
  `Call(Member("WktAvroBridge", "fromAvro"), [ ... ])` so the parens come from `Call` (the
  fix in `complete-javawriter-to-source-ast-migration.md`).
- *Test scenarios:* (1) `WktAvroBridge.fromAvro(field.getTs())` renders with the correct call parens
  -- the historical bug (a missing inner `()` rendering an implicit method reference / compile error)
  is a regression guard. (2) the `for`/`while`/`switch`/`default`/`var x = ...`/`return`/`this.x`/
  `new X(...)` idioms render correctly inside the codec's `encode`/`decode`. (3) the `oneof`/`WKT`/
  `unknown-field-carrier` bodies (the heaviest) render via real `Statement`/`Expression` idioms. (4)
  each mapping's `Fragment` count is logged + asserted non-increasing; a fresh-project "new idiom"
  is added by a new `Statement`/`Expression` node + renderer case + fragment->node test (the escape
  procedure, KTD2).
- *DoD:* `./gradlew build` green; every mapping's body is closed-idiom + minimal `Fragment`; the
  `WktAvroBridge` parens test is green; output byte-identical.

**U5 — Ergonomic hardening + clean-build + reference-equivalence + escape demonstration (B4).**
- *Goal:* confirm the API is ergonomic enough that a fresh code-generation project could build on it;
  a fragment->node "new idiom" procedure exists + is exercised; a clean/rerun build is green + the
  generated `.rec` output is reference-equivalent.
- *Deps:* U4. *Files:* the `codegen` test sources (a "fresh project can build" scenario), a
  doc/comment for the closed-set + escape procedure.
- *Approach:* add a "fresh-project" acceptance test that builds a small generated type purely through
  the new builder/idiom API (no codegen-specific knowledge); run a clean build
  (`rm -rf */build/ */bin/` then `./gradlew build --rerun-tasks` -- the clean-build rule from
  `complete-javawriter-to-source-ast-migration.md` -- so a stale `bin/` class cannot mask a source-
  level defect) and compare generated `.rec` output to the pre-refactor reference (
  `proto-codegen-wire-compat-interop-harness` baseline).
- *Test scenarios:* (1) a fresh-project test builds a record + a codec + a javadoc purely via the
  public builder/idiom API. (2) a clean/rerun build is green and the generated `.rec` output is byte-
  identical to the reference (modulo `Line` cosmetic alignment -- the format-latitude per R14). (3)
  the fragment->node escape procedure renders a new `Statement`/`Expression` + its test passes.
- *DoD:* `./gradlew build --rerun-tasks` green after a clean; reference-equivalent output; the new-
  idiom escape procedure is green; no raw-string method bodies remain except the logged `Fragment`
  residual.

## Risks

- **Migration risk concentrates in the heaviest mappings** (`InteropSourceMapping` first, then
  `AvroConverterSourceMapping` / `CodecSourceMapping`). The closed idiom set must hold there or
  those bodies force raw-fragment escape, which is a *visible, bounded* fallback (not a dead end
  -- the escape exists exactly for that) but degrades the "zero magic strings" goal for those paths.
  *Mitigation:* the executor confirms the closed set is a superset of each mapping's current uses
  *before* landing; if a mapping forces an escape, it is logged + the set extended (not a "we gave up
  on that idiom" decision, an "extend the set" one).
- **The soabase-to-hand-written migration touches the `:recordbuf-codegen` test sources + all six
  mappings** -- a wide surface where a missed call site means a half-migration (an old + new API
  shape both alive). *Mitigation:* C1 requires no consumer survives any phase green;
  `grep -rn 'soabase\|Modifiers.of(' codegen generator --include=*.java | grep -v build/` returns no
  hits by phase one end.
- **The renderer's "indent each line to the enclosing block's indent" contract** must be *replaced*
  by a recursive layout that owns indent structurally, *without* changing the emitted output. That is a
  non-trivial renderer change; a bug in the renderer is a bug across *every* body. *Mitigation:* the
  invariant test suite is the oracle -- if it stays green + the source-of-truth compile gate compiles
  the newly-generated `.rec`, the renderer is correct enough; a regression shows up as a compile
  failure or a test failure immediately.
- **The `RecordSourceMapping:162` `Modifiers.of("public", "static")` quirk** (a single space-
  delimited string rather than two keywords) is *today a latent bug masked by the renderer's "the
  caller has already joined" contract* -- it happens to render correctly because the renderer just
  re-joins whatever string it gets. Fixing it explicitly (A3 requires the split form) may *change* the
  rendered output for that one line -- but that's a *bug fix* (the split was always intent), and the
  invariant test suite must catch if it wasn't actually a *format-only* delta. *Mitigation:* verify the
  rendered output for the affected line is the *same* as today, or if it *differs*, it is a fix to
  log.
- **Forward-looking decoupling (C4) is a *design stance on the new API*, not a *scope expansion*.**
  If the executor accidentally widens the surface (adding forward-looking features this plan did not
  ask for, or widening module positioning), that's a regression against scope. *Mitigation:* C4
  explicitly states no positioning change; the executor logs any "would-be" forward-looking idea as a
  *deferred follow-up* rather than in-slice.

## Planning Contract

Applies the orchestration + recovery conventions from KTD2/KTD4 to this slice.

- **Orchestration:** a single executor (ce-work) runs U0 -> U5 in order. `subagent` is available but
  **not required** here: every phase (and each heavy mapping's sub-units, KTD4) is independently
  `./gradlew build`-green, so one executor can sequence them without fan-out. Fan-out
  (parallel children) is a *tracked optimization* iff two mappings are genuinely independent and safe
  to build in parallel -- recorded as a KTD, not assumed.
- **Parallelism / writes:** one writer per `cwd`/worktree. The `:recordbuf-codegen` `source` package
  and the six `*SourceMapping` are a shared write target; no two children write them concurrently.
- **Handoffs:** the `codegen` source AST is the source-of-truth (M4-C2b-6, U0). Each unit's DoD is a
  `./gradlew build` green at its own end; the whole slice is `./gradlew build --rerun-tasks` green
  after a clean output-dir wipe (the clean-build rule) + reference-equivalent generated output.
- **Recovery on failure:** per-unit green (KTD4) means a failing heavy mapping stops at its own sub-
  unit, not the whole slice; the `Fragment` escape (B4/KTD2) means a genuinely-unmappable line stops
  a sub-unit (recorded + KTD'd), not a hard compile break. A regression is caught by the invariant
  gate -- the four `codegen` test sources stay green, their assertions updated **mechanically** to the
  new builders only (R13: intent unchanged, no behavior change asserted).
- **DoD (whole slice, executable):** (1) every source `Method`/`Constructor`/`StaticBlock` `body` is
  a `List<Statement>` of closed-idiom `Statement`/`Expression` nodes, not a flat `List<Line>`; (2)
  every one of the eight AST node records carries a hand-written builder with individual modifier
  toggles -- no `Modifiers.of(...)` string anywhere, `Modifiers` structured (DEC-1/DEC-2); (3) soabase
  `@RecordBuilder`/`@With` is gone from the codegen AST and its import absent from
  `:recordbuf-codegen`'s classpath + sources, while remaining on the generator `model` records
  (DEC-2/C3); (4) the `SourceRenderer` body-layout pass owns indent/brace/paren/join -- no brace / indent
  string bookkeeping in the body pass; (5) the four `codegen` test sources stay green (invariant
  oracle; mechanically updated to the new builders, same intent -- R13); (6) the `:recordbuf-codegen`
  test suite is green after `clean` + `--rerun-tasks` (the source-of-truth entry, U0); (7) output is
  behavior-preserving vs the pre-refactor reference (byte-identical, R14); (8) the new API is decoupled
  -- the package-info + `Expression`/`Statement`/`Modifiers` use no `protoc`/`model` type (D3).
  Each unit's *DoD* above is the per-unit execution of these.

## Done means

- M4-C2b-6 is green (the `:recordbuf-codegen` test suite compiles + the source-of-truth compile gate
  passes) -- already shipped (git `86b5058`); **verify** it as the entry checkpoint before phase one.
- Phase one is landed: all 8 node records carry a hand-written, YAGNI-guarded, ergonomic builder
  (A1+A2); `Modifiers` is a structured model (A3); no `Modifiers.of("...")` remains in source trees;
  soabase is removed from `:recordbuf-codegen` (C3); all six mappings + the `:recordbuf-codegen` test sources + the CodeGenerator/Main dispatch entry are migrated (C1); `./gradlew build` + the invariant gate is green; output
  is behavior-preserving + format-clean.
- Phase two is landed: the closed-idiom body AST exists (B1–B4); `JavaRenderer` (or successor)
  recursively lays out the body tree, owning indent/brace/join; all six mappings' bodies migrate to
  the new AST; no `Line.of("    ")`/`Line.of("  ")`/`Line.of("}")` manual-indent strings remain on a
  migrated path (the renderer owns indent structurally); the invariant gate + round-trip parity +
  source-of-truth compile gate all stay green.
- The new API is decoupled + extensible + additive-friendly for future code-generation projects (C4),
  without widening scope.
- The `docs/solutions/` learning (if the executor deems there's a durable one -- e.g. "closed-idiom
  body AST for a codegen module") is captured via `ce-compound` at task end.

## Verification

- `./gradlew build` (compile + `spotlessCheck` + `checkstyle` + NullAway) is green after each phase.
- M4-C2b-6's own gate (`:recordbuf-codegen` test-suite green + source-of-truth compile gate) passes as
  the entry checkpoint.
- The `:recordbuf-codegen` test suite (`ClassSourceTest`, `JavaSourceTest`,
  `SourceRendererJavadocTest`, `TypeCollectorTest`) stays green in intent -- the assertions are the
  invariant oracle; they may be *updated mechanically* to the new builders (same intent, no *behavior*
  change asserted) but not weakened.
- The round-trip parity gate (fixtures + Avro, per 002 M11) stays green -- the emitted `.rec` output
  must still round-trip encode/decode/convert identically.
- The source-of-truth compile gate compiles the newly-generated `.rec` output for a WKT-using fixture
  (per M4-C1 `greeter.proto` + its `Kind` enum + map + oneof + WKT usage) after each phase.
- `grep -rn 'soabase\|Modifiers.of(' codegen generator --include=*.java | grep -v build/` returns no
  hits by phase one end; the same + `new Method(\|new CodeBlock(\|new TypeClass(` returns no hits on
  a *migrated* path by phase two end.
- Any forward-looking / "would-be" idea the executor surfaces is logged as a deferred follow-up
  item (not in-slice) -- e.g. "soabase removed from generator model records (known follow-up)"
  + "publish-ready module wiring (later step)".

## Inherited Context cross-references (summary)

This plan inherits from:
- `docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md` -- the source plan that created the
  `:recordbuf-codegen` module + migrated the writer (M0–M14, M4 phased M4-C1 … M4-C2b). M4-C2b-6
  is this plan's entry; M9/M10/M11 are the *substantive* follow-ups (out of scope here; they
  *converge on* the cleaner API this work creates).
- `docs/plans/2026-11-07-001-feat-well-known-types-native-plan.md` -- M4-C1's WKT-native emit
  (the 10 WKT records + 20 reference-only stubs emit via the same mappings this work reshapes; the
  closed idiom set must remain a superset).
- `docs/plans/2026-11-04-001-feat-nullaway-generated-code-plan.md` -- NullAway's `OnlyNullMarked=true`
  on generated `.rec` is a carry, not a re-decision; the new AST's emission must continue to satisfy
  that scope.
- `docs/plans/2026-11-06-002-feat-add-checkstyle-plan.md` -- the checkstyle quality gate; the new
  hand-written builders become gate-covered real code (Spotless + Checkstyle + NullAway), a surface
  the soabase-generated builders were explicitly *excluded* from.

## Inherited Context -- additional cross-references (added when this plan was enriched to implementation-ready)

- `docs/solutions/design-patterns/complete-javawriter-to-source-ast-migration.md` -- the *immediate*
  ancestor (the M4-C2b-6 / M4-C2d-13 close-out). Carries the `WktAvroBridge.fromAvro(field.getTs())`
  two-arg-call-parens pitfall (the `Call`/`Fragment` acceptance idiom in U4), the clean-build rule
  (`--rerun-tasks`, wipe non-`build/` output dirs so a stale class cannot mask a source-level defect),
  and the "port the codec before deleting the fallback" ordering. Read before implementing U4/U5.
- `docs/solutions/design-patterns/native-well-known-type-generation.md` -- the WKT converter bodies
  `Interop`/`Wkt` migrate in U3/U4 (the WKT dispatch idiom).
- `docs/solutions/design-patterns/proto-codegen-wire-compat-interop-harness.md` -- the byte-level
  wire-compat baseline that is R4/R14's behavior-preservation oracle (the "reference" in U5).
- `docs/solutions/design-patterns/unknown-field-capture-reemit.md` -- the unknown-field-carrier
  encode/decode idiom migrated in U4 (`:recordbuf-fixtures`).
- `docs/solutions/tooling-decisions/{enable-unnecessarily-fully-qualified-codegen-short-names, enable-nullaway-null-checks, record-component-javadoc-via-param}.md`
  -- the codegen AST's tooling posture the refactor must *preserve*: short-names, NullAway at error
  severity in `:recordbuf-codegen`, and the record-component Javadoc-via-`@param` idiom (the `@Nullable`
  doc-comment model on the AST nodes is kept per A1's note on `Optional` / `@Nullable`).

## Brainstorm source notes

This plan's scope was settled conversationally on 2026-11-10 (a manual, not ce-ideate-driven,
brainstorm). Key decisions + why:

- **Codegen AST only** (vs. full expression tree; vs. hybrid keep-`List<Line>`; vs. control-flow-
  only). The user chose the closed-idiom + escape posture, rejecting the full-tree (unbounded cost) +
  the hybrid (doesn't solve indent) + the control-flow-only (incomplete -- the expression shapes still
  stay as strings, which the user explicitly wants to reduce).
- **One internally-phased effort** (vs. two separate efforts; vs. one big bang). The user wants one
  coordinated change with a clean seam between declaration-layer (phase one: builders + modifiers) +
  body-layer (phase two: closed-idiom AST), not two efforts with a handoff, not one big diff.
- **YAGNI-guarded ergonomics, forward-looking posture** (vs. minimal current-only; vs. rich general-
  purpose library). The user's forward-looking ask ("I'll likely do more code-generation projects")
  is captured as a *design stance on the new API* (decoupled + extensible + additive-friendly), not a
  scope expansion.
- **Behavior-preserving, not byte-identical** (the M4 stance -- "forgiving on formatting, strict on
  behavior"). This is what *frees* the magic-string / indent-bookkeeping cut, since the change is
  unobservable in the emitted `.rec` surface.
