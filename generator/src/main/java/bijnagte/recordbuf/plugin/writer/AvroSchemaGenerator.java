package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Avro schema ({@code .avsc}) emitter, refactored (T3) off hand-built JSON strings into a real
 * <em>schema tree</em> built and pretty-printed with Jackson. Previously the {@code .avsc} was
 * assembled by string-concatenating {@link #typeUrlIfWkt}-derived fragment strings; the WKT mapping
 * now lives <em>once, as Jackson {@link JsonNode} trees</em> in {@link #WKT_SCHEMA}, and every
 * field/record type is composed from those nodes. The result is serialized with Jackson's {@link
 * ObjectWriter#withDefaultPrettyPrinter()} so the emitted {@code .avsc} is canonical, pretty JSON,
 * and the schema's WKT mapping is single-sourced (R8) instead of re-expressed in every emitter that
 * hands out a fragment.
 *
 * <p>v1 remains a scoped subset: enough to make the schema shape and the {@code .avsc} wiring real
 * and testable, with the fail-loud points in place ({@code uint64}, {@code map}, oneof groups, and
 * nested/repeated non-WKT messages each abort, R11/R17).
 *
 * <p>Tests parse the emitted {@code .avsc} back with the same {@link ObjectMapper} and assert the
 * tree structurally (no brittle string matching).
 */
public final class AvroSchemaGenerator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The {@code .avsc} pretty printer (R6/T3): canonical, human-readable JSON. */
  private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

  // WKT mapping — the single source (R8) of how every well-known type maps to Avro, held as a
  // Jackson tree per type. The fragment string a type maps to is its serialized node.
  private static final Map<String, JsonNode> WKT_SCHEMA = wktSchema();
  private static final Set<String> WKT = WKT_SCHEMA.keySet();

  private AvroSchemaGenerator() {}

  /**
   * The Avro namespace for a proto file's bridge output. It IS the bare proto package — the {@code
   * .avsc} (a free-form Avro schema STRING, not a record dir) lives in the proto package, and the
   * generated specific record's CLASS NAME (not its package) makes it collision-free: it is
   * suffixed {@code "Avro"} (see {@link #specificRecordName}) so {@code SampleAvro} is distinct
   * from both the recordbuf record ({@code .rec}-suffixed package, routed by recordbuf) and the
   * protobuf-java reference ({@code Sample} in the bare proto package under {@code java/}). An
   * empty proto package yields no namespace (a top-level Avro record).
   */
  public static String avroNamespace(@Nullable String protoPackage) {
    return protoPackage == null ? "" : protoPackage;
  }

  /**
   * The generated specific record's class name: the proto message name, capitalized, with an {@code
   * "Avro"} suffix so it is distinct from the recordbuf record + the protobuf-java reference that
   * share the bare name. {@code Sample -> SampleAvro}.
   */
  public static String specificRecordName(String messageName) {
    return cap(messageName) + "Avro";
  }

  /**
   * The pretty-printed {@code .avsc} JSON for the file, or an empty string if it has no top-level
   * message to bridge. The document is a Jackson tree serialized via {@link
   * com.fasterxml.jackson.databind.ObjectWriter#withDefaultPrettyPrinter()}; tests re-parse it with
   * the same {@link ObjectMapper} and assert the structure rather than string-matching.
   */
  public static String schema(ProtoFile pf) {
    if (pf.messages().isEmpty()) return "";
    Message root = pf.messages().get(0);
    if (hasOneof(root))
      throw new AvroUnsupportedException(root.name(), "oneof groups are not supported in avro v1");
    // The schema tree the bridge emits; serialized pretty, not string-concatenated.
    return pretty(rootRecord(root, avroNamespace(pf.protoPackage())));
  }

  /**
   * The Avro type JSON for a single (non-repeated) field as a Jackson tree: a native scalar, an
   * Avro enum reference (by short name), a repeated-as-array, or a well-known-type fragment. Used
   * by the record-field composition (not on its own in v1, but a clean structural entry point).
   */
  public static JsonNode typeNode(Field f) {
    JsonNode wkt = WKT_SCHEMA.get(typeUrlIfWkt(f));
    if (wkt != null) return wkt;
    if (f.isRepeated()) return arrayType(elementNode(f));
    return switch (f.kind()) {
      case SCALAR -> text(scalarAvro(f.typeUrl()));
      case ENUM -> text(lastSegment(f.typeUrl()));
      case MESSAGE ->
          throw new AvroUnsupportedException(
              f.name(), "nested message fields are not supported in avro v1");
      case MAP ->
          throw new AvroUnsupportedException(f.name(), "map fields are not supported in avro v1");
    };
  }

  /** Compact serialized JSON for a field's type (the {@code .avsc} {@code "type"} value). */
  public static String avroType(Field f) {
    return compact(typeNode(f));
  }

  /**
   * The native Avro scalar type NAME for a proto scalar. {@code uint64} aborts (no exact Avro type
   * — int64 overflows). Returns the bare Avro type name (e.g. {@code "int"}); the field's {@code
   * .avsc} value is its JSON-string form, produced by {@link #typeNode} via {@link #text}.
   */
  public static String scalarAvro(String protoScalar) {
    return switch (protoScalar == null ? "" : protoScalar) {
      case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> "int";
      case "int64", "sint64", "fixed64", "sfixed64" -> "long";
      case "uint64" ->
          throw new AvroUnsupportedException(
              "uint64", "no exact Avro type (int64 overflows) for uint64");
      case "double" -> "double";
      case "float" -> "float";
      case "bool" -> "boolean";
      case "string" -> "string";
      case "bytes" -> "bytes";
      default ->
          throw new AvroUnsupportedException(
              protoScalar == null ? "<null>" : protoScalar,
              "unknown proto scalar type (proto has a closed scalar set); the Avro bridge fails loud (R17) "
                  + "rather than emit a wrong type");
    };
  }

  /**
   * The WKT protoFqn a field names, or null — the single source {@link #WKT_SCHEMA} derives from.
   */
  public static @Nullable String typeUrlIfWkt(Field f) {
    return f.kind() == FieldKind.MESSAGE && WKT.contains(f.typeUrl()) ? f.typeUrl() : null;
  }

  // ---- schema-tree construction (T3) ----

  private static boolean hasOneof(Message m) {
    return !m.oneofs().isEmpty();
  }

  private static ObjectNode rootRecord(Message m, String ns) {
    ObjectNode rec = MAPPER.createObjectNode();
    if (!ns.isEmpty()) rec.put("namespace", ns);
    rec.put("type", "record");
    rec.put("name", specificRecordName(m.name()));
    rec.set("fields", recordFields(m));
    return rec;
  }

  /** The Avro {@code "fields"} array for a record: its direct scalar / repeated / WKT fields. */
  private static ArrayNode recordFields(Message m) {
    ArrayNode fields = MAPPER.createArrayNode();
    for (Field f : m.fields()) {
      // Fail loud: map fields and oneof members are not supported in avro v1 (R11/R17).
      if (f.isMap())
        throw new AvroUnsupportedException(f.name(), "map fields are not supported in avro v1");
      fields.add(fieldNode(f));
    }
    return fields;
  }

  /** One Avro field entry: {name, type} — the type composed via {@link #typeNode}. */
  private static ObjectNode fieldNode(Field f) {
    ObjectNode field = MAPPER.createObjectNode();
    field.put("name", f.name());
    field.set("type", typeNode(f));
    return field;
  }

  private static JsonNode elementNode(Field f) {
    JsonNode wkt = WKT_SCHEMA.get(typeUrlIfWkt(f));
    if (wkt != null) return wkt;
    return switch (f.kind()) {
      case SCALAR -> text(scalarAvro(f.typeUrl()));
      case ENUM -> text(lastSegment(f.typeUrl()));
      case MESSAGE, MAP ->
          throw new AvroUnsupportedException(
              f.name(), "repeated nested types are not supported in avro v1");
      default -> text("string");
    };
  }

  private static ObjectNode arrayType(JsonNode items) {
    ObjectNode array = MAPPER.createObjectNode();
    array.put("type", "array");
    array.set("items", items);
    return array;
  }

  private static JsonNode text(String value) {
    return MAPPER.getNodeFactory().textNode(value);
  }

  /**
   * Pretty-serialize a schema tree to a JSON string. Jackson serialization of our own in-memory
   * tree cannot fail, but {@code writeValueAsString} is checked, so the (unreachable) failure is
   * rethrown as an unchecked error.
   */
  private static String pretty(JsonNode node) {
    return serialize(node, PRETTY);
  }

  private static String compact(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException impossible) {
      // Our own in-memory tree never fails to serialize; the checked exception is unreachable.
      throw new IllegalStateException("failed to serialize schema tree", impossible);
    }
  }

  private static String serialize(JsonNode node, ObjectWriter writer) {
    try {
      return writer.writeValueAsString(node);
    } catch (JsonProcessingException impossible) {
      // Our own in-memory tree never fails to serialize; the checked exception is unreachable.
      throw new IllegalStateException("failed to serialize schema tree", impossible);
    }
  }

  // ---- the WKT → Avro mapping, single-sourced as Jackson trees (R8) ----

  private static Map<String, JsonNode> wktSchema() {
    var m = new LinkedHashMap<String, JsonNode>();
    m.put("google.protobuf.Timestamp", tree("{\"type\": \"long\"}"));
    m.put(
        "google.protobuf.Duration",
        tree(
            "{\"type\": \"record\", \"name\": \"Duration\", "
                + "\"fields\": [{\"name\": \"seconds\", \"type\": \"long\"}, {\"name\": \"nanos\", \"type\": \"int\"}]}"));
    m.put("google.protobuf.StringValue", tree("[\"null\", \"string\"]"));
    m.put("google.protobuf.BoolValue", tree("[\"null\", \"boolean\"]"));
    m.put("google.protobuf.BytesValue", tree("[\"null\", \"bytes\"]"));
    m.put("google.protobuf.Int64Value", tree("[\"null\", \"long\"]"));
    m.put("google.protobuf.DoubleValue", tree("[\"null\", \"double\"]"));
    m.put("google.protobuf.FieldMask", tree("[\"null\", \"string\"]"));
    m.put(
        "google.protobuf.NullValue",
        tree(
            "[\"null\", {\"type\": \"enum\", \"name\": \"NullValue\", "
                + "\"symbols\": [\"NULL_VALUE\", \"TRUE\", \"FALSE\"]}]"));
    m.put(
        "google.protobuf.Empty",
        tree("{\"type\": \"record\", \"name\": \"Empty\", \"fields\": []}"));
    return m;
  }

  /**
   * Parse a small, compile-time fragment literal into a Jackson tree (a WKT mapping value). The
   * literal is part of this source, so a parse failure is a compile-time bug and rethrown as an
   * error; it is never triggered at runtime.
   */
  private static JsonNode tree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException impossible) {
      throw new IllegalStateException("malformed WKT fragment literal: " + json, impossible);
    }
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
}
