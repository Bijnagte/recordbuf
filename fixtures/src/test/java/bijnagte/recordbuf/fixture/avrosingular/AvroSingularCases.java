package bijnagte.recordbuf.fixture.avrosingular;

import bijnagte.recordbuf.fixture.avrosingular.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The golden cases of family {@code avrosingular}, testing singular well-known types through the
 * Avro converter. The {@link Sample} message carries a singular {@code Duration}, {@code
 * BytesValue}, {@code Timestamp} and {@code StringValue}. Proto3 JSON is not applicable: the file
 * has no Interop converter.
 *
 * <p>Every value is whole microseconds, Avro's timestamp precision, so {@code populated}
 * round-trips in both Avro formats.
 */
public final class AvroSingularCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avrosingular";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", Sample.builder().build())));
  }

  /**
   * Every singular type set to a non-default value: a negative duration, bytes holding {@code 0x00}
   * and {@code 0xFF}, a timestamp with whole microseconds and a non-ASCII string.
   */
  public static Sample populated() {
    return Sample.builder()
        .span(Duration.ofMillis(-1_500))
        .blob(Bytes.of(new byte[] {0x00, (byte) 0xFF, 0x7F}))
        .at(Instant.ofEpochSecond(1_700_000_000L, 123_456_000L))
        .text("héllo 世界")
        .build();
  }
}
