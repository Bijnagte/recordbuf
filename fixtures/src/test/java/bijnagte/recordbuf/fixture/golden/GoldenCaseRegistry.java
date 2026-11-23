package bijnagte.recordbuf.fixture.golden;

import bijnagte.recordbuf.fixture.bytes.BytesCases;
import bijnagte.recordbuf.fixture.canonicalformat.CanonicalFormatCases;
import bijnagte.recordbuf.fixture.corpus.TestAllTypesCorpusCases;
import bijnagte.recordbuf.fixture.fixed.FixedCases;
import bijnagte.recordbuf.fixture.i32.IntCases;
import bijnagte.recordbuf.fixture.jackson.JacksonDynamicWktCases;
import bijnagte.recordbuf.fixture.jackson.JacksonKnobsCases;
import bijnagte.recordbuf.fixture.jackson.JacksonOneofCases;
import bijnagte.recordbuf.fixture.jackson.JacksonRoundTripCases;
import bijnagte.recordbuf.fixture.jacksonformat.JacksonFormatCases;
import bijnagte.recordbuf.fixture.optional.OptionalCases;
import bijnagte.recordbuf.fixture.order.OrderCases;
import bijnagte.recordbuf.fixture.packed.PackedCases;
import bijnagte.recordbuf.fixture.repeated.RepeatedCases;
import bijnagte.recordbuf.fixture.string.StringCases;
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
        new JacksonKnobsCases(),
        new CanonicalFormatCases(),
        new IntCases(),
        new FixedCases(),
        new StringCases(),
        new BytesCases(),
        new PackedCases(),
        new RepeatedCases(),
        new OptionalCases(),
        new OrderCases());
  }
}
