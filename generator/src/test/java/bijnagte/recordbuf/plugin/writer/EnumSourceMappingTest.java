package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.EnumSpec;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.plugin.model.Enum;
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
 * The M4-C2a-ext gate: map a proto {@link Enum} model into the {@code source} AST through {@link
 * EnumSourceMapping}, render it with {@link SourceRenderer}, and compile the rendered source
 * in-process (mirroring {@code JavaSourceTest}'s structural-compile pattern). This proves the FULL
 * enum -- header, numbered constants, {@code number} field, constructor, {@code getNumber()}, and
 * the reverse-lookup cluster -- the {@code BY_NUMBER} field + its {@code static {}} initializer +
 * the {@code forNumber} accessor, with their {@code java.util.Map}/{@code java.util.HashMap}
 * imports -- rounds through the pure-Java AST and compiles.
 */
class EnumSourceMappingTest {

  /** The package the rendered enum lives in; also the {@link TypeCollector} declaring package. */
  private static final String PKG = "bijnagte.recordbuf.enumcore";

  /**
   * A three-constant enum, {@code GREEN} carrying a proto comment, and the enum itself not
   * deprecated.
   */
  private static Enum color() {
    return new Enum(
        "Color",
        List.of(
            new Enum.EnumConstant("RED", 0, false, null),
            new Enum.EnumConstant("GREEN", 1, false, "the green constant"),
            new Enum.EnumConstant("BLUE", 2, false, null)),
        false,
        null,
        List.of());
  }

  @Test
  void maps_the_proto_enum_through_the_source_ast_and_compiles() throws Exception {
    TypeCollector ci = new TypeCollector(PKG);
    EnumSpec e = EnumSourceMapping.toSource(color(), PKG, false);
    String src = SourceRenderer.render(new JavaFile(PKG, Javadoc.builder().build(), e, ci));

    assertTrue(src.contains("public enum Color {"), "enum header: " + src);
    assertTrue(src.contains("RED(0)"), "a constant with its number: " + src);
    assertTrue(src.contains("GREEN(1)"), "a second constant with its number: " + src);
    assertTrue(src.contains("BLUE(2)"), "a third constant: " + src);
    assertTrue(src.contains("the green constant"), "the constant's proto comment: " + src);
    assertTrue(src.contains("private final int number;"), "the number field: " + src);
    assertTrue(
        src.contains("this.number = number;"), "the constructor body emitted as lines: " + src);
    assertTrue(src.contains("public int getNumber()"), "the getNumber accessor: " + src);
    assertTrue(src.contains("return number;"), "the getNumber body emitted as lines: " + src);

    // The reverse-lookup cluster -- M4-C2a-ext's fold-in, now rendered + compiled.
    assertTrue(
        src.contains("private static final Map<Integer, Color> BY_NUMBER = new HashMap<>();"),
        "the BY_NUMBER field with its pre-resolved generic type + initializer: " + src);
    assertTrue(src.contains("BY_NUMBER.put(0, RED)"), "a static-block entry: " + src);
    assertTrue(src.contains("BY_NUMBER.put(2, BLUE)"), "the last static-block entry: " + src);
    assertTrue(
        src.contains("public static Color forNumber(int number)"),
        "the forNumber accessor: " + src);
    assertTrue(
        src.contains("return BY_NUMBER.getOrDefault(number, RED);"),
        "the forNumber body returns the first constant: " + src);
    assertTrue(src.contains("import java.util.HashMap;"), "the HashMap import: " + src);
    assertTrue(src.contains("import java.util.Map;"), "the Map import: " + src);

    assertCompiles(src);
  }

  /**
   * A merged constant ({@code MOO}, with alias {@code moo}) carries {@code @JsonAlias} only when
   * the file has the Jackson surface, so an enum in a file without it stays free of Jackson.
   */
  @Test
  void aliasedConstant_carriesJsonAliasOnlyWithTheJacksonSurface() {
    Enum aliased =
        new Enum(
            "Flavor",
            List.of(
                new Enum.EnumConstant("ZERO", 0, false, null),
                new Enum.EnumConstant("MOO", 2, false, null, List.of("moo"))),
            false,
            null,
            List.of());
    TypeCollector withJackson = new TypeCollector(PKG);
    String jackson =
        SourceRenderer.render(
            new JavaFile(
                PKG,
                Javadoc.builder().build(),
                EnumSourceMapping.toSource(aliased, PKG, true),
                withJackson));
    TypeCollector withoutJackson = new TypeCollector(PKG);
    String plain =
        SourceRenderer.render(
            new JavaFile(
                PKG,
                Javadoc.builder().build(),
                EnumSourceMapping.toSource(aliased, PKG, false),
                withoutJackson));

    assertTrue(jackson.contains("@JsonAlias(\"moo\")"), "the alias on MOO: " + jackson);
    assertTrue(jackson.contains("MOO(2)"), "the merged constant keeps its number: " + jackson);
    assertFalse(plain.contains("JsonAlias"), "no Jackson annotation without the surface: " + plain);
    assertTrue(plain.contains("MOO(2)"), "the constant is the same without the surface: " + plain);
  }

  /**
   * Compile the rendered source to a temp dir (never the current working directory); fail loudly on
   * a missing compiler (a JRE) or any diagnostic. Class output is redirected with {@code -d} so no
   * {@code .class} files land where the test runs.
   */
  private static void assertCompiles(String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("a JDK (not JRE) is required to run this structural test");
    }
    var diags = new DiagnosticCollector<JavaFileObject>();
    JavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///b/bijnagte/recordbuf/enumcore/Color.java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    Path out = Files.createTempDirectory("recordbuf-enum-javac");
    boolean ok =
        compiler
            .getTask(null, null, diags, List.of("-d", out.toString()), null, List.of(unit))
            .call();
    assertTrue(
        ok,
        "rendered enum source must compile:\n"
            + source
            + "\n-- diagnostics --\n"
            + diagnostics(diags));
  }

  /** The compiler diagnostics as one line each, for a useful failure message. */
  private static String diagnostics(DiagnosticCollector<JavaFileObject> diags) {
    StringBuilder sb = new StringBuilder();
    for (var d : diags.getDiagnostics()) {
      sb.append(d.getKind()).append(' ').append(d.getMessage(null)).append('\n');
    }
    return sb.toString();
  }
}
