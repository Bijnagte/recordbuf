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
 * The {@code while} control-flow node: a {@link While} statement renders as a braced block ({@code
 * while (cond) { ... }}), closed by its own brace. The node's own render handler ({@link
 * SourceRenderer}'s {@code renderWhile}) owns the braces -- it is not a nested {@code Block} node;
 * an empty body renders the empty-loop case {@code while (cond) { }}, the only structural
 * difference from a guarded {@link If} clause, which has a terminal-close-on-empty semantics.
 * Unlike {@code if}, a {@code while} has no branch and no optional {@code else} clause, so it
 * always closes.
 */
class WhileRenderingTest {

  private static final String PKG = "bijnagte.recordbuf.codegen.source";

  private static String render(Type.Record r) {
    return SourceRenderer.render(new JavaFile(PKG, new Javadoc(""), r, new TypeCollector(PKG)));
  }

  @Test
  void while_with_body_renders_braced_block() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("WhileLoop")
                .component(Member.Field.builder().type("int").name("value").build())
                .method(
                    Member.Method.builder()
                        .public_()
                        .name("demo")
                        .body(While.builder().condition("value > 0").body(Statement.ret()).build())
                        .build())
                .build());

    assertTrue(src.contains("while (value > 0) {"), "the guarded loop renders: " + src);
    assertTrue(src.contains("return;"), "the loop-body statement renders: " + src);
    // A while always closes on its own brace -- no optional trailing clause like if's else.
    assertTrue(!src.contains("else"), "a while has no else clause: " + src);
    assertCompiles(src, "WhileLoop");
  }

  @Test
  void empty_body_renders_empty_loop() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("WhileEmpty")
                .component(Member.Field.builder().type("int").name("value").build())
                .method(
                    Member.Method.builder()
                        .public_()
                        .name("demo")
                        .body(While.builder().condition("value > 0").build())
                        .build())
                .build());

    // An empty body still emits the header and the closing brace.
    assertTrue(src.contains("while (value > 0) {"), src);
    assertCompiles(src, "WhileEmpty");
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
    Path out = Files.createTempDirectory("recordbuf-while-java");
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
