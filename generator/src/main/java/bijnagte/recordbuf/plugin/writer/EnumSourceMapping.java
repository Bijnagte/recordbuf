package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Component;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.StaticBlock;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Enum;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the generator's proto {@link Enum} model into a {@link Type.Enum} of the pure-Java,
 * proto-agnostic {@code source} AST ({@code bijnagte.recordbuf.codegen.source}) — the M4 step that
 * replaces the string-concatenation {@link EnumGenerator} with a renderable, structural tree.
 *
 * <p>M4-C2a-ext mirrors what {@link EnumGenerator#write} emits, in full: the {@code public enum
 * Name [implements ...]} header (its Javadoc and {@code @Deprecated} come from the proto {@code
 * comment} / {@code deprecated}), each constant, the {@code private final int number;} field, the
 * {@code Name(int number)} constructor, the {@code getNumber()} accessor, and the reverse-lookup
 * cluster — the {@code private static final Map<Integer, Name> BY_NUMBER = new HashMap<>();} field,
 * its {@code static {}} initializer, and the {@code forNumber} accessor.
 *
 * <p>The reverse-lookup cluster's external types are {@code java.util.Map} and {@code
 * java.util.HashMap}. U5 migration: the {@code Map<Integer, String>} field type and the {@code new
 * HashMap<>()} initializer are both structured {@link TypeRef}s registered in the collect phase;
 * they resolve collision-aware at render time via {@code TypeRef.render(ctx)}.
 */
public final class EnumSourceMapping {

  private EnumSourceMapping() {}

  /**
   * Map a proto {@link Enum} model into a source-AST {@link Type.Enum}.
   *
   * @param proto the proto enum model
   * @param ci the file's type collector, used to register the cluster's {@code java.util.Map} /
   *     {@code java.util.HashMap} imports
   * @param declaringPkg the Java package the generated enum belongs to — used to resolve the
   *     self-reference {@code Name} inside {@code Map<Integer, Name>}
   */
  public static Type.Enum toSource(Enum proto, TypeCollector ci, String declaringPkg) {
    String name = proto.name();

    Type.Enum.Builder result =
        Type.Enum.named(proto.name())
            .public_()
            .javadoc(Javadoc.builder().text(proto.comment()).build())
            .deprecated(proto.deprecated())
            .implementsFqns(proto.interfaces());

    // 1. The constants — each with its number ctor-arg, its own Javadoc, and its own @Deprecated.
    for (bijnagte.recordbuf.plugin.model.Enum.EnumConstant c : proto.constants()) {
      result.enumConstant(
          bijnagte.recordbuf.codegen.source.EnumConstant.named(c.name())
              .ctorArgs(Integer.toString(c.number()))
              .javadoc(Javadoc.builder().text(c.comment()).build())
              .deprecated(c.deprecated())
              .build());
    }

    // 2. The number field + ctor + accessor — the core the M4-C2a slice mapped.
    result.field(Component.named("int", "number").private_().final_().build());
    result.constructor(
        Constructor.builder()
            .param("int", "number")
            .body(Element.expr("this.number = number"))
            .build());
    result.method(
        Method.named("getNumber").public_().returnType("int").body(Element.ret("number")).build());

    // 3. The reverse-lookup cluster — U5: BY_NUMBER field type and initializers are TypeRefs.
    //    Collected in the collect phase; resolved collision-aware at render time.
    TypeRef selfRef = TypeRef.named(declaringPkg, name);
    List<TypeRef> mapGenerics = List.of(TypeRef.of("java.lang.Integer"), selfRef);
    TypeRef mapType = TypeRef.named("java.util", "Map", mapGenerics);
    // The initializer still needs a string for Component.initializer(String);
    // TypeRef.collect handles the import; the RawExpr @0 resolves to the short name at render.
    TypeRef hashMapRef = TypeRef.of("java.util.HashMap");
    hashMapRef.collect(ci);
    result.field(
        Component.type(mapType, "BY_NUMBER")
            .private_()
            .static_()
            .final_()
            .initializer(RawExpr.of("new @0<>()", hashMapRef).render(ci.resolutionContext()))
            .build());

    List<Element> init = new ArrayList<>();
    for (bijnagte.recordbuf.plugin.model.Enum.EnumConstant c : proto.constants()) {
      init.add(Element.expr("BY_NUMBER.put(@0, @1)", c.number(), c.name()));
    }
    result.staticBlock(new StaticBlock(init));

    result.method(
        Method.named("forNumber")
            .public_()
            .static_()
            .returnType(selfRef)
            .param("int", "number")
            .body(Element.ret("BY_NUMBER.getOrDefault(number, " + firstConstantName(proto) + ")"))
            .build());

    return result.build();
  }

  /** The first constant's name, or {@code UNRECOGNIZED} when the enum has none. */
  private static String firstConstantName(bijnagte.recordbuf.plugin.model.Enum e) {
    return e.constants().isEmpty() ? "UNRECOGNIZED" : e.constants().get(0).name();
  }
}
