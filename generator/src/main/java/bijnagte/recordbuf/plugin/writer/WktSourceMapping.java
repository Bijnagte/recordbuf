package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.source.Case;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.For;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.LocalVar;
import bijnagte.recordbuf.codegen.source.RawExpr;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import bijnagte.recordbuf.plugin.types.WellKnownTypes.Entry;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * M4-C2d-12: WKT (well-known type) codec + local-init helper.
 *
 * <p>Each WKT field type has a {@code WellKnownTypes.Entry} that supplies the Java type name, the
 * {@code bijnagte.recordbuf.runtime.wkt.Wkt} static methods to call, and (optionally) a
 * zero-default expression.
 */
final class WktSourceMapping {

  private WktSourceMapping() {}

  static @Nullable Entry resolve(Field f) {
    return WellKnownTypes.resolve(f.typeUrl());
  }

  static boolean isWkt(Field f) {
    return resolve(f) != null;
  }

  // local default-init: "Instant at = Instant.EPOCH;" / "List<Instant> whens = new ArrayList<>();"
  // /
  // "Integer i32 = null;" per Wkt.java type

  static Element localInitElement(Field f, Entry entry) {
    if (entry.nodeType() != null) return dynamicLocalInit(f, entry);
    TypeRef type = requireType(entry);
    String name = f.javaName();
    if (f.label() == FieldLabel.REPEATED) {
      TypeRef namedList = TypeRefs.LIST.withGenerics(requireType(entry));
      return LocalVar.builder(namedList, name)
          .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST));
    }
    if (entry.hasZeroDefault()) {
      return LocalVar.builder(type, name)
          .assigned(RawExpr.of(Objects.requireNonNull(entry.zeroDefault())));
    }
    // Wrapper WKT (nullable)
    return LocalVar.builder(type, name).assigned(RawExpr.of("null"));
  }

  // encode arm: for structured WKT, "if (!v.field().equals(ZERO)) out.writeByteArray(n,
  // Wkt.method(v.field()));"
  //            for wrapper WKT,   "if (v.field() != null) out.writeByteArray(n,
  // Wkt.wrap(Wkt.Wrap.KIND, v.field()));"
  //            repeated form:      "for (var e : v.field()) out.writeByteArray(n, Wkt.method(e));"

  static Element emitEncode(Field f, Entry entry, String out, String v) {
    if (entry.nodeType() != null) return dynamicEncode(f, entry, out, v);
    TypeRef wktRef = TypeRefs.WKT;
    int num = f.number();
    String g = v + "." + f.javaName() + "()";
    String methodName = encodeMethod(f.typeUrl());

    if (f.label() == FieldLabel.REPEATED) {
      if (entry.hasZeroDefault()) {
        String def = Objects.requireNonNull(entry.zeroDefault());
        // for (var e : v.field()) { if (!e.equals(def)) out.writeByteArray(n, Wkt.method(e)); }
        return For.enhanced("var e", g)
            .body(
                If.condition("!e.equals(@0)", def)
                    .body(
                        Element.expr(
                            "@0.writeByteArray(@1, @2.@3(e))", out, num, wktRef, methodName))
                    .build())
            .build();
      } else {
        // for (var e : v.field()) out.writeByteArray(n, Wkt.wrap(Wkt.Wrap.KIND, e));
        return For.enhanced("var e", g)
            .body(
                Element.expr(
                    "@0.writeByteArray(@1, @2.wrap(@2.Wrap.@3, e))",
                    out,
                    num,
                    wktRef,
                    wrapKind(f.typeUrl())))
            .build();
      }
    } else if (entry.hasZeroDefault()) {
      String def = Objects.requireNonNull(entry.zeroDefault());
      // if (v.field() != null && !def.equals(v.field())) out.writeByteArray(n,
      // Wkt.method(v.field()));
      return If.condition("@0 != null && !@1.equals(@0)", g, def)
          .body(Element.expr("@0.writeByteArray(@1, @2.@3(@4))", out, num, wktRef, methodName, g))
          .build();
    } else {
      // if (v.field() != null) out.writeByteArray(n, Wkt.wrap(Wkt.Wrap.KIND, v.field()));
      return If.condition("@0 != null", g)
          .body(
              Element.expr(
                  "@0.writeByteArray(@1, @2.wrap(@2.Wrap.@3, @4))",
                  out,
                  num,
                  wktRef,
                  wrapKind(f.typeUrl()),
                  g))
          .build();
    }
  }

  // decode case: "case N -> { field = Wkt.parseName(in.readByteArray()); }"
  //             "case N -> { field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray()); }"

  // decode case: "case N -> field = Wkt.parseName(in.readByteArray());"
  //             "case N -> field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray());"
  static Case emitDecodeCase(Field f, Entry entry, String in) {
    if (entry.nodeType() != null) return dynamicDecodeCase(f, entry, in);
    int num = f.number();
    String name = f.javaName();
    String read = in + ".readByteArray()";
    TypeRef wktRef = TypeRefs.WKT;

    Element assignment;
    if (f.label() == FieldLabel.REPEATED) {
      if (entry.hasZeroDefault()) {
        // field.add(Wkt.parseName(in.readByteArray()));
        assignment =
            Element.expr("@0.add(@1.@2(@3))", name, wktRef, parseMethod(f.typeUrl()), read);
      } else {
        TypeRef castRef = requireType(entry);
        // field.add((Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray()));
        assignment =
            Element.expr(
                "@0.add((@3) @1.parse(@1.Wrap.@4, @2))",
                name,
                wktRef,
                read,
                castRef,
                wrapKind(f.typeUrl()));
      }
    } else if (entry.hasZeroDefault()) {
      // field = Wkt.parseName(in.readByteArray());
      assignment = Element.expr("@0 = @1.@2(@3)", name, wktRef, parseMethod(f.typeUrl()), read);
    } else {
      TypeRef castRef = requireType(entry);
      // field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray());
      assignment =
          Element.expr(
              "@0 = (@3) @1.parse(@1.Wrap.@4, @2)",
              name,
              wktRef,
              read,
              castRef,
              wrapKind(f.typeUrl()));
    }
    return Case.of(String.valueOf(num), assignment);
  }

  // --- dynamic WKT (Jackson node) arms ---
  // The component is the WKT's Jackson node type; the wire arm round-trips through the
  // :recordbuf-json DynamicWkt facade, which the generated codec references directly.

  static Element dynamicLocalInit(Field f, Entry entry) {
    TypeRef type = Objects.requireNonNull(entry.nodeType(), "dynamic WKT has no node type");
    String name = f.javaName();
    if (f.label() == FieldLabel.REPEATED) {
      TypeRef namedList = TypeRefs.LIST.withGenerics(type);
      return LocalVar.builder(namedList, name)
          .assigned(RawExpr.of("new @0<>()", TypeRefs.ARRAY_LIST));
    }
    return LocalVar.builder(type, name).assigned(RawExpr.of("null"));
  }

  static Element dynamicEncode(Field f, Entry entry, String out, String v) {
    int num = f.number();
    String g = v + "." + f.javaName() + "()";
    if (f.label() == FieldLabel.REPEATED) {
      return For.enhanced("var e", g)
          .body(
              Element.expr(
                  "@0.writeByteArray(@1, @2.encode(\"" + f.typeUrl() + "\", e))",
                  out,
                  num,
                  TypeRefs.DYNAMIC_WKT))
          .build();
    }
    return If.condition("@0 != null", g)
        .body(
            Element.expr(
                "@0.writeByteArray(@1, @2.encode(\"" + f.typeUrl() + "\", @3))",
                out,
                num,
                TypeRefs.DYNAMIC_WKT,
                g))
        .build();
  }

  static Case dynamicDecodeCase(Field f, Entry entry, String in) {
    // decodeValue() returns the node cast to the component's concrete type where it is narrower
    // than JsonNode (Struct -> ObjectNode, ListValue -> ArrayNode).
    RawExpr decoded = decodeValue(f, entry, in + ".readByteArray()");
    Element assignment =
        f.label() == FieldLabel.REPEATED
            ? Element.expr("@0.add(@1)", f.javaName(), decoded)
            : Element.expr("@0 = @1", f.javaName(), decoded);
    return Case.of(String.valueOf(f.number()), assignment);
  }

  /**
   * The wire bytes of one WKT value, as an expression -- for a oneof member, which is written
   * whenever it is set (no default elision): {@code Wkt.timestamp(x)}, {@code Wkt.wrap(Wkt.Wrap.K,
   * x)}, or {@code DynamicWkt.encode("<fqn>", x)}.
   *
   * @param value the rendered value expression
   */
  static RawExpr encodeValue(Field f, Entry entry, String value) {
    if (entry.nodeType() != null) {
      return RawExpr.of("@0.encode(\"" + f.typeUrl() + "\", @1)", TypeRefs.DYNAMIC_WKT, value);
    }
    if (entry.hasZeroDefault()) {
      return RawExpr.of("@0.@1(@2)", TypeRefs.WKT, encodeMethod(f.typeUrl()), value);
    }
    return RawExpr.of("@0.wrap(@0.Wrap.@1, @2)", TypeRefs.WKT, wrapKind(f.typeUrl()), value);
  }

  /**
   * One WKT value decoded from its wire bytes, as an expression (the inverse of {@link
   * #encodeValue}), cast to the member's Java type where the runtime returns a wider one.
   *
   * @param bytes the rendered wire-bytes expression
   */
  static RawExpr decodeValue(Field f, Entry entry, String bytes) {
    TypeRef nodeType = entry.nodeType();
    if (nodeType != null) {
      return nodeType.equals(TypeRefs.JSON_NODE)
          ? RawExpr.of("@0.decode(\"" + f.typeUrl() + "\", @1)", TypeRefs.DYNAMIC_WKT, bytes)
          : RawExpr.of(
              "(@2) @0.decode(\"" + f.typeUrl() + "\", @1)", TypeRefs.DYNAMIC_WKT, bytes, nodeType);
    }
    if (entry.hasZeroDefault()) {
      return RawExpr.of("@0.@1(@2)", TypeRefs.WKT, parseMethod(f.typeUrl()), bytes);
    }
    return RawExpr.of(
        "(@0) @1.parse(@1.Wrap.@2, @3)",
        requireType(entry),
        TypeRefs.WKT,
        wrapKind(f.typeUrl()),
        bytes);
  }

  private static TypeRef requireType(Entry entry) {
    return Objects.requireNonNull(entry.javaType(), "WKT javaType must resolve");
  }

  private static String shortName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  // Method name for structured WKT encode (write side): Wkt.timestamp, Wkt.duration, etc.
  private static String encodeMethod(String typeUrl) {
    return switch (shortName(typeUrl)) {
      case "Timestamp" -> "timestamp";
      case "Duration" -> "duration";
      case "FieldMask" -> "fieldMask";
      case "Empty" -> "empty";
      default -> "write" + cap(shortName(typeUrl));
    };
  }

  // Method name for structured WKT decode (read side): Wkt.parseTimestamp, Wkt.parseDuration, etc.
  private static String parseMethod(String typeUrl) {
    return switch (shortName(typeUrl)) {
      case "Timestamp" -> "parseTimestamp";
      case "Duration" -> "parseDuration";
      case "FieldMask" -> "parseFieldMask";
      case "Empty" -> "parseEmpty";
      default -> "parse";
    };
  }

  private static String wrapKind(String typeUrl) {
    return switch (shortName(typeUrl)) {
      case "Int32Value" -> "INT32";
      case "Int64Value" -> "INT64";
      case "UInt32Value" -> "UINT32";
      case "UInt64Value" -> "UINT64";
      case "SInt32Value" -> "SINT_32";
      case "SInt64Value" -> "SINT_64";
      case "Fixed32Value" -> "FIXED32";
      case "Fixed64Value" -> "FIXED64";
      case "DoubleValue" -> "DOUBLE";
      case "FloatValue" -> "FLOAT";
      case "BoolValue" -> "BOOL";
      case "StringValue" -> "STRING";
      case "BytesValue" -> "BYTES";
      default -> "INT32";
    };
  }

  private static String cap(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
