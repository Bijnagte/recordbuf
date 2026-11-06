package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.AvroToggle;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
import java.util.List;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

/**
 * The Avro schema ({@code .avsc}) type mapping, R5/R6/R8: scalars map to native Avro types, an enum
 * to its Avro enum name, a repeated field to an array, a well-known type to its native fragment;
 * and fail-loud (R11/R17): {@code uint64} and map fields abort rather than emit a wrong type. Built
 * with hand-constructed {@link Field} records so the mapping is asserted deterministically,
 * independent of the descriptor-resolution layer.
 */
class AvroSchemaGeneratorTest {

  private static Field field(String name, FieldKind kind, String typeUrl, FieldLabel label) {
    // Field record: (name, javaName, number, label, kind, typeUrl, mapKey, mapValue,
    // proto3Optional, defaultValue, comment, deprecated). The nullable ones are left null/false/0.
    return new Field(name, name, 1, label, kind, typeUrl, null, null, false, null, null, false);
  }

  static final FieldLabel OPTIONAL = FieldLabel.OPTIONAL;
  static final FieldLabel REPEATED = FieldLabel.REPEATED;

  @Test
  void scalarsMapToNativeAvroTypes() {
    assertTrue(AvroSchemaGenerator.scalarAvro("int32").contains("int"));
    assertTrue(AvroSchemaGenerator.scalarAvro("int64").contains("long"));
    assertTrue(AvroSchemaGenerator.scalarAvro("double").contains("double"));
    assertTrue(AvroSchemaGenerator.scalarAvro("float").contains("float"));
    assertTrue(AvroSchemaGenerator.scalarAvro("bool").contains("boolean"));
    assertTrue(AvroSchemaGenerator.scalarAvro("string").contains("string"));
    assertTrue(AvroSchemaGenerator.scalarAvro("bytes").contains("bytes"));
  }

  @Test
  void enumRefersToAvroEnumByShortName() {
    Field c = field("c", FieldKind.ENUM, "p.Person.Color", OPTIONAL);
    assertTrue(AvroSchemaGenerator.avroType(c).contains("Color"), "enum -> Avro enum name");
  }

  @Test
  void wellKnownTypesMapToTheirFragment() {
    Field ts = field("when_created", FieldKind.MESSAGE, "google.protobuf.Timestamp", OPTIONAL);
    assertTrue(
        AvroSchemaGenerator.avroType(ts).contains("\"long\""), "Timestamp -> timestamp-micros");
    Field dur = field("d", FieldKind.MESSAGE, "google.protobuf.Duration", OPTIONAL);
    assertTrue(
        AvroSchemaGenerator.avroType(dur).contains("seconds"), "Duration -> structured record");
  }

  @Test
  void repeatedScalarBecomesAnArray() {
    Field tags = field("tags", FieldKind.SCALAR, "string", REPEATED);
    assertTrue(AvroSchemaGenerator.avroType(tags).contains("array"), "repeated -> Avro array");
  }

  @Test
  void uint64FailsLoud() {
    Field v = field("v", FieldKind.SCALAR, "uint64", OPTIONAL);
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.avroType(v));
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.scalarAvro("uint64"));
  }

  // ---- U7 fail-loud coverage (R11/R17): out-of-scope shapes abort, never a wrong type ----

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
            // @NonNull arg
            "string",
            "int32",
            false,
            null,
            null,
            false);
    assertTrue(m.isMap());
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.avroType(m));
  }

  @Test
  void repeatedNestedMessageFailsLoud() {
    Field m = field("list", FieldKind.MESSAGE, "com.example.P", REPEATED);
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.avroType(m));
  }

  @Test
  void unknownScalarFailsLoud() {
    Field x = field("x", FieldKind.SCALAR, "not_areal_proto_type", OPTIONAL);
    assertThrows(AvroUnsupportedException.class, () -> AvroSchemaGenerator.avroType(x));
  }

  @Test
  void unrecognizedWktOrNestedMessageFailsLoud() {
    // A message-typed field the bridge neither maps (WKT fragment) nor supports (nested message),
    // including the dynamic WKTs R11 scopes out of v1: Struct/Value/Any/ListValue/NullValue.
    for (String t : List.of("com.example.P", "google.protobuf.Struct", "google.protobuf.Any")) {
      Field m = field("x", FieldKind.MESSAGE, t, FieldLabel.OPTIONAL);
      assertThrows(
          AvroUnsupportedException.class,
          () -> AvroSchemaGenerator.avroType(m),
          "unrecognized/dynamic type must fail loud: " + t);
    }
  }

  /**
   * R11 dynamic-WKT fail-loud lives in the avro module's WktAvroBridgeTest, not here: the generator
   * is avro-free in main (R22) and must not reference :recordbuf-avro. The generator-side proof
   * that an out-of-scope shape aborts is unrecognizedWktOrNestedMessageFailsLoud above.
   */

  /**
   * Regression: the schema emitter once dropped the comma between the {@code namespace} and {@code
   * type} fields when a proto package was present, producing invalid JSON (two adjacent string
   * members) that Avro's parser rejects. A packaged schema must be valid, parseable Avro schema --
   * the exact shape option B's SpecificCompiler consumes.
   */
  @Test
  void packagedSchemaIsValidParseableAvro() throws Exception {
    Field id =
        new Field(
            "id",
            "id",
            1,
            OPTIONAL,
            FieldKind.SCALAR,
            "int32",
            null,
            null,
            false,
            null,
            null,
            false);
    Message m =
        new Message(
            "Sample",
            "p.Sample",
            List.of(id),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    ProtoFile pf =
        new ProtoFile("p.proto", "p", "p", List.of(m), List.of(), false, null, AvroToggle.ON);
    String schema = AvroSchemaGenerator.schema(pf);
    // Parse with Avro's own parser: a malformed document throws, which is the guard.
    Schema s = new Schema.Parser().parse(schema);
    assertEquals(Schema.Type.RECORD, s.getType(), "root is a record: " + schema);
    assertEquals(
        "SampleAvro",
        s.getName(),
        "class name = message name + \"Avro\" (distinct from record + ref)");
    assertEquals(
        "p", s.getNamespace(), "namespace IS the proto package (R7 simplified 2026-11-06);");
    assertEquals("int", s.getField("id").schema().getType().getName());
  }
}
