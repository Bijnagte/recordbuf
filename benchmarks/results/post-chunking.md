# Post chunking of wide-message encode/size bodies (short run: -wi 3 -i 5 -f 1)

Generated `writeBody` / `getSerializedSize` are split into `writeBodyPartN` / `sizePartN`
(16 slots each) for messages with more than 16 slots, so no method exceeds HotSpot's
8000-bytecode HugeMethodLimit (huge methods are never JIT-compiled).

| Benchmark | ops/s | B/op |
|---|---|---|
| Conformance encode | 10,146,460 | 104 |
| Conformance protoEncode | 4,142,801 | 1,544 |
| Conformance decode | 648,993 | 3,512 |
| Small encode | 13,775,104 | 136 |
| Small protoEncode | 38,501,343 | 136 |
| Small decode | 15,582,013 | 560 |

Before: Conformance encode 214K ops/s, 2,920 B/op.
