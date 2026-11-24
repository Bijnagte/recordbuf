package bijnagte.recordbuf.fixture.interopenum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.interopenum.rec.InteropEnum;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Interop round-trip tests for enum fields ({@code interopenum} family). The protobuf-java
 * reference class {@code bijnagte.recordbuf.fixture.interopenum.InteropEnum} shares the simple name
 * of the record, so it is referred to by its qualified name.
 */
class InteropEnumInteropTest {

  private static final InteropEnumCases SOURCE = new InteropEnumCases();

  @TestFactory
  Stream<DynamicNode> interopEnum_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  /**
   * A protobuf-java message whose singular enum holds an undeclared number converts through {@code
   * Interop.from} without throwing, to whatever the record's {@code forNumber} returns for it.
   */
  @Test
  void undeclaredSingularEnumNumber_convertsThroughForNumber() {
    var reference =
        bijnagte.recordbuf.fixture.interopenum.InteropEnum.newBuilder()
            .setSingularKindValue(99)
            .build();

    InteropEnum record = InteropEnum.Interop.from(reference);

    assertEquals(InteropEnum.Kind.forNumber(99), record.singularKind());
  }
}
