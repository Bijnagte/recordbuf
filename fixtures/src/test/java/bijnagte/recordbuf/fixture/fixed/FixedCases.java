package bijnagte.recordbuf.fixture.fixed;

import bijnagte.recordbuf.fixture.fixed.rec.Sample;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code fixed}, migrated from {@code FixedTest}: the twelve-field
 * {@link Sample} with one field of each varint, fixed-width and floating-point scalar type. {@code
 * mixed-values} is the one value that test asserted on, each field meaningfully non-default
 * (negative signed fields, a non-zero fixed value, non-integer floating point). The other cases are
 * the boundaries: the default record, the signed and floating-point extremes, and negative zero,
 * which is not the default for protobuf-java (it compares the bits).
 */
public final class FixedCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "fixed";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "mixed-values", mixedValues())),
        build(jacksonLossless(FAMILY, "all-default", Sample.builder().build())),
        build(jacksonLossless(FAMILY, "signed-extremes", signedExtremes())),
        build(jacksonLossless(FAMILY, "negative-zero", negativeZero())));
  }

  /** Every field non-default: the value {@code FixedTest} asserted on. */
  public static Sample mixedValues() {
    return Sample.builder()
        .a(2_000_000_000)
        .b(-42)
        .c(-1_234_567_890L)
        .d(12_345L)
        .e(7)
        .f(7L)
        .g(-7)
        .h(-7L)
        .i(1.25)
        .j(1.5f)
        .k(-5)
        .l(-9L)
        .build();
  }

  /**
   * The signed integer minimums, the unsigned values at their largest signed carrier, the
   * fixed-width all-ones patterns, and the largest double and smallest positive float.
   */
  public static Sample signedExtremes() {
    return Sample.builder()
        .a(Integer.MAX_VALUE)
        .b(Integer.MIN_VALUE)
        .c(Long.MIN_VALUE)
        .d(Long.MAX_VALUE)
        .e(-1)
        .f(-1L)
        .g(Integer.MIN_VALUE)
        .h(Long.MIN_VALUE)
        .i(Double.MAX_VALUE)
        .j(Float.MIN_VALUE)
        .k(Integer.MIN_VALUE)
        .l(Long.MIN_VALUE)
        .build();
  }

  /** The two floating-point fields at {@code -0.0}, with every other field default. */
  public static Sample negativeZero() {
    return Sample.builder().i(-0.0).j(-0.0f).build();
  }
}
