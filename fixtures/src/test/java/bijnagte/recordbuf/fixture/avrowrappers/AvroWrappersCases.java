package bijnagte.recordbuf.fixture.avrowrappers;

import bijnagte.recordbuf.fixture.avrowrappers.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.List;

/**
 * The golden cases of family {@code avrowrappers}, testing repeated scalar-wrapper and {@code
 * BytesValue} fields through the Avro converter. The {@link Sample} message carries one repeated
 * wrapper of each scalar kind and one of {@code BytesValue}. Proto3 JSON is not applicable: the
 * file has no Interop converter.
 */
public final class AvroWrappersCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avrowrappers";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", Sample.builder().build())));
  }

  /**
   * Every repeated wrapper with two elements: a non-ASCII string, bytes with the high bit set, and
   * unsigned values at the top of their non-negative range.
   */
  public static Sample populated() {
    return Sample.builder()
        .bools(List.of(true, false))
        .int32s(List.of(42, -7))
        .uint32s(List.of(1, Integer.MAX_VALUE))
        .int64s(List.of(7L, -9L))
        .uint64s(List.of(1L, Long.MAX_VALUE))
        .floats(List.of(1.5f, -2.25f))
        .doubles(List.of(3.5d, -0.5d))
        .strings(List.of("héllo 世界", "x"))
        .blobs(
            List.of(
                Bytes.of(new byte[] {(byte) 0xC3, (byte) 0xA9, (byte) 0xFF}),
                Bytes.of(new byte[] {(byte) 0x80})))
        .build();
  }
}
