package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/** A {@code static { ... }} initializer. */
public record StaticBlock(List<Element> body) {

  public StaticBlock {
    body = List.copyOf(body);
  }

  public static StaticBlock of(Element... body) {
    return new StaticBlock(List.of(body));
  }

  public static Builder builder() {
    return new Builder();
  }

  /** An accumulating, ergonomic {@link StaticBlock} builder. */
  public static final class Builder {
    private final List<Element> body = new ArrayList<>();

    public Builder body(Element... body) {
      for (Element element : body) {
        this.body.add(element);
      }

      return this;
    }

    public StaticBlock build() {
      return new StaticBlock(body);
    }
  }
}
