package bijnagte.recordbuf.codegen.source;

/**
 * Formats rendered Java source into its canonical style.
 *
 * <p>The default {@link #IDENTITY} leaves source untouched, so the {@code :recordbuf-codegen}
 * module stays formatter-free: no concrete formatter lives on the module's main classpath, and the
 * generated {@code .rec} surface stays dependency-free (R22). A concrete impl (for example a Google
 * Java Format-backed one in the {@code :recordbuf-generator} module) is threaded through {@link
 * SourceRenderer#render(JavaFile, Formatter)} to post-process the rendered output.
 *
 * <p>The interface is checked-exception-free on purpose: the identity path never throws, and a
 * formatter that can fail (a GJF parse error, say) is expected to isolate its own checked exception
 * within its implementation and surface it as an unchecked failure, keeping this SPI small.
 */
@FunctionalInterface
public interface Formatter {

  /** Formats one rendered source string, returning its canonical form. */
  String format(String source);

  /** The identity formatter: leaves its input unchanged. */
  Formatter IDENTITY = source -> source;
}
