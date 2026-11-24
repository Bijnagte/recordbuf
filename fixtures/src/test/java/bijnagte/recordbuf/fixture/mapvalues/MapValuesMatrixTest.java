package bijnagte.recordbuf.fixture.mapvalues;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Round-trip tests for bytes, message and enum map values ({@code mapvalues} family). */
class MapValuesMatrixTest {
  private static final MapValuesCases SOURCE = new MapValuesCases();

  @TestFactory
  Stream<DynamicNode> mapValues_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
