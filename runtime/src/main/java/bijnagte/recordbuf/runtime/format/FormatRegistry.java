package bijnagte.recordbuf.runtime.format;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a Java type to the {@link Format} that converts it to and from its string form — the
 * registry every {@code recordbuf.java_type} format-field surface arm resolves through.
 *
 * <p>The registry seeds itself from {@link ServiceLoader} discovery at class init: each discovered
 * provider contributes its {@link Format#type()} as the key, so the built-in formats ship as a
 * {@code META-INF/services} provider list in the runtime jar and resolve without any registration
 * code in the consuming project. Discovery is one-shot at class init — a provider added to the
 * classpath afterwards is reachable only via {@link #register}.
 *
 * <p>{@link #register} stays as the programmatic path and overrides a discovered entry for the same
 * type (last write wins). Lookups for a type with no known format fail loudly, naming the type.
 */
public final class FormatRegistry {

  private static final Map<Class<?>, Format<?>> FORMATS = new ConcurrentHashMap<>();

  static {
    for (Format<?> format : ServiceLoader.load(Format.class)) {
      FORMATS.put(format.type(), format);
    }
  }

  private FormatRegistry() {}

  /**
   * Resolves the format for {@code type}.
   *
   * @throws IllegalStateException when no format is registered for the type; the message names the
   *     type and both ways of providing one
   */
  @SuppressWarnings("unchecked") // the map keys by Class<?>; the caller's Class<T> pins the capture
  public static <T> Format<T> format(Class<T> type) {
    Format<?> format = FORMATS.get(type);
    if (format == null) {
      throw new IllegalStateException(
          "no recordbuf format is registered for type "
              + type.getName()
              + " — register one on FormatRegistry or put a Format provider on the classpath");
    }
    return (Format<T>) format;
  }

  /**
   * Registers (or overrides) the format for {@code type}. Programmatic counterpart to the {@link
   * ServiceLoader} seeding; last write wins.
   */
  public static <T> void register(Class<T> type, Format<T> format) {
    FORMATS.put(type, format);
  }
}
