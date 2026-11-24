package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JavaNames#protobufJavaAccessor(String)}, which converts proto field names to
 * protobuf-java accessor stems following protoc's Java naming rule, and for {@link
 * JavaNames#component(String)}, which derives the record component name from a field name.
 */
class JavaNamesTest {

  @Test
  void component_leadingUnderscoreUpperStart_lowersFirstLetter() {
    assertEquals("fieldName3", JavaNames.component("_field_name3"));
  }

  @Test
  void component_upperCamelStart_lowersFirstLetter() {
    assertEquals("fieldName8", JavaNames.component("FieldName8"));
  }

  @Test
  void component_upperSegmentAfterUnderscore_lowersOnlyTheFirstLetter() {
    assertEquals("fieldName10", JavaNames.component("Field_Name10"));
    assertEquals("fIELDNAME11", JavaNames.component("FIELD_NAME11"));
    assertEquals("fIELDName12", JavaNames.component("FIELD_name12"));
  }

  @Test
  void component_doubleUnderscoreUpperStart_lowersFirstLetter() {
    assertEquals("fieldName13", JavaNames.component("__field_name13"));
    assertEquals("fieldName14", JavaNames.component("__Field_name14"));
  }

  @Test
  void component_trailingUnderscores_areDropped() {
    assertEquals("fieldName18", JavaNames.component("Field_name18__"));
  }

  @Test
  void component_lowerStart_isUnchanged() {
    assertEquals("fieldName2", JavaNames.component("field_name2"));
    assertEquals("fieldName7", JavaNames.component("fieldName7"));
    assertEquals("fieldname1", JavaNames.component("fieldname1"));
  }

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
