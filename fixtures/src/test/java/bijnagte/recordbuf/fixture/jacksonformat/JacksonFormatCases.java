package bijnagte.recordbuf.fixture.jacksonformat;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.jacksonformat.rec.Probe;
import bijnagte.recordbuf.runtime.UnknownFields;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The golden case of family {@code jacksonformat}, migrated from {@code JacksonFormatTest}: the
 * {@code recordbuf.jackson_format} knob on temporal fields. {@code at} takes the default ISO
 * string, {@code span} the canonical {@code {seconds, nanos}} object, {@code whens} an array of ISO
 * strings.
 */
public final class JacksonFormatCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "jacksonformat";

  @Override
  public List<GoldenCase> cases() {
    return List.of(probeCase());
  }

  /**
   * Avro writes a timestamp as microseconds, so both Avro goldens hold {@code at} cut to the
   * microsecond and deserialize to the probe with that value.
   */
  private GoldenCase probeCase() {
    Probe probe = probe();
    Probe atMicros =
        new Probe(
            probe.at().truncatedTo(ChronoUnit.MICROS),
            probe.span(),
            probe.whens(),
            null,
            UnknownFields.empty());
    return build(
        jacksonLossless(FAMILY, "probe", probe)
            .expectedAfterTrip(avroJson, atMicros)
            .expectedAfterTrip(avroBinary, atMicros));
  }

  /** A {@code Probe} with its three temporal fields set. */
  public static Probe probe() {
    return new Probe(
        Instant.ofEpochSecond(1_763_614_896L, 789_012_345),
        Duration.ofSeconds(3661, 500_000_000),
        List.of(Instant.EPOCH, Instant.ofEpochSecond(1, 250_000_000)),
        null,
        UnknownFields.empty());
  }
}
