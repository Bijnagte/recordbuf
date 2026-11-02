---
module: bijnagte.recordbuf.plugin
component: build_tooling
type: tooling_decision
severity: high
title: Enforcing existing JSpecify @NullMarked/@Nullable with NullAway via net.ltgt.errorprone
applies_when:
  - "A module declares JSpecify @NullMarked/@Nullable but nothing enforces it statically and you want a compiler-time check"
  - "A prior note says the null-checker is "unresolvable" or "DEFERRED" and you need to (re)confirm it can be wired"
  - "Wiring Error Prone / a dataflow checker into a Kotlin-DSL Gradle build on a recent JDK"
root_cause: wrong_premise_plus_wrong_api
resolution_type: config_fix_plus_contract_fix
tags:
  - nullaway
  - error-prone
  - jspecify
  - jdk25
  - gradle
  - kts
  - build-tooling
  - recordbuf
---

# Enforce JSpecify `@NullMarked`/`@Nullable` with NullAway (via `net.ltgt.errorprone`)

## Context

`recordbuf`'s generator model layer (`bijnagte.recordbuf.plugin.{,model,types,writer}`)
was already annotated `@NullMarked` on `package-info.java` with per-member
`@Nullable` escapes, but **nothing was enforcing the annotations** - the
annotations documented intent only. A recurring note in the project's `AGENTS.md`
and build files claimed null checking was "DEFERRED" because "`com.uber:nullaway`
has no resolvable versions in this env." That was two wrong premises:

1. **Wrong coordinate.** The real artifact is
   `com.uber.nullaway:nullaway` (the group *is* `com.uber.nullaway`). Catalog
   pins the bare `com.uber:nullaway` at `0.14.3` - which never exists. `0.14.3`
   resolves cleanly on Maven Central; its transitive deps (`com.google.jspecify:
   jspecify` and `com.uber.nullaway:dataflow-nullaway`, both `4.3.1`) resolve
   too.
2. **Wrong API for the flags.** NullAway's scope options must go through the
   plugin's DSL. The prior build-file comment claimed you needed
   `JSpecifyMode=true` + `OnlyNullMarked=true` as two raw `compilerArgs`
   `-XepOpt` flags. Neither is true: `JSpecifyMode` is gone entirely (0.14.x
   auto-detects JSpecify on JDK 22+), and passing *any* `-XepOpt` via raw
   `compilerArgs.add("…")` is rejected as `invalid flag` on JDK 16+ with this
   plugin.

## The durable mechanism

### 1. Fix the catalog coordinate (root cause)

In `gradle/libs.versions.toml`, the NullAway coordinate group is
`com.uber.nullaway`, not `com.uber`. Align the `jspecify` pin to the version
NullAway transitively pulls (`1.0.1` for `0.14.3`) so a single JSpecify version
is on the classpath.

### 2. Wire the plugin per module (Kotlin DSL)

```kotlin
// generator/build.gradle.kts (and the same in runtime/build.gradle.kts)
import net.ltgt.gradle.errorprone.errorprone   // required for the DSL

plugins {
    java
    alias(libs.plugins.gradle.errorprone)       // v5.1.1 from the catalog alias
    // ...
}

dependencies {
    // The generated user-record/codec types pull jspecify transitively; this
    // module needs it on its own compile classpath for @Nullable/@NullMarked.
    compileOnly(libs.jspecify)
    // NullAway runs as an Error Prone check via the `errorprone` configuration,
    // which the plugin wires onto the annotation-processor path of every Java
    // compile task. error-prone-core loads Error Prone itself.
    errorprone(libs.nullaway)
    errorprone(libs.error.prone.core)
    // ...
}
```

