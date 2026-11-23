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
import bijnagte.recordbuf.plugin.types.TypeRefs;
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
   * its protobuf-java reference type, without a Jackson surface.
   *
   * @param m the message model (must have {@code protobufJava()} non-null/non-empty)
   * @param sourceName the source {@code .proto} file name (used in the Javadoc)
   * @return the nested {@code Interop} {@link ClassSpec} node
   */
  public static ClassSpec toInterop(Message m, String sourceName) {
    return toInterop(m, sourceName, false);
  }

  /**
   * Returns the nested {@code Interop} {@link ClassSpec} node that converts {@code m} to and from
   * its protobuf-java reference type.
   *
   * @param m the message model (must have {@code protobufJava()} non-null/non-empty)
   * @param sourceName the source {@code .proto} file name (used in the Javadoc)
   * @param jackson when {@code true} (the file opts into recordbuf.jackson) the record carries the
   *     {@code jsonExtras} component, so {@code from} passes a {@code null} extras argument (the
   *     record's compact constructor normalizes it) before the empty carrier; a protobuf-java ref
   *     never carries JSON extras
   * @return the nested {@code Interop} {@link ClassSpec} node
   */
  public static ClassSpec toInterop(Message m, String sourceName, boolean jackson) {
    String refPkg = m.protobufJava();
    if (refPkg == null || refPkg.isEmpty()) {
      throw new IllegalArgumentException("toInterop: protobufJava must be non-null/non-empty");
    }
    // The ref is used below as TypeRef.of(ref); the TypeCollector's collision policy emits it
    // fully-qualified, since its short name collides with the record's own name.

    TypeRef.Named recType = m.type();
    TypeRef.Named refType =
        new TypeRef.Named(refPkg, recType.enclosing(), recType.shortName(), List.of());

    Javadoc javadoc =
        GeneratedDocs.type(
            null,
            "Structural X <-> R converter between "
                + recType.shortName()
                + " (record) and "
                + refType.fqn()
                + " (protobuf-java), generated from "
                + sourceName
                + ". Field-by-field conversion only.");

    var fromMethod = emitFrom(m, refType, recType, jackson);
    var toMethod = emitTo(m, refType, recType);

    return ClassSpec.named(refType.sub("Interop"))
        .public_()
        .static_()
        .final_()
        .javadoc(javadoc)
        .constructor(Constructor.builder().private_().build())
        .method(fromMethod, toMethod)
        .build();
  }

  // -- from(reference) -> record ------------------------------------------------

  private static Method emitFrom(Message m, TypeRef ref, TypeRef rec, boolean jackson) {
    var method = Method.named("from").public_().static_().returnType(rec).param(ref, "x");
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
    if (jackson) {
      // The record carries the jsonExtras component between the oneofs and the carrier; a
      // protobuf-java ref never has JSON extras, so pass null (the record's compact constructor
      // normalizes null to an empty mutable map).
      ctorArgs.add(
          RawExpr.of(
              "null")); // bare null: the record's nullable jsonExtras component target-types it
    }
    ctorArgs.add(RawExpr.of("@0.empty()", TypeRefs.UNKNOWN_FIELDS));
    return method.body(Element.ret("new @0(@1)", rec, CommaList.of(ctorArgs))).build();
  }

  private static List<Element> fromLocal(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
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
          LocalVar.builder(f.type(), "_" + f.javaName())
              .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST)));
      out.add(
          For.enhanced("var _e", "x.get@0List()", cap)
              .body(Element.expr("_@0.add(@1.Interop.from(_e))", f.javaName(), recSib))
              .build());
      return out;
    }
    if (f.isMessage()) {
      out.add(
          Element.expr(
              RawExpr.of(
                  "var _@0 = x.has@1() ? @2.Interop.from(x.get@1()) : null",
                  f.javaName(),
                  JavaNames.protobufJavaAccessor(f.name()),
                  f.type())));
      return out;
    }
    if (f.kind() == FieldKind.ENUM && f.isRepeated()) {
      out.add(
          LocalVar.builder(f.type(), "_" + f.javaName())
              .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST)));
      out.add(
          For.enhanced("var _e", "x.get@0ValueList()", cap)
              .body(Element.expr("_@0.add(@1.forNumber(_e))", f.javaName(), enumElementType(f)))
              .build());
      return out;
    }
    if (f.kind() == FieldKind.ENUM) {
      out.add(Element.expr("var _@0 = @1", f.javaName(), singularEnumFrom(f)));
      return out;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out.add(
            LocalVar.builder(f.type(), "_" + f.javaName())
                .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST)));
        out.add(
            For.enhanced("var _e", "x.get@0List()", cap)
                .body(
                    Element.expr(
                        RawExpr.of(
                            "_@0.add(@1.of(_e.toByteArray()))", f.javaName(), TypeRefs.BYTES)))
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

  /**
   * A singular enum's record value. proto3 has no enum presence, so the default 0 reads as absent
   * (null); an explicit {@code optional} enum has presence, so its set 0 is the zero constant.
   */
  private static Formattable singularEnumFrom(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    if (f.isNullable()) {
      return RawExpr.of("x.has@0() ? @1.forNumber(x.get@0Value()) : null", cap, f.type());
    }
    return RawExpr.of("x.get@0Value() == 0 ? null : @1.forNumber(x.get@0Value())", cap, f.type());
  }

  private static Formattable fromExpr(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    if (f.kind() == FieldKind.ENUM) {
      return RawExpr.of("@0.forNumber(x.get@1Value())", f.type(), cap);
    }
    if ("bytes".equals(f.typeUrl())) {
      return RawExpr.of("x.get@0().toByteArray()", cap);
    }
    return RawExpr.of("x.get@0()", cap);
  }

  // -- to(record) -> reference --------------------------------------------------

  private static Method emitTo(Message m, TypeRef.Named ref, TypeRef rec) {
    var method =
        Method.named("to")
            .public_()
            .static_()
            .returnType(ref)
            .param(rec, "r")
            .body(Element.expr("var _b = @0.newBuilder()", ref));
    for (Field f : m.fields()) {
      method.body(toStmt(f));
    }
    for (Oneof o : m.oneofs()) {
      method.body(emitOneofTo(o));
    }
    return method.body(Element.ret("_b.build()")).build();
  }

  private static Element toStmt(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    final Element out;
    String src = "r." + f.javaName() + "()";

    if (f.isMap()) {
      out =
          For.enhanced("var _e", "@0.entrySet()", src)
              .body(Element.expr("_b.put@0(@1, @2)", cap, mapKeyTo(f), mapValueTo(f)))
              .build();
    } else if (f.isMessage() && f.isRepeated()) {
      String recSib = recordSiblingFqn(f);
      out =
          For.enhanced("var _e", src)
              .body(Element.expr("_b.add@0(@1.Interop.to(_e))", cap, recSib))
              .build();
    } else if (f.isMessage()) {
      String recSib = recordSiblingFqn(f);
      // Use a local variable so NullAway can track the non-null guard

      String local = "n" + cap;
      out =
          If.condition("@0 != null", src)
              .body(
                  Element.expr("var @0 = @1", local, src),
                  Element.expr("_b.set@0(@1.Interop.to(@2))", cap, recSib, local))
              .build();
    } else if (f.kind() == FieldKind.ENUM && f.isRepeated()) {
      out =
          For.enhanced("var _e", src)
              .body(Element.expr("_b.add@0Value(_e.getNumber())", cap))
              .build();
    } else if (f.kind() == FieldKind.ENUM) {
      // Use a local variable so NullAway can track the non-null guard
      String local = "n" + cap;
      out =
          If.condition("@0 != null", src)
              .body(
                  Element.expr("var @0 = @1", local, src),
                  Element.expr("_b.set@0Value(@1.getNumber())", cap, local))
              .build();
    } else if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out =
            For.enhanced("var _e", src)
                .body(
                    Element.expr(
                        RawExpr.of(
                            "_b.add@1(@0.copyFrom(_e.toByteArray()))", TypeRefs.BYTE_STRING, cap)))
                .build();
      } else {
        out = Element.expr("_b.addAll@0(@1)", cap, src);
      }

    } else {
      out = toSetStatement(f, src);
    }
    return out;
  }

  private static Element toSetStatement(Field f, String src) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    if (f.kind() == FieldKind.ENUM) {
      return Element.expr("_b.set@0Value(@1.getNumber())", cap, src);
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
      String cap = JavaNames.protobufJavaAccessor(f.name());
      Formattable inner =
          f.isMessage()
              ? RawExpr.of("@0.Interop.from(x.get@1())", recordSiblingFqn(f), cap)
              : fromExpr(f);
      sw.caseArm(
          JavaNames.snakeUpper(f.name()),
          RawExpr.of("new @0.@1Value(@2)", o.ifaceName(), JavaNames.cap(f.name()), inner));
    }
    sw.defaultArm(RawExpr.of("null"));
    return LocalVar.builder(TypeRef.of(o.ifaceName()), "_" + o.name())
        .annotation(Annotation.NULLABLE)
        .assigned(sw.build());
  }

  private static List<Element> emitOneofTo(Oneof o) {
    String iface = o.ifaceName();
    Switch.Builder sw = Switch.on("r.@0()", o.componentName());
    // The null arm: an empty body auto-braces to `case null -> {}`.
    sw.caseArm("null");
    for (Field f : o.members()) {
      String cap = JavaNames.protobufJavaAccessor(f.name());
      // The member value record is named on the record side (cap of the proto name), not by the
      // protobuf-java accessor rule.
      String pattern = RawExpr.of("@0.@1Value v", iface, JavaNames.cap(f.name())).render(null);
      if (f.isMessage()) {
        String recSib = recordSiblingFqn(f);
        sw.caseArm(pattern, Element.expr("_b.set@0(@1.Interop.to(v.value()))", cap, recSib));
      } else {
        sw.caseArm(pattern, toSetStatement(f, "v.value()"));
      }
    }
    return List.of(sw.build());
  }

  // -- helpers ------------------------------------------------------------------

  /** The record sibling short name from the field's typeUrl last segment. */
  private static String recordSiblingFqn(Field f) {
    return JavaNames.cap(lastSegment(f.typeUrl()));
  }

  /**
   * The record-side enum type of an enum field: the field's own type, or the element type of a
   * repeated field's {@code List}. Taken from the model, so a nested enum resolves to its owner.
   */
  private static TypeRef enumElementType(Field f) {
    return f.isRepeated() ? ((TypeRef.Named) f.type()).generics().get(0) : f.type();
  }

  private static String lastSegment(String fqn) {
    if (fqn == null) return "";
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
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
