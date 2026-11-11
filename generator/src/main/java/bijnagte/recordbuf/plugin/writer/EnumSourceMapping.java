package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Expression;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Member;
import bijnagte.recordbuf.codegen.source.Statement;
import bijnagte.recordbuf.codegen.source.Type;
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

    Type.Enum.Builder result =
        Type.Enum.builder()
            .public_()
            .javadoc(Javadoc.builder().text(proto.comment()).build())
            .deprecated(proto.deprecated())
            .name(proto.name())
            .implementsFqns(proto.interfaces());

    // 1. The constants -- each with its number ctor-arg, its own Javadoc, and its own @Deprecated.
    for (Enum.EnumConstant c : proto.constants()) {
      result.enumConstant(
          Member.EnumConstant.named(c.name())
              .ctorArgs(Integer.toString(c.number()))
              .javadoc(Javadoc.builder().text(c.comment()).build())
              .deprecated(c.deprecated())
              .build());
    }

    // 2. The number field + ctor + accessor -- the core the M4-C2a slice mapped.
    result.field(Member.Field.named("int", "number").private_().final_().build());
    result.constructor(
        Member.Constructor.builder()
            .param("int", "number")
            .body(
                Statement.expr(
                    Expression.assign(
                        Expression.member("this", "number"), Expression.lit("number"))))
            .build());
    result.method(
        Member.Method.named("getNumber")
            .public_()
            .returnType("int")
            .body(Statement.ret(Expression.lit("number")))
            .build());

    // 3. The reverse-lookup cluster -- M4-C2a-ext's fold-in. ci.use imports java.util.Map/HashMap +
    // yields each short name; the generic field type + initializer are then spelled out in short
    // form and emitted verbatim (TypeCollector.use strips generic args).
    String map = ci.use("java.util.Map");
    String hashMap = ci.use("java.util.HashMap");
    result.field(
        Member.Field.named(map + "<Integer, " + name + ">", "BY_NUMBER")
            .private_()
            .static_()
            .final_()
            .initializer("new " + hashMap + "<>()")
            .build());

    List<Statement> init = new ArrayList<>();
    for (Enum.EnumConstant c : proto.constants()) {
      init.add(
          Statement.expr(
              Expression.call(
                  Expression.member("BY_NUMBER", "put"),
                  Expression.lit(String.valueOf(c.number())),
                  Expression.lit(c.name()))));
    }
    result.staticBlock(new Member.StaticBlock(init));

    result.method(
        Member.Method.named("forNumber")
            .public_()
            .static_()
            .returnType(name)
            .param("int", "number")
            .body(
                Statement.ret(
                    Expression.call(
                        Expression.member("BY_NUMBER", "getOrDefault"),
                        Expression.lit("number"),
                        Expression.lit(firstConstantName(proto)))))
            .build());

    return result.build();
  }

  /** The first constant's name, or {@code UNRECOGNIZED} when the enum has none. */
  private static String firstConstantName(Enum e) {
    return e.constants().isEmpty() ? "UNRECOGNIZED" : e.constants().get(0).name();
  }
}
