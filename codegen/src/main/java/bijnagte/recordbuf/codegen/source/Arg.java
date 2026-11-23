package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * One argument to an {@link Annotation}.
 *
 * <p>An argument is an optional name (for the {@code @X(name = <text>)} form) plus one or more
 * {@link ArgValue}s. An argument renders positionally when {@link #name} is {@code null} or {@code
 * "value"} -- mirroring Java's {@code @SuppressWarnings}, whose element is implicitly named {@code
 * "value"} -- and as {@code name = <text>} otherwise. The renderer owns the outer form: the values
 * render as a comma-joined list (a single value bare, several as a {@code {...}} array
 * initializer); the renderer supplies the string-literal quoting for a {@link ArgValue.Str}, while
 * an {@link ArgValue.Ref} renders as its collector-resolved type name plus the value's suffix.
 */
public record Arg(String name, List<ArgValue> values) {
  // `name` is nullable -- a positional argument carries no name -- so it is documented as such
  // rather than annotated, matching the codegen source's un-@NullMarked convention. The
  // compact ctor normalizes a null `values` to an empty list.
  public Arg(String name, List<ArgValue> values) {
    this.name = name;
    this.values = values == null ? List.of() : values;
  }

  /**
   * Build a string-literal argument from a varargs call site: {@code Arg.of(null, "a", "b")}.
   *
   * @param name the argument name, or {@code null} (and the {@code "value"} alias) to render
   *     positionally
   * @param values the ordered value expressions
   * @return the argument
   */
  public static Arg of(String name, String... values) {
    List<ArgValue> wrapped = new ArrayList<>(values.length);
    for (String v : values) {
      wrapped.add(ArgValue.str(v));
    }
    return new Arg(name, values == null ? List.of() : List.copyOf(wrapped));
  }

  /**
   * Build an argument from explicit values (string literals or type references).
   *
   * @param name the argument name, or {@code null} to render positionally
   * @param values the ordered values
   * @return the argument
   */
  public static Arg of(String name, ArgValue... values) {
    return new Arg(name, values == null ? List.of() : List.of(values));
  }

  /**
   * The type references this argument's values carry (each {@link ArgValue.Ref} contributes its
   * {@link ArgValue.Ref#ref()}); empty for a string-literal argument. The renderer's collect phase
   * accumulates these into the file's referral set so the type collector imports them.
   */
  public List<TypeRef> referencedTypes() {
    List<TypeRef> refs = new ArrayList<>();
    for (ArgValue v : values) {
      if (v instanceof ArgValue.Ref ref) {
        refs.add(ref.ref());
      }
    }
    return refs;
  }
}
