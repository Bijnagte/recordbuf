# Post lazy repeated/map allocation in decode (short run: -wi 3 -i 5 -f 1)

| Benchmark | ops/s | B/op | Before (ops/s, B/op) |
|---|---|---|---|
| Conformance decode | 590,648 | 1,016 | 644K, 3,512 |
| Conformance protoDecode | ~2.7M | 1,656 | |
| Small decode | 15,691,757 | 560 | 15.5M, 560 |
| Small interopDecode | 13,706,020 | 440 | 13.5M, 440 |

Allocation on the wide conformance message fell 71% (below protobuf-java). Throughput did not
improve: HotSpot never compiles `TestAllTypesProto3Codec.decodeBody` (8,323 bytes) or the
record's ~150-argument canonical constructor:
  - C1: "COMPILE SKIPPED: out of virtual registers in linear scan"
  - C2: "COMPILE SKIPPED: unsupported calling sequence" (also for `TestAllTypesProto3::<init>`)
so decode runs in the interpreter regardless of -XX:-DontCompileHugeMethods. Fixing that would
need decode through a mutable accumulator instead of one wide canonical-constructor call.
