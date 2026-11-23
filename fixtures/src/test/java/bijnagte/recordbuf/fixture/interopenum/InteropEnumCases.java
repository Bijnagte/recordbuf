package bijnagte.recordbuf.fixture.interopenum;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.interopenum.rec.Color;
import bijnagte.recordbuf.fixture.interopenum.rec.InteropEnum;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code interopenum}, testing enum field conversion through the Interop
 * converter. The {@link InteropEnum} message carries a singular, a repeated and an unpacked nested
 * enum, a top-level singular and repeated enum, and a oneof with an enum member. Every case also
 * has a proto3 JSON golden produced through the generated {@code Interop} converter.
 *
 * <p>The file switches Avro off, so both Avro formats are not applicable to every case (gap {@code
 * avro-surface}).
 */
public final class InteropEnumCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interopenum";

  private static final String AVRO_OFF =
      "the file switches Avro off, and the Avro converter does not convert enum fields yet (gap"
          + " avro-surface)";

  private static final String REPEATED_NOT_UNPACKED =
      "the record writes a repeated enum unpacked, and protobuf-java writes the packed form that"
          + " the record cannot read, so the bytes differ (gap repeated-enum-unpacked-only)";

  private static final String ZERO_ENUM_WRITTEN =
      "the record writes the explicit zero of a singular enum, which protobuf-java omits for a"
          + " proto3 default, so the bytes differ (gap enum-default-value-written)";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(withoutAvro(jacksonLossless(FAMILY, "populated", populated()))),
        build(withoutAvro(jacksonLossless(FAMILY, "all-default", InteropEnum.builder().build()))),
        build(zeroEnum()),
        build(
            withoutAvro(jacksonLossless(FAMILY, "repeated-packed", repeatedPacked()))
                .notApplicable(protobufBinary, REPEATED_NOT_UNPACKED)));
  }

  /**
   * A populated case: singular enums set, the oneof on its enum member, and the unpacked repeated
   * nested enum set. The packed repeated fields stay empty (see {@link #repeatedPacked}).
   */
  public static InteropEnum populated() {
    return InteropEnum.builder()
        .singularColor(Color.GREEN)
        .singularKind(InteropEnum.Kind.BETA)
        .choice(new InteropEnum.Choice.PickedValue(InteropEnum.Kind.NEG))
        .unpackedKinds(
            List.of(InteropEnum.Kind.ALPHA, InteropEnum.Kind.NEG, InteropEnum.Kind.KIND_ZERO))
        .build();
  }

  /** Repeated enums of both kinds, packed by the proto3 default. */
  public static InteropEnum repeatedPacked() {
    return InteropEnum.builder()
        .repeatedKinds(
            List.of(InteropEnum.Kind.ALPHA, InteropEnum.Kind.NEG, InteropEnum.Kind.KIND_ZERO))
        .repeatedColors(List.of(Color.RED, Color.GREEN))
        .build();
  }

  /**
   * A singular nested enum holding its zero constant. protobuf-java writes nothing for it, so after
   * the proto3 JSON trip the field is absent again (the expected record).
   */
  private GoldenCase.Builder zeroEnum() {
    InteropEnum record = InteropEnum.builder().singularKind(InteropEnum.Kind.KIND_ZERO).build();
    return withoutAvro(
        GoldenCase.builder(FAMILY, "zero-enum", record)
            .lossless(jackson)
            .expectedAfterTrip(proto3Json, InteropEnum.builder().build())
            .notApplicable(protobufBinary, ZERO_ENUM_WRITTEN));
  }

  private GoldenCase.Builder withoutAvro(GoldenCase.Builder builder) {
    return builder.notApplicable(avroJson, AVRO_OFF).notApplicable(avroBinary, AVRO_OFF);
  }
}
