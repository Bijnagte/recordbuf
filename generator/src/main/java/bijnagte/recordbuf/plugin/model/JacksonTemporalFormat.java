package bijnagte.recordbuf.plugin.model;

/**
 * The per-field {@code recordbuf.jackson_format} option as resolved from the descriptor — the JSON
 * rendering for a temporal WKT field ({@code google.protobuf.Timestamp} / {@code Duration}) on a
 * {@code recordbuf.jackson}-enabled file (2026-11-20 Jackson plan).
 *
 * <p>{@link #JSON} is ISO-8601 text (the default when the option is absent on an enabled file);
 * {@link #CANONICAL} is the proto3 JSON-mapping rendering (3/6/9-group fractional seconds with a
 * {@code Z} suffix for {@code Instant}; {@code <n>}s text for {@code Duration}).
 */
public enum JacksonTemporalFormat {

  /** ISO-8601 text rendering. */
  JSON,
  /** Proto3 JSON-mapping (canonical) rendering. */
  CANONICAL;

  /**
   * Parse a raw option value, failing loud on an unrecognized one (an unknown value is an authoring
   * error, never silently coerced).
   */
  public static JacksonTemporalFormat parse(String raw) {
    return switch (raw) {
      case "json" -> JSON;
      case "canonical" -> CANONICAL;
      default ->
          throw new IllegalStateException(
              "recordbuf.jackson_format: unknown value \""
                  + raw
                  + "\" (expected \"json\" or \"canonical\")");
    };
  }
}
