package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.FieldMask;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * The proto3 JSON mapping for {@code google.protobuf.FieldMask}: one string of comma-joined paths,
 * each path's segments in lowerCamelCase ({@code "foo_bar.baz"} &rarr; {@code "fooBar.baz"}).
 * Reading converts each segment back to snake_case before {@link FieldMask#of} normalizes the set.
 * As with protobuf-java's own mapping, a snake_case segment that does not survive the camel round
 * trip (e.g. an underscore before a digit) is not representable. Registered per field by generated
 * records and in {@link RecordbufJsonModule}.
 */
public final class JsonFieldMask {

  private JsonFieldMask() {}

  /** Serializes a {@link FieldMask} as its proto3 JSON string. */
  public static final class Serializer extends ValueSerializer<FieldMask> {
    /** Creates the serializer. */
    public Serializer() {}

    @Override
    public Class<FieldMask> handledType() {
      return FieldMask.class;
    }

    @Override
    public void serialize(FieldMask value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeString(toJson(value));
    }
  }

  /** Reads a {@link FieldMask} from its proto3 JSON string. */
  public static final class Deserializer extends ValueDeserializer<FieldMask> {
    /** Creates the deserializer. */
    public Deserializer() {}

    @Override
    public Class<FieldMask> handledType() {
      return FieldMask.class;
    }

    @Override
    public FieldMask deserialize(JsonParser p, DeserializationContext ctxt) {
      if (!p.hasToken(JsonToken.VALUE_STRING)) {
        return (FieldMask) ctxt.handleUnexpectedToken(FieldMask.class, p);
      }
      return fromJson(p.getString());
    }
  }

  /** The proto3 JSON string for a mask: comma-joined, lowerCamelCase paths. */
  static String toJson(FieldMask mask) {
    List<String> camel = new ArrayList<>();
    for (String path : mask.paths()) {
      camel.add(snakeToCamel(path));
    }
    return String.join(",", camel);
  }

  /** The mask a proto3 JSON string names; an empty string is the empty mask. */
  static FieldMask fromJson(String json) {
    List<String> paths = new ArrayList<>();
    for (String path : json.split(",", -1)) {
      paths.add(camelToSnake(path.strip()));
    }
    return FieldMask.of(paths);
  }

  private static String snakeToCamel(String path) {
    StringBuilder out = new StringBuilder(path.length());
    boolean upper = false;
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '_') {
        upper = true;
      } else {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return out.toString();
  }

  private static String camelToSnake(String path) {
    StringBuilder out = new StringBuilder(path.length() + 4);
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (Character.isUpperCase(c)) {
        out.append('_').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
