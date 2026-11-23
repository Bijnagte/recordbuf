package bijnagte.recordbuf.fixture.order;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.order.rec.Sample;
import java.util.List;

/**
 * The golden cases of family {@code order}, migrated from {@code OrderTest}: the {@link Sample}
 * whose field numbers are declared non-ascending ({@code c = 3}, {@code a = 1}, {@code b = 2}).
 * {@code non-ascending-numbers} is the one value that test asserted on. protobuf-java always writes
 * in ascending field-number order, so its protobuf binary golden, which is protobuf-java's own
 * bytes, can only match if the generated encoder sorts by field number and not by declaration
 * order.
 */
public final class OrderCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "order";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonLossless(
                FAMILY, "non-ascending-numbers", Sample.builder().c(3).a(1).b(2).build())));
  }
}
