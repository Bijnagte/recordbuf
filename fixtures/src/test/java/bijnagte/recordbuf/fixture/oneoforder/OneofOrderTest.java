package bijnagte.recordbuf.fixture.oneoforder;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code oneoforder} golden family (see {@link OneofOrderCases}): every case is checked forward
 * and reverse in each format it applies to, and reported as skipped, with the reason, in each it
 * does not. The protobuf binary format carries the byte comparison against protobuf-java (a oneof
 * member is written at its own field number among the regular fields, as protobuf-java orders them)
 * and the decode of protobuf-java's bytes that this class used to assert by hand.
 */
class OneofOrderTest {

  private static final OneofOrderCases SOURCE = new OneofOrderCases();

  @TestFactory
  Stream<DynamicNode> oneoforderCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
