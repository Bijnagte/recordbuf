package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A for-each loop: {@code for (<pattern> : <iterable>) { ... }}. {@link #pattern()} holds the full
 * declaration pattern, e.g. {@code "var _e"} or {@code "byte[] _e"}; {@link #iterable()} is the
 * collection expression; {@link #body()} is the statement list laid out at the next indent. The
 * renderer always braces the body (even a single statement), a functional-equivalent style
 * normalisation built via {@link #builder()}.
 */
public record For(String pattern, String iterable, List<Statement> body) implements Statement {

  public For {
    Objects.requireNonNull(pattern);
    Objects.requireNonNull(iterable);
    body = body == null ? List.of() : List.copyOf(body);
  }

  /** A fresh builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** An accumulating, ergonomic {@link For} builder. */
  public static final class Builder {
    private String pattern;
    private String iterable;
    private List<Statement> body = List.of();

    /** Set the loop pattern, e.g. {@code "var _e"}. */
    public Builder pattern(String p) {
      this.pattern = p;
      return this;
    }

    /** Set the collection expression, e.g. {@code "x.getMap().entrySet()"}. */
    public Builder iterable(String i) {
      this.iterable = i;
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

    /** Construct the {@link For}. */
    public For build() {
      Objects.requireNonNull(pattern);
      Objects.requireNonNull(iterable);
      return new For(pattern, iterable, body);
    }
  }
}
