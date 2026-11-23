package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link JsonOneof#select}: which member a oneof's flat JSON properties describe. */
class JsonOneofTest {

  private static final List<String> NAMES = List.of("name", "count");

  @Test
  void discriminatorAndItsMemberSelectThatMember() {
    assertEquals(1, JsonOneof.select("choice", "count", NAMES, null, 3));
  }

  @Test
  void aLoneMemberWithoutDiscriminatorIsSelected() {
    assertEquals(0, JsonOneof.select("choice", null, NAMES, "blue", null));
  }

  @Test
  void nothingPresentIsUnset() {
    assertEquals(-1, JsonOneof.select("choice", null, NAMES, null, null));
  }

  @Test
  void malformedCombinationsFail() {
    // discriminator names an absent member / a different member is set
    var mismatch =
        assertThrows(
            IllegalArgumentException.class,
            () -> JsonOneof.select("choice", "name", NAMES, null, 3));
    String message = String.valueOf(mismatch.getMessage());
    assertTrue(message.contains("\"count\" is set"), message);
    assertThrows(
        IllegalArgumentException.class,
        () -> JsonOneof.select("choice", "name", NAMES, null, null));
    // unknown discriminator
    assertThrows(
        IllegalArgumentException.class,
        () -> JsonOneof.select("choice", "colour", NAMES, null, null));
    // two members set, with or without a discriminator
    assertThrows(
        IllegalArgumentException.class, () -> JsonOneof.select("choice", null, NAMES, "a", 1));
    assertThrows(
        IllegalArgumentException.class, () -> JsonOneof.select("choice", "name", NAMES, "a", 1));
  }
}
