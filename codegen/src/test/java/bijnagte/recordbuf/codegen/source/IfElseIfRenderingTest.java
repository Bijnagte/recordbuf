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
 * The {@code if} control-flow node's {@code else if} chain: an {@link If} statement renders a
 * guarded {@code if}-clause followed by zero or more {@code else if}-clauses and an optional
 * terminal {@code else}, all sharing the same nesting level. The {@link ElseIf} sub-clause renders
 * its own guard and braces on the closing-brace line ({@code } else if (cond) {}}).
 */
class IfElseIfRenderingTest {

  private static final String PKG = "bijnagte.recordbuf.codegen.source";

  private static String render(Type.Record r) {
    return SourceRenderer.render(new JavaFile(PKG, new Javadoc(""), r, new TypeCollector(PKG)));
  }

  @Test
  void if_with_one_else_if_renders_a_two_clauses() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("WithElseIf")
                .component(Member.Field.builder().type("int").name("value").build())
                .method(
                    Member.Method.builder()
                        .public_()
                        .name("demo")
                        .body(
                            If.condition("value > 0")
                                .body(Statement.ret())
                                .elseIf("value == 0", Statement.ret())
                                .build())
                        .build())
                .build());

    assertTrue(src.contains("if (value > 0) {"), "the guarded then-clause renders: " + src);
    assertTrue(src.contains("} else if (value == 0) {"), "the else-if clause renders: " + src);
    // No terminal else was set, so the else-if block closes on its own.
    assertTrue(!src.contains("} else {"), "no unset terminal else renders: " + src);
    assertCompiles(src, "WithElseIf");
  }

  @Test
  void if_else_if_then_terminal_else_renders_all_three_blocks() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("FullChain")
                .component(Member.Field.builder().type("int").name("value").build())
                .method(
                    Member.Method.builder()
                        .public_()
                        .name("demo")
                        .body(
                            If.condition("value > 0")
                                .body(Statement.ret())
                                .elseIf("value == 0", Statement.ret())
                                .else_(Statement.ret())
                                .build())
                        .build())
                .build());

    assertTrue(src.contains("if (value > 0) {"), "the guarded then-clause renders: " + src);
    assertTrue(src.contains("} else if (value == 0) {"), "the else-if clause renders: " + src);
    assertTrue(src.contains("} else {"), "the terminal else closes the else-if block: " + src);
    assertCompiles(src, "FullChain");
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
    Path out = Files.createTempDirectory("recordbuf-if-elseif-java");
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
