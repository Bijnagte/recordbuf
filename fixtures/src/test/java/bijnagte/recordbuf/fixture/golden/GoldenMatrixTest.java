package bijnagte.recordbuf.fixture.golden;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/**
 * Self-tests of the matrix, over stub in-memory formats and goldens written to a temp directory.
 */
class GoldenMatrixTest {

  private record Point(int x, int y) {}

  /** JSON {@code {"x":..,"y":..}}; not applicable to the origin, to exercise the format's rule. */
  private static final class StubJson implements GoldenFormat.Json {
    @Override
    public String extension() {
      return "stub.json";
    }

    @Override
    public Optional<String> notApplicableReason(Object record) {
      return record.equals(new Point(0, 0)) ? Optional.of("origin has no JSON") : Optional.empty();
    }

    @Override
    public String serialize(Object record) {
      Point p = (Point) record;
      return "{\"x\":" + p.x() + ",\"y\":" + p.y() + "}";
    }

    @Override
    public Object deserialize(String goldenText, Class<?> recordType) {
      String[] parts = goldenText.replaceAll("[^0-9,]", "").split(",", -1);
      return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
  }

  /** Two bytes {x, y}, but drops {@code y} on the way back: lossy by design. */
  private static final class StubBinary implements GoldenFormat.Binary {
    @Override
    public String extension() {
      return "stub.hex";
    }

    @Override
    public byte[] serialize(Object record) {
      Point p = (Point) record;
      return new byte[] {(byte) p.x(), (byte) p.y()};
    }

    @Override
    public Object deserialize(byte[] goldenBytes, Class<?> recordType) {
      return new Point(goldenBytes[0], 0);
    }
  }

  private final StubJson json = new StubJson();
  private final StubBinary binary = new StubBinary();
  private final List<GoldenFormat> formats = List.of(json, binary);

  @TempDir Path goldenDir;

  @Test
  void losslessCase_forwardAndReverse_pass() throws IOException {
    write("pts/a", "stub.json", "{ \"x\": 1, \"y\": 2 }\n");
    write("pts/a", "stub.hex", "0102\n");
    GoldenCase goldenCase =
        GoldenCase.builder("pts", "a", new Point(1, 2))
            .lossless(json)
            .expectedAfterTrip(binary, new Point(1, 0))
            .build(formats);

    List<DynamicTest> tests = run(goldenCase);

    assertEquals(
        List.of(
            "pts/a [stub.json] forward",
            "pts/a [stub.json] reverse",
            "pts/a [stub.hex] forward",
            "pts/a [stub.hex] reverse"),
        tests.stream().map(DynamicTest::getDisplayName).toList());
    tests.forEach(test -> assertDoesNotThrow(test.getExecutable()));
  }

  @Test
  void losslessCase_goldenDiffersFromSerialization_forwardFails() throws IOException {
    write("pts/a", "stub.json", "{\"x\":1,\"y\":3}\n");
    write("pts/a", "stub.hex", "0102\n");
    GoldenCase goldenCase =
        GoldenCase.builder("pts", "a", new Point(1, 2))
            .lossless(json)
            .expectedAfterTrip(binary, new Point(1, 0))
            .build(formats);

    DynamicTest forward = find(run(goldenCase), "pts/a [stub.json] forward");

    assertThrows(AssertionFailedError.class, forward.getExecutable());
  }

  @Test
  void expectedAfterTripCase_reverseMatchesDeclaredRecordAndNotBuiltRecord() throws IOException {
    write("pts/a", "stub.json", "{\"x\":1,\"y\":2}\n");
    write("pts/a", "stub.hex", "0102\n");
    GoldenCase declared =
        GoldenCase.builder("pts", "a", new Point(1, 2))
            .lossless(json)
            .expectedAfterTrip(binary, new Point(1, 0))
            .build(formats);
    GoldenCase builtRecordExpected =
        GoldenCase.builder("pts", "a", new Point(1, 2))
            .lossless(json)
            .lossless(binary)
            .build(formats);

    String reverse = "pts/a [stub.hex] reverse";

    assertDoesNotThrow(find(run(declared), reverse).getExecutable());
    assertDoesNotThrow(find(run(declared), "pts/a [stub.hex] forward").getExecutable());
    assertThrows(
        AssertionFailedError.class, find(run(builtRecordExpected), reverse).getExecutable());
  }

