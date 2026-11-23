package bijnagte.recordbuf.fixture.bytes;

import bijnagte.recordbuf.fixture.bytes.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.List;

/**
 * The golden cases of family {@code bytes}, migrated from {@code BytesTest}: the two-field {@link
 * Sample}. {@code empty-and-mixed} is the one value that test asserted on: an empty array (the
 * proto3 default, not written) and mixed-sign bytes, among them {@code 0x7f} and {@code 0x80},
 * which prove the value is framed as raw bytes and not a signed primitive. The other cases are the
 * default record and a value with every byte at the extremes.
 */
public final class BytesCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "bytes";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonLossless(
                FAMILY,
                "empty-and-mixed",
                Sample.builder()
                    .a(Bytes.of(new byte[] {}))
                    .b(Bytes.of(new byte[] {1, -1, 0, 127, -128, 65}))
                    .build())),
        build(jacksonLossless(FAMILY, "all-empty", Sample.builder().build())),
        build(
            jacksonLossless(
                FAMILY,
                "extreme-bytes",
                Sample.builder()
                    .a(Bytes.of(new byte[] {0}))
                    .b(Bytes.of(new byte[] {(byte) 0xff, (byte) 0x80, 0x7f, 0x00}))
                    .build())));
  }
}
