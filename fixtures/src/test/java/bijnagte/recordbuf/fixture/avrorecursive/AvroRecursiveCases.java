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
 * A}/{@link B} pair, which refer to each other. Every singular message reference is a null-first
 * union in Avro, so an absent one is the null branch. Proto3 JSON is not applicable: the file has
 * no Interop converter.
 */
public final class AvroRecursiveCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "avrorecursive";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(jacksonLossless(FAMILY, "chain", chain())),
        build(jacksonLossless(FAMILY, "all-default", Node.builder().build())));
  }

  /**
   * Three levels of {@code child} with an absent innermost child, a repeated element, and an {@code
   * A}/{@code B} chain ending in an absent {@code A.b}. The empty Node is its own case.
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
