package bijnagte.recordbuf.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.runtime.FieldMask;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link JsonFieldMaskFilter}: keep only the masked paths of a JSON tree. */
class JsonFieldMaskFilterTest {

  private static final JsonMapper M = new JsonMapper();

  private static final JsonNode USER =
      M.readTree(
          """
          {"id": 7,
           "displayName": "Ada",
           "address": {"city": "London", "postCode": "N1", "geo": {"lat": 1.5, "lon": 2.5}},
           "orders": [{"sku": "a", "qty": 1}, {"sku": "b", "qty": 2}],
           "tags": ["x", "y"],
           "nickname": null,
           "unknownExtra": true}
          """);

  private static void assertFilters(String expected, String... paths) {
    assertEquals(M.readTree(expected), JsonFieldMaskFilter.filter(USER, FieldMask.of(paths)));
  }

  @Test
  void topLevelPathsKeepWholeValues() {
    assertFilters(
        """
        {"id": 7, "address": {"city": "London", "postCode": "N1", "geo": {"lat": 1.5, "lon": 2.5}}}
        """,
        "id",
        "address");
  }

  @Test
  void snakeCasePathsSelectLowerCamelProperties() {
    assertFilters(
        """
        {"displayName": "Ada", "address": {"postCode": "N1"}}
        """,
        "display_name",
        "address.post_code");
  }

  @Test
  void nestedPathsKeepTheirParents() {
    assertFilters(
        """
        {"address": {"city": "London", "geo": {"lat": 1.5}}}
        """,
        "address.city",
        "address.geo.lat");
  }

  @Test
  void aPathEndingAtAParentWinsOverItsChildren() {
    assertFilters(
        """
        {"address": {"city": "London", "postCode": "N1", "geo": {"lat": 1.5, "lon": 2.5}}}
        """,
        "address",
        "address.city");
  }

  @Test
  void pathsIntoAnArrayApplyToEachElement() {
    assertFilters(
        """
        {"orders": [{"sku": "a"}, {"sku": "b"}], "tags": ["x", "y"]}
        """,
        "orders.sku",
        "tags");
  }

  @Test
  void aParentWithNothingSelectedBelowItIsKept() {
    assertFilters(
        """
        {"address": {}}
        """,
        "address.missing");
  }

  @Test
  void aPathIntoAScalarOrNullSelectsNothing() {
    assertFilters("{}", "id.value", "nickname.first");
  }

  @Test
  void anExplicitNullIsKeptWhenNamed() {
    assertFilters(
        """
        {"nickname": null}
        """,
        "nickname");
  }

  @Test
  void anEmptyMaskKeepsEverythingAndTheInputIsUntouched() {
    JsonNode before = USER.deepCopy();

    assertEquals(USER, JsonFieldMaskFilter.filter(USER, FieldMask.empty()));
    JsonFieldMaskFilter.filter(USER, FieldMask.of("id"));
    assertEquals(before, USER);
  }

  @Test
  void aRootArrayAppliesTheMaskToEachElement() {
    assertEquals(
        M.readTree("[{\"sku\": \"a\"}, {\"sku\": \"b\"}]"),
        JsonFieldMaskFilter.filter(USER.get("orders"), FieldMask.of("sku")));
  }
}
