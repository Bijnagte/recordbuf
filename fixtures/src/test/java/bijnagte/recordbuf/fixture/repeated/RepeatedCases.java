package bijnagte.recordbuf.fixture.repeated;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.repeated.rec.Sample;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.List;

/**
 * The golden cases of family {@code repeated}, migrated from {@code RepeatedTest}: the {@link
 * Sample} with a {@code repeated string} and a {@code repeated bytes}, which proto3 never packs, so
 * every element, an empty one included, is its own tagged field. {@code populated} is the value
 * that test asserted on: strings with an empty, a non-ASCII and a 200-character element, and byte
 * arrays with an empty and a mixed-sign element. {@code all-empty} is the default that test also
 * asserted on: both lists absent from the wire.
 */
public final class RepeatedCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "repeated";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-empty", Sample.builder().build())));
  }

  /**
   * Both lists non-empty, each with an empty element: the value {@code RepeatedTest} asserted on.
   */
  public static Sample populated() {
    return Sample.builder()
        .a(List.of("one", "", "dré", "x".repeat(200)))
        .b(
            List.of(
                Bytes.of(new byte[] {1, 2, 3}),
                Bytes.of(new byte[0]),
                Bytes.of(new byte[] {(byte) 0xff, 0, (byte) 0x80})))
        .build();
  }
}
