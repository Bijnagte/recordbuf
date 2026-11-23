package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.JsonGolden;
import bijnagte.recordbuf.fixture.jackson.avro.DynAvro;
import bijnagte.recordbuf.fixture.jackson.avro.rec.Dyn;
import bijnagte.recordbuf.fixture.jackson.avro.rec.DynAvroConverter;
import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.json.DynamicWkt;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.nio.ByteBuffer;
import java.util.List;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AE4a-c (2026-11-21-004 U10): node-typed dynamic WKT fields round-trip as nodes through the mapper
 * and the codec; an {@code Any} keeps its payload (structural or opaque base64); and on a Jackson x
 * Avro file the U9 envelope carries the content type and survives record -> Avro binary -> record
 * with every node intact.
 */
class JacksonDynamicWktTest {

  private static final JsonMapper M = new JsonMapper();

  private static final String STRUCT_URL = "type.googleapis.com/google.protobuf.Struct";

  @BeforeAll
  static void trustTheAvroClasses() {
    // The global validator is replaced per test class (see AvroInteropTest); name ours.
    var b = ClassSecurityValidator.builder();
    for (String name : List.of("DynAvro", "RecordbufDynamicWkt", "RecordbufUnknownField")) {
      b.add("bijnagte.recordbuf.fixture.jackson.avro." + name);
    }
    ClassSecurityValidator.setGlobal(b.build());
  }

  /** An Any packing a Struct: rendered with its value as the Struct's node. */
  private static JsonNode structAny() {
    Struct struct =
        Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build()).build();
    return DynamicWkt.any(Any.pack(struct));
  }

  /** An Any packing an unknown type: rendered with its value as the payload's base64. */
  private static JsonNode opaqueAny() {
    return DynamicWkt.any(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/com.example.Opaque")
            .setValue(ByteString.copyFrom(new byte[] {8, (byte) 150, 1}))
            .build());
  }

  private static Dyn dyn(JsonNode payload) {
    return Dyn.builder()
        .name("d")
        .value(M.readTree("{\"k\":[1.5,\"x\",null]}"))
        .doc((ObjectNode) M.readTree("{\"a\":{\"b\":true}}"))
        .items((ArrayNode) M.readTree("[2.25]"))
        .payload(payload)
        .values(List.of(M.readTree("\"s\""), M.readTree("[]")))
        .build();
  }

  @Test
  void dynRendersItsNodesAndAnyAsJson() throws Exception {
    JsonGolden.assertMatches("jackson/dyn-struct-any.json", M.writeValueAsString(dyn(structAny())));
    JsonGolden.assertMatches("jackson/dyn-opaque-any.json", M.writeValueAsString(dyn(opaqueAny())));
  }

  @Test
  void dynamicFieldsRoundTripAsNodesThroughTheMapperAndTheCodec() throws Exception {
    Full full = JacksonRoundTripTest.full();

    assertEquals(full, M.readValue(M.writeValueAsString(full), Full.class));
    // Value numbers are doubles on the wire; the fixture's nodes use decimals so they compare.
    assertEquals(full, Full.fromBytes(full.toByteArray()));
  }

  @Test
  void anyKeepsItsTypeUrlAndPayloadThroughTheMapperAndTheCodec() throws Exception {
    assertEquals(STRUCT_URL, structAny().get("typeUrl").asString());
    assertEquals("CJYB", opaqueAny().get("value").asString(), "opaque payload: base64");
    for (JsonNode payload : List.of(structAny(), opaqueAny())) {
      Dyn dyn = dyn(payload);

      assertEquals(dyn, M.readValue(M.writeValueAsString(dyn), Dyn.class));
      assertEquals(dyn, Dyn.fromBytes(dyn.toByteArray()));
    }
  }

  @Test
  void envelopeCarriesFormatAndContentType() {
    DynAvro avro = DynAvroConverter.fromRecord(dyn(structAny()));

    assertEquals("json", avro.getValue().getFormat().toString());
    assertEquals("google.protobuf.Value", avro.getValue().getContentType().toString());
    assertEquals("google.protobuf.Struct", avro.getDoc().getContentType().toString());
    assertEquals("google.protobuf.ListValue", avro.getItems().getContentType().toString());
    assertEquals(STRUCT_URL, avro.getPayload().getContentType().toString(), "Any: its typeUrl");
    assertEquals(
        M.readTree("{\"a\":{\"b\":true}}"),
        M.readTree(avro.getDoc().getPayload().toString()),
        "payload: the node's JSON");
  }

  @Test
  void envelopeSurvivesAvroBinaryWithNodesIntact() throws Exception {
    for (JsonNode payload : List.of(structAny(), opaqueAny())) {
      Dyn dyn = dyn(payload);

      ByteBuffer binary = DynAvroConverter.fromRecord(dyn).toByteBuffer();

      assertEquals(dyn, DynAvroConverter.toRecord(DynAvro.fromByteBuffer(binary)));
    }
  }
}
