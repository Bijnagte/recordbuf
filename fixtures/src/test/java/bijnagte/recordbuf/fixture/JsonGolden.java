package bijnagte.recordbuf.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden-JSON assertions for the fixtures: the expected document is a test resource under {@code
 * /json/}, and the rendered JSON must equal it as a Jackson tree -- every value, every property,
 * array order included; object member order is not significant. A mismatch reports both documents
 * pretty-printed.
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
    JsonNode expected = load(resource);
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

  private static String pretty(JsonNode node) {
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
  }

  private static JsonNode load(String resource) throws IOException {
    try (InputStream in = JsonGolden.class.getResourceAsStream("/json/" + resource)) {
      if (in == null) {
        throw new IOException("missing golden resource /json/" + resource);
      }
      return MAPPER.readTree(in);
    }
  }
}
