package bijnagte.recordbuf.json;

import bijnagte.recordbuf.runtime.FieldMask;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Filters a JSON tree down to the paths a {@link FieldMask} names -- the field-mask "keep only
 * these fields" projection, applied to a record's JSON rendering:
 *
 * <ul>
 *   <li>A path that ends at a property keeps that property's whole value.
 *   <li>A path that continues past a property keeps the property and filters its value by the rest
 *       of the path: an object is filtered recursively (kept even if nothing below it matched, so
 *       its presence survives); an array has the rest applied to each element, keeping the filtered
 *       object elements; any other value has nothing to descend into and the property is dropped.
 *   <li>Every other property is dropped -- including retained unknown properties and a oneof's
 *       discriminator unless the mask names them.
 *   <li>An empty mask keeps everything.
 * </ul>
 *
 * <p>Mask paths are matched against JSON property names regardless of case and underscores, so the
 * mask's normalized snake_case segments ({@code display_name}) select the records' lowerCamel
 * properties ({@code displayName}). The input tree is never modified; the result is a new tree.
 */
public final class JsonFieldMaskFilter {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private JsonFieldMaskFilter() {}

  /**
   * The part of {@code node} the mask selects.
   *
   * @param node the JSON tree (typically a record's rendering); an array applies the mask to each
   *     element, a scalar is returned as is
   * @param mask the paths to keep; empty keeps everything
   * @return a new tree holding only the selected paths
   */
  public static JsonNode filter(JsonNode node, FieldMask mask) {
    if (mask.isEmpty()) {
      return node.deepCopy();
    }
    Paths paths = new Paths();
    for (String path : mask.paths()) {
      paths.add(path.split("\\.", -1));
    }
    if (node.isObject()) {
      return filterObject((ObjectNode) node, paths);
    }
    if (node.isArray()) {
      return filterArray((ArrayNode) node, paths);
    }
    return node.deepCopy();
  }

  private static ObjectNode filterObject(ObjectNode node, Paths paths) {
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> property : node.properties()) {
      Paths next = paths.children.get(key(property.getKey()));
      if (next == null) {
        continue;
      }
      JsonNode value =
          next.whole ? property.getValue().deepCopy() : descend(property.getValue(), next);
      if (value != null) {
        out.set(property.getKey(), value);
      }
    }
    return out;
  }

  private static ArrayNode filterArray(ArrayNode node, Paths paths) {
    ArrayNode out = F.arrayNode();
    for (JsonNode element : node) {
      if (element.isObject()) {
        out.add(filterObject((ObjectNode) element, paths));
      }
    }
    return out;
  }

  /** A value the path continues into: objects and arrays filter, anything else is dropped. */
  private static @Nullable JsonNode descend(JsonNode value, Paths rest) {
    if (value.isObject()) {
      return filterObject((ObjectNode) value, rest);
    }
    if (value.isArray()) {
      return filterArray((ArrayNode) value, rest);
    }
    return null;
  }

  /**
   * The case- and underscore-insensitive form a mask segment and a property name are matched in.
   */
  private static String key(String name) {
    return name.replace("_", "").toLowerCase(Locale.ROOT);
  }

  /** The mask's paths as a tree of segments; {@code whole} marks a path that ends here. */
  private static final class Paths {
    private final Map<String, Paths> children = new LinkedHashMap<>();
    private boolean whole;

    private void add(String[] segments) {
      Paths at = this;
      for (String segment : segments) {
        at = at.children.computeIfAbsent(key(segment), k -> new Paths());
      }
      at.whole = true;
    }
  }
}
