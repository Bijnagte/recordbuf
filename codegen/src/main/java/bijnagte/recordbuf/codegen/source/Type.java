package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * The source-AST node for a single top-level or nested type declaration. Each variant ({@link
 * Record}, {@link Class}, {@link Enum}, {@link Interface}) carries its own header fields and a
 * <em>typed body</em>: the constituent members are stored in the component lists each belongs to
 * ({@link #components() components} for a record, plus {@link #fields() fields}, {@link
 * #staticBlocks() static blocks}, {@link #constructors() constructors}, {@link #methods() methods},
 * {@link #nested() nested types}, and for enums {@link #enumConstants() enum constants}). Each
 * variant is built via a hand-written, ergonomic nested {@code Builder} ({@code
 * Type.Record.Builder}, ...); the compact constructors still seed optional fields to their
 * null-safe defaults, so {@code new Record(...)} callers work unchanged.
 *
 * <p>The whole point of the typed body is that the {@link SourceRenderer} consumes the component
 * lists directly — it never has to re-partition a flat {@link Member} stream. The renderer switches
 * exhaustively over the {@link Member} variants when it renders a body, emitting fields/static
 * blocks/constructors/methods before nested types, and an enum's constants first.
 */
public sealed interface Type {
  String parentName();

  Modifiers mods();

  Javadoc javadoc();

  boolean deprecated();

  String name();

  /** An enclosing type (e.g. the {@code Codec} nested class's enclosing record). */
  default String parent() {
    return parentName();
  }

  /** The {@code extends} FQNs (empty for record/enum/interface; set for class). */
  default List<String> extendsFqns() {
    return List.of();
  }

  /** The {@code implements} FQNs (empty for class; used by record + sealed interface). */
  default List<String> implementsFqns() {
    return List.of();
  }

  /** The {@code permits} FQNs for a sealed interface (empty otherwise). */
  default List<String> permitsFqns() {
    return List.of();
  }

  /*
   * The typed body component lists. A subtype overrides only the lists it stores; the others
   * default to empty. The body order — fields, static blocks, constructors, methods, nested — is
   * the canonical order a Java body is emitted in; the record's components and the enum's constants
   * head it.
   */

  /**
   * The record components ({@code type name} in the record header). Empty for a non-record type.
   */
  default List<Component> components() {
    return List.of();
  }

  /** The type-body fields ({@code type name} declarations, for a class/enum body). */
  default List<Component> fields() {
    return List.of();
  }

  /** The {@code static { … }} initializer blocks. */
  default List<StaticBlock> staticBlocks() {
    return List.of();
  }

  /** The constructors. */
  default List<Constructor> constructors() {
    return List.of();
  }

  /** The methods. */
  default List<Method> methods() {
    return List.of();
  }

  /** The nested types (each wraps a {@link Type} via {@link Nested}). */
  default List<Nested> nested() {
    return List.of();
  }

  /** The enum constants (empty for a non-enum type). */
  default List<EnumConstant> enumConstants() {
    return List.of();
  }

  /** A record. Components are {@link Component}s; nested (non-field) members follow. */
  record Record(
      String parent,
      Modifiers mods,
      Javadoc javadoc,
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> implementsFqns,
      List<Component> components,
      List<StaticBlock> staticBlocks,
      List<Constructor> constructors,
      List<Method> methods,
      List<Nested> nested)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns + every body list ->
    // empty.
    // `deprecated` is a primitive (false by default); `name` is required.
    public Record {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
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

    @Override
    public String parentName() {
      return parent;
    }

    @Override
    public List<Component> components() {
      return components;
    }

    /** An accumulating, ergonomic {@link Record} builder. */
    public static final class Builder {
      public Builder(String name) {
        this.name = name;
      }

      private String parent;
      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private boolean deprecated;
      private final String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> implementsFqns = new ArrayList<>();
      private final List<Component> components = new ArrayList<>();
      private final List<StaticBlock> staticBlocks = new ArrayList<>();
      private final List<Constructor> constructors = new ArrayList<>();
      private final List<Method> methods = new ArrayList<>();
      private final List<Nested> nested = new ArrayList<>();

      /** Set the enclosing type name (null for a top-level record). */
      public Builder parent(String p) {
        this.parent = p;
        return this;
      }

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
      public Builder nested(Nested... ns) {
        for (Nested n : ns) this.nested.add(n);
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
            parent,
            mods.build(),
            javadoc,
            deprecated,
            name,
            List.copyOf(extendsFqns),
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
   * A {@code class Name [extends ...] [implements ...] { body }}. Its body members are stored in
   * the same typed lists a record uses: arbitrary fields/methods/constructors/static blocks/nested
   * types. The static {@code Codec} class (per-message encode/decode) renders as a {@code
   * Type.Class} nested inside the message's record. A class renders {@code extends} (unlike a
   * record, which has no {@code extends}); a record has no {@code extends} clause.
   */
  record Class(
      String parent,
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
      List<Nested> nested,
      List<String> annotations)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns/annotations + every
    // body list -> empty
    // lists.
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

    @Override
    public String parentName() {
      return parent;
    }

    /** An accumulating, ergonomic {@link Class} builder. */
    public static final class Builder {
      public Builder(String name) {
        this.name = name;
      }

      private String parent;
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
      private final List<Nested> nested = new ArrayList<>();
      private final List<String> annotations = new ArrayList<>();

      /** Set the enclosing type name (null for a top-level class). */
      public Builder parent(String p) {
        this.parent = p;
        return this;
      }

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
      public Builder nested(Nested... ns) {
        for (Nested n : ns) this.nested.add(n);
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
            parent,
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
      String parent,
      Modifiers mods,
      Javadoc javadoc,
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> implementsFqns,
      List<EnumConstant> enumConstants,
      List<Component> fields,
      List<StaticBlock> staticBlocks,
      List<Constructor> constructors,
      List<Method> methods,
      List<Nested> nested)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns + every body list -> empty
    // lists.
    public Enum {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
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

    @Override
    public String parentName() {
      return parent;
    }

    /** An accumulating, ergonomic {@link Enum} builder. */
    public static final class Builder {
      public Builder(String name) {
        this.name = name;
      }

      private String parent;
      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private boolean deprecated;
      private final String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> implementsFqns = new ArrayList<>();
      private final List<EnumConstant> enumConstants = new ArrayList<>();
      private final List<Component> fields = new ArrayList<>();
      private final List<StaticBlock> staticBlocks = new ArrayList<>();
      private final List<Constructor> constructors = new ArrayList<>();
      private final List<Method> methods = new ArrayList<>();
      private final List<Nested> nested = new ArrayList<>();

      /** Set the enclosing type name (null for a top-level enum). */
      public Builder parent(String p) {
        this.parent = p;
        return this;
      }

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

      /** Append one {@code extends} FQN. */
      public Builder extendsFqn(String fqn) {
        this.extendsFqns.add(fqn);
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
      public Builder nested(Nested... ns) {
        for (Nested n : ns) this.nested.add(n);
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
            parent,
            mods.build(),
            javadoc,
            deprecated,
            name,
            List.copyOf(extendsFqns),
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
   * A {@code interface Name [extends ...] [permits ...] { body }}. The source-AST surface for oneof
   * sealed interfaces. The {@code extendsFqns} field carries the {@code extends} FQNs; {@code
   * permitsFqns} carries the {@code permits} FQNs. The {@code implementsFqns} field is an unused
   * placeholder (interfaces cannot implement -- that is the wrong direction).
   */
  record Interface(
      String parent,
      Modifiers mods,
      Javadoc javadoc,
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> permitsFqns,
      List<Component> fields,
      List<StaticBlock> staticBlocks,
      List<Constructor> constructors,
      List<Method> methods,
      List<Nested> nested)
      implements Type {
    public Interface {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      permitsFqns = permitsFqns == null ? List.of() : permitsFqns;
      fields = fields == null ? List.of() : fields;
      staticBlocks = staticBlocks == null ? List.of() : staticBlocks;
      constructors = constructors == null ? List.of() : constructors;
      methods = methods == null ? List.of() : methods;
      nested = nested == null ? List.of() : nested;
    }

    /** A fresh interface builder. */
    public static Builder named(String name) {
      return new Builder(name);
    }

    @Override
    public String parentName() {
      return parent;
    }

    @Override
    public List<String> implementsFqns() {
      return List.of(); // interfaces cannot implement; permitsFqns are NOT imports
    }

    /** An accumulating, ergonomic {@link Interface} builder. */
    public static final class Builder {
      public Builder(String name) {
        this.name = name;
      }

      private String parent;
      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private boolean deprecated;
      private final String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> permitsFqns = new ArrayList<>();
      private final List<Component> fields = new ArrayList<>();
      private final List<StaticBlock> staticBlocks = new ArrayList<>();
      private final List<Constructor> constructors = new ArrayList<>();
      private final List<Method> methods = new ArrayList<>();
      private final List<Nested> nested = new ArrayList<>();

      /** Set the enclosing type name (null for a top-level interface). */
      public Builder parent(String p) {
        this.parent = p;
        return this;
      }

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
      public Builder nested(Nested... ns) {
        for (Nested n : ns) this.nested.add(n);
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
            parent,
            mods.build(),
            javadoc,
            deprecated,
            name,
            List.copyOf(extendsFqns),
            List.copyOf(permitsFqns),
            List.copyOf(fields),
            List.copyOf(staticBlocks),
            List.copyOf(constructors),
            List.copyOf(methods),
            List.copyOf(nested));
      }
    }
  }
}
