package bijnagte.recordbuf.codegen.source;

/**
 * A Javadoc block comment for a declaration -- the proto field/enum/doc messages. {@code null} or
 * blank renders nothing; otherwise it renders as a {@code /**}-delimited block comment.
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

  public static Builder builder() {
    return new Builder();
  }

  /**
   * A fluent builder that accumulates one or more text segments, concatenated (joined together)
   * into the javadoc rendered text.
   */
  public static final class Builder {
    private final StringBuilder text = new StringBuilder();

    private Builder() {}

    /**
     * Appends one or more text segments. All segments -- across this call and any others -- are
     * concatenated in order into the rendered javadoc; a single call may take several varargs
     * segments, so repeated calls are not required. Null is a no-op: a {@code null} vararg array or
     * any {@code null} element renders nothing, so a nullable source segment can pass straight
     * through (a {@code null} segment yields no text, exactly like a blank comment).
     */
    public Builder text(String... segments) {
      if (segments != null) {
        for (String segment : segments) {
          if (segment != null) {
            text.append(segment);
          }
        }
      }
      return this;
    }

    /** Joins the accumulated segments into a {@link Javadoc}; no segments renders nothing. */
    public Javadoc build() {
      return text.length() == 0 ? Javadoc.empty() : new Javadoc(text.toString());
    }
  }
}
