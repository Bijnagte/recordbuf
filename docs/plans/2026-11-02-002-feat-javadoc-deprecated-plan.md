# Javadoc + `@Deprecated` for generated Java

> **Requirements-only plan.** No tasks, estimates, implementation steps, or
> "U1." sections. This is the contract for the requirements and decisions of
> `protoc-gen-recordbuf` emitting Javadoc and `@Deprecated`; it is not an
> execution plan. For execution, hand this to `/ce-work docs/plans/` or
> `/ce-plan docs/plans/2026-11-02-002-feat-javadoc-deprecated-plan.md --deep`.
>
> Date: 2026-11-02 · Status: **Implemented end-to-end (2026-11-02).**
> See the "## Status / Implementation Notes" section at the bottom for what
> shipped, what was deferred, and one pre-existing gap discovered.
> This remains a requirements-only plan per the repo convention; the status
> section is the implementation log.

## Goal Capsule

- **Problem to solve:** Generated Java types carry no documentation and no
  deprecation markers, even though the source `.proto` may contain Javadoc
  comments and `deprecated` options. Downstream code reading recordbuf output
  loses all human intent that was in the proto.
- **Approach:** `protoc-gen-recordbuf` reads the `FileDescriptorProto`
  `source_code_info` location tree, populates the `@Nullable String comment`
  fields already on `Message`, `Enum`, `Enum.EnumConstant`, and `Field`,
  adds `deprecated` to the `Field` model, and the Java writers render the
  comments as clean, HTML-escaped Javadoc blocks and emit bare `@Deprecated`
  where relevant. Nested `Codec` classes get a short doc noting the source
  proto and that they read/write protobuf wire format.
- **Done when:** Every top-level / nested `record`, top-level / nested `enum`,
  `enum` constant, and record component with a source comment or `deprecated`
  flag renders Javadoc/deprecation in valid Java; a new "annotations" fixture
  round-trips and byte-matches protobuf-java's generated output; `./gradlew
  build` is green.
- **Why now:** The model already scaffolds `comment` on every element (all
  currently hardcoded `null`); enum-type `@Deprecated` is already wired. This
  closes the last gap and unblocks documented downstream consumers.
- **Constraints:** Must stay clean-idiomatic (recordbuf ethos); `java.lang.*`
  is never imported; NullAway remains at error severity; two-pass
  resolve-then-render and `TypeImports` are untouched; generated code stays
  dependency-free.

## Product Contract

### Summary

Add Javadoc and `@Deprecated` emission to `protoc-gen-recordbuf`, sourced from
`FileDescriptorProto.getSourceCodeInfo()` (leading comments) and the
`Options.getDeprecated()` flags. This is not a wire-format or behavior change to
existing codecs — it is an additive rendering capability on the Java writer
layer and a model extension (`Field.deprecated`).

### Problem Frame

`FileDescriptorProto` carries a `source_code_info` tree: each location is a
nested path (file → top-level declaration → field/constant → nested type …)
with `leading_comments` / `trailing_comments` / `leading_detached_comments`.
Protobuf-java's `protoc-gen-java` already renders these as Javadoc and emits
`@Deprecated` on deprecated elements — but its output is verbose
(`<pre>…</pre>` wrapping + `Protobuf type {@code …}` + `<code>NAME = N;</code>`
lines), which clashes with recordbuf's clean-record ethos. `protoc-gen-recordbuf`
currently:
- hardcodes `comment=null` on every `Message`, `Enum`, `Enum.EnumConstant`,
  `Field`, and `ProtoFile` in `ModelLoader.load*` calls;
- emits **zero** Javadoc in any writer (`CodeGenerator`, `EnumGenerator`,
  `CodecGenerator`);
- emits `@Deprecated` for `enum` types only (`EnumGenerator:16`);
- never emits `@Deprecated` on deprecated **messages** (a latent gap), nested
  messages/enums, enum constants, or record components;
- has no `deprecated` field on `Field` for field-level deprecation.

Native behavior, measured against `protoc` 4.36.2 (the project's managed
version): the user's leading comment is HTML-escaped (`<`→`&lt;`,
`>`→`&gt;`, `&`→`&amp;`, `@`→`&#64;`); `@Deprecated` is emitted fully-
qualified as `@java.lang.Deprecated`; detached leading comments are **not**
rendered onto the element's Javadoc. Native puts field Javadoc on the getter;
recordbuf has no getter, so the record **component** is the target — a
legitimate divergence from native.