  @Test
  void notApplicablePair_isAbortedWithReason_neverPassed() {
    GoldenCase goldenCase =
        GoldenCase.builder("pts", "origin", new Point(0, 0))
            .notApplicable(json, "origin has no JSON")
            .notApplicable(binary, "binary skipped for this case")
            .build(formats);

    List<DynamicTest> tests = run(goldenCase);

    assertEquals(2, tests.size());
    for (DynamicTest test : tests) {
      TestAbortedException aborted = assertThrows(TestAbortedException.class, test.getExecutable());
      assertTrue(
          aborted.getMessage().contains("origin has no JSON")
              || aborted.getMessage().contains("binary skipped"),
          aborted.getMessage());
    }
    assertTrue(tests.get(0).getDisplayName().endsWith("not applicable"));
  }

  @Test
  void missingGolden_forwardFailsNamingThePath() {
    GoldenCase goldenCase =
        GoldenCase.builder("pts", "nogolden", new Point(1, 2))
            .lossless(json)
            .lossless(binary)
            .build(formats);

    AssertionError error =
        assertThrows(
            AssertionError.class,
            find(run(goldenCase), "pts/nogolden [stub.json] forward").getExecutable());

    assertTrue(error.getMessage().contains("nogolden.stub.json"), error.getMessage());
  }

  @Test
  void caseWithoutOutcomeForAFormat_failsConstruction() {
    GoldenCase.Builder builder = GoldenCase.builder("pts", "a", new Point(1, 2)).lossless(json);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> builder.build(formats));

    assertTrue(error.getMessage().contains(".stub.hex"), error.getMessage());
  }

  @Test
  void caseDeclaringApplicableForAFormatThatRejectsTheRecord_failsConstruction() {
    GoldenCase.Builder builder =
        GoldenCase.builder("pts", "origin", new Point(0, 0)).lossless(json).lossless(binary);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> builder.build(formats));

    assertTrue(error.getMessage().contains("origin has no JSON"), error.getMessage());
  }

  @Test
  void caseDeclaringAnUnregisteredFormat_failsConstruction() {
    GoldenCase.Builder builder =
        GoldenCase.builder("pts", "a", new Point(1, 2)).lossless(json).lossless(binary);

    assertThrows(IllegalStateException.class, () -> builder.build(List.of(json)));
  }

  @Test
  void caseDeclaringAFormatTwice_fails() {
    GoldenCase.Builder builder = GoldenCase.builder("pts", "a", new Point(1, 2)).lossless(json);

    assertThrows(IllegalStateException.class, () -> builder.lossless(json));
  }

  @Test
  void matrix_caseBuiltAgainstOtherFormats_failsAtTestsCall() {
    GoldenCase goldenCase =
        GoldenCase.builder("pts", "a", new Point(1, 2)).lossless(json).build(List.of(json));
    GoldenMatrix matrix = GoldenMatrix.of(GoldenStore.inDirectory(goldenDir), formats);

    assertThrows(IllegalArgumentException.class, () -> matrix.tests(List.of(goldenCase)));
  }

  @Test
  void matrix_duplicateCaseOrExtension_fails() {
    GoldenCase goldenCase =
        GoldenCase.builder("pts", "a", new Point(1, 2))
            .lossless(json)
            .lossless(binary)
            .build(formats);
    GoldenMatrix matrix = GoldenMatrix.of(GoldenStore.inDirectory(goldenDir), formats);

    assertThrows(
        IllegalArgumentException.class, () -> matrix.tests(List.of(goldenCase, goldenCase)));
    assertThrows(
        IllegalArgumentException.class,
        () -> GoldenMatrix.of(GoldenStore.inDirectory(goldenDir), List.of(json, new StubJson())));
  }

  private List<DynamicTest> run(GoldenCase goldenCase) {
    Stream<DynamicNode> nodes =
        GoldenMatrix.of(GoldenStore.inDirectory(goldenDir), formats).tests(List.of(goldenCase));
    return nodes.map(DynamicTest.class::cast).toList();
  }

  private static DynamicTest find(List<DynamicTest> tests, String displayName) {
    return tests.stream()
        .filter(test -> test.getDisplayName().equals(displayName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no test named " + displayName + " in " + tests));
  }

  private void write(String caseDir, String extension, String content) throws IOException {
    Path file = goldenDir.resolve(caseDir + "." + extension);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }
}
