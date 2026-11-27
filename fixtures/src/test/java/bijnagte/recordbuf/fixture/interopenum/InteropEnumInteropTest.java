package bijnagte.recordbuf.fixture.interopenum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.interopenum.rec.InteropEnum;
import java.util.List;
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
   * A message whose singular, repeated, unpacked and oneof enums each hold an undeclared number.
   */
  private static bijnagte.recordbuf.fixture.interopenum.InteropEnum reference() {
    return bijnagte.recordbuf.fixture.interopenum.InteropEnum.newBuilder()
        .setSingularKindValue(99)
        .addRepeatedKindsValue(InteropEnum.Kind.ALPHA.getNumber())
        .addRepeatedKindsValue(77)
        .addUnpackedKindsValue(88)
        .setPickedValue(55)
        .build();
  }

  /**
   * An enum number no constant has stays out of the record's enum fields and rides the
   * unknown-field carrier, so {@code Interop.from} and the wire decoder agree, and neither invents
   * the first constant.
   */
  @Test
  void undeclaredEnumNumbers_rideTheCarrier() throws Exception {
    var reference = reference();

    InteropEnum fromInterop = InteropEnum.Interop.from(reference);
    InteropEnum fromBytes = InteropEnum.fromBytes(reference.toByteArray());

    assertNull(fromInterop.singularKind());
    assertEquals(List.of(InteropEnum.Kind.ALPHA), fromInterop.repeatedKinds());
    assertEquals(List.of(), fromInterop.unpackedKinds());
    assertNull(fromInterop.choice());
    assertEquals(
        List.of(2L, 3L, 5L, 6L),
        fromInterop.unknownFields().fields().stream()
            .map(field -> (long) field.fieldNumber())
            .sorted()
            .toList());
    assertEquals(fromBytes, fromInterop);
  }

  /**
   * The numbers survive {@code Interop.to} and a re-encode: the reference reads the same message.
   */
  @Test
  void undeclaredEnumNumbers_surviveTheRoundTrips() throws Exception {
    var reference = reference();
    InteropEnum record = InteropEnum.fromBytes(reference.toByteArray());

    assertEquals(reference, InteropEnum.Interop.to(record));
    assertEquals(
        reference,
        bijnagte.recordbuf.fixture.interopenum.InteropEnum.parseFrom(record.toByteArray()));
  }
}
