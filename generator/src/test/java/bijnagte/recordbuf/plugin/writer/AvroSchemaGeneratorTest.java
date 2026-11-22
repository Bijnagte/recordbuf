package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.AvroToggle;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code .avsc} document: field-type mapping, the v1 WKT table, maps, oneofs, the
 * unknown-fields trailer, cross-file types, and the parse green bar (R7/R8).
 *
 * <p>The full tree is exercised end to end against the real {@link org.apache.avro} parser in
 * {@code fullDocumentParsesWithTheAvroParser} — the bridge's exact flow (types before message).
 */
class AvroSchemaGeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Field field(String name, int number, String typeUrl) {
    return new Field(
        name,
        javaName(name),
        number,
        FieldLabel.OPTIONAL,
        kindFor(typeUrl),
        typeUrl,
        TypeRef.of(typeUrl),
        null,
        null,
        false,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  private static Field repeatedField(String name, int number, String typeUrl) {
    return new Field(
        name,
        javaName(name),
        number,
        FieldLabel.REPEATED,
        kindFor(typeUrl),
        typeUrl,
        TypeRef.of(typeUrl),
        null,
        null,
        false,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  private static Field mapField(String name, String keyType, String valueType) {
    return new Field(
        name,
        javaName(name),
        1,
        FieldLabel.OPTIONAL,
        FieldKind.MAP,
        keyType,
        TypeRef.of(keyType),
        keyType,
        valueType,
        false,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  private static Message message(String name, String pkg, Field... fields) {
    return new Message(
        name,
        TypeRef.named(pkg, name),
        List.of(fields),
        List.of(),
        List.of(),
        List.of(),
        false,
        null,
        List.of(),
        null);
  }

  private static ProtoFile file(String fileName, String pkg, Message... messages) {
    return new ProtoFile(
        fileName,
        pkg,
        "",
        List.of(messages),
        List.of(),
        false,
        null,
        AvroToggle.OFF,
        /* jackson= */ false);
  }

  private static FileTable tableOf(FileDescriptorProto... files) {
    FileTable table = new FileTable();
    table.addAll(List.of(files));
    return table;
  }

  private static FileDescriptorProto fdp(String fileName, String pkg, String... messages) {
    FileDescriptorProto.Builder b =
        FileDescriptorProto.newBuilder().setName(fileName).setPackage(pkg);
    for (String m : messages) {
      b.addMessageType(DescriptorProto.newBuilder().setName(m));
    }
    return b.build();
  }

  private static JsonNode tree(ProtoFile pf, Message root, @Nullable FileTable table)
      throws Exception {
    return MAPPER.readTree(AvroSchemaGenerator.schema(pf, root, table));
  }

  private static JsonNode rootField(JsonNode doc, String fieldName) {
    JsonNode fields = doc.get("message").get("fields");
    for (JsonNode f : fields) {
      if (f.get("name").asString().equals(fieldName)) {
        return f;
      }
    }
    throw new AssertionError("no field named " + fieldName + " in the document's message");
  }

  private static int typeNameIndex(JsonNode types, String name) {
    for (int i = 0; i < types.size(); i++) {
      if (types.get(i).get("name").asString().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  private static FieldKind kindFor(String type) {
    if (type.endsWith(".Color") || type.endsWith(".Status")) {
      return FieldKind.ENUM;
    }
    if (type.startsWith("google.protobuf.")
        || type.startsWith("p.")
        || type.startsWith("a.")
        || type.startsWith("b.")
        || type.startsWith("q.")) {
      return FieldKind.MESSAGE;
    }
    return FieldKind.SCALAR;
  }

  private static String javaName(String protoName) {
    return protoName;
  }

  @Test
  void scalarFieldsMapToAvroPrimitivesIncludingUInt64AsLong() throws Exception {
    Message root =
        message(
            "Sample",
            "p",
            field("b", 1, "bool"),
            field("i", 2, "int32"),
            field("u", 3, "uint32"),
            field("u64", 4, "uint64"),
            field("s64", 5, "sint64"),
            field("f", 6, "float"),
            field("d", 7, "double"),
            field("s", 8, "string"),
            field("bytes", 9, "bytes"));

    JsonNode doc = tree(file("a.proto", "p", root), root, null);

    assertEquals("boolean", rootField(doc, "b").get("type").asString());
    assertEquals("int", rootField(doc, "i").get("type").asString());
    assertEquals("int", rootField(doc, "u").get("type").asString());
    assertEquals("long", rootField(doc, "u64").get("type").asString());
    assertEquals("long", rootField(doc, "s64").get("type").asString());
    assertEquals("float", rootField(doc, "f").get("type").asString());
    assertEquals("double", rootField(doc, "d").get("type").asString());
    assertEquals("string", rootField(doc, "s").get("type").asString());
    assertEquals("bytes", rootField(doc, "bytes").get("type").asString());
  }

  @Test
  void enumFieldMapsToString() throws Exception {
    Enum color =
        new Enum(
            "Color",
            List.of(
                new Enum.EnumConstant("RED", 1, false, null),
                new Enum.EnumConstant("GREEN", 2, false, null)),
            false,
            null,
            List.of());
    Message root = message("Sample", "p", field("color", 1, "p.Color"));
    ProtoFile pf =
        new ProtoFile(
            "a.proto",
            "p",
            "",
            List.of(root),
            List.of(color),
            false,
            null,
            AvroToggle.OFF,
            /* jackson= */ false);

    JsonNode doc = tree(pf, root, null);

    assertEquals("string", rootField(doc, "color").get("type").asString());
    assertEquals(
        1,
        doc.get("types").size(),
        "only the unknown-fields entry record: enums contribute no type definitions");
  }

  @Test
  void wktFieldsInlineTheV1Fragments() throws Exception {
    Message root =
        message(
            "Sample",
            "p",
            field("at", 1, "google.protobuf.Timestamp"),
            field("for", 2, "google.protobuf.Duration"),
            field("note", 3, "google.protobuf.StringValue"));

    JsonNode doc = tree(file("a.proto", "p", root), root, null);

    assertEquals(
        "long",
        rootField(doc, "at").get("type").get(1).get("type").asString(),
        "Timestamp fragment's non-null branch is a long with the timestamp-micros logical type");
    JsonNode duration = rootField(doc, "for").get("type");
    assertEquals("record", duration.get(1).get("type").asString());
    assertEquals(2, duration.get(1).get("fields").size());
    assertTrue(rootField(doc, "note").get("type").isArray());
    assertEquals("null", rootField(doc, "note").get("type").get(0).asString());
    assertEquals("string", rootField(doc, "note").get("type").get(1).asString());
    assertEquals(
        2,
        doc.get("types").size(),
        "the reachable WKT named type (Duration) + the unknown-fields entry record");
    assertEquals("RecordbufUnknownField", doc.get("types").get(1).get("name").asString());
  }

  @Test
  void repeatedScalarsAreArraysAndRepeatedMessagesAreArraysOfNames() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Message root =
        message(
            "Sample", "p", repeatedField("tags", 1, "string"), repeatedField("cards", 2, "p.Card"));

    JsonNode doc = tree(file("a.proto", "p", root, card), root, null);

    assertEquals("array", rootField(doc, "tags").get("type").get("type").asString());
    assertEquals("string", rootField(doc, "tags").get("type").get("items").asString());
    assertEquals("array", rootField(doc, "cards").get("type").get("type").asString());
    assertEquals("CardAvro", rootField(doc, "cards").get("type").get("items").asString());
  }

  @Test
  void stringKeyedMapsMapToAvroMaps() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Message root =
        message(
            "Sample",
            "p",
            mapField("sizes", "string", "int32"),
            mapField("cards", "string", "p.Card"));

    JsonNode doc = tree(file("a.proto", "p", root, card), root, null);

    assertEquals("map", rootField(doc, "sizes").get("type").get("type").asString());
    assertEquals("int", rootField(doc, "sizes").get("type").get("values").asString());
    assertEquals("map", rootField(doc, "cards").get("type").get("type").asString());
    assertEquals("CardAvro", rootField(doc, "cards").get("type").get("values").asString());
  }

  @Test
  void nonStringKeyedMapsMapToPairRecordArrays() throws Exception {
    Message root = message("Sample", "p", mapField("sizes", "int32", "int32"));

    JsonNode doc = tree(file("a.proto", "p", root), root, null);

    assertEquals("array", rootField(doc, "sizes").get("type").get("type").asString());
    assertEquals("SizesEntryAvro", rootField(doc, "sizes").get("type").get("items").asString());
    JsonNode types = doc.get("types");
    assertEquals(2, types.size(), "the entry record plus the unknown-fields entry");
    JsonNode pair = types.get(0);
    assertEquals("SizesEntryAvro", pair.get("name").asString());
    assertEquals("int", pair.get("fields").get(0).get("type").asString());
    assertEquals("int", pair.get("fields").get(1).get("type").asString());
  }

  @Test
  void oneofBecomesANullFirstUnionWithoutDuplicates() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Field textMember = field("text", 7, "string");
    Field cardMember = field("card", 8, "p.Card");
    Oneof choice =
        new Oneof(
            "choice",
            TypeRef.named("p", "Choice"),
            OneofDescriptorProto.newBuilder().setName("choice").build(),
            DescriptorProto.newBuilder().setName("Sample").build(),
            List.of(textMember, cardMember),
            /* jsonOneof= */ null);
    Message root =
        new Message(
            "Sample",
            TypeRef.named("p", "Sample"),
            List.of(),
            List.of(),
            List.of(),
            List.of(choice),
            false,
            null,
            List.of(),
            null);

    JsonNode doc = tree(file("a.proto", "p", root, card), root, null);

    JsonNode union = rootField(doc, "choice").get("type");
    assertTrue(union.isArray());
    assertEquals("null", union.get(0).asString(), "null first");
    assertEquals(3, union.size(), "null + the two members");
    assertEquals("RecordbufOneofValue", union.get(1).asString());
    assertEquals("CardAvro", union.get(2).asString());
  }

  @Test
  void everyRecordCarriesTheTrailingUnknownFields() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Message root = message("Sample", "p", field("t", 1, "string"), field("card", 2, "p.Card"));

    JsonNode doc = tree(file("a.proto", "p", root, card), root, null);

    JsonNode trailer =
        doc.get("message").get("fields").get(doc.get("message").get("fields").size() - 1);
    assertEquals("unknownFields", trailer.get("name").asString());
    assertEquals(
        "array", trailer.get("type").get("type").asString(), "the v1 trailer is a plain array");
    assertEquals("RecordbufUnknownField", trailer.get("type").get("items").asString());
  }

  @Test
  void theEntryRecordDeclaresNumberWireTypeAndValue() throws Exception {
    Message root = message("Sample", "p", field("t", 1, "string"));

    JsonNode doc = tree(file("a.proto", "p", root), root, null);

    JsonNode types = doc.get("types");
    JsonNode entry = types.get(types.size() - 1);
    assertEquals("RecordbufUnknownField", entry.get("name").asString());
    assertEquals("int", entry.get("fields").get(0).get("type").asString());
    assertEquals("string", entry.get("fields").get(1).get("type").asString());
    JsonNode value = entry.get("fields").get(2).get("type");
    assertTrue(value.isArray());
    assertEquals("null", value.get(0).asString());
    assertEquals("long", value.get(1).asString());
    assertEquals("bytes", value.get(2).asString());
  }

  @Test
  void crossFileImportsAppearInTypesWithTheirOwnNamespace() throws Exception {
    Message root = message("Sample", "a", field("thing", 1, "b.Thing"));
    FileTable table = tableOf(fdp("b.proto", "b", "Thing"));

    JsonNode doc = tree(file("a.proto", "a", root), root, table);

    assertEquals("a", doc.get("namespace").asString());
    int idx = typeNameIndex(doc.get("types"), "ThingAvro");
    assertTrue(idx >= 0, "the imported type appears in types");
    assertEquals("b", doc.get("types").get(idx).get("namespace").asString());
  }

  @Test
  void referencedTypesAreOrderedBeforeTheirReferrers() throws Exception {
    Message a = message("A", "p", field("b", 1, "p.B"));
    Message b = message("B", "p", field("x", 1, "int32"));
    Message root = message("Root", "p", field("a", 1, "p.A"));

    JsonNode doc = tree(file("a.proto", "p", root, a, b), root, null);

    int aIdx = typeNameIndex(doc.get("types"), "AAvro");
    int bIdx = typeNameIndex(doc.get("types"), "BAvro");
    assertTrue(aIdx >= 0 && bIdx >= 0);
    assertTrue(bIdx < aIdx, "B must precede A (Avro resolves names at parse time)");
  }

  @Test
  void dynamicWktFailsLoudAtBuild() throws Exception {
    Message root = message("Sample", "p", field("payload", 1, "google.protobuf.Any"));

    assertThrows(
        AvroUnsupportedException.class,
        () -> AvroSchemaGenerator.schema(file("a.proto", "p", root), root, null));
  }

  @Test
  void aMessageWithNoFieldsStillCarriesTheTrailerOnly() throws Exception {
    Message root = message("Empty", "p");

    JsonNode doc = tree(file("a.proto", "p", root), root, null);

    assertEquals(1, doc.get("message").get("fields").size());
    assertEquals("unknownFields", doc.get("message").get("fields").get(0).get("name").asString());
  }

  @Test
  void unknownScalarStillFailsLoud() {
    // KTD7: the one surviving v1 fail-loud — a proto scalar with no Avro mapping.
    assertThrows(
        AvroUnsupportedException.class, () -> AvroSchemaGenerator.scalarAvro("quaternion"));
  }

  @Test
  void fullDocumentParsesWithTheAvroParser() throws Exception {
    // The bridge's exact flow: parse every types entry, then the message.
    Message color =
        new Message(
            "Color",
            TypeRef.named("p", "Color"),
            List.of(field("hue", 1, "int32")),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    Enum status =
        new Enum(
            "Status",
            List.of(new Enum.EnumConstant("ACTIVE", 1, false, null)),
            false,
            null,
            List.of());
    Message card =
        message("Card", "p", repeatedField("tags", 2, "string"), field("status", 3, "p.Status"));
    Message root =
        new Message(
            "Sample",
            TypeRef.named("p", "Sample"),
            List.of(
                field("title", 1, "string"),
                field("card", 2, "p.Card"),
                field("big", 3, "uint64"),
                field("at", 4, "google.protobuf.Timestamp"),
                field("pair", 5, "google.protobuf.Duration")),
            List.of(color),
            List.of(status),
            List.of(),
            false,
            null,
            List.of(),
            null);
    ProtoFile pf =
        new ProtoFile(
            "a.proto",
            "p",
            "",
            List.of(root, card),
            List.of(status),
            false,
            null,
            AvroToggle.OFF,
            /* jackson= */ false);

    JsonNode doc = tree(pf, root, null);

    Parser parser = new Parser();
    for (JsonNode def : doc.get("types")) {
      parser.parse(MAPPER.writeValueAsString(def));
    }
    Schema message = parser.parse(MAPPER.writeValueAsString(doc.get("message")));
    assertEquals("SampleAvro", message.getName());
    assertFalse(message.getFields().isEmpty());
  }

  @Test
  void jacksonOnDynamicWktFieldsMapToTheSharedEnvelope() throws Exception {
    Message root =
        message(
            "Sample",
            "p",
            field("value", 1, "google.protobuf.Value"),
            repeatedField("values", 2, "google.protobuf.Value"),
            field("payload", 3, "google.protobuf.Any"),
            field("at", 4, "google.protobuf.Timestamp"));
    ProtoFile pf =
        new ProtoFile(
            "a.proto",
            "p",
            "",
            List.of(root),
            List.of(),
            false,
            null,
            AvroToggle.OFF,
            /* jackson= */ true);

    JsonNode doc = tree(pf, root, null);

    // The envelope is emitted once, as a shared carrier in the document namespace.
    JsonNode types = doc.get("types");
    int envelopes = 0;
    for (JsonNode t : types) {
      if (t.get("name").asString().equals(AvroRegistry.DYNAMIC_WKT_ENTRY)) {
        envelopes++;
        List<String> names = new ArrayList<>();
        t.get("fields").forEach(f -> names.add(f.get("name").asString()));
        assertEquals(List.of("format", "contentType", "payload"), names);
        assertEquals("p", t.get("namespace").asString());
      }
    }
    assertEquals(1, envelopes, "the envelope record is emitted exactly once");

    // Singular dynamic WKTs: a null-first union over the envelope.
    for (String name : List.of("value", "payload")) {
      JsonNode type = rootField(doc, name).get("type");
      assertEquals("null", type.get(0).asString(), name);
      assertEquals(AvroRegistry.DYNAMIC_WKT_ENTRY, type.get(1).asString(), name);
    }
    // Repeated: an array whose items are the same union.
    JsonNode values = rootField(doc, "values").get("type");
    assertEquals("array", values.get("type").asString());
    assertEquals(AvroRegistry.DYNAMIC_WKT_ENTRY, values.get("items").get(1).asString());
    // Structured WKTs keep their structural-twin mapping.
    assertFalse(
        rootField(doc, "at").get("type").toString().contains(AvroRegistry.DYNAMIC_WKT_ENTRY));

    // And the document is valid Avro, parsed in the bridge's order.
    Parser parser = new Parser();
    for (JsonNode def : types) {
      parser.parse(MAPPER.writeValueAsString(def));
    }
    Schema message = parser.parse(MAPPER.writeValueAsString(doc.get("message")));
    assertEquals("SampleAvro", message.getName());
  }

  @Test
  void jacksonOffDynamicWktStillFailsLoud() {
    Message root = message("Sample", "p", field("value", 1, "google.protobuf.Value"));

    assertThrows(
        AvroUnsupportedException.class, () -> tree(file("a.proto", "p", root), root, null));
  }
}
