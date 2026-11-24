package bijnagte.recordbuf.fixture.interopnames;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Interop round-trip tests for edge-case field names ({@code interopnames} family). */
class InteropNamesInteropTest {
  private static final InteropNamesCases SOURCE = new InteropNamesCases();

  @TestFactory
  Stream<DynamicNode> interopNames_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
