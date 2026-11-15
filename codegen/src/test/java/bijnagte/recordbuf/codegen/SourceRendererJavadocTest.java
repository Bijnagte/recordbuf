package bijnagte.recordbuf.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Javadoc emission on the {@link SourceRenderer} level: a {@code Javadoc} block is emitted as a
 * clean block comment (no {@code <pre>}/{@code <code>} protobuf boilerplate) with only the
 * HTML-significant characters {@code & / < / >} escaped at render time. A bare {@code @} in the
 * comment body stays intact, matching recordbuf's clean-output style.
 *
 * <p>Proto source comments come from {@code source_code_info} and are raw text; nothing in the
 * model is HTML-escaped, so the escaping must land in the renderer.
 */
class SourceRendererJavadocTest {

  @Test
  void noOpForNullOrBlankCommentReturnsEmpty() {
    assertTrue(SourceRenderer.javadoc(null).isBlank(), "null raw renders nothing");
    assertTrue(SourceRenderer.javadoc("   \n\t  ").isBlank(), "blank raw renders nothing");
  }

  @Test
  void emitsMultilineBlockComment() {
    String out = SourceRenderer.javadoc("Line one.\nLine two.");
    assertTrue(out.startsWith("/**"), "block opens with /**");
    assertTrue(out.contains(" * Line one."), "first content line prefixed ' * '");
    assertTrue(out.contains(" * Line two."), "second content line prefixed ' * '");
    assertTrue(out.trim().endsWith("*/"), "block closed by */");
  }

  @Test
  void escapesHtmlSignificantCharacters() {
    String out =
        SourceRenderer.javadoc(
            "See <tag> and & ampersand, plus @NotNull stays bare; &amp; becomes &amp;amp;.");
    assertTrue(out.contains("&lt;tag&gt;"), "angle brackets escape to &lt;/&gt;");
    assertFalse(out.contains("<tag>"), "raw <tag> must be escaped");
    assertTrue(out.contains("&amp;"), "bare & escapes to &amp;");
    assertFalse(out.contains("& amp"), "raw ' & ' must be escaped");
    assertTrue(out.contains("@NotNull"), "bare @ is left intact");
    assertTrue(out.contains("&amp;amp;"), "a source '&amp;' becomes '&amp;amp;'");
  }

  @Test
  void emptyLineInSourceCommentRendersAsStar() {
    String out = SourceRenderer.javadoc("First.\n\nSecond.");
    assertTrue(out.contains(" *\n"), "a blank source line renders as ' *'");
    assertTrue(out.contains(" * First."), "first line still renders its text");
    assertTrue(out.contains(" * Second."), "second line still renders its text");
  }

  @Test
  void trimsLeadingAndTrailingBlankLines() {
    String out = SourceRenderer.javadoc("\n\nFirst\n  Second  \n");
    assertTrue(out.startsWith("/**"), "leading blank lines trimmed; block opens with /**");
    assertFalse(out.trim().endsWith("  */"), "trailing blank lines trimmed");
  }

  @Test
  void emitsAtZeroIndentFormattingIsTheFormattersJob() {
    // The renderer no longer indents Javadoc (or anything else): it emits at column 0 and a
    // formatter (GJF in production) owns the whitespace layout, including Javadoc alignment. So a
    // Javadoc block has no leading whitespace regardless of nesting -- the renderer owns structure
    // only.
    String out = SourceRenderer.javadoc("Nested");
    assertTrue(out.startsWith("/**"), "block opens at column 0, no leading indent");
    assertTrue(out.contains(" * Nested"), "content line prefixed ' * ' at column 0");
    assertFalse(out.startsWith(" "), "no leading whitespace -- formatting is the formatter's job");
  }

  @Test
  void escapeHtmlIsIdempotentForUnaffectedChars() {
    assertEquals("hello", SourceRenderer.escapeHtml("hello"));
    assertEquals("@Nullable", SourceRenderer.escapeHtml("@Nullable"));
    assertEquals("a.b.c", SourceRenderer.escapeHtml("a.b.c"));
  }

  @Test
  void escapeHtmlHandlesAllThreeSpecialChars() {
    assertTrue(SourceRenderer.escapeHtml("a & b").equals("a &amp; b"));
    assertTrue(SourceRenderer.escapeHtml("a < b").equals("a &lt; b"));
    assertTrue(SourceRenderer.escapeHtml("a > b").equals("a &gt; b"));
  }

  // The bare-@ ordering and @Deprecated-on-its-own-line tests from the original JavadocEmissionTest
  // tested JavaWriter.line() behavior (raw text line emission), not the Javadoc block-comment
  // renderer. Since those are not Javadoc-block-concerns and SourceRenderer renders via Type nodes
  // (not raw line emission), they do not need a SourceRenderer equivalent.
}
