package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * v1: emits the Avro schema for the FIRST top-level message of an enabled {@link ProtoFile} as a
 * single {@code .avsc} JSON document (R6). The namespace is the proto package (R7); the root is the
 * first top-level message as an Avro record with its fields mapped to Avro types per the WKT/type
 * table, and the file's enums declared as named Avro {@code enum} types the fields reference by
 * name. Nested records and oneofs are <strong>deferred</strong> to the converter unit (U5): a field
 * that needs them aborts generation with a clear message (fail loud, R11/R17) rather than emit a
 * partial schema.
 *
 * <p>Deliberately a scoped subset — enough to make the schema shape and the {@code .avsc} wiring
 * real and testable — with the fail-loud points in place: {@code uint64}, {@code map} fields, oneof
 * members, and nested messages each abort with a clear message.
 */
public final class AvroSchemaGenerator {

  // WKT fragments — the SAME native-type mapping the WktAvroBridge carries (R8): the schema the
  // generator emits must match the value conversions the runtime bridge performs. Both are pinned
  // together by WktAvroBridgeTest and the schema test; a change to one should update the other.
  private static final String TIMESTAMP = "\"long\"";
  private static final String DURATION =
      "{\"type\": \"record\", \"name\": \"Duration\", "
          + "\"fields\": [{\"name\": \"seconds\", \"type\": \"long\"}, {\"name\": \"nanos\", \"type\": \"int\"}]}";
  private static final String NULL_VALUE =
      "[\"null\", {\"type\": \"enum\", \"name\": \"NullValue\", \"symbols\": [\"NULL_VALUE\", \"TRUE\", \"FALSE\"]}]";

  private static final Map<String, String> WKT_FRAGMENT = fragments();
  private static final Set<String> WKT = fragments().keySet();

  private AvroSchemaGenerator() {}

  /** Build the well-known-type fragment map (the WKT -> native Avro mapping). */
  private static Map<String, String> fragments() {
    var m = new LinkedHashMap<String, String>();
    m.put("google.protobuf.Timestamp", TIMESTAMP);
    m.put("google.protobuf.Duration", DURATION);
    m.put("google.protobuf.StringValue", "[\"null\", \"string\"]");
    m.put("google.protobuf.BoolValue", "[\"null\", \"boolean\"]");
    m.put("google.protobuf.BytesValue", "[\"null\", \"bytes\"]");
    m.put("google.protobuf.Int64Value", "[\"null\", \"long\"]");
    m.put("google.protobuf.DoubleValue", "[\"null\", \"double\"]");
    m.put("google.protobuf.FieldMask", "[\"null\", \"string\"]");
    m.put("google.protobuf.NullValue", NULL_VALUE);
    m.put("google.protobuf.Empty", "{\"type\": \"record\", \"name\": \"Empty\", \"fields\": []}");
    return m;
  }

  /**
   * The {@code .avsc} JSON for the file, or an empty string if it has no top-level message to
   * bridge.
   */
  public static String schema(ProtoFile pf) {
    if (pf.messages().isEmpty()) return "";
    Message root = pf.messages().get(0);
    if (hasOneof(root))
      throw new AvroUnsupportedException(root.name(), "oneof groups are not supported in avro v1");
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    if (pf.protoPackage() != null && !pf.protoPackage().isEmpty()) {
      sb.append("\"namespace\": ").append(jsonStr(pf.protoPackage()));
      sb.append(", "); // separator before the next top-level object field
    }
    sb.append("\"type\": \"record\", \"name\": ").append(jsonStr(cap(root.name())));
    sb.append(", \"fields\": ").append(recordFields(root)).append('}');
    return sb.toString();
  }

  private static boolean hasOneof(Message m) {
    return !m.oneofs().isEmpty();
  }

  /** The Avro {@code "fields"} array for a record: its direct scalar / repeated / WKT fields. */
  private static String recordFields(Message m) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    boolean first = true;
    for (Field f : m.fields()) {
      // Fail loud: oneof members and non-WKT message fields are deferred to the converter unit
      // (the non-WKT MESSAGE case in the switch below aborts on the spot).
      if (f.isMap())
        throw new AvroUnsupportedException(f.name(), "map fields are not supported in avro v1");
      if (!first) sb.append(", ");
      first = false;
      sb.append('{');
      sb.append("\"name\": ").append(jsonStr(f.name()));
      sb.append(", \"type\": ").append(avroType(f));
      sb.append('}');
    }
    sb.append(']');
    return sb.toString();
  }

  /**
   * The Avro type JSON for a single (non-repeated) field: a native scalar, an Avro enum reference
   * (by short name), or the well-known-type fragment (R5). {@code uint64} aborts (R8).
   */
  public static String avroType(Field f) {
    String frag = WKT_FRAGMENT.get(typeUrlIfWkt(f));
    if (frag != null) return frag;
    if (f.isRepeated()) {
      return "{\"type\": \"array\", \"items\": " + elementAvroType(f) + "}";
    }
    return switch (f.kind()) {
      case SCALAR -> scalarAvro(f.typeUrl());
      case ENUM -> jsonStr(lastSegment(f.typeUrl()));
      case MESSAGE ->
          throw new AvroUnsupportedException(
              f.name(), "nested message fields are not supported in avro v1");
      case MAP ->
          throw new AvroUnsupportedException(f.name(), "map fields are not supported in avro v1");
    };
  }

  private static String elementAvroType(Field f) {
    String frag = WKT_FRAGMENT.get(typeUrlIfWkt(f));
    if (frag != null) return frag;
    return switch (f.kind()) {
      case SCALAR -> scalarAvro(f.typeUrl());
      case ENUM -> jsonStr(lastSegment(f.typeUrl()));
      case MESSAGE, MAP ->
          throw new AvroUnsupportedException(
              f.name(), "repeated nested types are not supported in avro v1");
      default -> "\"string\"";
    };
  }

  public static String scalarAvro(String protoScalar) {
    return switch (protoScalar == null ? "" : protoScalar) {
      case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> "\"int\"";
      case "int64", "sint64", "fixed64", "sfixed64" -> "\"long\"";
      case "uint64" ->
          throw new AvroUnsupportedException(
              "uint64", "no exact Avro type (int64 overflows) for uint64");
      case "double" -> "\"double\"";
      case "float" -> "\"float\"";
      case "bool" -> "\"boolean\"";
      case "string" -> "\"string\"";
      case "bytes" -> "\"bytes\"";
      default -> "\"string\"";
    };
  }

  /** The WKT protoFqn a field names, or null if the field is not a well-known type. */
  private static @Nullable String typeUrlIfWkt(Field f) {
    return f.kind() == FieldKind.MESSAGE && WKT.contains(f.typeUrl()) ? f.typeUrl() : null;
  }

  private static String cap(String s) {
    if (s == null || s.isEmpty()) return s;
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static String lastSegment(String fqn) {
    String s = fqn == null ? "" : fqn;
    int dot = s.lastIndexOf('.');
    return dot < 0 ? s : s.substring(dot + 1);
  }

  private static String jsonStr(String s) {
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
}
