package bijnagte.recordbuf.fixture.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.JsonGolden;
import bijnagte.recordbuf.fixture.jackson.oneof.rec.Event;
import bijnagte.recordbuf.fixture.oneof.rec.Color;
import bijnagte.recordbuf.fixture.oneof.rec.Compat;
import bijnagte.recordbuf.fixture.oneof.rec.Sub;
import bijnagte.recordbuf.runtime.Bytes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * AE2 (2026-11-21-005 U7): a oneof renders flat -- the set member as a top-level property under its
 * JSON name, plus a discriminator naming it; an unset oneof renders neither -- and reads back to an
 * equal record through a plain mapper. A discriminator that disagrees with the members present is
 * malformed and fails.
 */
class JacksonOneofRoundTripTest {

  private static final JsonMapper M = new JsonMapper();

  private static Compat compat(Compat.Choice choice) {
    return Compat.builder().choice(choice).build();
  }

  private static void assertRoundTrip(String golden, Object record) throws Exception {
    String json = M.writeValueAsString(record);
    JsonGolden.assertMatches(golden, json);
    assertEquals(record, M.readValue(json, record.getClass()));
  }

  @Test
  void eachMemberRendersFlatWithItsDiscriminator() throws Exception {
    assertRoundTrip("oneof/compat-name.json", compat(new Compat.Choice.NameValue("blue")));
    assertRoundTrip("oneof/compat-count.json", compat(new Compat.Choice.CountValue(3)));
    assertRoundTrip(
        "oneof/compat-item.json",
        compat(new Compat.Choice.ItemValue(Sub.builder().text("t").build())));
    assertRoundTrip("oneof/compat-color.json", compat(new Compat.Choice.ColorValue(Color.RED)));
  }

  @Test
  void unsetOneofRendersNeitherProperty() throws Exception {
    assertRoundTrip("oneof/compat-unset.json", Compat.builder().build());
  }

  @Test
  void overriddenDiscriminatorAndPairedMembers() throws Exception {
    // json_oneof = "kind"; a Timestamp member renders ISO, a bytes member base64.
    assertRoundTrip(
        "oneof/event-at.json",
        Event.builder()
            .id(7)
            .when(new Event.When.AtValue(Instant.ofEpochSecond(1_763_614_896L, 789_000_000)))
            .build());
    assertRoundTrip(
        "oneof/event-blob.json",
        Event.builder()
            .id(7)
            .when(new Event.When.BlobValue(Bytes.of(new byte[] {1, 2, 3})))
            .build());
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
}
