package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.AvroToggle;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

/**
 * The Avro schema ({@code .avsc}) type mapping, R5/R6/R8: scalars map to native Avro types, an enum
 * to its Avro enum name, a repeated field to an array, a well-known type to its native fragment;
 * and fail-loud (R11/R17): {@code uint64} and map fields abort rather than emit a wrong type.
 *
 * <p>Reframed in T3 off brittle string-matching: the emitter now hands out a pretty-printed JSON
 * tree, so these tests parse the emitted {@code .avsc} back with Jackson's {@link ObjectMapper} and
 * assert the {@link JsonNode} structure (field names, {@code type}, {@code items}, nested WKT
 * records) rather than substring-matching the JSON text. The {@link
 * #packagedSchemaIsValidParseableAvro} regression additionally guards that the produced document
 * parses with Avro's own {@code org.apache.avro.Schema} — the exact shape option B's {@code
 * SpecificCompiler} consumes.
 */
class AvroSchemaGeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FieldLabel OPTIONAL = FieldLabel.OPTIONAL;
  private static final FieldLabel REPEATED = FieldLabel.REPEATED;

  private static Field field(String name, FieldKind kind, String typeUrl, FieldLabel label) {
    // Field record: (name, javaName, number, label, kind, typeUrl, type, mapKey, mapValue,
    // proto3Optional, defaultValue, comment, deprecated). The nullable ones are left null/false/0.
    return new Field(
        name,
        name,
        1,
        label,
        kind,
        typeUrl,
        typeFor(kind, typeUrl, label),
        null,
        null,
        false,
        null,
        null,
        false);
  }

  private static TypeRef typeFor(FieldKind kind, String typeUrl, FieldLabel label) {
    if (kind == FieldKind.MAP) {
      return TypeRef.OBJECT;
    }
    if (kind == FieldKind.SCALAR) {
      TypeRef boxed =
          switch (typeUrl) {
            case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> TypeRef.INTEGER;
            case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> TypeRef.LONG;
            case "float" -> TypeRef.FLOAT;
            case "double" -> TypeRef.DOUBLE;
            case "bool" -> TypeRef.BOOLEAN;
            case "string" -> TypeRef.STRING;
            case "bytes" -> TypeRefs.BYTES;
            default -> TypeRef.OBJECT;
          };
      TypeRef ref =
          switch (typeUrl) {
            case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> TypeRef.Primitive.INT;
            case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> TypeRef.Primitive.LONG;
            case "float" -> TypeRef.Primitive.FLOAT;
            case "double" -> TypeRef.Primitive.DOUBLE;
            case "bool" -> TypeRef.Primitive.BOOLEAN;
            default -> boxed;
          };
      if (label == FieldLabel.REPEATED) {
        return TypeRefs.LIST.withGenerics(boxed);
      }
      return ref;
    }
    int dot = typeUrl.lastIndexOf('.');
    if (dot < 0) {
      return TypeRef.named("", typeUrl);
    }
    return TypeRef.named(typeUrl.substring(0, dot), typeUrl.substring(dot + 1));
  }

  private static Message message(List<Field> fields) {
    // Message: (name, type, fields, nestedMessages, nestedEnums, oneofs, deprecated,
    // comment, interfaces, protobufJava). Built the same shape the emitter + converter tests use.
    return new Message(
        "Sample",
        TypeRef.named("p", "Sample"),
        fields,
        List.of(),
        List.of(),
        List.of(),
        false,
        null,
        List.of(),
        null);
  }

  /**
   * Parse {@link AvroSchemaGenerator#schema} the .avsc for a one-message file into a {@link
   * JsonNode}.
   */
  private static JsonNode schemaOf(Message m) throws Exception {
    ProtoFile pf =
        new ProtoFile("p.proto", "p", "p", List.of(m), List.of(), false, null, AvroToggle.ON);
    return MAPPER.readTree(AvroSchemaGenerator.schema(pf));
  }

  /** The {@code "type"} node of the single (or named) field in the parsed schema. */
  private static JsonNode fieldType(JsonNode root, String fieldName) {
    JsonNode fields = root.get("fields");
    for (JsonNode f : fields) {
      if (f.get("name").asText().equals(fieldName)) return f.get("type");
    }
    throw new AssertionError("no field " + fieldName + " in " + root);
  }

  @Test
  void scalarsMapToNativeAvroTypes() throws Exception {
    Message m =
        message(
            List.of(
                field("i", FieldKind.SCALAR, "int32", OPTIONAL),
                field("l", FieldKind.SCALAR, "int64", OPTIONAL),
                field("d", FieldKind.SCALAR, "double", OPTIONAL),
                field("f", FieldKind.SCALAR, "float", OPTIONAL),
                field("b", FieldKind.SCALAR, "bool", OPTIONAL),
                field("s", FieldKind.SCALAR, "string", OPTIONAL),
                field("by", FieldKind.SCALAR, "bytes", OPTIONAL)));
    JsonNode root = schemaOf(m);
    assertEquals("int", fieldType(root, "i").asText(), "int32 -> avro int");
    assertEquals("long", fieldType(root, "l").asText(), "int64 -> avro long");
    assertEquals("double", fieldType(root, "d").asText(), "double -> avro double");
    assertEquals("float", fieldType(root, "f").asText(), "float -> avro float");
    assertEquals("boolean", fieldType(root, "b").asText(), "bool -> avro boolean");
    assertEquals("string", fieldType(root, "s").asText(), "string -> avro string");
    assertEquals("bytes", fieldType(root, "by").asText(), "bytes -> avro bytes");
  }

  @Test
  void enumRefersToAvroEnumByShortName() throws Exception {
    JsonNode root =
        schemaOf(message(List.of(field("c", FieldKind.ENUM, "p.Person.Color", OPTIONAL))));
    assertEquals("Color", fieldType(root, "c").asText(), "enum -> Avro enum name");
  }

  @Test
  void wellKnownTypesMapToTheirFragment() throws Exception {
    JsonNode ts =
        schemaOf(
            message(
                List.of(
                    field(
                        "when_created",
                        FieldKind.MESSAGE,
                        "google.protobuf.Timestamp",
                        OPTIONAL))));
    // Timestamp maps to the native long fragment { "type": "long" }.
    assertEquals(
        "long", fieldType(ts, "when_created").get("type").asText(), "Timestamp -> long fragment");

    JsonNode dur =
        schemaOf(
            message(List.of(field("d", FieldKind.MESSAGE, "google.protobuf.Duration", OPTIONAL))));
    JsonNode durType = fieldType(dur, "d");
    // Duration maps to a structured record {seconds, nanos}.
    assertEquals("record", durType.get("type").asText(), "Duration -> record");
    assertEquals("Duration", durType.get("name").asText(), "Duration record name");
    assertTrue(hasField(durType, "seconds"), "Duration record carries a seconds field");
    assertTrue(hasField(durType, "nanos"), "Duration record carries a nanos field");
  }

  @Test
  void repeatedScalarBecomesAnArray() throws Exception {
    JsonNode root = schemaOf(message(List.of(field("tags", FieldKind.SCALAR, "string", REPEATED))));
    JsonNode type = fieldType(root, "tags");
    assertEquals("array", type.get("type").asText(), "repeated -> Avro array");
    assertEquals("string", type.get("items").asText(), "array items are the element type");
  }

  // ---- U7 fail-loud coverage (R11/R17): out-of-scope shapes abort, never a wrong type ----

  @Test
  void uint64FailsLoud() {
    Field v = field("v", FieldKind.SCALAR, "uint64", OPTIONAL);
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.typeNode(v));
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.scalarAvro("uint64"));
  }

  @Test
  void mapFieldFailsLoud() {
    Field m =
        new Field(
            "m",
            "m",
            7,
            REPEATED,
            FieldKind.MAP,
            "", // typeUrl is null for a map (semantics come from mapKey/mapValue); "" avoids a null
            TypeRefs.MAP.withGenerics(TypeRef.STRING, TypeRef.INTEGER),
            "string",
            "int32",
            false,
            null,
            null,
            false);
    assertTrue(m.isMap());
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.typeNode(m));
  }

  @Test
  void repeatedNestedMessageFailsLoud() {
    Field m = field("list", FieldKind.MESSAGE, "com.example.P", REPEATED);
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.typeNode(m));
  }

  @Test
  void unknownScalarFailsLoud() {
    Field x = field("x", FieldKind.SCALAR, "not_areal_proto_type", OPTIONAL);
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.typeNode(x));
  }

  @Test
  void unrecognizedWktOrNestedMessageFailsLoud() throws Exception {
    // A message-typed field the bridge neither maps (WKT fragment) nor supports (nested message),
    // including the dynamic WKTs R11 scopes out of v1: Struct/Value/Any/ListValue/NullValue(union).
    for (String t : List.of("com.example.P", "google.protobuf.Struct", "google.protobuf.Any")) {
      Field m = field("x", FieldKind.MESSAGE, t, OPTIONAL);
      assertThrows(
          AvroUnsupportedException.class,
          () -> AvroSchemaGenerator.typeNode(m),
          "unrecognized/dynamic type must fail loud: " + t);
    }
  }

  /**
   * Regression: the schema emitter once dropped the comma between the {@code namespace} and {@code
   * type} fields when a proto package was present, producing invalid JSON (two adjacent string
   * members) that Avro's parser rejects. A packaged schema must be valid, parseable Avro schema --
   * the exact shape option B's SpecificCompiler consumes. Asserted structurally via Jackson + a
   * hard {@code org.apache.avro.Schema} parse.
   */
  @Test
  void packagedSchemaIsValidParseableAvro() throws Exception {
    Field id = field("id", FieldKind.SCALAR, "int32", OPTIONAL);
    ProtoFile pf =
        new ProtoFile(
            "p.proto",
            "p",
            "p",
            List.of(message(List.of(id))),
            List.of(),
            false,
            null,
            AvroToggle.ON);
    String schema = AvroSchemaGenerator.schema(pf);

    // Structural guard: the document parses as a Jackson tree.
    JsonNode root = MAPPER.readTree(schema);
    assertEquals("record", root.get("type").asText(), "root is a record");
    assertEquals("SampleAvro", root.get("name").asText(), "class name = message name + \"Avro\"");
    assertEquals("p", root.get("namespace").asText(), "namespace IS the proto package");
    assertTrue(root.get("fields").isArray() && root.get("fields").size() == 1, "one field");
    assertEquals("int", root.get("fields").get(0).get("type").asText(), "field id -> int");

    // And as a real Avro schema (the shape option B's SpecificCompiler consumes).
    Schema s = new Schema.Parser().parse(schema);
    assertEquals(Schema.Type.RECORD, s.getType());
    assertEquals("SampleAvro", s.getName());
    assertEquals("p", s.getNamespace());
    assertEquals("int", s.getField("id").schema().getType().getName());
  }

  private static boolean hasField(JsonNode recordSchema, String name) {
    JsonNode fields = recordSchema.get("fields");
    if (fields == null || !fields.isArray()) return false;
    for (JsonNode f : fields) {
      if (f.get("name").asText().equals(name)) return true;
    }
    return false;
  }
}
