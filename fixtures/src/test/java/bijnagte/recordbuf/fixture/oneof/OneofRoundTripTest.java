package bijnagte.recordbuf.fixture.oneof;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.oneof.rec.Compat;
import bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.ItemValue;
import bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue;
import bijnagte.recordbuf.fixture.oneof.rec.Sub;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code oneof} golden family (see {@link OneofCases}): every case is checked forward and
 * reverse in each format it applies to, and reported as skipped, with the reason, in each it does
 * not. The protobuf binary format carries the byte comparison against protobuf-java and the decode
 * of protobuf-java's bytes that this class used to assert by hand, and the proto3 JSON format
 * carries the generated {@code Interop} converter in both directions.
 *
 * <p>Two kinds of check stay structural. The unwrap tests read the member value through the {@code
 * choice()} accessor without naming the member type, which has no golden shape. The converter byte
 * test asserts that {@code Interop.to} serializes to exactly the record codec's bytes: the proto3
 * JSON golden fixes the converter's message by value, not the bytes it serializes to.
 *
 * <p>The record types are imported, so the protobuf-java reference types that share their simple
 * names ({@code Compat}, {@code Sub}) are not used here.
 */
class OneofRoundTripTest {

  private static final OneofCases SOURCE = new OneofCases();

  @TestFactory
  Stream<DynamicNode> oneofCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  @Test
  void unwrapableValueForScalar() {
    Compat record = Compat.builder().choice(new NameValue("hello")).build();

    assertEquals("hello", record.choice().value());
  }

  @Test
  void unwrapableValueForMessageMember() {
    Sub sub = Sub.builder().text("seven").build();
    Compat record = Compat.builder().choice(new ItemValue(sub)).build();

    assertEquals(sub, record.choice().value());
  }

  /**
   * For every case, the reference the converter builds serializes byte-identically to the record's
   * own codec.
   */
  @Test
  void interopToSerializesToTheRecordCodecBytes() throws IOException {
    for (GoldenCase goldenCase : SOURCE.cases()) {
      Compat record = (Compat) goldenCase.record();

      assertArrayEquals(
          record.toByteArray(),
          Compat.Interop.to(record).toByteArray(),
          "Interop.to must serialize like the record codec for " + goldenCase.name());
    }
  }
}
