package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Annotation;
import bijnagte.recordbuf.codegen.source.ClassSpec;
import bijnagte.recordbuf.codegen.source.CommaList;
import bijnagte.recordbuf.codegen.source.Constructor;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.For;
import bijnagte.recordbuf.codegen.source.Formattable;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.LocalVar;
import bijnagte.recordbuf.codegen.source.Method;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.Switch;
import bijnagte.recordbuf.codegen.source.SwitchExpr;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.JavaNames;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a {@code recordbuf.protobuf_java}-opted {@link Message} into a nested {@link ClassSpec}
 * {@code Interop} node for the source AST.
 *
 * <p>Protobuf-java reference types are emitted in fully-qualified form by the {@link TypeCollector}
 * collision policy, so the ref's short name never collides with the record's own name.
 */
public final class InteropSourceMapping {

  private InteropSourceMapping() {}

  /**
   * Returns the nested {@code Interop} {@link ClassSpec} node that converts {@code m} to and from
   * its protobuf-java reference type.
   *
   * @param m the message model (must have {@code protobufJava()} non-null/non-empty)
   * @param sourceName the source {@code .proto} file name (used in the Javadoc)
   * @return the nested {@code Interop} {@link ClassSpec} node
   */
  public static ClassSpec toInterop(Message m, String sourceName) {
    String refPkg = m.protobufJava();
    if (refPkg == null || refPkg.isEmpty()) {
      throw new IllegalArgumentException("toInterop: protobufJava must be non-null/non-empty");
    }
    String refType = JavaNames.cap(m.name());
    // The ref is used below as TypeRef.of(ref); the TypeCollector's collision policy emits it
    // fully-qualified, since its short name collides with the record's own name.
    String ref = refFqn(refPkg, refType);
    String rec = m.name();

    Javadoc javadoc =
        GeneratedDocs.type(
            null,
            "Structural X <-> R converter between "
                + rec
                + " (record) and "
                + ref
                + " (protobuf-java), generated from "
                + sourceName
                + ". Field-by-field conversion only.");

    var privateCtor = Constructor.builder().private_().build();

    var fromMethod = emitFrom(m, ref, rec);
    var toMethod = emitTo(m, refPkg, ref, rec);

    return ClassSpec.named("Interop")
        .public_()
        .static_()
        .final_()
        .javadoc(javadoc)
        .constructor(privateCtor)
        .method(fromMethod, toMethod)
        .build();
  }

  // -- from(reference) -> record ------------------------------------------------

  private static Method emitFrom(Message m, String ref, String rec) {
    var method =
        Method.named("from")
            .public_()
            .static_()
            .returnType(TypeRef.of(rec))
            .param(TypeRef.of(ref), "x");
    for (Field f : m.fields()) {
      method.body(fromLocal(f));
    }
    for (Oneof o : m.oneofs()) {
      method.body(emitOneofFrom(o));
    }

    List<Object> ctorArgs = new ArrayList<>();
    for (Field f : m.fields()) {
      ctorArgs.add("_" + f.javaName());
    }
    for (Oneof o : m.oneofs()) {
      ctorArgs.add("_" + o.name());
    }
    ctorArgs.add(RawExpr.of("@0.empty()", TypeRefs.UNKNOWN_FIELDS));
    return method.body(Element.ret("new @0(@1)", rec, CommaList.of(ctorArgs))).build();
  }

