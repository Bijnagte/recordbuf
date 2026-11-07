package bijnagte.recordbuf.runtime;

/**
 * The native Java type a generated {@code google.protobuf.Empty} field resolves to.
 *
 * <p>{@code Empty} is a message with no fields — it is a bare presence marker. Per R5 / AE6 it
 * resolves to <em>this</em> new runtime type rather than to a generated record or to
 * protobuf-java's {@code com.google.protobuf.Empty}: the generated codec still emits the field's
 * wire frame, and this type is the record's in-memory representation. It carries no data, so it is
 * a single shared, non-null default instance — absent-on-decode and unset-on-encode both resolve to
 * {@link #INSTANCE}.
 */
public final class Empty {

  /**
   * The one and only {@code Empty} value; the non-null default for an unresolved {@code Empty}
   * field.
   */
  public static final Empty INSTANCE = new Empty();

  private Empty() {}

  /**
   * Returns the shared {@code Empty} value.
   *
   * @return the singleton instance, never {@code null}
   */
  public static Empty instance() {
    return INSTANCE;
  }
}
