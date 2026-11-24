package bijnagte.recordbuf.fixture.enumalias;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/** Round-trip tests for a case-colliding enum alias ({@code enumalias} family). */
class EnumAliasMatrixTest {
  private static final EnumAliasCases SOURCE = new EnumAliasCases();

  @TestFactory
  Stream<DynamicNode> enumAlias_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