  private static List<Element> fromLocal(Field f) {
    String cap = JavaNames.cap(f.javaName());
    List<Element> out = new ArrayList<>();
    if (f.isMap()) {
      boolean hasBytes = "bytes".equals(f.mapKey()) || "bytes".equals(f.mapValue());
      if (!hasBytes) {
        // Non-byte maps: copy the protobuf-java map directly (compatible key/value types)
        out.add(
            Element.expr(
                RawExpr.of(
                    "var _@0 = new @1<>(x.get@2Map())", f.javaName(), TypeRefs.HASH_MAP, cap)));
      } else {
        String keyExpr = mapKeyFrom(f);
        String valExpr = mapValueFrom(f);
        out.add(Element.expr(RawExpr.of("var _@0 = new @1<>()", f.javaName(), TypeRefs.HASH_MAP)));
        out.add(
            For.enhanced("var _e", "x.get@0Map().entrySet()", cap)
                .body(Element.expr("_@0.put(@1, @2)", f.javaName(), keyExpr, valExpr))
                .build());
      }
      return out;
    }
    if (f.isMessage() && f.isRepeated()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          LocalVar.builder(TypeRefs.LIST.withGenerics(TypeRef.of(recSib)), "_" + f.javaName())
              .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST)));
      out.add(
          For.enhanced("var _e", "x.get@0List()", cap)
              .body(Element.expr("_@0.add(@1.Interop.from(_e))", f.javaName(), recSib))
              .build());
      return out;
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
      return out;
    }
    if (f.kind() == FieldKind.ENUM) {
      out.add(Element.expr("var _@0 = @1", f.javaName(), fromExpr(f)));
      return out;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out.add(
            Element.expr(RawExpr.of("var _@0 = new @1<>()", f.javaName(), TypeRefs.ARRAY_LIST)));
        out.add(
            For.enhanced("var _e", "x.get@0List()", cap)
                .body(Element.expr("_@0.add(_e.toByteArray())", f.javaName()))
                .build());
      } else {
        // For non-bytes repeated fields, use the protobuf-java list directly;
        // var infers the correct element type from x.getList().
        out.add(Element.expr("var _@0 = x.get@1List()", f.javaName(), cap));
      }
      return out;
    }
    // Singular scalar / bytes
    if ("bytes".equals(f.typeUrl())) {
      out.add(
          Element.expr(
              RawExpr.of("var _@0 = @1.of(@2)", f.javaName(), TypeRefs.BYTES, fromExpr(f))));

    } else if (f.isNullable()) {
      out.add(Element.expr("var _@0 = x.has@1() ? x.get@1() : null", f.javaName(), cap));
    } else {
      out.add(Element.expr("var _@0 = @1", f.javaName(), fromExpr(f)));
    }
    return out;
  }

  private static Formattable fromExpr(Field f) {
    String cap = JavaNames.cap(f.javaName());
    if (f.kind() == FieldKind.ENUM) {
      return RawExpr.of("@0.forNumber(x.get@1().getNumber())", recordSiblingFqn(f), cap);
    }
    if ("bytes".equals(f.typeUrl())) {
      return RawExpr.of("x.get@0().toByteArray()", cap);
    }
    return RawExpr.of("x.get@0()", cap);
  }

  // -- to(record) -> reference --------------------------------------------------

  private static Method emitTo(Message m, String refPkg, String ref, String rec) {
    var method =
        Method.named("to")
            .public_()
            .static_()
            .returnType(TypeRef.of(ref))
            .param(TypeRef.of(rec), "r")
            .body(Element.expr("var _b = @0.newBuilder()", ref));
    for (Field f : m.fields()) {
      method.body(toStmt(f, refPkg));
    }
    for (Oneof o : m.oneofs()) {
      method.body(emitOneofTo(o, refPkg));
    }
    return method.body(Element.ret("_b.build()")).build();
  }

  private static List<Element> toStmt(Field f, String refPkg) {
    String cap = JavaNames.cap(f.javaName());
    List<Element> out = new ArrayList<>();
    if (f.isMap()) {
      out.add(
          For.enhanced("var _e", "r.@0().entrySet()", f.javaName())
              .body(Element.expr("_b.put@0(@1, @2)", cap, mapKeyTo(f), mapValueTo(f)))
              .build());
      return out;
    }
    if (f.isMessage() && f.isRepeated()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          For.enhanced("var _e", "r.@0()", f.javaName())
              .body(Element.expr("_b.add@0(@1.Interop.to(_e))", cap, recSib))
              .build());
      return out;
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
      return out;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out.add(
            For.enhanced("byte[] _e", "r.@0()", f.javaName())
                .body(
                    Element.expr(
                        RawExpr.of("_b.add@1(@0.copyFrom(_e))", TypeRefs.BYTE_STRING, cap)))
                .build());
      } else {
        out.add(Element.expr("_b.addAll@0(r.@1())", cap, f.javaName()));
      }
      return out;
    }
    out.add(toSetStatement(f, "r." + f.javaName() + "()", refPkg));
    return out;
  }

  private static Element toSetStatement(Field f, String src, String refPkg) {
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
          RawExpr.of("_b.set@0(@1.copyFrom(@2.toByteArray()))", cap, TypeRefs.BYTE_STRING, src));
    }
    if (f.isNullable()) {
      return If.condition("@0 != null", src).body(Element.expr("_b.set@0(@1)", cap, src)).build();
    }
    return Element.expr("_b.set@0(@1)", cap, src);
  }

  private static Element emitOneofFrom(Oneof o) {
    var sw = SwitchExpr.on("x.get@0Case()", o.ifaceName());
    for (Field f : o.members()) {
      String cap = JavaNames.cap(f.name());
      Formattable inner =
          f.isMessage()
              ? RawExpr.of("@0.Interop.from(x.get@1())", recordSiblingFqn(f), cap)
              : fromExpr(f);
      sw.caseArm(
          JavaNames.snakeUpper(f.name()),
          RawExpr.of("new @0.@1Value(@2)", o.ifaceName(), cap, inner));
    }
    sw.defaultArm(RawExpr.of("null"));
    return LocalVar.builder(TypeRef.of(o.ifaceName()), "_" + o.name())
        .annotation(Annotation.NULLABLE)
        .assigned(sw.build());
  }

  private static List<Element> emitOneofTo(Oneof o, String refPkg) {
    String iface = o.ifaceName();
    Switch.Builder sw = Switch.on("r.@0()", o.componentName());
    // The null arm: an empty body auto-braces to `case null -> {}`.
    sw.caseArm("null");
    for (Field f : o.members()) {
      String cap = JavaNames.cap(f.name());
      String pattern = RawExpr.of("@0.@1Value v", iface, cap).render(null);
      if (f.isMessage()) {
        String recSib = recordSiblingFqn(f);
        sw.caseArm(pattern, Element.expr("_b.set@0(@1.Interop.to(v.value()))", cap, recSib));
      } else {
        sw.caseArm(pattern, toSetStatement(f, "v.value()", refPkg));
      }
    }
    return List.of(sw.build());
  }

  // -- helpers ------------------------------------------------------------------

  /** The record sibling short name from the field's typeUrl last segment. */
  private static String recordSiblingFqn(Field f) {
    return JavaNames.cap(lastSegment(f.typeUrl()));
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

  private static String mapValueFrom(Field f) {
    String t = f.mapValue();
    if (t == null) return "_e.getValue()";
    if ("bytes".equals(t)) return "_e.getValue().toByteArray()";
    return "_e.getValue()";
  }

  private static Formattable mapKeyTo(Field f) {
    if ("bytes".equals(f.mapKey())) {
      return RawExpr.of("@0.copyFrom(_e.getKey())", TypeRefs.BYTE_STRING);
    }
    return RawExpr.of("_e.getKey()");
  }

  private static Formattable mapValueTo(Field f) {
    if ("bytes".equals(f.mapValue())) {
      return RawExpr.of("@0.copyFrom(_e.getValue())", TypeRefs.BYTE_STRING);
    }
    return RawExpr.of("_e.getValue()");
  }
}
