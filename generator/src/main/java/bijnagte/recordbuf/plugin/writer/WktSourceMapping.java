package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Element;
import bijnagte.recordbuf.codegen.source.For;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.LocalVar;
import bijnagte.recordbuf.codegen.source.Switch;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import bijnagte.recordbuf.plugin.types.WellKnownTypes.Entry;
import java.util.List;
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

  private static final String WKT_FQN = "bijnagte.recordbuf.runtime.wkt.Wkt";

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

  static Element localInitElement(Field f, Entry entry, TypeCollector ci) {
    TypeRef type = TypeRef.of(requireType(entry));
    String name = f.javaName();
    if (f.label() == FieldLabel.REPEATED) {
      TypeRef namedList =
          TypeRef.named("java.util", "List", List.of(TypeRef.of(requireType(entry))));
      return LocalVar.builder(namedList, name)
          .build("new @0<>()", TypeRef.of("java.util.ArrayList"));
    }
    if (entry.hasZeroDefault()) {
      return LocalVar.builder(type, name).build(entry.zeroDefault());
    }
    // Wrapper WKT (nullable)
    return LocalVar.builder(type, name).build("null");
  }

  // encode arm: for structured WKT, "if (!v.field().equals(ZERO)) out.writeByteArray(n,
  // Wkt.method(v.field()));"
  //            for wrapper WKT,   "if (v.field() != null) out.writeByteArray(n,
  // Wkt.wrap(Wkt.Wrap.KIND, v.field()));"
  //            repeated form:      "for (var e : v.field()) out.writeByteArray(n, Wkt.method(e));"

  static void emitEncode(
      Field f, Entry entry, String out, String v, TypeCollector ci, List<Element> body) {
    TypeRef wktRef = TypeRef.of(WKT_FQN);
    wktRef.collect(ci);
    TypeRef.of(requireType(entry)).collect(ci);
    int num = f.number();
    String g = v + "." + f.javaName() + "()";
    String methodName = encodeMethod(f.typeUrl());
    String outExpr = out + ".writeByteArray(" + num + ", ";

    if (f.label() == FieldLabel.REPEATED) {
      TypeRef.of("java.util.List").collect(ci);
      if (entry.hasZeroDefault()) {
        String def = Objects.requireNonNull(entry.zeroDefault());
        // for (var e : v.field()) { if (!e.equals(def)) out.writeByteArray(n, Wkt.method(e)); }
        body.add(
            For.enhanced("var e", g)
                .body(
                    If.condition("!e.equals(@0)", def)
                        .body(Element.expr(outExpr + "@0." + methodName + "(e));", wktRef))
                        .build())
                .build());
      } else {
        // for (var e : v.field()) out.writeByteArray(n, Wkt.wrap(Wkt.Wrap.KIND, e));
        body.add(
            For.enhanced("var e", g)
                .body(
                    Element.expr(
                        outExpr + "@0.wrap(@0.Wrap." + wrapKind(f.typeUrl()) + ", e));", wktRef))
                .build());
      }
    } else if (entry.hasZeroDefault()) {
      String def = Objects.requireNonNull(entry.zeroDefault());
      // if (v.field() != null && !def.equals(v.field())) out.writeByteArray(n,
      // Wkt.method(v.field()));
      String call = outExpr + "@0." + methodName + "(" + g + "))";
      body.add(
          If.condition("@0 != null && !@1.equals(@0)", g, def)
              .body(Element.expr(call, wktRef))
              .build());
    } else {
      // if (v.field() != null) out.writeByteArray(n, Wkt.wrap(Wkt.Wrap.KIND, v.field()));
      String call = outExpr + "@0.wrap(@0.Wrap." + wrapKind(f.typeUrl()) + ", " + g + "));";
      body.add(If.condition("@0 != null", g).body(Element.expr(call, wktRef)).build());
    }
  }

  // decode case: "case N -> { field = Wkt.parseName(in.readByteArray()); }"
  //             "case N -> { field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray()); }"

  // decode case: "case N -> field = Wkt.parseName(in.readByteArray());"
  //             "case N -> field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray());"
  static void emitDecodeCase(Field f, Entry entry, String in, String wkt, Switch.Builder sw) {
    int num = f.number();
    String name = f.javaName();
    String read = in + ".readByteArray()";

    String assignment;
    if (f.label() == FieldLabel.REPEATED) {
      if (entry.hasZeroDefault()) {
        // field.add(Wkt.parseName(in.readByteArray()));
        assignment = name + ".add(" + wkt + "." + parseMethod(f.typeUrl()) + "(" + read + "));";
      } else {
        String javaShort = shortNameOf(requireType(entry));
        // field.add((Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray()));
        assignment =
            name
                + ".add(("
                + javaShort
                + ") "
                + wkt
                + ".parse("
                + wkt
                + ".Wrap."
                + wrapKind(f.typeUrl())
                + ", "
                + read
                + "));";
      }
    } else if (entry.hasZeroDefault()) {
      // field = Wkt.parseName(in.readByteArray());
      assignment = name + " = " + wkt + "." + parseMethod(f.typeUrl()) + "(" + read + ");";
    } else {
      String javaShort = shortNameOf(requireType(entry));
      // field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray());
      assignment =
          name
              + " = ("
              + javaShort
              + ") "
              + wkt
              + ".parse("
              + wkt
              + ".Wrap."
              + wrapKind(f.typeUrl())
              + ", "
              + read
              + ");";
    }
    sw.caseArm(String.valueOf(num), Element.of(assignment));
  }

  private static String requireType(Entry entry) {
    return Objects.requireNonNull(entry.javaType(), "WKT javaType must resolve");
  }

  private static String shortNameOf(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
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
