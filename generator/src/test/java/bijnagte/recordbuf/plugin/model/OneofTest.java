package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.codegen.source.TypeRef;
import org.junit.jupiter.api.Test;

/** Tests for {@link Oneof#valueRecordName(Field)}, the oneof member value record's simple name. */
class OneofTest {

  @Test
  void valueRecordName_snakeCaseMember_isUpperCamelCaseOfTheCamelName() {
    assertEquals("OneofUint32Value", Oneof.valueRecordName(member("oneof_uint32")));
    assertEquals("OneofNestedMessageValue", Oneof.valueRecordName(member("oneof_nested_message")));
  }

  @Test
  void valueRecordName_singleSegmentMember_capitalizesTheName() {
    assertEquals("NameValue", Oneof.valueRecordName(member("name")));
  }

  @Test
  void valueRecordName_upperSegmentAfterUnderscore_keepsItsCase() {
    assertEquals("FieldNameValue", Oneof.valueRecordName(member("field_Name")));
  }

  private static Field member(String protoName) {
    return new Field(
        protoName,
        protoName,
        1,
        FieldLabel.OPTIONAL,
        FieldKind.SCALAR,
        "uint32",
        TypeRef.Primitive.INT,
        null,
        null,
        false,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        protoName,
        /* jsonIgnored= */ false);
  }
}
