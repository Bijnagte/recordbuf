package bijnagte.recordbuf.fixture.avrooneof;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code avrooneof} golden family (see {@link AvroOneofCases}): every case is checked forward
 * and reverse in each format it applies to, and reported as skipped, with the reason, in each it
 * does not.
 */
class AvroOneofTest {

  private static final AvroOneofCases SOURCE = new AvroOneofCases();

  @TestFactory
  Stream<DynamicNode> avroOneofCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
