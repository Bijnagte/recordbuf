package bijnagte.recordbuf.fixture.corpus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code corpus} golden family over {@code TestAllTypesProto3} (see {@link
 * TestAllTypesCorpusCases}): every case is checked forward and reverse in each format it applies
 * to, and reported as skipped, with the reason, in each it does not.
 */
class TestAllTypesCorpusTest {

  private static final GoldenStore STORE = GoldenStore.classpath();

  private final TestAllTypesCorpusCases source = new TestAllTypesCorpusCases();

  @TestFactory
  Stream<DynamicNode> corpusCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(STORE, source.formats()).tests(source.cases());
  }

  /**
   * The populated record's protobuf binary golden is exactly what protobuf-java writes: the
   * reference message parsed from the golden carries the record's values, and re-serializes to the
   * golden's bytes.
   */
  @Test
  void populatedProtobufBinaryGolden_equalsProtobufJavaBytes() throws IOException {
    byte[] golden =
        STORE.loadBinary(
            TestAllTypesCorpusCases.FAMILY, TestAllTypesCorpusCases.POPULATED, "pb.hex");
    TestAllTypesProto3 record = TestAllTypesCorpusCases.populated();

    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 reference =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(golden);

    assertArrayEquals(golden, reference.toByteArray());
    assertArrayEquals(golden, record.toByteArray());
    assertEquals(record.optionalInt32(), reference.getOptionalInt32());
    assertEquals(record.optionalUint64(), reference.getOptionalUint64());
    assertEquals(record.optionalString(), reference.getOptionalString());
    assertEquals(record.repeatedSint64(), reference.getRepeatedSint64List());
    assertEquals(record.packedInt32(), reference.getPackedInt32List());
    assertEquals(record.mapStringString(), reference.getMapStringStringMap());
    assertEquals(record.mapUint32Uint32(), reference.getMapUint32Uint32Map());
    assertEquals(
        record.recursiveMessage().optionalString(),
        reference.getRecursiveMessage().getOptionalString());
    assertEquals(record.repeatedDuration().size(), reference.getRepeatedDurationCount());
    assertEquals(record.repeatedAny().size(), reference.getRepeatedAnyCount());
  }

  /** The default record has nothing to write: protobuf-java and the record agree on no bytes. */
  @Test
  void defaultRecord_serializesToNoBytes() throws IOException {
    byte[] golden = STORE.loadBinary(TestAllTypesCorpusCases.FAMILY, "all-types-default", "pb.hex");

    assertEquals(0, golden.length);
    assertEquals(0, TestAllTypesProto3.builder().build().toByteArray().length);
    assertEquals(TestAllTypesProto3.builder().build(), TestAllTypesProto3.fromBytes(golden));
  }
}
