package bijnagte.recordbuf.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The native Java type a generated {@code google.protobuf.FieldMask} field resolves to.
 *
 * <p>A {@code FieldMask} is, semantically, a normalized set of dotted field paths. Per R3 / AE3
 * this type captures that semantics directly rather than mirroring a raw {@code List<String>}: the
 * paths are, on the way in, each lowercased, dot-trimmed, empty-dropped, and de-duplicated. Two
 * {@code FieldMask} values that name the same paths in any casing, ordering, or with differing
 * dot-padding compare <em>equal</em> — a content model that mirrors proto's {@code FieldMask}
 * semantics (R3 says the value model, not a raw field list, owns the semantics).
 *
 * <p>The mask keeps the order in which each path first appeared, and the wire codec and the JSON
 * forms emit them in that order, as protobuf-java does for the same input. Equality and hash code
 * ignore the order, so two masks with the same paths are equal even when they print differently.
 */
public final class FieldMask {

  private static final FieldMask EMPTY = new FieldMask(List.of());

  private final List<String> paths;
  private final List<String> sorted;

  private FieldMask(List<String> paths) {
    this.paths = paths;
    List<String> copy = new ArrayList<>(paths);
    Collections.sort(copy);
    this.sorted = Collections.unmodifiableList(copy);
  }

  /**
   * Builds a {@code FieldMask} from raw path strings, normalizing each: lowercased, dot-trimmed,
   * empty-dropped, then the whole set de-duplicated keeping each path's first position.
   *
   * @param rawPaths the raw path strings; {@code null} is treated as "no paths"
   * @return the normalized field mask
   */
  public static FieldMask of(Iterable<String> rawPaths) {
    if (rawPaths == null) return EMPTY;
    List<String> normalized = new ArrayList<>();
    for (String raw : rawPaths) {
      if (raw == null) continue;
      String trimmed = trimDots(raw.toLowerCase(Locale.ROOT));
      if (!trimmed.isEmpty()) normalized.add(trimmed);
    }
    List<String> deduped = new ArrayList<>(new LinkedHashSet<>(normalized));
    return deduped.isEmpty() ? EMPTY : new FieldMask(Collections.unmodifiableList(deduped));
  }

  /**
   * Builds a {@code FieldMask} from a vararg of raw path strings.
   *
   * @param rawPaths the raw path strings
   * @return the normalized, canonical field mask
   */
  public static FieldMask of(String... rawPaths) {
    return of(List.of(rawPaths));
  }

  /**
   * Returns the shared, empty {@code FieldMask} (no paths).
   *
   * @return the shared empty instance
   */
  public static FieldMask empty() {
    return EMPTY;
  }

  /**
   * Returns the normalized, de-duplicated path list this mask holds, in first-seen order.
   *
   * @return the unmodifiable paths
   */
  public List<String> paths() {
    return paths;
  }

  /**
   * Whether this mask names no paths.
   *
   * @return whether the mask is empty
   */
  public boolean isEmpty() {
    return paths.isEmpty();
  }

  private static String trimDots(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && s.charAt(start) == '.') start++;
    while (end > start && s.charAt(end - 1) == '.') end--;
    return s.substring(start, end);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FieldMask that)) return false;
    return sorted.equals(that.sorted);
  }

  @Override
  public int hashCode() {
    return sorted.hashCode();
  }

  @Override
  public String toString() {
    return "FieldMask[" + String.join(",", paths) + "]";
  }
}
