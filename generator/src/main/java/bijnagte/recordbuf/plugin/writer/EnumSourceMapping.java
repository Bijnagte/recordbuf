package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.source.Annotation;
import bijnagte.recordbuf.codegen.source.Arg;
import bijnagte.recordbuf.codegen.source.Component;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.EnumConstant;
import bijnagte.recordbuf.codegen.source.EnumSpec;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.StaticBlock;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.types.TypeRefs;

/**
 * Maps the generator's proto {@link Enum} model into a {@link EnumSpec} of the pure-Java,
 * proto-agnostic {@code source} AST ({@code bijnagte.recordbuf.codegen.source}) — the M4 step that
 * replaces the string-concatenation {@code EnumGenerator} with a renderable, structural tree.
 *
 * <p>M4-C2a-ext mirrors what {@code EnumGenerator#write} emits, in full: the {@code public enum
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
   * Map a proto {@link Enum} model into a source-AST {@link EnumSpec}.
   *
   * @param proto the proto enum model
   * @param declaringPkg the Java package the generated enum belongs to — used to resolve the
   *     self-reference {@code Name} inside {@code Map<Integer, Name>}
   * @param jackson when {@code true} (the file has the Jackson surface) a constant with aliases
   *     carries {@code @JsonAlias} naming them; otherwise the enum stays free of Jackson
   */
  public static EnumSpec toSource(Enum proto, String declaringPkg, boolean jackson) {
    String name = proto.name();
    var selfRef = TypeRef.named(declaringPkg, name);

    EnumSpec.Builder result =
        EnumSpec.named(selfRef)
            .public_()
            .javadoc(
                GeneratedDocs.type(
                    proto.comment(),
                    "Auto-generated enum for the " + proto.name() + " proto enum."))
            .deprecated(proto.deprecated())
            .implementsFqns(proto.interfaces().stream().map(TypeRef::fqn).toList());

    // 1. The constants — each with its number ctor-arg, its own Javadoc, and its own @Deprecated.
    for (Enum.EnumConstant c : proto.constants()) {
      EnumConstant.Builder constant =
          EnumConstant.named(c.name())
              .ctorArgs(Integer.toString(c.number()))
              .javadoc(GeneratedDocs.type(c.comment(), "Enum constant " + c.name() + "."))
              .deprecated(c.deprecated());
      if (jackson && !c.aliases().isEmpty()) {
        constant.annotation(
            Annotation.of(
                TypeRef.of("com.fasterxml.jackson.annotation.JsonAlias"),
                Arg.of(null, c.aliases().toArray(String[]::new))));
      }
      result.enumConstant(constant.build());
    }

    // 2. The number field + ctor + accessor — the core the M4-C2a slice mapped.
    result
        .field(Component.named("int", "number").private_().final_().build())
        .constructor(
            Constructor.builder()
                .param(TypeRef.Primitive.INT, "number")
                .body(Element.expr("this.number = number"))
                .build())
        .method(
            Method.named("getNumber")
                .public_()
                .returnType(TypeRef.Primitive.INT)
                .body(Element.ret("number"))
                .build());

    // 3. The reverse-lookup cluster — U5: BY_NUMBER field type and initializers are TypeRefs.
    //    Collected in the collect phase; resolved collision-aware at render time.
    TypeRef mapType = TypeRefs.MAP.withGenerics(TypeRef.INTEGER, selfRef);
    TypeRef hashMapRef = TypeRefs.HASH_MAP;

    var init = StaticBlock.builder();
    for (Enum.EnumConstant c : proto.constants()) {
      init.body(Element.expr("BY_NUMBER.put(@0, @1)", c.number(), c.name()));
    }

    return result
        .field(
            Component.type(mapType, "BY_NUMBER")
                .private_()
                .static_()
                .final_()
                .initializer(RawExpr.of("new @0<>()", hashMapRef))
                .build())
        .staticBlock(init.build())
        .method(
            Method.named("forNumber")
                .public_()
                .static_()
                .returnType(selfRef)
                .param(TypeRef.Primitive.INT, "number")
                .body(
                    Element.ret("BY_NUMBER.getOrDefault(number, " + firstConstantName(proto) + ")"))
                .build())
        .method(
            Method.named("forNumberOrNull")
                .annotation(Annotation.NULLABLE)
                .public_()
                .static_()
                .returnType(selfRef)
                .param(TypeRef.Primitive.INT, "number")
                .body(Element.ret("BY_NUMBER.get(number)"))
                .build())
        .build();
  }

  /** The first constant's name, or {@code UNRECOGNIZED} when the enum has none. */
  private static String firstConstantName(Enum e) {
    return e.constants().isEmpty() ? "UNRECOGNIZED" : e.constants().get(0).name();
  }
}
