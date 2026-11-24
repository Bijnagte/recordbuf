package bijnagte.recordbuf.fixture.interopoptionalenum;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.interopoptionalenum.rec.InteropOptionalEnum;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code interopoptionalenum}, testing a proto3 {@code optional} enum
 * through the Interop converter and Avro. The {@link InteropOptionalEnum} message carries an
 * optional enum beside a plain enum of the same type. Every case also has a proto3 JSON golden
 * produced through protobuf-java's {@code JsonFormat}.
 *
 * <p>{@code present-zero} sets the optional enum to its zero constant, which proto3 presence keeps:
 * it must read back present, not absent. {@code absent} leaves it unset.
 *
 * <p>Avro writes an unset enum as the first constant, so an absent optional enum reads back as
 * {@code KIND_ZERO} (gap {@code optional-avro-presence}).
 */
public final class InteropOptionalEnumCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "interopoptionalenum";

  @Override
  public List<GoldenCase> cases() {
    InteropOptionalEnum presentZeroRecord = presentZero();
    InteropOptionalEnum absentRecord = absent();
    return List.of(
        build(jacksonLossless(FAMILY, "present-zero", presentZeroRecord)),
        build(
            jacksonLossless(FAMILY, "absent", absentRecord)
                .expectedAfterTrip(avroJson, absentAfterAvro(absentRecord))
                .expectedAfterTrip(avroBinary, absentAfterAvro(absentRecord))));
  }

  /** The optional enum set to its zero constant, which must stay present. */
  public static InteropOptionalEnum presentZero() {
    return InteropOptionalEnum.builder()
        .optKind(InteropOptionalEnum.Kind.KIND_ZERO)
        .plainKind(InteropOptionalEnum.Kind.BETA)
        .n(5)
        .build();
  }

  /** The optional enum unset; the plain enum is set so the record is not all default. */
  public static InteropOptionalEnum absent() {
    return InteropOptionalEnum.builder().plainKind(InteropOptionalEnum.Kind.ALPHA).n(5).build();
  }

  /**
   * The absent record as Avro reads it back. The Avro schema declares {@code opt_kind}
   * non-nullable, so an unset optional enum is written as the first constant and reads back as
   * {@code KIND_ZERO} (gap {@code optional-avro-presence}).
   */
  private static InteropOptionalEnum absentAfterAvro(InteropOptionalEnum record) {
    return record.toBuilder().optKind(InteropOptionalEnum.Kind.KIND_ZERO).build();
  }
}
