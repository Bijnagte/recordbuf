package bijnagte.recordbuf.fixture.message;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code message} golden family (see {@link MessageCases}): every case is checked forward and
 * reverse in each format it applies to, and reported as skipped, with the reason, in each it does
 * not. The protobuf binary format carries the byte comparison against protobuf-java and the decode
 * of protobuf-java's bytes that this class used to assert by hand.
 */
class MessageTest {

  private static final MessageCases SOURCE = new MessageCases();

  @TestFactory
  Stream<DynamicNode> messageCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
