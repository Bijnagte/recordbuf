package bijnagte.recordbuf.fixture.i32;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.i32.rec.Sample;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code int}, migrated from {@code IntTest}: the {@code int32} {@link
 * Sample}. {@code int32-min} is the one value that test asserted on; the others are the boundaries
 * around it (the other extreme, a negative value that protobuf-java writes as a ten-byte varint,
 * and the default that is not written at all).
 */
public final class IntCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "int";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "int32-min", Sample.builder().a(Integer.MIN_VALUE).build())),
        build(jacksonLossless(FAMILY, "int32-max", Sample.builder().a(Integer.MAX_VALUE).build())),
        build(jacksonLossless(FAMILY, "int32-minus-one", Sample.builder().a(-1).build())),
        build(jacksonLossless(FAMILY, "int32-default", Sample.builder().build())));
  }
}
