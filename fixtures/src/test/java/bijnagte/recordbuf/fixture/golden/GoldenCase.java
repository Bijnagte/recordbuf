package bijnagte.recordbuf.fixture.golden;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One golden case: a family, a name, a built record, and for every format in the suite exactly one
 * {@link Outcome}. The golden of a pair lives at {@code <family>/<name>.<format extension>} (see
 * {@link GoldenStore}).
 *
 * <p>Cases are created through {@link #builder}; {@link Builder#build} rejects a case that leaves
 * any format of the suite without an outcome, so a new format cannot be added without deciding
 * every case.
 */
public final class GoldenCase {

  /** What a format does to a case's record. */
  public sealed interface Outcome permits Lossless, ExpectedAfterTrip, NotApplicable {}

  /** The format round-trips the built record unchanged. */
  public record Lossless() implements Outcome {}

  /**
   * The format drops information by design: serializing the built record gives the golden, and
   * deserializing the golden gives {@code expected}, which differs from the built record.
   */
  public record ExpectedAfterTrip(Object expected) implements Outcome {}

  /** The format does not apply to this case; the matrix reports it as skipped with the reason. */
  public record NotApplicable(String reason) implements Outcome {}

  private final String family;
  private final String name;
  private final Object record;
  private final Map<GoldenFormat, Outcome> outcomes;

  private GoldenCase(
      String family, String name, Object record, Map<GoldenFormat, Outcome> outcomes) {
    this.family = family;
    this.name = name;
    this.record = record;
    this.outcomes = outcomes;
  }

  /**
   * Starts a case.
   *
   * @param family the family directory, e.g. {@code "scalars"}
   * @param name the case name, e.g. {@code "int32"}
   * @param record the built record every format serializes
   */
  public static Builder builder(String family, String name, Object record) {
    return new Builder(family, name, record);
  }

  /** Returns the family directory. */
  public String family() {
    return family;
  }

  /** Returns the case name. */
  public String name() {
    return name;
  }

  /** Returns the built record. */
  public Object record() {
    return record;
  }

  /**
   * Returns the outcome declared for {@code format}.
   *
   * @throws IllegalArgumentException if the case declares no outcome for the format
   */
  public Outcome outcomeFor(GoldenFormat format) {
    Outcome outcome = outcomes.get(format);
    if (outcome == null) {
      throw new IllegalArgumentException(
          "case " + family + "/" + name + " declares no outcome for format ." + format.extension());
    }
    return outcome;
  }

  /** Collects the per-format outcomes of a case. */
  public static final class Builder {

    private final String family;
    private final String name;
    private final Object record;
    private final Map<GoldenFormat, Outcome> outcomes = new LinkedHashMap<>();

    private Builder(String family, String name, Object record) {
      this.family = family;
      this.name = name;
      this.record = record;
    }

    /** Declares that {@code format} round-trips the built record unchanged. */
    public Builder lossless(GoldenFormat format) {
      return declare(format, new Lossless());
    }

    /**
     * Declares that {@code format} deserializes its golden to {@code expected}, not the built
     * record.
     */
    public Builder expectedAfterTrip(GoldenFormat format, Object expected) {
      return declare(format, new ExpectedAfterTrip(expected));
    }

    /** Declares that {@code format} does not apply to this case, for {@code reason}. */
    public Builder notApplicable(GoldenFormat format, String reason) {
      return declare(format, new NotApplicable(reason));
    }

    /**
     * Builds the case against the formats of the suite.
     *
     * @throws IllegalStateException if a format has no outcome, an outcome names a format outside
     *     the suite, or a format reports the record as not applicable while the case declares it
     *     applicable
     */
    public GoldenCase build(Collection<? extends GoldenFormat> formats) {
      String label = family + "/" + name;
      for (GoldenFormat format : formats) {
        Outcome outcome = outcomes.get(format);
        if (outcome == null) {
          throw new IllegalStateException(
              "case " + label + " declares no outcome for format ." + format.extension());
        }
        Optional<String> formatReason = format.notApplicableReason(record);
        if (formatReason.isPresent() && !(outcome instanceof NotApplicable)) {
          throw new IllegalStateException(
              "case "
                  + label
                  + " declares "
                  + outcome.getClass().getSimpleName()
                  + " for format ."
                  + format.extension()
                  + ", but the format is not applicable: "
                  + formatReason.get());
        }
      }
      for (GoldenFormat format : outcomes.keySet()) {
        if (!formats.contains(format)) {
          throw new IllegalStateException(
              "case "
                  + label
                  + " declares an outcome for unregistered format ."
                  + format.extension());
        }
      }
      return new GoldenCase(family, name, record, Map.copyOf(outcomes));
    }

    private Builder declare(GoldenFormat format, Outcome outcome) {
      if (outcomes.putIfAbsent(format, outcome) != null) {
        throw new IllegalStateException(
            "case " + family + "/" + name + " declares format ." + format.extension() + " twice");
      }
      return this;
    }
  }
}
