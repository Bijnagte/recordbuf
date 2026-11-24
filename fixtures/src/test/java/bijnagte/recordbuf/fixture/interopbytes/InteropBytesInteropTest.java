package bijnagte.recordbuf.fixture.interopbytes;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Interop round-trip tests for bytes fields ({@code interopbytes} family). */
class InteropBytesInteropTest {

  private static final InteropBytesCases SOURCE = new InteropBytesCases();

  @TestFactory
  Stream<DynamicNode> interopBytes_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
