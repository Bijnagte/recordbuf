package bijnagte.recordbuf.fixture.avro;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.JsonGolden;
import bijnagte.recordbuf.fixture.jackson.avro.DynAvro;
import bijnagte.recordbuf.fixture.jackson.avro.rec.Dyn;
import bijnagte.recordbuf.fixture.jackson.avro.rec.DynAvroConverter;
import bijnagte.recordbuf.json.DynamicWkt;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.apache.avro.SchemaFormatter;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Avro CANONICAL FORM for the generated .avsc schema and its specific records. Where protobuf's
 * canonical JSON is a canonical form of the MESSAGE, Avro's is one of its SCHEMA: Avro defines
 * canonical forms for schemas only (the Parsing Canonical Form -- the deterministic single-line
 * JSON of {@link SchemaFormatter} "canonical" + the fingerprint it hashes, per the spec's
 * Normalization section); the data encoding has no canonical form. So this test asserts:
 *
 * <ul>
 *   <li>the generated .avsc schema, in Parsing Canonical Form (fully-qualified names, declaration
 *       order, deduplication by name, no defaults/props), equals a committed golden file as a JSON
 *       tree -- the file is jq-formatted for readability, so byte-exactness of the canonical form
 *       itself is pinned by the fingerprint test;
 *   <li>the canonical fingerprint ({@code SHA-256} parsing form) equals a committed golden;
 *   <li>the deterministic binary encoding of one representative record with WKT fields equals a
 *       committed golden file -- the "committed golden" parity AvroInteropTest left as a follow-on
 *       once a representative value with WKT fields was available.
 * </ul>
 */
class AvroCanonicalFormTest {

  private static final JsonMapper M = new JsonMapper();

  @BeforeAll
  static void trustTheAvroClasses() {
    var b = ClassSecurityValidator.builder();
    for (String name : List.of("DynAvro", "RecordbufDynamicWkt", "RecordbufUnknownField")) {
      b.add("bijnagte.recordbuf.fixture.jackson.avro." + name);
    }
    ClassSecurityValidator.setGlobal(b.build());
  }

  /**
   * The representative record with WKT fields: the same value JacksonDynamicWktTest feeds its
   * envelope tests (a Value, a Struct doc, a ListValue, an Any packing a Struct, and two scalar
   * Value entries) -- every WKT conversion path exercised.
   */
  private static Dyn representative() {
    Struct struct =
        Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build()).build();
    return Dyn.builder()
        .name("d")
        .value(M.readTree("{\"k\":[1.5,\"x\",null]}"))
        .doc((ObjectNode) M.readTree("{\"a\":{\"b\":true}}"))
        .items((ArrayNode) M.readTree("[2.25]"))
        .payload(DynamicWkt.any(Any.pack(struct)))
        .values(List.of(M.readTree("\"s\""), M.readTree("[]")))
        .build();
  }

  private static byte[] resource(String path) throws IOException {
    try (InputStream in = AvroCanonicalFormTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing golden resource " + path);
      }
      return in.readAllBytes();
    }
  }

  @Test
  void canonicalSchemaMatchesCommittedGolden() throws Exception {
    String actual = SchemaFormatter.getInstance("canonical").format(DynAvro.SCHEMA$);
    JsonGolden.assertMatches("avro/dyn-canonical-schema.json", actual);
  }

  @Test
  void canonicalFingerprintMatchesCommittedGolden() throws Exception {
    byte[] digest = SchemaNormalization.parsingFingerprint("SHA-256", DynAvro.SCHEMA$);
    String actual = HexFormat.of().formatHex(digest);
    String expected =
        new String(resource("/avro/dyn-canonical-fingerprint.txt"), StandardCharsets.UTF_8).trim();
    assertEquals(
        expected, actual, () -> "fingerprint differs from /avro/dyn-canonical-fingerprint.txt");
  }

  @Test
  void committedBinaryEncodingParity() throws Exception {
    DynAvro avro = DynAvroConverter.fromRecord(representative());

    byte[] golden = resource("/avro/dyn-representative.avro");

    ByteBuffer encoded = avro.toByteBuffer();
    byte[] actual = new byte[encoded.remaining()];
    encoded.get(actual);
    assertArrayEquals(golden, actual, () -> "bytes differ from /avro/dyn-representative.avro");

    // The committed bytes are VALID encoding: they decode back to the same specific record.
    assertEquals(avro, DynAvro.fromByteBuffer(ByteBuffer.wrap(golden)));
  }
}
