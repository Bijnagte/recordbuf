package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The {@link DynamicWkt} facade's {@code Any} arms and the Jackson×Avro envelope helpers. */
class DynamicWktTest {

  private static final String ANY = "google.protobuf.Any";

  @Test
  void anyWithAStructuralPayloadRoundTripsThroughTheWire() throws Exception {
    Struct struct =
        Struct.newBuilder()
            .putFields("name", Value.newBuilder().setStringValue("x").build())
            .putFields("n", Value.newBuilder().setNumberValue(2).build())
            .build();
    byte[] wire = Any.pack(struct).toByteArray();

    JsonNode node = DynamicWkt.decode(ANY, wire);
    assertEquals("type.googleapis.com/google.protobuf.Struct", node.get("typeUrl").asString());
    assertEquals("x", node.get("value").get("name").asString());

    assertArrayEquals(wire, DynamicWkt.encode(ANY, node));
  }

  @Test
  void anyWithAnOpaquePayloadRoundTripsViaBase64() throws Exception {
    byte[] wire =
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/com.example.Opaque")
            .setValue(ByteString.copyFrom(new byte[] {8, (byte) 150, 1, 0, -1}))
            .build()
            .toByteArray();

    JsonNode node = DynamicWkt.decode(ANY, wire);
    assertEquals("type.googleapis.com/com.example.Opaque", node.get("typeUrl").asString());

    assertArrayEquals(wire, DynamicWkt.encode(ANY, node));
  }

  @Test
  void anyNodeWithoutATypeUrlFailsLoud() {
    JsonNode node = DynamicWkt.MAPPER.readTree("{\"value\":\"AA==\"}");

    assertThrows(IllegalArgumentException.class, () -> DynamicWkt.encode(ANY, node));
  }

  @Test
  void envelopeContentTypeIsTheWktFqnOrTheAnysTypeUrl() {
    JsonNode any = DynamicWkt.MAPPER.readTree("{\"typeUrl\":\"type.example/x.Y\",\"value\":\"\"}");

    assertEquals("type.example/x.Y", DynamicWkt.contentType(ANY, any));
    assertEquals(
        "google.protobuf.Value",
        DynamicWkt.contentType("google.protobuf.Value", DynamicWkt.MAPPER.readTree("[1,2]")));
  }

  @Test
  void envelopePayloadRoundTripsTheNodeByContent() {
    JsonNode node =
        DynamicWkt.MAPPER.readTree("{\"a\":[1,2.5,\"s\",null,true],\"b\":{\"c\":{}},\"d\":null}");

    JsonPayload payload = DynamicWkt.payload(node);

    assertEquals(node, DynamicWkt.node(payload));
    assertEquals(node, DynamicWkt.node(JsonPayload.of(payload.jsonText())));
  }
}
