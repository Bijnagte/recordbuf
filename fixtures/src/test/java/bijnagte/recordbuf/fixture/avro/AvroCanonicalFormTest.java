package bijnagte.recordbuf.fixture.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.jackson.avro.DynAvro;
import java.util.HexFormat;
import org.apache.avro.SchemaFormatter;
import org.apache.avro.SchemaNormalization;
import org.junit.jupiter.api.Test;

/**
 * Avro CANONICAL FORM for the generated .avsc schema. Where protobuf's canonical JSON is a
 * canonical form of the MESSAGE, Avro's is one of its SCHEMA: Avro defines canonical forms for
 * schemas only (the Parsing Canonical Form -- the deterministic single-line JSON of {@link
 * SchemaFormatter} "canonical" + the fingerprint it hashes, per the spec's Normalization section);
 * the data encoding has no canonical form. So this test asserts:
 *
 * <ul>
 *   <li>the generated .avsc schema, in Parsing Canonical Form (fully-qualified names, declaration
 *       order, deduplication by name, no defaults/props), equals the committed {@code
 *       golden/avro-schema/dyn-canonical-schema.json} through the golden matrix's normalized JSON
 *       comparison -- the file is jq-formatted for readability, so byte-exactness of the canonical
 *       form itself is pinned by the fingerprint test;
 *   <li>the canonical fingerprint ({@code SHA-256} parsing form) equals the committed {@code
 *       golden/avro-schema/dyn-canonical-fingerprint.txt}.
 * </ul>
 *
 * These are standalone schema checks, not golden cases: a schema is not a record in a format. The
 * deterministic binary encoding of the representative {@code Dyn} record, which this class used to
 * compare against {@code avro/dyn-representative.avro}, is the {@code jackson-dyn} family's Avro
 * binary golden in the golden matrix.
 */
class AvroCanonicalFormTest {

  private static final String SCHEMA_FAMILY = "avro-schema";

  @Test
  void canonicalSchemaMatchesCommittedGolden() {
    String actual = SchemaFormatter.getInstance("canonical").format(DynAvro.SCHEMA$);
    String expected =
        GoldenStore.classpath().loadText(SCHEMA_FAMILY, "dyn-canonical-schema", "json");
    GoldenAssertions.assertJsonMatches(
        expected,
        actual,
        GoldenStore.classpath().path(SCHEMA_FAMILY, "dyn-canonical-schema", "json"));
  }

  @Test
  void canonicalFingerprintMatchesCommittedGolden() throws Exception {
    byte[] digest = SchemaNormalization.parsingFingerprint("SHA-256", DynAvro.SCHEMA$);
    String actual = HexFormat.of().formatHex(digest);
    String path = GoldenStore.classpath().path(SCHEMA_FAMILY, "dyn-canonical-fingerprint", "txt");
    String expected =
        GoldenStore.classpath().loadText(SCHEMA_FAMILY, "dyn-canonical-fingerprint", "txt").trim();
    assertEquals(expected, actual, () -> "fingerprint differs from " + path);
  }
}
