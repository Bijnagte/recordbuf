package bijnagte.recordbuf.plugin;

import bijnagte.recordbuf.codegen.source.Formatter;
import com.google.googlejavaformat.java.FormatterException;

/**
 * The Google Java Format (GJF)-backed {@link Formatter} -- the plugin's default formatter.
 *
 * <p>This is the concrete implementation of the {@code :recordbuf-codegen} {@link Formatter} SPI
 * that the {@link CodeGenerator} threads its rendered source through. It lives here (not in {@code
 * :recordbuf-codegen}) so that the formatter is a generation-time concern only: the module the
 * generated {@code .rec} records compile against stays formatter-free, and the generated surface
 * stays dependency-free (R22).
 *
 * <p>GJF's own {@link com.google.googlejavaformat.java.Formatter} is referenced by fully-qualified
 * name on purpose: it collides with our SPI's {@link Formatter} simple name, and qualifying keeps
 * this class able to {@code implements Formatter} cleanly.
 *
 * <p>GJF parses source reflectively into a {@code com.sun.tools.javac} compiler tree, so the JVM
 * that runs this class must export the {@code jdk.compiler} packages GJF pokes. The {@code
 * :recordbuf-generator} build wires the required {@code --add-exports jdk.compiler/...} onto its
 * {@code test} task and its {@code application} launch; a JVM that omits them makes GJF fail at
 * runtime.
 */
public final class GoogleJavaFormatter implements Formatter {

  /** The shared instance -- GJF's {@code Formatter} is stateless, so one instance serves all. */
  private static final GoogleJavaFormatter INSTANCE = new GoogleJavaFormatter();

  private final com.google.googlejavaformat.java.Formatter gjf =
      new com.google.googlejavaformat.java.Formatter();

  /** The shared, GJF-backed {@link Formatter} instance. */
  public static GoogleJavaFormatter getInstance() {
    return INSTANCE;
  }

  @Override
  public String format(String source) {
    try {
      return gjf.formatSource(source);
    } catch (FormatterException e) {
      // The Formatter SPI is checked-exception-free; GJF's checked FormatterException (a parse
      // failure on the rendered source we generated) is surfaced as an unchecked failure so the
      // plugin fails loud rather than emitting malformed output.
      throw new IllegalStateException("Google Java Format could not format rendered source", e);
    }
  }
}
