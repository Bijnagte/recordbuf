package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.For;
import bijnagte.recordbuf.codegen.source.If;
import bijnagte.recordbuf.codegen.source.Statement;
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

  static String localInit(Field f, Entry entry, TypeCollector ci) {
    String type = ci.use(requireType(entry));
    String name = f.javaName();
    if (f.label() == FieldLabel.REPEATED) {
      ci.use("java.util.List");
      ci.use("java.util.ArrayList");
      return "List<" + type + "> " + name + " = new ArrayList<>();";
    } else if (entry.hasZeroDefault()) {
      return type + " " + name + " = " + entry.zeroDefault() + ";";
    } else {
      // Wrapper WKT (nullable)
      return type + " " + name + " = null;";
    }
  }

  // encode arm: for structured WKT, "if (!v.field().equals(ZERO)) out.writeByteArray(n,
  // Wkt.method(v.field()));"
  //            for wrapper WKT,   "if (v.field() != null) out.writeByteArray(n,
  // Wkt.wrap(Wkt.Wrap.KIND, v.field()));"
  //            repeated form:      "for (var e : v.field()) out.writeByteArray(n, Wkt.method(e));"

  static void emitEncode(
      Field f, Entry entry, String out, String v, TypeCollector ci, List<Statement> body) {
    String wkt = ci.use(WKT_FQN);
    ci.use(requireType(entry));
    int num = f.number();
    String g = v + "." + f.javaName() + "()";
    String m =
        encodeMethod(shortName(f.typeUrl()))
            .replace("parse", "write") // normalize for switch: already normalized in encodeMethod
        ; // no-op
    // For structured WKT: the encode method is Wkt.timestamp/duration/fieldMask/empty;
    // For wrapper:        the encode call is Wkt.wrap(Wkt.Wrap.KIND, value).
    String wktShort = shortName(f.typeUrl());
    String methodName = encodeMethod(f.typeUrl()); // already resolved below
    String outExpr = out + ".writeByteArray(" + num + ", ";

    if (f.label() == FieldLabel.REPEATED) {
      ci.use("java.util.List");
      if (entry.hasZeroDefault()) {
        String def = Objects.requireNonNull(entry.zeroDefault());
        // for (var e : v.field()) { if (!e.equals(def)) out.writeByteArray(n, Wkt.method(e)); }
        body.add(
            For.builder()
                .pattern("var e")
                .iterable(g)
                .body(
                    If.builder()
                        .condition("!e.equals(" + def + ")")
                        .body(Statement.of(outExpr + wkt + "." + methodName + "(e));"))
                        .build())
                .build());
      } else {
        // for (var e : v.field()) out.writeByteArray(n, Wkt.wrap(Wkt.Wrap.KIND, e));
        body.add(
            For.builder()
                .pattern("var e")
                .iterable(g)
                .body(
                    Statement.of(
                        outExpr
                            + wkt
                            + ".wrap("
                            + wkt
                            + ".Wrap."
                            + wrapKind(f.typeUrl())
                            + ", e));"))
                .build());
      }
    } else if (entry.hasZeroDefault()) {
      String def = Objects.requireNonNull(entry.zeroDefault());
      // if (v.field() != null && !def.equals(v.field())) out.writeByteArray(n,
      // Wkt.method(v.field()));
      String call = outExpr + wkt + "." + methodName + "(" + g + "));";
      body.add(
          If.builder()
              .condition(g + " != null && !" + def + ".equals(" + g + ")")
              .body(Statement.of(call))
              .build());
    } else {
      // if (v.field() != null) out.writeByteArray(n, Wkt.wrap(Wkt.Wrap.KIND, v.field()));
      String call =
          outExpr + wkt + ".wrap(" + wkt + ".Wrap." + wrapKind(f.typeUrl()) + ", " + g + "));";
      body.add(If.builder().condition(g + " != null").body(Statement.of(call)).build());
    }
  }

  // decode case: "case N -> { field = Wkt.parseName(in.readByteArray()); }"
  //             "case N -> { field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray()); }"

  static void emitDecodeCase(Field f, Entry entry, String in, String wkt, List<Statement> body) {
    int num = f.number();
    String name = f.javaName();
    String read = in + ".readByteArray()";

    body.add(Statement.of("    case " + num + " -> {"));

    if (f.label() == FieldLabel.REPEATED) {
      if (entry.hasZeroDefault()) {
        // field.add(Wkt.parseName(in.readByteArray()));
        body.add(
            Statement.of(
                "      "
                    + name
                    + ".add("
                    + wkt
                    + "."
                    + parseMethod(f.typeUrl())
                    + "("
                    + read
                    + "));"));
      } else {
        String javaShort = shortNameOf(requireType(entry));
        // field.add((Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray()));
        body.add(
            Statement.of(
                "      "
                    + name
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
                    + "));"));
      }
    } else if (entry.hasZeroDefault()) {
      // field = Wkt.parseName(in.readByteArray());
      body.add(
          Statement.of(
              "      " + name + " = " + wkt + "." + parseMethod(f.typeUrl()) + "(" + read + ");"));
    } else {
      String javaShort = shortNameOf(requireType(entry));
      // field = (Short) Wkt.parse(Wkt.Wrap.KIND, in.readByteArray());
      body.add(
          Statement.of(
              "      "
                  + name
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
                  + ");"));
    }
    body.add(Statement.of("    }"));
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
