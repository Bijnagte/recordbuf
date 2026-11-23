package bijnagte.recordbuf.json;

/**
 * The JSON rendering format for a temporal well-known type field — the one per-field knob of the
 * 2026-11-20 Jackson plan ({@code recordbuf.jackson_format} on a {@code google.protobuf.Timestamp}
 * / {@code google.protobuf.Duration} field).
 *
 * <p>Lives in the json runtime module rather than the dependency-free {@code codegen.source} spine:
 * generated code references the resolved (de)serializer <em>classes</em> (the nested arms of {@link
 * JsonTemporal} / {@link JavaTimeTemporal}), never this enum, so a generated-code consumer resolves
 * the mechanism from :recordbuf-json alone. The generator's <em>internal</em> model has its own
 * same-named enum ({@code bijnagte.recordbuf.plugin.model.JacksonTemporalFormat}); the two carry
 * the same constants and the generator maps between them at the annotation seam.
 */
public enum JacksonTemporalFormat {
  /**
   * RFC 3339 / ISO 8601 string with up to nanosecond precision (e.g. {@code 2026-11-20T12:34:56Z}).
   */
  JSON,
  /** The documented proto3 JSON shape: {@code {seconds: "<int64>", nanos: <int>}}. */
  CANONICAL
}
