package bijnagte.recordbuf.fixture.avro;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.runtime.Bytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U6 (KTD11 partial): the fixtures interop oracle for the v1 Avro bridge.
 *
 * <p>Proves the three properties that lock the bridge:
 *
 * <ol>
 *   <li><b>Record↔SpecificRecord round-trip</b> — {@code record → SampleAvro → record == original}
 *       for every supported scalar family (int, string, bytes), covering the converter's per-field
 *       rules: {@code .toString()} for string fields, {@code ByteBuffer ↔ byte[]} for bytes fields,
 *       direct passthrough for int fields, and {@code UnknownFields.empty()} on the record side.
 *   <li><b>Avro binary round-trip</b> — {@code SampleAvro.toByteBuffer → SampleAvro.fromByteBuffer}
 *       returns an equal record, proving the specific record produced by {@code SpecificCompiler}
 *       is a working Avro type.
 *   <li><b>Record is Avro-free</b> — a generated record (the recordbuf {@link
 *       bijnagte.recordbuf.fixture.i32.rec.Sample}) has no {@code org.apache.avro} import in its
 *       source, confirming R22 (the record stays free of Avro on its classpath).
 * </ol>
 *
 * <p>These are the "orange" tests of U6: a green round-trip + a green avro-free source check proves
 * the bridge works and the record's boundary is not violated. The committed-bytes parity (KTD11's
 * "committed golden") is a follow-on when a representative value with a WKT field is available.
 */
class AvroInteropTest {

  // Avro 1.11+ ClassSecurityValidator blocks binary (de)serialization of generated
  // specific record classes unless they are explicitly trusted. jvmArgs in build.gradle
  // also sets org.apache.avro.SERIALIZABLE_PACKAGES=bijnagte.recordbuf.fixture for
  // belt-and-suspenders coverage.
  @BeforeAll
  static void setupAvroSecurity() {
    // Trust the specific records AND their nested carriers (RecordbufUnknownField /
    // RecordbufOneofValue)
    // for Avro binary (de)serialization. The ClassSecurityValidator.Builder can only name concrete
    // classes, so the carriers must be listed alongside their owning records; each lives in the
    // same
    // package. Listing by name avoids importing every generated type into this test.
    var b = ClassSecurityValidator.builder();
    for (String pkg : List.of("i32", "string", "bytes")) {
      b.add("bijnagte.recordbuf.fixture." + pkg + ".SampleAvro");
      b.add("bijnagte.recordbuf.fixture." + pkg + ".RecordbufUnknownField");
    }
    ClassSecurityValidator.setGlobal(b.build());
  }

  // ── i32 round-trip ────────────────────────────────────────────────────────

  @Test
  void i32_roundTripsThroughAvro() {
    bijnagte.recordbuf.fixture.i32.rec.Sample original =
        bijnagte.recordbuf.fixture.i32.rec.Sample.builder().a(42).build();

    bijnagte.recordbuf.fixture.i32.SampleAvro s =
        bijnagte.recordbuf.fixture.i32.rec.SampleAvroConverter.fromRecord(original);
    assertEquals(42, s.getA());

    bijnagte.recordbuf.fixture.i32.rec.Sample back =
        bijnagte.recordbuf.fixture.i32.rec.SampleAvroConverter.toRecord(s);
    assertEquals(original, back, "i32 record must survive a round trip through SampleAvro");
  }

  @Test
  void i32_zeroValueRoundTripsThroughAvro() {
    bijnagte.recordbuf.fixture.i32.rec.Sample original =
        bijnagte.recordbuf.fixture.i32.rec.Sample.builder().build(); // a = 0

    bijnagte.recordbuf.fixture.i32.SampleAvro s =
        bijnagte.recordbuf.fixture.i32.rec.SampleAvroConverter.fromRecord(original);
    assertEquals(0, s.getA());

    bijnagte.recordbuf.fixture.i32.rec.Sample back =
        bijnagte.recordbuf.fixture.i32.rec.SampleAvroConverter.toRecord(s);
    assertEquals(original, back);
  }

  // ── string round-trip ──────────────────────────────────────────────────────

  @Test
  void string_roundTripsThroughAvro() {
    bijnagte.recordbuf.fixture.string.rec.Sample original =
        bijnagte.recordbuf.fixture.string.rec.Sample.builder().a("héllo").b("world").c("").build();

    bijnagte.recordbuf.fixture.string.SampleAvro s =
        bijnagte.recordbuf.fixture.string.rec.SampleAvroConverter.fromRecord(original);
    assertEquals("héllo", s.getA().toString());
    assertEquals("world", s.getB().toString());
    assertEquals("", s.getC().toString());

    bijnagte.recordbuf.fixture.string.rec.Sample back =
        bijnagte.recordbuf.fixture.string.rec.SampleAvroConverter.toRecord(s);
    assertEquals(original, back, "string record must survive a round trip through SampleAvro");
  }

