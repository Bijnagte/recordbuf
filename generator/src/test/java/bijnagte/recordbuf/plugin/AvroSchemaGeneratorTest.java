package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.writer.AvroSchemaGenerator;
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
}
