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
 * <p>Avro converts each enum as its constant's name. A singular enum that is unset is written as
 * the first constant, so the cases that leave one unset read it back as that constant.
 */
public final class InteropEnumCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interopenum";

  private static final String REPEATED_NOT_UNPACKED =
      "the record writes a repeated enum unpacked, and protobuf-java writes the packed form that"
          + " the record cannot read, so the bytes differ (gap repeated-enum-unpacked-only)";

  private static final String ZERO_ENUM_WRITTEN =
      "the record writes the explicit zero of a singular enum, which protobuf-java omits for a"
          + " proto3 default, so the bytes differ (gap enum-default-value-written)";

  @Override
  public List<GoldenCase> cases() {
    InteropEnum allDefaultRecord = InteropEnum.builder().build();
    InteropEnum repeatedPackedRecord = repeatedPacked();
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(
            withAvroAfterTrip(
                jacksonLossless(FAMILY, "all-default", allDefaultRecord), allDefaultRecord)),
        build(zeroEnum()),
        build(
            withAvroAfterTrip(
                    jacksonLossless(FAMILY, "repeated-packed", repeatedPackedRecord),
                    repeatedPackedRecord)
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
    return withAvroAfterTrip(
            GoldenCase.builder(FAMILY, "zero-enum", record)
                .lossless(jackson)
                .expectedAfterTrip(proto3Json, InteropEnum.builder().build()),
            record)
        .notApplicable(protobufBinary, ZERO_ENUM_WRITTEN);
  }

  /**
   * Declares both Avro formats as reading back {@code record} with each unset singular enum as its
   * first constant: an Avro enum is a non-null string, so an unset enum is written as the proto3
   * default. Every other field round-trips.
   */
  private GoldenCase.Builder withAvroAfterTrip(GoldenCase.Builder builder, InteropEnum record) {
    InteropEnum afterAvro =
        record.toBuilder()
            .singularColor(
                record.singularColor() == null ? Color.COLOR_UNSPECIFIED : record.singularColor())
            .singularKind(
                record.singularKind() == null ? InteropEnum.Kind.KIND_ZERO : record.singularKind())
            .build();
    return builder.expectedAfterTrip(avroJson, afterAvro).expectedAfterTrip(avroBinary, afterAvro);
  }
}
