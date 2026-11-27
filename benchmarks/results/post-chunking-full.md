# Full run after chunking + interop unknown-fields guard

```
Benchmark                                                Mode  Cnt          Score        Error   Units
ConformanceBenchmark.decode                             thrpt   10     643678.296 ±   3964.674   ops/s
ConformanceBenchmark.decode:gc.alloc.rate               thrpt   10       2155.600 ±     13.295  MB/sec
ConformanceBenchmark.decode:gc.alloc.rate.norm          thrpt   10       3512.011 ±      0.001    B/op
ConformanceBenchmark.decode:gc.count                    thrpt   10         48.000               counts
ConformanceBenchmark.decode:gc.time                     thrpt   10         27.000                   ms
ConformanceBenchmark.encode                             thrpt   10   10315252.484 ± 343261.796   ops/s
ConformanceBenchmark.encode:gc.alloc.rate               thrpt   10       1022.966 ±     34.055  MB/sec
ConformanceBenchmark.encode:gc.alloc.rate.norm          thrpt   10        104.001 ±      0.001    B/op
ConformanceBenchmark.encode:gc.count                    thrpt   10         23.000               counts
ConformanceBenchmark.encode:gc.time                     thrpt   10         12.000                   ms
ConformanceBenchmark.fromProto                          thrpt   10    1830570.002 ±  10782.739   ops/s
ConformanceBenchmark.fromProto:gc.alloc.rate            thrpt   10       8392.692 ±     49.483  MB/sec
ConformanceBenchmark.fromProto:gc.alloc.rate.norm       thrpt   10       4808.004 ±      0.001    B/op
ConformanceBenchmark.fromProto:gc.count                 thrpt   10        127.000               counts
ConformanceBenchmark.fromProto:gc.time                  thrpt   10         62.000                   ms
ConformanceBenchmark.interopDecode                      thrpt   10    1090730.425 ±   8516.018   ops/s
ConformanceBenchmark.interopDecode:gc.alloc.rate        thrpt   10       6723.103 ±     52.532  MB/sec
ConformanceBenchmark.interopDecode:gc.alloc.rate.norm   thrpt   10       6464.006 ±      0.001    B/op
ConformanceBenchmark.interopDecode:gc.count             thrpt   10        122.000               counts
ConformanceBenchmark.interopDecode:gc.time              thrpt   10         59.000                   ms
ConformanceBenchmark.interopEncode                      thrpt   10     885633.112 ±   6115.240   ops/s
ConformanceBenchmark.interopEncode:gc.alloc.rate        thrpt   10       9303.007 ±     64.468  MB/sec
ConformanceBenchmark.interopEncode:gc.alloc.rate.norm   thrpt   10      11016.008 ±      0.001    B/op
ConformanceBenchmark.interopEncode:gc.count             thrpt   10        141.000               counts
ConformanceBenchmark.interopEncode:gc.time              thrpt   10         63.000                   ms
ConformanceBenchmark.protoDecode                        thrpt   10    2725424.650 ±  60328.500   ops/s
ConformanceBenchmark.protoDecode:gc.alloc.rate          thrpt   10       4303.683 ±     95.320  MB/sec
ConformanceBenchmark.protoDecode:gc.alloc.rate.norm     thrpt   10       1656.003 ±      0.001    B/op
ConformanceBenchmark.protoDecode:gc.count               thrpt   10         97.000               counts
ConformanceBenchmark.protoDecode:gc.time                thrpt   10         43.000                   ms
ConformanceBenchmark.protoEncode                        thrpt   10    4147254.103 ±  18643.930   ops/s
ConformanceBenchmark.protoEncode:gc.alloc.rate          thrpt   10       6105.913 ±     27.420  MB/sec
ConformanceBenchmark.protoEncode:gc.alloc.rate.norm     thrpt   10       1544.002 ±      0.001    B/op
ConformanceBenchmark.protoEncode:gc.count               thrpt   10        111.000               counts
ConformanceBenchmark.protoEncode:gc.time                thrpt   10         50.000                   ms
ConformanceBenchmark.toProto                            thrpt   10    1614816.928 ±  12212.216   ops/s
ConformanceBenchmark.toProto:gc.alloc.rate              thrpt   10      11000.555 ±     83.210  MB/sec
ConformanceBenchmark.toProto:gc.alloc.rate.norm         thrpt   10       7144.004 ±      0.001    B/op
ConformanceBenchmark.toProto:gc.count                   thrpt   10        137.000               counts
ConformanceBenchmark.toProto:gc.time                    thrpt   10         63.000                   ms
SmallMessageBenchmark.decode                            thrpt   10   15538279.966 ±  40976.335   ops/s
SmallMessageBenchmark.decode:gc.alloc.rate              thrpt   10       8297.403 ±     21.879  MB/sec
SmallMessageBenchmark.decode:gc.alloc.rate.norm         thrpt   10        560.000 ±      0.001    B/op
SmallMessageBenchmark.decode:gc.count                   thrpt   10        126.000               counts
SmallMessageBenchmark.decode:gc.time                    thrpt   10         56.000                   ms
SmallMessageBenchmark.encode                            thrpt   10   13772554.544 ± 163347.333   ops/s
SmallMessageBenchmark.encode:gc.alloc.rate              thrpt   10       1786.065 ±     21.204  MB/sec
SmallMessageBenchmark.encode:gc.alloc.rate.norm         thrpt   10        136.001 ±      0.001    B/op
SmallMessageBenchmark.encode:gc.count                   thrpt   10         40.000               counts
SmallMessageBenchmark.encode:gc.time                    thrpt   10         17.000                   ms
SmallMessageBenchmark.fromProto                         thrpt   10  121537804.795 ± 402873.330   ops/s
SmallMessageBenchmark.fromProto:gc.alloc.rate           thrpt   10      11125.784 ±     36.880  MB/sec
SmallMessageBenchmark.fromProto:gc.alloc.rate.norm      thrpt   10         96.000 ±      0.001    B/op
SmallMessageBenchmark.fromProto:gc.count                thrpt   10        168.000               counts
SmallMessageBenchmark.fromProto:gc.time                 thrpt   10         66.000                   ms
SmallMessageBenchmark.interopDecode                     thrpt   10   13465243.186 ±  48915.320   ops/s
SmallMessageBenchmark.interopDecode:gc.alloc.rate       thrpt   10       5649.604 ±     20.505  MB/sec
SmallMessageBenchmark.interopDecode:gc.alloc.rate.norm  thrpt   10        440.001 ±      0.001    B/op
SmallMessageBenchmark.interopDecode:gc.count            thrpt   10        103.000               counts
SmallMessageBenchmark.interopDecode:gc.time             thrpt   10         45.000                   ms
SmallMessageBenchmark.interopEncode                     thrpt   10    8222311.035 ± 172813.298   ops/s
SmallMessageBenchmark.interopEncode:gc.alloc.rate       thrpt   10       1693.550 ±     35.593  MB/sec
SmallMessageBenchmark.interopEncode:gc.alloc.rate.norm  thrpt   10        216.001 ±      0.001    B/op
SmallMessageBenchmark.interopEncode:gc.count            thrpt   10         38.000               counts
SmallMessageBenchmark.interopEncode:gc.time             thrpt   10         17.000                   ms
SmallMessageBenchmark.protoDecode                       thrpt   10   14215665.957 ± 136768.725   ops/s
SmallMessageBenchmark.protoDecode:gc.alloc.rate         thrpt   10       5205.276 ±     50.070  MB/sec
SmallMessageBenchmark.protoDecode:gc.alloc.rate.norm    thrpt   10        384.000 ±      0.001    B/op
SmallMessageBenchmark.protoDecode:gc.count              thrpt   10         95.000               counts
SmallMessageBenchmark.protoDecode:gc.time               thrpt   10         43.000                   ms
SmallMessageBenchmark.protoEncode                       thrpt   10   37789279.989 ± 934194.223   ops/s
SmallMessageBenchmark.protoEncode:gc.alloc.rate         thrpt   10       4900.652 ±    121.209  MB/sec
SmallMessageBenchmark.protoEncode:gc.alloc.rate.norm    thrpt   10        136.000 ±      0.001    B/op
SmallMessageBenchmark.protoEncode:gc.count              thrpt   10        110.000               counts
SmallMessageBenchmark.protoEncode:gc.time               thrpt   10         44.000                   ms
SmallMessageBenchmark.toProto                           thrpt   10   42288239.729 ± 229229.391   ops/s
SmallMessageBenchmark.toProto:gc.alloc.rate             thrpt   10       6774.526 ±     36.720  MB/sec
SmallMessageBenchmark.toProto:gc.alloc.rate.norm        thrpt   10        168.000 ±      0.001    B/op
SmallMessageBenchmark.toProto:gc.count                  thrpt   10        124.000               counts
SmallMessageBenchmark.toProto:gc.time                   thrpt   10         54.000                   ms
```
