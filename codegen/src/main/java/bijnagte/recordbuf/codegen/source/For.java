package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.ResolutionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A for-each loop: {@code for (<pattern> : <iterable>) { ... }}. {@link #pattern()} holds the full
 * declaration pattern, e.g. {@code "var _e"} or {@code "byte[] _e"}; {@link #iterable()} is the
 * collection expression as a {@link RawExpr} (resolved at render time). {@link #body()} is the
 * element list laid out at the next indent. The renderer always braces the body (even a single
 * element), a functional-equivalent style normalisation.
 *
 * <p>Entry points: {@link #enhanced(String, String)} for a static iterable; {@link
 * #enhanced(String, String, Object[])} for a {@code @N}-templated iterable. The {@link #iterable()}
 * field is a {@link RawExpr}, so the collect phase can contribute type refs and the render phase
 * can resolve positional args against the {@link ResolutionContext}.
 */
public record For(String pattern, RawExpr iterable, List<Element> body) implements Element {

  public For {
    Objects.requireNonNull(pattern);
    Objects.requireNonNull(iterable);
    body = body == null ? List.of() : List.copyOf(body);
  }

  /** Entry point: seed a builder with a static iterable string. */
  public static Builder enhanced(String pattern, String iterable) {
    return new Builder(pattern, RawExpr.of(iterable));
  }

  /** Entry point: seed a builder with a {@code @N}-templated iterable string. */
  public static Builder enhanced(String pattern, String iterableTemplate, Object... iterArgs) {
    return new Builder(pattern, RawExpr.of(iterableTemplate, iterArgs));
  }

  /**
   * An accumulating, ergonomic {@link For} builder; the pattern is static and the iterable is a
   * {@link RawExpr} supplied at construction.
   */
  public static final class Builder {
    private final String pattern;
    private final RawExpr iterable;
    private List<Element> body = List.of();

    public Builder(String pattern, RawExpr iterable) {
      this.pattern = pattern;
      this.iterable = iterable;
    }

    /** Append one or more body elements; calls accumulate. */
    public Builder body(Element... s) {
      List<Element> next = new ArrayList<>(this.body);
      for (Element st : s) next.add(st);
      this.body = next;
      return this;
    }

    /** Append an existing sequence of body elements; calls accumulate. */
    public Builder body(List<Element> s) {
      List<Element> next = new ArrayList<>(this.body);
      if (s != null) next.addAll(s);
      this.body = next;
      return this;
    }

    /** Construct the {@link For}. */
    public For build() {
      return new For(pattern, iterable, body);
    }
  }
}
