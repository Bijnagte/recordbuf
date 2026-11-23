package bijnagte.recordbuf.fixture.avroenum;

import bijnagte.recordbuf.fixture.avroenum.rec.Color;
import bijnagte.recordbuf.fixture.avroenum.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code avroenum}, testing enum field conversion through the Avro
 * converter. The {@link Sample} message carries a singular and a repeated enum, nested and
 * top-level. Proto3 JSON is not applicable: the file has no Interop converter.
 */
public final class AvroEnumCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avroenum";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(
            jacksonLossless(FAMILY, "all-default", Sample.builder().build())
                // Avro's enum is a non-null string, so an unset enum writes the proto3 default (the
                // first constant), which reads back as that constant rather than as unset.
                .expectedAfterTrip(avroJson, allDefaultAfterTrip())
                .expectedAfterTrip(avroBinary, allDefaultAfterTrip())));
  }

  /** Every enum set to a non-default constant, the nested one negative in the repeated list. */
  public static Sample populated() {
    return Sample.builder()
        .kind(Sample.Kind.BETA)
        .color(Color.GREEN)
        .kinds(List.of(Sample.Kind.ALPHA, Sample.Kind.NEG))
        .colors(List.of(Color.RED))
        .build();
  }

  /** The all-default record as Avro reads it back: each unset enum is its first constant. */
  private static Sample allDefaultAfterTrip() {
    return Sample.builder().kind(Sample.Kind.KIND_ZERO).color(Color.COLOR_UNSPECIFIED).build();
  }
}
