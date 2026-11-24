package bijnagte.recordbuf.fixture.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.conformance.JsonConformance.Case;
import bijnagte.recordbuf.fixture.golden.GapLedger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link JsonConformance#KNOWN_GAPS} the way the binary conformance tests guard theirs:
 * every key names a scenario the JSON mirrors run, and every value is a row of the gap ledger, so
 * an entry cannot go stale unnoticed.
 */
class JsonKnownGapsTest {

  private static final String LEDGER_RESOURCE = "/golden/gap-ledger.md";

  /** Every {@code KNOWN_GAPS} entry names a mirrored JSON scenario and a gap-ledger row. */
  @Test
  void knownGaps_nameMirroredScenariosAndLedgerRows() throws IOException {
    assertEquals(
        List.of(),
        JsonConformance.knownGapProblems(
            JsonConformance.KNOWN_GAPS, mirroredScenarioNames(), ledgerIds()),
        "KNOWN_GAPS entries that name no mirrored scenario or no ledger row");
  }

  /**
   * Negative control: the same check reports a bogus key and a bogus id, so a pass means it ran.
   */
  @Test
  void knownGapProblems_reportsABogusEntry() throws IOException {
    List<String> problems =
        JsonConformance.knownGapProblems(
            Map.of("NoSuchScenario", "no-such-ledger-id"), mirroredScenarioNames(), ledgerIds());

    assertEquals(
        List.of(
            "KNOWN_GAPS entry 'NoSuchScenario' names no mirrored scenario",
            "KNOWN_GAPS entry 'NoSuchScenario' names gap 'no-such-ledger-id', which has no ledger"
                + " row"),
        problems);
  }

  private static Set<String> mirroredScenarioNames() {
    return Stream.concat(JsonCoreConformanceTest.allCases(), JsonWktConformanceTest.allCases())
        .map(Case::name)
        .collect(Collectors.toSet());
  }

  private static Set<String> ledgerIds() throws IOException {
    return GapLedger.parse(readResource(LEDGER_RESOURCE)).entries().stream()
        .map(GapLedger.Entry::id)
        .collect(Collectors.toSet());
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = JsonKnownGapsTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
