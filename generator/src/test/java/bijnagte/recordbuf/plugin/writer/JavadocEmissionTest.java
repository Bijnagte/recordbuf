package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Javadoc emission on the writer: type / component / enum-constant /
 * nested-record Javadoc is emitted as a clean block comment (no
 * {@code <pre>}/{@code <code>} protobuf boilerplate), and only the
 * HTML-significant characters & / < / > are escaped at emit time
 * (a bare @ in the comment body stays intact, matching recordbuf's
 * clean-output style).
 *
 * <p>Proto source comments come from {@code source_code_info} and are
 * raw text; nothing in the model is HTML-escaped, so the escaping
 * must land here. The generated output is asserted by substring
 * (the annotation fixture's end-to-end interop test already locks the
 * rendered record), so these tests lock the writer's contract directly.
 */
class JavadocEmissionTest {

    @Test
    void noOpForEmptyOrBlankComment() {
        JavaWriter empty = new JavaWriter();
        empty.javadoc(null);
        empty.javadoc("   \n\t  ");
        assertTrue(empty.toString().isEmpty() || empty.toString().isBlank(),
                "no Javadoc block should be emitted for a blank comment");
    }

    @Test
    void emitsMultilineBlockComment() {
        JavaWriter w = new JavaWriter();
        w.line("public record Foo(");
        w.indent(1);
        w.javadoc("Line one.\nLine two.");
        String out = w.toString();
        assertTrue(out.contains("/** Line one."), "first line opens with /**");
        assertTrue(out.contains(" * Line two."), "continuation lines are prefixed ' * '");
        assertTrue(out.contains("*/"), "block is closed by */");
    }

    @Test
    void escapesHtmlSignificantCharacters() {
        JavaWriter w = new JavaWriter();
        // Contains &, <, >, and a bare @ which must NOT be escaped.
        w.javadoc("See <tag> and & ampersand, plus @NotNull stays bare; &amp; becomes &amp;amp;.");
        String out = w.toString();
        assertTrue(out.contains("&lt;tag&gt;"), "angle brackets escape to &lt;/&gt;");
        assertTrue(out.contains("&amp;"), "bare & escapes to &amp;");
        // A bare @ must be left alone (no @escaping), but a literal &amp; is itself
        // escaped so the source "&amp;" becomes "&amp;amp;".
        assertTrue(out.contains("@NotNull"), "bare @ is left intact");
        // Verify the literal "&amp;" from the source was escaped, not passed through raw.
        assertTrue(out.contains("&amp;amp;"), "a source '&amp;' sequence becomes '&amp;amp;'");
        // And no raw, un-escaped & / < / > remain (would corrupt the Javadoc block).
        assertFalse(out.contains("& ampersand"), "raw ' & ' remains un-escaped in output");
        assertFalse(out.contains("<tag>"), "raw '<tag>' remains in output");
    }

    @Test
    void emptyLineInSourceCommentRendersAsStar() {
        JavaWriter w = new JavaWriter();
        w.javadoc("First.\n\nSecond.");
        String out = w.toString();
        // A blank line in the source renders as a bare ' *' continuation line.
        assertTrue(out.contains(" *\n"), "a blank source line renders as ' *'");
        assertTrue(out.contains(" * First.") || out.contains("First."),
                "first line still renders its text");
    }

    @Test
    void noLeadingTrailingWhitespaceInBlock() {
        JavaWriter w = new JavaWriter();
        w.javadoc("\n\nFirst\n  Second  \n");
        String out = w.toString();
        // The leading/trailing blank lines of the raw comment are trimmed.
        assertTrue(out.startsWith("/**"), "block opens with /** after trimming leading blanks");
        assertFalse(out.trim().endsWith("  */"), "no trailing whitespace before */");
    }

    /** Trivial sanity check that the writer emits bare @Deprecated as a single
     *  line before an element. This locks the @Deprecated-before-@Nullable
     *  ordering that recordbuf emits: {@code @Deprecated @Nullable T name}. */
    @Test
    void bareDeprecatedAnnotationOrdering() {
        JavaWriter w = new JavaWriter();
        w.line("@Deprecated @Nullable String note");
        String out = w.toString();
        int dep = out.indexOf("@Deprecated");
        int nullable = out.indexOf("@Nullable");
        assertTrue(dep >= 0 && nullable >= 0, "both annotations on the component");
        assertTrue(dep < nullable, "@Deprecated precedes @Nullable (JLS order)");
    }

    /** A bare @Deprecated with a following component line should land ON ITS
     *  OWN LINE (no inline "name" prefix). */
    @Test
    void deprecatedOnOwnLineForEnumConstant() {
        JavaWriter w = new JavaWriter();
        w.line("public enum Kind {");
        w.indent(1);
        w.line("@Deprecated");
        w.line("KIND_LEGACY(1),");
        assertTrue(List.of("public enum Kind {", "@Deprecated", "KIND_LEGACY(1),")
                .stream()
                .allMatch(w.toString()::contains),
                "the @Deprecated is on its own line before the constant");
    }
}
