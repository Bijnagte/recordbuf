package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * U8 (2026-11-20 Jackson plan): the java.time temporal arms ({@link JavaTimeTemporal}) — the
 * record-side renderings (ISO string / proto3 canonical object) plus the accept-all readers —
 * round-trip through a plain Jackson mapper. Pre-epoch instants and sub-second durations pin the
 * FLOOR seconds / non-negative-nanos contract.
 */
class JavaTimeTemporalTest {

  // The generated-code shape: the record's components carry the @JsonSerialize / @JsonDeserialize
  // annotations naming the arms, so a plain mapper (no module registration) drives them.
  private record Probe(
      @JsonSerialize(
              using = JavaTimeTemporal.InstantCanonicalSerializer.class,
              contentUsing = JavaTimeTemporal.InstantCanonicalSerializer.class)
          @JsonDeserialize(
              using = JavaTimeTemporal.InstantCanonicalDeserializer.class,
              contentUsing = JavaTimeTemporal.InstantCanonicalDeserializer.class)
          @Nullable Instant at,
      @JsonSerialize(
              using = JavaTimeTemporal.DurationCanonicalSerializer.class,
              contentUsing = JavaTimeTemporal.DurationCanonicalSerializer.class)
          @JsonDeserialize(
              using = JavaTimeTemporal.DurationCanonicalDeserializer.class,
              contentUsing = JavaTimeTemporal.DurationCanonicalDeserializer.class)
          @Nullable Duration span) {}

  private final ObjectMapper m = new ObjectMapper();

  @Test
  void isoInstantRoundsTrips() throws Exception {
    Instant t = Instant.ofEpochSecond(1_763_614_896L, 789_012_345);
    // The canonical arm writes the {seconds, nanos} object; the same reader accepts an ISO string.
    String json = m.writeValueAsString(new Probe(t, Duration.ofSeconds(3661, 500_000_000)));
    assertTrue(json.contains("{\"seconds\""), json);
    assertEquals(t, m.readValue("{\"at\":\"" + t + "\"}", Probe.class).at());
  }

  @Test
  void canonicalInstantObjectRoundsTrips() throws Exception {
    Instant t = Instant.ofEpochSecond(1_763_614_896L, 789_012_345);
    Probe back =
        m.readValue("{\"at\":{\"seconds\":\"1763614896\",\"nanos\":789012345}}", Probe.class);
    assertEquals(t, back.at());
  }

  @Test
  void preEpochInstantKeepsTheFloorPair() throws Exception {
    // -0.5s: the canonical shape must be the FLOOR pair {seconds: -1, nanos: +500000000} — the
    // truncating pair would lose the half second.
    Instant t = Instant.ofEpochSecond(-1, 500_000_000);
    String json = m.writeValueAsString(new Probe(t, null));
    assertTrue(json.contains("\"seconds\":\"-1\""), json);
    assertTrue(json.contains("\"nanos\":500000000"), json);
    assertEquals(t, m.readValue(json, Probe.class).at());
  }

  @Test
  void canonicalDurationRoundsTrips() throws Exception {
    Duration d = Duration.ofSeconds(3661, 500_000_000);
    String json = m.writeValueAsString(new Probe(null, d));
    assertTrue(json.contains("\"seconds\":\"3661\""), json);
    assertTrue(json.contains("\"nanos\":500000000"), json);
    assertEquals(d, m.readValue(json, Probe.class).span());
  }

  @Test
  void isoAndCanonicalStringShapesAreAccepted() {
    // Both the ISO form (PT3661.5S) and the proto3 JSON form (3661.5s) read back.
    assertEquals(
        Duration.ofSeconds(3661, 500_000_000), JavaTimeTemporal.parseDurationString("PT3661.5S"));
    assertEquals(
        Duration.ofSeconds(3661, 500_000_000), JavaTimeTemporal.parseDurationString("3661.5s"));
    assertEquals(
        Duration.ofSeconds(-3661).minusNanos(500_000_000),
        JavaTimeTemporal.parseDurationString("PT-3661.5S"));
    assertThrows(
        DateTimeParseException.class, () -> JavaTimeTemporal.parseDurationString("not-a-duration"));
  }

  @Test
  void forTypeResolvesTemporalWktsOnly() {
    var iso = JavaTimeTemporal.forType("google.protobuf.Timestamp", JacksonTemporalFormat.JSON);
    assertNotNull(iso);
    assertEquals(JavaTimeTemporal.InstantIsoSerializer.class, iso.serializer);
    var canonical =
        JavaTimeTemporal.forType("google.protobuf.Duration", JacksonTemporalFormat.CANONICAL);
    assertNotNull(canonical);
    assertEquals(JavaTimeTemporal.DurationCanonicalDeserializer.class, canonical.deserializer);
    assertEquals(
        null, JavaTimeTemporal.forType("google.protobuf.Struct", JacksonTemporalFormat.JSON));
  }
}
