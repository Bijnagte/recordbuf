package bijnagte.recordbuf.fixture.optional;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.optional.rec.Sample;
import bijnagte.recordbuf.fixture.optional.rec.Sample.Fixed;
import java.util.List;

/**
 * The golden cases of family {@code optional}, migrated from {@code OptionalTest}: the {@link
 * Sample} with a presence-carrying {@code optional} int, string, bool and nested message. {@code
 * populated} and {@code all-absent} are the two values that test asserted on: a non-default value
 * for every field, and every field {@code null}, which writes nothing and must read back as {@code
 * null}, not the proto3 default. {@code set-to-default} is the boundary between them: every field
 * present at its default ({@code 0}, {@code ""}, {@code false}, an empty message), which {@code
 * optional} writes where a plain proto3 field would not, so the record stays distinct from {@code
 * all-absent} in every format.
 */
public final class OptionalCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "optional";

  private static final String AVRO_HAS_NO_PRESENCE =
      "the record leaves every optional field null, which the generated Avro converter cannot"
          + " write: the Avro schema declares each field non-nullable, so the converter sets null"
          + " on the string and message fields and Avro rejects it (gap optional-avro-presence)";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonLossless(
                FAMILY,
                "populated",
                Sample.builder()
                    .a(42)
                    .b("hello")
                    .c(true)
                    .d(Fixed.builder().value(1).build())
                    .build())),
        build(
            jacksonLossless(FAMILY, "all-absent", Sample.builder().build())
                .notApplicable(avroJson, AVRO_HAS_NO_PRESENCE)
                .notApplicable(avroBinary, AVRO_HAS_NO_PRESENCE)),
        build(
            jacksonLossless(
                FAMILY,
                "set-to-default",
                Sample.builder().a(0).b("").c(false).d(Fixed.builder().build()).build())));
  }
}
