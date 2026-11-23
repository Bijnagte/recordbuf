package bijnagte.recordbuf.fixture.golden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The gap ledger (KTD7): the markdown table of constructs and conformance scenarios recordbuf
 * cannot satisfy yet, checked against the {@code GAP(<id>)} markers in the proto sources' {@code
 * //} comments. {@link #problems} checks both directions, and returns nothing when they agree.
 *
 * <p>The table is read by {@link #parse}; its columns are {@code id | construct or scenario |
 * formats | kind | reason}. A cell must not contain {@code |}.
 *
 * <p>The conformance manifest (U20) is the second table this class reads: {@link #parseManifest}
 * reads its rows and {@link #manifestProblems} cross-checks them against this ledger.
 */
public final class GapLedger {

  /** System property naming the proto directory that {@link #scan} reads. */
  public static final String PROTO_DIR_PROPERTY = "recordbuf.fixtures.protoDir";

  /**
   * Proto directory used when {@link #PROTO_DIR_PROPERTY} is unset, relative to the working dir.
   */
  public static final String DEFAULT_PROTO_DIR = "src/main/proto";

  private static final Pattern MARKER = Pattern.compile("GAP\\(([a-z0-9-]+)\\)");
  private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
  private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-+:?");
  private static final int COLUMNS = 5;
  private static final int MANIFEST_COLUMNS = 4;
  private static final Pattern TEST_REFERENCE = Pattern.compile("([\\w.$]+)#(\\w+)");

  /** Whether a row is a construct the proto comments out, or a conformance-only scenario. */
  public enum Kind {
    /** A proto construct that must carry a {@code GAP(<id>)} marker. */
    CONSTRUCT,
    /** A conformance scenario with no proto construct, so it has no marker. */
    CONFORMANCE_ONLY;

    /** Parses the {@code kind} cell: {@code construct} or {@code conformance-only}. */
    static Kind fromCell(String cell) {
      return switch (cell) {
        case "construct" -> CONSTRUCT;
        case "conformance-only" -> CONFORMANCE_ONLY;
        default ->
            throw new IllegalArgumentException(
                "unknown kind '" + cell + "' (expected construct or conformance-only)");
      };
    }
  }

  /** One ledger row. */
  public record Entry(
      String id, String constructOrScenario, String formats, Kind kind, String reason) {}

  /** The category of a conformance manifest row. */
  public enum Category {
    /** Wire-format scenarios. */
    BINARY,
    /** Proto3 JSON scenarios. */
    JSON,
    /** Text-format scenarios. */
    TEXT_FORMAT;

    /** Parses the {@code category} cell: {@code binary}, {@code json} or {@code text-format}. */
    static Category fromCell(String cell) {
      return switch (cell) {
        case "binary" -> BINARY;
        case "json" -> JSON;
        case "text-format" -> TEXT_FORMAT;
        default ->
            throw new IllegalArgumentException(
                "unknown category '" + cell + "' (expected binary, json or text-format)");
      };
    }
  }

  /** The status of a conformance manifest row. */
  public enum Status {
    /** A Java test mirrors the group; the reference names the test methods. */
    COVERED,
    /** The group is not satisfied or not mirrored yet; the reference names ledger ids. */
    GAP,
    /** The group does not apply to recordbuf; the reference is the reason. */
    NOT_APPLICABLE;

    /** Parses the {@code status} cell: {@code covered}, {@code gap} or {@code not-applicable}. */
    static Status fromCell(String cell) {
      return switch (cell) {
        case "covered" -> COVERED;
        case "gap" -> GAP;
        case "not-applicable" -> NOT_APPLICABLE;
        default ->
            throw new IllegalArgumentException(
                "unknown status '" + cell + "' (expected covered, gap or not-applicable)");
      };
    }
  }

  /**
   * One conformance manifest row. {@code reference} may be empty here; {@link #manifestProblems}
   * reports that as a violation.
   */
  public record ManifestRow(String group, Category category, Status status, String reference) {

    /** Returns the comma-separated entries of {@code reference}, trimmed; empty if it is empty. */
    List<String> references() {
      if (reference.isEmpty()) {
        return List.of();
      }
      return Arrays.stream(reference.split(",", -1)).map(String::strip).toList();
    }
  }

  /** One {@code GAP(<id>)} marker, at {@code location} ({@code <proto path>:<line>}). */
  public record Marker(String id, String location) {}

  private final List<Entry> entries;

  private GapLedger(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /** Returns the rows, in table order. */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Parses the ledger table out of {@code markdown}. Lines that are not table rows are ignored, as
   * are the header and separator rows; cells are trimmed.
   *
   * @throws IllegalArgumentException if a table row has the wrong number of cells, an empty cell,
   *     an id outside {@code [a-z0-9-]+}, or an unknown kind
   */
  public static GapLedger parse(String markdown) {
    List<Entry> entries = new ArrayList<>();
    String[] lines = markdown.split("\r?\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (!line.startsWith("|")) {
        continue;
      }
      int lineNumber = i + 1;
      List<String> cells = cells(line);
      if (cells.size() != COLUMNS) {
        throw new IllegalArgumentException(
            "ledger line "
                + lineNumber
                + ": expected "
                + COLUMNS
                + " cells, found "
                + cells.size());
      }
      if (cells.get(0).equals("id") || isSeparator(cells)) {
        continue;
      }
      for (String cell : cells) {
        if (cell.isEmpty()) {
          throw new IllegalArgumentException("ledger line " + lineNumber + ": empty cell");
        }
      }
      if (!ID.matcher(cells.get(0)).matches()) {
        throw new IllegalArgumentException(
            "ledger line " + lineNumber + ": id '" + cells.get(0) + "' is not [a-z0-9-]+");
      }
      Kind kind;
      try {
        kind = Kind.fromCell(cells.get(3));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("ledger line " + lineNumber + ": " + e.getMessage(), e);
      }
      entries.add(new Entry(cells.get(0), cells.get(1), cells.get(2), kind, cells.get(4)));
    }
    return new GapLedger(entries);
  }

  /**
   * Parses the conformance manifest table out of {@code markdown}: columns {@code group | category
   * | status | reference}. Lines that are not table rows are ignored, as are the header and
   * separator rows; cells are trimmed and backticks are dropped. An empty {@code reference} is
   * accepted, so {@link #manifestProblems} can report it.
   *
   * @throws IllegalArgumentException if a table row has the wrong number of cells, an empty group,
   *     category or status cell, an unknown category, or an unknown status
   */
  public static List<ManifestRow> parseManifest(String markdown) {
    List<ManifestRow> rows = new ArrayList<>();
    String[] lines = markdown.split("\r?\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (!line.startsWith("|")) {
        continue;
      }
      int lineNumber = i + 1;
      List<String> cells = cells(line).stream().map(cell -> cell.replace("`", "")).toList();
      if (cells.size() != MANIFEST_COLUMNS) {
        throw new IllegalArgumentException(
            "manifest line "
                + lineNumber
                + ": expected "
                + MANIFEST_COLUMNS
                + " cells, found "
                + cells.size());
      }
      if (cells.get(0).equals("group") || isSeparator(cells)) {
        continue;
      }
      for (String cell : cells.subList(0, 3)) {
        if (cell.isEmpty()) {
          throw new IllegalArgumentException("manifest line " + lineNumber + ": empty cell");
        }
      }
      try {
        rows.add(
            new ManifestRow(
                cells.get(0),
                Category.fromCell(cells.get(1)),
                Status.fromCell(cells.get(2)),
                cells.get(3)));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "manifest line " + lineNumber + ": " + e.getMessage(), e);
      }
    }
    return List.copyOf(rows);
  }

  /**
   * Scans every {@code .proto} file under {@code protoRoot}, recursively, for {@code GAP(<id>)}
   * markers inside {@code //} comments. Files are read in path order, so the result is stable.
   *
   * @throws IllegalArgumentException if {@code protoRoot} is not a directory
   */
  public static List<Marker> scan(Path protoRoot) throws IOException {
    if (!Files.isDirectory(protoRoot)) {
      throw new IllegalArgumentException("proto root is not a directory: " + protoRoot);
    }
    List<Marker> markers = new ArrayList<>();
    List<Path> protos;
    try (Stream<Path> files = Files.walk(protoRoot)) {
      protos =
          files
              .filter(path -> path.toString().endsWith(".proto") && Files.isRegularFile(path))
              .sorted()
              .toList();
    }
    for (Path proto : protos) {
      String name = protoRoot.relativize(proto).toString();
      List<String> lines = Files.readAllLines(proto, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        int comment = line.indexOf("//");
        if (comment < 0) {
          continue;
        }
        Matcher matcher = MARKER.matcher(line.substring(comment));
        while (matcher.find()) {
          markers.add(new Marker(matcher.group(1), name + ":" + (i + 1)));
        }
      }
    }
    return List.copyOf(markers);
  }

  /**
   * Returns the proto root named by {@link #PROTO_DIR_PROPERTY}, else {@link #DEFAULT_PROTO_DIR}.
   */
  public static Path defaultProtoRoot() {
    return Path.of(System.getProperty(PROTO_DIR_PROPERTY, DEFAULT_PROTO_DIR));
  }

  /**
   * Checks this ledger against the markers found in the proto sources. Returns one message per
   * violation, in a stable order; an empty list means the two agree:
   *
   * <ul>
   *   <li>every marker id has a ledger row;
   *   <li>every {@link Kind#CONSTRUCT} row has at least one marker;
   *   <li>every ledger id is unique.
   * </ul>
   *
   * {@link Kind#CONFORMANCE_ONLY} rows need no marker. U20 extends this check with the conformance
   * manifest.
   */
  public List<String> problems(List<Marker> markers) {
    List<String> problems = new ArrayList<>();
    Map<String, Integer> rowCounts = new LinkedHashMap<>();
    for (Entry entry : entries) {
      rowCounts.merge(entry.id(), 1, Integer::sum);
    }
    rowCounts.forEach((id, count) -> {
      if (count > 1) {
        problems.add("ledger id '" + id + "' appears " + count + " times; ids must be unique");
      }
    });
    Set<String> markedIds = new HashSet<>();
    for (Marker marker : markers) {
      markedIds.add(marker.id());
      if (!rowCounts.containsKey(marker.id())) {
        problems.add("GAP(" + marker.id() + ") at " + marker.location() + " has no ledger row");
      }
    }
    for (Entry entry : entries) {
      if (entry.kind() == Kind.CONSTRUCT && !markedIds.contains(entry.id())) {
        problems.add(
            "ledger row '"
                + entry.id()
                + "' is a construct, but no GAP("
                + entry.id()
                + ") marker names it");
      }
    }
    return List.copyOf(problems);
  }

  /**
   * Cross-checks the conformance manifest {@code rows} against this ledger. Returns one message per
   * violation, in a stable order; an empty list means they agree:
   *
   * <ul>
   *   <li>every group name is unique;
   *   <li>a {@link Status#COVERED} row has at least one {@code fully.qualified.Class#method}
   *       reference, and each names a class on the classpath that declares a method of that name;
   *   <li>a {@link Status#GAP} row names at least one ledger id, and each id has a ledger row;
   *   <li>a {@link Status#NOT_APPLICABLE} row gives a reason;
   *   <li>every {@link Kind#CONFORMANCE_ONLY} ledger row is named by at least one gap row.
   * </ul>
   */
  public List<String> manifestProblems(List<ManifestRow> rows) {
    List<String> problems = new ArrayList<>();
    Set<String> ledgerIds = new HashSet<>();
    for (Entry entry : entries) {
      ledgerIds.add(entry.id());
    }
    Set<String> groups = new HashSet<>();
    Set<String> referencedIds = new HashSet<>();
    for (ManifestRow row : rows) {
      if (!groups.add(row.group())) {
        problems.add("manifest group '" + row.group() + "' appears more than once");
      }
      switch (row.status()) {
        case COVERED -> coveredProblems(row, problems);
        case GAP -> {
          if (row.references().isEmpty()) {
            problems.add("manifest group '" + row.group() + "' is a gap but names no ledger id");
          }
          for (String id : row.references()) {
            referencedIds.add(id);
            if (!ledgerIds.contains(id)) {
              problems.add(
                  "manifest group '"
                      + row.group()
                      + "' is a gap, but ledger has no row '"
                      + id
                      + "'");
            }
          }
        }
        case NOT_APPLICABLE -> {
          if (row.reference().isEmpty()) {
            problems.add(
                "manifest group '" + row.group() + "' is not-applicable but gives no reason");
          }
        }
      }
    }
    for (Entry entry : entries) {
      if (entry.kind() == Kind.CONFORMANCE_ONLY && !referencedIds.contains(entry.id())) {
        problems.add(
            "ledger row '"
                + entry.id()
                + "' is conformance-only, but no manifest gap row names it");
      }
    }
    return List.copyOf(problems);
  }

  private static void coveredProblems(ManifestRow row, List<String> problems) {
    if (row.references().isEmpty()) {
      problems.add("manifest group '" + row.group() + "' is covered but names no test");
    }
    for (String reference : row.references()) {
      Matcher matcher = TEST_REFERENCE.matcher(reference);
      if (!matcher.matches()) {
        problems.add(
            "manifest group '"
                + row.group()
                + "' is covered, but '"
                + reference
                + "' is not Class#method");
        continue;
      }
      if (!declaresMethod(matcher.group(1), matcher.group(2))) {
        problems.add(
            "manifest group '"
                + row.group()
                + "' is covered, but test '"
                + reference
                + "' does not exist");
      }
    }
  }

  private static boolean declaresMethod(String className, String methodName) {
    try {
      Class<?> type = Class.forName(className, false, GapLedger.class.getClassLoader());
      return Arrays.stream(type.getDeclaredMethods())
          .anyMatch(method -> method.getName().equals(methodName));
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  private static List<String> cells(String row) {
    String inner = row.strip();
    if (inner.startsWith("|")) {
      inner = inner.substring(1);
    }
    if (inner.endsWith("|")) {
      inner = inner.substring(0, inner.length() - 1);
    }
    return Arrays.stream(inner.split("\\|", -1)).map(String::strip).toList();
  }

  private static boolean isSeparator(List<String> cells) {
    return cells.stream().allMatch(cell -> SEPARATOR_CELL.matcher(cell).matches());
  }
}
