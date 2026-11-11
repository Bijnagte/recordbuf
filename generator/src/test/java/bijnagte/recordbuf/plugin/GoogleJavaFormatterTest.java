package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.Formatter;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link GoogleJavaFormatter} -- the GJF-backed {@link Formatter} impl.
 *
 * <p>The assertions are property-based rather than canon-pinned: GJF's exact style is brittle to
 * hardcode across versions, and the SPI's contract is the property (reformats, is idempotent, fails
 * loud on bad input) rather than the specific whitespace choice. Running this test also confirms
 * the runtime wiring: GJF's {@code formatSource} parses source into a {@code com.sun.tools.javac}
 * tree, so the generator's test JVM (with the build's {@code --add-exports jdk.compiler/...}) must
 * let GJF run without an {@link UnsupportedOperationException} -- a missing export surfaces as such
 * and fails the test.
 */
class GoogleJavaFormatterTest {

  private final GoogleJavaFormatter formatter = GoogleJavaFormatter.getInstance();
  private final Formatter spi = (Formatter) GoogleJavaFormatter.getInstance();

  @Test
  void exposes_a_stable_single_instance() {
    assertEquals(GoogleJavaFormatter.getInstance(), GoogleJavaFormatter.getInstance());
  }

  @Test
  void is_a_formatter_spi_instance() {
    assertTrue(GoogleJavaFormatter.getInstance() instanceof Formatter);
  }

  @Test
  void reformats_badly_spaced_source() {
    String ugly = "class  A   {int  x ;\n}\n";
    String out = formatter.format(ugly);

    // GJF ran (did not no-op): it changed the source.
    assertFalse(
        out.equals(ugly), "GJF should reformat ugly source, not return it unchanged: " + out);

    // The specific ugly spacing collapses away; the structural facts survive. (GJF reformats
    // whitespace but does NOT add modifiers -- it is a formatter, not a linter.)
    assertFalse(out.contains("class  A"), "double space after `class` collapses: " + out);
    assertFalse(out.contains(" {int"), "no space after `{` collapses: " + out);
    assertFalse(out.contains("x ;"), "space before `;` collapses: " + out);
    assertTrue(out.contains("class A {"), "a reformatted, spaced body: " + out);
  }

  @Test
  void is_idempotent() {
    // A *valid* but badly-spaced Java snippet -- GJF only reformats parseable source, so the
    // input must parse (otherwise it throws FormatterException, which is not the point here).
    String source = "public class B {\nint  method(){   int  y   =  1;\nreturn  y;\n}\n}\n";
    String once = formatter.format(source);
    String twice = formatter.format(once);
    assertEquals(once, twice, "formatting a GJF-formatted string a second time is a no-op");
    // The GJF output canonicalized the ugly inner spacing (note: 2-space indentation is
    // GJF's style, so the body legitimately contains spaces -- do not assert "no double spaces").
    assertTrue(
        once.contains("int y = 1;"), "ugly `int  y   =  1` canonicalizes to `int y = 1;`: " + once);
    assertFalse(once.contains("y   ="), "the ugly inner spacing is gone: " + once);
    assertFalse(once.startsWith("\n"), "no leading blank lines survive formatting: " + once);
  }

  @Test
  void fails_loud_on_unparseable_source() {
    // GJF's checked FormatterException must surface as an unchecked failure -- the SPI is
    // checked-exception-free (KTD-5). Asserting a specific subclass would couple to the wrap
    // choice;
    // the contract is "throws unchecked, not checked."
    assertThrows(
        RuntimeException.class,
        () -> spi.format("this is not (java at all ;::"),
        "an unparseable source must fail the formatter, not succeed");
  }
}
