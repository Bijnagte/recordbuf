package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.FieldMask;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * The JSON mapping for {@code google.protobuf.FieldMask}: an array of paths, each path's segments
 * in lowerCamelCase -- the generated records' JSON property names ({@code "user.display_name"}
 * &rarr; {@code "user.displayName"}). Reading converts each segment back to snake_case before
 * {@link FieldMask#of} normalizes the set. A snake_case segment that does not survive the camel
 * round trip (an underscore before a digit or another underscore: {@code line_1} would read back as
 * {@code line1}) is not representable, so writing it fails rather than silently naming another
 * field. Registered per field by generated records and in {@link RecordbufJsonModule}.
 */
public final class JsonFieldMask {

  private JsonFieldMask() {}

  /** Serializes a {@link FieldMask} as an array of lowerCamel paths. */
  public static final class Serializer extends ValueSerializer<FieldMask> {
    /** Creates the serializer. */
    public Serializer() {}

    @Override
    public Class<FieldMask> handledType() {
      return FieldMask.class;
    }

    @Override
    public void serialize(FieldMask value, JsonGenerator gen, SerializationContext serializers) {
      List<String> paths;
      try {
        paths = jsonPaths(value);
      } catch (IllegalArgumentException e) {
        throw DatabindException.from(gen, e.getMessage());
      }
      gen.writeStartArray();
      for (String path : paths) {
        gen.writeString(path);
      }
      gen.writeEndArray();
    }
  }

  /** Reads a {@link FieldMask} from an array of lowerCamel (or snake_case) path strings. */
  public static final class Deserializer extends ValueDeserializer<FieldMask> {
    /** Creates the deserializer. */
    public Deserializer() {}

    @Override
    public Class<FieldMask> handledType() {
      return FieldMask.class;
    }

    @Override
    public FieldMask deserialize(JsonParser p, DeserializationContext ctxt) {
      if (!p.hasToken(JsonToken.START_ARRAY)) {
        return (FieldMask) ctxt.handleUnexpectedToken(FieldMask.class, p);
      }
      List<String> paths = new ArrayList<>();
      while (p.nextToken() != JsonToken.END_ARRAY) {
        if (!p.hasToken(JsonToken.VALUE_STRING)) {
          return (FieldMask) ctxt.handleUnexpectedToken(FieldMask.class, p);
        }
        paths.add(p.getString());
      }
      return fromJsonPaths(paths);
    }
  }

  /**
   * The mask's paths as JSON paths: each segment in lowerCamelCase, in the mask's canonical order.
   *
   * @param mask the mask
   * @return the JSON paths
   * @throws IllegalArgumentException if a path does not survive the camelCase round trip
   */
  public static List<String> jsonPaths(FieldMask mask) {
    List<String> camel = new ArrayList<>();
    for (String path : mask.paths()) {
      String json = snakeToCamel(path);
      if (!camelToSnake(json).equals(path)) {
        throw new IllegalArgumentException(
            "field mask path \""
                + path
                + "\" cannot be written as a lowerCamel JSON path (it would read back as \""
                + camelToSnake(json)
                + "\")");
      }
      camel.add(json);
    }
    return camel;
  }

  /**
   * The mask the JSON paths name (the inverse of {@link #jsonPaths}): each segment back to
   * snake_case, then normalized by {@link FieldMask#of}.
   *
   * @param jsonPaths lowerCamel (or already snake_case) paths
   * @return the mask
   */
  public static FieldMask fromJsonPaths(List<String> jsonPaths) {
    List<String> paths = new ArrayList<>();
    for (String path : jsonPaths) {
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
