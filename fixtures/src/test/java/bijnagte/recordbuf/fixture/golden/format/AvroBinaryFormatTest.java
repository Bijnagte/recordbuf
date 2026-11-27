package bijnagte.recordbuf.fixture.golden.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests of {@link AvroBinaryFormat} through the golden matrix: the {@code Dyn} representative
 * record passes forward and reverse against a hex golden converted from the former {@code
 * avro/dyn-representative.avro}, trust for Avro's class validator comes from the adapter, and a
 * record without a generated converter is not applicable.
 */
class AvroBinaryFormatTest {

  private static final String FAMILY = "_adapters";

  private static final JsonMapper NODES = new JsonMapper();

  private final AvroBinaryFormat format = new AvroBinaryFormat();

  /**
   * The same value as {@code JacksonDynamicWktCases.dyn(structAny())}: a Value, a Struct doc, a
   * ListValue, an Any packing a Struct, and two scalar Value entries.
   */
  private static Dyn representative() {
    Struct struct =
        Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build()).build();
    return Dyn.builder()
        .name("d")
        .value(NODES.readTree("{\"k\":[1.5,\"x\",null]}"))
        .doc((ObjectNode) NODES.readTree("{\"a\":{\"b\":true}}"))
        .items((ArrayNode) NODES.readTree("[2.25]"))
        .payload(DynamicWkt.any(Any.pack(struct)))
        .values(List.of(NODES.readTree("\"s\""), NODES.readTree("[]")))
        .build();
  }

  /**
   * The bytes of the {@code jackson-dyn/dyn-struct-any} Avro binary golden: the same record as the
   * representative, and the same bytes the retired {@code avro/dyn-representative.avro} held.
   */
  private static byte[] jacksonDynGolden() {
    return GoldenStore.classpath().loadBinary("jackson-dyn", "dyn-struct-any", "avro.hex");
  }

  /** The {@code Dyn} representative, forward and reverse against the committed hex golden. */
  @TestFactory
  Stream<DynamicNode> dynRepresentative_passesForwardAndReverse() {
    GoldenCase dyn =
        GoldenCase.builder(FAMILY, "dyn-representative", representative())
            .lossless(format)
            .build(List.of(format));
    return GoldenMatrix.of(GoldenStore.classpath(), List.of(format)).tests(List.of(dyn));
  }

  /**
   * The hex golden holds exactly the bytes of the {@code jackson-dyn} golden of the same record.
   */
  @Test
  void dynRepresentativeGolden_hasTheBytesOfTheJacksonDynGolden() {
    byte[] hexGolden =
        GoldenStore.classpath().loadBinary(FAMILY, "dyn-representative", format.extension());

    assertArrayEquals(jacksonDynGolden(), hexGolden);
  }

  /** The hex text is in the KTD4 form, so re-encoding its own bytes reproduces it. */
  @Test
  void dynRepresentativeGolden_isCanonicalHex() {
    String hexText =
        GoldenStore.classpath().loadText(FAMILY, "dyn-representative", format.extension());

    assertEquals(GoldenAssertions.encodeHex(jacksonDynGolden()), hexText);
  }

  /** The adapter's bytes are the golden's bytes, and its reverse gives the record back. */
  @Test
  void dynRepresentative_matchesTheGoldenBytesAndRoundTrips() throws Exception {
    Dyn dyn = representative();

    assertArrayEquals(jacksonDynGolden(), format.serialize(dyn));
    assertEquals(dyn, format.deserialize(jacksonDynGolden(), Dyn.class));
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

      byte[] bytes = format.serialize(representative());
      Object roundTripped = format.deserialize(bytes, Dyn.class);

      assertEquals(representative(), roundTripped);
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
    assertEquals(Optional.empty(), format.notApplicableReason(representative()));
  }
}
