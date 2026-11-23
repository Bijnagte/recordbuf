package bijnagte.recordbuf.fixture.interopwkt;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Interop round-trip tests for natively mapped well-known type fields ({@code interopwkt}). */
class InteropWktInteropTest {

  private static final InteropWktCases SOURCE = new InteropWktCases();

  @TestFactory
  Stream<DynamicNode> interopWkt_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
