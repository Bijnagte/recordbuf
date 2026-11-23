package bijnagte.recordbuf.fixture.golden.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.i32.rec.Sample;
import bijnagte.recordbuf.fixture.jackson.off.rec.Off;
import bijnagte.recordbuf.fixture.message.rec.Item;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/**
 * Tests of {@link ProtobufBinaryFormat} through the golden matrix: committed goldens under {@code
 * golden/_adapters/} pass forward and reverse (reverse includes the protobuf-java oracle), and bad
 * goldens written to a temp directory fail where expected.
 */
class ProtobufBinaryFormatTest {

  private static final String FAMILY = "_adapters";

  private final ProtobufBinaryFormat format = new ProtobufBinaryFormat();

  private static Sample int32Min() {
    return Sample.builder().a(0x8000_0000).build();
  }

  private static bijnagte.recordbuf.fixture.message.rec.Sample nestedMessage() {
    return bijnagte.recordbuf.fixture.message.rec.Sample.builder()
        .single(Item.builder().id(7).name("ab").build())
        .items(List.of(Item.builder().id(1).name("x").build()))
        .build();
  }

  /** The committed goldens: every generated test passes (forward, then reverse with oracle). */
  @TestFactory
  Stream<DynamicNode> committedGoldens_passForwardAndReverse() {
    GoldenCase int32 =
        GoldenCase.builder(FAMILY, "int32-min", int32Min()).lossless(format).build(List.of(format));
    GoldenCase nested =
        GoldenCase.builder(FAMILY, "message-nested", nestedMessage())
            .lossless(format)
            .build(List.of(format));
    return GoldenMatrix.of(GoldenStore.classpath(), List.of(format)).tests(List.of(int32, nested));
  }

  /** The committed hex is what protobuf-java itself writes for the equivalent message. */
  @Test
  void committedGoldens_equalProtobufJavaToByteArray() {
    byte[] int32 =
        bijnagte.recordbuf.fixture.i32.Sample.newBuilder().setA(0x8000_0000).build().toByteArray();
    byte[] nested =
        bijnagte.recordbuf.fixture.message.Sample.newBuilder()
            .setSingle(bijnagte.recordbuf.fixture.message.Item.newBuilder().setId(7).setName("ab"))
            .addItems(bijnagte.recordbuf.fixture.message.Item.newBuilder().setId(1).setName("x"))
            .build()
            .toByteArray();

    GoldenStore store = GoldenStore.classpath();
    assertArrayEquals(int32, store.loadBinary(FAMILY, "int32-min", "pb.hex"));
    assertArrayEquals(nested, store.loadBinary(FAMILY, "message-nested", "pb.hex"));
  }

  @Test
  void goldenWithOneFlippedByte_failsForward(@TempDir Path goldenDir) throws IOException {
    byte[] flipped = int32Bytes();
    flipped[flipped.length - 1] ^= 0x01;
    Path golden = goldenDir.resolve(FAMILY).resolve("int32-min.pb.hex");
    Files.createDirectories(golden.getParent());
    Files.writeString(golden, GoldenAssertions.encodeHex(flipped), StandardCharsets.UTF_8);
    GoldenCase goldenCase =
        GoldenCase.builder(FAMILY, "int32-min", int32Min()).lossless(format).build(List.of(format));

    List<DynamicTest> tests = run(goldenDir, goldenCase);

    DynamicTest forward = find(tests, FAMILY + "/int32-min [pb.hex] forward");
    AssertionFailedError error = assertThrows(AssertionFailedError.class, forward.getExecutable());
    assertTrue(error.getMessage().contains("offset 10"), error.getMessage());
  }

  /**
   * A non-minimal varint is valid wire format that recordbuf decodes, but protobuf-java rewrites it
   * minimally, so the oracle check rejects the golden. The oracle runs before the record decode.
   */
  @Test
  void nonCanonicalGolden_failsTheProtobufJavaOracleEvenThoughRecordbufDecodesIt()
      throws Exception {
    byte[] overLongVarint = {0x08, (byte) 0x81, 0x00};
    assertEquals(Sample.builder().a(1).build(), Sample.fromBytes(overLongVarint));

    AssertionFailedError error =
        assertThrows(
            AssertionFailedError.class, () -> format.deserialize(overLongVarint, Sample.class));

    assertTrue(error.getMessage().contains("protobuf-java oracle"), error.getMessage());
  }

  @Test
  void recordWithoutReferenceClass_reportsNotApplicable() {
    Off off = Off.builder().name("n").build();

    assertTrue(format.notApplicableReason(off).orElseThrow().contains("Off"));

    GoldenCase goldenCase =
        GoldenCase.builder(FAMILY, "off", off)
            .notApplicable(format, "no protobuf-java reference for the jackson test protos")
            .build(List.of(format));
    List<DynamicTest> tests = run(Path.of("unused"), goldenCase);

    assertEquals(1, tests.size());
    assertThrows(TestAbortedException.class, tests.get(0).getExecutable());
  }

  @Test
  void recordWithoutReferenceClass_declaredApplicable_failsConstruction() {
    GoldenCase.Builder builder = GoldenCase.builder(FAMILY, "off", Off.builder().build());
    builder.lossless(format);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> builder.build(List.of(format)));

    assertTrue(error.getMessage().contains("not applicable"), error.getMessage());
  }

  @Test
  void recordWithReferenceClass_isApplicable() {
    assertEquals(Optional.empty(), format.notApplicableReason(int32Min()));
  }

  @Test
  void serialize_isTheRecordCodecOutput() throws Exception {
    assertArrayEquals(int32Bytes(), format.serialize(int32Min()));
  }

  @Test
  void serialize_rejectsANonRecordbufObject() {
    assertThrows(IllegalArgumentException.class, () -> format.serialize("not a record"));
  }

  @Test
  void deserialize_malformedBytes_surfaceTheCodecException() {
    byte[] truncatedVarint = {0x08, (byte) 0x80};

    assertThrows(
        InvalidProtocolBufferException.class,
        () -> format.deserialize(truncatedVarint, Sample.class));
  }

  @Test
  void deserialize_returnsTheDecodedRecord() throws Exception {
    assertEquals(int32Min(), format.deserialize(int32Bytes(), Sample.class));
  }

  private static byte[] int32Bytes() {
    return bijnagte.recordbuf.fixture.i32.Sample.newBuilder()
        .setA(0x8000_0000)
        .build()
        .toByteArray();
  }

  private List<DynamicTest> run(Path goldenDir, GoldenCase goldenCase) {
    return GoldenMatrix.of(GoldenStore.inDirectory(goldenDir), List.<GoldenFormat>of(format))
        .tests(List.of(goldenCase))
        .map(DynamicTest.class::cast)
        .toList();
  }

  private static DynamicTest find(List<DynamicTest> tests, String displayName) {
    return tests.stream()
        .filter(test -> test.getDisplayName().equals(displayName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no test " + displayName + " in " + tests));
  }
}
