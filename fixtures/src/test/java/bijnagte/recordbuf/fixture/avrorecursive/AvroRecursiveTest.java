package bijnagte.recordbuf.fixture.avrorecursive;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code avrorecursive} golden family (see {@link AvroRecursiveCases}): every case is checked
 * forward and reverse in each format it applies to, and reported as skipped, with the reason, in
 * each it does not.
 */
class AvroRecursiveTest {

  private static final AvroRecursiveCases SOURCE = new AvroRecursiveCases();

  @TestFactory
  Stream<DynamicNode> avroRecursiveCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
