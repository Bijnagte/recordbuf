package bijnagte.recordbuf.codegen.source;

/**
 * The JSON rendering format for a temporal well-known type field — the one per-field knob of the
 * 2026-11-20 Jackson plan ({@code recordbuf.jackson_format} on a {@code google.protobuf.Timestamp}
 * / {@code google.protobuf.Duration} field).
 *
 * <p>Lives in the dependency-free {@code codegen.source} spine because the <em>generated</em> code
 * switches on it: the generated temporal field codec (the
 * {@code @JsonSerialize}/{@code @JsonDeserialize} companion) reads this enum, so every consumer of
 * generated code resolves it from :recordbuf-codegen alone. The generator's <em>internal</em> model
 * has its own same-named enum ({@code bijnagte.recordbuf.plugin.model.JacksonTemporalFormat}); the
 * two are deliberately separate types — the plugin never ships, and generated code never depends on
 * it.
 */
public enum JacksonTemporalFormat {
  /**
   * RFC 3339 / ISO 8601 string with up to nanosecond precision (e.g. {@code 2026-11-20T12:34:56Z}).
   */
  JSON,
  /** The documented proto3 JSON shape: {@code {seconds: "<int64>", nanos: <int>}}. */
  CANONICAL
}
