package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.UninterpretedOption;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

/**
 * U1 of the java_type format-field plan: the generator reads the per-field {@code
 * recordbuf.java_type} option (tag 50010) off a singular or repeated {@code string} field, puts the
 * FQCN verbatim on the model ({@link Field#javaType()}), and makes the field presentable ({@link
 * Field#isNullable()}). Non-string, map and empty-value settings fail loud (R3); oneof members take
 * the option too, and a singular string field on a message with the protobuf-java reference must be
 * declared `optional` (repeated fields are exempt).
 */
@SuppressWarnings("BadImport")
class JavaTypeOptionTest {

  /**
   * The tag of the {@code recordbuf.java_type} field-option extension (string, length-delimited).
   */
  private static final int JAVA_TYPE_TAG = 50010;

  /**
   * The tag of the {@code recordbuf.protobuf_java} message-option extension (string,
   * length-delimited).
   */
  private static final int MESSAGE_PROTOBUF_JAVA_TAG = 50002;

  // --- descriptor construction ---------------------------------------------------------

  private static FieldDescriptorProto stringField(String name, int number) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(Type.TYPE_STRING)
        .build();
  }

  private static FieldOptions javaTypeOption(String fqcn) {
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(
                JAVA_TYPE_TAG,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(ByteString.copyFromUtf8(fqcn))
                    .build())
            .build();
    return FieldOptions.newBuilder().setUnknownFields(unknown).build();
  }

  private static FieldDescriptorProto fieldWithJavaType(String name, int number, String fqcn) {
    return stringField(name, number).toBuilder().setOptions(javaTypeOption(fqcn)).build();
  }

  private static FileDescriptorProto fileWith(String name, DescriptorProto message) {
    return FileDescriptorProto.newBuilder()
        .setName(name)
        .setPackage("pkg")
        .addMessageType(message)
        .build();
  }

  private static ProtoFile load(FileDescriptorProto file) {
    return new ModelLoader(new FileTable(""), "pkg").load(file);
  }

  private static void assertJavaTypeError(IllegalStateException e) {
    String m = e.getMessage();
    assertNotNull(m, "exception message should be set");
    assertTrue(m.contains("recordbuf.java_type"), m);
  }

  // --- model assertions --------------------------------------------------------------

  @Test
  void singularStringWithJavaTypeCarriesType() {
    DescriptorProto doc =
        DescriptorProto.newBuilder()
            .setName("Doc")
            .addField(fieldWithJavaType("id", 1, "java.util.UUID"))
            .build();
    Field f = load(fileWith("doc.proto", doc)).messages().get(0).fields().get(0);

    assertEquals("java.util.UUID", f.javaType());
    assertEquals("java.util.UUID", f.type().fqn());
    assertTrue(f.isNullable(), "a format field is presentable regardless of `optional`");
  }

  @Test
  void optionalStringWithJavaTypeCarriesType() {
    FieldDescriptorProto field =
        fieldWithJavaType("id", 1, "java.util.UUID").toBuilder().setProto3Optional(true).build();
    DescriptorProto doc = DescriptorProto.newBuilder().setName("Doc").addField(field).build();
    Field f = load(fileWith("doc.proto", doc)).messages().get(0).fields().get(0);

    assertEquals("java.util.UUID", f.javaType());
    assertTrue(f.isNullable());
  }

  @Test
  void plainStringFieldIsUnaffected() {
    DescriptorProto doc =
        DescriptorProto.newBuilder().setName("Doc").addField(stringField("id", 1)).build();
    Field f = load(fileWith("doc.proto", doc)).messages().get(0).fields().get(0);

    assertNull(f.javaType());
    assertEquals("java.lang.String", f.type().fqn());
    assertFalse(f.isNullable());
  }

  @Test
  void javaTypeOnInt32FailsLoud() {
    FieldDescriptorProto field =
        FieldDescriptorProto.newBuilder()
            .setName("n")
            .setNumber(1)
            .setLabel(Label.LABEL_OPTIONAL)
            .setType(Type.TYPE_INT32)
            .setOptions(javaTypeOption("java.math.BigInteger"))
            .build();
    DescriptorProto doc = DescriptorProto.newBuilder().setName("Doc").addField(field).build();

    assertJavaTypeError(
        assertThrows(IllegalStateException.class, () -> load(fileWith("doc.proto", doc))));
  }

  @Test
  void repeatedStringWithJavaTypeCarriesElementType() {
    FieldDescriptorProto field =
        stringField("tags", 1).toBuilder()
            .setLabel(Label.LABEL_REPEATED)
            .setOptions(javaTypeOption("java.util.UUID"))
            .build();
    DescriptorProto doc = DescriptorProto.newBuilder().setName("Doc").addField(field).build();
    Field f = load(fileWith("doc.proto", doc)).messages().get(0).fields().get(0);

    assertEquals("java.util.UUID", f.javaType());
    assertTrue(f.isRepeated());
    assertTrue(
        f.type().fqn().contains("List"),
        "the component type is a List of the named type: " + f.type().fqn());
    assertFalse(f.isNullable(), "a repeated format field's list is always present");
  }

  @Test
  void javaTypeOnMapFieldFailsLoud() {
    // A map is a synthetic repeated message field over a nested map-entry message (the descriptor
    // protoc emits); the option set on the repeated field must be rejected, not expanded.
    DescriptorProto entry =
        DescriptorProto.newBuilder()
            .setName("Entry")
            .setOptions(MessageOptions.newBuilder().setMapEntry(true).build())
            .addField(stringField("key", 1))
            .addField(stringField("value", 2))
            .build();
    FieldDescriptorProto pairs =
        FieldDescriptorProto.newBuilder()
            .setName("pairs")
            .setNumber(1)
            .setLabel(Label.LABEL_REPEATED)
            .setType(Type.TYPE_MESSAGE)
            .setTypeName("Entry")
            .setOptions(javaTypeOption("java.util.UUID"))
            .build();
    DescriptorProto doc =
        DescriptorProto.newBuilder().setName("Doc").addField(pairs).addNestedType(entry).build();

    assertJavaTypeError(
        assertThrows(IllegalStateException.class, () -> load(fileWith("doc.proto", doc))));
  }

  @Test
  void emptyJavaTypeValueFailsLoud() {
    DescriptorProto doc =
        DescriptorProto.newBuilder()
            .setName("Doc")
            .addField(fieldWithJavaType("id", 1, ""))
            .build();

    assertJavaTypeError(
        assertThrows(IllegalStateException.class, () -> load(fileWith("doc.proto", doc))));
  }

  @Test
  void javaTypeOnOneofMemberCarriesType() {
    FieldDescriptorProto member =
        fieldWithJavaType("id", 1, "java.net.URI").toBuilder().setOneofIndex(0).build();
    DescriptorProto doc =
        DescriptorProto.newBuilder()
            .setName("Doc")
            .addField(member)
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice").build())
            .build();
    ProtoFile file = load(fileWith("doc.proto", doc));
    Message m = file.messages().get(0);
    Field f = m.oneofs().get(0).members().get(0);

    assertEquals("java.net.URI", f.javaType());
    assertEquals("java.net.URI", f.type().fqn());
  }

  @Test
  void uninterpretedOptionPathResolves() {
    UninterpretedOption opt =
        UninterpretedOption.newBuilder()
            .addName(
                UninterpretedOption.NamePart.newBuilder()
                    .setNamePart("recordbuf")
                    .setIsExtension(false)
                    .build())
            .addName(
                UninterpretedOption.NamePart.newBuilder()
                    .setNamePart("java_type")
                    .setIsExtension(false)
                    .build())
            .setStringValue(ByteString.copyFromUtf8("java.net.URI"))
            .build();
    FieldDescriptorProto field =
        stringField("uri", 1).toBuilder()
            .setOptions(FieldOptions.newBuilder().addUninterpretedOption(opt).build())
            .build();
    DescriptorProto doc = DescriptorProto.newBuilder().setName("Doc").addField(field).build();
    Field f = load(fileWith("doc.proto", doc)).messages().get(0).fields().get(0);

    assertEquals("java.net.URI", f.javaType());
  }

  private static DescriptorProto messageWithProtobufJava(
      String name, FieldDescriptorProto... fields) {
    DescriptorProto.Builder b =
        DescriptorProto.newBuilder()
            .setName(name)
            .setOptions(
                MessageOptions.newBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(
                                MESSAGE_PROTOBUF_JAVA_TAG,
                                UnknownFieldSet.Field.newBuilder()
                                    .addLengthDelimited(ByteString.copyFromUtf8("pkg.ref"))
                                    .build())
                            .build())
                    .build());
    for (FieldDescriptorProto field : fields) b.addField(field);
    return b.build();
  }

  @Test
  void bareStringJavaTypeWithProtobufJavaReferenceFailsLoud() {
    // A bare proto3 string has no presence getter on the protobuf-java reference, so the
    // Interop converter could not tell absent (null) from an explicit empty (fail loud) -- the
    // option is rejected rather than silently lossy.
    DescriptorProto doc =
        messageWithProtobufJava("Doc", fieldWithJavaType("id", 1, "java.util.UUID"));
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> load(fileWith("doc.proto", doc)));
    String msg = e.getMessage();
    assertNotNull(msg);
    assertTrue(msg.contains("`optional"), msg);
  }

  @Test
  void optionalStringJavaTypeWithProtobufJavaReferenceIsAccepted() {
    FieldDescriptorProto field =
        fieldWithJavaType("id", 1, "java.util.UUID").toBuilder().setProto3Optional(true).build();
    DescriptorProto doc = messageWithProtobufJava("Doc", field);
    Field f = load(fileWith("doc.proto", doc)).messages().get(0).fields().get(0);
    assertEquals("java.util.UUID", f.javaType());
  }
}
