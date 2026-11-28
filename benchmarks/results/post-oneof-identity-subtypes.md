# Post oneof identity subtypes (Unwrapable redesign)

Generator commits `c476c3e`, `75f8094`, `389550c` — oneof message/enum cases became identity
subtypes, wrappers implement `Unwrapable<T>`, bare sealed-marker interfaces. Full matrix,
`-i 10` with `-prof gc`, same machine as the earlier baselines.

```
Benchmark                                  Mode  Cnt        Score      Error   Units
ConformanceBenchmark.decode               thrpt   10  4629589.477 ± 44162.990  ops/s   (unchanged from 4,648,059 baseline)
ConformanceBenchmark.decode:gc.alloc.rate.norm      1616.002 B/op                         (was 1600 — see note)
ConformanceBenchmark.protoDecode          thrpt   10  2716455.546 ± 48291.999  ops/s   (was 2,663,419, within noise)
ConformanceBenchmark.protoDecode:gc.alloc.rate.norm 1656.003 B/op
SmallMessageBenchmark.decode              thrpt   10  14651239.965 ± 54253.813 ops/s   (was 15,638,611 — outside noise, unexplained, path untouched)
SmallMessageBenchmark.decode:gc.alloc.rate.norm       560.000 B/op                        (unchanged)
```

Full-matrix highlights vs the older `post-review-fixes` full baseline (spans several changes,
informational only): recordbuf-side build paths are uniformly +16 B/op
(`fromProto` 4808.004 → 4824.004, `interopDecode` 6464.006 → 6480.006, `decode` 1600 → 1616.002),
while every protobuf-java-side and encode path is bit-identical
(`toProto` 7144.004, `protoEncode` 1544.002, `interopEncode` 11016.008,
small `protoDecode` 384.001, `protoEncode` 136.000, `toProto` 168.000, `fromProto` 96.000,
`interopEncode` 344.001, `interopDecode` 440.001).

Notes:
- The +16 B/op on recordbuf decode/build paths is one extra 16-byte object (a
  reference-holding record). Identity work removes wrappers, so this is not expected from
  that change — allocation profile pending to find the site.
- Small-message decode −6.3% despite bit-identical allocation and an untouched path; likely
  machine variance, worth a re-run.
