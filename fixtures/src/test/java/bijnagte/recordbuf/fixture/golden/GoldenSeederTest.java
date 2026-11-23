package bijnagte.recordbuf.fixture.golden;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.combined.rec.Compat;
import bijnagte.recordbuf.fixture.combined.rec.Sub;
import bijnagte.recordbuf.fixture.golden.format.AvroBinaryFormat;
import bijnagte.recordbuf.fixture.golden.format.AvroJsonFormat;
import bijnagte.recordbuf.fixture.golden.format.JacksonFormat;
import bijnagte.recordbuf.fixture.golden.format.Proto3JsonFormat;
import bijnagte.recordbuf.fixture.golden.format.ProtobufBinaryFormat;
import bijnagte.recordbuf.fixture.i32.rec.Sample;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of {@link GoldenSeeder} against a temporary golden root: an absent golden is created, a
 * present one is left byte-identical and listed as skipped, the protobuf binary and proto3 JSON
 * goldens equal what protobuf-java produces for the equivalent message, and a not-applicable pair
 * writes nothing.
 */
class GoldenSeederTest {

  private static final String FAMILY = "seeded";
  private static final String CASE_NAME = "compat-populated";

  private final ProtobufBinaryFormat protobufBinary = new ProtobufBinaryFormat();
  private final Proto3JsonFormat proto3Json = new Proto3JsonFormat();
  private final JacksonFormat jackson = new JacksonFormat();
  private final AvroJsonFormat avroJson = new AvroJsonFormat();
  private final AvroBinaryFormat avroBinary = new AvroBinaryFormat();
  private final List<GoldenFormat> allFormats =
      List.of(protobufBinary, proto3Json, jackson, avroJson, avroBinary);

  /** A record every format applies to; one map entry keeps the wire order deterministic. */
  private static Compat populated() {
    return Compat.builder()
        .i(42)
        .fx(1_000_000L)
        .s("h\u00e9llo-\u4e16")
        .b(Bytes.of(new byte[] {(byte) 0x7f, (byte) 0x80, -1}))
        .packed(List.of(1, -2, 0, 100))
        .nested(Sub.builder().id(7).label("seven").build())
        .subs(
            List.of(
                Sub.builder().id(1).label("one").build(), Sub.builder().id(2).label("two").build()))
        .m(Map.of("x", 1))
        .presence(7)
        .build();
  }

  /** The same message as {@link #populated()}, built straight from protobuf-java. */
  private static bijnagte.recordbuf.fixture.combined.Compat protobufJavaPopulated() {
    return bijnagte.recordbuf.fixture.combined.Compat.newBuilder()
        .setI(42)
        .setFx(1_000_000L)
        .setS("h\u00e9llo-\u4e16")
        .setB(ByteString.copyFrom(new byte[] {(byte) 0x7f, (byte) 0x80, -1}))
        .addAllPacked(List.of(1, -2, 0, 100))
        .setNested(bijnagte.recordbuf.fixture.combined.Sub.newBuilder().setId(7).setLabel("seven"))
        .addSubs(bijnagte.recordbuf.fixture.combined.Sub.newBuilder().setId(1).setLabel("one"))
        .addSubs(bijnagte.recordbuf.fixture.combined.Sub.newBuilder().setId(2).setLabel("two"))
        .putM("x", 1)
        .setPresence(7)
        .build();
  }

  private GoldenCaseSource source(List<GoldenFormat> formats, GoldenCase... cases) {
    return new GoldenCaseSource() {
      @Override
      public List<GoldenFormat> formats() {
        return formats;
      }

      @Override
      public List<GoldenCase> cases() {
        return List.of(cases);
      }
    };
  }

  private GoldenCaseSource compatSource() {
    GoldenCase.Builder builder = GoldenCase.builder(FAMILY, CASE_NAME, populated());
    allFormats.forEach(builder::lossless);
    return source(allFormats, builder.build(allFormats));
  }

  private static String read(Path root, String file) throws IOException {
    return Files.readString(root.resolve(FAMILY).resolve(file));
  }

  private static String printed(GoldenSeeder.Report report) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    report.print(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    return bytes.toString(StandardCharsets.UTF_8);
  }

  /** Every golden of an applicable case is created, and Jackson alone is flagged for review. */
  @Test
  void absentGoldens_areCreated_andOnlyJacksonIsFlaggedForReview(@TempDir Path root)
      throws Exception {
    GoldenSeeder.Report report = GoldenSeeder.seed(root, List.of(compatSource()));

    assertEquals(
        List.of("pb.hex", "proto3.json", "jackson.json", "avro.json", "avro.hex"),
        report.created().stream().map(GoldenSeeder.Created::extension).toList());
    for (GoldenSeeder.Created created : report.created()) {
      assertTrue(Files.isRegularFile(root.resolve(created.relativePath())), created.relativePath());
      assertEquals("jackson.json".equals(created.extension()), created.needsReview());
    }
    assertEquals(List.of(), report.skippedExisting());
    assertEquals(List.of(), report.notApplicable());
    String text = printed(report);
    assertTrue(text.contains("seeded/compat-populated.jackson.json  REVIEW"), text);
    assertFalse(text.contains("pb.hex  REVIEW"), text);
  }

  /** The seeded goldens are accepted by the matrix, forward and reverse, in all five formats. */
  @Test
  void seededGoldens_passTheMatrixInEveryFormat(@TempDir Path root) throws Exception {
    GoldenCaseSource source = compatSource();
    GoldenSeeder.seed(root, List.of(source));

    List<DynamicNode> nodes =
        GoldenMatrix.of(GoldenStore.inDirectory(root), allFormats).tests(source.cases()).toList();

    assertEquals(10, nodes.size());
    for (DynamicNode node : nodes) {
      assertDoesNotThrow(((DynamicTest) node).getExecutable(), node.getDisplayName());
    }
  }

