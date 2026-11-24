package bijnagte.recordbuf.fixture.edgenames;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Round-trip tests for upper-case-start field names ({@code edgenames} family). */
class EdgeNamesMatrixTest {
  private static final EdgeNamesCases SOURCE = new EdgeNamesCases();

  @TestFactory
  Stream<DynamicNode> edgeNames_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
