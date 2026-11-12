---
title: "Unified first-class annotation model on the generated-Java AST"
type: refactor
status: draft
date: 2026-11-12
depth: standard
sources:
  - "User conversation thread (2026-11-12 brainstorm, refined) — first-class annotation model, full unification scope, fully-uniform node surface, single Arg.of factory, AST-structure deferred"
  - "docs/plans/2026-11-12-003-refactor-ast-type-positions-plan.md (prerequisite AST-structuring pass — 003's Raw parameterization will migrate Annotation.type String→TypeRef later; this plan is the design-only prerequisite for it)"
  - "docs/plans/2026-11-12-002-refactor-type-ref-imports-plan.md (related — TypeRef + Import model; this plan's Annotation.type is a raw eager string today, the seam 003 later upgrades)"
---

# Sources

- **User conversation thread (2026-11-12 brainstorm, refined)** — first-class annotation model on the generated-Java AST: (R2) a single `Annotation` type with a `type` reference + an `Arg` list; (R3) `type` is *not* stored as a raw string but as a typed reference — design-only now, the AST-structure prerequisite deferred to a later pass; (R5) deprecation booleans `@Deprecated` on the record/class/interface/enum/component/enum-constant nodes become `Annotation` instances; (R6–R7) the raw `List<String>` annotation field and the ad-hoc `@Nullable` string-splicing all become `Annotation` instances; (R8) `SourceRenderer` emits every annotation through one shared path (one annotation per line) instead of per-annotation special cases; (R9) annotations resolve through the current eager import mechanism; (R10) re-baseline the fixtures; (R11) the new `Annotation` lives in the `codegen` module. The scope decision was **full unify** (`@Deprecated`, `@Override`, `@SuppressWarnings`, `@Nullable` all via `Annotation`) and **fully uniform** (the annotation list is on *every* node that can carry an annotation, including method/record-builder parameters and local variables). The `Arg` factory surface is a **single `Arg.of(name, values...)` with a nullable name** (one factory, name-or-positional). The AST-structure prerequisite (how `type` is *stored* as a structured reference rather than a raw string) is design-only now — deferred as a later pass; this plan captures the design intent without the AST change.
- **docs/plans/2026-11-12-003-refactor-ast-type-positions-plan.md** — *prerequisite* AST-structuring pass: `Identifier` (bare token / qualified) + a parameterized `Raw` escape-hatch whose typed placeholder args become `TypeRef`s, migrating the string-based type positions. Its Phase B **retires the two-pass render pipeline** and makes `Annotation.type` the first consumer of the `Raw`/`TypeRef` structure the AST-structuring pass introduces — so this plan's `Annotation.type` is a raw eager `String` today (R9), the seam that 003's `Raw` migration later upgrades. Land **003 first**, then this refactor; otherwise `Annotation.type` is string-based and 003's raw-string migration must also visit it.
- **docs/plans/2026-11-12-002-refactor-type-ref-imports-plan.md** — *related* TypeRef + Import model that 003 supersedes. Its R4 ("all type positions become `TypeRef`; `Annotation` participates so annotation types resolve through the collector") is this plan's forward pointer to 003. This plan's `Annotation` (type-reference + `Arg` list) is the *consumer* of the `TypeRef`/import machinery those docs design; that machinery is the deferred later pass.

# Unified First-class Annotation Model on the Generated-Java AST

## Overview

Today the generated-Java AST treats annotations as a first-class model concept, so the model is a typed `Annotation(reference, args)`, but the renderer emits each annotation through its own ad-hoc special-case — `deprecated`, `override`, an unrendered class `List<String>` field, and `@Nullable` spliced into a type string — and the annotation *type* is stored as a raw string, resolved eagerly. This refactor replaces that scattering with a single `Annotation` model (type-reference + `Arg` list) that *every* annotatable node carries uniformly, and a single render path in `SourceRenderer` that emits them one-per-line — re-baselining the fixtures for the placement/form/ordering diffs. It is the design-only prerequisite for the AST-structuring migration (003) because 003's parameterized `Raw` makes `Annotation.type` its first consumer.

This is a `ce-unified-plan/v1` source: it is an **in-place enrichment to implementation-ready** of the 2026-11-12 brainstorm product contract (R1–R11, AE1–AE5 below, and the key decisions under "Key Decisions" are the Product Contract — unchanged, the source of truth). The implementation HOW (implementation units, file paths, sequencing, per-unit test scenarios, risks) is appended below and was grounded directly in the codebase in-session: the `codegen` source AST (`SourceRenderer`, `Type`, `Component`, `Method`, `EnumConstant`, `Param`, `LocalVar`), the mappers that produce annotations (`RecordSourceMapping`, `EnumSourceMapping`, `AvroConverterSourceMapping`), the generator snapshot tests, and the `:recordbuf-fixtures` generate/compile/EP/JUnit gate.

## Requirements

