package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code while (condition) { ... }} loop. The {@link #condition()} guards each iteration and
 * {@link #body()} is the element list laid out at the next indent. Unlike {@link If}, a {@code
 * while} has no branch and no optional {@code else} clause, so it always closes on its own brace;
 * an empty {@link #body()} renders the empty-loop case, {@code while (c) { }}. The entry point is
 * {@link #condition(String)}, which seeds a {@link Builder} with the guard expression; the builder
 * then offers {@link Builder#body(Element[])}.
 */
public record While(String condition, List<Element> body) implements Element {

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
    private List<Element> body = List.of();

    public Builder(String condition) {
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
