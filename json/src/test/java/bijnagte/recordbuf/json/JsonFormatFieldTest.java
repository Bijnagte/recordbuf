package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code JsonFormatField} contextual pair: the same two classes on every format field — the
 * declared type is captured from the field's bean-property context and the conversion runs through
 * the runtime {@code FormatRegistry} (built-in formats discover themselves).
 */
class JsonFormatFieldTest {

  /** A generated-record-style component: the constant pair pinned on a {@code UUID} field. */
  record UidHolder(
      @JsonSerialize(
              using = JsonFormatField.FormatSerializer.class,
              contentUsing = JsonFormatField.FormatSerializer.class)
          @JsonDeserialize(
              using = JsonFormatField.FormatDeserializer.class,
              contentUsing = JsonFormatField.FormatDeserializer.class)
          @Nullable UUID uid) {}

  /** The same constant pair on a different declared type — {@code java.net.URI}. */
  record UriHolder(
      @JsonSerialize(
              using = JsonFormatField.FormatSerializer.class,
              contentUsing = JsonFormatField.FormatSerializer.class)
          @JsonDeserialize(
              using = JsonFormatField.FormatDeserializer.class,
              contentUsing = JsonFormatField.FormatDeserializer.class)
          @Nullable URI uri) {}

  /** The pair on a type with no registered format — fails loud naming the type. */
  record DurHolder(
      @JsonSerialize(
              using = JsonFormatField.FormatSerializer.class,
              contentUsing = JsonFormatField.FormatSerializer.class)
          @JsonDeserialize(
              using = JsonFormatField.FormatDeserializer.class,
              contentUsing = JsonFormatField.FormatDeserializer.class)
          @Nullable Duration dur) {}

  private static final JsonMapper MAPPER = new JsonMapper();

  @Test
  void thePairRoundTripsARegisteredType() {
    UUID uid = UUID.randomUUID();
    UidHolder h = new UidHolder(uid);

    String json = MAPPER.writeValueAsString(h);

    assertEquals("{\"uid\":\"" + uid + "\"}", json);
    assertEquals(h, MAPPER.readValue(json, UidHolder.class));
  }

  @Test
  void theSamePairCoversASecondRegisteredType() {
    URI uri = URI.create("https://example.com/resource/42");
    UriHolder h = new UriHolder(uri);

    String json = MAPPER.writeValueAsString(h);

    assertEquals("{\"uri\":\"https://example.com/resource/42\"}", json);
    assertEquals(h, MAPPER.readValue(json, UriHolder.class));
  }

  @Test
  void jsonNullMapsToANullComponent() {
    assertNull(MAPPER.readValue("{\"uid\":null}", UidHolder.class).uid());
  }

  @Test
  void aPresentUnparseableStringFailsLoud() {
    assertThrows(
        JacksonException.class,
        () -> MAPPER.readValue("{\"uid\":\"not-a-uuid\"}", UidHolder.class));
  }

  @Test
  void aPresentEmptyStringFailsTheParse() {
    // "" is a present-but-unparseable value, not an absent one.
    assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"uid\":\"\"}", UidHolder.class));
  }

  @Test
  void aNonStringTokenFailsLoud() {
    assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"uid\":12}", UidHolder.class));
  }

  @Test
  void anUnregisteredTypeFailsLoudNamingTheType() {
    // The registry's failure rides in the cause chain under Jackson's wrapping exception.
    Throwable e =
        assertThrows(
            DatabindException.class,
            () -> MAPPER.writeValueAsString(new DurHolder(Duration.ofSeconds(1))));

    Throwable cause = e;
    while (cause != null && !String.valueOf(cause.getMessage()).contains("java.time.Duration")) {
      cause = cause.getCause();
    }
    assertNotNull(cause, "the registry failure names java.time.Duration: " + e);
  }
}
