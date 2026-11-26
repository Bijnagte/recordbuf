package bijnagte.recordbuf.json;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reading a oneof back from its flat JSON rendering (U7 of the 2026-11-20 Jackson plan). A
 * generated record writes the set member as a top-level property under the member's JSON name, next
 * to a discriminator property naming it ({@code {"name":"blue","choice":"name"}}); an unset oneof
 * writes neither. The record's generated {@code @JsonCreator} factory receives every member
 * property and the discriminator as parameters and asks {@link #select} which member is set.
 */
public final class JsonOneof {

  private JsonOneof() {}

  /**
   * The index of the set member, or {@code -1} when the oneof is unset.
   *
   * <p>With a discriminator, it must name one of {@code names} and exactly that member's value must
   * be present. Without one, at most one member value may be present (it is the set member). Any
   * other combination -- an unknown discriminator, a discriminator naming an absent member, or a
   * second member present -- is malformed input and fails.
   *
   * @param oneof the oneof's name, for the error message
   * @param discriminator the discriminator property's value, or {@code null} when absent
   * @param names the members' JSON names, in the same order as {@code values}
   * @param values the members' bound values, {@code null} for an absent property
   * @return the set member's index, or {@code -1} for an unset oneof
   * @throws IllegalArgumentException if the properties do not describe exactly one member (or none)
   */
  public static int select(
      String oneof,
      @Nullable String discriminator,
      List<String> names,
      @Nullable Object... values) {
    if (names.size() != values.length) {
      throw new IllegalArgumentException(
          "oneof " + oneof + ": " + names.size() + " names but " + values.length + " values");
    }
    List<String> present = new ArrayList<>();
    int set = -1;
    for (int i = 0; i < values.length; i++) {
      if (values[i] != null) {
        present.add(names.get(i));
        set = i;
      }
    }
    if (present.size() > 1) {
      throw new IllegalArgumentException(
          "oneof " + oneof + " has more than one member set: " + present);
    }
    if (discriminator == null) {
      return set;
    }
    int named = names.indexOf(discriminator);
    if (named < 0) {
      throw new IllegalArgumentException(
          "oneof "
              + oneof
              + ": unknown member \""
              + discriminator
              + "\"; expected one of "
              + names);
    }
    if (set != named) {
      throw new IllegalArgumentException(
          "oneof "
              + oneof
              + ": the discriminator names \""
              + discriminator
              + "\" but "
              + (set < 0 ? "no member is set" : "\"" + names.get(set) + "\" is set"));
    }
    return set;
  }
}
