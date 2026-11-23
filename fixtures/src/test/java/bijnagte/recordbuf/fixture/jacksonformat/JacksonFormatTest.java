package bijnagte.recordbuf.fixture.jacksonformat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.JsonGolden;
import bijnagte.recordbuf.fixture.jacksonformat.rec.Probe;
import bijnagte.recordbuf.runtime.UnknownFields;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * U8 (2026-11-20 Jackson plan): the temporal {@code .rec} record round-trips through a plain
 * Jackson {@link ObjectMapper} with no module registration — the generated {@code @JsonSerialize} /
 * {@code @JsonDeserialize} annotations carry the mechanism. Asserts the per-field renderings (ISO
 * string / canonical object / repeated ISO) and the accept-all reader shapes.
 */
class JacksonFormatTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static Probe probe() {
    return new Probe(
        Instant.ofEpochSecond(1_763_614_896L, 789_012_345),
        Duration.ofSeconds(3661, 500_000_000),
        List.of(Instant.EPOCH, Instant.ofEpochSecond(1, 250_000_000)),
        null,
        UnknownFields.empty());
  }

  /** at: the default ISO string; span: the canonical object; whens: an array of ISO strings. */
  @Test
  void rendersEachFieldPerItsFormat() throws Exception {
    JsonGolden.assertMatches("jacksonformat/probe.json", M.writeValueAsString(probe()));
  }

  @Test
  void roundTripsThroughJackson() throws Exception {
    Probe probe = probe();
    assertEquals(probe, M.readValue(M.writeValueAsString(probe), Probe.class));
  }

  @Test
  void readersAcceptTheOtherFormat() throws Exception {
    // `at` (an ISO writer by default) read as the canonical object; `span` (a canonical writer)
    // read as an ISO duration string — each reader accepts all renderings.
    Probe back =
        M.readValue(
            "{\"at\": {\"seconds\": \"1763614896\", \"nanos\": 789012345},"
                + "\"span\": \"PT3661.5S\","
                + "\"whens\": [\"1970-01-01T00:00:00Z\", \"1970-01-01T00:00:01.25Z\"]}",
            Probe.class);
    assertEquals(Instant.ofEpochSecond(1_763_614_896L, 789_012_345), back.at());
    assertEquals(Duration.ofSeconds(3661, 500_000_000), back.span());
    assertEquals(Instant.ofEpochSecond(1, 250_000_000), back.whens().get(1));
  }
}
