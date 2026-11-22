package bijnagte.recordbuf.json;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * JSON codecs for the temporal well-known types (the U3 module of the 2026-11-20 Jackson plan).
 *
 * <p>{@link Timestamp} and {@link Duration} have <em>no</em> bean-shaped getter/setter surface, so
 * a generated temporal field pairs its accessor with a Jackson (de)serializer class from here,
 * selected by the file/field temporal format (the U8 knob; {@code json} = the ISO string, {@code
 * canonical} = the documented proto3 JSON shape):
 *
 * <ul>
 *   <li>ISO: an RFC 3339 / ISO 8601 string with up to nanosecond precision (e.g. {@code
 *       2026-11-20T12:34:56.789012345Z}).
 *   <li>Canonical: an object with {@code seconds} (an int64-as-string) and {@code nanos} (an int).
 * </ul>
 *
 * <p>Each deserializer accepts <em>both</em> renderings, so a value written with one format reads
 * back with the other. Jackson instantiates (de)serializers through no-arg constructors, so each is
 * a public class with one.
 */
public final class JsonTemporal {

  private JsonTemporal() {}

  /** RFC 3339 timestamp: {@code yyyy-MM-ddTHH:mm:ss[.nnnnnnnnn]Z}. */
  public static String toIso(Timestamp t) {
    Instant i = Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    ZonedDateTime z = i.atZone(ZoneOffset.UTC);
    String base = z.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    if (t.getNanos() != 0) {
      base = base + DateTimeFormatter.ofPattern(".SSSSSSSSS").format(z);
    }
    return base + "Z";
  }

  /** ISO 8601 duration: {@code P...DT...} with fractional seconds. */
  public static String toIso(Duration d) {
    return java.time.Duration.ofSeconds(d.getSeconds(), d.getNanos()).toString();
  }

  /** Parse an RFC 3339 timestamp string back into the message. */
  public static Timestamp parseIsoTimestamp(String s) {
    Instant i = Instant.from(OffsetDateTime.parse(s));
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }

  /** Parse an ISO 8601 duration string back into the message. */
  public static Duration parseIsoDuration(String s) {
    java.time.Duration jd = java.time.Duration.parse(s);
    return Duration.newBuilder().setSeconds(jd.getSeconds()).setNanos(jd.getNano()).build();
  }

  static void writeCanonical(Timestamp t, JsonGenerator gen) {
    gen.writeStartObject();
    gen.writeName("seconds").writeString(Long.toString(t.getSeconds()));
    gen.writeName("nanos").writeNumber(t.getNanos());
    gen.writeEndObject();
  }

  static void writeCanonical(Duration d, JsonGenerator gen) {
    gen.writeStartObject();
    gen.writeName("seconds").writeString(Long.toString(d.getSeconds()));
    gen.writeName("nanos").writeNumber(d.getNanos());
    gen.writeEndObject();
  }

