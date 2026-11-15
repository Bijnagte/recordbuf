package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A local declaration: {@code [<annotations>] <type> <name> = <init>;} or, when {@code init} is
 * null, {@code [<annotations>] <type> <name>;}. {@code type} is a {@link TypeRef} resolved through
 * the collector at render time. {@code init} is a {@link Formattable} when non-null — the
 * initialization expression carrying its own {@code @N} positional args.
 */
public record LocalVar(List<Annotation> annotations, TypeRef type, String name, Formattable init)
    implements Element {

  /** Seed the annotation list when absent; other fields unchanged. */
  public LocalVar {
    annotations = annotations == null ? List.of() : annotations;
  }

  /**
   * Start a {@link LocalVar} builder with the type and name set.
   *
   * @param type the resolved type reference
   * @param name the local variable name
   * @return a new builder
   */
  public static Builder builder(TypeRef type, String name) {
    return new Builder(type, name);
  }

  /**
   * An accumulating, ergonomic {@link LocalVar} builder.
   *
   * <p>Usage:
   *
   * <pre>{@code
   * // With initializer:
   * LocalVar.builder(TypeRef.of("java.util.HashMap"), "_m").assigned(
   *     "new @0<>()", TypeRef.of("java.util.HashMap"));
   *
   * // Without initializer:
   * LocalVar.builder(TypeRef.int_(), "count").unassigned();
   *
   * // With a @Nullable annotation:
   * LocalVar.builder(TypeRef.of("com.example.Foo"), "f")
   *     .annotation(Annotation.NULLABLE)
   *     .assigned("switch (s) { ... }");
   * }</pre>
   */
  public static final class Builder {
    private final TypeRef type;
    private final String name;
    private final List<Annotation> annotations = new ArrayList<>();

    /**
     * @param type the resolved type reference; @param name the local variable name
     */
    Builder(TypeRef type, String name) {
      this.type = type;
      this.name = name;
    }

    /** Append one annotation. */
    public Builder annotation(Annotation a) {
      this.annotations.add(a);
      return this;
    }

    /**
     * Build with no initializer — renders as {@code <type> <name>;} (an unassigned declaration).
     */
    public LocalVar unassigned() {
      return new LocalVar(List.copyOf(annotations), type, name, null);
    }

    /**
     * Build with an {@code @N}-templated initializer expression (an assigned declaration).
     *
     * @param initTemplate the template string with {@code @N} positional placeholders
     * @param initArgs the positional args — each a {@link Formattable} or a bare {@link String}
     * @return a {@link LocalVar} with the initializer set
     * @throws IllegalArgumentException if the template contains out-of-bounds {@code @N}
     */
    public LocalVar assigned(String initTemplate, Object... initArgs) {
      return new LocalVar(List.copyOf(annotations), type, name, RawExpr.of(initTemplate, initArgs));
    }

    /**
     * Build with a pre-built {@link Formattable} initializer (an assigned declaration). Use this
     * when the initializer is a composite node (e.g. a {@link SwitchExpr}) rather than a
     * {@code @N}-templated string.
     *
     * @param init the initializer expression
     * @return a {@link LocalVar} with the initializer set
     */
    public LocalVar assigned(Formattable init) {
      return new LocalVar(List.copyOf(annotations), type, name, init);
    }
  }
}
