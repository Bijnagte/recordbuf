package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.fixture.jackson.full.rec.Inner;
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

  @Test
  void fullRendersTheProto3ContractShapes() {
    JsonNode tree = M.readTree(M.writeValueAsString(full()));

    assertEquals(AT.toString(), tree.get("at").asString(), "Timestamp: ISO string");
    assertEquals("PT1H1M1.5S", tree.get("span").asString(), "Duration: ISO string");
    assertEquals("90", tree.get("canonicalSpan").get("seconds").asString(), "canonical knob");
    assertEquals(5, tree.get("canonicalSpan").get("nanos").asInt(), "canonical knob");
    assertEquals("AQID/w==", tree.get("blob").asString(), "bytes: base64");
    assertEquals(M.readTree("[\"AA==\",\"\"]"), tree.get("chunks"), "repeated bytes: base64");
    assertEquals(M.readTree("{\"a\":{\"b\":\"c\"}}"), tree.get("doc"), "Struct: the node verbatim");
    assertEquals("in", tree.get("inner").get("label").asString());
  }

  @Test
  void fullRoundTripsThroughThePlainMapper() {
    Full full = full();

    assertEquals(full, M.readValue(M.writeValueAsString(full), Full.class));
  }

  @Test
  void unknownPropertiesSurviveJsonAndTheWire() throws Exception {
    ObjectNode json = (ObjectNode) M.readTree(M.writeValueAsString(full()));
    JsonNode future = M.readTree("{\"x\":[1,\"two\"]}");
    json.set("futureField", future);

    Full back = M.readValue(M.writeValueAsString(json), Full.class);
    assertEquals(future, back.jsonExtras().get("futureField"), "retained on read");
    assertEquals(future, M.readTree(M.writeValueAsString(back)).get("futureField"), "re-emitted");

    // On the wire the property folds into the binary unknown-field carrier (keyed by a hash of its
    // name), so a decode keeps it as an unknown field and re-emits it byte-for-byte.
    byte[] wire = back.toByteArray();
    Full decoded = Full.fromBytes(wire);
    assertFalse(decoded.unknownFields().isEmpty(), "folded into the carrier");
    assertArrayEquals(wire, decoded.toByteArray(), "re-emitted unchanged");
  }

  @Test
  void mainBytesFixtureRoundTrips() {
    var sample =
        bijnagte.recordbuf.fixture.bytes.rec.Sample.builder()
            .a(Bytes.of(new byte[] {(byte) 0xc3, 0x28}))
            .b(Bytes.empty())
            .build();

    String json = M.writeValueAsString(sample);

    assertEquals("{\"a\":\"wyg=\",\"b\":\"\"}", json);
    assertEquals(sample, M.readValue(json, bijnagte.recordbuf.fixture.bytes.rec.Sample.class));
  }

  @Test
  void mainWellKnownFixtureRoundTrips() {
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
    JsonNode tree = M.readTree(json);

    assertEquals("id,user.displayName", tree.get("mask").asString(), "FieldMask: path string");
    assertEquals(M.createObjectNode(), tree.get("empty"), "Empty: {}");
    assertEquals("CQ==", tree.get("blob").asString(), "BytesValue: base64");
    assertEquals(sample, M.readValue(json, bijnagte.recordbuf.fixture.wellknown.rec.Sample.class));
  }
}
