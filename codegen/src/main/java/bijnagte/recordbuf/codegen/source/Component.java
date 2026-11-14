package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A record component ({@code type name}) or a type-body field. When {@code initializer} is
 * non-blank it renders after the name as {@code = initializer} -- the {@code BY_NUMBER}
 * reverse-lookup field is {@code ... BY_NUMBER = new HashMap<>();}; blank means no initializer.
 *
 * <p>The {@code type} holds a {@link TypeRef} resolved at render time; the renderer registers its
 * FQN with the {@code TypeCollector} during the collect phase and resolves it to a short name or
 * FQN at render time. The {@code initializer} string is an escape-hatch raw string rendered
 * verbatim.
 *
 * <p>The {@link #annotations()} list is the uniform first-class annotation list (e.g. {@link
 * Annotation#NULLABLE} on a presence-tracked field). A builder {@code deprecated(boolean)} sugar
 * adds the {@link Annotation#DEPRECATED} marker when true -- the previous per-field boolean flag
 * now lives as an annotation instance, not a parallel field.
 */
public record Component(
    Javadoc javadoc,
    Modifiers mods,
    List<Annotation> annotations,
    TypeRef type,
    String name,
    String initializer) {
  // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
  // them:
  // javadoc -> empty (renders nothing), mods -> none, annotations -> empty, initializer -> blank.
  // `type`/`name` are required.
  public Component {
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    mods = mods == null ? Modifiers.none() : mods;
    annotations = annotations == null ? List.of() : annotations;
    initializer = initializer == null ? "" : initializer;
  }

  /**
   * Entry point: a field identified by its {@link #type} and {@link #name} — the two required
   * elements — taken at construction; the optional ones (Javadoc, modifiers, annotations,
   * initializer) accumulate as before.
   */
  public static Builder named(String fqn, String name) {
    return new Builder(TypeRef.of(fqn), name);
  }

  /** Entry point with a pre-structured {@link TypeRef}. */
  public static Builder type(TypeRef type, String name) {
    return new Builder(type, name);
  }

  /** An accumulating, ergonomic {@link Component} builder. */
  public static final class Builder {
    private Javadoc javadoc;
    private final Modifiers.Builder mods = Modifiers.builder();
    private final List<Annotation> annotations = new ArrayList<>();
    private final TypeRef type;
    private final String name;
    private String initializer;

    /** Seed the two required fields ({@code type}, {@code name}); the rest accumulate. */
    public Builder(TypeRef type, String name) {
      this.type = type;
      this.name = name;
    }

    /**
     * Seed the two required fields from a bare FQN string (parsed to {@link TypeRef} internally).
     */
    public Builder(String fqn, String name) {
      this(TypeRef.of(fqn), name);
    }

    /** The field's Javadoc. */
    public Builder javadoc(Javadoc d) {
      this.javadoc = d;
      return this;
    }

    /** Append each annotation in the list; calls accumulate. */
    public Builder annotations(List<Annotation> anns) {
      if (anns != null) annotations.addAll(anns);
      return this;
    }

    /** Append one annotation. */
    public Builder annotation(Annotation a) {
      this.annotations.add(a);
      return this;
    }

    /** Mark the field {@code @Deprecated} (adds the {@link Annotation#DEPRECATED} marker). */
    public Builder deprecated(boolean d) {
      if (d) annotations.add(Annotation.DEPRECATED);
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
      return new Component(
          javadoc, mods.build(), List.copyOf(annotations), type, name, initializer);
    }
  }
}
