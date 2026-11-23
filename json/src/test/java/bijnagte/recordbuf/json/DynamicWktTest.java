package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.List;
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

  @Test
  void anUnparseableStructPayloadRoundTripsAsBase64() throws Exception {
    byte[] wire =
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/google.protobuf.Struct")
            .setValue(ByteString.copyFrom(new byte[] {(byte) 0xff}))
            .build()
            .toByteArray();

    JsonNode node = DynamicWkt.decode(ANY, wire);

    assertEquals("/w==", node.get("value").asString(), "kept as the payload's base64");
    assertArrayEquals(wire, DynamicWkt.encode(ANY, node));
  }

  @Test
  void aNullValuePayloadAndAnEmptyOneAreDistinct() throws Exception {
    String valueUrl = "type.googleapis.com/google.protobuf.Value";
    byte[] explicitNull =
        Any.newBuilder()
            .setTypeUrl(valueUrl)
            .setValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build().toByteString())
            .build()
            .toByteArray();
    byte[] noKind = Any.newBuilder().setTypeUrl(valueUrl).build().toByteArray();

    JsonNode nullNode = DynamicWkt.decode(ANY, explicitNull);
    JsonNode emptyNode = DynamicWkt.decode(ANY, noKind);

    assertEquals(true, nullNode.get("value").isNull(), "explicit null_value");
    assertEquals(false, emptyNode.has("value"), "no kind set: no value member");
    assertArrayEquals(explicitNull, DynamicWkt.encode(ANY, nullNode));
    assertArrayEquals(noKind, DynamicWkt.encode(ANY, emptyNode));
  }

  @Test
  void malformedAnyValuesFailWithAnIllegalArgumentException() {
    String struct = "type.googleapis.com/google.protobuf.Struct";
    String opaque = "type.example/x.Y";
    for (String json :
        List.of(
            "{\"typeUrl\":\"" + struct + "\",\"value\":[1]}", // wrong shape
            "{\"typeUrl\":\"type.googleapis.com/google.protobuf.ListValue\",\"value\":{}}",
            "{\"typeUrl\":\"" + opaque + "\",\"value\":{\"a\":1}}", // container, not base64
            "{\"typeUrl\":\"" + opaque + "\",\"value\":\"***\"}")) { // not base64
      JsonNode node = DynamicWkt.MAPPER.readTree(json);

      assertThrows(IllegalArgumentException.class, () -> DynamicWkt.encode(ANY, node), json);
    }
  }
}
