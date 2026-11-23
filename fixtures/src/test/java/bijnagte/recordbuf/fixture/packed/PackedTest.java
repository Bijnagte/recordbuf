package bijnagte.recordbuf.fixture.packed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.packed.rec.Sample;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code packed} golden family (see {@link PackedCases}): every case is checked forward and
 * reverse in each format it applies to, and reported as skipped, with the reason, in each it does
 * not. The protobuf binary format carries the byte comparison against protobuf-java and the decode
 * of protobuf-java's bytes that this class used to assert by hand.
 *
 * <p>One structural test stays outside the matrix: a protobuf parser must accept a packed field
 * written unpacked (one tag per element), and that wire form is decode input only. It cannot be a
 * golden, because the protobuf binary golden is protobuf-java's own canonical (packed) encoding,
 * and the protobuf binary adapter's oracle check would reject it. That test is disabled while gap
 * {@code packed-decode-unpacked-input} is open: the generated decoder reads the packed form only.
 */
class PackedTest {

  private static final PackedCases SOURCE = new PackedCases();

  @TestFactory
  Stream<DynamicNode> packedCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  /**
   * An {@code int32} list written unpacked, one varint tag per element, decodes to the same record
   * as protobuf-java's parse of the same bytes.
   */
  @Disabled("gap packed-decode-unpacked-input: the generated decoder reads the packed form only")
  @Test
  void unpackedInput_decodesIntoThePackedField() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    writeUnpackedInt32(out, List.of(1, 2));
    out.flush();
    byte[] unpacked = bytes.toByteArray();

    Sample decoded = Sample.fromBytes(unpacked);

    assertEquals(
        List.of(1, 2),
        bijnagte.recordbuf.fixture.packed.Sample.parseFrom(unpacked).getAList(),
        "the reference parser reads the unpacked form");
    assertEquals(Sample.builder().a(List.of(1, 2)).build(), decoded);
  }

  private static void writeUnpackedInt32(CodedOutputStream out, List<Integer> values)
      throws IOException {
    for (int value : values) {
      out.writeInt32(1, value);
    }
  }
}