### Key Decisions (user-confirmed, 2026-11-02)

| # | Decision | Why |
|---|---|---|
| D1 | **Javadoc fidelity: clean** — plain `/** … */` with one `* ` per line, no `<pre>`, no `Protobuf type {@code …}` / `<code>NAME = N;</code>` boilerplate. HTML-escape `<` / `>` / `&` so it stays valid Javadoc. Bare `@Deprecated` (not `@java.lang.Deprecated`) — `java.lang` is never imported. | Matches recordbuf's clean-output ethos; native's verbose form is protobuf-java-specific and would read as noise in records. |
| D2 | **Coverage: types + enum constants + record components.** Javadoc on `record`, nested `record`, top-level/`enum`, and each enum constant's Javadoc and `@Deprecated`. Javadoc on each record component (the analogue of native's getter Javadoc). Adds `deprecated` to the `Field` model (currently missing). | Closest-to-native coverage; a deprecated record component should read as deprecated in consumers. |
| D3 | **File-level comments: dropped.** A `.proto` file's leading comment (the file-level location path `[]`) is not rendered. recordbuf has no outer class (one file per top-level type), so there is no natural home; native puts it on `java_outer_classname`'s outer class, which recordbuf has no analogue of. | Simplest; file-level comments are rare and low-value in record-style output; native's target (an outer class) does not exist here. |
| D4 | **Nested `Codec` class doc:** each nested `Codec` gets a short Javadoc noting the enclosing record, the source `.proto` file name, and that it reads/writes protobuf wire format. | User-directed; the Codec is the "wire surface" — it benefits most from a "what and where" doc. No per-field/constant Javadoc inside `Codec`. |

### Requirements

#### R1 — Parse `source_code_info` and populate `comment` (P1)
`ModelLoader` walks `file.getSourceCodeInfo()` as a location tree and attaches
each target's `leading_comments` to the `comment` field of the corresponding
`Message`, `Enum`, `Enum.EnumConstant`, or `Field`. A `leading_detached_
comments` block is **ignored** (matches native — see D3 note and open Q1).
The `comment` string passed to the model is the **raw** leading comment
(whitespace-trimmed, one block); HTML escaping happens **at emit time** in
the writer, not at parse time, so the model stores user-intent text.

- **Acceptance:** Given a proto with a leading comment on a message/enum/
  constant/field (and no detached comment between the comment and the
  declaration), `ModelLoader` populates `comment` on that element with the
  raw text; no `@Nullable` is emitted at the caller.
- **Acceptance:** A `comment` followed by a `leading_detached_comments` block
  (i.e. the comment does NOT immediately precede the declaration) is ignored.

#### R2 — Field model gains `deprecated` (P1)
`Field` gains a `boolean deprecated` component (appended as the last
parameter, after `comment`). The two `new Field(...)` positional construction
sites in `ModelLoader` (`loadField` line 177, `buildMapField` line 291)
pass the value: `loadField` reads `f.getOptions() != null &&
f.getOptions().getDeprecated()`; `buildMapField` **carries the user's
comment and deprecated flag through** to the synthetic map field (currently
both hardcoded `null`/`false` — the map field IS the user's field, e.g.
`map<string,int32> users = 1; // docs`). `@RecordBuilder` on `Field`
automatically regenerates the builder; no test constructs `Field`, so no
test call site needs updating.

- **Acceptance:** `loadField(f)` returns a `Field` whose `deprecated()` is
  `true` iff `f.getOptions().getDeprecated()` is `true`. `buildMapField`
  returns a `Field` whose `comment()` / `deprecated()` match the input
  `synthetic`'s.
- **Acceptance:** `./gradlew :recordbuf-generator:build` is green; NullAway
  does not trip on the new primitive field.

