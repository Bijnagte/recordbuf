package bijnagte.recordbuf.plugin.model;

/**
 * The per-field {@code recordbuf.jackson_format} option as resolved from the descriptor — the JSON
 * rendering for a temporal WKT field ({@code google.protobuf.Timestamp} / {@code Duration}) on a
 * {@code recordbuf.jackson}-enabled file (2026-11-20 Jackson plan).
 *
 * <p>{@link #JSON} is ISO-8601 text (the default when the option is absent on an enabled file);
 * {@link #CANONICAL} is the documented proto3 canonical shape — the {@code {seconds: "<int64>",
 * nanos: <int>}} object (the field's WKT message form in JSON).
 */
public enum JacksonTemporalFormat {

  /** ISO-8601 text rendering. */
  JSON,
  /** The documented proto3 canonical shape: the {seconds, nanos} object. */
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
