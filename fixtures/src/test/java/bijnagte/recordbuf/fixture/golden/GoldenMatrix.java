package bijnagte.recordbuf.fixture.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.golden.GoldenCase.ExpectedAfterTrip;
import bijnagte.recordbuf.fixture.golden.GoldenCase.Lossless;
import bijnagte.recordbuf.fixture.golden.GoldenCase.NotApplicable;
import bijnagte.recordbuf.fixture.golden.GoldenCase.Outcome;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.TestAbortedException;

/**
 * Runs case × format over a {@link GoldenStore} as JUnit dynamic tests, for use from a
 * {@code @TestFactory}. Each pair produces, by its {@link GoldenCase.Outcome}:
 *
 * <ul>
 *   <li>{@code Lossless} / {@code ExpectedAfterTrip}: a {@code forward} test (serialize the built
 *       record, compare with the golden) and a {@code reverse} test (deserialize the golden, assert
 *       equal to the built record, or to the declared record for {@code ExpectedAfterTrip}).
 *   <li>{@code NotApplicable}: one test that aborts with the reason, so it reports as skipped and
 *       never as passed.
 * </ul>
 *
 * Test names are {@code <family>/<case> [<extension>] forward|reverse|not applicable}.
 */
public final class GoldenMatrix {

  private final GoldenStore store;
  private final List<GoldenFormat> formats;

  private GoldenMatrix(GoldenStore store, List<GoldenFormat> formats) {
    this.store = store;
    this.formats = formats;
  }

  /**
   * Creates a matrix over the given formats.
   *
   * @throws IllegalArgumentException if two formats share an extension
   */
  public static GoldenMatrix of(GoldenStore store, List<? extends GoldenFormat> formats) {
    Set<String> extensions = new HashSet<>();
    for (GoldenFormat format : formats) {
      if (!extensions.add(format.extension())) {
        throw new IllegalArgumentException(
            "two formats share the extension ." + format.extension());
      }
    }
    return new GoldenMatrix(store, List.copyOf(formats));
  }

  /**
   * Returns the dynamic tests for {@code cases}, ordered by case then format.
   *
   * @throws IllegalArgumentException if a case declares no outcome for one of the matrix's formats
   *     or two cases share a family and name
   */
  public Stream<DynamicNode> tests(List<GoldenCase> cases) {
    Set<String> seen = new HashSet<>();
    List<DynamicNode> nodes = new ArrayList<>();
    for (GoldenCase goldenCase : cases) {
      String label = goldenCase.family() + "/" + goldenCase.name();
      if (!seen.add(label)) {
        throw new IllegalArgumentException("duplicate case " + label);
      }
      for (GoldenFormat format : formats) {
        addTests(nodes, goldenCase, format, goldenCase.outcomeFor(format));
      }
    }
    return nodes.stream();
  }

  private void addTests(
      List<DynamicNode> nodes, GoldenCase goldenCase, GoldenFormat format, Outcome outcome) {
    String pair = goldenCase.family() + "/" + goldenCase.name() + " [" + format.extension() + "]";
    switch (outcome) {
      case NotApplicable notApplicable ->
          nodes.add(
              DynamicTest.dynamicTest(
                  pair + " not applicable",
                  () -> {
                    throw new TestAbortedException(notApplicable.reason());
                  }));
      case Lossless lossless -> addPairTests(nodes, pair, goldenCase, format, goldenCase.record());
      case ExpectedAfterTrip afterTrip ->
          addPairTests(nodes, pair, goldenCase, format, afterTrip.expected());
    }
  }

  private void addPairTests(
      List<DynamicNode> nodes,
      String pair,
      GoldenCase goldenCase,
      GoldenFormat format,
      Object expectedAfterReverse) {
    nodes.add(DynamicTest.dynamicTest(pair + " forward", forward(goldenCase, format, pair)));
    nodes.add(
        DynamicTest.dynamicTest(
            pair + " reverse", reverse(goldenCase, format, expectedAfterReverse, pair)));
  }

  private Executable forward(GoldenCase goldenCase, GoldenFormat format, String pair) {
    String family = goldenCase.family();
    String name = goldenCase.name();
    String extension = format.extension();
    return switch (format) {
      case GoldenFormat.Json json ->
          () ->
              GoldenAssertions.assertJsonMatches(
                  store.loadText(family, name, extension),
                  json.serialize(goldenCase.record()),
                  pair);
      case GoldenFormat.Binary binary ->
          () ->
              GoldenAssertions.assertBinaryMatches(
                  store.loadBinary(family, name, extension),
                  binary.serialize(goldenCase.record()),
                  pair);
    };
  }

  private Executable reverse(
      GoldenCase goldenCase, GoldenFormat format, Object expected, String pair) {
    String family = goldenCase.family();
    String name = goldenCase.name();
    String extension = format.extension();
    Class<?> recordType = goldenCase.record().getClass();
    return switch (format) {
      case GoldenFormat.Json json ->
          () ->
              assertEquals(
                  expected,
                  json.deserialize(store.loadText(family, name, extension), recordType),
                  () -> "golden " + pair + " deserialized to a different record");
      case GoldenFormat.Binary binary ->
          () ->
              assertEquals(
                  expected,
                  binary.deserialize(store.loadBinary(family, name, extension), recordType),
                  () -> "golden " + pair + " deserialized to a different record");
    };
  }
}
