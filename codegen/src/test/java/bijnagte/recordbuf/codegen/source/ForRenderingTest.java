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
 * The {@code for-each} control-flow node: a {@link For} statement renders as a braced block ({@code
 * for (pattern : iterable) { ... }}), closed by its own brace. The node's own render handler
 * (SourceRenderer's {@code renderFor}) owns the braces -- it is not a nested {@code Block} node; an
 * empty body renders the empty-loop case {@code for (p : x) { }}. A for-each always closes on its
 * own; like a {@code while}, it has no branch and no optional {@code else}.
 */
class ForRenderingTest {

  private static final String PKG = "bijnagte.recordbuf.codegen.source";

  private static String render(Type.Record r) {
    return SourceRenderer.render(new JavaFile(PKG, new Javadoc(""), r, new TypeCollector(PKG)));
  }

  @Test
  void for_each_with_body_renders_braced_block() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("ForLoop")
                .component(
                    Member.Field.builder().type("java.util.List<String>").name("value").build())
                .method(
                    Member.Method.builder()
                        .public_()
                        .name("demo")
                        .body(
                            For.builder()
                                .pattern("var _e")
                                .iterable("value()")
                                .body(Statement.ret())
                                .build())
                        .build())
                .build());

    assertTrue(src.contains("for (var _e : value()) {"), "the loop renders: " + src);
    assertTrue(src.contains("return;"), "the loop-body statement renders: " + src);
    assertCompiles(src, "ForLoop");
  }

  @Test
  void empty_body_renders_empty_loop() throws Exception {
    String src =
        render(
            Type.Record.builder()
                .public_()
                .name("ForEmpty")
                .component(
                    Member.Field.builder().type("java.util.List<String>").name("value").build())
                .method(
                    Member.Method.builder()
                        .public_()
                        .name("demo")
                        .body(For.builder().pattern("var _e").iterable("value()").build())
                        .build())
                .build());

    // An empty body still emits the header and the closing brace.
    assertTrue(src.contains("for (var _e : value()) {"), src);
    assertCompiles(src, "ForEmpty");
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
    Path out = Files.createTempDirectory("recordbuf-for-java");
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
