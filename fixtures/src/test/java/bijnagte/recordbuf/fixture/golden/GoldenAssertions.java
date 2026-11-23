package bijnagte.recordbuf.fixture.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comparison rules and the hex codec for goldens.
 *
 * <ul>
 *   <li>JSON ({@link #assertJsonMatches}): both texts are parsed to a tree and pretty-printed with
 *       the same printer, then the printed texts are compared. Member order, structure, number
 *       types and values count; whitespace and indentation do not.
 *   <li>Binary ({@link #assertBinaryMatches}): byte for byte, reporting the first differing offset.
 *   <li>Hex ({@link #encodeHex}, {@link #decodeHex}): lowercase, {@value #BYTES_PER_LINE} bytes per
 *       line, one final newline. The decoder rejects anything else, so a hand-edited golden fails
 *       loudly instead of being quietly accepted.
 * </ul>
 */
public final class GoldenAssertions {

  /** Bytes per line in a hex golden. */
  public static final int BYTES_PER_LINE = 32;

  private static final int WINDOW_BYTES = 8;
  private static final String HEX_DIGITS = "0123456789abcdef";
  private static final JsonMapper MAPPER = new JsonMapper();

  private GoldenAssertions() {}

  /**
   * Asserts that two JSON texts are the same after normalization.
   *
   * @param expectedText the golden JSON text
   * @param actualText the JSON text produced by the code under test
   * @param label names the case and format in the failure message
   */
  public static void assertJsonMatches(String expectedText, String actualText, String label) {
    String expected = normalizeJson(expectedText);
    String actual = normalizeJson(actualText);
    assertEquals(
        expected,
        actual,
        () -> "JSON differs for " + label + "\nexpected:\n" + expected + "\nactual:\n" + actual);
  }

  /**
   * Asserts that two byte arrays are identical.
   *
   * @param expectedBytes the golden bytes
   * @param actualBytes the bytes produced by the code under test
   * @param label names the case and format in the failure message
   */
  public static void assertBinaryMatches(byte[] expectedBytes, byte[] actualBytes, String label) {
    int offset = Arrays.mismatch(expectedBytes, actualBytes);
    if (offset < 0) {
      return;
    }
    int start = Math.max(0, offset - WINDOW_BYTES);
    fail(
        "bytes differ for "
            + label
            + " at offset "
            + offset
            + " (expected "
            + expectedBytes.length
            + " bytes, actual "
            + actualBytes.length
            + " bytes)\nexpected from offset "
            + start
            + ": "
            + hexWindow(expectedBytes, start)
            + "\nactual   from offset "
            + start
            + ": "
            + hexWindow(actualBytes, start));
  }

  /**
   * Encodes bytes as a hex golden: lowercase, {@value #BYTES_PER_LINE} bytes per line, ending in a
   * single newline. Empty input encodes as a lone newline.
   */
  public static String encodeHex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2 + bytes.length / BYTES_PER_LINE + 1);
    for (int i = 0; i < bytes.length; i++) {
      if (i > 0 && i % BYTES_PER_LINE == 0) {
        out.append('\n');
      }
      out.append(HEX_DIGITS.charAt((bytes[i] >> 4) & 0xf))
          .append(HEX_DIGITS.charAt(bytes[i] & 0xf));
    }
    return out.append('\n').toString();
  }

  /**
   * Decodes a hex golden written by {@link #encodeHex}.
   *
   * @param hex the golden text
   * @param source names the golden in the failure message
   * @throws IllegalArgumentException if the text lacks its final newline, has an uppercase or
   *     non-hex character, or has a line of odd length or an empty line (other than the lone
   *     newline of an empty payload)
   */
  public static byte[] decodeHex(String hex, String source) {
    if (!hex.endsWith("\n")) {
      throw malformed(source, "missing final newline");
    }
    String body = hex.substring(0, hex.length() - 1);
    if (body.isEmpty()) {
      return new byte[0];
    }
    String[] lines = body.split("\n", -1);
    ByteArrayOutputStream out = new ByteArrayOutputStream(body.length() / 2);
    for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
      String line = lines[lineNumber];
      if (line.isEmpty()) {
        throw malformed(source, "empty line " + (lineNumber + 1));
      }
      if (line.length() % 2 != 0) {
        throw malformed(source, "odd number of hex digits on line " + (lineNumber + 1));
      }
      for (int i = 0; i < line.length(); i += 2) {
        out.write(
            nibble(line.charAt(i), source, lineNumber) << 4
                | nibble(line.charAt(i + 1), source, lineNumber));
      }
    }
    return out.toByteArray();
  }

  private static int nibble(char c, String source, int lineNumber) {
    int value = HEX_DIGITS.indexOf(c);
    if (value < 0) {
      String kind = c >= 'A' && c <= 'F' ? "uppercase hex digit" : "non-hex character";
      throw malformed(source, kind + " '" + c + "' on line " + (lineNumber + 1));
    }
    return value;
  }

  private static IllegalArgumentException malformed(String source, String problem) {
    return new IllegalArgumentException("malformed hex golden " + source + ": " + problem);
  }

  private static String normalizeJson(String text) {
    JsonNode tree = MAPPER.readTree(text);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
  }

  private static String hexWindow(byte[] bytes, int start) {
    int end = Math.min(bytes.length, start + 2 * WINDOW_BYTES + 1);
    if (start >= end) {
      return "(end of data)";
    }
    return encodeHex(Arrays.copyOfRange(bytes, start, end)).replace("\n", "").trim();
  }
}
