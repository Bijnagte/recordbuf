package bijnagte.recordbuf.fixture.oneofnames;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Round-trip tests for snake_case oneof member names ({@code oneofnames} family). */
class OneofNamesMatrixTest {
  private static final OneofNamesCases SOURCE = new OneofNamesCases();

  @TestFactory
  Stream<DynamicNode> oneofNames_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
