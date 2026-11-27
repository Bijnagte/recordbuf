# Wide-message decode through the builder carrier

Conformance (`TestAllTypesProto3`) decode, `-f 1 -wi 3 -i 5 -prof gc`, same machine as the earlier baselines.

```
Benchmark                                  Mode  Cnt        Score      Error   Units
ConformanceBenchmark.decode               thrpt    5  4648058.773 ± 83819.514  ops/s   (was 643,678)
ConformanceBenchmark.decode:gc.alloc.rate.norm      1600 B/op                          (was 3,512)
ConformanceBenchmark.protoDecode          thrpt    5  2663418.909 ± 131931.698 ops/s
ConformanceBenchmark.protoDecode:gc.alloc.rate.norm 1656 B/op
SmallMessageBenchmark.decode              thrpt    5 15638611.363 ± 267663.833 ops/s   (unchanged path)
SmallMessageBenchmark.decode:gc.alloc.rate.norm      560 B/op
```
