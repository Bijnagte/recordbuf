package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.format.FormatRegistry;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * The Jackson mapping of a {@code recordbuf.java_type} format field: the field's declared Java
 * value renders as — and parses from — its canonical string form through the runtime {@link
 * FormatRegistry}. Unlike {@link JsonBytes} (a fixed type), the pair is contextual: the
 * contextualized instance captures the field's declared type from its {@link BeanProperty}, so the
 * same two classes cover every format field regardless of its named type. The generated record
 * attaches this constant pair to each format component — no per-field class is generated.
 *
 * <p>JSON {@code null} keeps Jackson's null handling (the record's compact constructor applies the
 * field's presence default); a present, unparseable string fails loud, wrapped in the context's
 * string exception. A type with no registered format fails loud naming the type (the registry's
 * failure).
 */
public final class JsonFormatField {

  private JsonFormatField() {}

  /**
   * Renders a format field's value as its canonical string form. The declared type is captured from
   * the field's bean-property context.
   */
  public static final class FormatSerializer extends ValueSerializer<Object> {
    @Nullable private final Class<?> type;

    /** Creates the serializer; {@link #createContextual} resolves the declared type. */
    public FormatSerializer() {
      this(null);
    }

    private FormatSerializer(@Nullable Class<?> type) {
      this.type = type;
    }

    @Override
    public ValueSerializer<?> createContextual(
        SerializationContext serializers, BeanProperty property) {
      Class<?> declared = declaredType(property);
      return declared == null ? this : new FormatSerializer(declared);
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeString(render(requireType(), value));
    }

    private Class<?> requireType() {
      if (type == null) {
        throw new IllegalStateException(NO_CONTEXT);
      }
      return type;
    }
  }

  /**
   * Reads a format field's value from its canonical string form through the registry; a present,
   * unparseable string fails loud (the registry's failure, wrapped in the context's string
   * exception).
   */
  public static final class FormatDeserializer extends ValueDeserializer<Object> {
    @Nullable private final Class<?> type;

    /** Creates the deserializer; {@link #createContextual} resolves the declared type. */
    public FormatDeserializer() {
      this(null);
    }

    private FormatDeserializer(@Nullable Class<?> type) {
      this.type = type;
    }

    @Override
    public ValueDeserializer<?> createContextual(
        DeserializationContext ctxt, BeanProperty property) {
      Class<?> declared = declaredType(property);
      return declared == null ? this : new FormatDeserializer(declared);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) {
      if (!p.hasToken(JsonToken.VALUE_STRING)) {
        return ctxt.handleUnexpectedToken(requireType(), p);
      }
      String text = p.getString();
      try {
        return parse(requireType(), text);
      } catch (RuntimeException e) {
        throw ctxt.weirdStringException(text, requireType(), e.getMessage());
      }
    }

    private Class<?> requireType() {
      if (type == null) {
        throw new IllegalStateException(NO_CONTEXT);
      }
      return type;
    }
  }

  /**
   * The declared type of the field's value from the bean-property context, or {@code null} when
   * Jackson resolves this (de)serializer outside a property context.
   */
  @Nullable
  private static Class<?> declaredType(BeanProperty property) {
    return property == null ? null : property.getType().getRawClass();
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
