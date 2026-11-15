package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code interface Name [extends ...] [permits ...] { body }}. The source-AST surface for the
 * oneof sealed interfaces. The {@code extendsFqns} field carries the {@code extends} FQNs; {@code
 * permitsFqns} carries the {@code permits} FQNs. An interface holds only methods and nested types
 * -- no fields, constructors or static blocks.
 */
public record InterfaceSpec(
    Modifiers mods,
    Javadoc javadoc,
    List<Annotation> annotations,
    String name,
    List<TypeRef> extendsFqns,
    List<TypeRef> permitsFqns,
    List<Method> methods,
    List<Type> nested)
    implements Type {
  public InterfaceSpec {
    mods = mods == null ? Modifiers.none() : mods;
    javadoc = javadoc == null ? Javadoc.empty() : javadoc;
    annotations = annotations == null ? List.of() : annotations;
    extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
    permitsFqns = permitsFqns == null ? List.of() : permitsFqns;
    methods = methods == null ? List.of() : methods;
    nested = nested == null ? List.of() : nested;
  }

  /** A fresh interface builder. */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /** An accumulating, ergonomic {@link InterfaceSpec} builder. */
  public static final class Builder {
    public Builder(String name) {
      this.name = name;
    }

    private final Modifiers.Builder mods = Modifiers.builder();
    private Javadoc javadoc;
    private final List<Annotation> annotations = new ArrayList<>();
    private final String name;
    private final List<TypeRef> extendsFqns = new ArrayList<>();
    private final List<TypeRef> permitsFqns = new ArrayList<>();
    private final List<Method> methods = new ArrayList<>();
    private final List<Type> nested = new ArrayList<>();

    /** The interface's own Javadoc. */
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

    /** Mark the interface {@code @Deprecated} (adds the {@link Annotation#DEPRECATED} marker). */
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

    /** Append one {@code permits} FQN (convenience — parsed to {@link TypeRef}). */
    public Builder permitsFqn(String fqn) {
      this.permitsFqns.add(TypeRef.of(fqn));
      return this;
    }

    /** Append one {@code permits} {@link TypeRef}. */
    public Builder permitsType(TypeRef ref) {
      this.permitsFqns.add(ref);
      return this;
    }

    /** Append {@code permits} FQN strings (convenience — parsed to {@link TypeRef}). */
    public Builder permitsFqns(List<String> fqns) {
      if (fqns != null) fqns.forEach(f -> permitsFqns.add(TypeRef.of(f)));
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

    /** Construct the {@link InterfaceSpec}. */
    public InterfaceSpec build() {
      return new InterfaceSpec(
          mods.build(),
          javadoc,
          List.copyOf(annotations),
          name,
          List.copyOf(extendsFqns),
          List.copyOf(permitsFqns),
          List.copyOf(methods),
          List.copyOf(nested));
    }
  }
}
