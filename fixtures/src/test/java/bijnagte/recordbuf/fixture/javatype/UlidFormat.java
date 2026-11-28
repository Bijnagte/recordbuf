package bijnagte.recordbuf.fixture.javatype;

import bijnagte.recordbuf.runtime.format.Format;
import com.github.f4b6a3.ulid.Ulid;

/**
 * The format-field fixture's custom-format dogfood: a thin adapter over the {@code ulid-creator}
 * library's {@link Ulid} (the library's own validation backs the parse-failure branches). Declared
 * in the test classpath's {@code META-INF/services} file, so the runtime registry discovers it at
 * class init — no explicit registration anywhere in the fixture.
 */
public final class UlidFormat implements Format<Ulid> {

  @Override
  public Class<Ulid> type() {
    return Ulid.class;
  }

  @Override
  public String format(Ulid value) {
    return value.toString();
  }

  /**
   * ULID text is fixed-size — the canonical 26 Crockford characters, all ASCII — so the wire sizing
   * pass never renders the value.
   */
  @Override
  public int wireBytes(Ulid value) {
    return 26;
  }

  @Override
  public Ulid parse(String text) {
    try {
      return Ulid.from(text);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("not a ULID: " + text, e);
    }
  }
}
