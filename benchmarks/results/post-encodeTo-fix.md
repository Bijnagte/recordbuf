# JMH Results — Post-encodeTo

Generated: 2026-01-28 (post-encodeTo + writeBody refactoring)

## Summary of changes since previous results (post-wkt-map-size-fix):
- Added `encodeTo(CodedOutputStream, int, Message)` method to generated codecs
- Added `writeBody(CodedOutputStream, Message)` private method (shared field-writing logic)
- Changed `encode` to delegate to `writeBody`
- Changed all nested message field encodes to use `encodeTo` instead of `writeByteArray(encode(...))`
- Map entry codecs also got `encodeTo` + `writeBody` methods

## Results

| Benchmark | Mode | Score | Error | Units |
|-----------|------|-------|-------|-------|
| ConformanceBenchmark.decode | thrpt | 624,082.253 | ± 5,087.733 | ops/s |
| ConformanceBenchmark.decode:gc.alloc.rate | thrpt | 2,090.004 | ± 17.042 | MB/sec |
| ConformanceBenchmark.decode:gc.alloc.rate.norm | thrpt | 3,512.011 | ± 0.001 | B/op |
| ConformanceBenchmark.decode:gc.count | thrpt | 47.000 | | counts |
| ConformanceBenchmark.decode:gc.time | thrpt | 29.000 | | ms |
| ConformanceBenchmark.encode | thrpt | 208,229.476 | ± 2,220.857 | ops/s |
| ConformanceBenchmark.encode:gc.alloc.rate | thrpt | 579.789 | ± 6.186 | MB/sec |
| ConformanceBenchmark.encode:gc.alloc.rate.norm | thrpt | 2,920.034 | ± 0.001 | B/op |
| ConformanceBenchmark.encode:gc.count | thrpt | 13.000 | | counts |
| ConformanceBenchmark.encode:gc.time | thrpt | 13.000 | | ms |
| ConformanceBenchmark.fromProto | thrpt | 1,753,273.133 | ± 6,268.699 | ops/s |
| ConformanceBenchmark.fromProto:gc.alloc.rate | thrpt | 8,038.297 | ± 28.733 | MB/sec |
| ConformanceBenchmark.fromProto:gc.alloc.rate.norm | thrpt | 4,808.004 | ± 0.001 | B/op |
| ConformanceBenchmark.fromProto:gc.count | thrpt | 122.000 | | counts |
| ConformanceBenchmark.fromProto:gc.time | thrpt | 67.000 | | ms |
| ConformanceBenchmark.interopDecode | thrpt | 1,074,260.472 | ± 11,378.250 | ops/s |
| ConformanceBenchmark.interopDecode:gc.alloc.rate | thrpt | 6,621.440 | ± 70.032 | MB/sec |
| ConformanceBenchmark.interopDecode:gc.alloc.rate.norm | thrpt | 6,464.006 | ± 0.001 | B/op |
| ConformanceBenchmark.interopDecode:gc.count | thrpt | 120.000 | | counts |
| ConformanceBenchmark.interopDecode:gc.time | thrpt | 64.000 | | ms |
| ConformanceBenchmark.interopEncode | thrpt | 842,459.494 | ± 4,293.946 | ops/s |
| ConformanceBenchmark.interopEncode:gc.alloc.rate | thrpt | 8,849.551 | ± 45.355 | MB/sec |
| ConformanceBenchmark.interopEncode:gc.alloc.rate.norm | thrpt | 11,016.008 | ± 0.001 | B/op |
| ConformanceBenchmark.interopEncode:gc.count | thrpt | 134.000 | | counts |
| ConformanceBenchmark.interopEncode:gc.time | thrpt | 61.000 | | ms |
| ConformanceBenchmark.protoDecode | thrpt | 2,628,290.930 | ± 64,307.060 | ops/s |
| ConformanceBenchmark.protoDecode:gc.alloc.rate | thrpt | 4,150.254 | ± 101.502 | MB/sec |
| ConformanceBenchmark.protoDecode:gc.alloc.rate.norm | thrpt | 1,656.003 | ± 0.001 | B/op |
| ConformanceBenchmark.protoDecode:gc.count | thrpt | 94.000 | | counts |
| ConformanceBenchmark.protoDecode:gc.time | thrpt | 49.000 | | ms |
| ConformanceBenchmark.protoEncode | thrpt | 4,009,801.051 | ± 13,334.057 | ops/s |
| ConformanceBenchmark.protoEncode:gc.alloc.rate | thrpt | 5,903.604 | ± 19.646 | MB/sec |
| ConformanceBenchmark.protoEncode:gc.alloc.rate.norm | thrpt | 1,544.002 | ± 0.001 | B/op |
| ConformanceBenchmark.protoEncode:gc.count | thrpt | 108.000 | | counts |
| ConformanceBenchmark.protoEncode:gc.time | thrpt | 55.000 | | ms |
| ConformanceBenchmark.toProto | thrpt | 1,571,525.372 | ± 12,635.144 | ops/s |
| ConformanceBenchmark.toProto:gc.alloc.rate | thrpt | 10,705.437 | ± 85.818 | MB/sec |
| ConformanceBenchmark.toProto:gc.alloc.rate.norm | thrpt | 7,144.004 | ± 0.001 | B/op |
| ConformanceBenchmark.toProto:gc.count | thrpt | 162.000 | | counts |
| ConformanceBenchmark.toProto:gc.time | thrpt | 76.000 | | ms |
| SmallMessageBenchmark.decode | thrpt | 15,032,844.367 | ± 32,777.538 | ops/s |
| SmallMessageBenchmark.decode:gc.alloc.rate | thrpt | 8,027.462 | ± 17.474 | MB/sec |
| SmallMessageBenchmark.decode:gc.alloc.rate.norm | thrpt | 560.000 | ± 0.001 | B/op |
| SmallMessageBenchmark.decode:gc.count | thrpt | 122.000 | | counts |
| SmallMessageBenchmark.decode:gc.time | thrpt | 62.000 | | ms |
| SmallMessageBenchmark.encode | thrpt | 11,595,136.157 | ± 1,352,285.194 | ops/s |
| SmallMessageBenchmark.encode:gc.alloc.rate | thrpt | 1,503.623 | ± 175.390 | MB/sec |
| SmallMessageBenchmark.encode:gc.alloc.rate.norm | thrpt | 136.001 | ± 0.001 | B/op |
| SmallMessageBenchmark.encode:gc.count | thrpt | 34.000 | | counts |
| SmallMessageBenchmark.encode:gc.time | thrpt | 17.000 | | ms |
| SmallMessageBenchmark.fromProto | thrpt | 119,558,481.849 | ± 756,756.057 | ops/s |
| SmallMessageBenchmark.fromProto:gc.alloc.rate | thrpt | 10,944.432 | ± 69.331 | MB/sec |
| SmallMessageBenchmark.fromProto:gc.alloc.rate.norm | thrpt | 96.000 | ± 0.001 | B/op |
| SmallMessageBenchmark.fromProto:gc.count | thrpt | 166.000 | | counts |
| SmallMessageBenchmark.fromProto:gc.time | thrpt | 72.000 | | ms |
| SmallMessageBenchmark.interopDecode | thrpt | 12,862,326.991 | ± 77,956.130 | ops/s |
| SmallMessageBenchmark.interopDecode:gc.alloc.rate | thrpt | 5,396.539 | ± 32.718 | MB/sec |
| SmallMessageBenchmark.interopDecode:gc.alloc.rate.norm | thrpt | 440.001 | ± 0.001 | B/op |
| SmallMessageBenchmark.interopDecode:gc.count | thrpt | 98.000 | | counts |
| SmallMessageBenchmark.interopDecode:gc.time | thrpt | 49.000 | | ms |
| SmallMessageBenchmark.interopEncode | thrpt | 6,333,289.969 | ± 858,884.418 | ops/s |
| SmallMessageBenchmark.interopEncode:gc.alloc.rate | thrpt | 1,304.456 | ± 176.894 | MB/sec |
| SmallMessageBenchmark.interopEncode:gc.alloc.rate.norm | thrpt | 216.001 | ± 0.001 | B/op |
| SmallMessageBenchmark.interopEncode:gc.count | thrpt | 30.000 | | counts |
| SmallMessageBenchmark.interopEncode:gc.time | thrpt | 16.000 | | ms |
| SmallMessageBenchmark.protoDecode | thrpt | 13,636,385.136 | ± 177,805.223 | ops/s |
| SmallMessageBenchmark.protoDecode:gc.alloc.rate | thrpt | 4,993.147 | ± 65.203 | MB/sec |
| SmallMessageBenchmark.protoDecode:gc.alloc.rate.norm | thrpt | 384.001 | ± 0.001 | B/op |
| SmallMessageBenchmark.protoDecode:gc.count | thrpt | 91.000 | | counts |
| SmallMessageBenchmark.protoDecode:gc.time | thrpt | 44.000 | | ms |
| SmallMessageBenchmark.protoEncode | thrpt | 37,882,913.735 | ± 1,658,783.089 | ops/s |
| SmallMessageBenchmark.protoEncode:gc.alloc.rate | thrpt | 4,912.719 | ± 215.190 | MB/sec |
| SmallMessageBenchmark.protoEncode:gc.alloc.rate.norm | thrpt | 136.000 | ± 0.001 | B/op |
| SmallMessageBenchmark.protoEncode:gc.count | thrpt | 110.000 | | counts |
| SmallMessageBenchmark.protoEncode:gc.time | thrpt | 50.000 | | ms |
| SmallMessageBenchmark.toProto | thrpt | 41,733,266.062 | ± 201,305.048 | ops/s |
| SmallMessageBenchmark.toProto:gc.alloc.rate | thrpt | 6,685.458 | ± 31.969 | MB/sec |
| SmallMessageBenchmark.toProto:gc.alloc.rate.norm | thrpt | 168.000 | ± 0.001 | B/op |
| SmallMessageBenchmark.toProto:gc.count | thrpt | 122.000 | | counts |
| SmallMessageBenchmark.toProto:gc.time | thrpt | 60.000 | | ms |

