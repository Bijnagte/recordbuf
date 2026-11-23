package bijnagte.recordbuf.fixture.oneofwkt;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code oneofwkt} golden family (see {@link OneofWktCases}): every case is checked forward and
 * reverse in each format it applies to, and reported as skipped, with the reason, in each it does
 * not. The protobuf binary format carries the byte comparison against protobuf-java and the decode
 * of protobuf-java's bytes that this class used to assert by hand, with every set member on the
 * wire even at its zero value, and both Avro formats carry the round trip through the shared {@code
 * {tag, value}} oneof wrapper.
 */
class OneofWktTest {

  private static final OneofWktCases SOURCE = new OneofWktCases();

  @TestFactory
  Stream<DynamicNode> oneofwktCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
