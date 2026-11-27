package bijnagte.recordbuf.fixture.golden.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.jackson.avro.DynAvro;
import bijnagte.recordbuf.fixture.jackson.avro.rec.Dyn;
import bijnagte.recordbuf.fixture.mapvalues.rec.MapValues;
import bijnagte.recordbuf.json.DynamicWkt;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests of {@link AvroJsonFormat} through the golden matrix: both {@code Dyn} payload variants pass
 * forward and reverse against goldens that are byte copies of the former {@code json/jackson/avro}
 * goldens, trust for Avro's class validator comes from the adapter, and a record without a
 * generated converter is not applicable.
 */
class AvroJsonFormatTest {

  private static final String FAMILY = "_adapters";

  private static final JsonMapper NODES = new JsonMapper();

  private final AvroJsonFormat format = new AvroJsonFormat();

  /** An Any packing a Struct, as {@code JacksonDynamicWktTest.structAny()}. */
  private static JsonNode structAny() {
    Struct struct =
        Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build()).build();
    return DynamicWkt.any(Any.pack(struct));
  }

  /** An Any packing an unknown type, as {@code JacksonDynamicWktTest.opaqueAny()}. */
  private static JsonNode opaqueAny() {
    return DynamicWkt.any(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/com.example.Opaque")
            .setValue(ByteString.copyFrom(new byte[] {8, (byte) 150, 1}))
            .build());
  }

  /** The same value as {@code JacksonDynamicWktTest.dyn(JsonNode)}. */
  private static Dyn dyn(JsonNode payload) {
    return Dyn.builder()
        .name("d")
        .value(NODES.readTree("{\"k\":[1.5,\"x\",null]}"))
        .doc((ObjectNode) NODES.readTree("{\"a\":{\"b\":true}}"))
        .items((ArrayNode) NODES.readTree("[2.25]"))
        .payload(payload)
        .values(List.of(NODES.readTree("\"s\""), NODES.readTree("[]")))
        .build();
  }

  /** Both {@code Dyn} payload variants, forward and reverse against the committed goldens. */
  @TestFactory
  Stream<DynamicNode> dynPayloadVariants_passForwardAndReverse() {
    GoldenCase structAnyCase =
        GoldenCase.builder(FAMILY, "dyn-struct-any", dyn(structAny()))
            .lossless(format)
            .build(List.of(format));
    GoldenCase opaqueAnyCase =
        GoldenCase.builder(FAMILY, "dyn-opaque-any", dyn(opaqueAny()))
            .lossless(format)
            .build(List.of(format));
    return GoldenMatrix.of(GoldenStore.classpath(), List.of(format))
        .tests(List.of(structAnyCase, opaqueAnyCase));
  }

  /**
   * The adapter goldens hold the same document as the {@code jackson-dyn} family's Avro JSON
   * goldens (the ones that replaced the old {@code json/jackson/avro} files): same tree, same
   * member order, formatted differently.
   */
  @Test
  void dynGoldens_areTheSameDocumentAsTheJacksonDynGoldens() {
    GoldenStore store = GoldenStore.classpath();
    for (String name : List.of("dyn-struct-any", "dyn-opaque-any")) {
      GoldenAssertions.assertJsonMatches(
          store.loadText("jackson-dyn", name, format.extension()),
          store.loadText(FAMILY, name, format.extension()),
          name);
    }
  }

  /**
   * Avro's {@code JsonDecoder} accepts the pretty-printed golden as it is, and the adapter's own
   * compact output decodes to the same record.
   */
  @Test
  void dyn_decodesPrettyAndCompactText() throws Exception {
    for (JsonNode payload : List.of(structAny(), opaqueAny())) {
      Dyn dyn = dyn(payload);
      String compact = format.serialize(dyn);
      String pretty =
          NODES.writerWithDefaultPrettyPrinter().writeValueAsString(NODES.readTree(compact));

      assertTrue(pretty.contains("\n"), "the text is multi-line");
      assertEquals(dyn, format.deserialize(compact, Dyn.class));
      assertEquals(dyn, format.deserialize(pretty, Dyn.class));
    }
  }

  /** The adapter writes the compact canonical encoding: no whitespace outside string values. */
  @Test
  void serialize_isCompactAndInSchemaOrder() throws Exception {
    String json = format.serialize(dyn(structAny()));

    assertTrue(json.startsWith("{\"name\":\"d\",\"value\":{"), json);
    assertTrue(!json.contains("\n"), json);
  }

  /**
   * Trust lives in the adapter: with a global validator that trusts nothing the generated classes
   * need, both directions still work, and the validator that was installed stays in place.
   */
  @Test
  void trust_isEnsuredByTheAdapterAndLeavesTheInstalledValidatorInPlace() throws Exception {
    var previous = ClassSecurityValidator.getGlobal();
    try {
      var installed = ClassSecurityValidator.builder().add(String.class).build();
      ClassSecurityValidator.setGlobal(installed);
      assertThrows(SecurityException.class, () -> ClassSecurityValidator.validate(DynAvro.class));

      String json = format.serialize(dyn(structAny()));
      Object roundTripped = format.deserialize(json, Dyn.class);

      assertEquals(dyn(structAny()), roundTripped);
      ClassSecurityValidator.validate(DynAvro.class);
      assertTrue(
          ClassSecurityValidator.getGlobal().isTrusted(String.class),
          "the validator the test installed still trusts what it trusted");
    } finally {
      ClassSecurityValidator.setGlobal(previous);
    }
  }

  /** A message of a proto generated without Avro has no converter, so the format is skipped. */
  @Test
  void recordWithoutConverter_reportsNotApplicable() {
    MapValues noConverter = MapValues.builder().build();

    String reason = format.notApplicableReason(noConverter).orElseThrow();

    assertTrue(reason.contains("MapValuesAvroConverter"), reason);
    assertTrue(reason.contains(MapValues.class.getName()), reason);

    GoldenCase goldenCase =
        GoldenCase.builder(FAMILY, "no-converter", noConverter)
            .notApplicable(format, "no Avro converter")
            .build(List.of(format));
    List<DynamicTest> tests =
        GoldenMatrix.of(GoldenStore.classpath(), List.<GoldenFormat>of(format))
            .tests(List.of(goldenCase))
            .map(DynamicTest.class::cast)
            .toList();

    assertEquals(1, tests.size());
    assertThrows(TestAbortedException.class, tests.get(0).getExecutable());
  }

  @Test
  void recordWithoutConverter_declaredApplicable_failsConstruction() {
    GoldenCase.Builder builder =
        GoldenCase.builder(FAMILY, "no-converter", MapValues.builder().build());
    builder.lossless(format);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> builder.build(List.of(format)));

    assertTrue(error.getMessage().contains("not applicable"), error.getMessage());
  }

  @Test
  void recordWithConverter_isApplicable() {
    assertEquals(Optional.empty(), format.notApplicableReason(dyn(structAny())));
  }
}
