package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JavaNames#protobufJavaAccessor(String)}, which converts proto field names to
 * protobuf-java accessor stems following protoc's Java naming rule.
 */
class JavaNamesTest {

  @Test
  void protobufJavaAccessor_plainSnakeName() {
    assertEquals("OptionalInt32", JavaNames.protobufJavaAccessor("optional_int32"));
  }

  @Test
  void protobufJavaAccessor_fieldname1() {
    assertEquals("Fieldname1", JavaNames.protobufJavaAccessor("fieldname1"));
  }

  @Test
  void protobufJavaAccessor_field_name2() {
    assertEquals("FieldName2", JavaNames.protobufJavaAccessor("field_name2"));
  }

  @Test
  void protobufJavaAccessor_field__name4_() {
    assertEquals("FieldName4", JavaNames.protobufJavaAccessor("field__name4_"));
  }

  @Test
  void protobufJavaAccessor_field0name5() {
    assertEquals("Field0Name5", JavaNames.protobufJavaAccessor("field0name5"));
  }

  @Test
  void protobufJavaAccessor_field_0_name6() {
    assertEquals("Field0Name6", JavaNames.protobufJavaAccessor("field_0_name6"));
  }

  @Test
  void protobufJavaAccessor_fieldName7() {
    assertEquals("FieldName7", JavaNames.protobufJavaAccessor("fieldName7"));
  }

  @Test
  void protobufJavaAccessor_field_Name9() {
    assertEquals("FieldName9", JavaNames.protobufJavaAccessor("field_Name9"));
  }

  @Test
  void protobufJavaAccessor_field__name15() {
    assertEquals("FieldName15", JavaNames.protobufJavaAccessor("field__name15"));
  }

  @Test
  void protobufJavaAccessor_field__Name16() {
    assertEquals("FieldName16", JavaNames.protobufJavaAccessor("field__Name16"));
  }

  @Test
  void protobufJavaAccessor_field_name17__() {
    assertEquals("FieldName17", JavaNames.protobufJavaAccessor("field_name17__"));
  }

  @Test
  void protobufJavaAccessor_null() {
    assertEquals(null, JavaNames.protobufJavaAccessor(null));
  }

  @Test
  void protobufJavaAccessor_empty() {
    assertEquals("", JavaNames.protobufJavaAccessor(""));
  }
}
