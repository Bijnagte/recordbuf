package bijnagte.recordbuf.fixture.avrowrappers;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code avrowrappers} golden family (see {@link AvroWrappersCases}): every case is checked
 * forward and reverse in each format it applies to, and reported as skipped, with the reason, in
 * each it does not.
 */
class AvroWrappersTest {

  private static final AvroWrappersCases SOURCE = new AvroWrappersCases();

  @TestFactory
  Stream<DynamicNode> avroWrappersCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
