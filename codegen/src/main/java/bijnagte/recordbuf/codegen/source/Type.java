package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * The source-AST node for a single top-level or nested type declaration. Each variant ({@link
 * Record}, {@link Class}, {@link Enum}, {@link Interface}) carries its own header fields and its
 * own <em>typed body</em>: the member lists that kind can have. A record heads its body with {@link
 * Component components}; an enum heads its body with {@link EnumConstant constants}; a class holds
 * fields, constructors, static blocks and methods; an interface holds methods and nested types
 * only.
 *
 * <p>The interface itself exposes only what every variant shares -- {@link #name()}, {@link
 * #mods()}, {@link #javadoc()}, {@link #deprecated()}, and {@link #nested()} (every variant may
 * nest types). Every type-specific list -- {@code implements}, the record {@code components}, the
 * enum {@code enumConstants}, a class's fields/constructors/static blocks/annotations, an
 * interface's {@code extends}/{@code permits} -- lives on the variant that owns it, not on the
 * interface. Consequently the {@link SourceRenderer} dispatches on the concrete variant and renders
 * that variant's own lists directly; there is no flat member stream to re-partition.
 *
 * <p>Each variant is built via a hand-written, ergonomic nested {@code Builder} ({@code
 * Type.Record.Builder}, ...); the compact constructors still seed optional fields to their
 * null-safe defaults, so {@code new Record(...)} callers work unchanged.
 */
public sealed interface Type {
  String name();

  Modifiers mods();

  Javadoc javadoc();

  boolean deprecated();

  /** The nested types (each a nested {@link Type}); every variant may nest. */
  List<Type> nested();

  /**
   * A record. Components are {@link Component}s in the record header; the body may hold static
   * blocks, constructors, methods and nested types.
   */
  record Record(
      Modifiers mods,
      Javadoc javadoc,
      boolean deprecated,
      String name,
      List<String> implementsFqns,
      List<Component> components,
      List<StaticBlock> staticBlocks,
      List<Constructor> constructors,
      List<Method> methods,
      List<Type> nested)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, implementsFqns + every body list -> empty.
    public Record {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
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
      private boolean deprecated;
      private final String name;
      private final List<String> implementsFqns = new ArrayList<>();
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

      /** Mark the record {@code @Deprecated}. */
      public Builder deprecated(boolean d) {
        this.deprecated = d;
        return this;
      }

      /** Append one {@code implements} FQN. */
      public Builder implementsFqn(String fqn) {
        this.implementsFqns.add(fqn);
        return this;
      }

      /** Append all {@code implements} FQNs from a list; calls accumulate. */
      public Builder implementsFqns(List<String> fqns) {
        if (fqns != null) this.implementsFqns.addAll(fqns);
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
            deprecated,
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
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> implementsFqns,
      List<Component> fields,
      List<StaticBlock> staticBlocks,
      List<Constructor> constructors,
      List<Method> methods,
      List<Type> nested,
      List<String> annotations)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns/annotations + every
    // body list -> empty sets.
    public Class {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
      fields = fields == null ? List.of() : fields;
      staticBlocks = staticBlocks == null ? List.of() : staticBlocks;
      constructors = constructors == null ? List.of() : constructors;
      methods = methods == null ? List.of() : methods;
      nested = nested == null ? List.of() : nested;
      annotations = annotations == null ? List.of() : annotations;
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
      private boolean deprecated;
      private final String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> implementsFqns = new ArrayList<>();
      private final List<Component> fields = new ArrayList<>();
      private final List<StaticBlock> staticBlocks = new ArrayList<>();
      private final List<Constructor> constructors = new ArrayList<>();
      private final List<Method> methods = new ArrayList<>();
      private final List<Type> nested = new ArrayList<>();
      private final List<String> annotations = new ArrayList<>();

      /** The class's own Javadoc. */
      public Builder javadoc(Javadoc d) {
        this.javadoc = d;
        return this;
      }

      /** Mark the class {@code @Deprecated}. */
      public Builder deprecated(boolean d) {
        this.deprecated = d;
        return this;
      }

      /** Append one {@code extends} FQN. */
      public Builder extendsFqn(String fqn) {
        this.extendsFqns.add(fqn);
        return this;
      }

      /** Append one {@code implements} FQN. */
      public Builder implementsFqn(String fqn) {
        this.implementsFqns.add(fqn);
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

      /** Append an annotation list. */
      public Builder annotations(List<String> anns) {
        if (anns != null) annotations.addAll(anns);
        return this;
      }

      /** Append one annotation. */
      public Builder annotation(String ann) {
        this.annotations.add(ann);
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
            deprecated,
            name,
            List.copyOf(extendsFqns),
            List.copyOf(implementsFqns),
            List.copyOf(fields),
            List.copyOf(staticBlocks),
            List.copyOf(constructors),
            List.copyOf(methods),
            List.copyOf(nested),
            List.copyOf(annotations));
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
      boolean deprecated,
      String name,
      List<String> implementsFqns,
      List<EnumConstant> enumConstants,
      List<Component> fields,
      List<StaticBlock> staticBlocks,
      List<Constructor> constructors,
      List<Method> methods,
      List<Type> nested)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, implementsFqns + every body list -> empty sets.
    public Enum {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
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
      private boolean deprecated;
      private final String name;
      private final List<String> implementsFqns = new ArrayList<>();
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

      /** Mark the enum {@code @Deprecated}. */
      public Builder deprecated(boolean d) {
        this.deprecated = d;
        return this;
      }

      /** Append a {@code implements} FQN list. */
      public Builder implementsFqns(List<String> fqns) {
        if (fqns != null) implementsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code implements} FQN. */
      public Builder implementsFqn(String fqn) {
        this.implementsFqns.add(fqn);
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
            deprecated,
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
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> permitsFqns,
      List<Method> methods,
      List<Type> nested)
      implements Type {
    public Interface {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
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
      private boolean deprecated;
      private final String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> permitsFqns = new ArrayList<>();
      private final List<Method> methods = new ArrayList<>();
      private final List<Type> nested = new ArrayList<>();

      /** The interface's own Javadoc. */
      public Builder javadoc(Javadoc d) {
        this.javadoc = d;
        return this;
      }

      /** Mark the interface {@code @Deprecated}. */
      public Builder deprecated(boolean d) {
        this.deprecated = d;
        return this;
      }

      /** Append one {@code extends} FQN. */
      public Builder extendsFqn(String fqn) {
        this.extendsFqns.add(fqn);
        return this;
      }

      /** Append one {@code permits} FQN. */
      public Builder permitsFqn(String fqn) {
        this.permitsFqns.add(fqn);
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
            deprecated,
            name,
            List.copyOf(extendsFqns),
            List.copyOf(permitsFqns),
            List.copyOf(methods),
            List.copyOf(nested));
      }
    }
  }
}
