package bijnagte.recordbuf.plugin;

import bijnagte.recordbuf.codegen.source.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Google Java Format (GJF)-backed {@link Formatter} -- the plugin's default formatter.
 *
 * <p>This is the concrete implementation of the {@code :recordbuf-codegen} {@link Formatter} SPI
 * that the {@link CodeGenerator} threads its rendered source through. It lives here (not in {@code
 * :recordbuf-codegen}) so that the formatter is a generation-time concern only: the module the
 * generated {@code .rec} records compile against stays formatter-free, and the generated surface
 * stays dependency-free (R22).
 *
 * <p>It uses GJF's <i>default</i> {@code Formatter} (Google style, cleanups enabled) so the
 * generated {@code .java} output matches the formatting Spotless applies with {@code
 * googleJavaFormat()} -- the R4 "spotless parity" bar. The formatter is only ever applied to
 * generated Java source ({@code .rec} records and their {@code package-info.java}); the Avro
 * bridge's {@code .avsc} (JSON) is emitted by Avro's external compiler and is not run through GJF.
 *
 * <p>GJF's own {@code com.google.googlejavaformat.java.Formatter} is referenced by fully-qualified
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

  private static final Logger LOG = LoggerFactory.getLogger(GoogleJavaFormatter.class);

  /** GJF's {@code Formatter} is stateless, so one instance serves all files. */
  private static final GoogleJavaFormatter INSTANCE = new GoogleJavaFormatter();

  /** The shared GJF formatter (GJF's {@code Formatter} is stateless, so one instance is reused). */
  private final com.google.googlejavaformat.java.Formatter inner;

  private GoogleJavaFormatter() {
    this.inner = new com.google.googlejavaformat.java.Formatter();
  }

  /** The shared, GJF-backed {@link Formatter} instance. */
  public static GoogleJavaFormatter getInstance() {
    return INSTANCE;
  }

  @Override
  public String format(String source) {
    try {
      String formatted = inner.formatSource(source);
      // The rendered-source dump the developer used to hand-strip as temporary debug: same
      // info, but gated behind slf4j's debug level (off by default, so the launched plugin
      // stays quiet).
      LOG.debug("formatted source ({} chars):\n{}", formatted.length(), formatted);
      return formatted;
    } catch (FormatterException e) {
      // GJF's checked FormatterException (a parse failure on the rendered source we generated) is
      // surfaced as an unchecked failure so the plugin fails loud rather than emitting malformed
      // output.
      throw new IllegalStateException(
          "Google Java Format could not format rendered source: " + e.getMessage(), e);
    }
  }
}
