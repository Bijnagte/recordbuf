package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.IdentityFormatter;
import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link Formatter} SPI and its identity default, plus the {@link
 * SourceRenderer#render(JavaFile, Formatter)} overload that threads a formatter past the render.
 * The default path ({@link Formatter#IDENTITY}) leaves source untouched, so the codegen module
 * stays formatter-free and {@link JavaFile#toSource()} is unchanged; a concrete formatter is
 * opt-in.
 */
class FormatterTest {

  private static final String PKG = "bijnagte.recordbuf.codegen.source";

  private static JavaFile smallRecord() {
    TypeCollector ci = new TypeCollector(PKG);
    Type top =
        RecordSpec.named("Sample")
            .public_()
            .component(Component.named("int", "id").build())
            .build();
    return new JavaFile(PKG, Javadoc.empty(), top, ci);
  }

  @Test
  void formatter_identity_returns_input_unchanged() {
    assertEquals("abc\n", Formatter.IDENTITY.format("abc\n"));
    assertEquals("", Formatter.IDENTITY.format(""));
  }

  @Test
  void identity_formatter_returns_input_unchanged() {
    assertEquals("abc\n", new IdentityFormatter().format("abc\n"));
    assertEquals("", new IdentityFormatter().format(""));
  }

  @Test
  void render_with_identity_matches_no_arg_render() {
    JavaFile file = smallRecord();
    assertEquals(SourceRenderer.render(file), SourceRenderer.render(file, Formatter.IDENTITY));
  }

  @Test
  void render_with_custom_formatter_applies_it_post_render() {
    JavaFile file = smallRecord();
    // A trivial post-pass that appends a marker proves the formatter runs AFTER the render, not
    // during it: the record shell is already present and the marker is not.
    Formatter marker = src -> src + "/*formatted*/";
    String formatted = SourceRenderer.render(file, marker);

    assertTrue(formatted.contains("public record Sample("), "record shell renders: " + formatted);
    assertTrue(
        formatted.endsWith("/*formatted*/"), "custom formatter runs post-render: " + formatted);
    assertFalse(
        SourceRenderer.render(file).contains("/*formatted*/"),
        "the identity (no-arg) render does not apply the custom formatter");
  }

  @Test
  void identity_formatter_and_identity_constant_agree() {
    String src = "int x = 1;\n";
    assertEquals(Formatter.IDENTITY.format(src), new IdentityFormatter().format(src));
  }
}
