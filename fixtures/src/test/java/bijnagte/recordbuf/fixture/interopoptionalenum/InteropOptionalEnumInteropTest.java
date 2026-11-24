package bijnagte.recordbuf.fixture.interopoptionalenum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.interopoptionalenum.InteropOptionalEnum.Kind;
import bijnagte.recordbuf.fixture.interopoptionalenum.rec.InteropOptionalEnum;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Interop tests for a proto3 {@code optional} enum ({@code interopoptionalenum} family). The
 * protobuf-java reference class shares the simple name of the record, so it is referred to by its
 * qualified name.
 */
class InteropOptionalEnumInteropTest {

  private static final InteropOptionalEnumCases SOURCE = new InteropOptionalEnumCases();

  @TestFactory
  Stream<DynamicNode> interopOptionalEnum_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  /** An unset optional enum converts to a null record component, not to the zero constant. */
  @Test
  void unsetOptionalEnum_convertsToNull() {
    var reference =
        bijnagte.recordbuf.fixture.interopoptionalenum.InteropOptionalEnum.newBuilder().build();

    InteropOptionalEnum record = InteropOptionalEnum.Interop.from(reference);

    assertNull(record.optKind());
  }

  /**
   * An optional enum set to its zero constant is present, and converts to {@code KIND_ZERO}: the
   * presence protobuf-java keeps for an explicit zero.
   */
  @Test
  void optionalEnumSetToZero_convertsToTheZeroConstant() {
    var reference =
        bijnagte.recordbuf.fixture.interopoptionalenum.InteropOptionalEnum.newBuilder()
            .setOptKind(Kind.KIND_ZERO)
            .build();

    InteropOptionalEnum record = InteropOptionalEnum.Interop.from(reference);

    assertEquals(InteropOptionalEnum.Kind.KIND_ZERO, record.optKind());
  }
}
