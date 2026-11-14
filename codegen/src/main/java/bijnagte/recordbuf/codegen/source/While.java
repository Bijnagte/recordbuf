package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.ResolutionContext;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code while (condition) { ... }} loop. The {@link #condition()} guards each iteration and
 * {@link #body()} is the element list laid out at the next indent. Unlike {@link If}, a {@code
 * while} has no branch and no optional {@code else} clause, so it always closes on its own brace;
 * an empty {@link #body()} renders the empty-loop case, {@code while (c) { }}. The entry point is
 * {@link #condition(String)} for static conditions; for {@code @N}-templated conditions use {@link
 * #condition(String, Object[])}. The {@link #condition()} field is a {@link RawExpr}, so the
 * collect phase can contribute type refs and the render phase can resolve positional args against
 * the {@link ResolutionContext}.
 */
public record While(RawExpr condition, List<Element> body) implements Element {

  public While {
    body = body == null ? List.of() : List.copyOf(body);
  }

  /** Entry point: seed a builder with a static loop-condition expression. */
  public static Builder condition(String condition) {
    return new Builder(RawExpr.of(condition));
  }

  /** Entry point: seed a builder with a {@code @N}-templated loop-condition expression. */
  public static Builder condition(String template, Object... args) {
    return new Builder(RawExpr.of(template, args));
  }

  /**
   * An accumulating, ergonomic {@link While} builder; the loop condition is supplied at
   * construction.
   */
  public static final class Builder {
    private final RawExpr condition;
    private List<Element> body = List.of();

    public Builder(RawExpr condition) {
      this.condition = condition;
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

    /** Construct the {@link While}. */
    public While build() {
      return new While(condition, body);
    }
  }
}
