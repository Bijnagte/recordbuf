package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.TypeCollector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * The {@code switch} control-flow node: a {@link Switch} element renders as {@code switch (subject)
 * { <cases> default -> ... }}, closed by its own brace. Each {@link Case} arm and the terminal
 * {@code default} arm auto-brace by body size (DEC-3 / B4): an empty arm renders {@code -> {}}, a
 * single-element arm renders un-braced {@code -> stmt;} (so it can also act as a switch-expression
 * yield arm), and more than one braces {@code -> { ... }}. The node's own render handler
 * (SourceRenderer's {@code renderSwitch}) owns the braces; a {@code While} (or {@code For}) arm
 * body nests its loop through the element layout.
 */
class SwitchRenderingTest {

  private static final String PKG = "bijnagte.recordbuf.codegen.source";

  private static String render(Type.Record r) {
    return SourceRenderer.render(new JavaFile(PKG, new Javadoc(""), r, new TypeCollector(PKG)));
  }

  @Test
  void empty_arm_renders_empty_block() {
    String src =
        render(
            Type.Record.named("SwitchEmpty")
                .public_()
                .method(
                    Method.named("demo")
                        .public_()
                        .body(
                            Switch.on("x")
                                .caseArm("1") // empty arm: no body.
                                .build())
                        .build())
                .build());

    // An empty arm renders an empty block, and no default clause renders when defaultBody is empty.
    assertTrue(src.contains("case 1 -> {}"), "an empty arm renders an empty block: " + src);
    assertTrue(!src.contains("default ->"), "no default clause when defaultBody is empty: " + src);
  }

  @Test
  void single_statement_arm_renders_unbraced() {
    String src =
        render(
            Type.Record.named("SwitchSingle")
                .public_()
                .method(
                    Method.named("demo")
                        .public_()
                        .body(
                            Switch.on("Wire.tagFieldNumber(tag)")
                                .caseArm("1", Element.of("value = in.readInt64();"))
                                .defaultBody(Element.of("unknown.add(in, tag);"))
                                .build())
                        .build())
                .build());

    assertTrue(
        src.contains("case 1 -> value = in.readInt64();"),
        "a single-element arm renders un-braced: " + src);
    assertTrue(
        src.contains("default -> unknown.add(in, tag);"),
        "a single-element default arm renders un-braced: " + src);
  }

  @Test
  void multi_pattern_arm_joins_with_comma_and_braces() {
    String src =
        render(
            Type.Record.named("SwitchMulti")
                .public_()
                .method(
                    Method.named("demo")
                        .public_()
                        .body(
                            Switch.on("x")
                                .caseArm(
                                    List.of("1", "2"), Element.of("x = 1;"), Element.of("x = 2;"))
                                .build())
                        .build())
                .build());

    // A multi-pattern arm joins its patterns with ", "; a multi-element body braces.
    assertTrue(src.contains("case 1, 2 -> {"), "a multi-pattern multi-element arm braces: " + src);
    assertTrue(src.contains("x = 1;"), "first body element renders inside the brace: " + src);
    assertTrue(src.contains("x = 2;"), "second body element renders inside the brace: " + src);
  }

  @Test
  void switch_statement_compiles() throws Exception {
    String src =
        render(
            Type.Record.named("SwitchCompiles")
                .public_()
                .method(
                    Method.named("demo")
                        .public_()
                        .param("Object", "n")
                        .body(
                            Switch.on("n.hashCode()")
                                .caseArm("1", Element.of("System.out.println(1);"))
                                .caseArm("2", Element.of("System.out.println(2);"))
                                .defaultBody(Element.of("System.out.println(0);"))
                                .build())
                        .build())
                .build());

    assertCompiles(src, "SwitchCompiles");
  }

  private static void assertCompiles(String source, String typeName) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/bijnagte/recordbuf/codegen/source/" + typeName + ".java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    Path out = Files.createTempDirectory("recordbuf-switch-java");
    boolean ok =
        compiler
            .getTask(null, null, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    assertTrue(
        ok,
        "rendered source must compile:\n"
            + source
            + "\n-- diagnostics --\n"
            + diags.getDiagnostics().stream()
                .map(Diagnostic::toString)
                .collect(Collectors.joining("\n")));
  }
}
