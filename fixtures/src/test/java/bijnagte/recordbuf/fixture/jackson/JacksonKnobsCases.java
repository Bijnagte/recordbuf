package bijnagte.recordbuf.fixture.jackson;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.full.rec.Knobs;
import java.util.List;

/**
 * The golden case of family {@code jackson-knobs}: the per-field JSON-surface knobs {@code
 * (recordbuf.json_name)} and {@code (recordbuf.json_ignored)} on {@link Knobs}. The renamed field
 * shows under its override in the Jackson golden, the ignored one is absent from it, so the golden
 * deserializes to the record with that field cleared (AE2). The field is only dropped from JSON; it
 * stays on the wire (see {@code JacksonKnobsTest}).
 */
public final class JacksonKnobsCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "jackson-knobs";

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        build(
            jacksonExpectedAfterTrip(
                FAMILY, "knobs-ignored-field", knobs(), withoutIgnoredField())));
  }

  /** A {@code Knobs} with all three fields set, among them the ignored {@code secret}. */
  public static Knobs knobs() {
    return Knobs.builder().label("l").displayText("shown").secret("hidden").build();
  }

  /** What the Jackson golden of {@link #knobs()} reads back as: {@code secret} cleared. */
  public static Knobs withoutIgnoredField() {
    return knobs().toBuilder().secret("").build();
  }
}
