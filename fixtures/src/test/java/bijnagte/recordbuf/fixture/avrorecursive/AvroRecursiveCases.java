package bijnagte.recordbuf.fixture.avrorecursive;

import bijnagte.recordbuf.fixture.avrorecursive.rec.A;
import bijnagte.recordbuf.fixture.avrorecursive.rec.B;
import bijnagte.recordbuf.fixture.avrorecursive.rec.Node;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import java.util.List;

/**
 * The golden cases of family {@code avrorecursive}, testing recursive messages through the Avro
 * converter. {@link Node} refers to itself ({@code child}, {@code kids}) and to the {@link
 * A}/{@link B} pair, which refer to each other. The singular references that close a cycle are
 * nullable in Avro; the {@code Node.a} reference is not on a cycle through {@code Node}, so it
 * stays non-nullable, and every {@link Node} that is written sets it (gap {@code
 * avro-absent-singular-message}). Proto3 JSON is not applicable: the file has no Interop converter.
 */
public final class AvroRecursiveCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avrorecursive";

  private static final String ABSENT_NON_CYCLE_MESSAGE =
      "the empty Node leaves its non-cycle singular a absent (null), and the Avro schema declares it"
          + " non-nullable, so the converter cannot write the record to Avro (gap"
          + " avro-absent-singular-message)";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "chain", chain())),
        build(
            jacksonLossless(FAMILY, "all-default", Node.builder().build())
                .notApplicable(avroJson, ABSENT_NON_CYCLE_MESSAGE)
                .notApplicable(avroBinary, ABSENT_NON_CYCLE_MESSAGE)));
  }

  /**
   * Three levels of {@code child} with an absent innermost child, a repeated element, and an {@code
   * A}/{@code B} chain ending in an absent {@code A.b}. Every {@code Node} sets its {@code a}.
   */
  public static Node chain() {
    Node leaf = Node.builder().label("leaf").a(A.builder().n(3).build()).build();
    Node mid = Node.builder().label("mid").child(leaf).a(A.builder().n(2).build()).build();
    Node kid = Node.builder().label("kid").a(A.builder().n(5).build()).build();
    A innermost = A.builder().n(4).build();
    B middle = B.builder().s("b").a(innermost).build();
    return Node.builder()
        .label("root")
        .child(mid)
        .a(A.builder().n(1).b(middle).build())
        .kids(List.of(kid))
        .build();
  }
}
