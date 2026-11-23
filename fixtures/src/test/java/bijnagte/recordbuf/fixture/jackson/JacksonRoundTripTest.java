package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.json.JsonFieldMaskFilter;
import bijnagte.recordbuf.runtime.FieldMask;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AE3/AE4 (2026-11-21-004 U10): generated records round-trip through a plain {@link JsonMapper} (no
 * module registration -- the per-field annotations carry the contract), render the proto3 JSON
 * shapes, and retain unknown properties through JSON (and, folded into the unknown-field carrier,
 * through the wire).
 *
 * <p>The golden renderings these tests used to compare against are cases of the {@code
 * jackson-roundtrip} family (see {@link JacksonRoundTripCases}), run by {@link
 * #roundTripCases_matchTheirGoldensForwardAndReverse}; what stays here is what no golden case can
 * express: the plain mapper's round trip, retained properties and the wire, and the field-mask
 * projection, a one-way view.
 */
class JacksonRoundTripTest {

  private static final JsonMapper M = new JsonMapper();

  private static final JacksonRoundTripCases SOURCE = new JacksonRoundTripCases();

  static Full full() {
    return JacksonRoundTripCases.full();
  }

  /**
   * ISO temporals, the canonical knob's {seconds, nanos}, base64 bytes (singular + repeated), the
   * dynamic WKT nodes verbatim, the nested message, and the proto3 camelCase names; the retained
   * unknown property; the bytes and well-known samples; and absent values omitted, not written as
   * null.
   */
  @TestFactory
  Stream<DynamicNode> roundTripCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  @Test
  void fullRoundTripsThroughThePlainMapper() {
    Full full = full();

    assertEquals(full, M.readValue(M.writeValueAsString(full), Full.class));
  }

  @Test
  void unknownPropertiesSurviveJsonButAreNeverWrittenToTheWire() throws Exception {
    ObjectNode json = (ObjectNode) M.readTree(M.writeValueAsString(full()));
    JsonNode future = M.readTree("{\"x\":[1,\"two\"]}");
    json.set("futureField", future);

    Full back = M.readValue(M.writeValueAsString(json), Full.class);
    assertEquals(future, back.jsonExtras().get("futureField"), "retained on read");
    assertEquals(JacksonRoundTripCases.fullWithExtras(), back);

    // The extras are JSON-only: the wire carries exactly what the record without them would, so a
    // property name can never occupy (or collide with) a field number.
    byte[] wire = back.toByteArray();
    assertArrayEquals(full().toByteArray(), wire, "no trace of the extras on the wire");
    Full decoded = Full.fromBytes(wire);
    assertTrue(decoded.unknownFields().isEmpty());
    assertTrue(decoded.jsonExtras().isEmpty());
  }

  @Test
  void mainBytesFixtureRoundTrips() throws Exception {
    var sample = JacksonRoundTripCases.bytesSample();

    assertEquals(
        sample,
        M.readValue(
            M.writeValueAsString(sample), bijnagte.recordbuf.fixture.bytes.rec.Sample.class));
  }

  @Test
  void mainWellKnownFixtureRoundTrips() throws Exception {
    var sample = JacksonRoundTripCases.wellKnownSample();

    // FieldMask: an array of camelCase paths; Empty: {}; BytesValue: base64.
    assertEquals(
        sample,
        M.readValue(
            M.writeValueAsString(sample), bijnagte.recordbuf.fixture.wellknown.rec.Sample.class));
  }

  /** Absent values are read back as absent. */
  @Test
  void absentValuesAreOmitted() throws Exception {
    var sample = JacksonRoundTripCases.wellKnownSparse();

    assertEquals(
        sample,
        M.readValue(
            M.writeValueAsString(sample), bijnagte.recordbuf.fixture.wellknown.rec.Sample.class));
  }

  /** A field mask projects a record's JSON; the projection reads back as a partial record. */
  @Test
  void aFieldMaskProjectsTheRecordJson() throws Exception {
    JsonNode projected =
        JsonFieldMaskFilter.filter(
            M.valueToTree(full()), FieldMask.of("name", "inner.label", "canonical_span"));

    GoldenAssertions.assertJsonMatches(
        GoldenStore.classpath()
            .loadText(JacksonRoundTripCases.FAMILY, "full-masked", "jackson.json"),
        M.writeValueAsString(projected),
        "jackson-roundtrip/full-masked [jackson.json]");
    Full partial = M.treeToValue(projected, Full.class);
    assertEquals("in", partial.inner() == null ? null : partial.inner().label());
    assertEquals(full().canonicalSpan(), partial.canonicalSpan());
    assertEquals(0L, partial.count(), "an unmasked field reads back as its default");
  }
}
