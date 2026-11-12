package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * One argument to an {@link Annotation}.
 *
 * <p>An argument is an optional name (for the {@code @X(name = value)} form) plus one or more value
 * expressions. A single argument renders positionally when {@link #name} is {@code null} or {@code
 * "value"} -- mirroring Java's {@code @SuppressWarnings}, whose element is implicitly named {@code
 * value} -- and as {@code name = value} otherwise. The renderer owns the outer form: the {@code
 * values} render as a comma-joined list, and the renderer supplies the string-literal quoting and
 * the {@code {…}} array initializer (a single value as a bare literal, several as {@code {"a",
 * "b"}}).
 */
public record Arg(String name, List<String> values) {
  // `name` is nullable -- a positional argument carries no name -- so it is documented as such
  // rather than annotated, matching the codegen source's un-@NullMarked convention. The
  // compact ctor normalizes a null `values` to an empty list.
  public Arg(String name, List<String> values) {
    this.name = name;
    this.values = values == null ? List.of() : values;
  }

  /**
   * Build an argument from a varargs call site: {@code Arg.of(null, "a", "b")}.
   *
   * @param name the argument name, or {@code null} (and the {@code "value"} alias) to render
   *     positionally
   * @param values the ordered value expressions
   * @return the argument
   */
  public static Arg of(String name, String... values) {
    return new Arg(name, List.of(values));
  }
}
