package bijnagte.recordbuf.fixture.oneoforder;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.fixture.oneoforder.rec.Sample;
import bijnagte.recordbuf.fixture.oneoforder.rec.Sample.O;
import bijnagte.recordbuf.fixture.oneoforder.rec.Sample.P;
import bijnagte.recordbuf.runtime.Bytes;
import java.util.List;

/**
 * The golden cases of family {@code oneoforder}, migrated from {@code OneofOrderTest}: a {@link
 * Sample} whose oneof members sit at field numbers among the regular fields (regular {@code first}
 * 1, {@code mid} 4 and {@code tail} 9; oneof {@code o} members 2 and 6; oneof {@code p} members 3
 * and 8), so the wire order must interleave them in ascending field number, as protobuf-java
 * writes. The four cases are the four values that test asserted on: {@code s-and-blob} (members
 * numbered between and below the regular fields), {@code n-and-big} (members after {@code mid} and
 * before {@code tail}), {@code big-zero-only} (one oneof set to its zero value, defaults
 * elsewhere), and {@code empty-s-with-tail} (a set empty string and a repeated tail).
 */
public final class OneofOrderCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "oneoforder";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonLossless(
                FAMILY,
                "s-and-blob",
                sample(new O.SValue("x"), new P.BlobValue(Bytes.of(new byte[] {7})), 1, 5, "t"))),
        build(
            jacksonLossless(
                FAMILY, "n-and-big", sample(new O.NValue(3), new P.BigValue(9L), 1, 5, "t", "u"))),
        build(jacksonLossless(FAMILY, "big-zero-only", sample(null, new P.BigValue(0L), 0, 0))),
        build(
            jacksonLossless(
                FAMILY, "empty-s-with-tail", sample(new O.SValue(""), null, 0, 0, "z"))));
  }

  private static Sample sample(O o, P p, int first, int mid, String... tail) {
    return Sample.builder().first(first).o(o).mid(mid).tail(List.of(tail)).p(p).build();
  }
}
