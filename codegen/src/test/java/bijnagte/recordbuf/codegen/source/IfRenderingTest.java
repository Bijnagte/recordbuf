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
 * The {@code if} control-flow node: an {@link If} statement renders as a braced block ({@code if
 * (cond) { ... }}), with an optional terminal {@code else} block, and compiles through the
 * in-process {@code javac}. The node's own render handler ({@link SourceRenderer}'s {@code
 * renderIf}) owns the braces -- it is not a nested {@code Block} node; an empty branch renders no
 * clause. Each {@code then}/{@code else} body is a {@code List<Statement>} laid out at the next
 * indent.
 */
class IfRenderingTest {

  private static final String PKG = "bijnagte.recordbuf.codegen.source";

  private static String render(Type.Record r) {
    return SourceRenderer.render(new JavaFile(PKG, new Javadoc(""), r, new TypeCollector(PKG)));
  }

  @Test
  void if_then_only_renders_a_single_braced_block() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("ThenOnly")
                .component(Member.Field.builder().type("int").name("value").build())
                .method(
                    Member.Method.named("demo")
                        .public_()
                        .body(If.condition("true").body(Statement.ret()).build())
                        .build())
                .build());

    assertTrue(src.contains("if (true) {"), "the guarded clause renders: " + src);
    assertTrue(src.contains("return;"), "the then-body statement renders: " + src);
    // No else clause was set, so the block closes on its own with no trailing clause.
    assertTrue(!src.contains("else"), "no unset else clause renders: " + src);
    assertCompiles(src, "ThenOnly");
  }

  @Test
  void if_with_terminal_else_renders_both_braced_blocks() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("ThenElse")
                .component(Member.Field.builder().type("int").name("value").build())
                .method(
                    Member.Method.named("demo")
                        .public_()
                        .body(
                            If.condition("value > 0")
                                .body(Statement.ret())
                                .else_(Statement.ret())
                                .build())
                        .build())
                .build());

    assertTrue(src.contains("if (value > 0) {"), "the guarded clause renders: " + src);
    assertTrue(src.contains("} else {"), "the terminal else closes the prior block: " + src);
    assertCompiles(src, "ThenElse");
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
    Path out = Files.createTempDirectory("recordbuf-if-java");
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
