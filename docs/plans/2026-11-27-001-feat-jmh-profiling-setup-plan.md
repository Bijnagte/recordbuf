---
title: JMH Profiling Setup - Plan
type: feat
date: 2026-11-27
topic: jmh-profiling-setup
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
---

# JMH Profiling Setup - Plan

## Goal Capsule

- **Objective:** Make CPU and allocation profiling of the existing JMH benchmarks a one-command workflow that an agent can run and read.
- **Product authority:** This document's Product Contract.
- **Open blockers:** None.

## Product Contract

### Summary

Add a profiling workflow to `:recordbuf-benchmarks` built on free tools: JFR as the zero-setup baseline and async-profiler as an optional sharper layer. One Gradle task profiles a named benchmark and prints a short text summary of the top CPU and allocation frames.

### Problem Frame

The benchmarks in `benchmarks/` report throughput and bytes per operation (`-prof gc`) but not where the time or allocation comes from. Optimization work such as the hotspot finding in `docs/solutions/performance-issues/hotspot-huge-methods-in-generated-codecs.md` has to guess or profile ad hoc. Agents cannot drive GUI profilers, so the output must be text or files they can read.

### Key Decisions

- **JFR is the baseline.** It ships with the JDK, runs headless, works on macOS and Linux, and its output can be read as text (`jfr print`, `jfr view`).
- **async-profiler is optional and auto-detected.** It gives sharper CPU, wall-clock and allocation profiles and flame graphs, but needs a native binary per platform. The workflow uses it when present and falls back to JFR when not.
- **Free tools only.** Commercial profilers and GUI-only workflows are not part of the workflow.

### Requirements

**Invocation**
- R1. One Gradle task in `:recordbuf-benchmarks` profiles a benchmark selected by name.
- R2. The task works with no installs beyond the project toolchain (JFR path).
- R3. The task uses async-profiler when it is installed and says which profiler ran.

**Output**
- R4. A run produces both a CPU profile and an allocation profile.
- R5. The task prints a short text summary of the top CPU and allocation frames.
- R6. Raw artifacts (JFR recording, collapsed stacks or flame graph) are kept under `benchmarks/build/`, not committed.

**Documentation**
- R7. The README and a `docs/solutions/performance-issues/` note describe the workflow, the JFR-versus-async-profiler trade-off, and the macOS limits (itimer engine, no perf_events).

### Success Criteria

- An agent can run one command for a named benchmark, read the text summary, and identify the top CPU and allocation frames without opening a GUI.
- Existing `jmh` runs and results files are unaffected.

### Scope Boundaries

- Commercial profilers (YourKit, JProfiler) and GUI-only tools (VisualVM, JMC).
- Committing profile history or tracking regressions across commits.
- CI integration.
- Changes to benchmark code or generated codecs.

### Outstanding Questions

**Deferred to Planning**
- How async-profiler is located: environment variable, PATH, or a documented install path.
- Whether the text summary comes from `jfr view` or from collapsed-stack aggregation.
- Whether profiling passes through the `jmh` plugin's `-prof` options or runs a separate JVM invocation.

---

## Planning Contract

Product Contract unchanged.

### Context and Research

- `benchmarks/build.gradle` applies the `me.champeau.jmh` plugin (0.7.4, JMH 1.37) and sets `jmhArgs = '-prof gc'`. Compile settings for `compileJmhJava` are already handled there.
- JMH 1.37 ships `-prof async` (`org.openjdk.jmh.profile.AsyncProfiler`; takes `libPath`, `event`, `output`, `dir`) and `-prof stack`. It has no `-prof jfr`, so JFR comes from JVM flags passed to the forked benchmark JVM.
- The JDK on this machine (Temurin 25) provides `jfr view`, which prints text tables such as `hot-methods` and `allocation-by-site` from a recording. It needs no extra tooling.
- async-profiler is not installed here (no `asprof`, no brew package). On macOS arm64 it runs with the itimer engine for CPU sampling. Its output formats include `jfr`, `collapsed` and `flamegraph`.
- Precedent: `docs/solutions/performance-issues/hotspot-huge-methods-in-generated-codecs.md` records a JMH-driven finding that profiling would have exposed faster.

### Key Technical Decisions

- **KTD1. Run the profile as a task in `benchmarks/build.gradle`, not a new module.** The task reuses the JMH classpath and the plugin's include filtering. This keeps `:recordbuf-benchmarks:jmh` unchanged (R-scope: existing runs unaffected).
- **KTD2. JFR flags go to the forked JVM through JMH's JVM-args option.** Use the `profile` settings plus `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`, so sampled frames land on real lines in inlined code. One recording per fork is written under `benchmarks/build/profiles/<benchmark>/`.
- **KTD3. The text summary comes from `jfr view`** (hot methods and allocation by site), run by the task after the benchmark. No custom parser is written. Allocation sampling uses JFR's `jdk.ObjectAllocationSample`, which is on in the `profile` settings.
- **KTD4. async-profiler is located by an environment variable** (`ASYNC_PROFILER_LIB`, path to `libasyncProfiler.dylib`/`.so`). When it is set and the file exists, the task adds `-prof async` with flamegraph output and prints the paths; otherwise it prints that JFR only was used. No auto-download, so there is no network step in the build.
- **KTD5. Profile with the default (non-`-prof gc`) JMH measurement settings, but fewer iterations.** The task exposes only the benchmark name and an optional shorter-run toggle, since the goal is attribution, not a score.

