package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A constructor: {@code mods params { body}} — the name is the enclosing type's name (a Java law),
 * so the renderer supplies it rather than the node storing it.
 *
 * <p>A {@linkplain #compact() compact} constructor omits the parameter list AND the parens entirely
 * ({@code Name { body }} rather than {@code Name() { body }}) — the record-compact-constructor
 * form, in which the enclosing record's component names are in scope and assignable. Only
 * meaningful on a record; on a non-record type a no-param constructor always renders its {@code
 * ()}.
 */
public record Constructor(
    Javadoc javadoc, Modifiers mods, List<Param> params, List<Element> body, boolean compact) {
  // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
  // them:
  // javadoc -> empty, mods -> none, body -> empty list (an empty ctor body, e.g. a private no-arg
  // Codec ctor). `params` is required. A convenience 4-arg form (non-compact) keeps the
  // historical shape for callers that never build a record-compact constructor.
  public Constructor(Javadoc javadoc, Modifiers mods, List<Param> params, List<Element> body) {
    this(javadoc, mods, params, body, false);
  }

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
    private boolean compact = false;

    /** The constructor's Javadoc. */
    public Builder javadoc(Javadoc d) {
      this.javadoc = d;
      return this;
    }

    /** Append one parameter by type + name; calls accumulate, no params renders {@code ()}. */
    public Builder param(TypeRef type, String name) {
      this.params.add(Param.of(type, name));
      return this;
    }

    /** Append a fully-structured parameter (type + name + annotations); calls accumulate. */
    public Builder param(Param param) {
      this.params.add(param);
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

    /**
     * Mark this a record-compact constructor: no parameter list, no parens ({@code Name { body }}),
     * the component-name-assignable normalization form. Only valid on a record.
     */
    public Builder compact() {
      this.compact = true;
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
      return new Constructor(
          javadoc, mods.build(), List.copyOf(params), List.copyOf(body), compact);
    }
  }
}
