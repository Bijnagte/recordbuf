package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code while (condition) { ... }} loop. The {@link #condition()} guards each iteration and
 * {@link #body()} is the statement list laid out at the next indent. Unlike {@link If}, a {@code
 * while} has no branch and no optional {@code else} clause, so it always closes on its own brace;
 * an empty {@link #body()} renders the empty-loop case, {@code while (c) { }}. The entry point is
 * {@link #condition(String)}, which seeds a {@link Builder} with the guard expression; the builder
 * then offers {@link Builder#body(Statement[])}.
 */
public record While(String condition, List<Statement> body) implements Statement {

  public While {
    condition = condition;
    body = body == null ? List.of() : List.copyOf(body);
  }

  /** Entry point: seed a builder with the loop-condition expression. */
  public static Builder condition(String condition) {
    return new Builder(condition);
  }

  /**
   * An accumulating, ergonomic {@link While} builder; the loop condition is supplied at
   * construction.
   */
  public static final class Builder {
    private final String condition;
    private List<Statement> body = List.of();

    public Builder(String condition) {
      this.condition = condition;
    }

    /** Append one or more body statements; calls accumulate. */
    public Builder body(Statement... s) {
      List<Statement> next = new ArrayList<>(this.body);
      for (Statement st : s) next.add(st);
      this.body = next;
      return this;
    }

    /** Append an existing sequence of body statements; calls accumulate. */
    public Builder body(List<Statement> s) {
      List<Statement> next = new ArrayList<>(this.body);
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
