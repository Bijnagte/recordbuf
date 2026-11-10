package bijnagte.recordbuf.codegen.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A single top-level (or nested) type declaration the AST owns -- a {@link Record}, {@link Class},
 * {@link Enum} or an {@link Interface}. Each type carries its modifiers, optional Javadoc, name,
 * {@code extends}/{@code implements}/{@code permits} clauses, and an ordered body of {@link
 * Member}s; nested types are themselves {@code Member}s ({@link Member#Nested}).
 *
 * <p>Each variant is built via a hand-written, ergonomic nested {@code Builder} (no external
 * builder generator): the builder exposes named modifier toggles ({@code public_()}/{@code
 * static_()}/ {@code final_()}/{@code sealed_()}/{@code default_()}/{@code private_()} ...),
 * collection accumulators ({@code member()}/{@code extendsFqn()}/{@code implementsFqn()} ...), and
 * the raw component setters so a caller sets only what it needs. The compact constructors still
 * seed optional fields to their null-safe defaults, so {@code new Type.Record(...)} callers work
 * unchanged.
 */
public sealed interface Type {

  /** The enclosing {@link Type} the type is nested within, or {@code null} for a top-level type. */
  String parentName();

  /** Whether the type is {@code @Deprecated}. */
  boolean deprecated();

  /** The modifiers, in keyword order. */
  Modifiers mods();

  /** The type's own Javadoc, or an empty {@link Javadoc}. */
  Javadoc javadoc();

  /** The declared name. */
  String name();

  /** Supertype FQNs for an {@code extends} clause (records only; empty elsewhere). */
  List<String> extendsFqns();

  /** Interface FQNs for an {@code implements} clause. */
  List<String> implementsFqns();

  /** The ordered body members. */
  List<Member> members();

  /**
   * Annotations (as complete {@code @Xxx(...)} source strings) rendered before the Javadoc /
   * {@code @Deprecated} / type keyword line. The default is empty; only {@link Class} overrides it.
   */
  default List<String> annotations() {
    return List.of();
  }

  /**
   * A {@code record Name(components) [extends ...] [implements ...] { body }}. Components are the
   * {@link Member.Field} members; the rest render in the body.
   */
  record Record(
      String parent,
      Modifiers mods,
      Javadoc javadoc,
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> implementsFqns,
      List<Member> members)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns/members -> empty lists. `parent`
    // stays null for a top-level type (the renderer's "one null field"); `deprecated` and `name`
    // are
    // a primitive + required, respectively.
    public Record {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
      members = members == null ? List.of() : members;
    }

    /** A fresh record builder. */
    public static Builder builder() {
      return new Builder();
    }

    @Override
    public String parentName() {
      return parent;
    }

    /** An accumulating, ergonomic {@link Record} builder. */
    public static final class Builder {
      private String parent;
      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private boolean deprecated;
      private String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> implementsFqns = new ArrayList<>();
      private final List<Member> members = new ArrayList<>();

      /** Set the enclosing type name (null for a top-level type). */
      public Builder parent(String p) {
        this.parent = p;
        return this;
      }

      /** The type's own Javadoc. */
      public Builder javadoc(Javadoc d) {
        this.javadoc = d;
        return this;
      }

      /** Mark the type {@code @Deprecated}. */
      public Builder deprecated(boolean d) {
        this.deprecated = d;
        return this;
      }

      /** Set the declared name. */
      public Builder name(String n) {
        this.name = n;
        return this;
      }

      /** Replace the full {@code extends} FQN list. */
      public Builder extendsFqns(List<String> fqns) {
        this.extendsFqns.clear();
        if (fqns != null) extendsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code extends} FQN. */
      public Builder extendsFqn(String fqn) {
        this.extendsFqns.add(fqn);
        return this;
      }

      /** Replace the full {@code implements} FQN list. */
      public Builder implementsFqns(List<String> fqns) {
        this.implementsFqns.clear();
        if (fqns != null) implementsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code implements} FQN. */
      public Builder implementsFqn(String fqn) {
        this.implementsFqns.add(fqn);
        return this;
      }

      /** Replace the full body member list. */
      public Builder members(List<Member> ms) {
        this.members.clear();
        if (ms != null) members.addAll(ms);
        return this;
      }

      /** Append one body member. */
      public Builder member(Member m) {
        this.members.add(m);
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
            List.copyOf(members));
      }
    }
  }

  /**
   * A {@code class Name [extends ...] [implements ...] { body }}. Its body members are {@link
   * Member}s exactly as a record's: arbitrary fields/methods/nested types. The static {@code Codec}
   * class (per-message encode/decode) renders as a {@code Type.Class} nested inside the message's
   * record. A class renders {@code extends} (unlike a record, which has no {@code extends}); a
   * record has no {@code extends} clause.
   */
  record Class(
      String parent,
      Modifiers mods,
      Javadoc javadoc,
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> implementsFqns,
      List<Member> members,
      List<String> annotations)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns/members/annotations -> empty
    // lists.
    public Class {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
      members = members == null ? List.of() : members;
      annotations = annotations == null ? List.of() : annotations;
    }

    /** A fresh class builder. */
    public static Builder builder() {
      return new Builder();
    }

    @Override
    public String parentName() {
      return parent;
    }

    /** An accumulating, ergonomic {@link Class} builder. */
    public static final class Builder {
      private String parent;
      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private boolean deprecated;
      private String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> implementsFqns = new ArrayList<>();
      private final List<Member> members = new ArrayList<>();
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

      /** Set the declared name. */
      public Builder name(String n) {
        this.name = n;
        return this;
      }

      /** Replace the full {@code extends} FQN list. */
      public Builder extendsFqns(List<String> fqns) {
        this.extendsFqns.clear();
        if (fqns != null) extendsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code extends} FQN. */
      public Builder extendsFqn(String fqn) {
        this.extendsFqns.add(fqn);
        return this;
      }

      /** Replace the full {@code implements} FQN list. */
      public Builder implementsFqns(List<String> fqns) {
        this.implementsFqns.clear();
        if (fqns != null) implementsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code implements} FQN. */
      public Builder implementsFqn(String fqn) {
        this.implementsFqns.add(fqn);
        return this;
      }

      /** Replace the full body member list. */
      public Builder members(List<Member> ms) {
        this.members.clear();
        if (ms != null) members.addAll(ms);
        return this;
      }

      /** Append one body member. */
      public Builder member(Member m) {
        this.members.add(m);
        return this;
      }

      /** Replace the full annotation list. */
      public Builder annotations(List<String> anns) {
        this.annotations.clear();
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
            List.copyOf(members),
            List.copyOf(annotations));
      }
    }
  }

  /**
   * A {@code enum Name [implements ...] { constants; body }}. The constant members are {@link
   * Member.EnumConstant}s (rendered first, comma/semicolon-terminated); the remaining members
   * (fields, initializer, methods) follow the {@code ;} that terminates the constant list.
   */
  record Enum(
      String parent,
      Modifiers mods,
      Javadoc javadoc,
      boolean deprecated,
      String name,
      List<String> extendsFqns,
      List<String> implementsFqns,
      List<Member> members)
      implements Type {
    // Optional fields stay null/empty when a builder leaves them unset; the compact ctor seeds
    // them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns/members -> empty lists.
    public Enum {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
      members = members == null ? List.of() : members;
    }

    /** A fresh enum builder. */
    public static Builder builder() {
      return new Builder();
    }

    @Override
    public String parentName() {
      return parent;
    }

    /** An accumulating, ergonomic {@link Enum} builder. */
    public static final class Builder {
      private String parent;
      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private boolean deprecated;
      private String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> implementsFqns = new ArrayList<>();
      private final List<Member> members = new ArrayList<>();

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

      /** Set the declared name. */
      public Builder name(String n) {
        this.name = n;
        return this;
      }

      /** Replace the full {@code extends} FQN list. */
      public Builder extendsFqns(List<String> fqns) {
        this.extendsFqns.clear();
        if (fqns != null) extendsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code extends} FQN. */
      public Builder extendsFqn(String fqn) {
        this.extendsFqns.add(fqn);
        return this;
      }

      /** Replace the full {@code implements} FQN list. */
      public Builder implementsFqns(List<String> fqns) {
        this.implementsFqns.clear();
        if (fqns != null) implementsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code implements} FQN. */
      public Builder implementsFqn(String fqn) {
        this.implementsFqns.add(fqn);
        return this;
      }

      /** Replace the full body member list. */
      public Builder members(List<Member> ms) {
        this.members.clear();
        if (ms != null) members.addAll(ms);
        return this;
      }

      /** Append one body member. */
      public Builder member(Member m) {
        this.members.add(m);
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
            List.copyOf(members));
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
      List<Member> members)
      implements Type {
    public Interface {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      permitsFqns = permitsFqns == null ? List.of() : permitsFqns;
      members = members == null ? List.of() : members;
    }

    /** A fresh interface builder. */
    public static Builder builder() {
      return new Builder();
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
      private String parent;
      private final Modifiers.Builder mods = Modifiers.builder();
      private Javadoc javadoc;
      private boolean deprecated;
      private String name;
      private final List<String> extendsFqns = new ArrayList<>();
      private final List<String> permitsFqns = new ArrayList<>();
      private final List<Member> members = new ArrayList<>();

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

      /** Set the declared name. */
      public Builder name(String n) {
        this.name = n;
        return this;
      }

      /** Replace the full {@code extends} FQN list. */
      public Builder extendsFqns(List<String> fqns) {
        this.extendsFqns.clear();
        if (fqns != null) extendsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code extends} FQN. */
      public Builder extendsFqn(String fqn) {
        this.extendsFqns.add(fqn);
        return this;
      }

      /** Replace the full {@code permits} FQN list. */
      public Builder permitsFqns(List<String> fqns) {
        this.permitsFqns.clear();
        if (fqns != null) permitsFqns.addAll(fqns);
        return this;
      }

      /** Append one {@code permits} FQN. */
      public Builder permitsFqn(String fqn) {
        this.permitsFqns.add(fqn);
        return this;
      }

      /** Replace the full body member list. */
      public Builder members(List<Member> ms) {
        this.members.clear();
        if (ms != null) members.addAll(ms);
        return this;
      }

      /** Append one body member. */
      public Builder member(Member m) {
        this.members.add(m);
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
            List.copyOf(members));
      }
    }
  }
}
