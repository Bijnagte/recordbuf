package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A constructor: {@code mods params { body}} — the name is the enclosing type's name (a Java law),
 * so the renderer supplies it rather than the node storing it.
 */
public record Constructor(Javadoc javadoc, Modifiers mods, List<Param> params, List<Element> body) {
  // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
  // them:
  // javadoc -> empty, mods -> none, body -> empty list (an empty ctor body, e.g. a private no-arg
  // Codec ctor). `params` is required.
  public Constructor {
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    mods = mods == null ? Modifiers.none() : mods;
    params = params == null ? List.of() : params;
    body = body == null ? List.of() : body;
  }

  /** A fresh constructor builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** An accumulating, ergonomic {@link Constructor} builder. */
  public static final class Builder {
    private Javadoc javadoc;
    private final Modifiers.Builder mods = Modifiers.builder();
    private final List<Param> params = new ArrayList<>();
    private final List<Element> body = new ArrayList<>();

    /** The constructor's Javadoc. */
    public Builder javadoc(Javadoc d) {
      this.javadoc = d;
      return this;
    }

    /** Append one parameter (type + name); calls accumulate, and no params renders {@code ()}. */
    public Builder param(String type, String name) {
      this.params.add(Param.of(type, name));
      return this;
    }

    /** Append one or more body elements; calls accumulate. */
    public Builder body(Element... elements) {
      for (Element s : elements) {
        this.body.add(s);
      }
      return this;
    }

    /** Append an existing sequence of body elements; calls accumulate. */
    public Builder body(Iterable<Element> elements) {
      if (elements != null) {
        for (Element s : elements) {
          this.body.add(s);
        }
      }
      return this;
    }

    /** Set the {@code public} visibility. */
    public Builder public_() {
      mods.public_();
      return this;
    }

    /** Set the {@code private} visibility. */
    public Builder private_() {
      mods.private_();
      return this;
    }

    /** Set the visibility dimension directly. */
    public Builder visibility(Modifiers.Visibility v) {
      mods.visibility(v);
      return this;
    }

    /** Set the {@code static} flag. */
    public Builder static_() {
      mods.static_();
      return this;
    }

    /** Set the {@code final} flag. */
    public Builder final_() {
      mods.final_();
      return this;
    }

    /** Construct the {@link Constructor}. */
    public Constructor build() {
      return new Constructor(javadoc, mods.build(), List.copyOf(params), List.copyOf(body));
    }
  }
}
