package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.Empty;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * The proto3 JSON mapping for {@code google.protobuf.Empty}: the empty object {@code {}}. Reading
 * accepts any object (its members are skipped, as a parser ignores unknown fields) and yields the
 * shared {@link Empty#INSTANCE}. Registered per field by generated records and in {@link
 * RecordbufJsonModule}.
 */
public final class JsonEmpty {

  private JsonEmpty() {}

  /** Serializes {@link Empty} as {@code {}}. */
  public static final class Serializer extends ValueSerializer<Empty> {
    /** Creates the serializer. */
    public Serializer() {}

    @Override
    public Class<Empty> handledType() {
      return Empty.class;
    }

    @Override
    public void serialize(Empty value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeStartObject();
      gen.writeEndObject();
    }
  }

  /** Reads {@link Empty} from a JSON object. */
  public static final class Deserializer extends ValueDeserializer<Empty> {
    /** Creates the deserializer. */
    public Deserializer() {}

    @Override
    public Class<Empty> handledType() {
      return Empty.class;
    }

    @Override
    public Empty deserialize(JsonParser p, DeserializationContext ctxt) {
      if (!p.hasToken(JsonToken.START_OBJECT)) {
        return (Empty) ctxt.handleUnexpectedToken(Empty.class, p);
      }
      p.skipChildren();
      return Empty.INSTANCE;
    }
  }
}