  // ── bytes round-trip ───────────────────────────────────────────────────────

  @Test
  void bytes_roundTripsThroughAvro() {
    byte[] a = new byte[] {0x01, 0x02, 0x7f};
    byte[] b = new byte[] {(byte) 0x80, (byte) 0x81, -1};

    bijnagte.recordbuf.fixture.bytes.rec.Sample original =
        bijnagte.recordbuf.fixture.bytes.rec.Sample.builder().a(Bytes.of(a)).b(Bytes.of(b)).build();

    bijnagte.recordbuf.fixture.bytes.SampleAvro s =
        bijnagte.recordbuf.fixture.bytes.rec.SampleAvroConverter.fromRecord(original);
    // SampleAvro stores bytes as ByteBuffer; content must be preserved.
    assertArrayEquals(a, s.getA().array(), "field a bytes must survive fromRecord");
    assertArrayEquals(b, s.getB().array(), "field b bytes must survive fromRecord");

    bijnagte.recordbuf.fixture.bytes.rec.Sample back =
        bijnagte.recordbuf.fixture.bytes.rec.SampleAvroConverter.toRecord(s);
    assertEquals(original, back, "bytes record must survive a round trip through SampleAvro");
  }

  @Test
  void bytes_emptyFieldsRoundTripThroughAvro() {
    bijnagte.recordbuf.fixture.bytes.rec.Sample original =
        bijnagte.recordbuf.fixture.bytes.rec.Sample.builder().build();

    bijnagte.recordbuf.fixture.bytes.SampleAvro s =
        bijnagte.recordbuf.fixture.bytes.rec.SampleAvroConverter.fromRecord(original);
    // fromRecord wraps byte[0] → ByteBuffer with size 0.
    assertTrue(s.getA() != null && s.getA().remaining() == 0, "empty byte[] field must round-trip");

    bijnagte.recordbuf.fixture.bytes.rec.Sample back =
        bijnagte.recordbuf.fixture.bytes.rec.SampleAvroConverter.toRecord(s);
    assertEquals(original, back);
  }

  // ── Avro binary round-trip (proves SpecificCompiler product is a working Avro type) ───

  @Test
  void i32_binaryAvroRoundTrip() throws IOException {
    bijnagte.recordbuf.fixture.i32.SampleAvro original =
        bijnagte.recordbuf.fixture.i32.SampleAvro.newBuilder().setA(12345).build();

    // toByteBuffer()/fromByteBuffer() use BinaryMessageEncoder/Decoder;
    // @BeforeAll's addTrustedClassesFromCurrentPackages() permits the generated class.
    ByteBuffer encoded = original.toByteBuffer();
    bijnagte.recordbuf.fixture.i32.SampleAvro decoded =
        bijnagte.recordbuf.fixture.i32.SampleAvro.fromByteBuffer(encoded);
    assertEquals(original, decoded, "i32 SampleAvro must round-trip through Avro binary encoding");
    assertEquals(12345, decoded.getA());
  }

  // ── Record is Avro-free (R22) ─────────────────────────────────────────────

  @Test
  void i32_recordSourceHasNoAvroImport() throws IOException {
    // The generated record source lives in the build's generated main-source dir;
    // find it relative to the test module's working directory.
    Path sourceFile =
        Path.of(
            "build",
            "generated",
            "sources",
            "proto",
            "main",
            "recordbuf",
            "bijnagte",
            "recordbuf",
            "fixture",
            "i32",
            "rec",
            "Sample.java");
    // The module's Gradle working dir is fixtures/; try both the module-relative path
    // (the common Gradle test working dir) and the repo-root path as a fallback.
    if (!Files.exists(sourceFile)) {
      sourceFile =
          Path.of(
              "fixtures",
              "build",
              "generated",
              "sources",
              "proto",
              "main",
              "recordbuf",
              "bijnagte",
              "recordbuf",
              "fixture",
              "i32",
              "rec",
              "Sample.java");
    }
    String source = Files.readString(sourceFile);
    assertTrue(
        !source.contains("org.apache.avro"),
        "A recordbuf-generated record must not import org.apache.avro (R22); found it in "
            + sourceFile);
    // The record SHOULD import the runtime (it IS a protobuf codec user).
    assertTrue(source.contains("bijnagte.recordbuf.runtime"), "Record should still import runtime");
  }
}
