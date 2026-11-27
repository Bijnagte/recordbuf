package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The JSON mappings for {@code FieldMask} (an array of paths) and {@code Empty} ({@code {}}). */
class JsonFieldMaskEmptyTest {

  record Holder(@Nullable FieldMask mask, @Nullable Empty empty) {}

  private static final JsonMapper M = JsonMapper.builder().findAndAddModules().build();

  @Test
  void fieldMaskPathsAreLowerCamel() {
    FieldMask mask = FieldMask.of("foo_bar.baz_qux", "a", "z.y_x");

    assertEquals(List.of("fooBar.bazQux", "a", "z.yX"), JsonFieldMask.jsonPaths(mask));
    assertEquals(mask, JsonFieldMask.fromJsonPaths(List.of("fooBar.bazQux", "a", "z.yX")));
  }

  @Test
  void aPathThatDoesNotSurviveTheCamelRoundTripFailsInsteadOfRenamingAField() {
    for (String path : List.of("addr.line_1", "a__b")) {
      var e =
          assertThrows(
              IllegalArgumentException.class, () -> JsonFieldMask.jsonPaths(FieldMask.of(path)));

      assertTrue(String.valueOf(e.getMessage()).contains("cannot be written"), e.getMessage());
    }
    assertThrows(JacksonException.class, () -> M.writeValueAsString(FieldMask.of("addr.line_1")));
  }

  @Test
  void emptyFieldMaskIsTheEmptyArray() {
    assertEquals("[]", M.writeValueAsString(FieldMask.empty()));
    assertEquals(FieldMask.empty(), M.readValue("[]", FieldMask.class));
  }

  @Test
  void holderRoundTripsThroughTheModule() {
    Holder h = new Holder(FieldMask.of("user.display_name", "id"), Empty.INSTANCE);

    String json = M.writeValueAsString(h);

    assertEquals("{\"mask\":[\"user.displayName\",\"id\"],\"empty\":{}}", json);
    Holder back = M.readValue(json, Holder.class);
    assertEquals(h.mask(), back.mask());
    assertSame(Empty.INSTANCE, back.empty());
  }

  @Test
  void emptyReadsAnyObjectAndRejectsOtherTokens() {
    assertSame(Empty.INSTANCE, M.readValue("{\"empty\":{\"x\":[1]}}", Holder.class).empty());
    assertThrows(JacksonException.class, () -> M.readValue("{\"empty\":1}", Holder.class));
    assertThrows(JacksonException.class, () -> M.readValue("{\"mask\":\"a,b\"}", Holder.class));
    assertThrows(JacksonException.class, () -> M.readValue("{\"mask\":[1]}", Holder.class));
  }
}
