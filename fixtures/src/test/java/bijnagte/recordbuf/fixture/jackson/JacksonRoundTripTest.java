package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.JsonGolden;
import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.fixture.jackson.full.rec.Inner;
import bijnagte.recordbuf.json.JsonFieldMaskFilter;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AE3/AE4 (2026-11-21-004 U10): generated records round-trip through a plain {@link JsonMapper} (no
 * module registration -- the per-field annotations carry the contract), render the proto3 JSON
 * shapes, and retain unknown properties through JSON (and, folded into the unknown-field carrier,
 * through the wire).
 */
class JacksonRoundTripTest {

  private static final JsonMapper M = new JsonMapper();

  private static final Instant AT = Instant.ofEpochSecond(1_763_614_896L, 789_000_000);

  static Full full() {
    return Full.builder()
        .name("n")
        .count(1_234_567_890_123L)
        .flag(true)
        .ratio(0.25)
        .blob(Bytes.of(new byte[] {1, 2, 3, (byte) 0xff}))
        .nickname("nick")
        .inner(Inner.builder().label("in").build())
        .value(M.readTree("{\"k\":[1.5,\"x\",null,true]}"))
        .doc((ObjectNode) M.readTree("{\"a\":{\"b\":\"c\"}}"))
        .items((ArrayNode) M.readTree("[2.25,\"y\"]"))
        .values(List.of(M.readTree("\"s\""), M.readTree("{\"z\":false}")))
        .at(AT)
        .span(Duration.ofSeconds(3661, 500_000_000))
        .canonicalSpan(Duration.ofSeconds(90, 5))
        .chunks(List.of(Bytes.of(new byte[] {0}), Bytes.empty()))
        .build();
  }

  /**
   * ISO temporals, the canonical knob's {seconds, nanos}, base64 bytes (singular + repeated), the
   * dynamic WKT nodes verbatim, the nested message, and the proto3 camelCase names.
   */
  @Test
  void fullRendersTheProto3ContractShapes() throws Exception {
    JsonGolden.assertMatches("jackson/full.json", M.writeValueAsString(full()));
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
    JsonGolden.assertMatches("jackson/full-with-extras.json", M.writeValueAsString(back));

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
    var sample =
        bijnagte.recordbuf.fixture.bytes.rec.Sample.builder()
            .a(Bytes.of(new byte[] {(byte) 0xc3, 0x28}))
            .b(Bytes.empty())
            .build();

    String json = M.writeValueAsString(sample);

    JsonGolden.assertMatches("jackson/bytes-sample.json", json);
    assertEquals(sample, M.readValue(json, bijnagte.recordbuf.fixture.bytes.rec.Sample.class));
  }

  @Test
  void mainWellKnownFixtureRoundTrips() throws Exception {
    var sample =
        bijnagte.recordbuf.fixture.wellknown.rec.Sample.builder()
            .at(AT)
            .span(Duration.ofMillis(-1500))
            .mask(FieldMask.of("user.display_name", "id"))
            .i32(7)
            .i64(-8L)
            .dbl(1.5)
            .flt(2.5f)
            .flag(false)
            .text("t")
            .blob(Bytes.of(new byte[] {9}))
            .empty(Empty.INSTANCE)
            .whens(List.of(Instant.EPOCH, AT))
            .build();

    String json = M.writeValueAsString(sample);

    // FieldMask: an array of camelCase paths; Empty: {}; BytesValue: base64.
    JsonGolden.assertMatches("jackson/wellknown-sample.json", json);
    assertEquals(sample, M.readValue(json, bijnagte.recordbuf.fixture.wellknown.rec.Sample.class));
  }

  /** Absent values are omitted, not written as null, and read back as absent. */
  @Test
  void absentValuesAreOmitted() throws Exception {
    var sample = bijnagte.recordbuf.fixture.wellknown.rec.Sample.builder().at(AT).build();

    String json = M.writeValueAsString(sample);

    JsonGolden.assertMatches("jackson/wellknown-sparse.json", json);
    assertEquals(sample, M.readValue(json, bijnagte.recordbuf.fixture.wellknown.rec.Sample.class));
  }

  /** A field mask projects a record's JSON; the projection reads back as a partial record. */
  @Test
  void aFieldMaskProjectsTheRecordJson() throws Exception {
    JsonNode projected =
        JsonFieldMaskFilter.filter(
            M.valueToTree(full()), FieldMask.of("name", "inner.label", "canonical_span"));

    JsonGolden.assertMatches("jackson/full-masked.json", M.writeValueAsString(projected));
    Full partial = M.treeToValue(projected, Full.class);
    assertEquals("in", partial.inner() == null ? null : partial.inner().label());
    assertEquals(full().canonicalSpan(), partial.canonicalSpan());
    assertEquals(0L, partial.count(), "an unmasked field reads back as its default");
  }
}