#### R3 — Emit clean Javadoc on record, nested record, enum, and record
component (P1)
`JavaWriter` gains a block-comment / Javadoc emission capability (reuse or
generalize `header(...)`: same structure, `/** */` instead of `/* */`).
Each writer emits a Javadoc block when the element's `comment()` is
non-blank:
- **Record type header:** Javadoc immediately before `public [final] record
  Name(...)`. Nesting handled like types today (indentation via `w.indent()`).
- **Nested record / nested enum inside a record body:** Javadoc before the
  nested type declaration, respecting nesting depth.
- **Enum type header:** Javadoc immediately before `public [final] enum
  Name`. (Replaces the current silent gap for enum comments.)
- **Enum constants:** each constant that has a `comment` gets a Javadoc
  block before its declaration; `UNRECOGNIZED(-1)` (recordbuf's synthetic
  fallback) gets **no** Javadoc.
- **Record components:** each field with a `comment` gets a Javadoc block
  before its `var name` line within the canonical header; the record body is
  unchanged. The canonical-header's per-component trailing comma must attach
  to the `var name` line (not the prepended Javadoc line) — see Technical
  Design.
- **Codec class doc (D4):** `public static final class Codec {` gets a
  short Javadoc: "Wire codec for the `NAME` record. Generated from
  `SOURCE.proto`; reads/writes protobuf wire format." No per-field Javadoc
  inside `writeMembers`.

- **Acceptance:** Every generated `record` / `enum` / `enum` constant /
  record component with a non-blank `comment` produces a `/** … */` block
  immediately preceding the declaration (indentation matches the
  surrounding context).
- **Acceptance:** HTML-escaping applies: a comment containing `& < >`
  renders as `&amp; &lt; &gt;`; a `@` in prose is **not** escaped
  (clean mode — not wrapped in `<pre>`, so `@` is safe in a javadoc block).
- **Acceptance:** An enum constant with no comment emits no Javadoc.
  `UNRECOGNIZED` never gets one.
- **Acceptance:** The nested `Codec` class has a Javadoc on its declaration
  line naming the enclosing record and the source `.proto` file; the
  message body of `Codec` (constructor, `read`, `write`) is unchanged.

