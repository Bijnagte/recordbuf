---
title: "Document record components via @param in the record's class-level Javadoc, not per-component block comments"
date: 2026-05-27
category: docs/solutions/tooling-decisions
module: bijnagte.recordbuf.plugin
problem_type: tooling_decision
component: tooling
severity: medium
applies_when:
  - "Generating a public Java record whose components should carry per-field documentation"
  - "Choosing where to put per-component Javadoc in generated code that must compile cleanly under javac -Xlint:all and the javadoc tool"
  - "Emitting comment text that contains @word tokens into a Javadoc body"
tags:
  - javadoc
  - record-component
  - record-builder
  - javac
  - codegen
  - dangling-doc-comments
  - recordbuf
---

# Document record components via `@param` in the record's class-level Javadoc

## Context

`recordbuf`'s generator emits a Java `record` per protobuf message, and each
record component previously got documented as a per-component `/** */` block
comment inside the record's component header, e.g.:

```java
public record Foo(
    /**
     * The identifier.
     */
    int id,
    ...
) {
```

This is **not a valid Javadoc location**. The javadoc tool renders `@param` tags
that live inside the record's *own* (class-level) Javadoc — not `/** */` block
comments attached to individual record components. A per-component comment at that
site therefore:

1. is **silently dropped** by the `javadoc` tool — the text never appears in the
   generated HTML; and
2. is flagged by `javac -Xlint:-dangling-doc-comments` as a *dangling doc
   comment* (a doc comment not attached to a valid program-element location) under
   `javac -Xlint:all`.

Both were surfaced while auditing the project's `-Xlint:all` output: the 24
dangling-doc-comment diagnostics were dominated by these per-component comments.
The fix follows the idiomatic path — document a record's components with `@param`
entries in the record's class-level Javadoc, exactly as you would document the
parameters of a method.

## Guidance

Collect one `@param` entry per documented component (in declaration order —
message fields, then oneofs, then the always-on `unknownFields` carrier — a
component with a blank/absent comment contributes none) and fold it, after the
record's type-level summary, into the record's single class-level Javadoc:

```java
/**
 * The Foo message.
 *
 * @param id The identifier.
 * @param bar The Bar oneof: at most one member is present at a time; null when
 *     absent.
 * @param unknownFields Unknown fields seen during this record's decode that its
 *     schema does not model, captured so they survive a round-trip:
 *     re-emitted byte-for-byte on encode. An empty decode carries the shared
 *     bijnagte.recordbuf.runtime.UnknownFields.empty(), so the common case adds
 *     no payload.
 */
public record Foo(int id, Bar bar,
    bijnagte.recordbuf.runtime.UnknownFields unknownFields) {
```

The record's **header stays header-only** (no per-component Javadoc blocks) because
those docs now live in the `@param` tags. The emission lives in
`generator/src/main/java/bijnagte/recordbuf/plugin/CodeGenerator.java`:
`recordJavadoc(...)` assembles the block (type-summary + `@param` lines),
`addComponentParam(...)` appends a documented component, and `oneofComponentDoc` /
`carrierComponentDoc` supply the fixed descriptions for oneof and `unknownFields`
components. A missing `@param` never warns because the project runs **no
javadoc/doclint task** — confirmed: no `javadoc`/`doclint` task exists in any
`build.gradle.kts` (the build is compile + test + assemble + `spotlessCheck`).

### Caveat: `@word` tokens in comment text

Once component docs move into `@param` *descriptions*, the `javadoc` tool **does
parse `@word` runs inside them as block tags**; an unknown one emits
`error: unknown tag "<word>"`. This only bites when a proto *comment's text*
itself contains an `@word` (the generator deliberately keeps a bare `@` intact in
comment text — `JavadocEmissionTest` asserts it). In practice the `javadoc` tool is
not part of recordbuf's build (no doclint task), the diagnostics are non-fatal
(HTML still generates), and real comments rarely carry a lone `@word`. **Decision:**
accept the collision rather than escape `@` in comment text, which would break the
"bare `@` stays intact" feature `JavadocEmissionTest` enforces.

## Why This Matters

- **Fixes real defects at the source.** `@param`-in-class-Javadoc eliminates the
  `dangling-doc-comments` diagnostics *and* makes component documentation actually
  render into generated HTML — two symptoms, one fix.
- **No new build cost.** The change is confined to generated *output*; because no
  doclint task exists, nothing new is gated.
- **The `@`-text caveat is documented, not hidden.** It is a deliberate, recorded
  trade-off against a pre-existing "bare `@` intact" feature — future readers know
  the shape of the collision and the decision already made.

## When to Apply

- When adding per-component documentation to generated records: emit `@param`
  entries in the record's class-level Javadoc, never per-component `/** */`
  blocks.
- When emitting comment text that contains `@word` tokens into a `@param` body:
  expect the `javadoc` tool to parse them as tags; acceptable today because no
  doclint task exists, but worth revisiting if a doclint gate is ever added.

## Examples

**Before (flagged + dropped):**

```java
public record Foo(
    /**
     * The identifier.
     */
    int id, ... ) { }
```

**After (renders cleanly, no dangling diagnostic):**

```java
/**
 * The Foo message.
 *
 * @param id The identifier.
 * @param unknownFields Unknown fields seen during this record's decode ...
 */
public record Foo(int id, ...) { }
```

**Verification (this session):**
- `javac -Xlint:-dangling-doc-comments` on the generated fixtures: `0` diagnostic
  lines (was `24` — all per-component Javadoc).
- `javadoc` HTML render: the `@param` component descriptions (oneof + carrier +
  plain fields like "The identifier") all appear in the generated HTML; a plain
  fixture with no `@`-in-comment text renders with zero warnings.
- Full build (`./gradlew clean build`, incl. `spotlessCheck` + code generation)
  green; all tests pass.

## Related

- [`../design-patterns/unknown-field-capture-reemit.md`](../design-patterns/unknown-field-capture-reemit.md) —
  the `unknownFields` carrier that one of these `@param` entries now documents.
