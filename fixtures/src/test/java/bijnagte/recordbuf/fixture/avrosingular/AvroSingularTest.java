package bijnagte.recordbuf.fixture.avrosingular;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code avrosingular} golden family (see {@link AvroSingularCases}): every case is checked
 * forward and reverse in each format it applies to, and reported as skipped, with the reason, in
 * each it does not.
 */
class AvroSingularTest {

  private static final AvroSingularCases SOURCE = new AvroSingularCases();

  @TestFactory
  Stream<DynamicNode> avroSingularCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
