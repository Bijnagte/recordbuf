package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.For;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.Switch;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a {@code recordbuf.protobuf_java}-opted {@link Message} into a nested {@link Type.Class}
 * {@code Interop} node for the source AST.
 *
 * <p>Protobuf-java reference types are emitted in fully-qualified form (via {@link
 * TypeCollector#useFqn}) so the ref's short name never collides with the record's own name.
 */
public final class InteropSourceMapping {

  private static final String LIST = "java.util.List";
  private static final String ARRAY_LIST = "java.util.ArrayList";
  private static final String HASH_MAP = "java.util.HashMap";
  private static final String BYTE_STRING = "com.google.protobuf.ByteString";
  private static final String RT = "bijnagte.recordbuf.runtime";

  private InteropSourceMapping() {}

  /**
   * @param m the message model (must have {@code protobufJava()} non-null/non-empty)
   * @param ci the shared TypeCollector for this record's source file
   * @param sourceName the source {@code .proto} file name (used in the Javadoc)
   * @return the nested {@code Interop} {@link Type.Class} node
   */
  public static Type.Class toInterop(Message m, TypeCollector ci, String sourceName) {
    String refPkg = m.protobufJava();
    if (refPkg == null || refPkg.isEmpty()) {
      throw new IllegalArgumentException("toInterop: protobufJava must be non-null/non-empty");
    }
    String refType = JavaNames.cap(m.name());
    // Register the ref FQN-only so no import is generated (avoids collision with the record).
    String ref = refFqn(refPkg, refType);
    TypeRef.of(ref).useFqn(ci);
    String rec = m.name();

    Javadoc javadoc =
        Javadoc.builder()
            .text(
                "Structural X <-> R converter between ",
                rec,
                " (record) and ",
                ref,
                " (protobuf-java), generated from ",
                sourceName,
                ". Field-by-field conversion only.")
            .build();

    var privateCtor = Constructor.builder().private_().build();

    var fromMethod = emitFrom(m, ci, refPkg, ref, rec);
    var toMethod = emitTo(m, ci, refPkg, ref, rec);

    return Type.Class.named("Interop")
        .public_()
        .static_()
        .final_()
        .javadoc(javadoc)
        .constructor(privateCtor)
        .method(fromMethod, toMethod)
        .build();
  }

  // -- from(reference) -> record ------------------------------------------------

  private static Method emitFrom(
      Message m, TypeCollector ci, String refPkg, String ref, String rec) {
    List<Element> body = new ArrayList<>();
    for (Field f : m.fields()) {
      fromLocal(body, f, ci);
    }
    for (Oneof o : m.oneofs()) {
      body.addAll(emitOneofFrom(o, ci));
    }
    body.add(Element.of("return new " + rec + "("));
    for (Field f : m.fields()) {
      body.add(Element.of("    _" + f.javaName() + ","));
    }
    for (Oneof o : m.oneofs()) {
      body.add(Element.of("    _" + o.name() + ","));
    }
    TypeRef ufRef = TypeRef.of(RT + ".UnknownFields");
    ufRef.collect(ci);
    body.add(Element.of("    " + ufRef.render(ci.resolutionContext()) + ".empty()"));
    body.add(Element.of("    );"));

    return Method.named("from")
        .public_()
        .static_()
        .returnType(rec)
        .param(ref, "x")
        .body(body)
        .build();
  }

  private static void fromLocal(List<Element> out, Field f, TypeCollector ci) {
    String cap = JavaNames.cap(f.javaName());
    if (f.isMap()) {
      boolean hasBytes = "bytes".equals(f.mapKey()) || "bytes".equals(f.mapValue());
      if (!hasBytes) {
        // Non-byte maps: copy the protobuf-java map directly (compatible key/value types)
        out.add(
            Element.expr(
                RawExpr.of(
                    "var _@0 = new @1<>(x.get@2Map())", f.javaName(), TypeRef.of(HASH_MAP), cap)));
      } else {
        String keyExpr = mapKeyFrom(f);
        String valExpr = mapValueFrom(f, ci);
        out.add(
            Element.expr(RawExpr.of("var _@0 = new @1<>()", f.javaName(), TypeRef.of(HASH_MAP))));
        out.add(
            For.enhanced("var _e", "x.get@0Map().entrySet()", cap)
                .body(Element.expr("_@0.put(@1, @2)", f.javaName(), keyExpr, valExpr))
                .build());
      }
      return;
    }
    if (f.isMessage() && f.isRepeated()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          Element.expr(
              RawExpr.of(
                  "@0<@2> _@3 = new @1<>()",
                  TypeRef.of(LIST),
                  TypeRef.of(ARRAY_LIST),
                  TypeRef.of(recSib),
                  f.javaName())));
      out.add(
          For.enhanced("var _e", "x.get@0List()", cap)
              .body(Element.expr("_@0.add(@1.Interop.from(_e))", f.javaName(), recSib))
              .build());
      return;
    }
    if (f.isMessage()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          Element.expr(
              RawExpr.of(
                  "var _@0 = x.has@1() ? @2.Interop.from(x.get@1()) : null",
                  f.javaName(),
                  JavaNames.cap(f.javaName()),
                  TypeRef.of(recSib))));
      return;
    }
    if (f.kind() == FieldKind.ENUM) {
      out.add(Element.expr("var _@0 = @1", f.javaName(), fromExpr(f)));
      return;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out.add(
            Element.expr(RawExpr.of("var _@0 = new @1<>()", f.javaName(), TypeRef.of(ARRAY_LIST))));
        out.add(
            For.enhanced("var _e", "x.get@0List()", cap)
                .body(Element.expr("_@0.add(_e.toByteArray())", f.javaName()))
                .build());
      } else {
        // For non-bytes repeated fields, use the protobuf-java list directly;
        // var infers the correct element type from x.getList().
        out.add(Element.expr("var _@0 = x.get@1List()", f.javaName(), cap));
      }
      return;
    }
    // Singular scalar / bytes
    if ("bytes".equals(f.typeUrl())) {
      out.add(
          Element.expr(
              RawExpr.of(
                  "var _@0 = @1.of(@2)", f.javaName(), TypeRef.of(RT + ".Bytes"), fromExpr(f))));
    } else if (f.isNullable()) {
      out.add(Element.expr("var _@0 = x.has@1() ? x.get@1() : null", f.javaName(), cap));
    } else {
      out.add(Element.expr("var _@0 = @1", f.javaName(), fromExpr(f)));
    }
  }

  private static String fromExpr(Field f) {
    String cap = JavaNames.cap(f.javaName());
    if (f.kind() == FieldKind.ENUM) {
      return recordSiblingFqn(f) + ".forNumber(x.get" + cap + "().getNumber())";
    }
    if ("bytes".equals(f.typeUrl())) {
      return "x.get" + cap + "().toByteArray()";
    }
    return "x.get" + cap + "()";
  }

  // -- to(record) -> reference --------------------------------------------------

  private static Method emitTo(Message m, TypeCollector ci, String refPkg, String ref, String rec) {
    List<Element> body = new ArrayList<>();
    body.add(Element.expr("var _b = @0.newBuilder()", ref));
    for (Field f : m.fields()) {
      toStmt(body, f, ci, refPkg);
    }
    for (Oneof o : m.oneofs()) {
      body.addAll(emitOneofTo(o, ci, refPkg));
    }
    body.add(Element.of("return _b.build();"));

    return Method.named("to")
        .public_()
        .static_()
        .returnType(ref)
        .param(rec, "r")
        .body(body)
        .build();
  }

  private static void toStmt(List<Element> out, Field f, TypeCollector ci, String refPkg) {
    String cap = JavaNames.cap(f.javaName());
    if (f.isMap()) {
      out.add(
          For.enhanced("var _e", "r.@0().entrySet()", f.javaName())
              .body(Element.expr("_b.put@0(@1, @2)", cap, mapKeyTo(f, ci), mapValueTo(f, ci)))
              .build());
      return;
    }
    if (f.isMessage() && f.isRepeated()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          For.enhanced("var _e", "r.@0()", f.javaName())
              .body(Element.expr("_b.add@0(@1.Interop.to(_e))", cap, recSib))
              .build());
      return;
    }
    if (f.isMessage()) {
      String recSib = recordSiblingFqn(f);
      // Use a local variable so NullAway can track the non-null guard
      String src = "r." + f.javaName() + "()";
      String local = "n" + JavaNames.cap(f.javaName());
      out.add(
          If.condition("@0 != null", src)
              .body(
                  Element.expr("var @0 = @1", local, src),
                  Element.expr("_b.set@0(@1.Interop.to(@2))", cap, recSib, local))
              .build());
      return;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out.add(
            For.enhanced("byte[] _e", "r.@0()", f.javaName())
                .body(
                    Element.expr(
                        RawExpr.of("_b.add@1(@0.copyFrom(_e))", TypeRef.of(BYTE_STRING), cap)))
                .build());
      } else {
        out.add(Element.expr("_b.addAll@0(r.@1())", cap, f.javaName()));
      }
      return;
    }
    out.add(toSetStatement(f, "r." + f.javaName() + "()", ci, refPkg));
  }

  private static Element toSetStatement(Field f, String src, TypeCollector ci, String refPkg) {
    String cap = JavaNames.cap(f.javaName());
    if (f.kind() == FieldKind.ENUM) {
      String refEnum =
          refPkg.isEmpty()
              ? JavaNames.cap(lastSegment(f.typeUrl()))
              : refPkg + "." + JavaNames.cap(lastSegment(f.typeUrl()));
      return Element.expr("_b.set@0(@1.forNumber(@2.getNumber()))", cap, refEnum, src);
    }
    if ("bytes".equals(f.typeUrl())) {
      return Element.expr(
          RawExpr.of(
              "_b.set" + cap + "(@0.copyFrom(" + src + ".toByteArray()))",
              TypeRef.of(BYTE_STRING)));
    }
    if (f.isNullable()) {
      return If.condition("@0 != null", src).body(Element.expr("_b.set@0(@1)", cap, src)).build();
    }
    return Element.expr("_b.set@0(@1)", cap, src);
  }

  private static List<Element> emitOneofFrom(Oneof o, TypeCollector ci) {
    String iface = o.ifaceName();
    List<Element> out = new ArrayList<>();
    out.add(
        Element.of(
            "@Nullable " + iface + " _" + o.name() + " = switch (x.get" + iface + "Case()) {"));
    for (Field f : o.members()) {
      String cap = JavaNames.cap(f.name());
      if (f.isMessage()) {
        String recSib = recordSiblingFqn(f);
        // The message member wraps the actual record in a per-member sealed value record.
        out.add(
            Element.of(
                "  case "
                    + JavaNames.snakeUpper(f.name())
                    + " -> new "
                    + iface
                    + "."
                    + cap
                    + "Value("
                    + recSib
                    + ".Interop.from(x.get"
                    + cap
                    + "()));"));
        continue;
      }
      out.add(
          Element.of(
              "  case "
                  + JavaNames.snakeUpper(f.name())
                  + " -> new "
                  + iface
                  + "."
                  + cap
                  + "Value("
                  + fromExpr(f)
                  + ");"));
    }
    out.add(Element.of("  default -> null;"));
    out.add(Element.of("};"));
    return out;
  }

  private static List<Element> emitOneofTo(Oneof o, TypeCollector ci, String refPkg) {
    String iface = o.ifaceName();
    Switch.Builder sw = Switch.on("r." + o.componentName() + "()");
    // The null arm: an empty body auto-braces to `case null -> {}`.
    sw.caseArm("null");
    for (Field f : o.members()) {
      String cap = JavaNames.cap(f.name());
      String pattern = iface + "." + cap + "Value v";
      if (f.isMessage()) {
        String recSib = recordSiblingFqn(f);
        sw.caseArm(pattern, Element.expr("_b.set@0(@1.Interop.to(v.value()))", cap, recSib));
      } else {
        sw.caseArm(pattern, toSetStatement(f, "v.value()", ci, refPkg));
      }
    }
    return List.of(sw.build());
  }

  // -- helpers ------------------------------------------------------------------

  /** The record sibling short name from the field's typeUrl last segment. */
  private static String recordSiblingFqn(Field f) {
    return JavaNames.cap(lastSegment(f.typeUrl()));
  }

  /** The Java element type of a repeated scalar/enum field. */
  private static String recordElement(Field f) {
    // For repeated, the component is a List<T> where T is the element type.
    // Use var in the generated code since the specific type can vary by proto.
    return "Object"; // var handles this; the specific type is inferred from the x.getList()
  }

  private static String lastSegment(String fqn) {
    if (fqn == null) return "";
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  private static String refFqn(String refPkg, String refType) {
    return refPkg.isEmpty() ? refType : refPkg + "." + refType;
  }

  private static String mapKeyFrom(Field f) {
    return "bytes".equals(f.mapKey()) ? "_e.getKey().toByteArray()" : "_e.getKey()";
  }

  private static String mapValueFrom(Field f, TypeCollector ci) {
    String t = f.mapValue();
    if (t == null) return "_e.getValue()";
    if ("bytes".equals(t)) return "_e.getValue().toByteArray()";
    return "_e.getValue()";
  }

  private static String mapKeyTo(Field f, TypeCollector ci) {
    if ("bytes".equals(f.mapKey())) {
      TypeRef bytesRef = TypeRef.of(BYTE_STRING);
      bytesRef.collect(ci);
      return bytesRef.render(ci.resolutionContext()) + ".copyFrom(_e.getKey())";
    }
    return "_e.getKey()";
  }

  private static String mapValueTo(Field f, TypeCollector ci) {
    String t = f.mapValue();
    if (t == null) return "_e.getValue()";
    if ("bytes".equals(t)) {
      TypeRef bytesRef = TypeRef.of(BYTE_STRING);
      bytesRef.collect(ci);
      return bytesRef.render(ci.resolutionContext()) + ".copyFrom(_e.getValue())";
    }
    return "_e.getValue()";
  }
}
