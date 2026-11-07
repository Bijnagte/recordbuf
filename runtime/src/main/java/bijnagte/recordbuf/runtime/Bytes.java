package bijnagte.recordbuf.runtime;

import java.util.Arrays;

/**
 * An immutable, content-equal holder for a protobuf {@code bytes} value.
 *
 * <p>Generated messages store a {@code bytes} field as a {@code Bytes} rather than a raw {@code
 * byte[]}. The wrapper owns its underlying array (copied on the way in via {@link #of(byte[])}), so
 * a generated record is immutable and value-equal through {@link #equals} / {@link #hashCode} even
 * though the array itself compares by reference. It is a {@code final class} rather than a record
 * precisely so the {@code byte[]} component it carries never trips the array-of-array
 * value-equality caveat an array component would.
 *
 * <p>This keeps generated records on the runtime's {@link bijnagte.recordbuf.runtime} surface and
 * dependency-light: no protobuf dependency is pulled into the type's API.
 */
public final class Bytes {

  /** The shared empty value, used for the proto3 singular-bytes default and for an empty decode. */
  public static final Bytes EMPTY = new Bytes(new byte[0]);

  private final byte[] value;

  private Bytes(byte[] value) {
    this.value = value;
  }

  /**
   * Wrap a byte array as a {@link Bytes}, owning a fresh copy so the result never aliases the
   * caller's array. A {@code null} or empty input yields the shared {@link #EMPTY} singleton.
   *
   * @param value the bytes to hold, or {@code null} (treated as empty)
   * @return an equal-value {@link Bytes} that owns its own array
   */
  public static Bytes of(byte[] value) {
    if (value == null || value.length == 0) return EMPTY;
    return new Bytes(Arrays.copyOf(value, value.length));
  }

  /**
   * Returns the shared, immutable empty {@code Bytes} value.
   *
   * @return the shared empty value.
   */
  public static Bytes empty() {
    return EMPTY;
  }

  /**
   * Returns the underlying raw byte content this value holds.
   *
   * @return the underlying byte content.
   *     <p>The array is the value this {@link Bytes} holds; treat it as read-only. A caller that
   *     must mutate the bytes should copy it first, not mutate in place.
   */
  public byte[] toByteArray() {
    return value;
  }

  /**
   * Returns the number of bytes this value holds.
   *
   * @return the number of bytes held.
   */
  public int size() {
    return value.length;
  }

  /**
   * Whether this value holds no bytes (the proto3 default for a singular {@code bytes}).
   *
   * @return whether this holds no bytes.
   */
  public boolean isEmpty() {
    return value.length == 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Bytes that)) return false;
    return Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return "Bytes[size=" + value.length + "]";
  }
}
