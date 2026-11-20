package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * U3 (2026-11-20 Jackson plan): the :recordbuf-json helper surface — the structural WKT ↔ node
 * conversions, the JsonPayload value type, and the temporal (de)serializers — exercised through a
 * real ObjectMapper, the same path the generated code takes.
 */
class JsonModuleTest {

  private static final ObjectMapper M = DynamicWkt.MAPPER;

  // --- structural WKT -> node -> WKT round-trips ---

  @Test
  void structRoundTripsThroughObjectNode() throws Exception {
    Struct s =
        Struct.newBuilder()
            .putFields("name", Value.newBuilder().setStringValue("dana").build())
            .putFields("age", Value.newBuilder().setNumberValue(31.5).build())
            .build();
    String json = M.writeValueAsString(DynamicWkt.struct(s));
    JsonNode back = M.readTree(json);
    Struct s2 = DynamicWkt.structFrom((ObjectNode) back);
    assertEquals(s, s2);
  }

  @Test
  void valueKindsRoundTrip() throws Exception {
    Value original =
        Value.newBuilder()
            .setStructValue(
                Struct.newBuilder()
                    .putFields("a", Value.newBuilder().setBoolValue(true).build())
                    .putFields("b", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                    .putFields(
                        "c",
                        Value.newBuilder()
                            .setListValue(
                                ListValue.newBuilder()
                                    .addValues(Value.newBuilder().setNumberValue(1).build())
                                    .addValues(Value.newBuilder().setNumberValue(2.25).build())
                                    .build())
                            .build())
                    .build())
            .build();
    String json = M.writeValueAsString(DynamicWkt.value(original));
    JsonNode node = M.readTree(json);
    assertEquals(original, DynamicWkt.valueFrom(node));
  }

  @Test
  void anyStructuralRoundTrips() throws Exception {
    Struct payload =
        Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build()).build();
    Any any =
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/google.protobuf.Struct")
            .setValue(payload.toByteString())
            .build();
    String json = M.writeValueAsString(DynamicWkt.any(any));
    JsonNode node = M.readTree(json);
    assertEquals("type.googleapis.com/google.protobuf.Struct", node.get("typeUrl").textValue());
    Any back = DynamicWkt.anyFrom(node.get("typeUrl").textValue(), node.get("value"));
    assertEquals(any, back);
  }

  @Test
  void anyOpaquePayloadSurvivesAsBase64() throws Exception {
    // a non-structural type: the payload is base64 text in the JSON mapping, lossless.
    byte[] opaque = {1, 2, 3, 4, 5};
    Any any =
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/com.example.Widget")
            .setValue(ByteString.copyFrom(opaque))
            .build();
    String json = M.writeValueAsString(DynamicWkt.any(any));
    JsonNode node = M.readTree(json);
    assertTrue(node.get("value").isTextual());
    Any back = DynamicWkt.anyFrom(node.get("typeUrl").textValue(), node.get("value"));
    assertEquals(any, back);
  }

  // --- JsonPayload ---

  @Test
  void jsonPayloadSerializesAsJsonString() throws Exception {
    JsonPayload p = JsonPayload.of("{\"x\":1}");
    assertEquals("{\"x\":1}", p.jsonText());
    String json = M.writeValueAsString(p);
    assertEquals("\"{\\\"x\\\":1}\"", json);
    JsonPayload back = M.readValue(json, JsonPayload.class);
    assertEquals(p, back);
  }

  // --- temporal (de)serializers ---

  private static ObjectMapper isoMapper() {
    SimpleModule m = new SimpleModule();
    m.addSerializer(new JsonTemporal.TimestampIsoSerializer());
    m.addSerializer(new JsonTemporal.DurationIsoSerializer());
    m.addDeserializer(Timestamp.class, new JsonTemporal.TimestampIsoDeserializer());
    m.addDeserializer(Duration.class, new JsonTemporal.DurationIsoDeserializer());
    ObjectMapper om = new ObjectMapper();
    om.registerModule(m);
    return om;
  }

  @Test
  void timestampIsoRoundTrips() throws Exception {
    Timestamp t = Timestamp.newBuilder().setSeconds(1_763_614_896L).setNanos(789_012_345).build();
    String rendered = isoMapper().writeValueAsString(t);
    assertEquals("\"2025-11-20T05:01:36.789012345Z\"", rendered);
    assertEquals(t, isoMapper().readValue(rendered, Timestamp.class));
  }

