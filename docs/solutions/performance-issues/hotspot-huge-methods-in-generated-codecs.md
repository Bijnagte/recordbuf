---
title: Wide-message codecs run interpreted because HotSpot skips huge generated methods
date: 2026-11-26
category: docs/solutions/performance-issues
module: bijnagte.recordbuf.plugin.writer
problem_type: performance_issue
component: tooling
symptoms:
  - "Conformance encode ran at 214K ops/s against protobuf-java's 4.1M, a 19x gap"
  - "Encode allocated 2,920 B/op although only five fields were set"
  - "Adding -XX:-DontCompileHugeMethods made encode 6x faster with no code change"
root_cause: logic_error
resolution_type: code_fix
severity: high
tags: [jmh, hotspot, jit, codegen, encode, decode, hugemethodlimit]
---

# Wide-message codecs run interpreted because HotSpot skips huge generated methods

## Problem
After the two-pass encode (size pass, then write into a pre-allocated buffer), the narrow benchmark
message got fast but the wide `TestAllTypesProto3` message got about 7x slower than the old
`Encoder` path. The generated `writeBody` and `getSerializedSize` emit one statement per field, so a
message with about 150 fields produced methods past HotSpot's limit.

## Symptoms
- `ConformanceBenchmark.encode` 214K ops/s versus 4.15M ops/s for protobuf-java.
- `-XX:-DontCompileHugeMethods` took it to 1.29M ops/s, which isolated the cause.
- `SmallMessageBenchmark` was unaffected, which is why the regression hid until the wide case ran.

## What Didn't Work
- Blaming per-field cost ("two-pass work is proportional to field count"). It is, but a 7x loss on a
  message with five populated fields is a JIT cliff, not arithmetic.
- Allocation work alone (WKT and map sizing, `encodeTo`, `lenDelimSize`). Those removed bytes per op
  but left an interpreted method interpreted.

## Solution
Split bodies that exceed `CHUNK_SLOTS` (16) slots into private parts, called in order:

```java
// generated, only for messages with more than 16 field/oneof slots
private static void writeBody(CodedOutputStream out, Msg v) throws IOException {
  writeBodyPart0(out, v);
  writeBodyPart1(out, v);
  // ...
}
public static int getSerializedSize(Msg v) {
  int size = 0;
  size += sizePart0(v);
  size += sizePart1(v);
  size += v.unknownFields().getSerializedSize();
  return size;
}
```

The change is in `CodecSourceMapping` (`writeSlots`, `sizeSlots`, `chunkParts`). Messages at or under
the threshold generate the same code as before. Result: conformance encode 214K to 10.3M ops/s and
2,920 to 104 B/op (`benchmarks/results/post-chunking-full.md`).

## Why This Works
HotSpot never JIT-compiles a method over 8000 bytecodes (`HugeMethodLimit`, controlled by
`-XX:-DontCompileHugeMethods`). Each part stays far below it, so C1 and C2 compile all of them. Users
cannot be asked to pass a JVM flag, so the generator has to stay under the limit.

## Prevention
- Benchmark one wide message, not only small ones. A narrow message cannot show this cliff.
- When a benchmark is unexpectedly slow, run it with `-XX:+PrintCompilation` and look for
  `COMPILE SKIPPED`, and try `-XX:-DontCompileHugeMethods` to confirm.
- **Known unfixed limit (decode):** `decodeBody` for the same message is 8,323 bytes and its record
  constructor takes about 150 arguments. C1 reports "out of virtual registers in linear scan", C2
  reports "unsupported calling sequence" (also for the record's `<init>`), so wide-message decode
  stays interpreted even with the flag. Fixing it needs decode through a mutable accumulator rather
  than one canonical-constructor call; this was judged not worth it for rare messages of this width.
  Lazy allocation of repeated and map locals did cut that decode from 3,512 to 1,016 B/op
  (`benchmarks/results/post-lazy-collections.md`), at the cost that an absent collection is now an
  immutable empty one.

## Related Issues
- `docs/plans/2026-11-26-003-feat-encode-allocation-decode-policy-plan.md`
- `benchmarks/results/` holds the baselines for each step.
