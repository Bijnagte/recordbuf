# Post review-fixes (checkNoSpaceLeft, unknown-fields to(), Encoder removed)

```
Benchmark                                                Mode  Cnt          Score         Error   Units
ConformanceBenchmark.decode                             thrpt   10     647420.417 ±    3112.436   ops/s
ConformanceBenchmark.decode:gc.alloc.rate               thrpt   10       2168.074 ±      10.426  MB/sec
ConformanceBenchmark.decode:gc.alloc.rate.norm          thrpt   10       3512.011 ±       0.001    B/op
ConformanceBenchmark.decode:gc.count                    thrpt   10         49.000                counts
ConformanceBenchmark.decode:gc.time                     thrpt   10         34.000                    ms
ConformanceBenchmark.encode                             thrpt   10     213925.597 ±    1108.661   ops/s
ConformanceBenchmark.encode:gc.alloc.rate               thrpt   10        595.629 ±       3.061  MB/sec
ConformanceBenchmark.encode:gc.alloc.rate.norm          thrpt   10       2920.033 ±       0.001    B/op
ConformanceBenchmark.encode:gc.count                    thrpt   10         14.000                counts
ConformanceBenchmark.encode:gc.time                     thrpt   10         14.000                    ms
ConformanceBenchmark.fromProto                          thrpt   10    1824532.087 ±    7414.971   ops/s
ConformanceBenchmark.fromProto:gc.alloc.rate            thrpt   10       8364.718 ±      33.934  MB/sec
ConformanceBenchmark.fromProto:gc.alloc.rate.norm       thrpt   10       4808.004 ±       0.001    B/op
ConformanceBenchmark.fromProto:gc.count                 thrpt   10        127.000                counts
ConformanceBenchmark.fromProto:gc.time                  thrpt   10         76.000                    ms
ConformanceBenchmark.interopDecode                      thrpt   10    1103916.307 ±    7975.536   ops/s
ConformanceBenchmark.interopDecode:gc.alloc.rate        thrpt   10       6804.226 ±      49.218  MB/sec
ConformanceBenchmark.interopDecode:gc.alloc.rate.norm   thrpt   10       6464.006 ±       0.001    B/op
ConformanceBenchmark.interopDecode:gc.count             thrpt   10        104.000                counts
ConformanceBenchmark.interopDecode:gc.time              thrpt   10         63.000                    ms
ConformanceBenchmark.interopEncode                      thrpt   10     873529.268 ±    4496.663   ops/s
ConformanceBenchmark.interopEncode:gc.alloc.rate        thrpt   10       9175.984 ±      47.268  MB/sec
ConformanceBenchmark.interopEncode:gc.alloc.rate.norm   thrpt   10      11016.008 ±       0.001    B/op
ConformanceBenchmark.interopEncode:gc.count             thrpt   10        139.000                counts
ConformanceBenchmark.interopEncode:gc.time              thrpt   10         64.000                    ms
ConformanceBenchmark.protoDecode                        thrpt   10    2734678.453 ±   98992.557   ops/s
ConformanceBenchmark.protoDecode:gc.alloc.rate          thrpt   10       4318.190 ±     156.339  MB/sec
ConformanceBenchmark.protoDecode:gc.alloc.rate.norm     thrpt   10       1656.003 ±       0.001    B/op
ConformanceBenchmark.protoDecode:gc.count               thrpt   10         79.000                counts
ConformanceBenchmark.protoDecode:gc.time                thrpt   10         47.000                    ms
ConformanceBenchmark.protoEncode                        thrpt   10    4143017.069 ±   18563.764   ops/s
ConformanceBenchmark.protoEncode:gc.alloc.rate          thrpt   10       6099.167 ±      27.311  MB/sec
ConformanceBenchmark.protoEncode:gc.alloc.rate.norm     thrpt   10       1544.002 ±       0.001    B/op
ConformanceBenchmark.protoEncode:gc.count               thrpt   10        111.000                counts
ConformanceBenchmark.protoEncode:gc.time                thrpt   10         62.000                    ms
ConformanceBenchmark.toProto                            thrpt   10    1610438.536 ±   10206.661   ops/s
ConformanceBenchmark.toProto:gc.alloc.rate              thrpt   10      10970.361 ±      69.652  MB/sec
ConformanceBenchmark.toProto:gc.alloc.rate.norm         thrpt   10       7144.004 ±       0.001    B/op
ConformanceBenchmark.toProto:gc.count                   thrpt   10        136.000                counts
ConformanceBenchmark.toProto:gc.time                    thrpt   10         73.000                    ms
SmallMessageBenchmark.decode                            thrpt   10   15497586.939 ±   72514.205   ops/s
SmallMessageBenchmark.decode:gc.alloc.rate              thrpt   10       8275.389 ±      38.734  MB/sec
SmallMessageBenchmark.decode:gc.alloc.rate.norm         thrpt   10        560.000 ±       0.001    B/op
SmallMessageBenchmark.decode:gc.count                   thrpt   10        126.000                counts
SmallMessageBenchmark.decode:gc.time                    thrpt   10         68.000                    ms
SmallMessageBenchmark.encode                            thrpt   10    9955739.703 ± 1094083.247   ops/s
SmallMessageBenchmark.encode:gc.alloc.rate              thrpt   10       1291.076 ±     141.872  MB/sec
SmallMessageBenchmark.encode:gc.alloc.rate.norm         thrpt   10        136.001 ±       0.001    B/op
SmallMessageBenchmark.encode:gc.count                   thrpt   10         29.000                counts
SmallMessageBenchmark.encode:gc.time                    thrpt   10         17.000                    ms
SmallMessageBenchmark.fromProto                         thrpt   10  116315237.839 ± 6204387.022   ops/s
SmallMessageBenchmark.fromProto:gc.alloc.rate           thrpt   10      10647.397 ±     568.440  MB/sec
SmallMessageBenchmark.fromProto:gc.alloc.rate.norm      thrpt   10         96.000 ±       0.001    B/op
SmallMessageBenchmark.fromProto:gc.count                thrpt   10        162.000                counts
SmallMessageBenchmark.fromProto:gc.time                 thrpt   10         76.000                    ms
SmallMessageBenchmark.interopDecode                     thrpt   10   13158730.505 ±  104711.735   ops/s
SmallMessageBenchmark.interopDecode:gc.alloc.rate       thrpt   10       5520.563 ±      44.574  MB/sec
SmallMessageBenchmark.interopDecode:gc.alloc.rate.norm  thrpt   10        440.001 ±       0.001    B/op
SmallMessageBenchmark.interopDecode:gc.count            thrpt   10        101.000                counts
SmallMessageBenchmark.interopDecode:gc.time             thrpt   10         55.000                    ms
SmallMessageBenchmark.interopEncode                     thrpt   10    5385704.093 ±  707376.220   ops/s
SmallMessageBenchmark.interopEncode:gc.alloc.rate       thrpt   10       1766.471 ±     232.417  MB/sec
SmallMessageBenchmark.interopEncode:gc.alloc.rate.norm  thrpt   10        344.001 ±       0.001    B/op
SmallMessageBenchmark.interopEncode:gc.count            thrpt   10         40.000                counts
SmallMessageBenchmark.interopEncode:gc.time             thrpt   10         22.000                    ms
SmallMessageBenchmark.protoDecode                       thrpt   10   13869130.479 ±  200539.058   ops/s
SmallMessageBenchmark.protoDecode:gc.alloc.rate         thrpt   10       5078.239 ±      73.293  MB/sec
SmallMessageBenchmark.protoDecode:gc.alloc.rate.norm    thrpt   10        384.001 ±       0.001    B/op
SmallMessageBenchmark.protoDecode:gc.count              thrpt   10         92.000                counts
SmallMessageBenchmark.protoDecode:gc.time               thrpt   10         51.000                    ms
SmallMessageBenchmark.protoEncode                       thrpt   10   35361689.430 ± 2030842.264   ops/s
SmallMessageBenchmark.protoEncode:gc.alloc.rate         thrpt   10       4585.691 ±     263.341  MB/sec
SmallMessageBenchmark.protoEncode:gc.alloc.rate.norm    thrpt   10        136.000 ±       0.001    B/op
SmallMessageBenchmark.protoEncode:gc.count              thrpt   10         84.000                counts
SmallMessageBenchmark.protoEncode:gc.time               thrpt   10         43.000                    ms
SmallMessageBenchmark.toProto                           thrpt   10   41932371.453 ±  283563.048   ops/s
SmallMessageBenchmark.toProto:gc.alloc.rate             thrpt   10       6717.306 ±      45.701  MB/sec
SmallMessageBenchmark.toProto:gc.alloc.rate.norm        thrpt   10        168.000 ±       0.001    B/op
SmallMessageBenchmark.toProto:gc.count                  thrpt   10        123.000                counts
SmallMessageBenchmark.toProto:gc.time                   thrpt   10         66.000                    ms
```

Note: Small interopEncode allocated 344 B/op in this run vs 216 before, from an unconditional setUnknownFields in Interop.to(); fixed afterwards by guarding on non-empty unknown fields (not yet re-measured).
