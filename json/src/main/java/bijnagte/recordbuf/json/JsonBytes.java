package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.Bytes;
import java.util.Base64;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * The proto3 JSON mapping for {@code bytes}: a {@link Bytes} value is a base64 string. This is the
 * single implementation behind both registration paths — the per-field {@code @JsonSerialize} /
 * {@code @JsonDeserialize} pair a generated record's {@code bytes}-family component carries, and
 * the auto-registered {@link RecordbufJsonModule} that covers every other {@code Bytes} on the
 * classpath. Jackson instantiates (de)serializers through no-arg constructors, so each is a public
 * class with one.
 */
public final class JsonBytes {

  private JsonBytes() {}

  /** Serializes {@link Bytes} as a standard (padded) base64 string. */
  public static final class B64Serializer extends ValueSerializer<Bytes> {
    /** Creates the serializer. */
    public B64Serializer() {}

    @Override
    public Class<Bytes> handledType() {
      return Bytes.class;
    }

    @Override
    public void serialize(Bytes value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeString(Base64.getEncoder().encodeToString(value.toByteArray()));
    }
  }

  /**
   * Reads {@link Bytes} from a base64 string — standard or URL-safe alphabet, padded or not, as the
   * proto3 JSON mapping accepts. JSON {@code null} keeps Jackson's null handling (the record's
   * compact constructor applies the field's presence default).
   */
  public static final class B64Deserializer extends ValueDeserializer<Bytes> {
    /** Creates the deserializer. */
    public B64Deserializer() {}

    @Override
    public Class<Bytes> handledType() {
      return Bytes.class;
    }

    @Override
    public Bytes deserialize(JsonParser p, DeserializationContext ctxt) {
      if (!p.hasToken(JsonToken.VALUE_STRING)) {
        return (Bytes) ctxt.handleUnexpectedToken(Bytes.class, p);
      }
      String text = p.getString();
      try {
        return Bytes.of(decode(text));
      } catch (IllegalArgumentException e) {
        throw ctxt.weirdStringException(text, Bytes.class, "not a base64 string");
      }
    }
  }

  /** Decodes standard or URL-safe base64; padding is optional in both. */
  static byte[] decode(String text) {
    boolean urlSafe = text.indexOf('-') >= 0 || text.indexOf('_') >= 0;
    return (urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(text);
  }
}