### 3. Scope the checker to the code you own, via the **`options.errorprone` DSL**

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableAllChecks = true                       // NullAway-only; drop EP defaults
        error("NullAway")                             // fail the build on any null violation
        option("NullAway:OnlyNullMarked", "true")     // don't touch un-@NullMarked code
        excludedPaths = ".*build/generated.*"         // generator only: skip record-builder-generated noise
    }
}
```

**`options.errorprone { … }` is the required API. Do NOT write**
`options.compilerArgs.add("-XepOpt:NullAway:…")` - on JDK 16+ the plugin rejects
that as `invalid flag` because the plugin does not auto-add JEP 396's
`--add-exports`/`--add-opens` for the check when they come via raw compiler
args.

### 4. `OnlyNullMarked` keeps out-of-scope generated glue out of scope

`OnlyNullMarked=true` restricts NullAway to `@NullMarked` packages, so the
generated `com.google.protobuf.compiler.PluginProtos` shim (an un-`@NullMarked`
protocol-glue file the project doesn't touch) is automatically outside scope.
This is the clean, project-consistent way to keep the shim out - no per-file
suppression.

### 5. `excludedPaths` for the record-builder-generated sources (generator only)

The `@RecordBuilder` annotation processor generates `*Builder`/`*With`
interfaces/sources into `build/generated/annotationProcessor/`. Record-builder's
generated builder methods do not propagate the `@Nullable` from the component of
record types whose components are `@Nullable`, so NullAway flags `returning
@Nullable` on the generated `withX(...)` and `build()` methods of these
generated classes. They're **not first-party code** - exclude the directory:

```kotlin
excludedPaths = ".*build/generated.*"
// ~25 findings drop for the generator; runtime has no generated sources and
// needs no such filter.
```

### 6. `NullAway` at error severity + disable the rest

`disableAllChecks = true` + `error("NullAway")` turns on ONLY NullAway, at
`error` severity - so a null violation fails the compile. The prior `~71`
"tolerated default-check warnings" are gone because we turned off the default
check set instead of living with them. Non-null default checks on the un-
`@NullMarked` shim are moot because `OnlyNullMarked` already excludes the shim.

## Why JSpecifyMode is NOT a flag you set

A common trap: prior NullAway build-file recipes say "on JDK 22+ set
`JSpecifyMode=true` and `OnlyNullMarked=true`." Only `OnlyNullMarked` is real
today. `JSpecifyMode` was **added** to make NullAway on pre-JSpecify-JDKs read
JSpecify instead of its own annotation set; on JDK 22+ it's auto-detected, so
passing `JSpecifyMode=true` at all is an **unknown-flag rejection** that fails
the compile. (0.14.x is the version where this matters most.)

## The real first-party findings (each, and why)

These were fixed directly (annotations or logic), not suppressed:

- `ModelLoader.shortName(String)` had a `if (typeUrl == null) return null;` guard
  on a `@NullMarked` static that already received `@NonNull` input - **dead
  guard**, removed. Same for `TypeResolver.mapComponentType(@Nullable Object)`
  and `wireScalarForType(@Nullable Object)` - `f.type()` is never null on the
  call site.
- `FileTable.javaFqn` / `byName` / `findNestedMessage` / `CodeGenerator.findByPath`
  return "the thing if present, else null" - annotate the return / local
  `@Nullable`. `applyPackageSuffix`'s contract is "blank side is a no-op, never
  null" - make its params `@Nullable` and blank-normalise (`base ?? ""`).
- `ModelLoader.extractDefault` already returns `null` for a field with no default
  - annotate `@Nullable Object`.
- `CodecGenerator.lastSeg` / `JavaNames.snakeUpper` had dead `if (s == null)`
  guards on `@NullMarked` non-null input - removed.
- The `FileTablePackageSuffixTest.applyPackageSuffix(name, null)` call sites now
  call a `@Nullable`-accepting method - no change needed once the signature is
  updated.
- The generated `*Builder`/`*With` noise in `build/generated/annotationProcessor/`
  is `excludedPaths`'d, not fixed (it's not first-party code).

## When to Apply

- Anywhere a project has JSpecify annotations on the model/contract layer but no
  static enforcement, and the annotations are being ignored.
- When a prior note says a checker is "unresolvable" or "DEFERRED" - re-derive
  the real coordinate, and check whether the "flag" recipe is actually
  applicable on the current toolchain before accepting the blocker.
- Kotlin-DSL `build.gradle.kts` wiring for `net.ltgt.errorprone` on JDK 16+.

## When NOT to apply

- Un-`@NullMarked` third-party code in the module (the `com.google.protobuf`
  shim is the canonical example here): keep it out via `OnlyNullMarked=true`,
  don't annotate it.
- Generated sources a build tool *must* produce (`build/generated/annotationProcessor/`):
  `excludedPaths` rather than trying to fix what a tool emits.
- A "DEFERRED because unresolvable" note that turns out to be a coordinate bug
  that was fixed this session - the right move was to fix the coordinate, not to
  accept the blocker.
