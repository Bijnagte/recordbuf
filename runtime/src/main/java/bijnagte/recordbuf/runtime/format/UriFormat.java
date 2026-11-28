package bijnagte.recordbuf.runtime.format;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The built-in {@link Format} for {@link URI}: the URI's text form ({@link URI#toString()} / {@link
 * URI#URI(String)} round-trip). Ships as a {@link java.util.ServiceLoader} provider of the runtime
 * jar, so it resolves without any registration code in a consuming project.
 */
public final class UriFormat implements Format<URI> {

  @Override
  public Class<URI> type() {
    return URI.class;
  }

  @Override
  public String format(URI value) {
    return value.toString();
  }

  /**
   * Parses URI text.
   *
   * @throws IllegalArgumentException when {@code text} is not a well-formed URI (the empty string
   *     included — it is present, not absent); the {@link URISyntaxException} is the cause
   */
  @Override
  public URI parse(String text) {
    try {
      return new URI(text);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("not a well-formed URI: \"" + text + "\"", e);
    }
  }
}
