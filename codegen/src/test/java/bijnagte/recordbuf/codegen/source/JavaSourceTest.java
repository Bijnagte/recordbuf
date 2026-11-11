package bijnagte.recordbuf.codegen.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * The M4-C1 gate: build a <em>pure-Java</em> program through the AST and render it to source, then
 * compile that rendered source with the in-process {@code javac}. The program is a top-level record
 * {@code Sample} that owns a nested {@code enum SampleType} (the "fragment"), a {@code private int
 * number} field, its own constructor, and a {@code getNumber} method -- plus two static methods on
 * the record. Every method/constructor/static-block <em>body is a {@link List}&lt;{@link
 * Statement}&gt; emitted verbatim</em>; the surrounding structure is the AST. So the test is the
 * proof that the "structural AST, line-emission bodies" hybrid renders compilable Java.
 */
class JavaSourceTest {

  /** The nested enum, fully specified as an AST. */
  private static Type.Enum sampleType() {
    return Type.Enum.builder()
        .public_()
        .name("SampleType")
        .enumConstant(
            Member.EnumConstant.builder().name("A").ctorArgs("0").build(),
            Member.EnumConstant.builder().name("B").ctorArgs("1").build())
        .field(Member.Field.builder().private_().type("int").name("number").build())
        .constructor(
            Member.Constructor.builder()
                .name("SampleType")
                .param("int", "number")
                .body(Statement.of("this.number = number;"))
                .build())
        .method(
            Member.Method.builder()
                .public_()
                .returnType("int")
                .name("getNumber")
                .body(Statement.of("return number;"))
                .build())
        .build();
  }

  /** The top-level record, with its nested type + two static methods as body members. */
  private static Type.Record sample() {
    return Type.Record.builder()
        .public_()
        .name("Sample")
        .component(
            Member.Field.builder().type("int").name("value").build(),
            Member.Field.builder().type("byte[]").name("items").build(),
            Member.Field.builder().type("SampleType").name("type").build())
        .nested(new Member.Nested(sampleType()))
        .method(
            Member.Method.builder()
                .public_()
                .static_()
                .returnType("byte[]")
                .name("toByteArray")
                .param("Sample", "s")
                .body(Statement.of("return new byte[] { 0 };"))
                .build(),
            Member.Method.builder()
                .public_()
                .static_()
                .returnType("Sample")
                .name("fromBytes")
                .param("byte[]", "b")
                .body(Statement.of("return new Sample(0, new byte[0], SampleType.A);"))
                .build())
        .build();
  }

  @Test
  void renders_a_top_level_record_with_a_nested_enum_and_line_bodies() throws Exception {
    String src =
        SourceRenderer.render(
            new JavaFile(
                "bijnagte.recordbuf.fixture",
                new Javadoc(""),
                sample(),
                new bijnagte.recordbuf.codegen.TypeCollector("bijnagte.recordbuf.fixture")));

    assertTrue(src.contains("public record Sample("), "record header: " + src);
    assertTrue(src.contains("byte[] items"), "a byte[] component: " + src);
    assertTrue(src.contains("public enum SampleType {"), "nested enum: " + src);
    assertTrue(src.contains("A(0)"), "enum constant with ctor arg: " + src);
    assertTrue(src.contains("private int number;"), "enum field: " + src);
    assertTrue(src.contains("this.number = number;"), "ctor body emitted as lines: " + src);
    assertTrue(src.contains("return number;"), "method body emitted as lines: " + src);
    assertFalse(src.contains("import"), "self-contained, no imports: " + src);

    assertCompiles(src);
  }

  /**
   * Compile the rendered source in-memory; fail loudly if there is no compiler (JRE) or any error.
   */
  /**
   * Compile the rendered source to a temp dir (never the current working directory); fail loudly on
   * a missing compiler (a JRE) or any diagnostic. The class output is redirected with {@code -d} so
   * no {@code .class} files land where the test runs.
   */
  private static void assertCompiles(String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK (not JRE) is required to run this structural test");
    var diags = new DiagnosticCollector<JavaFileObject>();
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/bijnagte/recordbuf/fixture/Sample.java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    Path out = Files.createTempDirectory("recordbuf-javac");
    boolean ok =
        compiler
            .getTask(null, null, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    assertTrue(ok, "rendered source must compile:\n" + source + "\n-- diagnostics --\n" + diags);
  }
}
