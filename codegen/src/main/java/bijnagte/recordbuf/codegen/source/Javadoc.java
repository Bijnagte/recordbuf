package bijnagte.recordbuf.codegen.source;

/**
 * A Javadoc block comment for a declaration -- the proto field/enum/doc messages. {@code null} or
 * blank renders nothing; otherwise it renders as a {@code /** ... *&#47;} block, HTML-escaped.
 */
public record Javadoc(String raw) {

  /** An empty javadoc that renders nothing -- the builder default when a declaration has no doc. */
  public static Javadoc empty() {
    return new Javadoc(null);
  }

  /** Whether this javadoc renders anything (i.e. is non-blank). */
  public boolean present() {
    return raw != null && !raw.isBlank();
  }
}
