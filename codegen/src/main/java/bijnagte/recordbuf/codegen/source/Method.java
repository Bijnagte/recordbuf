package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A method. {@code mods returnType name(params)[ throws thrown] { body }}. Built via the
 * hand-written fluent {@link Builder}, so a caller sets only the elements it needs; the <em>compact
 * constructor</em> seeds the optional fields (javadoc, modifiers, {@code throws} types, body) to
 * their defaults when the caller leaves them unset, which keeps the record's accessors non-null.
 * Each {@code thrown} type is resolved through the collector; a non-empty list renders a {@code
 * throws} clause between the params and the block. An {@code abstract} method (the {@code
 * interface} case) renders the declaration followed by {@code ;} and no block.
 */
public record Method(
    Javadoc javadoc,
    Modifiers mods,
    String returnType,
    String name,
    List<Param> params,
    List<String> thrown,
    List<Element> body,
    boolean abstractDecl,
    boolean override) {
  // The builder leaves the optional fields unset (null); the compact constructor seeds them to
  // their defaults so a caller sets only what it needs and the accessors stay non-null.
  public Method {
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    mods = mods == null ? Modifiers.none() : mods;
    params = params == null ? List.of() : params;
    thrown = thrown == null ? List.of() : thrown;
    body = body == null ? List.of() : body;
    returnType = returnType == null ? "void" : returnType;
  }

  /**
   * Entry point: a method identified by {@link #name}, the one always-required field, taken at
   * construction — the optional elements accumulate as before.
   */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /** An accumulating, ergonomic {@link Method} builder. */
  public static final class Builder {
    private Javadoc javadoc;
    private final Modifiers.Builder mods = Modifiers.builder();
    private String returnType;
    private final String name;
    private final List<Param> params = new ArrayList<>();
    private final List<String> thrown = new ArrayList<>();
    private final List<Element> body = new ArrayList<>();
    private boolean abstractDecl;
    private boolean override;

    /** Seed the {@link #name} (the one always-required field); the rest accumulate. */
    public Builder(String name) {
      this.name = name;
    }

    /** The method's Javadoc. */
    public Builder javadoc(Javadoc d) {
      this.javadoc = d;
      return this;
    }

    /** Set the return type. */
    public Builder returnType(String rt) {
      this.returnType = rt;
      return this;
    }

    /** Append one parameter (type + name); calls accumulate, and no params renders {@code ()}. */
    public Builder param(String type, String name) {
      this.params.add(new Param(type, name));
      return this;
    }

    /** Append one {@code throws} type. */
    public Builder thrown(String t) {
      this.thrown.add(t);
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

    /** Mark this an abstract declaration ({@code ;}, no block). */
    public Builder abstractDecl(boolean a) {
      this.abstractDecl = a;
      return this;
    }

    /** Render {@code @Override} before the modifier line. */
    public Builder override(boolean o) {
      this.override = o;
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

    /** Set the {@code default} flag. */
    public Builder default_() {
      mods.default_();
      return this;
    }

    /** Construct the {@link Method}. */
    public Method build() {
      return new Method(
          javadoc,
          mods.build(),
          returnType,
          name,
          List.copyOf(params),
          List.copyOf(thrown),
          List.copyOf(body),
          abstractDecl,
          override);
    }
  }
}
