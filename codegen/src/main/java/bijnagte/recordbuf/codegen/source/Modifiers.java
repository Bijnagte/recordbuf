package bijnagte.recordbuf.codegen.source;

/**
 * The leading modifier keywords for a declaration -- {@code public static final} for {@code static
 * final class GreeterCodec}, for example. Carries the already-joined keyword string so the renderer
 * only needs to know whether to append a trailing space before the following keyword.
 */
public record Modifiers(String prefix) {

  /** A declaration with no modifiers of its own. */
  public static Modifiers none() {
    return new Modifiers("");
  }

  /** Build a modifier prefix from its keywords, space-joined. */
  public static Modifiers of(String... kws) {
    return new Modifiers(String.join(" ", kws));
  }

  /** Whether there are no modifiers. */
  public boolean isEmpty() {
    return prefix.isEmpty();
  }

  /**
   * The modifier keywords followed by the one separating space the caller needs before the type /
   * keyword. Empty when there are no modifiers.
   */
  public String withSpace() {
    return isEmpty() ? "" : prefix + " ";
  }
}
