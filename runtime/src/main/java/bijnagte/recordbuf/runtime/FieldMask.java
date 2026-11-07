package bijnagte.recordbuf.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The native Java type a generated {@code google.protobuf.FieldMask} field resolves to.
 *
 * <p>A {@code FieldMask} is, semantically, a normalized set of dotted field paths. Per R3 / AE3
 * this type captures that semantics directly rather than mirroring a raw {@code List<String>}: the
 * paths are, on the way in, each lowercased, dot-trimmed, empty-dropped, and the whole set
 * de-duplicated and sorted. Two {@code FieldMask} values that name the same paths in any casing,
 * ordering, or with differing dot-padding compare <em>equal</em>, and the same canonical set
 * encodes to the same bytes — a content model that mirrors proto's {@code FieldMask} semantics and
 * that the wire codec leans on (R3 says the value model, not a raw field list, owns the semantics).
 *
 * <p>On the wire it stays proto-compatible: the canonical, normalized paths are emitted in
 * ascending order as a repeated string field, which is byte-equal to a {@code FieldMask} built in
 * protobuf-java from the same normalized paths.
 */
public final class FieldMask {

  private static final FieldMask EMPTY = new FieldMask(List.of());

  private final List<String> paths;

  private FieldMask(List<String> paths) {
    this.paths = paths;
  }

  /**
   * Builds a {@code FieldMask} from raw path strings, normalizing each: lowercased, dot-trimmed,
   * empty-dropped, then the whole set de-duplicated and sorted.
   *
   * @param rawPaths the raw path strings; {@code null} is treated as "no paths"
   * @return the normalized, canonical field mask
   */
  public static FieldMask of(Iterable<String> rawPaths) {
    if (rawPaths == null) return EMPTY;
    List<String> normalized = new ArrayList<>();
    for (String raw : rawPaths) {
      if (raw == null) continue;
      String trimmed = trimDots(raw.toLowerCase(Locale.ROOT));
      if (!trimmed.isEmpty()) normalized.add(trimmed);
    }
    List<String> sorted = new ArrayList<>(normalized);
    Collections.sort(sorted);
    List<String> deduped = new ArrayList<>();
    for (String p : sorted) {
      if (deduped.isEmpty() || !deduped.get(deduped.size() - 1).equals(p)) deduped.add(p);
    }
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
   * Returns the canonical, normalized, sorted, de-duplicated path list this mask holds.
   *
   * @return the unmodifiable canonical paths
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
    return paths.equals(that.paths);
  }

  @Override
  public int hashCode() {
    return paths.hashCode();
  }

  @Override
  public String toString() {
    return "FieldMask[" + String.join(",", paths) + "]";
  }
}
