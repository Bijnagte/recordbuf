package bijnagte.recordbuf.codegen.source;

import bijnagte.recordbuf.codegen.SourceRenderer;
import java.util.ArrayList;
import java.util.List;

// AvoidCommonTypeNames is suppressed on this type: Record/Class/Enum are the source-AST's
// deliberate
// domain names, modeling Java's own 'record'/'class'/'enum' declaration keywords. They are
// referenced
// as Type.Record/Type.Class/Type.Enum throughout every mapper and the renderer, so renaming them
// would
// ripple across the codebase with no readability gain.

/**
 * The source-AST node for a single top-level or nested type declaration. Each variant ({@link
 * Record}, {@link Class}, {@link Enum}, {@link Interface}) carries its own header fields and its
 * own <em>typed body</em>: the member lists that kind can have. A record heads its body with {@link
 * Component components}; an enum heads its body with {@link EnumConstant constants}; a class holds
 * fields, constructors, static blocks and methods; an interface holds methods and nested types
 * only.
 *
 * <p>The interface itself exposes only what every variant shares -- {@link #name()}, {@link
 * #mods()}, {@link #javadoc()}, {@link #annotations()}, and {@link #nested()} (every variant may
 * nest types). Every variant carries a uniform {@code List<Annotation>} ({@link #annotations()}); a
 * single {@link SourceRenderer} path emits it (one annotation per line, before the node's Javadoc),
 * so the ad-hoc {@code @Deprecated}/{@code @Override}/${@code @SuppressWarnings}/${@code @Nullable}
 * handling collapses into one model. Each variant is built via a hand-written, ergonomic nested
 * {@code Builder} ({@code Type.Record.Builder}, ...); the compact constructors still seed optional
 * fields to their null-safe defaults, so {@code new Record(...)} callers work unchanged.
 *
 * <p>Every builder keeps a transient {@code deprecated(boolean)} sugar that, when true, adds the
 * {@link Annotation#DEPRECATED} marker to that node's list -- the previous per-node boolean flag
 * now lives as an {@link Annotation} instance, not a parallel field.
 */
@SuppressWarnings("AvoidCommonTypeNames")
public sealed interface Type {
  String name();

  Modifiers mods();

  Javadoc javadoc();

  /**
   * The uniform annotation list the node carries (the record/class/interface/enum and -- through
   * the same model -- their components, methods, params, and enum constants). Empty when none, so a
   * node without annotations renders none.
   */
  List<Annotation> annotations();

  /** The nested types (each a nested {@link Type}); every variant may nest. */
  List<Type> nested();

  /**
   * A record. Components are {@link Component}s in the record header; the body may hold static
   * blocks, constructors, methods and nested types.
   */
  record Record(
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
    public Record {
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

    /** An accumulating, ergonomic {@link Record} builder. */
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

      /** Construct the {@link Record}. */
      public Type.Record build() {
        return new Type.Record(
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

  /**
   * A {@code class Name [extends ...] [implements ...] { body }}. A class holds arbitrary fields,
   * constructors, static blocks, methods and nested types. The static {@code Codec} class
   * (per-message encode/decode) renders as a {@code Type.Class} nested inside the message's record.
   * A class renders {@code extends} (unlike a record, which has no {@code extends}); a record has
   * no {@code extends} clause.
   */
  record Class(
      Modifiers mods,
      Javadoc javadoc,
      List<Annotation> annotations,
      String name,
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
    public Class {
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

    /** A fresh class builder. */
    public static Builder named(String name) {
      return new Builder(name);
    }

    /** An accumulating, ergonomic {@link Class} builder. */
    public static final class Builder {
      public Builder(String name) {
        this.name = name;
      }

      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private final List<Annotation> annotations = new ArrayList<>();
      private final String name;
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

      /** Construct the {@link Class}. */
      public Type.Class build() {
        return new Type.Class(
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

  /**
   * A {@code enum Name [implements ...] { constants; body }}. The constant members are {@link
   * EnumConstant}s (rendered first, comma/semicolon-terminated); the remaining members (fields,
   * static blocks, constructors, methods, nested) follow the {@code ;} that terminates the constant
   * list, in canonical order.
   */
  record Enum(
      Modifiers mods,
      Javadoc javadoc,
      List<Annotation> annotations,
      String name,
      List<TypeRef> implementsFqns,
      List<EnumConstant> enumConstants,
      List<Component> fields,
      List<StaticBlock> staticBlocks,
      List<Constructor> constructors,
      List<Method> methods,
      List<Type> nested)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, annotations/implementsFqns + every body list -> empty sets.
    public Enum {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      annotations = annotations == null ? List.of() : annotations;
      implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
      enumConstants = enumConstants == null ? List.of() : enumConstants;
      fields = fields == null ? List.of() : fields;
      staticBlocks = staticBlocks == null ? List.of() : staticBlocks;
      constructors = constructors == null ? List.of() : constructors;
      methods = methods == null ? List.of() : methods;
      nested = nested == null ? List.of() : nested;
    }

    /** A fresh enum builder. */
    public static Builder named(String name) {
      return new Builder(name);
    }

    /** An accumulating, ergonomic {@link Enum} builder. */
    public static final class Builder {
      public Builder(String name) {
        this.name = name;
      }

      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private final List<Annotation> annotations = new ArrayList<>();
      private final String name;
      private final List<TypeRef> implementsFqns = new ArrayList<>();
      private final List<EnumConstant> enumConstants = new ArrayList<>();
      private final List<Component> fields = new ArrayList<>();
      private final List<StaticBlock> staticBlocks = new ArrayList<>();
      private final List<Constructor> constructors = new ArrayList<>();
      private final List<Method> methods = new ArrayList<>();
      private final List<Type> nested = new ArrayList<>();

      /** The enum's own Javadoc. */
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

      /** Mark the enum {@code @Deprecated} (adds the {@link Annotation#DEPRECATED} marker). */
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

      /** Append one or more enum constants; calls accumulate. */
      public Builder enumConstant(EnumConstant... cs) {
        for (EnumConstant c : cs) this.enumConstants.add(c);
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

      /** Construct the {@link Enum}. */
      public Type.Enum build() {
        return new Type.Enum(
            mods.build(),
            javadoc,
            List.copyOf(annotations),
            name,
            List.copyOf(implementsFqns),
            List.copyOf(enumConstants),
            List.copyOf(fields),
            List.copyOf(staticBlocks),
            List.copyOf(constructors),
            List.copyOf(methods),
            List.copyOf(nested));
      }
    }
  }

  /**
   * A {@code interface Name [extends ...] [permits ...] { body }}. The source-AST surface for the
   * oneof sealed interfaces. The {@code extendsFqns} field carries the {@code extends} FQNs; {@code
   * permitsFqns} carries the {@code permits} FQNs. An interface holds only methods and nested types
   * -- no fields, constructors or static blocks.
   */
  record Interface(
      Modifiers mods,
      Javadoc javadoc,
      List<Annotation> annotations,
      String name,
      List<TypeRef> extendsFqns,
      List<TypeRef> permitsFqns,
      List<Method> methods,
      List<Type> nested)
      implements Type {
    public Interface {
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

    /** An accumulating, ergonomic {@link Interface} builder. */
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

      /** Construct the {@link Interface}. */
      public Type.Interface build() {
        return new Type.Interface(
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
}
