package bijnagte.recordbuf.plugin.writer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Minimal indented Java source writer.
 *
 * <p>Tracks current indentation and a list of leading comment header lines. Callers add code via
 * {@link #line(String) line(...)} and structural blocks via {@link #block(String, String, Consumer)
 * block(...)}. The header comment is emitted automatically before the body on {@link #toString()}.
 * A file's referenced types are collected by a shared {@link TypeImports} so {@link #emitImports()}
 * can render a short-name + import block instead of fully- qualified names.
 *
 * <p>This is intentionally simple — it is not a parser-aware Java code generator. We avoid line
 * continuations, generics spanning lines, and anything that requires the writer to "know" about the
 * language.
 */
public final class JavaWriter {
  private final StringBuilder sb;
  private int indent = 0;
  private final List<String> headerLines = new ArrayList<>();
  private final TypeImports imports;

  /** A writer with no import collection (used only for the collect pass). */
  public JavaWriter() {
    this(new TypeImports(""));
  }

  /**
   * A writer that collects the file's referenced types so they resolve to short names plus a
   * deduplicated, ordered import block. The collector is shared across the collection and render
   * passes, so a single instance is passed to both writers for a file.
   */
  public JavaWriter(TypeImports imports) {
    this.sb = new StringBuilder(1024);
    this.imports = imports;
  }

  /** The file's import collector, shared by the generators running on this writer. */
  public TypeImports imports() {
    return imports;
  }

  /** Add a multi-line comment header line, emitted before the body. */
  public JavaWriter header(String line) {
    headerLines.add(line);
    return this;
  }

  /**
   * Current indent level (0 = top of file). Callers may need it for manual indentation in
   * fixed-structure code.
   */
  public int indent() {
    return indent;
  }

  /** Set the indent level. */
  public JavaWriter indent(int n) {
    this.indent = n;
    return this;
  }

  /** Add a raw line of code (no indentation management beyond the current level). */
  public JavaWriter line() {
    sb.append('\n');
    return this;
  }

  public JavaWriter line(String text) {
    for (int i = 0; i < indent; i++) sb.append("    ");
    sb.append(text).append('\n');
    return this;
  }

  /**
   * Emit a JavaDoc block comment for an element at the current indent. A leading/trailing blank in
   * the source comment is trimmed, each line is HTML-escaped and prefixed with {@code * }, matching
   * recordbuf's clean-output style (no {@code <pre>} / {@code <code>} protobuf boilerplate). A
   * no-op when there is no (non-blank) comment.
   */
  public JavaWriter javadoc(@Nullable String rawText) {
    return javadoc(indent(), rawText);
  }

  /**
   * {@link #javadoc(String) javadoc} at a fixed indent level. Record components sit one level
   * deeper than their type's header, so the writer passes {@code w.indent() + 1} when documenting a
   * component.
   */
  public JavaWriter javadoc(int level, @Nullable String rawText) {
    if (rawText == null || rawText.isBlank()) return this;
    boolean first = true;
    for (String raw : rawText.strip().split("\n")) {
      for (int i = 0; i < level; i++) sb.append("    ");
      sb.append(first ? "/**" : " *");
      first = false;
      String c = escapeHtml(raw);
      if (c.isEmpty()) {
        sb.append('\n');
      } else {
        sb.append(' ').append(c).append('\n');
      }
    }
    for (int i = 0; i < level; i++) sb.append("    ");
    sb.append("*/\n");
    return this;
  }

  /**
   * HTML-escape the characters that would otherwise read as markup in a Javadoc block. Only {@code
   * & < >} are escaped; {@code @} is left intact (clean mode, no {@code <pre>} wrapper, so a bare
   * {@code @} is safe).
   */
  private static String escapeHtml(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * Run {@code body} with indentation incremented and the block wrapped in {@code open} / {@code
   * close} on their own lines.
   */
  public void block(String open, String close, Consumer<JavaWriter> body) {
    int prev = indent;
    indent++;
    line(open);
    body.accept(this);
    indent = prev;
    line(close);
  }

  /**
   * Emit the file's resolved import block: a blank line after the package statement, then each
   * import, then a blank line before the first declaration. A no-op when the file imports nothing.
   */
  public JavaWriter emitImports() {
    List<String> lines = imports.importLines();
    if (lines.isEmpty()) {
      line();
      return this;
    }
    line();
    for (String l : lines) line(l);
    line();
    return this;
  }

  @Override
  public String toString() {
    StringWriter out = new StringWriter();
    PrintWriter pw = new PrintWriter(out);

    // 1. Leading comment header (license, generated-by, etc.)
    if (!headerLines.isEmpty()) {
      pw.print("/*\n");
      for (String l : headerLines) pw.println(" * " + l);
      pw.println(" */\n");
    }

    // 2. Body (the caller emits the `package ...;` line and every
    //    declaration; we leave that to them so the structure stays
    //    flexible).
    pw.print(sb.toString());
    pw.flush();
    return out.toString();
  }
}
