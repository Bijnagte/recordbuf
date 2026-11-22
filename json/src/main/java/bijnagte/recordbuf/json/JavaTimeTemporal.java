package bijnagte.recordbuf.json;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * The java.time side of the temporal mechanism (U8 of the 2026-11-20 Jackson plan): the
 * (de)serializer arms a generated record's temporal field references. A {@code .rec} record holds
 * the <em>java.time</em> types (the codec WKT mapping resolves {@code Timestamp} to {@link
 * Instant}, {@code Duration} to {@link Duration}), so the record-side arms live here, alongside
 * {@link JsonTemporal}, which covers the protobuf WKT message types on the codec's dynamic path;
 * both expose the same two renderings ({@link JacksonTemporalFormat#JSON} — an ISO string — and
 * {@link JacksonTemporalFormat#CANONICAL} — the documented proto3 canonical {@code {seconds,
 * nanos}} object).
 *
 * <p>Each deserializer accepts <em>all</em> shapes — the canonical object, a bare seconds number,
 * or a duration/timestamp string (both the ISO form with its {@code PT} prefix and the proto3
 * JSON-mapping form without one) — so a value written with one format reads back with the other.
 * Jackson instantiates (de)serializers through no-arg constructors, so each is a public class with
 * one.
 */
public final class JavaTimeTemporal {

  private JavaTimeTemporal() {}

  /** Serializes an {@link Instant} as an RFC 3339 / ISO 8601 string. */
  public static final class InstantIsoSerializer extends ValueSerializer<Instant> {
    public InstantIsoSerializer() {}

    @Override
    public Class<Instant> handledType() {
      return Instant.class;
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeString(value.toString());
    }
  }

  /** Reads an {@link Instant} given as an ISO string, the canonical object, or a seconds number. */
  public static final class InstantIsoDeserializer extends ValueDeserializer<Instant> {
    public InstantIsoDeserializer() {}

    @Override
    public Class<Instant> handledType() {
      return Instant.class;
    }

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) {
      return readInstant(p);
    }
  }

  /** Serializes an {@link Instant} in the documented proto3 canonical shape. */
  public static final class InstantCanonicalSerializer extends ValueSerializer<Instant> {
    public InstantCanonicalSerializer() {}

    @Override
    public Class<Instant> handledType() {
      return Instant.class;
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext serializers) {
      // Instant holds the FLOOR seconds with a non-negative nano-of-second fraction (unlike
      // Duration's truncating getSeconds()/getNano()), so the pair is the canonical shape as-is.
      long seconds = value.getEpochSecond();
      gen.writeStartObject();
      gen.writeName("seconds").writeString(Long.toString(seconds));
      gen.writeName("nanos").writeNumber(value.getNano());
      gen.writeEndObject();
    }
  }

  /** Reads an {@link Instant} given as the canonical object, an ISO string, or a number. */
  public static final class InstantCanonicalDeserializer extends ValueDeserializer<Instant> {
    public InstantCanonicalDeserializer() {}

    @Override
    public Class<Instant> handledType() {
      return Instant.class;
    }

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) {
      return readInstant(p);
    }
  }

  /** Serializes a {@link Duration} as an ISO 8601 duration string ({@code PT3661S}). */
  public static final class DurationIsoSerializer extends ValueSerializer<Duration> {
    public DurationIsoSerializer() {}

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializationContext serializers) {
      gen.writeString(value.toString());
    }
  }

  /** Reads a {@link Duration} given as an ISO / proto3 duration string, the object, or a number. */
  public static final class DurationIsoDeserializer extends ValueDeserializer<Duration> {
    public DurationIsoDeserializer() {}

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) {
      return readDuration(p);
    }
  }

  /** Serializes a {@link Duration} in the documented proto3 canonical shape. */
  public static final class DurationCanonicalSerializer extends ValueSerializer<Duration> {
    public DurationCanonicalSerializer() {}

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializationContext serializers) {
      // The WKT {seconds, nanos} contract is the FLOOR pair — java.time's getSeconds()/getNano()
      // is the *truncating* pair (a negative duration's {getSeconds, getNano} is the wrong
      // value), so derive the floor seconds with the canonical toSeconds/toNanos decomposition.
      long seconds = value.toSeconds();
      gen.writeStartObject();
      gen.writeName("seconds").writeString(Long.toString(seconds));
      gen.writeName("nanos").writeNumber((int) (value.toNanos() - seconds * 1_000_000_000L));
      gen.writeEndObject();
    }
  }

  /**
   * Reads a {@link Duration} given as the canonical object, an ISO / proto3 string, or a number.
   */
  public static final class DurationCanonicalDeserializer extends ValueDeserializer<Duration> {
    public DurationCanonicalDeserializer() {}

    @Override
    public Class<Duration> handledType() {
      return Duration.class;
    }

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) {
      return readDuration(p);
    }
  }

  /**
   * Resolve the temporal (de)serializer class pair for a temporal well-known type.
   *
   * @param temporalFqn the field's WKT FQN — {@code google.protobuf.Timestamp} or {@code
   *     google.protobuf.Duration}
   * @param format the per-field rendering
   * @return the resolved {@link JsonTemporal.Coords} pair, or {@code null} when the type is not a
   *     temporal WKT (the caller skips the annotation)
   */
  public static JsonTemporal.@Nullable Coords forType(
      String temporalFqn, JacksonTemporalFormat format) {
    return switch (temporalFqn) {
      case "google.protobuf.Timestamp" ->
          new JsonTemporal.Coords(
              format == JacksonTemporalFormat.JSON
                  ? InstantIsoSerializer.class
                  : InstantCanonicalSerializer.class,
              format == JacksonTemporalFormat.JSON
                  ? InstantIsoDeserializer.class
                  : InstantCanonicalDeserializer.class);
      case "google.protobuf.Duration" ->
          new JsonTemporal.Coords(
              format == JacksonTemporalFormat.JSON
                  ? DurationIsoSerializer.class
                  : DurationCanonicalSerializer.class,
              format == JacksonTemporalFormat.JSON
                  ? DurationIsoDeserializer.class
                  : DurationCanonicalDeserializer.class);
      default -> null;
    };
  }

  /**
   * Read an instant in all accepted shapes: the canonical {@code {seconds, nanos}} object, a bare
   * seconds number, or an ISO 8601 / RFC 3339 string.
   */
  static Instant readInstant(JsonParser p) {
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
      return Instant.ofEpochSecond(seconds, nanos);
    }
    if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
      return Instant.ofEpochSecond(p.getLongValue());
    }
    return Instant.from(OffsetDateTime.parse(p.getString()));
  }

  /**
   * Read a duration in all accepted shapes: the canonical {@code {seconds, nanos}} object, a bare
   * seconds number, or a duration string (the ISO form with its {@code PT} prefix or the proto3
   * JSON form without one).
   */
  static Duration readDuration(JsonParser p) {
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
      return Duration.ofSeconds(seconds, nanos);
    }
    if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
      return Duration.ofSeconds(p.getLongValue());
    }
    return parseDurationString(p.getString());
  }

  /**
   * Accept both duration string forms: the ISO 8601 form ({@code PT3661S}, what {@link
   * Duration#toString()} emits) and the proto3 JSON-mapping form ({@code 3661s}).
   */
  static Duration parseDurationString(String s) {
    String t = s;
    if (t.isEmpty() || (t.charAt(0) != 'P' && t.charAt(0) != 'p')) {
      t = "PT" + t;
    }
    if (t.endsWith("s")) {
      t = t.substring(0, t.length() - 1) + "S";
    }
    return Duration.parse(t);
  }
}
