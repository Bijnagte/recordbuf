package bijnagte.recordbuf.codegen.source;

import java.util.List;

/**
 * An annotation instance. The type is a {@link TypeRef} (typically a {@link TypeRef.Named} or a
 * {@link TypeRef.Primitive}); the args are positional or named {@link Arg}s. The type is resolved
 * through the collector at render time via {@link TypeRef#render}.
 */
public record Annotation(TypeRef type, List<Arg> args) {

  public static Annotation of(TypeRef type, Arg... args) {
    return new Annotation(type, args == null ? List.of() : List.of(args));
  }

  /** {@code @Nullable} (JSpecify present, no args). */
  public static final Annotation NULLABLE =
      new Annotation(TypeRef.of("org.jspecify.annotations.Nullable"), List.of());

  /** {@code @Deprecated}. */
  public static final Annotation DEPRECATED =
      new Annotation(TypeRef.of("java.lang.Deprecated"), List.of());

  /** {@code @Override}. */
  public static final Annotation OVERRIDE =
      new Annotation(TypeRef.of("java.lang.Override"), List.of());

  /**
   * {@code @SuppressWarnings(names...)}. Each name is a {@link Arg} with a blank positional name
   * and the list of strings as its values (rendered as a {@code {"a", "b"}} array literal).
   */
  public static Annotation suppressWarnings(String... names) {
    if (names.length == 0)
      return new Annotation(TypeRef.of("java.lang.SuppressWarnings"), List.of());
    return new Annotation(TypeRef.of("java.lang.SuppressWarnings"), List.of(Arg.of(null, names)));
  }

  /**
   * {@code @SuppressWarnings("all")} -- the common suppress-all form. This is the canonical {@code
   * Arg}/{@code Args} form the {@code @SuppressWarnings} escape hatch now delegates to: a single
   * blank-named (positional) arg whose values carry the array literal.
   */
  public static final Annotation SUPPRESS_ALL =
      new Annotation(TypeRef.of("java.lang.SuppressWarnings"), List.of(Arg.of(null, "all")));
}
