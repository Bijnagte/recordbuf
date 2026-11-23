package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.JsonGolden;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.jackson.avro.DynAvro;
import bijnagte.recordbuf.fixture.jackson.avro.rec.Dyn;
import bijnagte.recordbuf.fixture.jackson.avro.rec.DynAvroConverter;
import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AE4a-c (2026-11-21-004 U10): node-typed dynamic WKT fields round-trip as nodes through the mapper
 * and the codec; an {@code Any} keeps its payload (structural or opaque base64); and on a Jackson x
 * Avro file the U9 envelope carries the content type and survives record -> Avro binary -> record
 * with every node intact. The same test data also serializes through the Avro bridge to canonical
 * Avro JSON (avro's official {@link JsonEncoder}); see the {@code /json/jackson/avro} goldens.
 */
class JacksonDynamicWktTest {

  private static final JsonMapper M = new JsonMapper();

  private static final JacksonDynamicWktCases SOURCE = new JacksonDynamicWktCases();

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

  private static JsonNode structAny() {
    return JacksonDynamicWktCases.structAny();
  }

  private static JsonNode opaqueAny() {
    return JacksonDynamicWktCases.opaqueAny();
  }

  private static Dyn dyn(JsonNode payload) {
    return JacksonDynamicWktCases.dyn(payload);
  }

  /**
   * The nodes and the {@code Any} render as JSON (the {@code jackson-dyn} family's Jackson
   * goldens), and the same records have Avro binary and canonical Avro JSON goldens there too.
   */
  @TestFactory
  Stream<DynamicNode> dynCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  @Test
  void dynSerializesToCanonicalAvroJsonThroughTheConverter() throws Exception {
    JsonGolden.assertMatches(
        "jackson/avro/dyn-struct-any.json", canonicalAvroJson(dyn(structAny())));
    JsonGolden.assertMatches(
        "jackson/avro/dyn-opaque-any.json", canonicalAvroJson(dyn(opaqueAny())));
  }

  /** Avro's official JSON encoding of one specific record: compact, members in schema order. */
  private static String canonicalAvroJson(Dyn record) throws Exception {
    DynAvro avro = DynAvroConverter.fromRecord(record);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JsonEncoder encoder = EncoderFactory.get().jsonEncoder(DynAvro.SCHEMA$, out, false);
    new SpecificDatumWriter<>(DynAvro.SCHEMA$).write(avro, encoder);
    encoder.flush();
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
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
