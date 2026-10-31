# AGENTS.md — recordbuf

Project context for `recordbuf`. Project rules here **override** the user-level
defaults in `~/.pi/agent/AGENTS.md` and the Java skills for this project.

## What this project is

`recordbuf` builds **`protoc-gen-recordbuf`**, a `protoc` code generator plugin
that turns `.proto` files into Java records + protobuf codecs.
- Group / version: `bijnagte.recordbuf` / `0.0.1`.
- Main class: `bijnagte.recordbuf.plugin.Main`; application name
  `protoc-gen-recordbuf`.
- Output of codegen goes to `src/main/java` under the `bijnagte.recordbuf`
  package (subpackages: `plugin`, `plugin.model`, `plugin.types`,
  `plugin.writer`). A `com.google.protobuf.compiler` shim in
  `src/main/java/com/google/protobuf/compiler` holds the protoc plugin-protocol
  classes — treat it as generated/protocol glue; don't hand-rename.

## Build & toolchain overrides (read these)

- **Build tool:** Gradle, **Kotlin DSL** (`build.gradle.kts`). Always use the
  wrapper `./gradlew` — never a global `gradle`.
- **Java level: this project targets Java 25** (`java.release = 25`,
  `sourceCompatibility/targetCompatibility = 25`). The user's active JDK is
  Temurin 25 (LTS); we don't artificially down-level. Use **Java 25 language
  features** when building on the project's toolchain — but generated code
  that goes into *user's* classpaths should stay close to a wider baseline
  (e.g. 17+) unless asked — discuss before changing the target. Don't bump the
  target further without being asked.
- **Nullability / record builders:** the user defaults (NullAway + JSPECIFY,
  `io.soabase.record-builder` via a version catalog) are **not yet wired into
  this project's `build.gradle.kts`.** Don't assume they're active. Wire them
  in deliberately if the user asks; otherwise follow the existing (un-annotated)
  style of the surrounding code.
- **Formatting / logging:** the cross-project requirements — Spotless +
  Google Java Format and SLF4J-only logging — are **not yet wired into
  recordbuf.** Don't assume `spotlessCheck` or a logging framework are present;
  add them deliberately if the user asks. Until then match the surrounding
  (unformatted-by-tool, no-framework) code.
- **Protobuf runtime:** generated codecs use
  `com.google.protobuf.{CodedInputStream,CodedOutputStream}` — keep
  `com.google.protobuf:protobuf-java` (`4.29.3`) on the consumer classpath.

## How to run / verify

- Full check (compile + test + assemble): `./gradlew build`
- Tests (JUnit Platform — no AssertJ/Mockito are in this project's classpath
  yet): `./gradlew test`
- Regenerate the plugin launcher for use by `protoc`: `./gradlew installDist`
  → produces `build/scripts/protoc-gen-recordbuf` (a self-contained launcher
  that execs `java -cp … bijnagte.recordbuf.plugin.Main`).
- Generate code from a `.proto` (after `installDist`):
  ```bash
  protoc --plugin=protoc-gen-recordbuf=$PWD/build/scripts/protoc-gen-recordbuf \
         --recordbuf_out=src/main/java src/main/proto/hello.proto
  ```
- Gradle is configured for **cache + parallel** (`gradle.properties`); expect
  up-to-date cache hits and run `./gradlew --rerun-tasks` only to bypass it.

## Conventions specific to this project

- Match existing code: it is currently **un-annotated** (no NullAway/JSPECIFY)
  and does **not** use record-builders. Add those only if asked and propagate
  them consistently.
- Generated code under `src/main/java` (from `.proto`) — prefer editing the
  generator / source model over hand-patching generated outputs; if you must,
  say so.
- The `com.google.protobuf.compiler` shim: don't rename or refactor without a
  reason.

## Verification before "done"

1. `./gradlew build` is green (compile + `./gradlew test`).
2. If codegen was touched: `./gradlew installDist`, re-generate a sample
   `.proto`, and confirm the generated output compiles + round-trips.
3. No manual reformatting that the project doesn't otherwise enforce.

## Skills (apply automatically, user-level)

`java-code-style`, `java-unit-testing`, `java-gradle-build`, and
`java-design-review` from `~/.pi/agent/skills/` are in scope here. They carry
the cross-project defaults; the overrides above win when they conflict
(principally the **not-yet-wired** nullability / record-builder tooling).
