package bijnagte.recordbuf.runtime.format;

import java.util.UUID;

/**
 * The built-in {@link Format} for {@link UUID}: the canonical hyphenated 36-character text ({@link
 * UUID#toString()} / {@link UUID#fromString(String)} round-trip). Ships as a {@link
 * java.util.ServiceLoader} provider of the runtime jar, so it resolves without any registration
 * code in a consuming project.
 */
public final class UuidFormat implements Format<UUID> {

  @Override
  public Class<UUID> type() {
    return UUID.class;
  }

  @Override
  public String format(UUID value) {
    return value.toString();
  }

  /**
   * UUID text is fixed-size — the canonical 36 characters, all ASCII — so the wire sizing pass
   * never renders the value.
   */
  @Override
  public int wireBytes(UUID value) {
    return 36;
  }

  /**
   * Parses canonical UUID text.
   *
   * @throws IllegalArgumentException when {@code text} is not a well-formed UUID (the empty string
   *     included — it is present, not absent)
   */
  @Override
  public UUID parse(String text) {
    return UUID.fromString(text);
  }
}
