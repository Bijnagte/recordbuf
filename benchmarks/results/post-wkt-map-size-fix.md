Benchmark                                                Mode  Cnt          Score         Error   Units
ConformanceBenchmark.decode                             thrpt   10     649718.459 ±    8724.910   ops/s
ConformanceBenchmark.decode:gc.alloc.rate               thrpt   10       2175.821 ±      29.220  MB/sec
ConformanceBenchmark.decode:gc.alloc.rate.norm          thrpt   10       3512.011 ±       0.001    B/op
ConformanceBenchmark.decode:gc.count                    thrpt   10         49.000                counts
ConformanceBenchmark.decode:gc.time                     thrpt   10         32.000                    ms
ConformanceBenchmark.encode                             thrpt   10     211744.505 ±    2349.469   ops/s
ConformanceBenchmark.encode:gc.alloc.rate               thrpt   10        615.424 ±       6.826  MB/sec
ConformanceBenchmark.encode:gc.alloc.rate.norm          thrpt   10       3048.033 ±       0.001    B/op
ConformanceBenchmark.encode:gc.count                    thrpt   10         14.000                counts
ConformanceBenchmark.encode:gc.time                     thrpt   10         13.000                    ms
ConformanceBenchmark.fromProto                          thrpt   10    1863070.479 ±    6595.655   ops/s
ConformanceBenchmark.fromProto:gc.alloc.rate            thrpt   10       8541.629 ±      30.322  MB/sec
ConformanceBenchmark.fromProto:gc.alloc.rate.norm       thrpt   10       4808.004 ±       0.001    B/op
ConformanceBenchmark.fromProto:gc.count                 thrpt   10        130.000                counts
ConformanceBenchmark.fromProto:gc.time                  thrpt   10         69.000                    ms
ConformanceBenchmark.interopDecode                      thrpt   10    1131444.171 ±    8947.480   ops/s
ConformanceBenchmark.interopDecode:gc.alloc.rate        thrpt   10       6974.001 ±      55.204  MB/sec
ConformanceBenchmark.interopDecode:gc.alloc.rate.norm   thrpt   10       6464.006 ±       0.001    B/op
ConformanceBenchmark.interopDecode:gc.count             thrpt   10        127.000                counts
ConformanceBenchmark.interopDecode:gc.time              thrpt   10         68.000                    ms
ConformanceBenchmark.interopEncode                      thrpt   10     912078.143 ±    6221.194   ops/s
ConformanceBenchmark.interopEncode:gc.alloc.rate        thrpt   10       9580.475 ±      65.118  MB/sec
ConformanceBenchmark.interopEncode:gc.alloc.rate.norm   thrpt   10      11016.008 ±       0.001    B/op
ConformanceBenchmark.interopEncode:gc.count             thrpt   10        145.000                counts
ConformanceBenchmark.interopEncode:gc.time              thrpt   10         71.000                    ms
ConformanceBenchmark.protoDecode                        thrpt   10    2759048.238 ±   71097.757   ops/s
ConformanceBenchmark.protoDecode:gc.alloc.rate          thrpt   10       4356.760 ±     112.266  MB/sec
ConformanceBenchmark.protoDecode:gc.alloc.rate.norm     thrpt   10       1656.003 ±       0.001    B/op
ConformanceBenchmark.protoDecode:gc.count               thrpt   10         98.000                counts
ConformanceBenchmark.protoDecode:gc.time                thrpt   10         50.000                    ms
ConformanceBenchmark.protoEncode                        thrpt   10    4224895.740 ±   17419.847   ops/s
ConformanceBenchmark.protoEncode:gc.alloc.rate          thrpt   10       6220.223 ±      25.627  MB/sec
ConformanceBenchmark.protoEncode:gc.alloc.rate.norm     thrpt   10       1544.002 ±       0.001    B/op
ConformanceBenchmark.protoEncode:gc.count               thrpt   10        113.000                counts
ConformanceBenchmark.protoEncode:gc.time                thrpt   10         56.000                    ms
ConformanceBenchmark.toProto                            thrpt   10    1667732.476 ±   10689.432   ops/s
ConformanceBenchmark.toProto:gc.alloc.rate              thrpt   10      11361.018 ±      72.805  MB/sec
ConformanceBenchmark.toProto:gc.alloc.rate.norm         thrpt   10       7144.004 ±       0.001    B/op
ConformanceBenchmark.toProto:gc.count                   thrpt   10        172.000                counts
ConformanceBenchmark.toProto:gc.time                    thrpt   10         82.000                    ms
SmallMessageBenchmark.decode                            thrpt   10   15848839.883 ±   56158.186   ops/s
SmallMessageBenchmark.decode:gc.alloc.rate              thrpt   10       8463.196 ±      29.938  MB/sec
SmallMessageBenchmark.decode:gc.alloc.rate.norm         thrpt   10        560.000 ±       0.001    B/op
SmallMessageBenchmark.decode:gc.count                   thrpt   10        155.000                counts
SmallMessageBenchmark.decode:gc.time                    thrpt   10         72.000                    ms
SmallMessageBenchmark.encode                            thrpt   10   11055667.110 ± 1357135.498   ops/s
SmallMessageBenchmark.encode:gc.alloc.rate              thrpt   10       1433.720 ±     175.997  MB/sec
SmallMessageBenchmark.encode:gc.alloc.rate.norm         thrpt   10        136.001 ±       0.001    B/op
SmallMessageBenchmark.encode:gc.count                   thrpt   10         32.000                counts
SmallMessageBenchmark.encode:gc.time                    thrpt   10         16.000                    ms
SmallMessageBenchmark.fromProto                         thrpt   10  146326079.948 ±  292822.120   ops/s
SmallMessageBenchmark.fromProto:gc.alloc.rate           thrpt   10      13395.056 ±      26.899  MB/sec
SmallMessageBenchmark.fromProto:gc.alloc.rate.norm      thrpt   10         96.000 ±       0.001    B/op
SmallMessageBenchmark.fromProto:gc.count                thrpt   10        203.000                counts
SmallMessageBenchmark.fromProto:gc.time                 thrpt   10         84.000                    ms
SmallMessageBenchmark.interopDecode                     thrpt   10   13383162.116 ±  167181.633   ops/s
SmallMessageBenchmark.interopDecode:gc.alloc.rate       thrpt   10       5615.102 ±      70.166  MB/sec
SmallMessageBenchmark.interopDecode:gc.alloc.rate.norm  thrpt   10        440.001 ±       0.001    B/op
SmallMessageBenchmark.interopDecode:gc.count            thrpt   10        102.000                counts
SmallMessageBenchmark.interopDecode:gc.time             thrpt   10         51.000                    ms
SmallMessageBenchmark.interopEncode                     thrpt   10    6233694.530 ±  740451.155   ops/s
SmallMessageBenchmark.interopEncode:gc.alloc.rate       thrpt   10       2044.771 ±     242.880  MB/sec
SmallMessageBenchmark.interopEncode:gc.alloc.rate.norm  thrpt   10        344.001 ±       0.001    B/op
SmallMessageBenchmark.interopEncode:gc.count            thrpt   10         46.000                counts
SmallMessageBenchmark.interopEncode:gc.time             thrpt   10         25.000                    ms
SmallMessageBenchmark.protoDecode                       thrpt   10   14437059.710 ±  211434.749   ops/s
SmallMessageBenchmark.protoDecode:gc.alloc.rate         thrpt   10       5286.290 ±      77.502  MB/sec
SmallMessageBenchmark.protoDecode:gc.alloc.rate.norm    thrpt   10        384.000 ±       0.001    B/op
SmallMessageBenchmark.protoDecode:gc.count              thrpt   10         97.000                counts
SmallMessageBenchmark.protoDecode:gc.time               thrpt   10         50.000                    ms
SmallMessageBenchmark.protoEncode                       thrpt   10   40286809.955 ± 2117233.228   ops/s
SmallMessageBenchmark.protoEncode:gc.alloc.rate         thrpt   10       5224.456 ±     274.644  MB/sec
SmallMessageBenchmark.protoEncode:gc.alloc.rate.norm    thrpt   10        136.000 ±       0.001    B/op
SmallMessageBenchmark.protoEncode:gc.count              thrpt   10        117.000                counts
SmallMessageBenchmark.protoEncode:gc.time               thrpt   10         51.000                    ms
SmallMessageBenchmark.toProto                           thrpt   10   44174877.588 ±  212292.352   ops/s
SmallMessageBenchmark.toProto:gc.alloc.rate             thrpt   10       7076.638 ±      34.044  MB/sec
SmallMessageBenchmark.toProto:gc.alloc.rate.norm        thrpt   10        168.000 ±       0.001    B/op
SmallMessageBenchmark.toProto:gc.count                  thrpt   10        129.000                counts
SmallMessageBenchmark.toProto:gc.time                   thrpt   10         64.000                    ms
