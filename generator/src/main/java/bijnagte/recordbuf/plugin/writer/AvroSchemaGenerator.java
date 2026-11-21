package bijnagte.recordbuf.plugin.writer;

import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Avro schema ({@code .avsc}) emitter, refactored (T3) off hand-built JSON strings into a real
 * <em>schema tree</em> built and pretty-printed with Jackson. Previously the {@code .avsc} was
 * assembled by string-concatenating {@link #typeUrlIfWkt}-derived fragment strings; the WKT mapping
 * now lives <em>once, as Jackson {@link JsonNode} trees</em> in {@link #WKT_SCHEMA}, and every
 * field/record type is composed from those nodes. The result is serialized with Jackson's {@link
 * ObjectMapper} (pretty writer) rather than hand-formatted strings.
 *
 * <p><b>Document shape (structural twin).</b> The {@code .avsc} document is the <em>root</em>
 * record plus the whole reachable type graph: a {@code protocol} key naming the declaring {@code
 * .proto} file, the document's {@code namespace} (the root file's proto package), a {@code types}
 * array holding every non-root reachable record (plus the map pair-entry records and the shared
 * unknown-field entry record), and the root record itself as the document's {@code message}. The
 * whole file compiles into one Avro package: {@code specificRecordFqn} of the root (and every
 * reachable record, via its registry namespace).
 *
 * <p><b>Field mapping.</b> A non-repeated message field references its reachable record by Avro
 * name; a repeated field wraps its element; a map is a JSON object for a string key, or an array of
 * named key/value pair records otherwise; a oneof is a {@code null}-first union of its members.
 * Every bridged record ends with the recordbuf unknown-field carrier as a trailing {@code
 * unknownFields} array field — the structural twin of the proto unknown-field carrier, so
 * unrecognized fields survive the {@code .avsc} round trip.
 *
 * <p><b>WKTs.</b> A WKT reference maps to its fixed Avro fragment (<em>not</em> the generated
 * message type — see the {@code .avsc} decision rationale in this family's plan):
 *
 * <table border="1">
 *   <caption>proto well-known type → Avro (the v1 table, preserved per R7)</caption>
 *   <tr><th>proto</th><th>Avro</th></tr>
 *   <tr><td>google.protobuf.Timestamp</td><td>long</td></tr>
 *   <tr><td>google.protobuf.Duration</td><td>{type:record,name:Duration, fields: [seconds:long,
 *       nanos:int]}</td></tr>
 *   <tr><td>google.protobuf.StringValue</td><td>[null, string]</td></tr>
 *   <tr><td>google.protobuf.BoolValue</td><td>[null, boolean]</td></tr>
 *   <tr><td>google.protobuf.BytesValue</td><td>[null, bytes]</td></tr>
 *   <tr><td>google.protobuf.Int64Value</td><td>[null, long]</td></tr>
 *   <tr><td>google.protobuf.DoubleValue</td><td>[null, double]</td></tr>
 *   <tr><td>google.protobuf.FieldMask</td><td>[null, string]</td></tr>
 *   <tr><td>google.protobuf.NullValue</td><td>[null, the NullValue closed enum]</td></tr>
 *   <tr><td>google.protobuf.Empty</td><td>{type:record,name:Empty, fields:[]}</td></tr>
 * </table>
 *
 * <p>WKTs are inlined wherever referenced (their nodes appear in the document); a well-known type
 * with no static schema (a <em>dynamic</em> WKT such as {@code Struct} or {@code Any}) is
 * <em>not</em> in the table and is therefore a message reference the registry must resolve — and it
 * resolves nowhere, so it fails loud: the registry rejects the bridge rather than emit a schema
 * that silently drops data.
 *
 * <p><b>Enum mapping.</b> An enum maps to {@code string}, the named constant's <em>name</em> — the
 * open-ended choice for undefined values: an unknown wire number survives as a (possibly
 * unrecognized) string instead of coercing to a constant.
 *
 * <p><b>Unknown reference fail-loud.</b> A message reference that is neither a WKT nor a reachable
 * record fails loud ({@link AvroUnsupportedException}): the bridge never emits a dangling schema
 * reference.
 *
 * <p><b>uint64 bit-exactness.</b> A {@code uint64} scalar maps to Avro {@code long} (8-byte two's
 * complement): values &ge; 2^63 round-trip as their negative two's-complement reading, then back to
 * the original value on decode — bit-exact, with the negative display documented as the accepted
 * tradeoff (the Avro schema has no unsigned 64-bit type).
 *
 * <p>The emitted tree is the {@code .avsc} contract: the same tree (or a parse of it) drives the
 * converter and the specific-record list, so the schema is the single source of truth for the
 * bridge.
 */
public final class AvroSchemaGenerator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The {@code .avsc} pretty printer (R6/T3): canonical, human-readable JSON. */
  private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

  // WKT mapping — the single source (R8) of how every well-known type maps to Avro, held as a
  // Jackson tree per type. The fragment string a type maps to is its serialized node.
  private static final Map<String, JsonNode> WKT_SCHEMA = wktSchema();
  private static final Set<String> WKT = WKT_SCHEMA.keySet();

  /**
   * The WKT FQNs with a static Avro fragment — the reference set the bridge accepts (everything
   * else is either a user type or a dynamic WKT, and fails loud).
   */
  static Set<String> wktFqns() {
    return WKT;
  }

  private AvroSchemaGenerator() {}

  /**
   * The proto package → Avro namespace mapping: the proto package is the Avro namespace, verbatim
   * (a proto package is an already-dotted Avro namespace; the "javaPackage + .avro" rule applies
   * only to the specific-record Java package — see {@link #specificRecordFqn}).
   */
  public static String avroNamespace(@Nullable String protoPackage) {
    if (protoPackage == null || protoPackage.isBlank()) return "";
    return protoPackage;
  }

  /**
   * The specific-record Java package: the (javaPackage-suffixed) proto package's parent — i.e. the
   * Java package of the record — with {@code .avro} appended.
   */
  public static String specificRecordFqn(String recordFqn) {
    if (recordFqn == null || recordFqn.isBlank()) return "avro";
    int dot = recordFqn.lastIndexOf('.');
    String pkg = dot < 0 ? "" : recordFqn.substring(0, dot);
    String name = dot < 0 ? recordFqn : recordFqn.substring(dot + 1);
    return (pkg.isEmpty() ? "" : pkg + ".") + "avro." + name;
  }

  /**
   * The specific record's Java class name: the proto message's name with a first-uppercase segment
   * and an {@code Avro} suffix (distinctness is enforced by construction — see the plan's
   * distinctness contract).
   */
  public static String specificRecordName(String messageName) {
    if (messageName == null || messageName.isEmpty()) return "Avro";
    String[] parts = messageName.split("_", -1); // explicit: the loop skips empty segments
    StringBuilder b = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty()) {
        b.append(Character.toUpperCase(part.charAt(0)));
        if (part.length() > 1) b.append(part.substring(1));
      }
    }
    String base = b.length() == 0 ? "X" : b.toString();
    return base + "Avro";
  }

  /**
   * Generate the whole {@code .avsc} document for a bridged file's root message: the root record
   * (the document's {@code message}) plus the reachable type graph as {@code types}.
   *
   * <p><b>Null/empty files.</b> An unparseable, or type-less, file has no bridgeable root: an empty
   * document is <em>skipped</em> (the bridge emits nothing, no diagnostic).
   *
   * <p><b>Unsupported types fail loud.</b> A field whose type cannot be mapped ({@link #typeNode})
   * aborts the whole generation with {@link AvroUnsupportedException} — the bridge emits a
   * diagnostic and no file, never a half-generated {@code .avsc} with a silent gap.
   *
   * @param file the declaring file
   * @param root the bridged root message (a top-level message of {@code file})
   * @param table the request's type-resolution table, or null for single-file input
   * @return the pretty-printed {@code .avsc} document, or {@code ""} when there is no bridgeable
   *     root
   * @throws AvroUnsupportedException when a field type has no static Avro mapping
   */
  public static String schema(ProtoFile file, Message root, @Nullable FileTable table) {
    if (file == null || root == null || file.messages().isEmpty()) {
      return "";
    }
    AvroRegistry registry = AvroRegistry.build(file, root, table);
    return pretty(document(file, root, registry));
  }

  /** Convenience for single-file input (no type-resolution table). */
  public static String schema(ProtoFile file, Message root) {
    return schema(file, root, null);
  }

  // ---- the .avsc document ----

  /**
   * The {@code .avsc} document tree: a {@code protocol} key naming the declaring {@code .proto},
   * the document's namespace, the non-root reachable records (and pair/entry records) as {@code
   * types}, and the root record as the document's {@code message}.
   */
  public static ObjectNode document(ProtoFile file, Message root, AvroRegistry registry) {
    ObjectNode doc = MAPPER.createObjectNode();
    doc.put("protocol", file.fileName());
    if (!registry.documentNamespace().isEmpty()) {
      doc.put("namespace", registry.documentNamespace());
    }

    String rootFqn = registry.fqnOf(root);
    ArrayNode types = MAPPER.createArrayNode();
    Set<String> addedNames = new LinkedHashSet<>();
    for (String fqn : registry.protoFqns()) {
      if (fqn.equals(rootFqn)) continue;
      Message m = registry.messageOf(fqn);
      if (m != null) {
        types.add(recordNode(m, registry));
        String name = registry.nameOf(m);
        if (name != null) addedNames.add(name);
      }
    }
    // Map pair-entry records (non-string keys): named after the owning field, defined once each.
    for (String fqn : registry.protoFqns()) {
      Message m = registry.messageOf(fqn);
      if (m == null) continue;
      for (Field f : m.fields()) {
        if (f.isMap() && f.mapKey() != null && !"string".equals(f.mapKey())) {
          String name = mapEntryRecordName(f);
          if (!addedNames.add(name)) {
            throw new AvroUnsupportedException(
                name,
                "two map pair-entry records share a name in the same Avro namespace; the Avro "
                    + "bridge cannot disambiguate");
          }
          JsonNode pair = pairNode(f, name, registry);
          // A named record with no namespace of its own compiles into the DEFAULT package;
          // inherit the document namespace so the specific class lands with the rest.
          if (pair.path("namespace").asString("").isEmpty()) {
            ((ObjectNode) pair).put("namespace", registry.documentNamespace());
          }
          types.add(pair);
        }
      }
    }
    // The WKT named types referenced by the reachable graph (Duration/Empty/NullValue), as
    // document types so their specific classes resolve for the consumer.
    for (ObjectNode wkt : wktNamedTypes(registry)) {
      String wktName = wkt.path("name").asString();
      if (!addedNames.add(wktName)) {
        throw new AvroUnsupportedException(
            wktName,
            "a reachable name occupies a WKT named type's name; the Avro bridge cannot "
                + "disambiguate");
      }
      types.add(wkt);
    }
    // The shared oneof-value wrapper record, emitted when any reachable oneof carries a
    // scalar/enum member (KTD5); a name collision with a reachable record fails loud.
    if (oneofNeedsValueWrapper(registry)) {
      String oneofName = AvroRegistry.ONEOF_VALUE_ENTRY;
      if (!addedNames.add(oneofName)) {
        throw new AvroUnsupportedException(
            oneofName,
            "a reachable name occupies the shared oneof-value wrapper's name; the Avro bridge "
                + "cannot disambiguate");
      }
      ObjectNode oneofRec = oneofValueRecord();
      if (oneofRec.path("namespace").asString("").isEmpty()) {
        oneofRec.put("namespace", registry.documentNamespace());
      }
      types.add(oneofRec);
    }
    // The shared unknown-field entry record every bridged record references.
    if (!addedNames.add(registry.entryRecordName())) {
      throw new AvroUnsupportedException(
          registry.entryRecordName(),
          "a reachable name occupies the unknown-field entry record's name; the Avro bridge "
              + "cannot disambiguate");
    }
    ObjectNode entry = unknownFieldsEntryRecord(registry);
    if (entry.path("namespace").asString("").isEmpty()) {
      entry.put("namespace", registry.documentNamespace());
    }
    types.add(entry);
    List<ObjectNode> defs = new ArrayList<>();
    for (JsonNode t : types) {
      defs.add((ObjectNode) t);
    }
    ArrayNode ordered = doc.putArray("types");
    for (ObjectNode d : dependencyOrder(defs)) {
      ordered.add(d);
    }

    doc.set("message", recordNode(root, registry));
    return doc;
  }

  /** Whether any reachable oneof group carries a scalar or enum member (those ride the wrapper). */
  private static boolean oneofNeedsValueWrapper(AvroRegistry registry) {
    for (String fqn : registry.protoFqns()) {
      Message m = registry.messageOf(fqn);
      if (m == null) continue;
      for (Oneof oneof : m.oneofs()) {
        for (Field member : oneof.members()) {
          if (member.kind() != FieldKind.MESSAGE || typeUrlIfWkt(member) != null) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * The shared oneof-value wrapper record: {@code tag} is the active member's proto field number
   * (the same discriminant the record-side oneof carries) and {@code value} its native value.
   */
  static ObjectNode oneofValueRecord() {
    ObjectNode rec = MAPPER.createObjectNode();
    rec.put("type", "record");
    rec.put("name", AvroRegistry.ONEOF_VALUE_ENTRY);
    ArrayNode fields = MAPPER.createArrayNode();
    fields.add(fieldNodeRaw("tag", text("int")));
    ObjectNode value = MAPPER.createObjectNode();
    value.put("name", "value");
    ArrayNode union = MAPPER.createArrayNode();
    union.add(text("null"));
    union.add(text("string"));
    union.add(text("int"));
    union.add(text("long"));
    union.add(text("double"));
    union.add(text("float"));
    union.add(text("boolean"));
    union.add(text("bytes"));
    value.set("type", union);
    fields.add(value);
    rec.set("fields", fields);
    return rec;
  }

  // ---- records ----

  /**
   * One reachable record node. The record always carries an explicit {@code namespace}: the bridge
   * parses the document node-by-node, so every record must be self-resolving (a namespace-less
   * record would resolve its name references against the wrong namespace); redundancy with the
   * document-level namespace is harmless.
   */
  static ObjectNode recordNode(Message m, AvroRegistry registry) {
    String name = registry.nameOf(m);
    if (name == null) {
      throw new AvroUnsupportedException(
          m.name(), "message is not part of the bridged graph; the Avro bridge fails loud");
    }
    ObjectNode rec = MAPPER.createObjectNode();
    rec.put("type", "record");
    rec.put("name", name);
    rec.put("namespace", registry.namespaceOfMessage(m));
    rec.set("fields", recordFields(m, registry));
    return rec;
  }

  /**
   * A record's fields, in order: normal fields, then oneofs, then the trailing {@code
   * unknownFields}.
   */
  static ArrayNode recordFields(Message m, AvroRegistry registry) {
    ArrayNode out = MAPPER.createArrayNode();
    for (Field f : m.fields()) {
      out.add(fieldNode(f, registry));
    }
    for (Oneof oneof : m.oneofs()) {
      out.add(oneofNode(oneof, registry));
    }
    out.add(unknownFieldsField(registry));
    return out;
  }

  static ObjectNode fieldNode(Field f, AvroRegistry registry) {
    ObjectNode field = MAPPER.createObjectNode();
    field.put("name", f.name());
    field.set("type", typeNode(f, registry));
    return field;
  }

  /**
   * A oneof field: a {@code null}-first union of its members. Members whose element mappings
   * coincide are emitted once (a null-first union with a duplicate branch would not parse).
   */
  static ObjectNode oneofNode(Oneof oneof, AvroRegistry registry) {
    ObjectNode field = MAPPER.createObjectNode();
    field.put("name", oneof.name());
    ArrayNode union = MAPPER.createArrayNode();
    Set<String> branches = new LinkedHashSet<>();
    union.add(text("null"));
    branches.add("null");
    // KTD5: scalar/enum members ride the shared oneof-value wrapper {tag, value} (a union of raw
    // branches would silently collide — string scalar vs enum-to-string — and could not carry the
    // case identity, KTD2); message members ride their own specific records.
    for (Field member : oneof.members()) {
      JsonNode branch;
      if (member.kind() == FieldKind.MESSAGE && typeUrlIfWkt(member) == null) {
        String refName = registry.nameOf(member.typeUrl());
        if (refName == null) {
          throw new AvroUnsupportedException(
              member.typeUrl(), "oneof message member not in the reachable graph");
        }
        branch = text(refName);
      } else {
        branch = text(AvroRegistry.ONEOF_VALUE_ENTRY);
      }
      if (branches.add(branch.toString())) {
        union.add(branch);
      }
    }
    field.set("type", union);
    return field;
  }

  /**
   * The trailing unknown-field carrier field of every bridged record — the structural twin of the
   * proto unknown-field carrier, so unrecognized fields survive the {@code .avsc} round trip.
   */
  static ObjectNode unknownFieldsField(AvroRegistry registry) {
    ObjectNode field = MAPPER.createObjectNode();
    field.put("name", "unknownFields");
    ObjectNode array = MAPPER.createObjectNode();
    array.put("type", "array");
    array.set("items", text(registry.entryRecordName()));
    // Default to an empty array so Avro's generated builder does not throw
    // AvroMissingFieldException
    // when the field is left unset (a record with no unknown fields is the common case).
    field.putArray("default");
    field.set("type", array);
    return field;
  }

  /**
   * The shared unknown-field entry record: the structural twin of the recordbuf carrier's ({@code
   * number, wireType, value}) tuple — {@code wireType} is the runtime {@code WireType} enum's
   * <em>name</em> (a string, kept open for a new wire type), and {@code value} is a {@code
   * null}/long/bytes union, the single carrier for every field kind (ints as long,
   * floats/bool/strings/bytes, and the message/enum/group encodings).
   */
  static ObjectNode unknownFieldsEntryRecord(AvroRegistry registry) {
    ObjectNode rec = MAPPER.createObjectNode();
    rec.put("type", "record");
    rec.put("name", registry.entryRecordName());
    String ns = registry.documentNamespace();
    if (!ns.isEmpty()) {
      // The bridge parses the document node-by-node; a namespace-less entry record would register
      // in the wrong namespace and the records' short-name references would dangle.
      rec.put("namespace", ns);
    }
    ArrayNode fields = MAPPER.createArrayNode();
    fields.add(fieldNodeRaw("number", text("int")));
    fields.add(fieldNodeRaw("wireType", text("string")));
    ObjectNode value = MAPPER.createObjectNode();
    value.put("name", "value");
    ArrayNode union = MAPPER.createArrayNode();
    union.add(text("null"));
    union.add(text("long"));
    union.add(text("bytes"));
    value.set("type", union);
    fields.add(value);
    rec.set("fields", fields);
    return rec;
  }

  static ObjectNode fieldNodeRaw(String name, JsonNode type) {
    ObjectNode field = MAPPER.createObjectNode();
    field.put("name", name);
    field.set("type", type);
    return field;
  }

  // ---- field type mapping ----

  /**
   * The Avro type node for a single field (the whole file compiles into one Avro package, so a
   * non-repeated message field references its reachable record by Avro name). An Avro-mapped WKT is
   * inlined as its fixed fragment.
   *
   * @throws AvroUnsupportedException when the field type has no static Avro mapping
   */
  public static JsonNode typeNode(Field f, AvroRegistry registry) {
    if (f.isRepeated()) {
      return arrayType(elementNode(f, registry));
    }
    String wktFqn = typeUrlIfWkt(f);
    JsonNode wkt = wktFqn == null ? null : WKT_SCHEMA.get(wktFqn);
    if (wkt != null) return wkt;
    return switch (f.kind()) {
      case SCALAR -> text(scalarAvro(f.typeUrl()));
      case ENUM -> text("string");
      case MESSAGE -> namedMessageNode(f, registry);
      case MAP -> mapTypeNode(f, registry);
    };
  }

  /**
   * The field's {@code .avsc} fragment, as a compact JSON string — the mapping layer the converter
   * and the specific-record list consume.
   *
   * @throws AvroUnsupportedException when the field type has no static Avro mapping
   */
  public static String avroType(Field f, AvroRegistry registry) {
    return compact(typeNode(f, registry));
  }

  /** A repeated field: an {@code array} of its element type. */
  static ObjectNode arrayType(JsonNode items) {
    ObjectNode array = MAPPER.createObjectNode();
    array.put("type", "array");
    array.set("items", items);
    return array;
  }

  /**
   * The type of one element of a repeated field (singular: WKT fragment, scalar, string, or a
   * reachable record name).
   */
  static JsonNode elementNode(Field f, AvroRegistry registry) {
    String wktFqn = typeUrlIfWkt(f);
    JsonNode wkt = wktFqn == null ? null : WKT_SCHEMA.get(wktFqn);
    if (wkt != null) return wkt;
    return switch (f.kind()) {
      case SCALAR -> text(scalarAvro(f.typeUrl()));
      case ENUM -> text("string");
      case MESSAGE -> namedMessageNode(f, registry);
      case MAP ->
          throw new AvroUnsupportedException(
              f.name(), "a map field cannot be an element of a repeated field in Avro");
    };
  }

  /**
   * A non-repeated message field: a reference to its reachable record by Avro name.
   *
   * @throws AvroUnsupportedException when the reference cannot be resolved to a bridged record
   */
  static JsonNode namedMessageNode(Field f, AvroRegistry registry) {
    if (registry == null) {
      throw new AvroUnsupportedException(
          f.name(),
          "a message-typed field requires the bridged registry to resolve its record reference");
    }
    String ref = normalizeRef(f.typeUrl());
    JsonNode wkt = WKT_SCHEMA.get(ref);
    if (wkt != null) return wkt;
    String name = registry.nameOf(ref);
    if (name == null) {
      throw new AvroUnsupportedException(
          ref,
          "message reference is not part of the bridged graph; the Avro bridge fails loud rather"
              + " than dangle");
    }
    return text(name);
  }

  /**
   * A map field: a JSON object for a string key; for a non-string scalar key, an array of a named
   * key/value <em>pair record</em> (the Avro idiom for non-string-keyed maps). The value is a
   * message when the map's value is one (its reachable record), a WKT fragment, a string for an
   * enum, or a scalar.
   *
   * @throws AvroUnsupportedException when the map's value type cannot be mapped
   */
  static JsonNode mapTypeNode(Field f, AvroRegistry registry) {
    String key = f.mapKey();
    String value = f.mapValue();
    if (key == null || value == null) {
      throw new AvroUnsupportedException(
          f.name(), "a map field without a declared key/value cannot be mapped to Avro");
    }
    if ("string".equals(key)) {
      ObjectNode map = MAPPER.createObjectNode();
      map.put("type", "map");
      map.set("values", mapValueNode(value, registry));
      return map;
    }
    ObjectNode array = MAPPER.createObjectNode();
    array.put("type", "array");
    array.set("items", text(mapEntryRecordName(f)));
    return array;
  }

  /**
   * The pair-entry record name for a non-string-keyed map: the owning field's name, capitalized,
   * plus {@code Entry} and the {@code Avro} suffix (see {@link AvroRegistry#mapEntryRecordName}).
   */
  static String mapEntryRecordName(Field f) {
    return AvroRegistry.mapEntryRecordName(f);
  }

  /**
   * One key/value pair record node for a non-string-keyed map.
   *
   * @throws AvroUnsupportedException when the key or value type cannot be mapped
   */
  static ObjectNode pairNode(Field f, String entryName, AvroRegistry registry) {
    ObjectNode rec = MAPPER.createObjectNode();
    rec.put("type", "record");
    rec.put("name", entryName);
    ArrayNode fields = MAPPER.createArrayNode();
    String key = f.mapKey();
    String value = f.mapValue();
    if (key == null || value == null) {
      throw new AvroUnsupportedException(
          f.name(), "a map field is missing its key or value type; the Avro bridge fails loud");
    }
    fields.add(fieldNodeRaw("key", text(scalarAvro(key))));
    fields.add(fieldNodeRaw("value", mapValueNode(value, registry)));
    rec.set("fields", fields);
    return rec;
  }

  /**
   * The value side of a map: the map's value token is either a scalar name or a resolved proto FQN
   * (a message/enum). WKTs are inlined, scalars mapped, enums to string, and messages resolved to
   * their reachable record name.
   *
   * @throws AvroUnsupportedException when the value token cannot be mapped
   */
  static JsonNode mapValueNode(String token, AvroRegistry registry) {
    String ref = normalizeRef(token);
    JsonNode wkt = WKT_SCHEMA.get(ref);
    if (wkt != null) return wkt;
    if (scalarTokens().contains(ref)) {
      return text(scalarAvro(ref));
    }
    if (registry != null && registry.isEnum(ref)) {
      return text("string");
    }
    if (registry != null) {
      String name = registry.nameOf(ref);
      if (name != null) {
        return text(name);
      }
    }
    throw new AvroUnsupportedException(
        ref,
        "a map value type with no Avro mapping; the Avro bridge fails loud rather than drop the"
            + " map");
  }

  static final Set<String> SCALARS =
      Set.of(
          "int32",
          "uint32",
          "sint32",
          "fixed32",
          "sfixed32",
          "int64",
          "uint64",
          "sint64",
          "fixed64",
          "sfixed64",
          "float",
          "double",
          "bool",
          "string",
          "bytes");

  /**
   * The proto scalar type names that map directly to Avro primitives and need no type definition:
   * the closed scalar set shared with the registry's graph walk.
   */
  static Set<String> scalarTokens() {
    return SCALARS;
  }

  /**
   * Map a scalar proto type to its Avro scalar. {@code uint64} maps to {@code long} — bit-exact
   * (two's complement): values &ge; 2^63 round-trip as their negative reading, then back to the
   * original on decode (the documented, accepted behavior; Avro has no unsigned 64-bit type).
   *
   * @throws AvroUnsupportedException for a scalar name the Avro mapping does not know — the closed
   *     proto scalar set makes this reachable only for a malformed field, in which case failing
   *     loud is right
   */
  public static String scalarAvro(String protoScalar) {
    return switch (protoScalar) {
      case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> "int";
      case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> "long";
      case "float" -> "float";
      case "double" -> "double";
      case "bool" -> "boolean";
      case "string" -> "string";
      case "bytes" -> "bytes";
      default ->
          throw new AvroUnsupportedException(
              protoScalar, "no Avro mapping for this proto scalar type");
    };
  }

  /**
   * If the field references an avro-mapped WKT, its fully-qualified name (the key into {@link
   * #WKT_SCHEMA}); otherwise null. The mapping set is {@link #wktFqns()} — a dynamic WKT (e.g.
   * {@code Struct}) is <em>not</em> in it and fails loud at registry/field level.
   */
  public static @Nullable String typeUrlIfWkt(Field f) {
    if (f.kind() != FieldKind.MESSAGE) return null;
    String t = f.typeUrl();
    return WKT.contains(t) ? t : null;
  }

  /**
   * Strip a leading {@code .} and surrounding whitespace from a type reference (a proto {@code
   * .a.b} vs {@code a.b}).
   */
  static String normalizeRef(@Nullable String ref) {
    if (ref == null) return "";
    String s = ref.trim();
    return s.startsWith(".") ? s.substring(1) : s;
  }

  // ---- type-definition ordering ----

  /**
   * Order the document's type definitions so that every referenced record precedes its referrer:
   * the Avro parser resolves a name reference <em>at parse time</em>, so a forward reference inside
   * a definition fails. First-seen (breadth-first) order breaks ties, keeping the output stable. A
   * reference cycle — a recursive proto message — cannot be ordered (an Avro record is not
   * recursively parseable); the leftovers are emitted in first-seen order and the specific-record
   * step fails over to the {@code .avsc}-only output (recursion is a documented v1 gap).
   */
  static List<ObjectNode> dependencyOrder(List<ObjectNode> defs) {
    Set<String> allNames = new LinkedHashSet<>();
    for (ObjectNode d : defs) {
      allNames.add(d.get("name").asString());
    }
    Map<String, Set<String>> refs = new LinkedHashMap<>();
    for (ObjectNode d : defs) {
      String name = d.get("name").asString();
      Set<String> r = new LinkedHashSet<>();
      if (d.has("fields")) {
        for (JsonNode f : d.get("fields")) {
          collectRefs(f.get("type"), allNames, r);
        }
      }
      refs.put(name, r);
    }
    List<ObjectNode> out = new ArrayList<>();
    Set<String> emitted = new LinkedHashSet<>();
    boolean progress = true;
    while (out.size() < defs.size() && progress) {
      progress = false;
      for (ObjectNode d : defs) {
        String name = d.get("name").asString();
        if (emitted.contains(name)) continue;
        boolean ready =
            refs.getOrDefault(name, Set.of()).stream()
                .allMatch(n -> !allNames.contains(n) || emitted.contains(n));
        if (ready) {
          out.add(d);
          emitted.add(name);
          progress = true;
        }
      }
    }
    for (ObjectNode d : defs) {
      if (!emitted.contains(d.get("name").asString())) out.add(d); // a cycle: leave as-is
    }
    return out;
  }

  /**
   * Collect the named-record references a type expression points at (through unions and arrays).
   */
  private static void collectRefs(@Nullable JsonNode type, Set<String> allNames, Set<String> out) {
    if (type == null) return;
    if (type.isArray()) {
      for (JsonNode t : type) {
        collectRefs(t, allNames, out);
      }
      return;
    }
    if (type.isObject()) {
      if ("record".equals(type.path("type").asString(null))) {
        for (JsonNode f : type.get("fields")) {
          collectRefs(f.get("type"), allNames, out);
        }
      }
      // An array is {"type":"array","items":T} — the element type may be a named record
      // (the unknown-fields trailer); a map's value may be too. Without this the dependency
      // order would emit a record before the record its items reference.
      JsonNode items = type.get("items");
      if (items != null) collectRefs(items, allNames, out);
      JsonNode values = type.get("values");
      if (values != null) collectRefs(values, allNames, out);
      return;
    }
    if (type.stringValueOpt().isPresent() && allNames.contains(type.asString())) {
      out.add(type.asString());
    }
  }

  // ---- serialization + WKT fragments ----

  /** Render a node tree as the {@code .avsc} document: pretty JSON (R6). */
  public static String pretty(JsonNode node) {
    try {
      return PRETTY.writeValueAsString(node);
    } catch (JacksonException impossible) {
      throw new IllegalStateException("pretty-printing a schema tree must not throw", impossible);
    }
  }

  /** Render a node tree as a single-line fragment (field-type fragments the converter uses). */
  static String compact(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException impossible) {
      throw new IllegalStateException("serializing a schema tree must not throw", impossible);
    }
  }

  private static JsonNode text(String value) {
    return MAPPER.getNodeFactory().stringNode(value);
  }

  private static JsonNode tree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JacksonException impossible) {
      throw new IllegalStateException("the WKT fragment literals must always parse", impossible);
    }
  }

  /**
   * The namespace of the WKT named types ({@code Duration}/{@code Empty}/the {@code NullValue}
   * enum) — the runtime WKT package's Avro twin; the named types are emitted as document types (see
   * {@link #wktNamedTypes}) so their specific classes resolve at the consumer.
   */
  public static final String WKT_NAMED_NAMESPACE = "bijnagte.recordbuf.avro.wkt";

  /**
   * The single WKT → Avro fragment table (R8), <em>synced with the Avro bridge's</em> canonical
   * {@code WktAvroBridge} fragment table (KTD8): every WKT field fragment is {@code null}-first
   * (the record component for a WKT field is a {@code @Nullable} presence value), and the named
   * types ({@code Duration}/{@code Empty}/{@code NullValue}) carry {@link #WKT_NAMED_NAMESPACE}.
   * The wrapper fragments are the WKT's raw value, matching the record component the runtime WKT
   * resolution unwraps to.
   */
  private static Map<String, JsonNode> wktSchema() {
    var m = new LinkedHashMap<String, JsonNode>();
    m.put(
        "google.protobuf.Timestamp",
        tree("[\"null\",{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}]"));
    m.put(
        "google.protobuf.Duration",
        tree(
            "[\"null\",{\"type\":\"record\",\"name\":\"Duration\","
                + "\"namespace\":\""
                + WKT_NAMED_NAMESPACE
                + "\","
                + "\"fields\":[{\"name\":\"seconds\",\"type\":\"long\"},{\"name\":\"nanos\",\"type\":\"int\"}]}]"));
    m.put("google.protobuf.FieldMask", tree("[\"null\",\"string\"]"));
    m.put(
        "google.protobuf.NullValue",
        tree(
            "[\"null\",{\"type\":\"enum\",\"name\":\"NullValue\",\"namespace\":\""
                + WKT_NAMED_NAMESPACE
                + "\",\"symbols\":[\"NULL_VALUE\",\"TRUE\",\"FALSE\"]}]"));
    m.put(
        "google.protobuf.Empty",
        tree(
            "[\"null\",{\"type\":\"record\",\"name\":\"Empty\",\"namespace\":\""
                + WKT_NAMED_NAMESPACE
                + "\",\"fields\":[]}]"));
    m.put("google.protobuf.Int32Value", tree("[\"null\",\"int\"]"));
    m.put("google.protobuf.Int64Value", tree("[\"null\",\"long\"]"));
    m.put("google.protobuf.UInt32Value", tree("[\"null\",\"int\"]"));
    m.put("google.protobuf.UInt64Value", tree("[\"null\",\"long\"]"));
    m.put("google.protobuf.FloatValue", tree("[\"null\",\"float\"]"));
    m.put("google.protobuf.DoubleValue", tree("[\"null\",\"double\"]"));
    m.put("google.protobuf.BoolValue", tree("[\"null\",\"boolean\"]"));
    m.put("google.protobuf.StringValue", tree("[\"null\",\"string\"]"));
    m.put("google.protobuf.BytesValue", tree("[\"null\",\"bytes\"]"));
    return Map.copyOf(m);
  }

  /**
   * The WKT named types referenced by the reachable graph ({@code Duration}/{@code Empty}/the
   * {@code NullValue} enum, de-duplicated), emitted as document types so their specific classes
   * resolve for the consumer.
   */
  private static List<ObjectNode> wktNamedTypes(AvroRegistry registry) {
    Set<String> seen = new LinkedHashSet<>();
    List<ObjectNode> out = new ArrayList<>();
    for (String fqn : registry.protoFqns()) {
      Message m = registry.messageOf(fqn);
      if (m == null) continue;
      for (Field f : m.fields()) {
        collectWktNamedField(f, out, seen);
      }
      for (Oneof oneof : m.oneofs()) {
        for (Field member : oneof.members()) {
          collectWktNamedField(member, out, seen);
        }
      }
    }
    return out;
  }

  /**
   * Add the named WKT types ({@code Duration}/{@code Empty}/{@code NullValue}) a field references.
   */
  private static void collectWktNamedField(Field f, List<ObjectNode> out, Set<String> seen) {
    String fqn = typeUrlIfWkt(f);
    if (fqn == null) return;
    JsonNode node = WKT_SCHEMA.get(fqn);
    if (node == null) return;
    // A fragment is either a top-level union array or an object wrapping one in its "type".
    JsonNode typeNode = node.isObject() ? node.path("type") : node;
    if (!typeNode.isArray()) return;
    for (JsonNode branch : typeNode) {
      if (branch.isObject()
          && ("record".equals(branch.path("type").asString())
              || "enum".equals(branch.path("type").asString()))
          && branch.has("name")) {
        if (seen.add(
            branch.path("name").asString() + "@" + branch.path("namespace").asString(""))) {
          out.add((ObjectNode) branch);
        }
      }
    }
  }
}
