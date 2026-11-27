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
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
    ctorArgs.add(RawExpr.of("@0.fromProto(x.getUnknownFields())", TypeRefs.UNKNOWN_FIELDS));
    return method.body(Element.ret("new @0(@1)", rec, CommaList.of(ctorArgs))).build();
  }

  private static List<Element> fromLocal(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    List<Element> out = new ArrayList<>();
    NativeWkt wkt = nativeWkt(f);
    if (wkt != null) {
      return nativeWktFrom(f, wkt);
    }
    if (dynamicWkt(f) != null) {
      return dynamicWktFrom(f);
    }
    if (f.isMap()) {
      if (!convertsEntries(f)) {
        // Scalar map: copy the protobuf-java map directly (compatible key/value types)
        out.add(
            Element.expr(
                RawExpr.of(
                    "var _@0 = new @1<>(x.get@2Map())", f.javaName(), TypeRefs.HASH_MAP, cap)));
      } else {
        // A message or enum value, or a bytes key or value, converts entry by entry. The local is
        // typed from the model: `var` would infer HashMap<Object, Object> from the diamond.
        out.add(
            LocalVar.builder(f.type(), "_" + f.javaName())
                .assigned(RawExpr.of("new @0<>()", TypeRefs.HASH_MAP)));
        out.add(
            For.enhanced("var _e", "@0.entrySet()", mapEntriesFrom(f))
                .body(Element.expr("_@0.put(@1, @2)", f.javaName(), mapKeyFrom(f), mapValueFrom(f)))
                .build());
      }
      return out;
    }
    if (f.isMessage() && f.isRepeated()) {
      return repeatedFrom(f, "x.get" + cap + "List()");
    }
    if (f.isMessage()) {
      out.add(
          Element.expr(
              RawExpr.of(
                  "var _@0 = x.has@1() ? @2 : null",
                  f.javaName(),
                  cap,
                  valueFrom(f, "x.get" + cap + "()"))));
      return out;
    }
    if (f.kind() == FieldKind.ENUM && f.isRepeated()) {
      return repeatedFrom(f, "x.get" + cap + "ValueList()");
    }
    if (f.kind() == FieldKind.ENUM) {
      out.add(Element.expr("var _@0 = @1", f.javaName(), singularEnumFrom(f)));
      return out;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        return repeatedFrom(f, "x.get" + cap + "List()");
      } else {
        // For non-bytes repeated fields, use the protobuf-java list directly;
        // var infers the correct element type from x.getList().
        out.add(Element.expr("var _@0 = x.get@1List()", f.javaName(), cap));
      }
      return out;
    }
    // Singular scalar / bytes
    if (f.isNullable() && !"bytes".equals(f.typeUrl())) {
      out.add(Element.expr("var _@0 = x.has@1() ? x.get@1() : null", f.javaName(), cap));
    } else {
      out.add(Element.expr("var _@0 = @1", f.javaName(), valueFrom(f, "x.get" + cap + "()")));
    }
    return out;
  }

  /**
   * A repeated field's record list: a new list that takes {@link #valueFrom} of each element of the
   * protobuf-java list expression {@code protobufList}.
   */
  private static List<Element> repeatedFrom(Field f, String protobufList) {
    return List.of(
        LocalVar.builder(f.type(), "_" + f.javaName())
            .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST)),
        For.enhanced("var _e", "@0", protobufList)
            .body(Element.expr("_@0.add(@1)", f.javaName(), valueFrom(f, "_e")))
            .build());
  }

  /**
   * One value of {@code f}, read from protobuf-java and converted to its record form. The singular
   * field arms, the repeated element arms and the oneof member arms all convert through this one
   * method, so a kind converts the same way wherever it appears. {@code src} is the protobuf-java
   * value expression: the message, the {@code ByteString} or scalar, or, for an enum, its number
   * ({@code get<Name>Value()} or a {@code get<Name>ValueList()} element).
   */
  private static Formattable valueFrom(Field f, String src) {
    NativeWkt wkt = nativeWkt(f);
    if (wkt != null) {
      return RawExpr.of("@0.@1(@2)", TypeRefs.PROTOBUF_JAVA_WKT, wkt.fromProtobuf(), src);
    }
    DynamicWktMethods dynamic = dynamicWkt(f);
    if (dynamic != null) {
      return RawExpr.of("@0.@1(@2)", TypeRefs.DYNAMIC_WKT, dynamic.fromProtobuf(), src);
    }
    return convertFrom(f.kind(), "bytes".equals(f.typeUrl()), elementType(f), src);
  }

  /**
   * The record form of one protobuf-java value that has no well-known type: a message through its
   * Interop, an enum through {@code forNumber}, {@code bytes} through {@code Bytes}, and a scalar
   * unchanged. A singular, repeated, oneof or map value all convert through this method.
   */
  private static Formattable convertFrom(FieldKind kind, boolean bytes, TypeRef type, String src) {
    if (kind == FieldKind.MESSAGE) {
      return RawExpr.of("@0.Interop.from(@1)", type, src);
    }
    if (kind == FieldKind.ENUM) {
      return RawExpr.of("@0.forNumber(@1)", type, src);
    }
    if (bytes) {
      return RawExpr.of("@0.of(@1.toByteArray())", TypeRefs.BYTES, src);
    }
    return RawExpr.of("@0", src);
  }

  /**
   * A natively mapped well-known type field, read from protobuf-java through {@link
   * bijnagte.recordbuf.runtime.wkt.ProtobufJavaWkt}. A singular wrapper is {@code null} when
   * absent; a singular structured WKT reads as its zero default when absent; a repeated field
   * converts every element.
   */
  private static List<Element> nativeWktFrom(Field f, NativeWkt wkt) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    if (f.isRepeated()) {
      return repeatedFrom(f, "x.get" + cap + "List()");
    }
    String absent = wkt.entry().hasZeroDefault() ? zeroDefault(wkt) : "null";
    return List.of(
        Element.expr(
            "var _@0 = x.has@1() ? @2 : @3",
            f.javaName(),
            cap,
            valueFrom(f, "x.get" + cap + "()"),
            absent));
  }

  /**
   * A dynamic well-known type field (Struct, Value, ListValue, Any), read from protobuf-java as its
   * Jackson node through {@link bijnagte.recordbuf.json.DynamicWkt}. A singular field is {@code
   * null} when absent; a repeated field converts every element.
   */
  private static List<Element> dynamicWktFrom(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    if (f.isRepeated()) {
      return repeatedFrom(f, "x.get" + cap + "List()");
    }
    return List.of(
        Element.expr(
            "var _@0 = x.has@1() ? @2 : null",
            f.javaName(),
            cap,
            valueFrom(f, "x.get" + cap + "()")));
  }

  /**
   * A singular enum's record value. proto3 has no enum presence, so the default 0 reads as absent
   * (null); an explicit {@code optional} enum has presence, so its set 0 is the zero constant.
   */
  private static Formattable singularEnumFrom(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    if (f.isNullable()) {
      return RawExpr.of("x.has@0() ? @1 : null", cap, valueFrom(f, "x.get" + cap + "Value()"));
    }
    return RawExpr.of(
        "x.get@0Value() == 0 ? null : @1", cap, valueFrom(f, "x.get" + cap + "Value()"));
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
    return method
        .body(Element.expr("_b.setUnknownFields(r.unknownFields().toProto())"))
        .body(Element.ret("_b.build()"))
        .build();
  }

  private static Element toStmt(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    String src = "r." + f.javaName() + "()";
    NativeWkt wkt = nativeWkt(f);
    if (wkt != null) {
      return nativeWktTo(f, wkt);
    }
    if (dynamicWkt(f) != null) {
      return dynamicWktTo(f);
    }
    if (f.isMap()) {
      // An enum value is put by its number, as the protobuf-java map setter takes an int.
      String put = cap + (f.mapValueKind() == FieldKind.ENUM ? "Value" : "");
      return For.enhanced("var _e", "@0.entrySet()", src)
          .body(Element.expr("_b.put@0(@1, @2)", put, mapKeyTo(f), mapValueTo(f)))
          .build();
    }
    if (f.isRepeated()) {
      if (f.isMessage() || f.kind() == FieldKind.ENUM || "bytes".equals(f.typeUrl())) {
        return For.enhanced("var _e", src)
            .body(Element.expr("_b.add@0(@1)", setterStem(f), valueTo(f, "_e")))
            .build();
      }
      return Element.expr("_b.addAll@0(@1)", cap, src);
    }
    if (f.isMessage() || f.kind() == FieldKind.ENUM) {
      // Use a local variable so NullAway can track the non-null guard
      String local = "n" + cap;
      return If.condition("@0 != null", src)
          .body(
              Element.expr("var @0 = @1", local, src),
              Element.expr("_b.set@0(@1)", setterStem(f), valueTo(f, local)))
          .build();
    }
    return toSetStatement(f, src);
  }

  /**
   * The stem of the protobuf-java setter and adder of {@code f}: {@code <Name>}, or {@code
   * <Name>Value} for an enum, which protobuf-java sets by its number.
   */
  private static String setterStem(Field f) {
    String cap = Objects.requireNonNull(JavaNames.protobufJavaAccessor(f.name()));
    return f.kind() == FieldKind.ENUM ? cap + "Value" : cap;
  }

  /**
   * One value of {@code f}, converted from its record form to the argument of the protobuf-java
   * setter or adder named by {@link #setterStem}. The singular field arms, the repeated element
   * arms and the oneof member arms all convert through this one method (the inverse of {@link
   * #valueFrom}). {@code src} is the record value expression.
   */
  private static Formattable valueTo(Field f, String src) {
    NativeWkt wkt = nativeWkt(f);
    if (wkt != null) {
      return RawExpr.of("@0.@1(@2)", TypeRefs.PROTOBUF_JAVA_WKT, wkt.toProtobuf(), src);
    }
    DynamicWktMethods dynamic = dynamicWkt(f);
    if (dynamic != null) {
      return dynamicToProtobuf(dynamic, src);
    }
    return convertTo(f.kind(), "bytes".equals(f.typeUrl()), elementType(f), src);
  }

  /**
   * The inverse of {@link #convertFrom}: the protobuf-java argument of a value that has no
   * well-known type. An enum is set by its number.
   */
  private static Formattable convertTo(FieldKind kind, boolean bytes, TypeRef type, String src) {
    if (kind == FieldKind.MESSAGE) {
      return RawExpr.of("@0.Interop.to(@1)", type, src);
    }
    if (kind == FieldKind.ENUM) {
      return RawExpr.of("@0.getNumber()", src);
    }
    if (bytes) {
      return RawExpr.of("@0.copyFrom(@1.toByteArray())", TypeRefs.BYTE_STRING, src);
    }
    return RawExpr.of("@0", src);
  }

  /**
   * A natively mapped well-known type field, written to protobuf-java through {@link
   * bijnagte.recordbuf.runtime.wkt.ProtobufJavaWkt}. A singular wrapper is set when present. A
   * singular structured WKT is set only when it differs from its zero default (KTD7), as the binary
   * encoder skips the default. A repeated field converts every element, defaults included.
   */
  private static Element nativeWktTo(Field f, NativeWkt wkt) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    String src = "r." + f.javaName() + "()";
    if (f.isRepeated()) {
      return For.enhanced("var _e", src)
          .body(Element.expr("_b.add@0(@1)", cap, valueTo(f, "_e")))
          .build();
    }
    if (wkt.entry().hasZeroDefault()) {
      // The record component is @Nullable (its constructor normalizes null to the zero default).
      return If.condition("@0 != null && !@1.equals(@0)", src, zeroDefault(wkt))
          .body(Element.expr("_b.set@0(@1)", cap, valueTo(f, src)))
          .build();
    }
    // Use a local variable so NullAway can track the non-null guard
    String local = "n" + cap;
    return If.condition("@0 != null", src)
        .body(
            Element.expr("var @0 = @1", local, src),
            Element.expr("_b.set@0(@1)", cap, valueTo(f, local)))
        .build();
  }

  /**
   * A dynamic well-known type field, written to protobuf-java through {@link
   * bijnagte.recordbuf.json.DynamicWkt}. A singular field is set when non-null, the same presence
   * the record's binary encoder uses; a repeated field converts every element.
   */
  private static Element dynamicWktTo(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    String src = "r." + f.javaName() + "()";
    if (f.isRepeated()) {
      return For.enhanced("var _e", src)
          .body(Element.expr("_b.add@0(@1)", cap, valueTo(f, "_e")))
          .build();
    }
    // Use a local variable so NullAway can track the non-null guard
    String local = "n" + cap;
    return If.condition("@0 != null", src)
        .body(
            Element.expr("var @0 = @1", local, src),
            Element.expr("_b.set@0(@1)", cap, valueTo(f, local)))
        .build();
  }

  /**
   * The protobuf-java message of one dynamic WKT node, as an expression. An {@code Any} node is
   * {@code {typeUrl, value}}, so its typeUrl comes from the node ({@code contentType}) and its
   * payload from the node's {@code value} member.
   */
  private static Formattable dynamicToProtobuf(DynamicWktMethods dynamic, String node) {
    if (dynamic.fqn().equals(ANY_FQN)) {
      return RawExpr.of(
          "@0.anyFrom(@0.contentType(\"@1\", @2), @2.get(\"value\"))",
          TypeRefs.DYNAMIC_WKT,
          ANY_FQN,
          node);
    }
    return RawExpr.of("@0.@1(@2)", TypeRefs.DYNAMIC_WKT, dynamic.toProtobuf(), node);
  }

  /**
   * A singular scalar or bytes field's setter call; a nullable (optional) field is set if present.
   */
  private static Element toSetStatement(Field f, String src) {
    Element set = Element.expr("_b.set@0(@1)", setterStem(f), valueTo(f, src));
    if (f.isNullable() && !"bytes".equals(f.typeUrl())) {
      return If.condition("@0 != null", src).body(set).build();
    }
    return set;
  }

  /**
   * The record's oneof value read from protobuf-java's case switch. protobuf-java names the case
   * accessor {@code get<Accessor>Case()} from the oneof's name and each case constant from the
   * member's upper-cased name; an unset oneof (the {@code *_NOT_SET} constant) reads as null. Each
   * set member converts through {@link #valueFrom}, as a singular field does, and is present even
   * at its default value.
   */
  private static Element emitOneofFrom(Oneof o) {
    var sw = SwitchExpr.on("x.get@0Case()", JavaNames.protobufJavaAccessor(o.name()));
    for (Field f : o.members()) {
      String cap = JavaNames.protobufJavaAccessor(f.name());
      String read = f.kind() == FieldKind.ENUM ? "x.get" + cap + "Value()" : "x.get" + cap + "()";
      sw.caseArm(
          JavaNames.snakeUpper(f.name()),
          RawExpr.of("new @0.@1(@2)", o.ifaceName(), Oneof.valueRecordName(f), valueFrom(f, read)));
    }
    sw.defaultArm(RawExpr.of("null"));
    return LocalVar.builder(TypeRef.of(o.ifaceName()), "_" + o.name())
        .annotation(Annotation.NULLABLE)
        .assigned(sw.build());
  }

  /**
   * Sets the set oneof member on the protobuf-java builder, converting its value through {@link
   * #valueTo} as a singular field does. An unset oneof sets nothing.
   */
  private static List<Element> emitOneofTo(Oneof o) {
    String iface = o.ifaceName();
    Switch.Builder sw = Switch.on("r.@0()", o.componentName());
    // The null arm: an empty body auto-braces to `case null -> {}`.
    sw.caseArm("null");
    for (Field f : o.members()) {
      // The member value record is named on the record side (Oneof.valueRecordName), not by the
      // protobuf-java accessor rule.
      String pattern = RawExpr.of("@0.@1 v", iface, Oneof.valueRecordName(f)).render(null);
      sw.caseArm(pattern, Element.expr("_b.set@0(@1)", setterStem(f), valueTo(f, "v.value()")));
    }
    return List.of(sw.build());
  }

  // -- helpers ------------------------------------------------------------------

  /**
   * A natively mapped well-known type: its registry entry and the {@code ProtobufJavaWkt} methods
   * that convert it from protobuf-java ({@code fromProtobuf}) and to it ({@code toProtobuf}). Null
   * for every other field, including the dynamic WKTs (see {@link DynamicWktMethods}).
   */
  private record NativeWkt(WellKnownTypes.Entry entry, String fromProtobuf, String toProtobuf) {}

  /**
   * A dynamic well-known type: its FQN and the {@code DynamicWkt} methods that convert it from
   * protobuf-java ({@code fromProtobuf}, the node read from the message) and to it ({@code
   * toProtobuf}, the message built from the node; unused for {@code Any}, see {@link
   * #dynamicToProtobuf}).
   */
  private record DynamicWktMethods(String fqn, String fromProtobuf, String toProtobuf) {}

  private static final String ANY_FQN = "google.protobuf.Any";

  private static @Nullable DynamicWktMethods dynamicWkt(Field f) {
    if (f.kind() != FieldKind.MESSAGE) {
      return null;
    }
    WellKnownTypes.Entry entry = WellKnownTypes.resolve(f.typeUrl());
    if (entry == null || !entry.isDynamic()) {
      return null;
    }
    return switch (entry.fqn()) {
      case "google.protobuf.Struct" -> new DynamicWktMethods(entry.fqn(), "struct", "structFrom");
      case "google.protobuf.Value" -> new DynamicWktMethods(entry.fqn(), "value", "valueFrom");
      case "google.protobuf.ListValue" -> new DynamicWktMethods(entry.fqn(), "list", "listFrom");
      case "google.protobuf.Any" -> new DynamicWktMethods(entry.fqn(), "any", "anyFrom");
      default -> throw new IllegalStateException("no DynamicWkt conversion for " + entry.fqn());
    };
  }

  private static @Nullable NativeWkt nativeWkt(Field f) {
    if (f.kind() != FieldKind.MESSAGE) {
      return null;
    }
    WellKnownTypes.Entry entry = WellKnownTypes.resolve(f.typeUrl());
    if (entry == null || entry.isDynamic()) {
      return null;
    }
    return switch (entry.fqn()) {
      case "google.protobuf.Timestamp" -> new NativeWkt(entry, "toInstant", "fromInstant");
      case "google.protobuf.Duration" -> new NativeWkt(entry, "toDuration", "fromDuration");
      case "google.protobuf.FieldMask" -> new NativeWkt(entry, "toFieldMask", "fromFieldMask");
      case "google.protobuf.Empty" -> new NativeWkt(entry, "toEmpty", "fromEmpty");
      case "google.protobuf.BoolValue" -> new NativeWkt(entry, "toBoolean", "boolValue");
      case "google.protobuf.Int32Value" -> new NativeWkt(entry, "toInt32", "int32Value");
      case "google.protobuf.UInt32Value" -> new NativeWkt(entry, "toUint32", "uint32Value");
      case "google.protobuf.Int64Value" -> new NativeWkt(entry, "toInt64", "int64Value");
      case "google.protobuf.UInt64Value" -> new NativeWkt(entry, "toUint64", "uint64Value");
      case "google.protobuf.FloatValue" -> new NativeWkt(entry, "toFloat", "floatValue");
      case "google.protobuf.DoubleValue" -> new NativeWkt(entry, "toDouble", "doubleValue");
      case "google.protobuf.StringValue" -> new NativeWkt(entry, "toStringValue", "stringValue");
      case "google.protobuf.BytesValue" -> new NativeWkt(entry, "toBytes", "bytesValue");
      default -> null;
    };
  }

  /** The zero default of a structured WKT, as the record's field initializer spells it. */
  private static String zeroDefault(NativeWkt wkt) {
    return Objects.requireNonNull(wkt.entry().zeroDefault());
  }

  /**
   * The record-side type of one value of a field: the field's own type, or the element type of a
   * repeated field's {@code List}. Taken from the model, so a nested message or enum resolves to
   * its owner.
   */
  private static TypeRef elementType(Field f) {
    return f.isRepeated() ? ((TypeRef.Named) f.type()).generics().get(0) : f.type();
  }

  /**
   * Whether a map's entries convert one by one rather than copying across: a message or enum value,
   * or a bytes key or value. A scalar map with no bytes copies as protobuf-java's map.
   */
  private static boolean convertsEntries(Field f) {
    return f.mapValueKind() != FieldKind.SCALAR
        || "bytes".equals(f.mapKey())
        || "bytes".equals(f.mapValue());
  }

  /**
   * The protobuf-java map a converted map iterates. An enum value is read by its number through the
   * {@code get<Name>ValueMap()} accessor, which avoids protobuf-java's {@code UNRECOGNIZED}
   * constant.
   */
  private static String mapEntriesFrom(Field f) {
    String cap = JavaNames.protobufJavaAccessor(f.name());
    return "x.get" + cap + (f.mapValueKind() == FieldKind.ENUM ? "ValueMap()" : "Map()");
  }

  private static String mapKeyFrom(Field f) {
    return "bytes".equals(f.mapKey()) ? "_e.getKey().toByteArray()" : "_e.getKey()";
  }

  private static Formattable mapValueFrom(Field f) {
    return convertFrom(
        Objects.requireNonNull(f.mapValueKind()),
        "bytes".equals(f.mapValue()),
        f.mapValueType(),
        "_e.getValue()");
  }

  private static Formattable mapKeyTo(Field f) {
    if ("bytes".equals(f.mapKey())) {
      return RawExpr.of("@0.copyFrom(_e.getKey())", TypeRefs.BYTE_STRING);
    }
    return RawExpr.of("_e.getKey()");
  }

  private static Formattable mapValueTo(Field f) {
    return convertTo(
        Objects.requireNonNull(f.mapValueKind()),
        "bytes".equals(f.mapValue()),
        f.mapValueType(),
        "_e.getValue()");
  }
}
