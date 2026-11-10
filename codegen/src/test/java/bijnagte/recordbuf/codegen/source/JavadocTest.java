package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@link Javadoc#builder()} fluent builder: a single {@link Javadoc.Builder#text} call accepts
 * several varargs segments and all segments are concatenated (joined together) into the rendered
 * text. Repeated {@code text(...)} calls also concatenate. Because the renderer emits a javadoc
 * purely from its {@code raw()} string, the builder being byte-equivalent to {@code new
 * Javadoc(concat)} is the proof that "all text is joined together when rendered" holds without
 * changing any rendered output.
 */
class JavadocTest {

  @Test
  void builderWithNoTextRendersNothing() {
    assertFalse(Javadoc.builder().build().present());
    assertEquals(Javadoc.empty(), Javadoc.builder().build());
    assertEquals(null, Javadoc.builder().build().raw());
  }

  @Test
  void oneTextCallConcatenatesVarargsSegments() {
    assertEquals("x y z", Javadoc.builder().text("x ", "y ", "z").build().raw());
  }

  @Test
  void repeatedTextCallsConcatenate() {
    assertEquals("ab", Javadoc.builder().text("a").text("b").build().raw());
  }

  @Test
  void builderRendersExpectedText() {
    Javadoc built = Javadoc.builder().text("Wire codec for the ", "Sample", " record.").build();
    assertEquals("Wire codec for the Sample record.", built.raw());
    assertTrue(built.present());
  }

  @Test
  void nullSegmentRendersNothing() {
    // A nullable source segment passes straight through: a null arg yields no text, exactly
    // like a blank comment, so the builder can replace the direct nullable constructor call.
    assertFalse(Javadoc.builder().text((String) null).build().present());
    assertEquals(Javadoc.empty(), Javadoc.builder().text((String) null).build());
  }
}