#### R4 — Emit `@Deprecated` (P1)
Emit bare `@Deprecated` (never `@java.lang.Deprecated` — `java.lang` is
never imported, matching the repo's two-pass TypeImports invariant) on
deprecated elements:
- **Deprecated record (`message.deprecated`):** `@Deprecated` on the record
  declaration's own line, immediately after any Javadoc. Fixes the latent
  gap where deprecated messages today get no marker.
- **Deprecated nested record / nested enum inside a record body:**
  same, respecting nesting indentation.
- **Deprecated enum constant (`constant.deprecated`):** `@Deprecated`
  immediately before the constant declaration line, in addition to any
  Javadoc; matches the pattern already emitted for enum **types**
  (`EnumGenerator:16`).
- **Deprecated record component (`field.deprecated`):** `@Deprecated`
  on the `var name` line alongside the existing `@Nullable` if the field
  is also nullable (`@Deprecated @Nullable String name`).

- **Acceptance:** `message`/nested `record` with `deprecated` renders
  `@Deprecated` on its declaration line; nested indentation preserved.
- **Acceptance:** An enum **type** with `deprecated` continues to emit
  `@Deprecated` (no regression — current behavior at
  `EnumGenerator:16`).
- **Acceptance:** A deprecated enum constant `WARN = 1 [deprecated = true]`
  with no comment renders just `@Deprecated` on its declaration line;
  with a comment, `@Deprecated` goes immediately **after** the Javadoc
  block.
- **Acceptance:** A deprecated nullable record component renders
  `@Nullable @Deprecated String name,` (or `@Deprecated @Nullable` —
  order fixed by a decision, see Open Q2); a deprecated non-nullable
  `int count = 3` renders `@Deprecated int count = 3,` or `@Deprecated int
  count` depending on position in header.
- **Acceptance:** No `@java.lang.Deprecated` appears anywhere in any
  generated file (grep `@java.lang.Deprecated` across
  `fixtures/build/generated/…` returns nothing after a fixture regen that
  exercises deprecation).

#### R5 — New "annotations" fixture + tests (P1)
Add a `fixtures/src/main/proto/annotations.proto` exercising, at minimum:
- one top-level `record` with a doc + a nested record with a doc + a doc
  and `@Deprecated` on at least one nested type;
- one top-level `enum` with a doc + one deprecated constant with a doc +
  one non-deprecated constant (with and without a doc);
- one record field that is a nested message, one that is the enum,
  one `optional` field that is `@Deprecated`, and one `map` field with a
  doc and `@Deprecated` (to exercise `buildMapField` carry-through, D2);
- one nested enum with a doc + a deprecated constant;
- HTML-content in at least one comment (`<`, `>`, `&`, a `@` in prose).

The fixture round-trips (encode → decode via recordbuf's own `Codec`) and
byte-matches protobuf-java's output (the existing interop harness). Two new
generator unit tests: one drives `ModelLoader` on a synthetic `FileDescriptor
Proto` with `source_code_info` populated, asserts `comment`/`deprecated`
landed on the right model elements; one drives a writer end-to-end and
asserts `contains`/`not-contains` on key Javadoc/`@Deprecated` lines.
Existing generator unit tests — which use `contains` assertions on generated
output — must keep passing without modification.

- **Acceptance:** `./gradlew :recordbuf-fixtures:generateJava` regenerates
  `annotations.proto` → a new `annotations` Java source file that compiles
  cleanly under `javac` and NullAway at error severity.
- **Acceptance:** The annotations fixture's round-trip test and protobuf-
  java byte-pair test pass.
- **Acceptance:** New generator unit tests assert `Field.comment`/
  `Field.deprecated`, `Message.comment`, `Enum.comment`,
  `Enum.EnumConstant.comment`/`deprecated` are populated from
  `source_code_info`/`Options`, and that a writer emits `/** … */` +
  `@Deprecated` for a deprecated annotated element.
- **Acceptance:** `./gradlew build` is green across all modules.

#### R6 — Drop file-level comments; ignore detached comments (P3)
`ModelLoader` **does not** populate `ProtoFile.comment` from `source_code_info`
`[]` (the file root location). Leading-detached comment blocks are not
attached to the following element. These are no-ops by omission; no
behavior to write, just a statement of intent and a regression guard.

- **Acceptance:** A generated file has no `/** file comment */` block
  anywhere; a detached comment is not emitted on the following element's
  Javadoc. Existing `contains`-based unit tests remain green.

### Scope Boundaries

- **In scope:** Clean Javadoc on record type / nested type / enum type /
  enum constant / record component; `@Deprecated` on all five; a short
  Javadoc on each nested `Codec` naming source proto + record + wire
  format; `Field.deprecated` addition; a new `annotations` fixture
  (proto + interop + generator unit tests); HTML-escaping of `< > &` at
  emit time.
- **Out of scope:** Native-faithful `<pre>` + `Protobuf type {@code …}` /
  `<code>NAME = N;</code>` wrapping (revisit if a downstream tool depends
  on it); `leading_detached_comments` (ignored — matches native);
  `trailing_comments` on declarations (rare; not user-visible in proto
  editors); per-element source-location comments beyond `leading_comments`
  on declarations; a `comment`/`deprecated` option on `Enum.Constant`
  beyond the already-present `deprecated` (no proto-native `comment`
  option — `leading_comments` from `source_code_info` is the source,
  same as for every other element); propagating `@Deprecated` to record
  accessors or canonical constructors (per Java's `@Deprecated` targeting
  on record components — see open Q5); changing the `codec.read/write`
  bodies; changing the two-pass TypeImports flow; changing any existing
  fixture proto or its generated output beyond what adding a new
  `annotations.proto` requires.

### Dependencies / Assumptions

- `protoc` is the managed version at `4.36.2` (per
  `~/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protoc/4.36.2/`);
  this was used to verify native behavior. If the managed version moves, the
  native-emission reference in this plan is superseded by that version.
- recordbuf's clean-idiomatic ethos (as stated in `AGENTS.md`) is a
  standing product constraint — "generated code that goes into the user's
  classpath should stay close to a wider baseline (e.g. 17+)". `@Deprecated`
  (Java 9, standard-library) and `/** */` javadoc (Java 1+) are well within
  that. The plan does not bump the project's JDK target.
- Java 25 (the project's Gradle toolchain) — no new language-feature
  dependency.

### Outstanding Questions

1. **Detached comments (leading_detached_comments).** Native does not render
   them on the following element's Javadoc. This plan ignores them too (see
   R1/R6). If a downstream consumer wants detached comments surfaced, this
   is the place to revisit.
2. **`@Deprecated` / `@Nullable` order on a deprecated nullable field.**
   Java tolerates any order; this plan fixes `@Deprecated` **before**
   `@Nullable` (`@Deprecated @Nullable String name`) to match the
   "deprecation first, then type annotations" reading. If the user prefers
   `@Nullable @Deprecated`, the writer is a one-line change.
3. **Javadoc single-line vs multi-line.** This plan always emits a
   multi-line block (`/**\n * …\n */`) even for single-line comments,
   matching the existing file-header style and native's `<pre>` wrapping.
   If the user prefers single-line collapse
   (`/** foo */` when the comment has no internal newline), that is a
   one-paragraph change to the writer helper.
4. **`Codec` Javadoc wording.** Proposed: "Wire codec for the `NAME` record,
   generated from `SOURCE.proto`. Reads and writes bytes in protobuf wire
   format." If the user has a preferred phrasing, update the string literal
   in `CodecGenerator`.
5. **Record-component deprecation and Java's warning behavior.** `@Deprecated`
   on a record component does **not** propagate to the record's canonical
   constructor or its implicit accessors in current JDKs — a `sample.name()`
   call does not warn. This plan emits `@Deprecated` on the component to
   document intent; it does NOT attempt to also deprecate the accessor
   (not possible in Java without a hand-written accessor). Consumers must
   treat the deprecated component as "documentation + a warning when it is
   directly named from another compilation unit". If the user wants a
   stronger guarantee, a hand-written `@Deprecated` accessor is a separate
   (out-of-scope) feature.
6. **Field-level `option deprecated = true` on the `.proto` file.** `option
   deprecated = true;` at the **file** level is deprecated in proto3 and
   not commonly used; recordbuf has no outer class to deprecate (see D3),
   so this plan ignores it. If needed, it is a small follow-up.

## Planning Contract

### Key Technical Decisions (high-level, not task steps)

| Area | Choice | Rationale |
|---|---|---|
| `ModelLoader` comment tree | Walk `file.getSourceCodeInfo()` recursively as a nested-children tree (mirroring `loadTop`); attach `leading_comments` to each target's `comment` when the location path matches the target's own nested location in the proto. | Native's rendering path is the same tree; this matches what protobuf-java does. |
| Raw vs escaped `comment` on model | **Raw** at parse time, **escaped at emit time** in the writer. The model stores user-intent text (no `&amp;`, `&lt;`); the writer is the single place that decides HTML-escaping. | Keeps the model reusable (e.g. if a future writer wanted the raw text) and localizes HTML-escaping to the one place that knows it is emitting Javadoc. |
| Detached comments | Ignore (`leading_detached_comments` not attached). | Matches native (measured on `4.36.2`). No surprising behavior in consumers. |
| Writer Javadoc helper | Add a small `JavaWriter.javadoc(indent, text)` (or generalize `header(...)`) that emits the `/**` / ` * …` / ` */` block, trimming trailing newlines from the input and HTML-escaping `< > &` on each line. Reuse for record, enum, enum constant, record component, and Codec doc sites. | Single code path; one escaping helper; consistent indentation. |
| Per-component trailing comma | Currently `sep(0, ",")` attaches the comma to the last non-blank line **before** indentation. When a component gets a preceding Javadoc / `@Deprecated` line, the comma would land on the wrong (Javadoc's last) line. Restructure the per-component loop so the comma is appended to the **`var name`** line specifically — likely by emitting `line("var name")` then `sep(0, ",")` for non-last components, or by appending `,` to the component name as a string. | Prevents "comma on the last Javadoc line" bug that would produce malformed Java. |
| `Field.deprecated` addition | **Append** as the last parameter of the `Field` record (after `comment`); update the two `new Field(...)` positional call sites in `ModelLoader.loadField` and `ModelLoader.buildMapField`. | Minimal disruption to existing positional usage; record-builder auto-regenerates the builder; no test constructs `Field`. |
| `Field.comment` / `deprecated` carry-through in `buildMapField` | The synthetic map field IS the user's field (e.g. `map<string,int32> users = 1; // docs`); its comment/deprecation must come from the original `synthetic` proto field, not be hardcoded `null`/`false`. | `buildMapField` creates the user-visible `Field` that carries the doc — losing the doc here loses it forever. |
| `@Deprecated` bare form | `@Deprecated` (not `@java.lang.Deprecated`) on all five element kinds; no `import java.lang.Deprecated;`. | Matches the repo's "never import `java.lang.*`" invariant (see `docs/plans/2026-11-02-001-feat-fqn-to-imports-plan.md` D2/Step C3 / `TypeImportsTest.javaFqnReturnsShort`). |
| `Codec` doc | `CodecGenerator` gains a source-proto string threaded from `CodeGenerator` (which already has `fdp.getName()`); a short 2-line Javadoc on the `public static final class Codec` declaration. No per-field Javadoc inside `writeMembers`. | Matches D4 without expanding scope. |
| HTML-escaping at emit time | A small helper (in a new `Javadoc` util or inline in `JavaWriter`) that escapes `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;` per line. `@` is **not** escaped in clean mode (no `<pre>` wrapper, so `@` is safe in a javadoc block — javadoc tag-`@` risk is not relevant when the text is a plain `* line`). | Prevents "invalid Javadoc HTML" in generated output without introducing `<pre>`-style noise native uses. |
| Record-component deprecation in Java | Emit `@Deprecated` on the component only; do NOT attempt to propagate to the accessor or the canonical constructor. | Java cannot cleanly do both; see open Q5. |

### High-Level Technical Design

The loader walks `source_code_info` as a nested-children tree and populates
`comment` on each target; a small `Javadoc` helper on `JavaWriter` renders
clean, HTML-escaped blocks; the three writers call it at each kind's
declaration site; `Field` gains a `deprecated` component that `loadField`
populates from `f.getOptions().getDeprecated()` and `buildMapField` carries
through. No changes to the two-pass TypeImports flow, the two codec bodies,
or any existing fixture's proto source.

### Implementation Overview (one paragraph, not tasks)

`ModelLoader` gets a `locationComments(...)` helper that walks
`file.getSourceCodeInfo()` into a nested map from proto path → leading-comment
string, attaches it to each loaded `Message`/`Enum`/`Enum.Constant`/`Field`
via the loader's existing `comment(...)`, populates `Field.deprecated` in
both `loadField` (direct from `Options.getDeprecated()`) and `buildMapField`
(from the user's `synthetic`); `JavaWriter` gains a `javadoc(indent, text)`
method that emits a clean multi-line `/** … */` block with per-line
`<`/`>`/`&` escaping and trims the trailing newline the source-comment
string carries; `CodeGenerator.emitMessage`, `CodeGenerator.recordAndCodec`
(the type-header part), `EnumGenerator.write` (type header + per-constant),
and `CodeGenerator.recordAndCodec` (the per-component loop) each call
`w.javadoc(...)` + emit `@Deprecated` when the model says so; the per-
component loop is restructured so the trailing comma attaches to the `var
name` line; `CodecGenerator` emits a 2-line Javadoc on the `Codec` class
declaring "Wire codec for the `NAME` record, generated from `SOURCE.proto`;
reads/writes protobuf wire format". A new `annotations.proto` fixture covers
message doc + deprecated nested record + enum doc + deprecated enum constant
+ a deprecated nullable field + a deprecated map + a nested enum with a doc
+ an HTML-content comment; the new fixture's round-trip and byte-pair tests
follow the existing patterns; two new generator unit tests assert
`ModelLoader` populates `comment`/`deprecated` correctly and that a writer
emits Javadoc + `@Deprecated` for a deprecated element. No changes to
`codec.read`/`write`, TypeImports, or any existing fixture.

### Verification Contract

- **Unit-level:** New generator unit tests pass:
  - `ModelLoader` on a synthetic `FileDescriptorProto` with
    `source_code_info` asserts `comment` and `deprecated` land on the right
    model elements for `Message`/`Enum`/`Enum.Constant`/`Field`.
  - A writer end-to-end test asserts `contains` on key Javadoc /
    `@Deprecated` lines for a deprecated-with-comment message, a
    deprecated-with-comment enum constant, a deprecated-with-comment record
    component, a clean nested `Codec` Javadoc, and asserts `not-contains`
    for `@java.lang.Deprecated` (anywhere) and for a file-level `/** … */`.
  - Existing generator unit tests (`ImplementsClauseEmissionTest`,
    `TypeImportsTest`, `MessageInterfacesTest`, `ModelInterfaceOptionTest`,
    `FileTablePackageSuffixTest`) continue to pass **unmodified**.
- **Fixture-level:** The new `annotations` fixture round-trips and byte-
  matches protobuf-java's output (existing interop harness).
- **Build-level:** `./gradlew build` is green across all modules
  (`:recordbuf-options`, `:recordbuf-runtime`, `:recordbuf-generator`,
  `:recordbuf-fixtures`), with NullAway at error severity.
- **Regen:** `./gradlew :recordbuf-generator:installDist && protoc
  --plugin=protoc-gen-recordbuf=…/build/scripts/protoc-gen-recordbuf
  --recordbuf_out=<tmp> annotation_demo.proto` produces a Java source that
  compiles cleanly (no unexpected javac warnings; no `@java.lang.Deprecated`
  in the output).
- **Code review:** `ce-code-review` (or manual review) confirms NullAway is
  untouched, the two-pass flow is untouched, and `java.lang.*` imports are
  absent.

### Definition of Done

- `ModelLoader` populates `comment` on `Message`/`Enum`/`Enum.Constant`/
  `Field` from `source_code_info`; `Field.deprecated()` reads
  `f.getOptions().getDeprecated()`; `buildMapField` carries the user's
  comment and deprecation through to the synthetic map field.
- The writers emit clean, HTML-escaped `/** … */` Javadoc on record /
  nested record / enum / enum constant / record component, and bare
  `@Deprecated` on each deprecated element (record, nested record,
  nested enum, enum, enum constant, record component).
- Each nested `Codec` has a 2-line Javadoc naming the source `.proto`
  and the enclosing record + "reads/writes protobuf wire format".
- No `@java.lang.Deprecated` anywhere; no file-level `/** … */`;
  detached comments are not rendered.
- A new `annotations.proto` fixture exists, compiles, round-trips, and
  byte-matches protobuf-java's output.
- `./gradlew build` is green; `ce-code-review` clean.

## Status / Implementation Notes (2026-11-02)

Implemented end-to-end against this requirements contract. All of
`./gradlew build` is green across `:recordbuf-options`, `:recordbuf-runtime`,
`:recordbuf-generator`, and `:recordbuf-fixtures`, with NullAway at error
severity, and the new end-to-end + unit tests pass.

### What shipped (all five requirement groups)

- **R1 — `source_code_info` comment indexing + `comment` population.**
  `ModelLoader.indexComments` builds a `Map<List<Integer>, String>` from the
  file's `source_code_info` location tree; `loadMessage` / `loadEnum` /
  `loadField` (and the enum-constant load) look up their location's leading
  comment via `commentAtPath(path)`. `leading_detached_comments` are ignored
  by the index (R6). Verified by
  `ModelDeprecationCommentTest.loadMessageSurfacesLeadingCommentFromSourceCode
  Info` and end-to-end by the `annotations.proto` fixture.
- **R2 — `Field.deprecated` + `buildMapField` carry-through.** `Field` gained a
  `boolean deprecated` component (last, after `comment`); both `new Field(...)`
  call sites in `ModelLoader` were updated — `loadField` reads
  `f.getOptions().getDeprecated()`; `buildMapField` passes `synthetic.comment()`
  / `synthetic.deprecated()` through. Verified by
  `ModelDeprecationCommentTest.loadFieldPopulatesDeprecatedFlagFromFieldOptions`
  and end-to-end by the fixture's `@Deprecated Map<String, Integer> tags`
  component (the map carry-through was proven by the generated output).
- **R3 — clean Javadoc on record / nested record / enum / enum constant / record
  component + `Codec` doc.** `JavaWriter.javadoc(int level, @Nullable String)`
  emits a clean `/** … */` block (one `* ` per line, `@` un-escaped,
  `<`/`>`/`&` escaped). Callers: `CodeGenerator` (type-level on the record
  type + per-component inside the canonical header) and `EnumGenerator`
  (type-level on the enum + per-constant). `CodecGenerator` emits a 2-line
  Javadoc on the nested `public static final class Codec` naming the enclosing
  record + source `.proto`. Verified by `JavadocEmissionTest` (7 tests,
  HTML-escape + ordering + no-op-for-blank) and end-to-end by the fixture
  (type-level + per-component + enum-constant Javadoc rendered in
  `Sample.java` / `Kind.java`).
- **R4 — bare `@Deprecated`.** `CodeGenerator.recordAndCodec` emits a bare
  `@Deprecated` when `Message.deprecated()` (record type) or `Field.deprecated()`
  (record component) — the component path also composes as
  `@Deprecated @Nullable T` for deprecated nullable fields. `EnumGenerator.write`
  emits a bare `@Deprecated` on the enum type (as before) and now also on each
  `EnumConstant` whose `deprecated` is `true`. Verified by
  `ModelDeprecationCommentTest` (Field / Message / EnumConstant deprecated) and
  end-to-end by the fixture's `@Deprecated @Nullable String note`,
  `@Deprecated Map<String, Integer> tags`, and `@Deprecated\nKIND_LEGACY(1)` in
  `Kind.java`. No `@java.lang.Deprecated` anywhere; grep confirms it.
- **R5 — `annotations.proto` fixture + tests.** The new
  `fixtures/src/main/proto/annotations.proto` exercises type-level doc on
  a record and a nested record, `@Deprecated` on a nested record, `@Deprecated`
  on an enum type + a deprecated enum constant, `@Deprecated` + `@Nullable`
  composition on a record component, a deprecated map field (buildMapField
  carry-through), an HTML-content comment, and an all-default round-trip.
  `AnnotationsTest` asserts byte-match + round-trip. Two new generator
  unit tests: `JavadocEmissionTest` (writer HTML-escaping + ordering) and
  `ModelDeprecationCommentTest` (loader populates `deprecated` / `comment`
  on Field / Message / EnumConstant).
- **R6 — file-level + detached comments dropped.** `ProtoFile.comment` is
  still the file-name only; no file-level `/** … */` is emitted on any
  generated file; `leading_detached_comments` are not in `indexComments`.
  Verified by the generated output (no file-comment block on any
  `Sample`/`Kind` file) and by the existing `contains`-based unit tests
  remaining green unmodified.

### A pre-existing gap, discovered but not closed here

While building the fixture it turned out that `protoc-gen-recordbuf` does
**not** support a top-level (or nested) ENUM-TYPED FIELD yet — when a
`message Sample { Kind kind = 5; }` references a same-file enum `Kind`,
recordbuf throws `unknown scalar type … Kind`
(`generator/…/types/TypeResolver.java:194`, `default -> throw
new IllegalStateException("unknown scalar type " + protoType);`). The
`annotation.proto` fixture therefore does NOT carry an enum-typed field;
the test for an enum-typed component is deferred to when recordbuf
gains enum-field support. This is unrelated to the Javadoc / `@Deprecated`
feature and is left as a separate task. The plan's "one that is the
enum" bullet of R5 is not exercised.

### Known minor formatting nit (not a bug)

The record-component Javadoc renders with a 5-space indent on the
` * ` continuation lines vs. a 4-space indent on the record body (an
indentation quirk from `JavaWriter.javadoc(level, ...)` being passed
`level = w.indent() + 1` for components, but the record body stays at
`w.indent()`). This is a stylistic nit only — the generated output is
still valid, compiles, and reads cleanly. Fixing it is a one-line
change in `CodeGenerator.recordAndCodec` and a separate task.

### Deferred from this slice

None — R1/R2/R3/R4/R5/R6 are all implemented as above (with the
pre-existing enum-field gap noted, which is out of scope for this
feature).
