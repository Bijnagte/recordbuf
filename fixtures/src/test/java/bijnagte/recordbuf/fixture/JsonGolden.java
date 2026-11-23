package bijnagte.recordbuf.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden-file assertions for the fixtures. The expected document is a test resource under {@code
 * /json/}, compared in one of two flavors:
 *
 * <ul>
 *   <li>{@link #assertMatches} -- as a Jackson tree: every value, every property, array order
 *       included; object member order is not significant. For formats whose meaning is the value
 *       content only (the Jackson-surface tests).
 *   <li>{@link #assertCanonicalMatches} -- exact text, character for character (a single trailing
 *       newline on the resource is ignored). For CANONICAL formats -- protobuf canonical JSON, Avro
 *       Parsing Canonical Form, or avro's official JSON encoding of a record -- where member order
 *       and number rendering ARE part of the meaning, so a tree comparison would pass for
 *       non-canonical output.
 * </ul>
 *
 * A mismatch reports both documents (pretty-printed in {@link #assertMatches}, raw text in {@link
 * #assertCanonicalMatches}).
 */
public final class JsonGolden {

  private static final JsonMapper MAPPER = new JsonMapper();

  private JsonGolden() {}

  /**
   * Asserts that {@code actualJson} equals the expected resource as a JSON tree.
   *
   * @param resource the expected document's path under {@code /json/}, e.g. {@code
   *     "jackson/full.json"}
   * @param actualJson the rendered JSON text
   * @throws IOException if the resource is missing
   */
  public static void assertMatches(String resource, String actualJson) throws IOException {
    JsonNode expected = MAPPER.readTree(loadText(resource));
    JsonNode actual = MAPPER.readTree(actualJson);
    assertEquals(
        expected,
        actual,
        () ->
            "JSON differs from /json/"
                + resource
                + "\nexpected:\n"
                + pretty(expected)
                + "\nactual:\n"
                + pretty(actual));
  }

  /**
   * Asserts that {@code actualText} equals the expected resource exactly, character for character
   * (a single trailing newline on the resource is ignored). For canonical formats: member order,
   * number rendering, and whitespace are all part of the asserted meaning.
   *
   * @param resource the expected document's path under {@code /json/}, e.g. {@code
   *     "canonicalformat/probe.json"}
   * @param actualText the rendered canonical text (no trailing newline)
   * @throws IOException if the resource is missing
   */
  public static void assertCanonicalMatches(String resource, String actualText) throws IOException {
    String expected = loadText(resource);
    assertEquals(
        expected,
        actualText,
        () ->
            "canonical text differs from /json/"
                + resource
                + "\nexpected:\n"
                + expected
                + "\nactual:\n"
                + actualText);
  }

  private static String pretty(JsonNode node) {
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
  }

  /** Reads the resource as text, ignoring a single trailing newline (POSIX convention). */
  private static String loadText(String resource) throws IOException {
    try (InputStream in = JsonGolden.class.getResourceAsStream("/json/" + resource)) {
      if (in == null) {
        throw new IOException("missing golden resource /json/" + resource);
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (text.endsWith("\n")) {
        text = text.substring(0, text.length() - 1);
      }
      return text;
    }
  }
}
