package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code if ... else if ... else} control-flow statement. The {@link #condition()} guards the
 * then-branch; zero or more {@link #elseIf()} clauses chain {@code else if (c) { ... }}, and a
 * terminal {@link #elseBody()} renders the final {@code else { ... }}. Every branch is a {@link
 * List} of {@link Statement}s laid out at the next indent. The {@link #body()} and {@link
 * #elseBody()} lists are <em>seeded to empty</em> so a caller sets only the branches it needs; an
 * empty branch renders no clause. The entry point is {@link #condition(String)}, which seeds a
 * {@link Builder} with the guard expression; the builder then offers {@link
 * Builder#body(Statement[])}, {@link Builder#elseIf(String, Statement[])}, and {@link
 * Builder#else_(Statement[])}.
 */
public record If(
    String condition, List<Statement> body, List<ElseIf> elseIf, List<Statement> elseBody)
    implements Statement {

  public If {
    condition = condition;
    body = body == null ? List.of() : List.copyOf(body);
    elseIf = elseIf == null ? List.of() : List.copyOf(elseIf);
    elseBody = elseBody == null ? List.of() : List.copyOf(elseBody);
  }

  /** Entry point: seed a builder with the guard expression. */
  public static Builder condition(String condition) {
    return new Builder(condition);
  }

  /**
   * An accumulating, ergonomic {@link If} builder; the guard expression is supplied at
   * construction.
   */
  public static final class Builder {
    private final String condition;
    private List<Statement> body = List.of();
    private List<ElseIf> elseIf = new ArrayList<>();
    private List<Statement> elseBody = new ArrayList<>();

    public Builder(String condition) {
      this.condition = condition;
    }

    /** Append one or more then-body statements; calls accumulate. */
    public Builder body(Statement... s) {
      List<Statement> next = new ArrayList<>(this.body);
      for (Statement st : s) next.add(st);
      this.body = next;
      return this;
    }

    /** Append an existing sequence of then-body statements; calls accumulate. */
    public Builder body(List<Statement> s) {
      List<Statement> next = new ArrayList<>(this.body);
      if (s != null) next.addAll(s);
      this.body = next;
      return this;
    }

    /**
     * Accumulate a further {@code else if} clause by adding to the chain.
     *
     * @param c the guard expression of the new {@code else if}
     * @param s the branch statements
     * @return the same builder
     */
    public Builder elseIf(String c, Statement... s) {
      this.elseIf.add(new ElseIf(c, List.of(s)));
      return this;
    }

    /**
     * Accumulate a further {@code else if} clause by adding to the chain.
     *
     * @param c the guard expression of the new {@code else if}
     * @param s the branch statements (or {@code null} for an empty clause)
     * @return the same builder
     */
    public Builder elseIf(String c, List<Statement> s) {
      this.elseIf.add(new ElseIf(c, s));
      return this;
    }

    /** Append one or more terminal-else statements; calls accumulate. */
    public Builder else_(Statement... s) {
      List<Statement> next = new ArrayList<>(this.elseBody);
      for (Statement st : s) next.add(st);
      this.elseBody = next;
      return this;
    }

    /** Append an existing sequence of terminal-else statements; calls accumulate. */
    public Builder else_(List<Statement> s) {
      List<Statement> next = new ArrayList<>(this.elseBody);
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
