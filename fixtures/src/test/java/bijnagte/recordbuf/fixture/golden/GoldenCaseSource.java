package bijnagte.recordbuf.fixture.golden;

import java.util.List;

/**
 * A set of golden cases that the seeding task can enumerate. The same source feeds the test that
 * runs the cases ({@link GoldenMatrix#tests}) and the seeder, so a case is defined once.
 *
 * <p>A source is listed in {@link GoldenCaseRegistry}; a case that is not registered there is never
 * seeded.
 */
public interface GoldenCaseSource {

  /** Returns the formats the cases of this source are built against and checked in. */
  List<GoldenFormat> formats();

  /** Returns the cases, each built against {@link #formats()}. */
  List<GoldenCase> cases();
}