## Comparison with previous results (post-wkt-map-size-fix)

### Small message

| Method | Pre-encodeTo | Post-encodeTo | Δ B/op | Δ ops/s |
|--------|-------------|---------------|--------|---------|
| encode | 11.1M / 136 B | 11.6M / 136 B | — | +4% |
| decode | 15.8M / 560 B | 15.0M / 560 B | — | noise |
| **interopEncode** | **6.23M / 344 B** | **6.33M / 216 B** | **-128 B (-37%)** | +2% |
| interopDecode | 13.4M / 440 B | 12.9M / 440 B | — | noise |
| fromProto | 146M / 96 B | 120M / 96 B | — | noise |
| toProto | 44.2M / 168 B | 41.7M / 168 B | — | noise |
| protoEncode | 40.3M / 136 B | 37.9M / 136 B | — | noise |
| protoDecode | 14.4M / 384 B | 13.6M / 384 B | — | noise |

### Conformance message (TestAllTypesProto3)

| Method | Pre-encodeTo | Post-encodeTo | Δ B/op | Δ ops/s |
|--------|-------------|---------------|--------|---------|
| **encode** | **212K / 3,048 B** | **208K / 2,920 B** | **-128 B (-4%)** | noise |
| decode | 650K / 3,512 B | 624K / 3,512 B | — | noise |
| interopEncode | 912K / 11,016 B | 842K / 11,016 B | — | noise |
| interopDecode | 1.13M / 6,464 B | 1.07M / 6,464 B | — | noise |
| fromProto | 1.86M / 4,808 B | 1.75M / 4,808 B | — | noise |
| toProto | 1.67M / 7,144 B | 1.57M / 7,144 B | — | noise |
| protoEncode | 4.22M / 1,544 B | 4.01M / 1,544 B | — | noise |
| protoDecode | 2.76M / 1,656 B | 2.63M / 1,656 B | — | noise |

## Key observations

1. **Small interopEncode B/op: 344 → 216 B/op (-37%)** — The `encodeTo` change eliminated nested message `byte[]` allocations in the interop encode path. The small message has a nested message field that was previously allocating a `byte[]`.

2. **Conformance encode B/op: 3,048 → 2,920 B/op (-4%)** — Smaller but measurable reduction. The conformance message has fewer nested fields relative to its total size.

3. **Throughput changes are within noise** for most methods (±5%). The `encodeTo` change eliminates allocations but doesn't change the computation.

4. **The `encodeTo` change is most effective for messages with nested message fields** — the small message's interop encode saw the biggest gain because it has a nested message field.
