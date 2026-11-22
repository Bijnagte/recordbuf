package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The proto3 JSON mappings for {@code FieldMask} (path string) and {@code Empty} ({@code {}}). */
class JsonFieldMaskEmptyTest {

  record Holder(@Nullable FieldMask mask, @Nullable Empty empty) {}

  private static final JsonMapper M = JsonMapper.builder().findAndAddModules().build();

  @Test
  void fieldMaskIsCommaJoinedLowerCamelPaths() {
    FieldMask mask = FieldMask.of("foo_bar.baz_qux", "a", "z.y_x");

    assertEquals("a,fooBar.bazQux,z.yX", JsonFieldMask.toJson(mask));
    assertEquals(mask, JsonFieldMask.fromJson("a,fooBar.bazQux,z.yX"));
  }

  @Test
  void emptyFieldMaskIsTheEmptyString() {
    assertEquals("", JsonFieldMask.toJson(FieldMask.empty()));
    assertEquals(FieldMask.empty(), JsonFieldMask.fromJson(""));
  }

  @Test
  void holderRoundTripsThroughTheModule() {
    Holder h = new Holder(FieldMask.of("user.display_name", "id"), Empty.INSTANCE);

    String json = M.writeValueAsString(h);

    assertEquals("{\"mask\":\"id,user.displayName\",\"empty\":{}}", json);
    Holder back = M.readValue(json, Holder.class);
    assertEquals(h.mask(), back.mask());
    assertSame(Empty.INSTANCE, back.empty());
  }

  @Test
  void emptyReadsAnyObjectAndRejectsOtherTokens() {
    assertSame(Empty.INSTANCE, M.readValue("{\"empty\":{\"x\":[1]}}", Holder.class).empty());
    assertThrows(JacksonException.class, () -> M.readValue("{\"empty\":1}", Holder.class));
    assertThrows(JacksonException.class, () -> M.readValue("{\"mask\":[\"a\"]}", Holder.class));
  }
}
