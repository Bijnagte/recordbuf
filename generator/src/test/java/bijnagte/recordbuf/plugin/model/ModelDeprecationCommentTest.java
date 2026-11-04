package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueOptions;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end model-layer carry-through of deprecation + leading-comment text from {@code
 * source_code_info} down to the model. This locks:
 *
 * <ul>
 *   <li>{@code Field.deprecated()} reads the field's {@code FieldOptions.deprecated} flag.
 *   <li>{@code Message.deprecated()} reads the message's {@code MessageOptions.deprecated} flag.
 *   <li>{@code Enum.EnumConstant.deprecated()} reads the enum constant's {@code
 *       FieldOptions}-equivalent {@code EnumValueOptions.deprecated} flag.
 *   <li>{@code Message.comment()} populates from {@code source_code_info} leading comments ({@code
 *       Field.comment()} / {@code EnumConstant.comment()} follow the same lookup helper).
 * </ul>
 *
 * <p>The map-field carry-through is also exercised via an end-to-end interop fixture ({@code
 * annotations.proto}): its {@code @Deprecated Map<String, Integer> tags} component proves {@code
 * buildMapField} carries the user's deprecation through to the synthesized map field.
 */
class ModelDeprecationCommentTest {

  private static final String JAVA_PACKAGE = "com.example";

  // --- builders ----

  private static FieldDescriptorProto scalarField(int num, String name, boolean deprecated) {
    var f =
        FieldDescriptorProto.newBuilder()
            .setNumber(num)
            .setName(name)
            .setType(Type.TYPE_INT32)
            .setLabel(Label.LABEL_OPTIONAL);
    if (deprecated) f.setOptions(FieldOptions.newBuilder().setDeprecated(true).build());
    return f.build();
  }

  private static ProtoFile loadMessageFile(DescriptorProto msg, SourceCodeInfo sci) {
    FileDescriptorProto.Builder fd =
        FileDescriptorProto.newBuilder()
            .setName("test.proto")
            .setPackage("pkg")
            .setSyntax("proto3")
            .addMessageType(msg)
            .setSourceCodeInfo(sci);
    return new ModelLoader(new FileTable(), JAVA_PACKAGE).load(fd.build());
  }

  /** Load a message file with no source-code info (no comments). */
  private static ProtoFile loadMessageFile(DescriptorProto msg) {
    return loadMessageFile(msg, SourceCodeInfo.getDefaultInstance());
  }

  private static ProtoFile loadEnumFile(EnumDescriptorProto e) {
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("enum_test.proto")
            .setPackage("pkg")
            .setSyntax("proto3")
            .addEnumType(e)
            .build();
    return new ModelLoader(new FileTable(), JAVA_PACKAGE).load(fd);
  }

  // --- behavior ----

  @Test
  void scalarFieldWithoutOptionsHasNoDeprecationNorComment() {
    Message m =
        loadMessageFile(
                DescriptorProto.newBuilder()
                    .setName("Sample")
                    .addField(scalarField(1, "id", false))
                    .build())
            .messages()
            .get(0);
    Field id = m.fields().get(0);
    assertFalse(id.deprecated(), "a scalar field without @Deprecated must not be deprecated");
    assertNull(id.comment(), "a field with no leading comment has null comment");
    assertFalse(m.deprecated(), "a message without @Deprecated must not be deprecated");
    assertNull(m.comment(), "a message with no leading comment has null comment");
  }

  @Test
  void loadFieldPopulatesDeprecatedFlagFromFieldOptions() {
    Message m =
        loadMessageFile(
                DescriptorProto.newBuilder()
                    .setName("Sample")
                    .addField(scalarField(2, "old", true))
                    .build())
            .messages()
            .get(0);
    Field old = m.fields().get(0);
    assertTrue(
        old.deprecated(), "a FieldDescriptorProto with FieldOptions.deprecated=true must surface");
    assertEquals("old", old.javaName());
  }

  @Test
  void loadMessagePopulatesDeprecatedFlagFromMessageOptions() {
    Message m =
        loadMessageFile(
                DescriptorProto.newBuilder()
                    .setName("Legacy")
                    .setOptions(MessageOptions.newBuilder().setDeprecated(true).build())
                    .addField(scalarField(1, "x", false))
                    .build())
            .messages()
            .get(0);
    assertTrue(
        m.deprecated(), "a DescriptorProto with MessageOptions.deprecated=true must surface");
  }

  @Test
  void loadEnumConstantSurfacesDeprecatedFlag() {
    Enum out =
        loadEnumFile(
                EnumDescriptorProto.newBuilder()
                    .setName("Color")
                    .addValue(
                        EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0).build())
                    .addValue(
                        EnumValueDescriptorProto.newBuilder()
                            .setName("LEGACY")
                            .setNumber(1)
                            .setOptions(EnumValueOptions.newBuilder().setDeprecated(true).build())
                            .build())
                    .build())
            .enums()
            .get(0);
    assertEquals(
        List.of("RED", "LEGACY"), out.constants().stream().map(Enum.EnumConstant::name).toList());
    Enum.EnumConstant red = out.constants().get(0);
    Enum.EnumConstant legacy = out.constants().get(1);
    assertFalse(red.deprecated(), "a non-deprecated enum constant must not surface");
    assertTrue(
        legacy.deprecated(),
        "an EnumValueDescriptorProto with FieldOptions.deprecated=true must surface");
  }

  @Test
  void loadMessageSurfacesLeadingCommentFromSourceCodeInfo() {
    SourceCodeInfo sci =
        SourceCodeInfo.newBuilder()
            .addLocation(
                SourceCodeInfo.Location.newBuilder()
                    // A top-level message at index 0 has path [4, 0].
                    .addPath(4)
                    .addPath(0)
                    .setLeadingComments("A documented message.\n")
                    .build())
            .build();
    Message m =
        loadMessageFile(
                DescriptorProto.newBuilder()
                    .setName("Sample")
                    .addField(scalarField(1, "id", false))
                    .build(),
                sci)
            .messages()
            .get(0);
    assertEquals("A documented message.\n", m.comment());
  }
}
