package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A for-each loop: {@code for (<pattern> : <iterable>) { ... }}. {@link #pattern()} holds the full
 * declaration pattern, e.g. {@code "var _e"} or {@code "byte[] _e"}; {@link #iterable()} is the
 * collection expression; {@link #body()} is the element list laid out at the next indent. The
 * renderer always braces the body (even a single element), a functional-equivalent style
 * normalisation. The entry point is {@link #enhanced(String, String)}, which seeds a {@link
 * Builder} with the pattern and iterable; the builder then offers {@link Builder#body(Element[])}.
 */
public record For(String pattern, String iterable, List<Element> body) implements Element {

  public For {
    Objects.requireNonNull(pattern);
    Objects.requireNonNull(iterable);
    body = body == null ? List.of() : List.copyOf(body);
  }

  /** Entry point: seed a builder with the for-each pattern and iterable. */
  public static Builder enhanced(String pattern, String iterable) {
    return new Builder(pattern, iterable);
  }

  /**
   * An accumulating, ergonomic {@link For} builder; the pattern and iterable are supplied at
   * construction.
   */
  public static final class Builder {
    private final String pattern;
    private final String iterable;
    private List<Element> body = List.of();

    public Builder(String pattern, String iterable) {
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
