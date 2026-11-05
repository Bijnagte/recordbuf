package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.Message;

/**
 * Emits the generated {@code XSpecific} type for a (bridge-enabled) message — a concrete {@code
 * org.apache.avro.specific.SpecificRecordBase} subclass carrying the message's fields as Avro
 * fields and the embedded {@code SCHEMA} constant (R3, R6; KTD4). This is the half of KTD6 the
 * converter round-trips a record <em>through</em>; it is emitted (with the embedded schema) for the
 * proto's first top-level message only, the same scoping the {@link AvroSchemaGenerator} uses for
 * the standalone {@code .avsc}.
 *
 * <p>Field Java types are the <em>boxed</em> Avro types (a {@code SpecificRecord} holds boxed
 * values, so an {@code int32} field is an {@code Integer}, {@code int64} a {@code Long}, and so
 * on). The fields, in declaration order, are the {@code get(int)}/{@code put(int, Object)} index
 * space and the {@code SCHEMA} field order.
 */
public final class AvroSpecificGenerator {

  private AvroSpecificGenerator() {}

  /** A Java string literal for a JSON blob, escaped so it is a single source literal. */
  private static String javaStringLiteral(String s) {
    StringBuilder sb = new StringBuilder();
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\t' -> sb.append("\\t");
        case '\r' -> sb.append("\\r");
        default -> sb.append(c);
      }
    }
    sb.append('"');
    return sb.toString();
  }

  /**
   * The generated source for {@code <X>Specific}.
   *
   * @param javaPackage the java package (the proto's java_package)
   * @param message the top-level message being bridged
   * @return the Java source (a single source file, unformatted — formatted by the build that
   *     compiles it)
   */
  public static String specificClass(String javaPackage, Message message, String schemaJson) {
    var fields = message.fields();
    String typeName = specificName(message);
    StringBuilder sb = new StringBuilder();
    if (!javaPackage.isEmpty()) sb.append("package ").append(javaPackage).append(";\n\n");
    sb.append("import org.apache.avro.Schema;\n");
    sb.append("import org.apache.avro.specific.SpecificRecordBase;\n\n");
    sb.append("/** Generated Avro SpecificRecord for the ")
        .append(message.name())
        .append(" message. */\n");
    sb.append("public final class ").append(typeName).append(" extends SpecificRecordBase {\n\n");
    // The embedded SCHEMA is the same schema the standalone .avsc holds, parsed on demand (R3)
    // - no hand-built Schema.Builder to keep escaping out of the generated source.
    sb.append("  public static final Schema SCHEMA = Schema.parse(");
    sb.append(javaStringLiteral(schemaJson));
    sb.append(");\n\n");

    for (Field f : fields) {
      sb.append("  private ")
          .append(boxedAvroJavaType(f))
          .append(' ')
          .append(f.javaName())
          .append(";\n");
    }
    sb.append('\n');
    sb.append("  public ").append(typeName).append("() {}\n\n");
    sb.append("  @Override public Schema getSchema() { return SCHEMA; }\n\n");

    sb.append("  @Override public Object get(int index) {\n");
    sb.append("    switch (index) {\n");
    for (int i = 0; i < fields.size(); i++) {
      sb.append("      case ")
          .append(i)
          .append(": return ")
          .append(fields.get(i).javaName())
          .append(";\n");
    }
    sb.append("      default:\n");
    sb.append(
        "        throw new org.apache.avro.AvroRuntimeException(\"index out of range: \" + index);\n");
    sb.append("    }\n");
    sb.append("  }\n\n");

    sb.append("  @Override public void put(int index, Object value) {\n");
    sb.append("    switch (index) {\n");
    for (int i = 0; i < fields.size(); i++) {
      var t = boxedAvroJavaType(fields.get(i));
      sb.append("      case ")
          .append(i)
          .append(": this.")
          .append(fields.get(i).javaName())
          .append(" = (")
          .append(t)
          .append(") value;\n");
    }
    sb.append("      default:\n");
    sb.append(
        "        throw new org.apache.avro.AvroRuntimeException(\"index out of range: \" + index);\n");
    sb.append("    }\n");
    sb.append("  }\n");
    sb.append("}\n");
    return sb.toString();
  }

  /** The generated specific-record class name for a message. */
  public static String specificName(Message m) {
    return m.name() + "Specific";
  }

  /**
   * The boxed Java field type a message field maps to in the generated {@code SpecificRecord}:
   * scalars to their Java wrappers, bytes to {@code byte[]}. Enums and nested messages are {@code
   * Object} here - their precise wiring (the record's enum type / a nested {@code SpecificRecord})
   * is a U6 concern.
   */
  private static String boxedAvroJavaType(Field f) {
    if (f.isRepeated()) return "java.util.List<" + elementBoxedType(f) + ">";
    return elementBoxedType(f);
  }

  private static String elementBoxedType(Field f) {
    return switch (f.kind()) {
      case SCALAR ->
          switch (f.typeUrl() == null ? "" : f.typeUrl()) {
            case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> "java.lang.Integer";
            case "int64", "sint64", "fixed64", "sfixed64" -> "java.lang.Long";
            case "uint64" ->
                "java.lang.String"; // fail-loud upstream; not reached for enabled files
            case "double" -> "java.lang.Double";
            case "float" -> "java.lang.Float";
            case "bool" -> "java.lang.Boolean";
            case "string" -> "java.lang.String";
            case "bytes" -> "byte[]";
            default -> "java.lang.Object";
          };
      case ENUM, MESSAGE, MAP -> "java.lang.Object";
    };
  }
}
