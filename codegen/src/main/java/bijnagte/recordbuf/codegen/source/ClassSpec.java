package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code class Name [extends ...] [implements ...] { body }}. A class holds arbitrary fields,
 * constructors, static blocks, methods and nested types. The static {@code Codec} class
 * (per-message encode/decode) renders as a {@code ClassSpec} nested inside the message's record. A
 * class renders {@code extends} (unlike a record, which has no {@code extends}); a record has no
 * {@code extends} clause.
 */
public record ClassSpec(
    Modifiers mods,
    Javadoc javadoc,
    List<Annotation> annotations,
    TypeRef name,
    List<TypeRef> extendsFqns,
    List<TypeRef> implementsFqns,
    List<Component> fields,
    List<StaticBlock> staticBlocks,
    List<Constructor> constructors,
    List<Method> methods,
    List<Type> nested)
    implements Type {
  // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
  // them:
  // mods -> none, javadoc -> empty, annotations/implementsFqns/extendsFqns +
  // every body list -> empty sets.
  public ClassSpec {
    mods = mods == null ? Modifiers.none() : mods;
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    annotations = annotations == null ? List.of() : annotations;
    extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
    implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
    fields = fields == null ? List.of() : fields;
    staticBlocks = staticBlocks == null ? List.of() : staticBlocks;
    constructors = constructors == null ? List.of() : constructors;
    methods = methods == null ? List.of() : methods;
    nested = nested == null ? List.of() : nested;
  }

  /** A fresh builder from the declared type's canonical {@link TypeRef}. */
  public static Builder named(TypeRef name) {
    return new Builder(name);
  }

  /**
   * A fresh builder from a short name only — a convenience for the default package (the {@link
   * JavaFile} carries the real package); generator writers pass the model's fully-typed reference
   * instead.
   */
  public static Builder named(String name) {
    return new Builder(TypeRef.named("", name));
  }

  /** An accumulating, ergonomic {@link ClassSpec} builder. */
  public static final class Builder {
    public Builder(TypeRef name) {
      this.name = name;
    }

    private final Modifiers.Builder mods = Modifiers.builder();
    private Javadoc javadoc;
    private final List<Annotation> annotations = new ArrayList<>();
    private final TypeRef name;
    private final List<TypeRef> extendsFqns = new ArrayList<>();
    private final List<TypeRef> implementsFqns = new ArrayList<>();
    private final List<Component> fields = new ArrayList<>();
    private final List<StaticBlock> staticBlocks = new ArrayList<>();
    private final List<Constructor> constructors = new ArrayList<>();
    private final List<Method> methods = new ArrayList<>();
    private final List<Type> nested = new ArrayList<>();

    /** The class's own Javadoc. */
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

    /** Mark the class {@code @Deprecated} (adds the {@link Annotation#DEPRECATED} marker). */
    public Builder deprecated(boolean d) {
      if (d) annotations.add(Annotation.DEPRECATED);
      return this;
    }

    /** Append one {@code extends} FQN (convenience — parsed to {@link TypeRef}). */
    public Builder extendsFqn(String fqn) {
      this.extendsFqns.add(TypeRef.of(fqn));
      return this;
    }

    /** Append one {@code extends} {@link TypeRef}. */
    public Builder extend(TypeRef ref) {
      this.extendsFqns.add(ref);
      return this;
    }

    /** Append {@code extends} FQN strings (convenience — parsed to {@link TypeRef}). */
    public Builder extendsFqns(List<String> fqns) {
      if (fqns != null) fqns.forEach(f -> extendsFqns.add(TypeRef.of(f)));
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

    /** Append one or more fields; calls accumulate. */
    public Builder field(Component... fs) {
      for (Component f : fs) this.fields.add(f);
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

    /** Construct the {@link ClassSpec}. */
    public ClassSpec build() {
      return new ClassSpec(
          mods.build(),
          javadoc,
          List.copyOf(annotations),
          name,
          List.copyOf(extendsFqns),
          List.copyOf(implementsFqns),
          List.copyOf(fields),
          List.copyOf(staticBlocks),
          List.copyOf(constructors),
          List.copyOf(methods),
          List.copyOf(nested));
    }
  }
}
