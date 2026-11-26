# Benchmark Baseline — Pre-Fix (2026-11-26)

JMH run before the two-pass encode allocation fix and interop unknown-fields
conversion. Use these numbers to compare against post-fix results.

## Small message (3 fields: int32, string, repeated string)

| Method | ops/s | B/op |
|---|---|---|
| `encode` (recordbuf) | 4.53M | 4,360 |
| `protoEncode` (native) | 8.75M | 176 |
| `interopEncode` (convert+native) | 6.34M | 216 |
| `decode` (recordbuf) | 15.84M | 560 |
| `protoDecode` (native) | 14.20M | 384 |
| `interopDecode` (native+convert) | 13.37M | 440 |
| `interopFullEncode` (encode+convert+native) | 3.92M | 4,576 |
| `interopFullDecode` (native decode+convert) | 12.51M | 480 |

## Conformance (TestAllTypesProto3, 23 fields, 6 oneofs, nested messages)

| Method | ops/s | B/op |
|---|---|---|
| `encode` (recordbuf) | 1.54M | 5,784 |
| `protoEncode` (native) | 4.23M | 1,544 |
| `interopEncode` (convert+native) | 0.90M | 11,016 |
| `decode` (recordbuf) | 0.66M | 3,512 |
| `protoDecode` (native) | 2.81M | 1,656 |
| `interopDecode` (native+convert) | 1.12M | 6,464 |
| `interopFullEncode` (encode+convert+native) | 1.35M | 16,800 |
| `interopFullDecode` (native decode+convert) | 1.05M | 6,944 |

## Key findings (pre-fix)

- Recordbuf encode allocates **25x** native on small (4,360 vs 176 B/op)
- Root cause: `Encoder.start()` wraps `ByteArrayOutputStream` in
  `CodedOutputStream.newInstance(OutputStream)`, allocating a 4,096-byte
  internal buffer per call
- Recordbuf decode is slower than interop decode on conformance (0.66M vs 1.12M ops/s)
- Interop `from()` discards unknown fields (`UnknownFields.empty()`)
- `WireType` enum has no `FIXED32` entry (wire code 5) — decode throws on fixed32 unknown fields

## Success criteria (post-fix targets)

- Encode allocation ≤ native: ≤176 B/op (small), ≤1,544 B/op (conformance)
- Encode throughput ≥ interop+native: ≥6.3M ops/s (small), ≥0.90M ops/s (conformance)
- `Interop.from()` preserves unknown fields
- Fixed32 unknown fields round-trip without throwing
