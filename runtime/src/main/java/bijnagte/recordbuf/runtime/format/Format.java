package bijnagte.recordbuf.runtime.format;

import java.nio.charset.StandardCharsets;

/**
 * A bidirectional {@code String ⇄ T} converter for one Java type — the contract a {@code
 * recordbuf.java_type} format field's generated surface code leans on.
 *
 * <p>A format owns exactly the conversion to and from the field's string form (the wire, proto3
 * JSON, Jackson, and Avro surfaces all carry that string); it owns no wire encoding, no JSON shape,
 * and no Avro schema. The registry resolves a type's format — see {@link FormatRegistry} — and
 * generated code calls {@link #format(Object)} on the way out and {@link #parse(String)} on the way
 * in.
 *
 * @param <T> the Java type this format converts
 */
public interface Format<T> {

  /**
   * The Java type this format converts. The registry keys a {@link java.util.ServiceLoader}
   * discovered provider by it, so a provider names its type without any external registration step.
   */
  Class<T> type();

  /** Renders {@code value} in its canonical string form. */
  String format(T value);

  /**
   * The number of UTF-8 bytes {@link #format(Object)} renders {@code value} to.
   *
   * <p>Wire codecs size a field before they write it (measure, allocate, write); a format with a
   * fixed-size text form — a UUID's 36 characters, a ULID's 26 — overrides this with a constant so
   * the sizing pass never renders the value at all. The default renders and measures, which costs
   * exactly what the sizing pass would have paid for a variable-size form.
   */
  default int wireBytes(T value) {
    return format(value).getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Parses {@code text} into a value of this format's type.
   *
   * <p>Throws a {@link RuntimeException} when {@code text} is not a well-formed value — the empty
   * string included, it is a present-but-unparseable value, not an absent one. No {@code Optional},
   * no {@code null} return: failure is loud.
   */
  T parse(String text);
}
