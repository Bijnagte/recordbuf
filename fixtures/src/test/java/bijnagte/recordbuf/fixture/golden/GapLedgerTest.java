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
  private static final String HEADER =
      "| id | construct or scenario | formats | kind | reason |\n| --- | --- | --- | --- | --- |\n";

  /** The committed ledger passes against the real vendored protos. */
  @Test
  void realLedger_agreesWithRealProtos() throws IOException {
    GapLedger ledger = GapLedger.parse(readLedgerResource());
    List<GapLedger.Marker> markers = GapLedger.scan(GapLedger.defaultProtoRoot());

    assertEquals(List.of(), ledger.problems(markers));
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

  private static String readLedgerResource() throws IOException {
    try (InputStream in = GapLedgerTest.class.getResourceAsStream(LEDGER_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + LEDGER_RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
