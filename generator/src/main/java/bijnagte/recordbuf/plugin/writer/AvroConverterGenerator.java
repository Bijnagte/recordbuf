package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.Message;

/**
 * Emits the generated {@code XAvro} converter for a (bridge-enabled) message: a static {@code
 * fromRecord(X)} that fills the generated {@code XSpecific} — a {@code SpecificRecord} produced by
 * Avro's {@code org.apache.avro.compiler.specific.SpecificCompiler} from the per-file {@code .avsc}
 * (KTD6 delegation, not hand-emitted — see U5) — and a static {@code toRecord(XSpecific)} that
 * rebuilds the record from it (KTD6 / R20-P1). Scalars and repeated scalars copy directly; the
 * well-known-type fields route through the runtime bridge in {@code
 * bijnagte.recordbuf.avro.wkt.WktAvroBridge} (composed, not re-derived). The generated classes live
 * in the record's java package so the record sibling resolves by its short name (no import,
 * matching {@code Interop}).
 *
 * <p>The record's exact component ordering and its {@code UnknownFields} default are resolved by
 * the {@code .rec} layer; this emitter produces a converter whose <em>shape</em> is pinned by a
 * generator test, while its components compile and round-trip at the fixtures level (U6).
 */
public final class AvroConverterGenerator {

  private static final String BRIDGE = "bijnagte.recordbuf.avro.wkt.WktAvroBridge";

  private AvroConverterGenerator() {}

  /**
   * The generated source for {@code <X>Avro}: the per-type record<->Avro converter. The specific
   * record it targets is the one Avro's {@code SpecificCompiler} generates from the per-file {@code
   * .avsc} (option B), so {@code specificClass} is that generated class's fully-qualified name.
   */
  public static String converter(String javaPackage, Message message, String specificClass) {
    var recordClass = message.name();
    var fields = message.fields();
    StringBuilder sb = new StringBuilder();
    if (!javaPackage.isEmpty()) sb.append("package ").append(javaPackage).append(";\n\n");
    sb.append("/** Generated record\u2194-Avro converter for the ")
        .append(recordClass)
        .append(" message. Well-known-type fields route through ")
        .append(BRIDGE)
        .append(" (compose, do not re-derive). */\n");
    sb.append("public final class ").append(recordClass).append("Avro {\n\n");
    sb.append(fromRecord(fields, recordClass, specificClass)).append('\n');
    sb.append(toRecord(fields, recordClass, specificClass)).append('\n');
    sb.append("}\n");
    return sb.toString();
  }

  /** {@code public static XSpecific fromRecord(X r)} - the record -> Avro direction. */
  private static String fromRecord(
      java.util.List<Field> fields, String recordClass, String specific) {
    StringBuilder sb = new StringBuilder();
    sb.append("  public static ")
        .append(specific)
        .append(" fromRecord(")
        .append(recordClass)
        .append(" r) {\n");
    sb.append("    ").append(specific).append(" s = new ").append(specific).append("();\n");
    for (Field f : fields) {
      if (isWkt(f)) {
        sb.append("    s.")
            .append(f.javaName())
            .append(" = ")
            .append(BRIDGE)
            .append(".toAvro(")
            .append(f.javaName())
            .append("); // WKT routed via the bridge\n");
      } else {
        sb.append("    s.")
            .append(f.javaName())
            .append(" = r.")
            .append(f.javaName())
            .append("();\n");
      }
    }
    sb.append("    return s;\n  }\n");
    return sb.toString();
  }

  /** {@code public static X toRecord(XSpecific s)} - the Avro -> record direction. */
  private static String toRecord(
      java.util.List<Field> fields, String recordClass, String specific) {
    StringBuilder sb = new StringBuilder();
    sb.append("  public static ")
        .append(recordClass)
        .append(" toRecord(")
        .append(specific)
        .append(" s) {\n");
    sb.append("    ").append("return new ").append(recordClass).append('(');
    for (int i = 0; i < fields.size(); i++) {
      Field f = fields.get(i);
      if (i > 0) sb.append(", ");
      if (isWkt(f)) {
        sb.append(BRIDGE).append(".fromAvro(").append("s.").append(f.javaName()).append(')');
      } else {
        sb.append("s.").append(f.javaName()).append(')');
      }
    }
    sb.append(')');
    sb.append(";\n  }\n");
    return sb.toString();
  }

  /** A field names a well-known type when it is a message of a {@code google.protobuf.*} type. */
  private static boolean isWkt(Field f) {
    String t = f.typeUrl();
    return f.kind() == FieldKind.MESSAGE && t != null && t.startsWith("google.protobuf.");
  }
}