### Deferred to Implementation

- Whether async-profiler can sample CPU and allocation in a single `-prof async` instance via `rawCommand`, or needs two separate runs. If two runs are needed, the task runs them sequentially.
- The exact JMH option syntax for passing JVM args from the Gradle plugin versus the `JavaExec` route. Choose whichever keeps `jmh` untouched.
- How much of the `jfr view` output to print (row limit) so the summary stays short.

### Scope Boundaries

Carried from the Product Contract. Not planned here: committing profiles, CI, changes to benchmark code or generated codecs.

---

## Implementation Units

### U1. Profiling task with JFR

- **Goal:** One Gradle task profiles a named benchmark with JFR and writes artifacts under `benchmarks/build/profiles/`.
- **Requirements:** R1, R2, R4, R6
- **Dependencies:** none
- **Files:** `benchmarks/build.gradle`
- **Approach:** Register a `profile` task next to `jmh`. It takes the benchmark name from a project property (for example `-Pbench=ConformanceBenchmark.decode`), fails with a clear message when it is missing, and runs JMH with the forked-JVM flags from KTD2. The output directory is per benchmark.
- **Patterns to follow:** The existing `tasks.named('jmh')` configuration in `benchmarks/build.gradle`.
- **Test scenarios:**
  - Happy path: `-Pbench=ConformanceBenchmark.decode` produces a `.jfr` file under `benchmarks/build/profiles/` containing `jdk.ExecutionSample` and `jdk.ObjectAllocationSample` events.
  - Error path: no `-Pbench` fails fast with a message naming the property and an example.
  - Edge case: a name matching no benchmark fails with JMH's no-match error rather than succeeding silently.
  - Integration: `./gradlew :recordbuf-benchmarks:jmh` behaves as before (still `-prof gc`, no JFR files).
- **Verification:** The recording exists and `jfr summary` lists both event types.

### U2. Text summary of CPU and allocation hot spots

- **Goal:** After the run the task prints the top CPU and allocation frames as text.
- **Requirements:** R4, R5
- **Dependencies:** U1
- **Files:** `benchmarks/build.gradle`
- **Approach:** A finalizer (or last action) invokes the JDK's `jfr view` for hot methods and allocation by site on the recording from U1 and prints a bounded number of rows (KTD3).
- **Test scenarios:**
  - Happy path: the console output has a CPU section and an allocation section, each with at least one row naming a recordbuf or runtime method.
  - Edge case: a recording with no allocation samples prints an explicit "no allocation samples" line instead of an empty table.
- **Verification:** The summary is readable on its own; no GUI step is needed to name the top frame.

### U3. Optional async-profiler layer

- **Goal:** When async-profiler is installed, add its profile and say so.
- **Requirements:** R3, R4, R6
- **Dependencies:** U1
- **Files:** `benchmarks/build.gradle`
- **Approach:** Read `ASYNC_PROFILER_LIB` (KTD4). When valid, add JMH `-prof async` with flamegraph output into the same per-benchmark directory, and print the file paths. When unset or the file is missing, print one line saying JFR only was used. Resolve the CPU-plus-allocation question from Deferred to Implementation.
- **Test scenarios:**
  - Happy path: with the library present, a flamegraph file appears and the task output names async-profiler as used.
  - Edge case: variable set to a nonexistent path falls back to JFR and says why.
  - Edge case: variable unset prints the JFR-only line.
- **Verification:** Both paths run end to end on this machine. If async-profiler cannot be installed locally, the fallback path is verified and the async path is documented as unverified.

### U4. Documentation

- **Goal:** Describe the workflow where contributors and agents look.
- **Requirements:** R7
- **Dependencies:** U1, U2, U3
- **Files:** `README.md` (the `benchmarks/` row and a short profiling section), `docs/solutions/performance-issues/` (new note on the profiling workflow), `AGENTS.md` (one line in *How to run / verify*)
- **Approach:** Cover the command, the output locations, JFR versus async-profiler, how to install async-profiler and set `ASYNC_PROFILER_LIB`, and the macOS limits (itimer, no perf_events). Follow the `project-docs` conventions (Mermaid only if a diagram is added).
- **Test expectation:** none -- documentation only.
- **Verification:** Commands in the docs match the task names and properties from U1.

---

## Verification Contract

| Gate | Applies to | Done signal |
|---|---|---|
| `./gradlew :recordbuf-benchmarks:profile -Pbench=ConformanceBenchmark.decode` | U1, U2, U3 | Recording written, text summary printed with CPU and allocation sections |
| Same command with `ASYNC_PROFILER_LIB` set to a valid library | U3 | Flamegraph written, async-profiler named in the output |
| `./gradlew :recordbuf-benchmarks:jmh` (short run) | U1 | Unchanged behavior, no profile files |
| `./gradlew build` | all | Green, including `spotlessCheck` and checkstyle |

## Definition of Done

- R1-R7 from the Product Contract are met.
- The profile task runs on this machine with JFR and prints the summary; the async path is verified or explicitly documented as unverified.
- `./gradlew build` is green.
- README, the solutions note and `AGENTS.md` describe the workflow.
