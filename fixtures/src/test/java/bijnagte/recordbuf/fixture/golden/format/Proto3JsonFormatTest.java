package bijnagte.recordbuf.fixture.golden.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.combined.rec.Compat;
import bijnagte.recordbuf.fixture.combined.rec.Sub;
import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.i32.rec.Sample;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests of {@link Proto3JsonFormat} through the golden matrix: the committed golden under {@code
 * golden/_adapters/} passes forward and reverse, equals what protobuf-java prints for the same
 * message built without the Interop converter, and records without an Interop are not applicable.
 */
class Proto3JsonFormatTest {

  private static final String FAMILY = "_adapters";
  private static final String CASE_NAME = "compat-populated";

  private final Proto3JsonFormat format = new Proto3JsonFormat();

  private static Compat populated() {
    return Compat.builder()
        .i(42)
        .fx(1_000_000L)
        .s("h\u00e9llo-\u4e16-\ud83c\udf89")
        .b(Bytes.of(new byte[] {(byte) 0x7f, (byte) 0x80, -1}))
        .packed(List.of(1, -2, 0, 100))
        .nested(Sub.builder().id(7).label("seven").build())
        .subs(
            List.of(
                Sub.builder().id(1).label("one").build(), Sub.builder().id(2).label("two").build()))
        .m(Map.of("x", 1, "z", -2))
        .presence(7)
        .build();
  }

  /** The same message as {@link #populated()}, built straight from protobuf-java. */
  private static bijnagte.recordbuf.fixture.combined.Compat protobufJavaPopulated() {
    return bijnagte.recordbuf.fixture.combined.Compat.newBuilder()
        .setI(42)
        .setFx(1_000_000L)
        .setS("h\u00e9llo-\u4e16-\ud83c\udf89")
        .setB(ByteString.copyFrom(new byte[] {(byte) 0x7f, (byte) 0x80, -1}))
        .addAllPacked(List.of(1, -2, 0, 100))
        .setNested(bijnagte.recordbuf.fixture.combined.Sub.newBuilder().setId(7).setLabel("seven"))
        .addSubs(bijnagte.recordbuf.fixture.combined.Sub.newBuilder().setId(1).setLabel("one"))
        .addSubs(bijnagte.recordbuf.fixture.combined.Sub.newBuilder().setId(2).setLabel("two"))
        .putM("x", 1)
        .putM("z", -2)
        .setPresence(7)
        .build();
  }

  /** The committed golden: forward and reverse both pass. */
  @TestFactory
  Stream<DynamicNode> committedGolden_passesForwardAndReverse() {
    GoldenCase compat =
        GoldenCase.builder(FAMILY, CASE_NAME, populated()).lossless(format).build(List.of(format));
    return GoldenMatrix.of(GoldenStore.classpath(), List.of(format)).tests(List.of(compat));
  }

  /** The committed golden is what protobuf-java itself prints, with no Interop involved. */
  @Test
  void committedGolden_equalsProtobufJavaPrinterOutputOfTheDirectlyBuiltMessage() throws Exception {
    String printed = JsonFormat.printer().sortingMapKeys().print(protobufJavaPopulated());

    GoldenAssertions.assertJsonMatches(
        GoldenStore.classpath().loadText(FAMILY, CASE_NAME, "proto3.json"),
        printed,
        FAMILY + "/" + CASE_NAME + " [proto3.json] vs the directly built protobuf-java message");
  }

  @Test
  void sixtyFourBitFields_renderAsJsonStrings() throws Exception {
    String serialized = format.serialize(populated());
    String golden = GoldenStore.classpath().loadText(FAMILY, CASE_NAME, "proto3.json");

    for (String text : List.of(serialized, golden)) {
      JsonNode root = new JsonMapper().readTree(text);
      assertTrue(root.get("fx").isString(), "fixed64 must be a JSON string: " + text);
      assertEquals("1000000", root.get("fx").asString());
      assertTrue(root.get("i").isNumber(), "int32 stays a JSON number: " + text);
    }
  }

  @Test
  void deserialize_malformedJson_surfacesTheParserException() {
    assertThrows(
        InvalidProtocolBufferException.class,
        () -> format.deserialize("{\"fx\": \"not a number\"}", Compat.class));
  }

  @Test
  void recordWithoutInterop_reportsNotApplicable() {
    Sample int32 = Sample.builder().a(1).build();

    assertTrue(
        format.notApplicableReason(int32).orElseThrow().contains("no generated Interop"),
        "the reason names the missing Interop");

    GoldenCase goldenCase =
        GoldenCase.builder(FAMILY, "int32", int32)
            .notApplicable(format, "no Interop for the int-family protos")
            .build(List.of(format));
    List<DynamicTest> tests =
        GoldenMatrix.of(GoldenStore.classpath(), List.<GoldenFormat>of(format))
            .tests(List.of(goldenCase))
            .map(DynamicTest.class::cast)
            .toList();

    assertEquals(1, tests.size());
    assertThrows(TestAbortedException.class, tests.get(0).getExecutable());
  }

  @Test
  void recordWithoutInterop_declaredApplicable_failsConstruction() {
    GoldenCase.Builder builder = GoldenCase.builder(FAMILY, "int32", Sample.builder().build());
    builder.lossless(format);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> builder.build(List.of(format)));

    assertTrue(error.getMessage().contains("not applicable"), error.getMessage());
  }

  @Test
  void recordWithoutInterop_cannotBeSerializedOrDeserialized() {
    assertThrows(IllegalArgumentException.class, () -> format.serialize(Sample.builder().build()));
    assertThrows(IllegalArgumentException.class, () -> format.deserialize("{}", Sample.class));
  }

  @Test
  void recordWithInterop_isApplicable() {
    assertEquals(Optional.empty(), format.notApplicableReason(populated()));
  }
}
