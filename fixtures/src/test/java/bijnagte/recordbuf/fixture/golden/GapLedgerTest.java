package bijnagte.recordbuf.fixture.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the committed gap ledger against the {@code GAP(<id>)} markers in the vendored proto, both
 * ways (KTD7), and self-tests the check over fixture inputs.
 */
class GapLedgerTest {

  private static final String LEDGER_RESOURCE = "/golden/gap-ledger.md";
  private static final String MANIFEST_RESOURCE = "/golden/conformance-manifest.md";
  private static final String MANIFEST_HEADER =
      "| group | category | status | reference |\n| --- | --- | --- | --- |\n";
  private static final String HEADER =
      "| id | construct or scenario | formats | kind | reason |\n| --- | --- | --- | --- | --- |\n";

  /** The committed ledger passes against the real vendored protos. */
  @Test
  void realLedger_agreesWithRealProtos() throws IOException {
    GapLedger ledger = GapLedger.parse(readResource(LEDGER_RESOURCE));
    List<GapLedger.Marker> markers = GapLedger.scan(GapLedger.defaultProtoRoot());

    assertEquals(List.of(), ledger.problems(markers));
  }

  /** The committed manifest agrees with the committed ledger, and has rows in every status. */
  @Test
  void realManifest_agreesWithRealLedger() throws IOException {
    GapLedger ledger = GapLedger.parse(readResource(LEDGER_RESOURCE));
    List<GapLedger.ManifestRow> rows = GapLedger.parseManifest(readResource(MANIFEST_RESOURCE));

    assertEquals(List.of(), ledger.manifestProblems(rows));
    assertTrue(
        rows.stream().anyMatch(row -> row.status() == GapLedger.Status.GAP),
        "the manifest has no gap rows");
    assertTrue(
        rows.stream().anyMatch(row -> row.status() == GapLedger.Status.NOT_APPLICABLE),
        "the manifest has no not-applicable rows");
  }