  @Test
  void timestampCanonicalRoundTripsThroughObject() throws Exception {
    Timestamp t = Timestamp.newBuilder().setSeconds(1_763_614_896L).setNanos(42).build();
    SimpleModule m = new SimpleModule();
    m.addSerializer(new JsonTemporal.TimestampCanonicalSerializer());
    m.addDeserializer(Timestamp.class, new JsonTemporal.TimestampCanonicalDeserializer());
    ObjectMapper om = new ObjectMapper();
    om.registerModule(m);
    String json = om.writeValueAsString(t);
    // the documented proto3 canonical shape: seconds as an int64-as-string, nanos as a number.
    JsonNode node = M.readTree(json);
    assertEquals("1763614896", node.get("seconds").textValue());
    assertEquals(42, node.get("nanos").intValue());
    assertEquals(t, om.readValue(json, Timestamp.class));
  }

  @Test
  void isoReaderAcceptsCanonicalAndViceVersa() throws Exception {
    // the readers accept BOTH renderings regardless of the writer format selected:
    SimpleModule m = new SimpleModule();
    m.addSerializer(new JsonTemporal.TimestampCanonicalSerializer());
    m.addSerializer(new JsonTemporal.DurationIsoSerializer());
    m.addDeserializer(Timestamp.class, new JsonTemporal.TimestampIsoDeserializer());
    m.addDeserializer(Duration.class, new JsonTemporal.DurationCanonicalDeserializer());
    ObjectMapper om = new ObjectMapper();
    om.registerModule(m);
    assertEquals(
        Timestamp.newBuilder().setSeconds(1_763_614_896L).setNanos(42).build(),
        om.readValue("{\"seconds\":\"1763614896\",\"nanos\":42}", Timestamp.class));
    // the canonical writer emits the canonical object even for a plain timestamp:
    Timestamp t = Timestamp.newBuilder().setSeconds(1_763_614_896L).setNanos(789_012_345).build();
    assertTrue(om.writeValueAsString(t).startsWith("{"));
  }

  @Test
  void durationIsoRoundTrips() throws Exception {
    Duration d = Duration.newBuilder().setSeconds(3661L).setNanos(1_000).build();
    String rendered = isoMapper().writeValueAsString(d);
    assertEquals(d, isoMapper().readValue(rendered, Duration.class));
  }

  // --- U5: the wire dispatch the generated codec arms call (node <-> WKT bytes) ---

  @Test
  void valueNodeRoundTripsThroughWktBytes() throws Exception {
    Value v =
        Value.newBuilder()
            .setStructValue(
                Struct.newBuilder()
                    .putFields("k", Value.newBuilder().setStringValue("v").build())
                    .build())
            .build();
    JsonNode node = DynamicWkt.value(v);
    byte[] bytes = DynamicWkt.encode("google.protobuf.Value", node);
    Value back = Value.parseFrom(bytes);
    assertEquals(v, back, "encode/decode must be the identity through the WKT wire form");
  }

  @Test
  void decodeReadsAValueFieldBackAsANode() throws Exception {
    Value v = Value.newBuilder().setNumberValue(2.5).build();
    JsonNode node = DynamicWkt.decode("google.protobuf.Value", v.toByteArray());
    assertEquals(v, DynamicWkt.valueFrom(node));
  }

  @Test
  void structAndListValueDispatchByFqn() throws Exception {
    Struct s =
        Struct.newBuilder().putFields("a", Value.newBuilder().setBoolValue(true).build()).build();
    assertSameBytes(
        "google.protobuf.Struct",
        DynamicWkt.encode("google.protobuf.Struct", DynamicWkt.struct(s)),
        s.toByteArray());
    ListValue l =
        ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("x").build()).build();
    assertSameBytes(
        "google.protobuf.ListValue",
        DynamicWkt.encode("google.protobuf.ListValue", DynamicWkt.list(l)),
        l.toByteArray());
  }

  private static void assertSameBytes(String label, byte[] a, byte[] b) {
    assertEquals(Arrays.toString(b), Arrays.toString(a), label + " wire bytes must agree");
  }
}