- R1. The source AST has a single `Annotation` model — an annotation is a reference (its type) plus a list of arguments, not a pre-rendered string.
- R2. `Annotation` carries a typed reference to its type and a list of `Arg`s; arguments have a single uniform shape (`Arg.of(name, values...)`, nullable `name`).
- R3. The annotation *type* is a typed reference, not a raw string — design intent only; the AST structure needed is deferred to a later pass.
- R4. Every node that can carry an annotation carries the annotation list (`record`, `class`, `interface`, `enum`, `component`, `enum-constant`, `method` including its `param`s, and local variables).
- R5. The `deprecated` booleans on the record, class, interface, enum, component, and enum-constant nodes become `Annotation.DEPRECATED` instances in that node's annotation list.
- R6. The raw `List<String>` annotation field on classes and interfaces becomes the same uniform annotation list.
- R7. The ad-hoc `@Nullable` that is currently spliced into type-expression strings becomes an `Annotation.NULLABLE` instance on the owning node's list; the type expression stays bare.
- R8. `SourceRenderer` emits a node's annotations through a single shared path — one annotation per line — instead of per-annotation special cases, and renders them before the node's javadoc.
- R9. Annotations resolve through the current eager import mechanism (`ci.use(...)` at render, no new render path); later (the AST-structuring pass / 003) the type-reference resolves through that pass's `Raw`/`TypeRef` structure.
- R10. The fixtures are re-baselined for the resulting placement/form/ordering diffs; no non-annotation output changes.
- R11. The new `Annotation` (and `Arg`) live in the `codegen` module (with the rest of the source AST — `Component`, `Type`, `SourceRenderer`, …), not the generator's model package.

## Acceptance Examples

- AE1. `record Point(@Deprecated long x, @Deprecated @Nullable long y = 0L, long z)` → three record-level annotations, each rendered on its own line before the record's javadoc, as `@Deprecated` instances (not booleans / spliced strings); `@Nullable` is an instance on `y`, leaving `y`'s type expression bare.
- AE2 — `@SuppressWarnings` with a single value renders `@X("Foo")`, and with multiple values renders `@X({"a", "b"})` as an array initializer (R2/R5/R6); a named argument renders as `@X(name = value)` unless the name is `value` (then positional).
- AE3. Every node that can carry annotations does.
- AE4. The fixtures are re-baselined: the diff is the annotation placement/form/ordering, and every previously-stable fixture is byte-for-byte unchanged aside from the annotation lines themselves.
- AE5. The `codegen` module compiles with the new model; the generator emits the annotation lines the way today's `@Deprecated`/`@Nullable`/`@SuppressWarnings` sites render — one annotation per line, before the javadoc — just assembled uniformly rather than by four separate code paths.

## Key Decisions

- One `Annotation` model; all four ad-hoc handling styles (`@Deprecated`, `@Override`, `@SuppressWarnings` via the class `List<String>`, `@Nullable` via type-string splicing) become instances of it. `@SuppressWarnings` is the one that needs `Arg`s (its value is an argument string).
- Fully uniform: the annotation list is on *every* annotatable node including method parameters and local variables — not just the four nodes that already carry it.
- `Arg` via a single `Arg.of(name, values...)`: nullable `name` collapses named and positional args into one factory; render is positional when the name is null or `"value"`, `name = value` otherwise.
- `Annotation.type` is a *typed reference* conceptually; the AST that stores it as structured form is **deferred to a later pass** — this plan is design-only for the reference, eager resolution for now.
- `@Nullable` migrates from a type-string splice to an `Annotation.NULLABLE` instance on the owning node; the type expression stays bare.
- Fixtures re-baselined; the diff is the annotation placement/form/ordering.
- `Annotation` lives in the `codegen` module (with the source AST).
- **Open threads (kept for the later pass, not this refinement):** `Annotation.type` as a genuine `TypeRef` (002 R4 / 003 — the reference machinery is a later pass), and whether the new uniform `Annotation` path makes the legacy `deprecated`/`override`/raw `List<String>` fields (and the unrendered class `annotations` list — currently class-only; interfaces get the uniform list per R4 but no `@SuppressWarnings` flows to them today) removable. The AST-structuring pass (003) is the *prerequisite* for R3's reference storage and for migrating `Annotation.type` off a raw string — see 003.

---

## Approach & Scope

**Goal:** Replace the four ad-hoc annotation handling styles with a single uniform `Annotation`/`Arg` model carried by every annotatable node and rendered through one shared `SourceRenderer` path, re-baselining the fixtures for the placement diff. This is a **behavior-preserving refactor of the generated output**, not a behavior change: the emitted annotations are identical except for placement/form (one-per-line before the javadoc) and the migration of the four scattered styles into one model.

