package bijnagte.recordbuf.runtime.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** U2: the {@link Format} / {@link FormatRegistry} API — discovery seeding, loud miss, register. */
class FormatRegistryTest {

  // --- discovery (R8/R9) ---

  @Test
  void builtInsResolveWithoutExplicitRegistration() {
    assertNotNull(FormatRegistry.format(UUID.class));
    assertNotNull(FormatRegistry.format(URI.class));
  }

  @Test
  void unknownTypeFailsLoudNamingIt() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> FormatRegistry.format(Integer.class));
    String m = e.getMessage();
    assertNotNull(m, "exception message should be set");
    assertTrue(m.contains("java.lang.Integer"), m);
  }

  // --- register (R10) ---

  private static final class DurationFormat implements Format<Duration> {

    @Override
    public Class<Duration> type() {
      return Duration.class;
    }

    @Override
    public String format(Duration value) {
      return value.toString();
    }

    @Override
    public Duration parse(String text) {
      return Duration.parse(text);
    }
  }

  @Test
  void registerMakesCustomTypeResolvable() {
    DurationFormat format = new DurationFormat();
    FormatRegistry.register(Duration.class, format);
    assertSame(format, FormatRegistry.format(Duration.class));
  }

  @Test
  void registerOverridesDiscoveredEntryForSameType() {
    // Behaviorally identical to the built-in UUID format, so it stays green no matter which test
    // runs first; the assertion is instance identity — the override won.
    Format<UUID> override =
        new Format<>() {
          @Override
          public Class<UUID> type() {
            return UUID.class;
          }

          @Override
          public String format(UUID value) {
            return value.toString();
          }

          @Override
          public UUID parse(String text) {
            return UUID.fromString(text);
          }
        };
    FormatRegistry.register(UUID.class, override);
    assertSame(override, FormatRegistry.format(UUID.class));
  }

  // --- built-in behavior (R9) ---

  @Test
  void uuidRoundTrips() {
    Format<UUID> format = FormatRegistry.format(UUID.class);
    UUID id = UUID.randomUUID();
    assertEquals(id.toString(), format.format(id));
    assertEquals(id, format.parse(format.format(id)));
  }

  @Test
  void uuidParseRejectsEmptyAndMalformed() {
    Format<UUID> format = FormatRegistry.format(UUID.class);
    assertThrows(IllegalArgumentException.class, () -> format.parse(""));
    assertThrows(IllegalArgumentException.class, () -> format.parse("not-a-uuid"));
  }

  @Test
  void uriRoundTripsAndWrapsSyntaxError() {
    Format<URI> format = FormatRegistry.format(URI.class);
    URI uri = URI.create("https://example.com/pets/42");
    assertEquals(uri, format.parse(format.format(uri)));
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> format.parse("ht!tp://nope"));
    assertNotNull(e.getCause());
    assertTrue(e.getCause() instanceof URISyntaxException);
  }

  @Test
  void typeMatchesKey() {
    assertEquals(UUID.class, new UuidFormat().type());
    assertEquals(URI.class, new UriFormat().type());
  }

  // --- wireBytes ---

  @Test
  void uuidWireBytesIsTheFixedConstant() {
    Format<UUID> format = FormatRegistry.format(UUID.class);
    assertEquals(36, format.wireBytes(UUID.randomUUID()));
    assertEquals(36, format.wireBytes(UUID.fromString("00000000-0000-0000-0000-000000000000")));
  }

  @Test
  void defaultWireBytesMeasuresTheRenderedString() {
    Format<URI> format = FormatRegistry.format(URI.class);
    URI uri = URI.create("https://example.com/pets/42");
    assertEquals(format.format(uri).getBytes(StandardCharsets.UTF_8).length, format.wireBytes(uri));
  }

  @Test
  void defaultWireBytesCountsUtf8BytesNotChars() {
    Format<Duration> format =
        new Format<>() {
          @Override
          public Class<Duration> type() {
            return Duration.class;
          }

          @Override
          public String format(Duration value) {
            // The last character is two bytes in UTF-8, so bytes != chars.
            return "PT" + value.toSeconds() + "S-\u00e9";
          }

          @Override
          public Duration parse(String text) {
            throw new UnsupportedOperationException("no parse needed for this test");
          }
        };
    String rendered = format.format(Duration.ofSeconds(5));
    assertEquals(
        rendered.getBytes(StandardCharsets.UTF_8).length, format.wireBytes(Duration.ofSeconds(5)));
    assertTrue(format.wireBytes(Duration.ofSeconds(5)) > rendered.length());
  }
}
