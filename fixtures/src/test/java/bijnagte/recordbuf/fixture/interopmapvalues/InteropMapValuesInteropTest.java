package bijnagte.recordbuf.fixture.interopmapvalues;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Interop round-trip tests for bytes, message and enum map values ({@code interopmapvalues}). */
class InteropMapValuesInteropTest {

  private static final InteropMapValuesCases SOURCE = new InteropMapValuesCases();

  @TestFactory
  Stream<DynamicNode> interopMapValues_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
