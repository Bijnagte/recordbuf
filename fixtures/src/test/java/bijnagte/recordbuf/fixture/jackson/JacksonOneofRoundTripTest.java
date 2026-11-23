package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.jackson.oneof.EventAvro;
import bijnagte.recordbuf.fixture.jackson.oneof.rec.Event;
import bijnagte.recordbuf.fixture.jackson.oneof.rec.EventAvroConverter;
import bijnagte.recordbuf.fixture.oneof.rec.Compat;
import bijnagte.recordbuf.json.JsonFieldMaskFilter;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.FieldMask;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AE2 (2026-11-21-005 U7): a oneof renders flat -- the set member as a top-level property under its
 * JSON name, plus a discriminator naming it; an unset oneof renders neither -- and reads back to an
 * equal record through a plain mapper. A discriminator that disagrees with the members present is
 * malformed and fails.
 */
class JacksonOneofRoundTripTest {

  private static final JsonMapper M = new JsonMapper();

  private static final JacksonOneofCases SOURCE = new JacksonOneofCases();

  private static Compat compat(Compat.Choice choice) {
    return Compat.builder().choice(choice).build();
  }

  private static void assertRoundTrip(Object record) throws Exception {
    assertEquals(record, M.readValue(M.writeValueAsString(record), record.getClass()));
  }

  /**
   * Every member renders flat with its discriminator, an unset oneof renders neither, and the
   * overridden discriminator ({@code json_oneof = "kind"}) and the paired Timestamp and bytes
   * members render as their Jackson goldens in the {@code jackson-oneof} family.
   */
  @TestFactory
  Stream<DynamicNode> oneofCases_matchTheirGoldensForwardAndReverse() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  @Test
  void eachMemberRoundTripsThroughThePlainMapper() throws Exception {
    assertRoundTrip(JacksonOneofCases.compatName());
    assertRoundTrip(JacksonOneofCases.compatCount());
    assertRoundTrip(JacksonOneofCases.compatItem());
    assertRoundTrip(JacksonOneofCases.compatColor());
  }

  @Test
  void unsetOneofRoundTripsThroughThePlainMapper() throws Exception {
    assertRoundTrip(JacksonOneofCases.compatUnset());
  }

  @Test
  void overriddenDiscriminatorAndPairedMembersRoundTripThroughThePlainMapper() throws Exception {
    assertRoundTrip(JacksonOneofCases.eventAt());
    assertRoundTrip(JacksonOneofCases.eventBlob());
  }

  @Test
  void theDiscriminatorIsOptionalOnRead() {
    Compat back = M.readValue("{\"count\":5}", Compat.class);

    assertEquals(new Compat.Choice.CountValue(5), back.choice());
  }

  @Test
  void unknownPropertiesAreRetainedNextToTheOneof() {
    Compat back = M.readValue("{\"name\":\"blue\",\"choiceType\":\"name\",\"x\":1}", Compat.class);

    assertEquals(new Compat.Choice.NameValue("blue"), back.choice());
    assertEquals(M.readTree("1"), back.jsonExtras().get("x"));
    assertTrue(M.writeValueAsString(back).contains("\"x\":1"));
  }

  @Test
  void malformedOneofJsonFails() {
    for (String json :
        List.of(
            "{\"name\":\"blue\",\"choiceType\":\"count\"}", // discriminator names another member
            "{\"choiceType\":\"name\"}", // discriminator without its member
            "{\"name\":\"blue\",\"count\":1}", // two members
            "{\"name\":\"blue\",\"choiceType\":\"shade\"}")) { // unknown member
      var e = assertThrows(JacksonException.class, () -> M.readValue(json, Compat.class), json);
      assertTrue(String.valueOf(e.getMessage()).contains("oneof choice"), e.getMessage());
    }
  }

  @Test
  void nullMemberValuesAreAbsent() {
    Compat back = M.readValue("{\"name\":null}", Compat.class);

    assertNull(back.choice());
    assertInstanceOf(Compat.class, back);
  }

  @Test
  void pairedMembersRoundTripThroughAvroBinary() throws Exception {
    var b = ClassSecurityValidator.builder();
    for (String name : List.of("EventAvro", "RecordbufOneofValue", "RecordbufUnknownField")) {
      b.add("bijnagte.recordbuf.fixture.jackson.oneof." + name);
    }
    ClassSecurityValidator.setGlobal(b.build());
    for (Event.When when :
        List.of(
            new Event.When.AtValue(Instant.ofEpochSecond(1_763_614_896L, 789_000)),
            new Event.When.BlobValue(Bytes.of(new byte[] {1, 2, 3})),
            new Event.When.LabelValue("l"))) {
      Event event = Event.builder().id(7).when(when).build();

      var binary = EventAvroConverter.fromRecord(event).toByteBuffer();

      assertEquals(event, EventAvroConverter.toRecord(EventAvro.fromByteBuffer(binary)));
    }
  }

  /** Masking a oneof member keeps it without the discriminator, which is optional on read. */
  @Test
  void aMaskedOneofMemberReadsBackWithoutItsDiscriminator() {
    Compat compat = compat(new Compat.Choice.NameValue("blue"));

    JsonNode projected = JsonFieldMaskFilter.filter(M.valueToTree(compat), FieldMask.of("name"));

    assertEquals(M.readTree("{\"name\":\"blue\"}"), projected);
    assertEquals(compat, M.treeToValue(projected, Compat.class));
  }
}