  /** A covered row with no test reference fails, and the message names the group. */
  @Test
  void coveredRowWithoutTestReference_fails() {
    GapLedger ledger = GapLedger.parse(HEADER);
    List<GapLedger.ManifestRow> rows =
        GapLedger.parseManifest(MANIFEST_HEADER + "| TestX | binary | covered |  |\n");

    List<String> problems = ledger.manifestProblems(rows);

    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("'TestX'"), problems.get(0));
    assertTrue(problems.get(0).contains("names no test"), problems.get(0));
  }

  /** A covered row must name a real class and method; a stale or misspelled one fails. */
  @Test
  void coveredRow_requiresExistingTestMethod() {
    GapLedger ledger = GapLedger.parse(HEADER);
    String self = GapLedgerTest.class.getName();
    List<GapLedger.ManifestRow> rows =
        GapLedger.parseManifest(
            MANIFEST_HEADER
                + "| Real | binary | covered | `"
                + self
                + "#duplicateId_fails` |\n"
                + "| NoMethod | binary | covered | "
                + self
                + "#noSuchMethod |\n"
                + "| NoClass | json | covered | no.such.Type#test |\n"
                + "| NoHash | json | covered | JustAName |\n");

    List<String> problems = ledger.manifestProblems(rows);

    assertEquals(3, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("'NoMethod'"), problems.get(0));
    assertTrue(problems.get(1).contains("'NoClass'"), problems.get(1));
    assertTrue(problems.get(2).contains("not Class#method"), problems.get(2));
  }

  /** A gap row with no ledger id fails, and so does one naming an id the ledger lacks. */
  @Test
  void gapRow_requiresExistingLedgerId() {
    GapLedger ledger =
        GapLedger.parse(HEADER + "| known-id | scenario | pb.hex | conformance-only | r |\n");
    List<GapLedger.ManifestRow> rows =
        GapLedger.parseManifest(
            MANIFEST_HEADER
                + "| NoId | binary | gap |  |\n"
                + "| BadId | binary | gap | known-id, missing-id |\n");

    List<String> problems = ledger.manifestProblems(rows);

    assertEquals(2, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("'NoId'"), problems.get(0));
    assertTrue(problems.get(0).contains("no ledger id"), problems.get(0));
    assertTrue(problems.get(1).contains("'missing-id'"), problems.get(1));
  }

  /** A conformance-only ledger row that no manifest gap row names fails. */
  @Test
  void conformanceOnlyRowMissingFromManifest_fails() {
    GapLedger ledger =
        GapLedger.parse(
            HEADER
                + "| named | scenario a | pb.hex | conformance-only | r |\n"
                + "| orphan | scenario b | pb.hex | conformance-only | r |\n"
                + "| construct-row | construct | pb.hex | construct | r |\n");
    List<GapLedger.ManifestRow> rows =
        GapLedger.parseManifest(
            MANIFEST_HEADER
                + "| TestA | binary | gap | named |\n"
                + "| TestB | json | not-applicable | Google-owned (R15) |\n");

    List<String> problems = ledger.manifestProblems(rows);

    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("'orphan'"), problems.get(0));
  }

  /** A not-applicable row needs a reason, and a group name may appear only once. */
  @Test
  void notApplicableWithoutReason_andDuplicateGroup_fail() {
    GapLedger ledger = GapLedger.parse(HEADER);
    List<GapLedger.ManifestRow> rows =
        GapLedger.parseManifest(
            MANIFEST_HEADER
                + "| TestA | binary | not-applicable |  |\n"
                + "| TestA | json | not-applicable | proto2 |\n");

    List<String> problems = ledger.manifestProblems(rows);

    assertEquals(2, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("gives no reason"), problems.get(0));
    assertTrue(problems.get(1).contains("'TestA'"), problems.get(1));
    assertTrue(problems.get(1).contains("more than once"), problems.get(1));
  }

  /** An unknown status or category is rejected at parse time, with its line number. */
  @Test
  void parseManifest_rejectsUnknownStatusAndCategory() {
    IllegalArgumentException status =
        assertThrows(
            IllegalArgumentException.class,
            () -> GapLedger.parseManifest(MANIFEST_HEADER + "| T | binary | done | x |\n"));
    IllegalArgumentException category =
        assertThrows(
            IllegalArgumentException.class,
            () -> GapLedger.parseManifest(MANIFEST_HEADER + "| T | proto | gap | x |\n"));

    assertTrue(status.getMessage().contains("line 3"), status.getMessage());
    assertTrue(status.getMessage().contains("unknown status 'done'"), status.getMessage());
    assertTrue(category.getMessage().contains("unknown category 'proto'"), category.getMessage());
  }

  /** A marker with no ledger row fails, and the message names the marker and its location. */
  @Test
  void markerWithoutRow_failsNamingTheMarker(@TempDir Path root) throws IOException {
    write(root, "vendored.proto", "message M {\n  // GAP(null-value-field)\n  int32 x = 1;\n}\n");
    GapLedger ledger = GapLedger.parse(HEADER);

    List<String> problems = ledger.problems(GapLedger.scan(root));

    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("GAP(null-value-field)"), problems.get(0));
    assertTrue(problems.get(0).contains("vendored.proto:2"), problems.get(0));
  }

  /** A construct row with no marker fails, and the message names the row. */
  @Test
  void constructRowWithoutMarker_failsNamingTheRow(@TempDir Path root) throws IOException {
    write(root, "vendored.proto", "message M {\n  int32 x = 1;\n}\n");
    GapLedger ledger =
        GapLedger.parse(
            HEADER
                + "| alias-enum | aliased enum values | avro | construct | avro cannot alias |\n");

    List<String> problems = ledger.problems(GapLedger.scan(root));

    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("'alias-enum'"), problems.get(0));
  }

  /** A repeated ledger id fails, and the message names the id. */
  @Test
  void duplicateId_fails() {
    GapLedger ledger =
        GapLedger.parse(
            HEADER
                + "| dup | first | avro | conformance-only | reason a |\n"
                + "| dup | second | avro | conformance-only | reason b |\n");

    List<String> problems = ledger.problems(List.of());

    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("'dup'"), problems.get(0));
    assertTrue(problems.get(0).contains("unique"), problems.get(0));
  }

  /** Markers matched by rows pass; a conformance-only row needs no marker. */
  @Test
  void consistentInputs_pass(@TempDir Path root) throws IOException {
    write(
        root,
        "vendored.proto",
        "// GAP(null-value-field)\nmessage M {\n  int32 x = 1; // GAP(recursive-avro)\n}\n");
    GapLedger ledger =
        GapLedger.parse(
            HEADER
                + "| null-value-field | NullValue fields | avro | construct | unsupported |\n"
                + "| recursive-avro | recursive messages | avro | construct | v1 gap |\n"
                + "| wkt-json | WKT JSON scenario | proto3 json | conformance-only | no construct |\n");

    assertEquals(List.of(), ledger.problems(GapLedger.scan(root)));
  }

  /** A marker only counts inside a {@code //} comment, and only with a lowercase id. */
  @Test
  void markerOutsideLineComment_isIgnored(@TempDir Path root) throws IOException {
    write(root, "vendored.proto", "option x = \"GAP(quoted)\";\n/* GAP(block) */\n// GAP(Upper)\n");

    assertEquals(List.of(), GapLedger.scan(root));
  }

  /** A row with an unknown kind is rejected at parse time, with its line number. */
  @Test
  void parse_rejectsUnknownKind() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> GapLedger.parse(HEADER + "| x | c | f | gap | r |\n"));

    assertTrue(error.getMessage().contains("line 3"), error.getMessage());
    assertTrue(error.getMessage().contains("unknown kind 'gap'"), error.getMessage());
  }

  /** Cells are trimmed, so spacing inside the table does not matter. */
  @Test
  void parse_toleratesWhitespace() {
    GapLedger ledger = GapLedger.parse("|  x-1 |c|f|construct|r  |\n");

    assertEquals(1, ledger.entries().size());
    assertEquals("x-1", ledger.entries().get(0).id());
    assertEquals(GapLedger.Kind.CONSTRUCT, ledger.entries().get(0).kind());
  }

  private static void write(Path root, String name, String content) throws IOException {
    Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = GapLedgerTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
