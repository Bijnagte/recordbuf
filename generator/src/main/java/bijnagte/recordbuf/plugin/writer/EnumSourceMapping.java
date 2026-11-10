package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Line;
import bijnagte.recordbuf.codegen.source.Member;
import bijnagte.recordbuf.codegen.source.MemberConstructorBuilder;
import bijnagte.recordbuf.codegen.source.MemberEnumConstantBuilder;
import bijnagte.recordbuf.codegen.source.MemberFieldBuilder;
import bijnagte.recordbuf.codegen.source.MemberMethodBuilder;
import bijnagte.recordbuf.codegen.source.Modifiers;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeEnumBuilder;
import bijnagte.recordbuf.plugin.model.Enum;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the generator's proto {@link Enum} model into a {@link Type.Enum} of the pure-Java,
 * proto-agnostic {@code source} AST ({@code bijnagte.recordbuf.codegen.source}) -- the M4 step that
 * replaces the string-concatenation {@link EnumGenerator} with a renderable, structural tree.
 *
 * <p>M4-C2a-ext mirrors what {@link EnumGenerator#write} emits, in full: the {@code public enum
 * Name [implements ...]} header (its Javadoc and {@code @Deprecated} come from the proto {@code
 * comment} / {@code deprecated}), each constant, the {@code private final int number;} field, the
 * {@code Name(int number)} constructor, the {@code getNumber()} accessor, and the reverse-lookup
 * cluster -- the {@code private static final Map<Integer, Name> BY_NUMBER = new HashMap<>();}
 * field, its {@code static {}} initializer, and the {@code forNumber} accessor.
 *
 * <p>The reverse-lookup cluster's generic types ({@code java.util.Map} / {@code java.util.HashMap})
 * are the only external types. Because {@code TypeCollector.use} strips generic arguments, the
 * field's type + initializer are built as <em>pre-resolved short-form strings</em>: each raw FQN is
 * run through {@code ci.use} once (to import it and yield its short name) and inlined -- e.g.
 * {@code ci.use("java.util.Map") + "<Integer, " + name + ">"} for the type and {@code "new " +
 * ci.use("java.util.HashMap") + "<>()"} for the initializer. The renderer emits a field's type +
 * initializer verbatim (see {@link Member.Field}).
 *
 * <p>The one detail from {@link EnumGenerator} this mirror omits is its leading {@code // Reverse
 * lookup ...} comment: the {@code source} AST has no standalone line-comment member to hold a
 * bare-slash comment (a noted M4 follow-up: a comment/line member), so it is not reproduced here.
 */
public final class EnumSourceMapping {

  private EnumSourceMapping() {}

  /**
   * Map a proto {@link Enum} model into a source-AST {@link Type.Enum}.
   *
   * @param proto the proto enum model
   * @param ci the file's type collector, used to import + short-name the cluster's {@code
   *     java.util.Map} / {@code java.util.HashMap} references
   */
  public static Type.Enum toSource(Enum proto, TypeCollector ci) {
    String name = proto.name();
    List<Member> members = new ArrayList<>();

    // 1. The constants -- each with its number ctor-arg, its own Javadoc, and its own @Deprecated.
    for (Enum.EnumConstant c : proto.constants()) {
      members.add(
          MemberEnumConstantBuilder.builder()
              .name(c.name())
              .ctorArgs("(" + c.number() + ")")
              .javadoc(new Javadoc(c.comment()))
              .deprecated(c.deprecated())
              .build());
    }

    // 2. The number field + ctor + accessor -- the core the M4-C2a slice mapped.
    members.add(
        MemberFieldBuilder.builder()
            .mods(Modifiers.of("private", "final"))
            .type("int")
            .name("number")
            .build());
    members.add(
        MemberConstructorBuilder.builder()
            .name(name)
            .params("(int number)")
            .body(List.of(Line.of("this.number = number;")))
            .build());
    members.add(
        MemberMethodBuilder.builder()
            .mods(Modifiers.of("public"))
            .returnType("int")
            .name("getNumber")
            .params("()")
            .body(List.of(Line.of("return number;")))
            .build());

    // 3. The reverse-lookup cluster -- M4-C2a-ext's fold-in. ci.use imports java.util.Map/HashMap +
    // yields each short name; the generic field type + initializer are then spelled out in short
    // form and emitted verbatim (TypeCollector.use strips generic args).
    String map = ci.use("java.util.Map");
    String hashMap = ci.use("java.util.HashMap");
    members.add(
        MemberFieldBuilder.builder()
            .mods(Modifiers.of("private", "static", "final"))
            .type(map + "<Integer, " + name + ">")
            .name("BY_NUMBER")
            .initializer("new " + hashMap + "<>()")
            .build());

    List<Line> init = new ArrayList<>();
    for (Enum.EnumConstant c : proto.constants()) {
      init.add(Line.of("BY_NUMBER.put(" + c.number() + ", " + c.name() + ");"));
    }
    members.add(new Member.StaticBlock(init));

    members.add(
        MemberMethodBuilder.builder()
            .mods(Modifiers.of("public", "static"))
            .returnType(name)
            .name("forNumber")
            .params("(int number)")
            .body(
                List.of(
                    Line.of(
                        "return BY_NUMBER.getOrDefault(number, "
                            + firstConstantName(proto)
                            + ");")))
            .build());

    return TypeEnumBuilder.builder()
        .mods(Modifiers.of("public"))
        .javadoc(new Javadoc(proto.comment()))
        .deprecated(proto.deprecated())
        .name(name)
        .implementsFqns(proto.interfaces())
        .members(List.copyOf(members))
        .build();
  }

  /** The first constant's name, or {@code UNRECOGNIZED} when the enum has none. */
  private static String firstConstantName(Enum e) {
    return e.constants().isEmpty() ? "UNRECOGNIZED" : e.constants().get(0).name();
  }
}
