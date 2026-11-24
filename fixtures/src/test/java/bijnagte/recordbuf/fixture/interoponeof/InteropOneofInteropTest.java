package bijnagte.recordbuf.fixture.interoponeof;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Interop round-trip tests for oneof members ({@code interoponeof} family). */
class InteropOneofInteropTest {

  private static final InteropOneofCases SOURCE = new InteropOneofCases();

  @TestFactory
  Stream<DynamicNode> interopOneof_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
