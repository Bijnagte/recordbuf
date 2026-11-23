package bijnagte.recordbuf.fixture.golden.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.combined.rec.Compat;
import bijnagte.recordbuf.fixture.golden.GoldenAssertions;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.jackson.JacksonRoundTripCases;
import bijnagte.recordbuf.fixture.jackson.full.rec.Full;
import bijnagte.recordbuf.fixture.jackson.off.rec.Off;
import bijnagte.recordbuf.runtime.UnknownFields;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests of {@link JacksonFormat} through the golden matrix: the {@code Full} record passes forward
 * and reverse against a golden anchored to the reviewed {@code jackson-roundtrip/full} golden; a
 * record carrying a {@code @JsonIgnore} component (the wire {@code unknownFields} carrier)
 * reverse-matches its declared expected-after-trip record and not the built one; and a jackson-off
 * record is not applicable.
 *
 * <p>No fixture proto reads {@code (recordbuf.json_ignored)} yet (the knob is reserved), so the
 * lossy case uses the {@code @JsonIgnore}d carrier, which is the same by-design loss through the
 * adapter.
 */
class JacksonFormatTest {

  private static final String FAMILY = "_adapters";

  private final JacksonFormat format = new JacksonFormat();

  private static Full full() {
    return JacksonRoundTripCases.full();
  }

  private static Full fullWithUnknownWireField() throws Exception {
    byte[] wire = full().toByteArray();
    byte[] withUnknown = Arrays.copyOf(wire, wire.length + 3);
    // field 99, varint 5: a field the Full schema does not model
    withUnknown[wire.length] = (byte) 0x98;
    withUnknown[wire.length + 1] = 0x06;
    withUnknown[wire.length + 2] = 0x05;
    return Full.fromBytes(withUnknown);
  }

  /** The {@code Full} record, forward and reverse against the committed golden. */
  @TestFactory
  Stream<DynamicNode> full_passesForwardAndReverse() {
    GoldenCase full =
        GoldenCase.builder(FAMILY, "full", full()).lossless(format).build(List.of(format));
    return GoldenMatrix.of(GoldenStore.classpath(), List.of(format)).tests(List.of(full));
  }

  /** The committed golden is the already-reviewed {@code jackson-roundtrip/full} golden. */
  @Test
  void fullGolden_isAnchoredToTheReviewedJacksonGolden() throws IOException {
    JsonMapper mapper = new JsonMapper();
    GoldenStore store = GoldenStore.classpath();
    String adapterGolden = store.loadText(FAMILY, "full", "jackson.json");
    String reviewed = store.loadText(JacksonRoundTripCases.FAMILY, "full", "jackson.json");

    assertEquals(mapper.readTree(reviewed), mapper.readTree(adapterGolden));
  }

  /**
   * AE2 at the adapter level: the {@code @JsonIgnore}d carrier is lost, so the golden (identical to
   * the one for {@code full}) deserializes to the declared record with the carrier cleared.
   */
  @TestFactory
  Stream<DynamicNode> ignoredComponent_reverseMatchesTheDeclaredRecord() throws Exception {
    GoldenCase lossy =
        GoldenCase.builder(FAMILY, "full-unknown-field", fullWithUnknownWireField())
            .expectedAfterTrip(format, full())
            .build(List.of(format));
    return GoldenMatrix.of(GoldenStore.classpath(), List.of(format)).tests(List.of(lossy));
  }

  /** The declared record really differs from the built one, and the golden yields the declared. */
  @Test
  void ignoredComponent_goldenDeserializesToTheDeclaredRecordNotTheBuiltOne() throws Exception {
    Full built = fullWithUnknownWireField();
    Full expected = full();

    assertFalse(built.unknownFields().isEmpty(), "the built record carries an unknown field");
    assertEquals(UnknownFields.empty(), expected.unknownFields());
    assertNotEquals(built, expected, "the declared record differs from the built record");
    Full reversed =
        (Full)
            format.deserialize(
                GoldenStore.classpath().loadText(FAMILY, "full-unknown-field", "jackson.json"),
                Full.class);
    assertEquals(expected, reversed);
    assertNotEquals(built, reversed);
    assertFalse(format.serialize(built).contains("unknown"), "the carrier never reaches the JSON");
  }

  /**
   * Map members come out in key order whatever the map's iteration order, so goldens are stable.
   */
  @Test
  void mapMembers_serializeInKeyOrder() throws Exception {
    Map<String, Integer> unordered =
        Map.of(
            "zulu", 8, "mike", 4, "bravo", 2, "papa", 6, "alpha", 1, "tango", 7, "charlie", 3,
            "echo", 5);
    Compat compat = Compat.builder().m(unordered).build();

    JsonNode m = new JsonMapper().readTree(format.serialize(compat)).get("m");

    assertEquals(
        List.of("alpha", "bravo", "charlie", "echo", "mike", "papa", "tango", "zulu"),
        List.copyOf(m.propertyNames()));
  }

  /** The existing extras golden, written by the plain mapper, still holds under the adapter. */
  @Test
  void retainedJsonExtras_matchTheExistingExtrasGolden() throws Exception {
    String reviewed =
        GoldenStore.classpath()
            .loadText(JacksonRoundTripCases.FAMILY, "full-with-extras", "jackson.json");

    Object parsed = format.deserialize(reviewed, Full.class);

    GoldenAssertions.assertJsonMatches(reviewed, format.serialize(parsed), "full-with-extras");
  }

  @Test
  void jacksonOffRecord_reportsNotApplicable() {
    Off off = Off.builder().name("n").build();

    assertTrue(
        format.notApplicableReason(off).orElseThrow().contains("recordbuf.jackson = false"),
        "the reason names the jackson-off generation");

    GoldenCase goldenCase =
        GoldenCase.builder(FAMILY, "off", off)
            .notApplicable(format, "jackson surface off")
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
  void jacksonOffRecord_declaredApplicable_failsConstruction() {
    GoldenCase.Builder builder = GoldenCase.builder(FAMILY, "off", Off.builder().build());
    builder.lossless(format);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> builder.build(List.of(format)));

    assertTrue(error.getMessage().contains("not applicable"), error.getMessage());
  }

  @Test
  void jacksonOnRecord_isApplicable() {
    assertEquals(Optional.empty(), format.notApplicableReason(full()));
  }
}
