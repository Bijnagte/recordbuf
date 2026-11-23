package bijnagte.recordbuf.fixture.packed;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.packed.rec.Sample;
import java.util.List;

/**
 * The golden cases of family {@code packed}, migrated from {@code PackedTest}: the ten-field {@link
 * Sample}, one {@code repeated} numeric field per scalar family, which proto3 writes packed (one
 * tag and length, then the values). {@code populated} is the value that test asserted on: a
 * distinct-value list for every field, negative for the signed families so varint and zigzag
 * packing are exercised, with fixed-width and IEEE forms for the rest. {@code all-empty} is the
 * default that test also asserted on: an empty list for every field, so every packed group is
 * omitted from the wire.
 */
public final class PackedCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "packed";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-empty", Sample.builder().build())));
  }

  /** A non-empty list for every field: the value {@code PackedTest} asserted on. */
  public static Sample populated() {
    return Sample.builder()
        .a(List.of(1, -2, Integer.MAX_VALUE, 0))
        .b(List.of(1L, -2L, 1_000_000L))
        .c(List.of(7, -7, 0))
        .d(List.of(7L, -7L, 1_000_000L))
        .e(List.of(1.5f, -2.5f, 0f))
        .f(List.of(1.25, -3.75, 0.0))
        .g(List.of(1, -5, 100))
        .h(List.of(1L, -7L, 1_000L))
        .i(List.of(1, 42, 1_000))
        .j(List.of(1L, 12_345L, 9_000_000_000L))
        .build();
  }
}
