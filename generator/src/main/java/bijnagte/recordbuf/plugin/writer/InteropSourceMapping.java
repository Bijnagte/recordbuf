package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.Line;
import bijnagte.recordbuf.codegen.source.Member;
import bijnagte.recordbuf.codegen.source.MemberConstructorBuilder;
import bijnagte.recordbuf.codegen.source.MemberMethodBuilder;
import bijnagte.recordbuf.codegen.source.Modifiers;
import bijnagte.recordbuf.codegen.source.Type;
import bijnagte.recordbuf.codegen.source.TypeClassBuilder;
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
    ci.useFqn(ref);
    String rec = m.name();

    Javadoc javadoc =
        new Javadoc(
            "Structural X <-> R converter between "
                + rec
                + " (record) and "
                + ref
                + " (protobuf-java), generated from "
                + sourceName
                + ". Field-by-field conversion only.");

    var privateCtor =
        MemberConstructorBuilder.builder()
            .mods(Modifiers.of("private"))
            .name("Interop")
            .params("()")
            .build();

    var fromMethod = emitFrom(m, ci, refPkg, ref, rec);
    var toMethod = emitTo(m, ci, refPkg, ref, rec);

    List<Member> members = new ArrayList<>(List.of(privateCtor, fromMethod, toMethod));

    return TypeClassBuilder.builder()
        .parent(rec)
        .mods(Modifiers.of("public", "static", "final"))
        .javadoc(javadoc)
        .name("Interop")
        .members(members)
        .build();
  }

  // -- from(reference) -> record ------------------------------------------------

  private static Member.Method emitFrom(
      Message m, TypeCollector ci, String refPkg, String ref, String rec) {
    List<Line> body = new ArrayList<>();
    for (Field f : m.fields()) {
      fromLocal(body, f, ci);
    }
    for (Oneof o : m.oneofs()) {
      emitOneofFrom(body, o, ci);
    }
    body.add(Line.of("return new " + rec + "("));
    for (Field f : m.fields()) {
      body.add(Line.of("    _" + f.javaName() + ","));
    }
    for (Oneof o : m.oneofs()) {
      body.add(Line.of("    _" + o.name() + ","));
    }
    body.add(Line.of("    " + ci.use(RT + ".UnknownFields") + ".empty()"));
    body.add(Line.of("    );"));

    return MemberMethodBuilder.builder()
        .mods(Modifiers.of("public", "static"))
        .returnType(rec)
        .name("from")
        .params("(" + ref + " x)")
        .body(body)
        .build();
  }

  private static void fromLocal(List<Line> out, Field f, TypeCollector ci) {
    String cap = JavaNames.cap(f.javaName());
    if (f.isMap()) {
      boolean hasBytes = "bytes".equals(f.mapKey()) || "bytes".equals(f.mapValue());
      if (!hasBytes) {
        // Non-byte maps: copy the protobuf-java map directly (compatible key/value types)
        out.add(
            Line.of(
                "var _"
                    + f.javaName()
                    + " = new "
                    + ci.use(HASH_MAP)
                    + "<>(x.get"
                    + cap
                    + "Map());"));
      } else {
        String keyExpr = mapKeyFrom(f);
        String valExpr = mapValueFrom(f, ci);
        out.add(Line.of("var _" + f.javaName() + " = new " + ci.use(HASH_MAP) + "<>();"));
        out.add(
            Line.of(
                "for (var _e : x.get"
                    + cap
                    + "Map().entrySet()) _"
                    + f.javaName()
                    + ".put("
                    + keyExpr
                    + ", "
                    + valExpr
                    + ");"));
      }
      return;
    }
    if (f.isMessage() && f.isRepeated()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          Line.of(
              ci.use(LIST)
                  + "<"
                  + recSib
                  + "> _"
                  + f.javaName()
                  + " = new "
                  + ci.use(ARRAY_LIST)
                  + "<>();"));
      out.add(
          Line.of(
              "for (var _e : x.get"
                  + cap
                  + "List()) _"
                  + f.javaName()
                  + ".add("
                  + recSib
                  + ".Interop.from(_e));"));
      return;
    }
    if (f.isMessage()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          Line.of(
              "var _"
                  + f.javaName()
                  + " = x.has"
                  + cap
                  + "() ? "
                  + recSib
                  + ".Interop.from(x.get"
                  + cap
                  + "()) : null;"));
      return;
    }
    if (f.kind() == FieldKind.ENUM) {
      out.add(Line.of("var _" + f.javaName() + " = " + fromExpr(f) + ";"));
      return;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out.add(Line.of("var _" + f.javaName() + " = new " + ci.use(ARRAY_LIST) + "<>();"));
        out.add(
            Line.of(
                "for (var _e : x.get"
                    + cap
                    + "List()) _"
                    + f.javaName()
                    + ".add(_e.toByteArray());"));
      } else {
        // For non-bytes repeated fields, use the protobuf-java list directly;
        // var infers the correct element type from x.getList().
        out.add(Line.of("var _" + f.javaName() + " = x.get" + cap + "List();"));
      }
      return;
    }
    // Singular scalar / bytes
    if ("bytes".equals(f.typeUrl())) {
      out.add(
          Line.of(
              "var _"
                  + f.javaName()
                  + " = "
                  + ci.use(RT + ".Bytes")
                  + ".of("
                  + fromExpr(f)
                  + ");"));
    } else if (f.isNullable()) {
      out.add(
          Line.of("var _" + f.javaName() + " = x.has" + cap + "() ? x.get" + cap + "() : null;"));
    } else {
      out.add(Line.of("var _" + f.javaName() + " = " + fromExpr(f) + ";"));
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

  private static Member.Method emitTo(
      Message m, TypeCollector ci, String refPkg, String ref, String rec) {
    List<Line> body = new ArrayList<>();
    body.add(Line.of("var _b = " + ref + ".newBuilder();"));
    for (Field f : m.fields()) {
      toStmt(body, f, ci, refPkg);
    }
    for (Oneof o : m.oneofs()) {
      emitOneofTo(body, o, ci, refPkg);
    }
    body.add(Line.of("return _b.build();"));

    return MemberMethodBuilder.builder()
        .mods(Modifiers.of("public", "static"))
        .returnType(ref)
        .name("to")
        .params("(" + rec + " r)")
        .body(body)
        .build();
  }

  private static void toStmt(List<Line> out, Field f, TypeCollector ci, String refPkg) {
    String cap = JavaNames.cap(f.javaName());
    if (f.isMap()) {
      out.add(
          Line.of(
              "for (var _e : r."
                  + f.javaName()
                  + "().entrySet()) _b.put"
                  + cap
                  + "("
                  + mapKeyTo(f, ci)
                  + ", "
                  + mapValueTo(f, ci)
                  + ");"));
      return;
    }
    if (f.isMessage() && f.isRepeated()) {
      String recSib = recordSiblingFqn(f);
      out.add(
          Line.of(
              "for (var _e : r."
                  + f.javaName()
                  + "()) _b.add"
                  + cap
                  + "("
                  + recSib
                  + ".Interop.to(_e));"));
      return;
    }
    if (f.isMessage()) {
      String recSib = recordSiblingFqn(f);
      // Use a local variable so NullAway can track the non-null guard
      String src = "r." + f.javaName() + "()";
      String local = "n" + JavaNames.cap(f.javaName());
      out.add(Line.of("if (" + src + " != null) {"));
      out.add(Line.of("  var " + local + " = " + src + ";"));
      out.add(Line.of("  _b.set" + cap + "(" + recSib + ".Interop.to(" + local + "));"));
      out.add(Line.of("}"));
      return;
    }
    if (f.isRepeated()) {
      if ("bytes".equals(f.typeUrl())) {
        out.add(
            Line.of(
                "for (byte[] _e : r."
                    + f.javaName()
                    + "()) _b.add"
                    + cap
                    + "("
                    + ci.use(BYTE_STRING)
                    + ".copyFrom(_e));"));
      } else {
        out.add(Line.of("_b.addAll" + cap + "(r." + f.javaName() + "());"));
      }
      return;
    }
    out.add(Line.of(toSet(f, "r." + f.javaName() + "()", ci, refPkg)));
  }

  private static String toSet(Field f, String src, TypeCollector ci, String refPkg) {
    String cap = JavaNames.cap(f.javaName());
    if (f.kind() == FieldKind.ENUM) {
      String refEnum =
          refPkg.isEmpty()
              ? JavaNames.cap(lastSegment(f.typeUrl()))
              : refPkg + "." + JavaNames.cap(lastSegment(f.typeUrl()));
      return "_b.set" + cap + "(" + refEnum + ".forNumber(" + src + ".getNumber()));";
    }
    if ("bytes".equals(f.typeUrl())) {
      return "_b.set" + cap + "(" + ci.use(BYTE_STRING) + ".copyFrom(" + src + ".toByteArray()));";
    }
    if (f.isNullable()) {
      return "if (" + src + " != null) _b.set" + cap + "(" + src + ");";
    }
    return "_b.set" + cap + "(" + src + ");";
  }

  // -- oneof --------------------------------------------------------------------

  private static void emitOneofFrom(List<Line> out, Oneof o, TypeCollector ci) {
    String iface = o.ifaceName();
    out.add(
        Line.of("@Nullable " + iface + " _" + o.name() + " = switch (x.get" + iface + "Case()) {"));
    for (Field f : o.members()) {
      String cap = JavaNames.cap(f.name());
      if (f.isMessage()) {
        String recSib = recordSiblingFqn(f);
        // The message member wraps the actual record in a per-member sealed value record.
        out.add(
            Line.of(
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
          Line.of(
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
    out.add(Line.of("  default -> null;"));
    out.add(Line.of("};"));
  }

  private static void emitOneofTo(List<Line> out, Oneof o, TypeCollector ci, String refPkg) {
    String iface = o.ifaceName();
    out.add(Line.of("switch (r." + o.componentName() + "()) {"));
    out.add(Line.of("  case null -> {}"));
    for (Field f : o.members()) {
      String cap = JavaNames.cap(f.name());
      if (f.isMessage()) {
        String recSib = recordSiblingFqn(f);
        out.add(
            Line.of(
                "  case "
                    + iface
                    + "."
                    + cap
                    + "Value v -> _b.set"
                    + cap
                    + "("
                    + recSib
                    + ".Interop.to(v.value()));"));
        continue;
      }
      out.add(
          Line.of(
              "  case " + iface + "." + cap + "Value v -> " + toSet(f, "v.value()", ci, refPkg)));
    }
    out.add(Line.of("}"));
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
    return "bytes".equals(f.mapKey())
        ? ci.use(BYTE_STRING) + ".copyFrom(_e.getKey())"
        : "_e.getKey()";
  }

  private static String mapValueTo(Field f, TypeCollector ci) {
    String t = f.mapValue();
    if (t == null) return "_e.getValue()";
    if ("bytes".equals(t)) return ci.use(BYTE_STRING) + ".copyFrom(_e.getValue())";
    return "_e.getValue()";
  }
}
