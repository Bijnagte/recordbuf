package bijnagte.recordbuf.fixture.annotations;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code annotations} golden family (see {@link AnnotationsCases}): every case is checked
 * forward and reverse in each format it applies to, and reported as skipped, with the reason, in
 * each it does not. The protobuf binary format carries the byte comparison against protobuf-java
 * and the decode of protobuf-java's bytes that this class used to assert by hand: the Javadoc and
 * {@code @Deprecated} emission changes nothing on the wire, so the wire parity still holds.
 */
class AnnotationsTest {

  private static final AnnotationsCases SOURCE = new AnnotationsCases();

  @TestFactory
  Stream<DynamicNode> annotationsCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }
}
