# JMH Results — Post lenDelimSize Fix (2026-11-27)

Latest run after the `lenDelimSize` helper fix (eliminates double `getSerializedSize`
in the size pass for length-delimited fields).

## Small message (3 fields: int32, string, repeated string)

| Method | Pre-fix | Post WKT/map fix | **Post lenDelimSize** | Δ vs native |
|---|---|---|---|---|
| `protoEncode` (native) | 8.75M / 176 B | 40.3M / 136 B | **35.9M / 136 B** | — |
| `encode` (recordbuf) | 4.53M / **4,360 B** | 11.1M / 136 B | **12.4M / 136 B** | 2.9× slower, **same alloc** |
| `protoDecode` (native) | 14.20M / 384 B | 14.4M / 384 B | **13.6M / 384 B** | — |
| `decode` (recordbuf) | 15.84M / 560 B | 15.8M / 560 B | **15.0M / 560 B** | 1.1× faster, 1.5× more alloc |
| `fromProto` (wire→record) | — | 146.3M / 96 B | **119.6M / 96 B** | 8.8× faster than protoDecode |
| `toProto` (record→wire) | — | 44.2M / 168 B | **42.0M / 168 B** | 1.2× faster than protoEncode |
| `interopDecode` (obj→record) | 13.37M / 440 B | 13.4M / 440 B | **12.7M / 440 B** | 0.93× vs protoDecode |
| `interopEncode` (record→obj) | 6.34M / 216 B | 6.2M / 344 B | **6.6M / 216 B** | 5.4× slower than protoEncode |

## Conformance (TestAllTypesProto3, 23 fields, 6 oneofs, nested messages)

| Method | Pre-fix | Post WKT/map fix | **Post lenDelimSize** | Δ vs native |
|---|---|---|---|---|
| `protoEncode` (native) | 4.23M / 1,544 B | 4.22M / 1,544 B | **4.01M / 1,544 B** | — |
| `encode` (recordbuf) | 1.54M / **5,784 B** | 212K / 3,048 B | **202K / 2,920 B** | **20× slower**, 1.9× more alloc |
| `protoDecode` (native) | 2.81M / 1,656 B | 2.76M / 1,656 B | **2.64M / 1,656 B** | — |
| `decode` (recordbuf) | 0.66M / 3,512 B | 650K / 3,512 B | **620K / 3,512 B** | 4.3× slower, 2.1× more alloc |
| `fromProto` (wire→record) | — | 1.86M / 4,808 B | **1.76M / 4,808 B** | 0.67× vs protoDecode |
| `toProto` (record→wire) | — | 1.67M / 7,144 B | **1.57M / 7,144 B** | 0.39× vs protoEncode |
| `interopDecode` (obj→record) | 1.12M / 6,464 B | 1.13M / 6,464 B | **1.08M / 6,464 B** | 0.41× vs protoDecode |
| `interopEncode` (record→obj) | 0.90M / 11,016 B | 912K / 11,016 B | **847K / 11,016 B** | 4.7× slower than protoEncode |

## Raw results

See `benchmarks/build/results/jmh/results.txt` for the full JMH output.

## Progression summary

### Small message encode (the original problem)
- **Pre-fix:** 4.53M ops/s, **4,360 B/op** (BAOS 4K buffer)
- **Post two-pass:** 11.1M ops/s, **136 B/op** (pre-allocated buffer)
- **Post lenDelimSize:** 12.4M ops/s, **136 B/op** (no double size call)
- **Target:** ≤176 B/op ✅, ≥6.3M ops/s ✅
- **Native parity on allocation** (136 B/op both). Throughput 2.9× slower (accepted two-pass cost).

### Conformance encode
- **Pre-fix:** 1.54M ops/s, **5,784 B/op**
- **Post two-pass:** 212K ops/s, **3,048 B/op**
- **Post lenDelimSize:** 202K ops/s, **2,920 B/op**
- Allocation halved (5,784 → 2,920). Throughput dropped (1.54M → 202K) because the
  two-pass cost is proportional to field count (23 fields × 6 oneofs = many size computations).
  The two-pass penalty is **severe for wide messages**.

### Wire-level interop (fromProto/toProto) — new benchmarks
- Small: `fromProto` 119.6M ops/s / 96 B — **8.8× faster** than native protoDecode, **4× less alloc**
- Small: `toProto` 42.0M ops/s / 168 B — **1.2× faster** than native protoEncode
- Conformance: `fromProto` 1.76M / 4,808 B — 0.67× native (slower, more alloc)
- Conformance: `toProto` 1.57M / 7,144 B — 0.39× native (slower, 4.6× more alloc)

Wire-level interop dominates for small messages but loses for wide messages, confirming
the two-pass overhead scales with field count.

## Open items
- [ ] Conformance encode is 20× slower than native — consider single-pass encode with
      growable buffer for wide messages
- [ ] Conformance `toProto` allocates 4.6× native — investigate the wire-level encode path
- [ ] `interopEncode` on conformance allocates 11 KB/op — the object-level conversion
      creates a full protobuf Java `Message` with `FieldSet`/`UnknownFieldSet` overhead
