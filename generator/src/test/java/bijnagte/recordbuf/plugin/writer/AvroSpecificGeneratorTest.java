package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the shape of the generated Avro-specific {@code XSpecific} record (KTD4 / R3 / R6). The
 * emitted source must be a {@code SpecificRecordBase} subclass with the embedded {@code SCHEMA}, a
 * {@code get(int)}/{@code put(int, Object)} index space matching the field order, and boxed Java
 * field types - i.e. the record the converter round-trips <em>through</em> (U6 compiles +
 * round-trips it; this test pins its fragments at the generator level).
 */
class AvroSpecificGeneratorTest {

  private static Message person() {
    var age =
        new Field(
            "age",
            "age",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "int32",
            null,
            null,
            false,
            null,
            null,
            false);
    var name =
        new Field(
            "name",
            "name",
            2,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "string",
            null,
            null,
            false,
            null,
            null,
            false);
    return new Message(
        "Person",
        "Person",
        List.of(age, name),
        java.util.List.<bijnagte.recordbuf.plugin.model.Message>of(),
        java.util.List.<bijnagte.recordbuf.plugin.model.Enum>of(),
        java.util.List.<bijnagte.recordbuf.plugin.model.Oneof>of(),
        false,
        null,
        List.of(),
        null);
  }

  @Test
  void specificRecordIsASpecificRecordBaseWithSchemaAndIndexSpace() {
    String src =
        AvroSpecificGenerator.specificClass("com.example", person(), "{\"type\":\"record\"}");
    assertTrue(src.contains("extends SpecificRecordBase"), src);
    assertTrue(src.contains("public static final Schema SCHEMA"), src);
    assertTrue(src.contains("public Schema getSchema()"), src); // getSchema via @Override
    assertTrue(src.contains("PersonSpecific"), "class name <X>Specific: " + src);
    // The get/put index space covers both fields.
    assertTrue(src.contains("case 0: return age"), src);
    assertTrue(src.contains("case 1: return name"), src);
    // Boxed Java field types.
    assertTrue(src.contains("Integer age"), src);
    assertTrue(src.contains("String name"), src);
  }
}
