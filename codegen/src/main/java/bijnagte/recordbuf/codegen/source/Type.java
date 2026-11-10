package bijnagte.recordbuf.codegen.source;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;

/**
 * A single top-level (or nested) type declaration the AST owns -- a {@link Record} or an {@link
 * Enum} for this slice. Each type carries its modifiers, optional Javadoc, name, optional {@code
 * extends}/{@code implements} clauses, and an ordered body of {@link Member}s; nested types are
 * themselves {@code Member}s ({@link Member#Nested}).
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
  @RecordBuilder
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
    // Optional fields stay null when a builder leaves them unset; the compact ctor seeds them:
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

    @Override
    public String parentName() {
      return parent;
    }
  }

  /**
   * A {@code class Name [extends ...] [implements ...] { body }}. Its body members are {@link
   * Member}s exactly as a record's: arbitrary fields/methods/nested types. The M4-C2b-2+ static
   * {@code Codec} class (per-message encode/decode) renders as a {@code Type.Class} nested inside
   * the message's record, like the M4-C2a {@code Member.Nested} enum family. A class renders {@code
   * extends} (unlike a record, which has no {@code extends}); a record has no {@code extends}
   * clause. The class body is line-emitted: each {@link Member.Method} carries a {@code
   * List}&lt;{@link Line}&gt; body verbatim.
   */
  @RecordBuilder
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
    // Optional fields stay null when a builder leaves them unset; the compact ctor seeds them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns/members/annotations -> empty
    // lists. `parent` is the class's enclosing type when it is a nested class (the Codec case);
    // `deprecated` and `name` are a primitive + required, respectively.
    public Class {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
      members = members == null ? List.of() : members;
      annotations = annotations == null ? List.of() : annotations;
    }

    @Override
    public String parentName() {
      return parent;
    }
  }

  /**
   * A {@code enum Name [implements ...] { constants; body }}. The constant members are {@link
   * Member.EnumConstant}s (rendered first, comma/semicolon-terminated); the remaining members
   * (fields, initializer, methods) follow the {@code ;} that terminates the constant list.
   */
  @RecordBuilder
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
    // Optional fields stay null when a builder leaves them unset; the compact ctor seeds them:
    // mods -> none, javadoc -> empty, extendsFqns/implementsFqns/members -> empty lists. `parent`
    // is
    // the enum's enclosing type when it is a nested enum; `deprecated` and `name` are a primitive +
    // required, respectively.
    public Enum {
      mods = mods == null ? Modifiers.none() : mods;
      javadoc = javadoc == null ? Javadoc.empty() : javadoc;
      extendsFqns = extendsFqns == null ? List.of() : extendsFqns;
      implementsFqns = implementsFqns == null ? List.of() : implementsFqns;
      members = members == null ? List.of() : members;
    }

    @Override
    public String parentName() {
      return parent;
    }
  }

  /**
   * A {@code interface Name [extends ...] [permits ...] { body }}. M4-C2b-2b-12: the source-AST
   * surface for oneof sealed interfaces. The {@code extendsFqns} field carries the {@code extends}
   * FQNs; {@code permitsFqns} carries the {@code permits} FQNs. The {@code implementsFqns} field is
   * an unused placeholder (interfaces cannot implement — that is the wrong direction).
   */
  @RecordBuilder
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

    @Override
    public String parentName() {
      return parent;
    }

    @Override
    public List<String> implementsFqns() {
      return List.of(); // interfaces cannot implement; permitsFqns are NOT imports
    }
  }
}
