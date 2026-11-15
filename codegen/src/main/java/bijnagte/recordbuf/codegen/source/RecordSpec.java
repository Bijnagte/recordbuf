package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A record. Components are {@link Component}s in the record header; the body may hold static
 * blocks, constructors, methods and nested types.
 */
public record RecordSpec(
    Modifiers mods,
    Javadoc javadoc,
    List<Annotation> annotations,
    String name,
    List<TypeRef> implementsFqns,
    List<Component> components,
    List<StaticBlock> staticBlocks,
    List<Constructor> constructors,
    List<Method> methods,
    List<Type> nested)
    implements Type {
  // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
  // them:
  // mods -> none, javadoc -> empty, annotations/implementsFqns + every body list -> empty.
  public RecordSpec {
    mods = mods == null ? Modifiers.none() : mods;
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    annotations = annotations == null ? List.of() : annotations;
    implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
    components = components == null ? List.of() : components;
    staticBlocks = staticBlocks == null ? List.of() : staticBlocks;
    constructors = constructors == null ? List.of() : constructors;
    methods = methods == null ? List.of() : methods;
    nested = nested == null ? List.of() : nested;
  }

  /** A fresh record builder. */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /** An accumulating, ergonomic {@link RecordSpec} builder. */
  public static final class Builder {
    public Builder(String name) {
      this.name = name;
    }

    private final Modifiers.Builder mods = Modifiers.builder();
    private Javadoc javadoc;
    private final List<Annotation> annotations = new ArrayList<>();
    private final String name;
    private final List<TypeRef> implementsFqns = new ArrayList<>();
    private final List<Component> components = new ArrayList<>();
    private final List<StaticBlock> staticBlocks = new ArrayList<>();
    private final List<Constructor> constructors = new ArrayList<>();
    private final List<Method> methods = new ArrayList<>();
    private final List<Type> nested = new ArrayList<>();

    /** The record's own Javadoc. */
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

    /** Mark the record {@code @Deprecated} (adds the {@link Annotation#DEPRECATED} marker). */
    public Builder deprecated(boolean d) {
      if (d) annotations.add(Annotation.DEPRECATED);
      return this;
    }

    /** Append one {@code implements} FQN (convenience — parsed to {@link TypeRef}). */
    public Builder implementsFqn(String fqn) {
      this.implementsFqns.add(TypeRef.of(fqn));
      return this;
    }

    /** Append one {@code implements} {@link TypeRef}. */
    public Builder impl(TypeRef ref) {
      this.implementsFqns.add(ref);
      return this;
    }

    /** Append {@code implements} FQN strings (convenience — parsed to {@link TypeRef}). */
    public Builder implementsFqns(List<String> fqns) {
      if (fqns != null) fqns.forEach(f -> implementsFqns.add(TypeRef.of(f)));
      return this;
    }

    /** Append one or more components; calls accumulate. */
    public Builder component(Component... cs) {
      for (Component c : cs) this.components.add(c);
      return this;
    }

    /** Append one or more static blocks; calls accumulate. */
    public Builder staticBlock(StaticBlock... blocks) {
      for (StaticBlock b : blocks) this.staticBlocks.add(b);
      return this;
    }

    /** Append one or more constructors; calls accumulate. */
    public Builder constructor(Constructor... cs) {
      for (Constructor c : cs) this.constructors.add(c);
      return this;
    }

    /** Append one or more methods; calls accumulate. */
    public Builder method(Method... ms) {
      for (Method m : ms) this.methods.add(m);
      return this;
    }

    /** Append one or more nested types; calls accumulate. */
    public Builder nested(Type... ns) {
      for (Type n : ns) this.nested.add(n);
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

    /** Set the {@code protected} visibility. */
    public Builder protected_() {
      mods.protected_();
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

    /** Construct the {@link RecordSpec}. */
    public RecordSpec build() {
      return new RecordSpec(
          mods.build(),
          javadoc,
          List.copyOf(annotations),
          name,
          List.copyOf(implementsFqns),
          List.copyOf(components),
          List.copyOf(staticBlocks),
          List.copyOf(constructors),
          List.copyOf(methods),
          List.copyOf(nested));
    }
  }
}
