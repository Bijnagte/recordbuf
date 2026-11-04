package bijnagte.recordbuf.runtime;

/**
 * The generic unwrap surface for any oneof member value wrapper.
 *
 * <p>Per-message generated oneof subtypes (the per-member scalar records and the message-member
 * records) implement this interface so that consumers can extract the underlying value without
 * knowing the concrete wrapper type:
 *
 * <pre>
 * if (chosen instanceof Unwrapable&lt;?&gt; u) {
 *     Object v = u.value();
 *     // for a scalar member: the scalar itself (e.g. "blue", 42)
 *     // for a message member: the member record itself
 * }
 * </pre>
 *
 * <p>{@code <T>} is the value's Java type — for a scalar member it is the concrete scalar type
 * (e.g. {@code String}, {@code Integer}); for a message member it is the member record type itself.
 */
public interface Unwrapable<T> {
  /**
   * The value held by this oneof member wrapper.
   *
   * @return the scalar value for a scalar/enum member; the member record for a message member
   */
  T value();
}