**Approach:**
1. Add `Annotation` + `Arg` to the `codegen` module (R1, R2, R11). `Annotation` is a `(reference, args)` record; `Arg` is a `(name, values)` record with the single `Arg.of` factory.
2. Give *every* annotatable node a `List<Annotation> annotations` and remove the per-node ad-hoc fields: the `deprecated` flag (6 nodes via `Type.deprecated()` + `Component`/`EnumConstant`/`Method`), `Method.override`, and the class-only `List<String> annotations` on `Type.Class` (R4–R7).
3. Re-home the four producers to emit `Annotation` instances: `@Deprecated` → `Annotation.DEPRECATED`, `@Override` → `Annotation.OVERRIDE`, `@SuppressWarnings(...)` → an `Annotation` with `Arg`s, `@Nullable` → `Annotation.NULLABLE` on the type-bearing node with a bare type (R5–R7).
4. Replace the six renderer `if (…deprecated()) out.append("@Deprecated")` / `if (m.override()) out.append("@Override")` / class `annotations()` list sites with **one** shared helper that emits each `Annotation` on its own line before the node's javadoc, resolving the annotation type via the eager `ci.use(...)` import mechanism (R8, R9).
5. Re-baseline: regenerate the fixtures, confirm the only diffs are annotation placement/form/ordering, and that every generated fixture still compiles, stays GJF-clean, and passes its `@SuppressWarnings`/deprecation tests (R10).

