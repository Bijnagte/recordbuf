package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code if ... else if ... else} control-flow element. The {@link #condition()} guards the
 * then-branch; zero or more {@link #elseIf()} clauses chain {@code else if (c) { ... }}, and a
 * terminal {@link #elseBody()} renders the final {@code else { ... }}. Every branch is a {@link
 * List} of {@link Element}s laid out at the next indent. The {@link #body()} and {@link
 * #elseBody()} lists are <em>seeded to empty</em> so a caller sets only the branches it needs; an
 * empty branch renders no clause. The entry point is {@link #condition(String)} for static guards,
 * or {@link #condition(String, Object[])} for {@code @N}-templated guards. The {@link #condition()}
 * field is a {@link RawExpr}, so the collect phase can contribute type refs and the render phase
 * can resolve positional args against the {@link ResolutionContext}.
 *
 * <p>For {@code else if} clauses: {@link Builder#elseIf(RawExpr, Element[])} is the primary API; a
 * caller needing a {@code @N}-templated guard builds it with {@code RawExpr.of(template, args)} and
 * passes the result. {@link Builder#elseIf(String, Element[])} and {@link Builder#elseIf(String,
 * List)} are static-guard convenience overloads.
 */
public record If(RawExpr condition, List<Element> body, List<ElseIf> elseIf, List<Element> elseBody)
    implements Element {

  public If {
    body = body == null ? List.of() : List.copyOf(body);
    elseIf = elseIf == null ? List.of() : List.copyOf(elseIf);
    elseBody = elseBody == null ? List.of() : List.copyOf(elseBody);
  }

  /** Entry point: seed a builder with a static guard expression. */
  public static Builder condition(String condition) {
    return new Builder(RawExpr.of(condition));
  }

  /** Entry point: seed a builder with a {@code @N}-templated guard expression. */
  public static Builder condition(String template, Object... args) {
    return new Builder(RawExpr.of(template, args));
  }

  /**
   * An accumulating, ergonomic {@link If} builder; the guard expression is supplied at
   * construction.
   */
  public static final class Builder {
    private final RawExpr condition;
    private List<Element> body = List.of();
    private List<ElseIf> elseIf = new ArrayList<>();
    private List<Element> elseBody = new ArrayList<>();

    public Builder(RawExpr condition) {
      this.condition = condition;
    }

    /** Append one or more then-body elements; calls accumulate. */
    public Builder body(Element... s) {
      List<Element> next = new ArrayList<>(this.body);
      for (Element st : s) next.add(st);
      this.body = next;
      return this;
    }

    /** Append an existing sequence of then-body elements; calls accumulate. */
    public Builder body(List<Element> s) {
      List<Element> next = new ArrayList<>(this.body);
      if (s != null) next.addAll(s);
      this.body = next;
      return this;
    }

    /**
     * Primary {@code else-if} API: add a clause with a {@link RawExpr} guard and one or more body
     * elements. Use this for both static guards ({@code RawExpr.of("c")}) and {@code @N}-templated
     * guards ({@code RawExpr.of(template, args)}).
     *
     * @param guard the {@link RawExpr} guard expression
     * @param s the branch elements
     * @return the same builder
     */
    public Builder elseIf(RawExpr guard, Element... s) {
      this.elseIf.add(new ElseIf(guard, s == null ? List.of() : List.of(s)));
      return this;
    }

    /**
     * Convenience overload for a static (non-templated) guard.
     *
     * @param c the guard expression (no positional args)
     * @param s the branch elements
     * @return the same builder
     */
    public Builder elseIf(String c, Element... s) {
      return elseIf(RawExpr.of(c), s);
    }

    /**
     * Convenience overload for a static guard with an existing {@code List} of branch elements.
     *
     * @param c the guard expression (no positional args)
     * @param s the branch elements (or {@code null} for an empty clause)
     * @return the same builder
     */
    public Builder elseIf(String c, List<Element> s) {
      this.elseIf.add(new ElseIf(RawExpr.of(c), s == null ? List.of() : s));
      return this;
    }

    /** Append one or more terminal-else elements; calls accumulate. */
    public Builder else_(Element... s) {
      List<Element> next = new ArrayList<>(this.elseBody);
      for (Element st : s) next.add(st);
      this.elseBody = next;
      return this;
    }

    /** Append an existing sequence of terminal-else elements; calls accumulate. */
    public Builder else_(List<Element> s) {
      List<Element> next = new ArrayList<>(this.elseBody);
      if (s != null) next.addAll(s);
      this.elseBody = next;
      return this;
    }

    /** Construct the {@link If}. */
    public If build() {
      return new If(condition, body, elseIf, elseBody);
    }
  }
}
