package bijnagte.recordbuf.codegen.source;

/**
 * A record component ({@code type name}) or a type-body field. When {@code initializer} is
 * non-blank it renders after the name as {@code = initializer} -- the {@code BY_NUMBER}
 * reverse-lookup field is {@code ... BY_NUMBER = new HashMap<>();}; blank means no initializer.
 *
 * <p>The {@code type} and {@code initializer} strings are <em>pre-resolved short form</em>: the
 * caller has already run their type references through the {@code TypeCollector} and inlined each
 * short name, and the renderer emits them verbatim.
 */
public record Component(
    Javadoc javadoc,
    Modifiers mods,
    String type,
    String name,
    boolean deprecated,
    String initializer)
    implements Member {
  // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
  // them:
  // javadoc -> empty (renders nothing), mods -> none, initializer -> blank. `deprecated` is a
  // primitive (false by default); `type`/`name` are required.
  public Component {
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    mods = mods == null ? Modifiers.none() : mods;
    initializer = initializer == null ? "" : initializer;
  }

  /**
   * Entry point: a field identified by its {@link #type} and {@link #name} — the two required
   * elements — taken at construction; the optional ones (Javadoc, modifiers, deprecation,
   * initializer) accumulate as before.
   */
  public static Builder named(String type, String name) {
    return new Builder(type, name);
  }

  /** An accumulating, ergonomic {@link Component} builder. */
  public static final class Builder {
    private Javadoc javadoc;
    private final Modifiers.Builder mods = Modifiers.builder();
    private final String type;
    private final String name;
    private boolean deprecated;
    private String initializer;

    /** Seed the two required fields ({@link #type}, {@link #name}); the rest accumulate. */
    public Builder(String type, String name) {
      this.type = type;
      this.name = name;
    }

    /** The field's Javadoc. */
    public Builder javadoc(Javadoc d) {
      this.javadoc = d;
      return this;
    }

    /** Mark the field {@code @Deprecated}. */
    public Builder deprecated(boolean d) {
      this.deprecated = d;
      return this;
    }

    /** Set the initializer expression (blank/absent means none). */
    public Builder initializer(String i) {
      this.initializer = i;
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

    /** Set the {@code sealed} flag. */
    public Builder sealed_() {
      mods.sealed_();
      return this;
    }

    /** Set the {@code default} flag. */
    public Builder default_() {
      mods.default_();
      return this;
    }

    /** Construct the {@link Component}. */
    public Component build() {
      return new Component(javadoc, mods.build(), type, name, deprecated, initializer);
    }
  }
}
