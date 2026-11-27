---
title: Profiling JMH benchmarks with JFR and async-profiler
date: 2026-11-27
category: docs/solutions/performance-issues
module: recordbuf-benchmarks
problem_type: performance_issue
component: tooling
symptoms:
  - "JMH shows ops/s and B/op but not which methods burn the time or allocate"
  - "Optimization work relied on guessing or one-off profiler setups"
root_cause: missing_tooling
resolution_type: tooling
severity: medium
tags: [jmh, jfr, async-profiler, profiling, flamegraph, allocation]
---

# Profiling JMH benchmarks with JFR and async-profiler

## Problem
The benchmarks report throughput and bytes per operation (`-prof gc`) but not where the time or the allocation comes from. Agents cannot drive GUI profilers, so the answer has to be text or a file.

## Solution
`./gradlew :recordbuf-benchmarks:profile -Pbench=<regex> [-PprofileQuick]` (in `benchmarks/build.gradle`) runs the benchmark in the JMH fat jar with:

- **JFR** (always): the forked JVM gets `-XX:StartFlightRecording=...,settings=profile` and `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`. The task then prints `jfr view hot-methods` and `jfr view allocation-by-site` from the recording. No install is needed; the JDK ships `jfr`.
- **async-profiler** (optional): when `ASYNC_PROFILER_LIB` points at `libasyncProfiler.dylib`/`.so`, JMH's built-in `-prof async` adds a CPU flame graph (`flame-cpu-forward.html`, `flame-cpu-reverse.html`) beside the recording.

Artifacts go to `benchmarks/build/profiles/<bench>/`. The `jmh` task is untouched.

## Why These Tools
- JFR is in every JDK, runs headless, and `jfr view` gives readable tables. JMH 1.37 has no `-prof jfr`, so the flags go through `-jvmArgsAppend`.
- async-profiler avoids safepoint bias and draws flame graphs. It needs a native library per platform, so it is opt-in. On macOS it uses the itimer engine, since perf_events is Linux-only.
- `DebugNonSafepoints` lets samples land on real lines inside inlined code; without it samples cluster at safepoints.
- GUI-only profilers (VisualVM, JMC, YourKit, JProfiler) were left out. JMC can still open the `.jfr`.

## Prevention
- Profile one benchmark at a time; the JFR recording covers the whole fork, including warmup.
- Read the allocation table together with `-prof gc` B/op from `./gradlew :recordbuf-benchmarks:jmh`: JFR samples allocation, so it ranks sites rather than counting bytes.
- First result worth knowing: profiling `ConformanceBenchmark.decode` puts about 88% of CPU samples and 95% of allocation pressure in the generated `TestAllTypesProto3Codec.decodeBody`, matching the wide-message JIT limit in `hotspot-huge-methods-in-generated-codecs.md`.

## Related
- `docs/plans/2026-11-27-001-feat-jmh-profiling-setup-plan.md`
- `docs/solutions/performance-issues/hotspot-huge-methods-in-generated-codecs.md`