  static Timestamp readTimestamp(String text, JsonParser p) {
    if (p.currentToken() == JsonToken.START_OBJECT) {
      long seconds = 0;
      int nanos = 0;
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentName().equals("seconds")) {
          p.nextToken();
          seconds = Long.parseLong(p.getValueAsString());
        } else if (p.currentName().equals("nanos")) {
          p.nextToken();
          nanos = (int) p.getLongValue();
        } else {
          p.skipChildren();
        }
      }
      return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
    }
    return parseIsoTimestamp(text);
  }

  static Duration readDuration(String text, JsonParser p) {
    if (p.currentToken() == JsonToken.START_OBJECT) {
      long seconds = 0;
      int nanos = 0;
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentName().equals("seconds")) {
          p.nextToken();
          seconds = Long.parseLong(p.getValueAsString());
        } else if (p.currentName().equals("nanos")) {
          p.nextToken();
          nanos = (int) p.getLongValue();
        } else {
          p.skipChildren();
        }
      }
      return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
    }
    return parseIsoDuration(text);
  }

  /** Serializes a {@link Timestamp} as an RFC 3339 / ISO 8601 string. */
  public static final class TimestampIsoSerializer extends ValueSerializer<Timestamp> {

    @Override
    public Class<Timestamp> handledType() {
      return Timestamp.class;
    }

    @Override
    public void serialize(Timestamp value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeString(toIso(value));
    }
  }

  /** Reads a {@link Timestamp} given as an ISO string or the canonical object. */
  public static final class TimestampIsoDeserializer extends ValueDeserializer<Timestamp> {

    @Override
    public Class<Timestamp> handledType() {
      return Timestamp.class;
    }

    @Override
    public Timestamp deserialize(JsonParser p, DeserializationContext ctxt) {
      return readTimestamp(p.getString(), p);
    }
  }

  /** Serializes a {@link Timestamp} in the documented proto3 canonical shape. */
  public static final class TimestampCanonicalSerializer extends ValueSerializer<Timestamp> {

    @Override
    public Class<Timestamp> handledType() {
      return Timestamp.class;
    }

    @Override
    public void serialize(Timestamp value, JsonGenerator gen, SerializationContext serializers) {
      writeCanonical(value, gen);
    }
  }

  /** Reads a {@link Timestamp} given as the canonical object or an ISO string. */
  public static final class TimestampCanonicalDeserializer extends ValueDeserializer<Timestamp> {

    @Override
    public Class<Timestamp> handledType() {
      return Timestamp.class;
    }

    @Override
    public Timestamp deserialize(JsonParser p, DeserializationContext ctxt) {
      return readTimestamp(p.getString(), p);
    }
  }

  /** Serializes a {@link Duration} as an ISO 8601 string. */
  public static final class DurationIsoSerializer extends ValueSerializer<Duration> {

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeString(toIso(value));
    }
  }

  /** Reads a {@link Duration} given as an ISO string or the canonical object. */
  public static final class DurationIsoDeserializer extends ValueDeserializer<Duration> {

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) {
      return readDuration(p.getString(), p);
    }
  }

  /** Serializes a {@link Duration} in the documented proto3 canonical shape. */
  public static final class DurationCanonicalSerializer extends ValueSerializer<Duration> {

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializationContext serializers) {
      writeCanonical(value, gen);
    }
  }

  /** Reads a {@link Duration} given as the canonical object or an ISO string. */
  public static final class DurationCanonicalDeserializer extends ValueDeserializer<Duration> {

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) {
      return readDuration(p.getString(), p);
    }
  }

  /** The (de)serializer class pair for the given temporal message type and format. */
  public static class Coords {
    public final Class<? extends ValueSerializer<?>> serializer;
    public final Class<? extends ValueDeserializer<?>> deserializer;

    public Coords(
        Class<? extends ValueSerializer<?>> serializer,
        Class<? extends ValueDeserializer<?>> deserializer) {
      this.serializer = serializer;
      this.deserializer = deserializer;
    }
  }

  /** Resolve the temporal (de)serializer classes for a message type and format. */
  public static Coords forType(String wktFullName, JacksonTemporalFormat format) {
    return switch (wktFullName) {
      case "google.protobuf.Timestamp" ->
          new Coords(
              format == JacksonTemporalFormat.JSON
                  ? TimestampIsoSerializer.class
                  : TimestampCanonicalSerializer.class,
              format == JacksonTemporalFormat.JSON
                  ? TimestampIsoDeserializer.class
                  : TimestampCanonicalDeserializer.class);
      case "google.protobuf.Duration" ->
          new Coords(
              format == JacksonTemporalFormat.JSON
                  ? DurationIsoSerializer.class
                  : DurationCanonicalSerializer.class,
              format == JacksonTemporalFormat.JSON
                  ? DurationIsoDeserializer.class
                  : DurationCanonicalDeserializer.class);
      default -> throw new IllegalArgumentException("not a temporal WKT: " + wktFullName);
    };
  }
}
