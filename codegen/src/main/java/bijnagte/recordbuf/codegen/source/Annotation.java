package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * A first-class AST annotation -- a reference to its type plus an ordered list of {@link Arg args}.
 *
 * <p>Every annotatable node (the {@link Type} variants, {@link Component}, {@link Method}, {@link
 * Param}, {@link LocalVar}, and {@link EnumConstant}) carries a {@code List<Annotation>}; a single
 * {@link SourceRenderer render path} emits the whole list -- one annotation per line, before the
 * node's Javadoc -- instead of the previous ad-hoc per-annotation handling: the {@code @Deprecated}
 * marker fields, the {@code @Override} marker on {@link Method}, the raw {@code List<String>}
 * annotation list on a {@link Type.Class}, and the {@code @Nullable} string splice on type
 * expressions, all of which are now instances of this one model.
 *
 * <p>The annotation's type is stored as an eager fully-qualified name string and resolved through
 * the collector's {@code ci.use(...)} at render time -- the same import mechanism that a type
 * string uses. Making this {@code type} a structured reference is the later AST-structuring pass's
 * job, not this migration's.
 *
 * <p>The no-argument annotations the generator emits today are exposed as constants; parameterized
 * forms such as {@code @SuppressWarnings({"a", "b"})} are constructed via {@link #of}.
 */
public record Annotation(String type, List<Arg> args) {

  /** The {@code @Deprecated} marker (no arguments). */
  public static final Annotation DEPRECATED = new Annotation("java.lang.Deprecated", List.of());

  /** The {@code @Override} marker (no arguments). */
  public static final Annotation OVERRIDE = new Annotation("java.lang.Override", List.of());

  /** The JSpecify {@code @Nullable} marker (a type-use annotation, no arguments). */
  public static final Annotation NULLABLE =
      new Annotation("org.jspecify.annotations.Nullable", List.of());

  /**
   * Build a parameterized annotation such as {@code @SuppressWarnings(...)}.
   *
   * @param type the fully-qualified annotation type (eager, resolved at render via {@code ci.use})
   * @param args the ordered arguments, rendered inside the annotation's parentheses
   * @return the annotation
   */
  public static Annotation of(String type, Arg... args) {
    return new Annotation(type, List.of(args));
  }
}
