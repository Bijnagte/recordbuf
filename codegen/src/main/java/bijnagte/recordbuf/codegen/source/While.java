package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code while (condition) { ... }} loop. The {@link #condition()} guards each iteration and
 * {@link #body()} is the statement list laid out at the next indent. Unlike {@link If}, a {@code
 * while} has no branch and no optional {@code else} clause, so it always closes on its own brace;
 * an empty {@link #body()} renders the empty-loop case, {@code while (c) { }}. Built via {@link
 * #builder()}.
 */
public record While(String condition, List<Statement> body) implements Statement {

  public While {
    condition = condition;
    body = body == null ? List.of() : List.copyOf(body);
  }

  /** A fresh builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** An accumulating, ergonomic {@link While} builder. */
  public static final class Builder {
    private String condition;
    private List<Statement> body = List.of();

    /** Set the loop condition expression. */
    public Builder condition(String c) {
      this.condition = c;
      return this;
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