  /** Present goldens, however odd their content, are neither rewritten nor rewritten on rerun. */
  @Test
  void presentGoldens_stayByteIdentical_andAreListedAsSkipped(@TempDir Path root) throws Exception {
    Path existing = root.resolve(FAMILY).resolve(CASE_NAME + ".pb.hex");
    Files.createDirectories(existing.getParent());
    byte[] frozen = "00 not what protobuf-java writes".getBytes(StandardCharsets.UTF_8);
    Files.write(existing, frozen);

    GoldenSeeder.Report first = GoldenSeeder.seed(root, List.of(compatSource()));

    assertArrayEquals(frozen, Files.readAllBytes(existing));
    assertEquals(List.of(FAMILY + "/" + CASE_NAME + ".pb.hex"), first.skippedExisting());
    assertEquals(4, first.created().size());
    String afterFirst = read(root, CASE_NAME + ".avro.json");

    GoldenSeeder.Report second = GoldenSeeder.seed(root, List.of(compatSource()));

    assertEquals(List.of(), second.created());
    assertEquals(5, second.skippedExisting().size());
    assertEquals(afterFirst, read(root, CASE_NAME + ".avro.json"));
    assertArrayEquals(frozen, Files.readAllBytes(existing));
  }

  /** Writing over an existing golden is refused and leaves the file as it was. */
  @Test
  void writeNew_refusesToOverwriteAnExistingGolden(@TempDir Path root) throws Exception {
    Path golden = root.resolve(FAMILY).resolve("x.pb.hex");
    Files.createDirectories(golden.getParent());
    Files.writeString(golden, "keep\n");

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> GoldenSeeder.writeNew(golden, "replace\n".getBytes(StandardCharsets.UTF_8)));

    assertTrue(refused.getMessage().contains(golden.toString()), refused.getMessage());
    assertEquals("keep\n", Files.readString(golden));
  }

  /** The protobuf binary and proto3 JSON goldens equal protobuf-java's output for the message. */
  @Test
  void protobufGoldens_equalProtobufJavaOutputOfTheEquivalentMessage(@TempDir Path root)
      throws Exception {
    GoldenSeeder.seed(root, List.of(compatSource()));
    var reference = protobufJavaPopulated();

    assertEquals(
        GoldenAssertions.encodeHex(reference.toByteArray()), read(root, CASE_NAME + ".pb.hex"));
    assertEquals(
        JsonFormat.printer().sortingMapKeys().print(reference) + "\n",
        read(root, CASE_NAME + ".proto3.json"));
  }

  /** A record the format rejects is reported, and no file is written for it. */
  @Test
  void notApplicablePair_writesNothing(@TempDir Path root) throws Exception {
    GoldenCase noInterop =
        GoldenCase.builder(FAMILY, "int32", Sample.builder().a(1).build())
            .lossless(protobufBinary)
            .notApplicable(proto3Json, "the message has no Interop")
            .build(List.of(protobufBinary, proto3Json));
    GoldenCaseSource source = source(List.of(protobufBinary, proto3Json), noInterop);

    GoldenSeeder.Report report = GoldenSeeder.seed(root, List.of(source));

    assertEquals(
        List.of(
            new GoldenSeeder.Inapplicable(
                FAMILY, "int32", "proto3.json", "the message has no Interop")),
        report.notApplicable());
    assertFalse(Files.exists(root.resolve(FAMILY).resolve("int32.proto3.json")));
    assertEquals(
        List.of(FAMILY + "/int32.pb.hex"),
        report.created().stream().map(GoldenSeeder.Created::relativePath).toList());
    assertTrue(printed(report).contains("seeded/int32 [proto3.json]: the message has no Interop"));
  }

  /** A format with no seeding source is refused rather than seeded from the wrong place. */
  @Test
  void unknownFormat_isRefused(@TempDir Path root) {
    GoldenFormat.Json unknown =
        new GoldenFormat.Json() {
          @Override
          public String extension() {
            return "unknown.json";
          }

          @Override
          public String serialize(Object record) {
            return "{}";
          }

          @Override
          public Object deserialize(String goldenText, Class<?> recordType) {
            return populated();
          }
        };
    GoldenCase goldenCase =
        GoldenCase.builder(FAMILY, "any", populated()).lossless(unknown).build(List.of(unknown));
    GoldenCaseSource source = source(List.of(unknown), goldenCase);

    assertThrows(IllegalStateException.class, () -> GoldenSeeder.seed(root, List.of(source)));
    assertFalse(Files.exists(root.resolve(FAMILY).resolve("any.unknown.json")));
  }

  /**
   * Every applicable pair of the registered sources already has its committed golden, so a real
   * {@code seedGoldens} run has nothing to create.
   */
  @Test
  void registeredSources_haveEveryApplicableGoldenCommitted() {
    GoldenStore committed = GoldenStore.classpath();
    int checked = 0;
    for (GoldenCaseSource source : GoldenCaseRegistry.sources()) {
      for (GoldenCase goldenCase : source.cases()) {
        for (GoldenFormat format : source.formats()) {
          if (!(goldenCase.outcomeFor(format) instanceof GoldenCase.NotApplicable)) {
            assertTrue(
                committed.exists(goldenCase.family(), goldenCase.name(), format.extension()),
                goldenCase.family() + "/" + goldenCase.name() + "." + format.extension());
            checked++;
          }
        }
      }
    }
    assertTrue(checked > 0);
  }
}
