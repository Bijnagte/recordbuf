package bijnagte.recordbuf.fixture.avroenum;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code avroenum} golden family (see {@link AvroEnumCases}): every case is checked forward and
 * reverse in each format it applies to, and reported as skipped, with the reason, in each it does
 * not.
 */
class AvroEnumTest {

  private static final AvroEnumCases SOURCE = new AvroEnumCases();

  @TestFactory
  Stream<DynamicNode> avroEnumCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
