package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import bijnagte.recordbuf.fixture.golden.GoldenMatrix;
import bijnagte.recordbuf.fixture.golden.GoldenStore;
import bijnagte.recordbuf.fixture.jackson.full.rec.Knobs;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code (recordbuf.json_name)} and {@code (recordbuf.json_ignored)} knobs on {@link Knobs}
 * (AE2): the golden case of family {@code jackson-knobs} (see {@link JacksonKnobsCases}) pins the
 * JSON, and the tests here pin what a golden cannot say: the renamed property is the only way in,
 * and the ignored field is a JSON-only loss that the wire does not share.
 */
class JacksonKnobsTest {

  private static final JsonMapper M = new JsonMapper();

  private static final JacksonKnobsCases SOURCE = new JacksonKnobsCases();

  /**
   * The renamed field is under its override, the ignored one is absent, and it reads back cleared.
   */
  @TestFactory
  Stream<DynamicNode> knobsCase_matchesItsGoldenAndReadsBackWithoutTheIgnoredField() {
    return GoldenMatrix.of(GoldenStore.classpath(), SOURCE.formats()).tests(SOURCE.cases());
  }

  @Test
  void jsonName_replacesTheCamelCaseName() {
    JsonNode json = M.valueToTree(JacksonKnobsCases.knobs());

    assertEquals("shown", json.get("shown_as").asString());
    assertFalse(json.has("displayText"), "the proto3 camelCase name is replaced, not added");
  }

  @Test
  void jsonName_isReadBack() {
    Knobs back = M.readValue("{\"label\":\"l\",\"shown_as\":\"shown\"}", Knobs.class);

    assertEquals("shown", back.displayText());
  }

  @Test
  void jsonIgnored_isNeverWrittenToJson() {
    assertFalse(M.valueToTree(JacksonKnobsCases.knobs()).has("secret"));
  }

  @Test
  void jsonIgnored_staysOnTheWire() throws Exception {
    Knobs knobs = JacksonKnobsCases.knobs();

    Knobs decoded = Knobs.fromBytes(knobs.toByteArray());

    assertEquals(knobs, decoded);
    assertEquals("hidden", decoded.secret(), "the ignored field is JSON-only");
  }
}
