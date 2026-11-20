package bijnagte.recordbuf.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.nio.charset.StandardCharsets;

/**
 * The Jackson-side representation of a dynamic JSON payload (the U3 module of the 2026-11-20
 * Jackson plan): the field type behind a node-typed dynamic well-known type ({@code Any}, {@code
 * Value}) and the J6 Jackson×Avro envelope's {@code payload} property.
 *
 * <p>One canonical value, one canonical JSON text: the raw UTF-8 bytes are derived from the JSON
 * document at construction, so a {@code JsonPayload} can never carry bytes that disagree with its
 * textual form. Instances are immutable and safe to share.
 *
 * <p>Jackson itself serializes a {@code JsonPayload} as a single JSON string via the
 * {@code @JsonValue}/{@code @JsonCreator} pair — nested, not inlined (inlining an arbitrary user
 * JSON document would require dynamic delegation and is deliberately out of scope for v1).
 */
public record JsonPayload(String jsonText) {

  /**
   * Wraps the given JSON document text.
   *
   * @throws NullPointerException if the text is null
   */
  public JsonPayload {
    if (jsonText == null) {
      throw new NullPointerException("jsonText must not be null");
    }
  }

  /** Parses the given JSON bytes (UTF-8) into a payload. */
  @JsonCreator
  public static JsonPayload fromJson(String jsonText) {
    return new JsonPayload(jsonText);
  }

  /** The JSON document as text. */
  @JsonValue
  @Override
  public String jsonText() {
    return jsonText;
  }

  /** The JSON document as UTF-8 bytes — the form a binary envelope (J6) stores. */
  public byte[] bytes() {
    return jsonText.getBytes(StandardCharsets.UTF_8);
  }

  /** Convenience: build a payload whose document is the given raw text, verbatim. */
  public static JsonPayload of(String rawJsonText) {
    return new JsonPayload(rawJsonText);
  }
}
