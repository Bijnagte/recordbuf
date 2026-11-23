package bijnagte.recordbuf.fixture.map;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code map} golden family (see {@link MapCases}): every case is checked forward and reverse
 * in each format it applies to, and reported as skipped, with the reason, in each it does not. The
 * protobuf binary format carries the byte comparison against protobuf-java and the decode of
 * protobuf-java's bytes that this class used to assert by hand.
 */
class MapTest {

  private static final MapCases SOURCE = new MapCases();

  @TestFactory
  Stream<DynamicNode> mapCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
