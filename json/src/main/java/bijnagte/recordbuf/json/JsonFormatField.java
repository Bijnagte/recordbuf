package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.format.FormatRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * The Jackson mapping of a {@code recordbuf.java_type} format field: the field's declared Java
 * value renders as — and parses from — its canonical string form through the runtime {@link
 * FormatRegistry}. Unlike {@link JsonBytes} (a fixed type), the pair is contextual: the
 * contextualized instance captures the field's declared type from its {@link BeanProperty}, so the
 * same two classes cover every format field regardless of its named type. A repeated format field
 * declares a {@code List<T>}: it renders as a JSON array of the elements' canonical strings, and
 * parses back element by element. The generated record attaches this constant pair to each format
 * component — no per-field class is generated.
 *
 * <p>JSON {@code null} keeps Jackson's null handling (the record's compact constructor applies the
 * field's presence default); a present, unparseable string fails loud, wrapped in the context's
 * string exception. A type with no registered format fails loud naming the type (the registry's
 * failure).
 */
public final class JsonFormatField {

  private JsonFormatField() {}

  /**
   * Renders a format field's value as its canonical string form (a list value as an array of its
   * elements' forms). The declared type is captured from the field's bean-property context.
   */
  public static final class FormatSerializer extends ValueSerializer<Object> {
    @Nullable private final Class<?> type;
    @Nullable private final Class<?> elementType;

    /** Creates the serializer; {@link #createContextual} resolves the declared type. */
    public FormatSerializer() {
      this(null, null);
    }

    private FormatSerializer(@Nullable Class<?> type, @Nullable Class<?> elementType) {
      this.type = type;
      this.elementType = elementType;
    }

    @Override
    public ValueSerializer<?> createContextual(
        SerializationContext serializers, BeanProperty property) {
      Context c = resolveProperty(property);
      return c.type == null ? this : new FormatSerializer(c.type, c.elementType);
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext serializers)
        throws JacksonException {
      Class<?> type = requireType();
      if (type == List.class) {
        Class<?> element = requireElementType();
        gen.writeStartArray();
        for (Object v : (List<?>) value) {
          gen.writeString(render(element, v));
        }
        gen.writeEndArray();
        return;
      }
      gen.writeString(render(type, value));
    }

    private Class<?> requireType() {
      if (type == null) {
        throw new IllegalStateException(NO_CONTEXT);
      }
      return type;
    }

    private Class<?> requireElementType() {
      if (elementType == null) {
        throw new IllegalStateException(NO_CONTEXT);
      }
      return elementType;
    }
  }

  /**
   * Reads a format field's value from its canonical string form through the registry (a list value
   * element by element); a present, unparseable string fails loud (the registry's failure, wrapped
   * in the context's string exception).
   */
  public static final class FormatDeserializer extends ValueDeserializer<Object> {
    @Nullable private final Class<?> type;
    @Nullable private final Class<?> elementType;

    /** Creates the deserializer; {@link #createContextual} resolves the declared type. */
    public FormatDeserializer() {
      this(null, null);
    }

    private FormatDeserializer(@Nullable Class<?> type, @Nullable Class<?> elementType) {
      this.type = type;
      this.elementType = elementType;
    }

    @Override
    public ValueDeserializer<?> createContextual(
        DeserializationContext ctxt, BeanProperty property) {
      Context c = resolveProperty(property);
      return c.type == null ? this : new FormatDeserializer(c.type, c.elementType);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
      Class<?> type = requireType();
      if (type == List.class) {
        Class<?> element = requireElementType();
        if (p.currentToken() != JsonToken.START_ARRAY) {
          return ctxt.handleUnexpectedToken(List.class, p);
        }
        List<Object> out = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
          if (p.currentToken() != JsonToken.VALUE_STRING) {
            return ctxt.handleUnexpectedToken(element, p);
          }
          String text = p.getString();
          try {
            out.add(parse(element, text));
          } catch (RuntimeException e) {
            throw ctxt.weirdStringException(text, element, e.getMessage());
          }
        }
        return out;
      }
      if (!p.hasToken(JsonToken.VALUE_STRING)) {
        return ctxt.handleUnexpectedToken(type, p);
      }
      String text = p.getString();
      try {
        return parse(type, text);
      } catch (RuntimeException e) {
        throw ctxt.weirdStringException(text, type, e.getMessage());
      }
    }

    private Class<?> requireType() {
      if (type == null) {
        throw new IllegalStateException(NO_CONTEXT);
      }
      return type;
    }

    private Class<?> requireElementType() {
      if (elementType == null) {
        throw new IllegalStateException(NO_CONTEXT);
      }
      return elementType;
    }
  }

  /** The resolved declaration of a format property: its type, and its element type when a list. */
  private record Context(@Nullable Class<?> type, @Nullable Class<?> elementType) {
    static final Context NONE = new Context(null, null);
  }

  /**
   * The declared type of the field's value from the bean-property context, with its element type
   * when the property is a {@code List}; the {@code NONE} constant when Jackson resolves this
   * (de)serializer outside a property context.
   */
  private static Context resolveProperty(@Nullable BeanProperty property) {
    if (property == null) {
      return Context.NONE;
    }
    JavaType t = property.getType();
    Class<?> raw = t.getRawClass();
    if (raw == List.class && t.hasContentType()) {
      return new Context(raw, t.getContentType().getRawClass());
    }
    return new Context(raw, null);
  }

  private static final String NO_CONTEXT =
      "JsonFormatField requires a bean-property context to resolve the field's declared type";

  /** Renders {@code value} through the format registered for {@code type}. */
  private static String render(Class<?> type, Object value) {
    @SuppressWarnings("unchecked")
    Class<Object> asObject = (Class<Object>) type;
    return FormatRegistry.format(asObject).format(value);
  }

  /** Parses {@code text} through the format registered for {@code type}. */
  private static Object parse(Class<?> type, String text) {
    @SuppressWarnings("unchecked")
    Class<Object> asObject = (Class<Object>) type;
    return FormatRegistry.format(asObject).parse(text);
  }
}
