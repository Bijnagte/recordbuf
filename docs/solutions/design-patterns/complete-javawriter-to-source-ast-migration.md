---
title: "Complete a JavaWriter-to-source-AST migration for a code generator with a fallback path"
date: "2026-11-10"
category: "docs/solutions/design-patterns"
module: "bijnagte.recordbuf.plugin / codegen"
problem_type: "design_pattern"
component: "codegen_architecture"
severity: "medium"
applies_when:
  - "A code generator has an old string-concatenation implementation and a new source-AST implementation, and you need to complete the migration safely"
  - "A source-AST node renders method-call syntax wrong in specific cases (e.g., missing call parens for a getter invocation)"
  - "Stale compiled classes in a non-standard output directory mask a source-level bug during verification"
tags:
  - "codegen"
  - "migration"
  - "source-ast"
  - "fallback"
  - "avro"
  - "interop"
root_cause: "incomplete_migration"
resolution_type: "code_fix"
---

# Complete a JavaWriter-to-source-AST migration for a code generator with a fallback path

## Context

The `:recordbuf-generator` plugin builds Java source as a `ClassSource` AST and
renders it through `SourceRenderer` (the "source-AST path"). An older `JavaWriter`
string-concatenation implementation coexisted as a fallback. During a large
refactor, many code generators were migrated one by one from JavaWriter to
`*SourceMapping` classes producing `ClassSource` trees.

Two specific code paths were still on JavaWriter after the bulk of the migration:
- **Oneof fields on a message record** — the data path (`Field` → nested
  message) was complete, but the *codec* path (encode/decode arms for
  `Oneof`/`OneofField`) was not ported.
- **Avro converter** (`XAvroConverter`) — the entire converter was still on
  JavaWriter.

Both were completed this session. The Avro converter migration surfaced two
non-obvious bugs that were invisible until a clean build was forced.

## Guidance

### 1. Port the codec before deleting the fallback

The `AvroConverterGenerator` (JavaWriter, string-concatenation) and
`RecordSourceMapping`/`CodecSourceMapping` (source-AST) both emitted `XAvroConverter`.
The source-AST version had a bug that was silently masked because the compiled
class on disk was the *JavaWriter* version (see bug #2 below).

**Rule:** Before deleting the fallback, do a **clean** build
(`rm -rf build/ bin/` on every module) that forces both `compileJava` and
`compileTestJava` to re-run from source, so stale class files cannot mask a
source-level defect.

### 2. Watch for missing method-call parens in source-AST

When rendering a method *call expression* in the source-AST (not just the
method *node*), the node must include both the receiver member chain and the
argument list. A common pitfall: an "Avro to record" bridge call like
`WktAvroBridge.fromAvro(instant)` was rendered in JavaWriter as the literal
string `WktAvroBridge.fromAvro(field.getTs())`, so the `()` parens were
hard-coded. After porting to `Expression.method("fromAvro", "in")` and
`Expression.member("field.getTs")`, the rendered source was `WktAvroBridge.fromAvro(field.getTs)` —
**missing the call parens**. The Avro converter class does not use a generic
`Expression.call(...)` for this — it renders the receiver as a plain
`Expression.member("WktAvroBridge.fromAvro", "s.getTs")` and relies on the
arg-list node to supply the parens, but the args node was empty.

**Fix:** For a two-argument bridge `bridge.fromAvro(getter)`, the correct
expression tree is:

```java
// WRONG — renders: bridge.fromAvro(s.getTs)
Expression.method("fromAvro", "s.getTs")  // no argument list

// RIGHT — renders: bridge.fromAvro(s.getTs())
Expression.call(Expression.member("someType", "fromAvro"), memberChain("s.getTs"))
// or, if bridge calls are rendered as a receiver+arg pattern:
Expression.member("WktAvroBridge", "fromAvro").withArgs(
    Expression.member("s", "getTs"))
```

The symptom is a compile error `cannot find symbol` on an expression like
`WktAvroBridge.fromAvro(s.getTs())` where the rendered source lacks the
inner parens — or it silently produces `WktAvroBridge.fromAvro(s.getTs)` which
Java parses as an implicit method reference or a compile error depending on
context.

### 3. Stale `bin/main/` and non-standard output dirs mask source bugs

Gradle normally compiles to `build/classes/`, but some modules (or IDEs)
compile to `bin/`. In `recordbuf`, the `:recordbuf-generator` module was
compiling to `generator/bin/main/` (possibly from an IDE or a custom Gradle
config). A `:compileJava` task that appeared up-to-date did not recompile,
so tests ran against the *old* JavaWriter class even after the source was
changed. Running `./gradlew build --rerun-tasks` forces a recompile but still
used the stale `bin/` directory if it was on the test classpath.

**Rule:** Before claiming a source-level fix is verified, check whether any
non-`build/` output directory contains compiled classes that are on the test
classpath:

```bash
find . -name "bin" -type d | grep -v node_modules
ls -la generator/bin/main/bijnagte/recordbuf/plugin/writer/AvroConverterGenerator.class 2>/dev/null
# Compare timestamps:
stat -f '%m' generator/src/main/java/.../AvroConverterGenerator.java
stat -f '%m' generator/bin/main/bijnagte/recordbuf/plugin/writer/AvroConverterGenerator.class
```

If a compiled class is newer than its source, it is a stale build output.
Delete it (`rm -rf generator/bin/`) and rebuild from scratch.

## Why This Works

The source-AST path is type-safe at the node level: each `Expression`,
`Member.Method`, or `Type.Record` is constructed through a builder and
rendered by `SourceRenderer`. The two bugs fixed here were:

1. `toRecord` WKT bridge: the method-call expression was constructed without
   an argument list node, so `field.getTs()` (which was a single
   `Expression.member`) rendered without call parens. The fix was to make
   `Expression.member("s.getTs()")` contain the parens, or use
   `Expression.call(receiver, argList)`.

2. The stale `bin/main/` class shadowed the source-AST output because
   `compileTestJava` used the compiled class from `bin/main/` rather than
   `build/classes/`. A clean build fixes this permanently.

## Prevention

- **After porting any code generator to source-AST:** run a full clean build
  (`rm -rf */build/ */bin/`, `./gradlew build --rerun-tasks`) and compare
  the generated fixture output against a reference before claiming green.
- **Before deleting a fallback path:** run the clean build so the test
  suite proves the source-AST output, not the fallback, is what's being
  tested.
- **For two-argument bridge expressions in source-AST:** always use
  `Expression.call(receiver, argList)` so the parens come from the arg-list
  node — never hard-code `s.getTs()` as a string inside an expression that
  is then called with no arg-list node.
## Related

- `docs/solutions/design-patterns/native-well-known-type-generation.md` — WKT
  bridge patterns (the `WktAvroBridge` / `WktCodecs` bridge pattern that this
  doc's bug #1 touches)
- `docs/solutions/design-patterns/proto-codegen-wire-compat-interop-harness.md` —
  the interop harness that verifies byte-level wire compatibility
- `docs/plans/2026-11-07-002-refactor-javapoet-writer-plan.md` — the full
  refactor plan covering all source-AST migrations
