package bijnagte.recordbuf.fixture.golden;

import bijnagte.recordbuf.fixture.corpus.TestAllTypesCorpusCases;
import bijnagte.recordbuf.fixture.jackson.JacksonDynamicWktCases;
import bijnagte.recordbuf.fixture.jackson.JacksonKnobsCases;
import bijnagte.recordbuf.fixture.jackson.JacksonOneofCases;
import bijnagte.recordbuf.fixture.jackson.JacksonRoundTripCases;
import bijnagte.recordbuf.fixture.jacksonformat.JacksonFormatCases;
import java.util.List;

/**
 * The explicit list of {@link GoldenCaseSource}s the {@code seedGoldens} task walks. A new family
 * of golden cases is seedable once its source is added to {@link #sources()}.
 */
public final class GoldenCaseRegistry {

  private GoldenCaseRegistry() {}

  /** Returns every registered case source, in the order the seeder walks them. */
  public static List<GoldenCaseSource> sources() {
    return List.of(
        new AdapterGoldenCases(),
        new TestAllTypesCorpusCases(),
        new JacksonRoundTripCases(),
        new JacksonDynamicWktCases(),
        new JacksonOneofCases(),
        new JacksonFormatCases(),
        new JacksonKnobsCases());
  }
}
