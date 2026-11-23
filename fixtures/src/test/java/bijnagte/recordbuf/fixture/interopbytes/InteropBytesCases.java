package bijnagte.recordbuf.fixture.interopbytes;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.interopbytes.rec.InteropBytes;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.List;

/**
 * The golden cases of family {@code interopbytes}, testing bytes field conversion through the
 * Interop converter. The {@link InteropBytes} message carries a singular and a repeated {@code
 * bytes} field. Every case also has a proto3 JSON golden produced through the generated {@code
 * Interop} converter.
 */
public final class InteropBytesCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interopbytes";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", InteropBytes.builder().build())),
        build(
            jacksonLossless(
                FAMILY,
                "empty-element-only",
                InteropBytes.builder().chunks(List.of(Bytes.of(new byte[] {}))).build())));
  }

  /**
   * A singular value with a zero and a high-bit byte, and a repeated list holding an empty element,
   * a one-byte element and an element with every high-bit byte.
   */
  public static InteropBytes populated() {
    return InteropBytes.builder()
        .single(Bytes.of(new byte[] {0x00, 0x7F, (byte) 0xFF, 0x01}))
        .chunks(
            List.of(
                Bytes.of(new byte[] {}),
                Bytes.of(new byte[] {0x7F}),
                Bytes.of(
                    new byte[] {
                      (byte) 0x80,
                      (byte) 0x81,
                      (byte) 0x88,
                      (byte) 0x90,
                      (byte) 0xA0,
                      (byte) 0xC0,
                      (byte) 0xE0,
                      (byte) 0xFF
                    })))
        .build();
  }
}
