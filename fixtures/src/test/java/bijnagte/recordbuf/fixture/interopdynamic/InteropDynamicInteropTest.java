package bijnagte.recordbuf.fixture.interopdynamic;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Interop round-trip tests for dynamic well-known type fields ({@code interopdynamic}). */
class InteropDynamicInteropTest {

  private static final InteropDynamicCases SOURCE = new InteropDynamicCases();

  @TestFactory
  Stream<DynamicNode> interopDynamic_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