**Scope decisions:**
- **In:** the annotation *model + carry + render + the four producer migrations + re-baseline*. The `codegen` AST (`Annotation`/`Arg` new; `Type`, `Component`, `Method`, `EnumConstant`, `Param`, `LocalVar` gain the uniform list; `SourceRenderer` gets the shared emit path) and the four generator mapper sites that produce the annotations.
- **Out:** *how `Annotation.type` is stored as a structured reference* (003's `Raw`/`Identity`/`TypeRef` — a later pass; this plan keeps `type` an eager `String` resolved by `ci.use`, per R3/R9), the *import machinery refactoring* (002/001's later pass), and any change to the *semantic* meaning of an annotation — only its placement/form/uniformity changes.
- **Out (explicitly not the plugin's model layer):** the `codegen/*` source AST only. The plugin's *model* layer (`bijnagte.recordbuf.plugin.model.*` — `Field`, `Message`, `Enum` carrying a `deprecated` flag read from the proto) keeps its own `deprecated()` reads; those are the *source* the mappers consult to decide whether to add `Annotation.DEPRECATED` to the source-AST node — they stay. Only the **source AST** node fields (`Component.deprecated`, `Type.deprecated()`, `Method.override`, the class `List<String> annotations`, `EnumConstant.deprecated`) are removed/changed.

## Implementation Units

**U1 — `Annotation` + `Arg` model (new files), standalone green.**
- **Goal:** Add the two model records to the `codegen` module so the migration has its target types. No existing code changes yet — this unit compiles green on its own.
- **Files:**
  - New `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Annotation.java`.
  - New `codegen/src/main/java/bijnagte/recordbuf/codegen/source/Arg.java`.
- **Design / decisions:**
  - `Annotation` is a `(reference, args)` record, with `reference` the eager type `String` (R3/R9 — a raw FQN *today*; the AST-structuring pass (003) later turns it into a `Raw`/`TypeRef`). Provide `static final` instances `DEPRECATED` (reference `java.lang.Deprecated`, no args), `OVERRIDE` (`java.lang.Override`, no args), `NULLABLE` (`org.jspecify.annotations.Nullable`, no args), and a constructor `annotation(String type, List<Arg> args)` for parameterized ones like `@SuppressWarnings`.
  - `Arg` is a `(name, values)` record. `name` is `@Nullable String` (the single `Arg.of(String name, String... values)` factory, R5/R2); a null or `"value"` name is positional.
  - Both carry `@JdkOnly`/module-level `@NullMarked` via the package's `package-info` (the `codegen.source` package is already `@NullMarked` — `Annotation`/`Arg` inherit it) and `org.jspecify.annotations.Nullable` on `Arg.name` per the project JSpecify style (the `codegen.source` package uses `org.jspecify.annotations`, not `javax`/`edu.umd` — match `CodeGenerator.java`/other `codegen` files).
- **Pseudocode (directional only):**
  ```java
  // Annotation.java
  public record Annotation(String type, List<Arg> args) {
    public static final Annotation DEPRECATED = new Annotation("java.lang.Deprecated", List.of());
    public static final Annotation OVERRIDE   = new Annotation("java.lang.Override",   List.of());
    public static final Annotation NULLABLE   = new Annotation("org.jspecify.annotations.Nullable", List.of());
    // @SuppressWarnings etc.: new Annotation("java.lang.SuppressWarnings", List.of(Arg.of(null, "a", "b")));
  }
  // Arg.java
  public record Arg(@Nullable String name, List<String> values) {
    public Arg(@Nullable String name, List<String> values) {
      this.name = name;
      this.values = values == null ? List.of() : values;
    }
    public static Arg of(@Nullable String name, String... values) {
      return new Arg(name, List.of(values));
    }
  }
  ```
  (Design only — `type` as `String` is deliberate per R3/R9; the AST-structuring pass migrates it to `Raw`/`TypeRef`. The `renderAnnotations`/`renderArg` formatting lives in a unit, not the model — the model holds the data only.)
- **How to verify:** `./gradlew :recordbuf-codegen:compileJava` — the new files compile; both are reachable from the `codegen` module classpath (R11). `./gradlew spotlessCheck` clean. No behavior change yet.

**U2–U4 — End-to-end annotation migration (atomic; the build is red until this group lands together).**
The model must be *added* (U1), the nodes *re-homed* (U2), the renderer *re-wired* (U3), and the producers *re-wired* (U4) in one shot: removing `deprecated()`/`override()`/the class `List<String>` breaks the renderer and the producers simultaneously. Treat U2+U3+U4 as one atomic implementation step (one commit); the per-node changes below are its parts.

**U2 — Add the uniform `annotations` list + remove the ad-hoc node fields.**
- **Goal:** Every annotatable node carries `List<Annotation> annotations`; the four ad-hoc fields are removed.
- **Files (the `codegen/src/main/java/bijnagte/recordbuf/codegen/source/*` AST nodes):**
  - `Type.java` — the shared `Type` interface: replace `boolean deprecated()` with a shared `List<Annotation> annotations();` method (R4 "every node"). `Type.Record`/`Type.Class`/`Type.Enum`/`Type.Interface` — drop the per-variant `deprecated` field (and `Type.Class`'s `List<String> annotations`), add the `annotations` field + a builder `annotation(Annotation)`/`annotations(List)`; update the four compact constructors (seed `annotations` to `List.of()` when absent). The shared `annotations()` interface method means all four variants satisfy it uniformly.
  - `Component.java`, `EnumConstant.java` — add `annotations`; drop their `deprecated` fields + the `deprecated(...)` builder methods; seed `annotations` in the compact ctor.
  - `Method.java` — add `annotations` + `List<Param>` already exists; drop `override` + its builder; seed `annotations`.
  - `Param.java`, `LocalVar.java` — *gain* the `annotations` list for the first time (R4 — every node; currently they carry no annotations at all). These are minimal records (no builder today) — add a single `annotations` field + an `@Nullable`-seeded compact ctor / `of` variant that defaults `annotations` to `List.of()`. No caller change needed for the no-annotation case.
- **Design / decisions:**
  - The shared interface method `annotations()` (on `Type`) is the uniformity guarantee (AE3): a node *without* annotations returns `List.of()` and renders nothing — so the "every node can carry annotations" requirement holds structurally, not by special-casing.
  - `Type.Class`'s `List<String> annotations` (R6) is *promoted* to the shared `List<Annotation>` — the `@SuppressWarnings` list is re-homed in U4.
  - `Param`/`LocalVar` gaining an `annotations` field (R4) is the one *additive* (non-removal) node change: today only `Component`/`Method`/`Type`/`EnumConstant` carry ad-hoc annotation state; @Nullable on a param/local *was* already spliced into their type strings today — U4 moves that into their new `annotations` list.
- **How to verify:** this unit's parts don't compile alone (U3+U4 needed to re-wire the renderer/producers); verify as a group after U4. The structural check: every `Type` variant + `Component` + `Method` + `EnumConstant` + `Param` + `LocalVar` exposes `annotations()`; none retain `deprecated()`/`override()`/a `List<String>` annotation field. `@Nullable` is now `org.jspecify.annotations.Nullable` (match `codegen`'s JSpecify import — the project uses `org.jspecify`, confirmed by `CodeGenerator.java`'s `import org.jspecify.annotations.Nonnull/Nullable`).

**U3 — Unified renderer annotation path.**
- **Goal:** Emit every node's `annotations()` through one shared helper that renders each annotation on its own line *before* the node's javadoc (R8), resolving the annotation type via the eager `ci.use(...)` import mechanism (R9).
- **Files:**
  - `codegen/src/main/java/bijnagte/recordbuf/codegen/source/SourceRenderer.java` — the six ad-hoc @Deprecated/@Override/list sites (lines ~161-166 `renderRecord`/`renderClass`/`renderInterface`/`renderEnum`/`renderComponent`, ~403 `renderMethod`) become a single call into a private `renderAnnotations(Annotation, …)` helper. The helper iterates `node.annotations()`, and for each: resolves the type via `ci.use(a.type())` (eager, R9 — same as today's `ci.use` on types), then renders `@<short>` + args.
  - `codegen/src/main/java/bijnagte/recordbuf/codegen/source/SourceRender.java` (or the `ci`/`ImportResolver` the renderer holds) — the collect phase must also register each annotation's type `ci.use(a.type())` so its import is resolved before render (R9). For the `java.lang.*` annotations (`Deprecated`, `Override`, `SuppressWarnings`) the collector must short-circuit (no import); for `org.jspecify.annotations.Nullable` it registers the import.
- **Design / decisions:**
  - **The ordering:** the contract says annotations render *before the node's javadoc* (R8). Today `@Deprecated` on a record renders *after* the javadoc (`if (f.javadoc().present()) renderJavadoc; if (f.deprecated()) out.append("@Deprecated")`); the class `@SuppressWarnings` renders *before* the javadoc. Unifying to *before-javadoc* is the placement change the re-baseline (R10) captures — the record @Deprecated moves up; the class @SuppressWarnings stays put. Both are valid Java.
  - **The one nuance — `@Nullable` is a *type-use* annotation:** `Annotation.NULLABLE` is attached to the *type-bearing node* (a record `Component`/builder `Method.param`/a `LocalVar`), and that node's "before-javadoc" position is *immediately before its own type* — so `@Nullable` lands at the type position it held today (e.g. `@Nullable long y`), just assembled via the annotation list rather than a string splice. For a `Component`/`LocalVar` that also has a javadoc, `@Nullable` renders above the javadoc (still valid — it qualifies the type, and Javadoc is just a comment). This is a *cosmetic* placement diff the GJF-clean + re-baseline confirm; if GJF or the fixtures prefer `@Nullable` glued to the type, the helper could special-case `NULLABLE` to render *at* the type position (an open decision the re-baseline surfaces).
  - **`@SuppressWarnings` rendering (AE2):** the helper renders `@SuppressWarnings` + its `Arg`s. A single value → `@SuppressWarnings("Foo")` (bare literal, no braces); multiple values → `@SuppressWarnings({"a", "b"})` (an array initializer). A named arg renders `@X(name = value)` — *unless* the name is `value` (or null), in which case it's positional. This is the *Arg.render* logic (R2/R5/R6), the one place `Arg`'s nullable-name matters.
  - **`ci.use` ordering risk:** today `@Nullable` is resolved *interleaved* with the type string in the mapper (the mapper calls `ci.use` for both the type and `@Nullable` as it builds the string). After migration, `@Nullable`'s import is registered in the *collection* phase (before render) via the node's `annotations()` — a possibly different *order* than today's interleaved string build. For the fixtures, import registration order can shift which type "wins" a short-name collision (short vs. FQN), so the re-baseline may show a short↔FQN diff on a *colliding* type that is not itself an annotation. **Directional call:** register all annotation types in the collection pass *after* the existing type registrations (so today's type-registration order is preserved for types); any residual collision-order shift is expected, captured, and acceptable (R10 permits "placement/form/ordering diffs," and a different short-name resolution is a cosmetic ordering diff, not a semantic change).
- **Pseudocode (directional only — implementation, not the spec):**
  ```java
  // the shared emit path (replaces the six per-node sites)
  private void renderAnnotations(Iterable<Annotation> annotations, StringBuilder out, Collector c) {
    for (Annotation a : annotations) {
      out.append('@').append(c.ci.use(a.type()));  // eager import resolution (R9)
      if (!a.args().isEmpty()) {
        out.append('(');
        for (int i = 0; i < a.args().size(); i++) {
          if (i > 0) out.append(", ");
          out.append(renderArg(a.args().get(i)));
        }
        out.append(')');
      }
      out.append('\n');
    }
  }
  private static String renderArg(Arg a) {
    String name = a.name();
    boolean positional = name == null || "value".equals(name);
    String values = a.values().size() == 1 ? quote(a.values().get(0)) : "{" + a.values().stream().map(SourceRenderer::quote).collect(joining(", ")) + "}";
    return positional ? values : name + " = " + values;
  }
  // collection phase: register each annotation's type so the import resolves before render.
  // (after the existing type registrations to preserve their order — see U3 risk note)
  ```
- **How to verify:** this unit is part of the atomic U2–U4 group; verified after U4. The structural check: the six `if (…deprecated()/override()) out.append(@…)` / class `annotations()` list sites are all gone, replaced by the single `renderAnnotations` call; `renderArg` produces the `@X("Foo")`/`@X({"a","b"})`/`@X(name = value)` forms AE2 requires.

**U4 — Migrate the annotation producers to emit `Annotation` instances.**
- **Goal:** The four producers emit `Annotation`/`Arg` instances instead of splicing strings or setting booleans.
- **Files:**
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/RecordSourceMapping.java`:
    - `@Deprecated` (2 sites, lines ~54, ~102): `.deprecated(m.deprecated())` → `.annotation(Annotation.DEPRECATED)` guarded by `if (m.deprecated())` (conditional attach, not a boolean field).
    - `@Override` (1 site, line ~170): `.override(true)` → `.annotation(Annotation.OVERRIDE)`.
    - `@Nullable` (~5–6 sites, lines ~226/227, ~235/236, ~242/243, ~296–298, ~423, ~481): the `ci.use("org.jspecify.annotations.Nullable"); return "@Nullable " + ci.use(type)` pattern → attach `Annotation.NULLABLE` to the built `Component`/`Method.param`/`LocalVar`'s `annotations`, and render the *type string bare* (no `@Nullable ` prefix). **Note:** each call site currently calls `ci.use("org.jspecify.annotations.Nullable")` *in the map phase* — after migration the *collect* phase registers the import via `annotations()` (U3), so these `ci.use` calls for `@Nullable` can be removed from the map phase (the collect phase handles it).
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/EnumSourceMapping.java`:
    - `@Deprecated` (2 sites, lines ~58, ~67): `.deprecated(proto.deprecated())` / `.deprecated(c.deprecated())` → `.annotation(Annotation.DEPRECATED)` guarded, on the `Enum`/`EnumConstant` builders.
  - `generator/src/main/java/bijnagte/recordbuf/plugin/writer/AvroConverterSourceMapping.java`:
    - `@SuppressWarnings` (1 site, line ~94 `.annotations(ANNOTATIONS)`; the `ANNOTATIONS` constant is the `List<String>` `List.of("@SuppressWarnings({\"checkstyle:HideUtilityClassConstructor\", \"checkstyle:MissingJavadoc\"})")` at line ~39): re-home `ANNOTATIONS` from `List<String>` to `List<Annotation>` — `List.of(new Annotation("java.lang.SuppressWarnings", List.of(Arg.of(null, "checkstyle:HideUtilityClassConstructor", "checkstyle:MissingJavadoc"))))` — and `.annotations(...)` takes the new type. (AE2's multi-value array-initializer case — the renderer's `renderArg` produces `@SuppressWarnings({"…", "…"}`).)
- **Design / decisions:**
  - **The `@Deprecated` attach becomes conditional:** today `.deprecated(m.deprecated())` unconditionally sets a boolean that the renderer conditionally emits; after migration there is no boolean, so the *producer* conditionally adds `Annotation.DEPRECATED` to the list when the proto field/enum/message is deprecated. The renderer just emits whatever is in the list. (Same for `@Override` — though `@Override` at line 170 is *unconditional* today, i.e. always present on that generated method, so it's an unguarded `.annotation(Annotation.OVERRIDE)`.)
  - **`@Nullable` is now data, not a prefix:** the 5–6 `RecordSourceMapping` sites each splice `"@Nullable " + type` → they instead attach `Annotation.NULLABLE` and render `type` bare. The record component `@Nullable Long y = 0L` (AE1) becomes the `Component`'s `annotations` = `[NULLABLE]` + bare type `Long` + initializer `0L`.
- **How to verify:** atomic with U2/U3; verified after this unit lands. The structural check: no `.deprecated(...)`, `.override(...)`, `@Nullable ` string prefix, or `List<String>` `@SuppressWarnings` literal remains in the producers; every annotation is an `Annotation`/`Arg` instance. `./gradlew :recordbuf-generator:compileJava` green (the whole atomic group compiles).

**U5 — Update the generator snapshot tests to the new annotation rendering.**
- **Goal:** The snapshot asserts that match today's rendering (a `@Nullable` type-prefix, a `@Deprecated` line *after* javadoc, etc.) are updated to the new rendering — one annotation per line *before* the javadoc — so the generator test suite stays green.
- **Files:**
  - `generator/src/test/java/bijnagte/recordbuf/plugin/writer/RecordSourceMappingTest.java` — the `src.contains(...)` asserts that match the old rendering:
    - ~L469 `src.contains("@Nullable Integer opt")` — update to the new rendering (`@Nullable` on its own line before the type, i.e. contains `@Nullable\nInteger opt` or the GJF-folded form; confirm the exact post-migration rendering by regen).
    - ~L553 `src.contains("@Nullable Choice choice")` — same.
    - ~L796 `src.contains("@Nullable Color color = null")` — same.
    - Any `src.contains("@Deprecated")` / `@Override` asserts (grepped at verification time) — update placement to pre-javadoc.
  - `generator/src/test/java/bijnagte/recordbuf/plugin/writer/AvroConverterSourceMappingTest.java` — the `@SuppressWarnings` assertion (if any) → the new `@SuppressWarnings({"…", "…"})` array-initializer form (AE2).
  - Any other snapshot test that renders a deprecated/annotated node — grepped at U5 start; update each to the new placement.
- **Design / decisions:**
  - **The re-baseline is the *test expectations*, not a golden-file corpus:** the generator tests are snapshot-style `src.contains(...)` asserts over the *in-memory rendered string*; the fixtures are generated fresh by `:recordbuf-fixtures:generateProto` and compiled/EP/JUnit-tested (no committed golden files — generation writes to `build/generated/`, confirmed in-session). So "re-baseline" = (a) update the snapshot asserts in U5, and (b) regenerate + compile + EP + JUnit the fixtures in U6, confirming the only *output* diffs are the annotation lines. There is **no separate golden-diff tool** — the `contains(...)` asserts + the fixture compile/EP/JUnit gate *are* the baseline.
  - **AE4 ("every previously-stable fixture byte-for-byte unchanged aside from the annotation lines")** is verified by regenerating and inspecting the diff (a `git diff` of the regenerated `build/generated/…` before/after, or a manual review of the per-fixture rendered output) — an expected, documented step, not an automated test.
- **How to verify:** `./gradlew :recordbuf-generator:test` — the updated snapshot asserts pass against the new rendering. The per-assert diffs are reviewable (each is a single `contains(...)` line changed to the new placement).

**U6 — Fixtures re-baseline + full verification.**
- **Goal:** Regenerate every fixture, confirm it compiles + stays NullAway-clean + passes its JUnit harness, and that the only output diffs are annotation placement/form (R10, AE4).
- **Files:** none (regenerate + inspect). The `:recordbuf-fixtures` module (`fixtures/`) — `protoc-gen-recordbuf` regenerates into `build/generated/`; the Java compile + EP/NullAway + JUnit gate runs there. No fixture `.proto` changes.
- **How to verify:**
  - `./gradlew :recordbuf-generator:installDist` then the `:recordbuf-fixtures` `generateProto`/`generateTestProto` tasks regenerate the records/encoders (the launcher is KTD8-up-to-date-closed; a generator rebuild triggers regen).
  - `./gradlew :recordbuf-fixtures:build` (compile the generated Java + run EP/NullAway + JUnit). The generated output must compile (AE5), be NullAway-clean (the `@NullMarked` fixture packages + `NullAway:OnlyNullMarked`), and pass its `AnnotationEmissionTest`/`AnnotationsTest`/`RecordSourceMappingTest` (the deprecation/annotation behavioral tests).
  - `./gradlew spotlessCheck` clean; `./gradlew checkstyleMain` clean (the generated `@SuppressWarnings({"checkstyle:…", "checkstyle:…"})` array-initializer must still satisfy the existing `checkstyleMain` on the converter classes — AE2).
  - Inspect the regenerated output diff: the only changes are the annotation lines (placement/form/ordering); everything else byte-for-byte identical (AE4). Flag any non-annotation diff as a finding.
- **Scope:** this is the verification gate (R10/AE4/AE5). It is a *confirmation* step, not a code change — no `Files` to edit; the work is regenerate + inspect + confirm.

## Dependencies & Sequencing

- **U1 (`Annotation`/`Arg` model)** — standalone; lands green first. Prerequisite for U2–U4.
- **U2 + U3 + U4 (nodes + renderer + producers)** — **one atomic step**: removing the `deprecated()`/`override()` node fields (U2) breaks the renderer's `renderX` calls (U3) and the producers' `.deprecated(...)`/`.override(...)`/`@Nullable ` splices (U4) *simultaneously* — none of U2/U3/U4 compiles alone. Land them together as one commit. No other work may interleave (single writer, `codegen/*` then `generator/*`).
- **U5 (snapshot test updates)** — *after* U2–U4 land (the new rendering exists). The generator test suite then stays green.
- **U6 (fixtures re-baseline)** — *after* U5; the final verification gate. Can run in parallel *only* after U1–U5 land (it regenerates + compiles + EP + JUnit).
- **Cross-plan:** 003 (AST-structuring) is a *prerequisite* for R3's reference storage / `Annotation.type` → `Raw`/`TypeRef`. If 003 lands *after* this refactor, `Annotation.type` stays a raw `String` and 003's raw-string migration must visit `Annotation.type` too (a small, documented follow-on, not a blocker for this refactor). The recommended order is **003 → this**, so `Annotation.type` is born structured.

## Test Strategy

- **Unit (the model):** U1 — `Annotation.toString()`/`renderArg(Arg)` produce the AE2 forms: `@SuppressWarnings("Foo")` (single), `@SuppressWarnings({"a", "b"})` (multi, array initializer), `@SuppressWarnings(name = value)` (named, non-`value` name), positional when name is null/`"value"`. A small `ArgsTest` (JUnit 5, the `codegen` module's test scope) over `renderArg`/`Annotation` covers AE2.
- **Unit / snapshot (the renderer + producers):** U5 — the generator `src.contains(...)` snapshot asserts updated to the new one-per-line-before-javadoc placement; `./gradlew :recordbuf-generator:test` green.
- **Fixture behavioral (the re-baseline):** U6 — `:recordbuf-fixtures:build` compiles the regenerated output + runs EP/NullAway + JUnit (`AnnotationEmissionTest`/`AnnotationsTest` — the deprecation/annotation behavioral tests). The generated output must compile (AE5).
- **Integration (placement diff):** U6 — regenerate every fixture and *inspect the diff*: only the annotation lines change (R10/AE4). A non-annotation diff is a finding, not a pass.
- **Style gate (all units):** `spotlessCheck` + `checkstyleMain` on the changed files per the project rules.

## Assumptions

- **`@Nullable` placement:** the uniform "before javadoc" rendering (R8) places `@Nullable` above the type-bearing node's javadoc — valid Java, a cosmetic diff the re-baseline captures. If GJF or a fixture prefers `@Nullable` glued to the type, the renderer can special-case `NULLABLE` to render at the type position (an open decision surfaced at U6 re-baseline).
- **Import registration order:** `@Nullable`'s import is registered in the *collection* phase (after the existing type registrations) rather than interleaved with type-string building (U3 risk). For the fixtures this is safe — import registration order only affects *collision short-name resolution* (short vs. FQN), and the re-baseline tolerates ordering diffs (R10). A short↔FQN shift on a *colliding* type is acceptable, documented.
- **No annotation semantics change:** the migration is placement/form/uniformity only. `@Deprecated`/`@Override`/`@SuppressWarnings`/`@Nullable` keep their exact meaning. The `@SuppressWarnings({"checkstyle:…", "checkstyle:…"})` values are identical (AE2); `@NullMarked`/`@Nullable` (JSpecify, the nullness annotations on the fixture packages) are unchanged in meaning.
- **`Arg.of(null, …)` = positional, `Arg.of("value", …)` = positional** (the contract's R2/R5): a named arg with name `value` is *not* rendered as `value = …` — it's positional. A named arg with any other name renders `name = value`.
- **The `codegen` AST is `@NullMarked`** (its `package-info`): `Annotation`/`Arg` inherit it; `Arg.name` is `@Nullable` (JSpecify). Match the `codegen` files' `org.jspecify.annotations` import (confirmed `CodeGenerator.java`).
- **The plugin's model layer `deprecated()` reads stay** (`bijnagte.recordbuf.plugin.model.*`): they feed the mappers' *decision* to add `Annotation.DEPRECATED`; only the source AST node fields change. Out of scope (Scope, above).
- **AE3 "every node carries the annotation list"** is satisfied *structurally* by defaulting each node's `annotations()` to `List.of()`, so a node without annotations renders nothing — no per-node special-casing at render.
- **No committed golden-file corpus:** the re-baseline is the snapshot asserts (U5) + regenerate/compile/EP/JUnit (U6); AE4's "byte-for-byte" is verified by regeneration + diff inspection, not a golden-diff tool. The `:recordbuf-fixtures` writes generated output to `build/generated/` (confirmed).

## Research Sources

- **In-repo (grounded in-session, no external research):** the `codegen` source AST — `codegen/src/main/java/bijnagte/recordbuf/codegen/source/{SourceRenderer,SourceRender,Type,Component,Method,EnumConstant,Param,LocalVar}.java` (the four ad-hoc styles + the six renderer `@Deprecated`/`@Override`/`annotations()` sites; the `deprecated()` on the shared `Type` interface + the four variants; the class-only `List<String> annotations` on `Type.Class`; the `Param`/`LocalVar` that carry no annotations today).
- **The producers:** `generator/src/main/java/bijnagte/recordbuf/plugin/writer/{RecordSourceMapping,EnumSourceMapping,AvroConverterSourceMapping}.java` — `@Deprecated` (2 + 2 sites), `@Override` (1), `@SuppressWarnings` `List<String>` (1, `AvroConverterSourceMapping.ANNOTATIONS`), `@Nullable ` string-splice (`RecordSourceMapping` ~5–6 sites).
- **The tests / gate:** `generator/src/test/java/bijnagte/recordbuf/plugin/writer/{RecordSourceMappingTest,AvroConverterSourceMappingTest}.java` (the `src.contains(...)` snapshot asserts that break on migration); `fixtures/build.gradle` (the generate → compile → EP/NullAway → JUnit gate; no committed golden corpus).
- **Project learnings + terminology:** `docs/solutions/tooling-decisions/enable-nullaway-null-checks.md` (the JSpecify/NullAway context — `@Nullable` is the JSpecify nullness annotation, enforced by NullAway in the fixture module; the coordinate/API facts are background to why `@Nullable` exists). `CONCEPTS.md` (the codec/carrier/interop harness vocabulary — adjacent but not directly the annotation domain).
- **External:** none — this is a pure in-repo refactor of an existing model; no new technology, library, or external option set. No external research was warranted.

## Open Threads (re-stated post-enrichment for the later pass)

- **`Annotation.type` → a genuine `TypeRef`** (003's `Raw`/`Identity`; 002's R4): this plan keeps `type` an eager `String` resolved by `ci.use` (R3/R9). The AST-structuring pass (003) is the prerequisite that migrates it — and, if 003 lands after this, 003's raw-string migration must also visit `Annotation.type` (a small follow-on).
- **Removability of the legacy fields:** post-migration, the `deprecated`/`override`/raw `List<String>` annotation fields are gone (superseded by `annotations()`). The class `@SuppressWarnings` list is now `List<Annotation>` — no further cleanup. No open thread here beyond confirming the build is green.
- **`@Nullable` placement special-case:** if the re-baseline (U6) shows GJF or a fixture prefers `@Nullable` glued to the type, add a `NULLABLE` special-case to the renderer's placement (render at the type position, not before the node's javadoc). A documented follow-on, not a blocker.
- This is a *design-only* prerequisite for the AST-structuring migration (see 003); the structured reference storage / typed `type` is the later pass's job. The `Raw`/`Identity` migration *also* touches `Annotation.type`'s representation — so 003 and this refactor share the "annotation resolution through the collector" seam; landing 003 first is the clean ordering.
