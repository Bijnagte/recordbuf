package bijnagte.recordbuf.fixture.enumalias;

import bijnagte.recordbuf.fixture.enumalias.rec.EnumAlias;
import bijnagte.recordbuf.fixture.enumalias.rec.Flavor;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code enumalias}, testing an enum alias that differs only in case.
 * The {@link Flavor} enum has {@code foo} and {@code FOO} both as number 1, so the generated enum
 * keeps one {@code FOO} constant. A populated case sets both enum shapes, and a default case leaves
 * them unset. Every case round-trips through all five formats. The Avro enum is written as the
 * canonical {@code FOO}, and the proto3 JSON as protobuf-java's canonical name.
 */
public final class EnumAliasCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "enumalias";

  @Override
  public List<GoldenCase> cases() {
    EnumAlias allDefaultRecord = EnumAlias.builder().build();
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(
            withAvroAfterTrip(
                jacksonLossless(FAMILY, "all-default", allDefaultRecord), allDefaultRecord)));
  }

  /** A singular and a repeated enum set, the repeated one holding the merged constant. */
  public static EnumAlias populated() {
    return EnumAlias.builder()
        .singularFlavor(Flavor.FOO)
        .repeatedFlavors(List.of(Flavor.BAR, Flavor.FOO))
        .build();
  }

  /**
   * Declares both Avro formats as reading back {@code record} with an unset singular enum as its
   * first constant: an Avro enum is a non-null string, so an unset enum is written as the proto3
   * default. Every other field round-trips.
   */
  private GoldenCase.Builder withAvroAfterTrip(GoldenCase.Builder builder, EnumAlias record) {
    EnumAlias afterAvro =
        record.toBuilder()
            .singularFlavor(record.singularFlavor() == null ? Flavor.ZERO : record.singularFlavor())
            .build();
    return builder.expectedAfterTrip(avroJson, afterAvro).expectedAfterTrip(avroBinary, afterAvro);
  }
}
