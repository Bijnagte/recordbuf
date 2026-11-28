package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofOptions;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * U7 (2026-11-21-005): the flat JSON surface of a oneof on a jackson file -- the unwrapped
 * component, the renamed wrapper values (with their member's (de)serializer pair), the
 * discriminator method, and the {@code @JsonCreator} factory -- and its absence on an opt-out file.
 */
class OneofJsonSurfaceTest {

  private static final String FILE = "event.proto";

  /**
   * {@code message Event { int32 id = 1; oneof when { Timestamp at = 2; bytes blob = 3; string
   * label = 4; } }}, optionally with a discriminator override and the Jackson opt-out.
   */
  private static FileDescriptorProto event(
      boolean jackson, @Nullable String discriminator, String extraField) {
    OneofDescriptorProto.Builder when = OneofDescriptorProto.newBuilder().setName("when");
    if (discriminator != null) {
      when.setOptions(
          OneofOptions.newBuilder()
              .setUnknownFields(
                  UnknownFieldSet.newBuilder()
                      .addField(
                          50008,
                          UnknownFieldSet.Field.newBuilder()
                              .addLengthDelimited(ByteString.copyFromUtf8(discriminator))
                              .build())
                      .build()));
    }
    DescriptorProto.Builder msg =
        DescriptorProto.newBuilder()
            .setName("Event")
            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_INT32))
            .addField(
                field("at", 2, FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".google.protobuf.Timestamp")
                    .setOneofIndex(0))
            .addField(field("blob", 3, FieldDescriptorProto.Type.TYPE_BYTES).setOneofIndex(0))
            .addField(field("label", 4, FieldDescriptorProto.Type.TYPE_STRING).setOneofIndex(0))
            .addOneofDecl(when);
    if (!extraField.isEmpty()) {
      msg.addField(field(extraField, 5, FieldDescriptorProto.Type.TYPE_STRING));
    }
    FileDescriptorProto.Builder file =
        FileDescriptorProto.newBuilder()
            .setName(FILE)
            .setPackage("com.example")
            .setSyntax("proto3")
            .addDependency("google/protobuf/timestamp.proto")
            .addMessageType(msg);
    if (!jackson) {
      file.setOptions(
          FileOptions.newBuilder()
              .setUnknownFields(
                  UnknownFieldSet.newBuilder()
                      .addField(50004, UnknownFieldSet.Field.newBuilder().addVarint(0).build())
                      .build()));
    }
    return file.build();
  }

  private static FieldDescriptorProto.Builder field(
      String name, int number, FieldDescriptorProto.Type type) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(type);
  }

  /** The generated Event.java, whitespace collapsed so annotations read as single lines. */
  private static String render(FileDescriptorProto file) {
    CodeGeneratorResponse resp =
        new CodeGenerator()
            .generate(
                CodeGeneratorRequest.newBuilder()
                    .addProtoFile(
                        FileDescriptorProto.newBuilder()
                            .setName("google/protobuf/timestamp.proto")
                            .setPackage("google.protobuf")
                            .addMessageType(DescriptorProto.newBuilder().setName("Timestamp")))
                    .addProtoFile(file)
                    .addFileToGenerate(FILE)
                    .setParameter("avro=off")
                    .build());
    if (resp.hasError()) {
      throw new IllegalStateException(resp.getError());
    }
    for (CodeGeneratorResponse.File f : resp.getFileList()) {
      if (f.getName().endsWith("/Event.java")) {
        return f.getContent().replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
      }
    }
    throw new AssertionError("no Event.java in " + resp.getFileList());
  }

  @Test
  void componentIsUnwrappedAndWrappersCarryTheMemberNameAndPair() {
    String src = render(event(true, null, ""));

    assertTrue(
        src.contains("@JsonUnwrapped @JsonProperty(\"when$\") @Nullable When when"),
        "the oneof component is @JsonUnwrapped and carries an internal @JsonProperty so the bare"
            + " oneof name is free for the discriminator: "
            + src);
    assertTrue(
        src.contains(
            "record AtValue(@JsonProperty(\"at\") @JsonSerialize(using ="
                + " JavaTimeTemporal.InstantIsoSerializer.class"),
        "a Timestamp member carries the temporal pair: " + src);
    assertTrue(
        src.contains(
            "record BlobValue(@JsonProperty(\"blob\") @JsonSerialize(using ="
                + " JsonBytes.B64Serializer.class"),
        "a bytes member carries the base64 pair: " + src);
    assertTrue(src.contains("record LabelValue(@JsonProperty(\"label\") String value)"), src);
  }

  @Test
  void jacksonRecordsOmitNullProperties() {
    assertTrue(
        render(event(true, null, ""))
            .contains("@JsonInclude(JsonInclude.Include.NON_NULL) public record Event("));
    assertFalse(render(event(false, null, "")).contains("JsonInclude"));
  }

  @Test
  void discriminatorDefaultsToTheOneofNameAndHonorsTheOverride() {
    String byDefault = render(event(true, null, ""));
    assertTrue(
        byDefault.contains("@JsonProperty(\"when\") @Nullable" + " public String whenType()"),
        byDefault);

    String overridden = render(event(true, "kind", ""));
    assertTrue(
        overridden.contains("@JsonProperty(\"kind\") @Nullable" + " public String whenType()"),
        overridden);
    assertTrue(
        overridden.contains("@JsonProperty(\"kind\") @Nullable String whenType,"),
        "the factory binds the overridden property: " + overridden);
  }

  @Test
  void factoryTakesFieldsMembersDiscriminatorAndExtras() {
    String src = render(event(true, null, ""));

    assertTrue(
        src.contains(
            "@JsonCreator static Event fromJson(@JsonProperty(\"id\") int id, @JsonProperty(\"when\") @Nullable String whenType, @JsonProperty(\"at\")"
                + " @JsonSerialize(using = JavaTimeTemporal.InstantIsoSerializer.class"),
        src);
    assertTrue(
        src.contains(
            "@JsonProperty(\"blob\") @JsonSerialize(using = JsonBytes.B64Serializer.class,"
                + " contentUsing = JsonBytes.B64Serializer.class) @JsonDeserialize(using ="
                + " JsonBytes.B64Deserializer.class, contentUsing ="
                + " JsonBytes.B64Deserializer.class) @Nullable Bytes blob,"),
        src);
    assertTrue(
        src.contains(
            "@JsonProperty(\"label\") @Nullable String label, @JsonAnySetter @Nullable"
                + " Map<String, JsonNode> jsonExtras)"),
        src);
    assertTrue(
        src.contains(
            "JsonOneof.select(\"when\", whenType, List.of(\"at\", \"blob\", \"label\"), at, blob,"
                + " label)"),
        src);
    assertTrue(src.contains("return new Event(id, when, jsonExtras, UnknownFields.empty());"), src);
  }

  @Test
  void optOutFileRendersNoOneofJsonSurface() {
    String src = render(event(false, null, ""));

    assertFalse(src.contains("JsonUnwrapped"), src);
    assertFalse(src.contains("fromJson"), src);
    assertFalse(src.contains("whenType"), src);
    assertTrue(src.contains("record LabelValue(String value)"), src);
  }

  @Test
  void discriminatorCollidingWithAFieldFailsGeneration() {
    var e = assertThrows(IllegalStateException.class, () -> render(event(true, null, "when")));

    assertTrue(
        String.valueOf(e.getMessage()).contains("collides with a field's JSON name"),
        e.getMessage());
  }

  /**
   * A field whose accessor is already {@code whenType()} only collides with the discriminator's
   * Java method, not its JSON property once json_oneof renames that: the method steps aside.
   */
  @Test
  void javaOnlyCollisionRenamesTheDiscriminatorMethodInsteadOfFailing() {
    // field when_type: Java accessor whenType(), JSON name whenType; discriminator property "kind"
    String src = render(event(true, "kind", "when_type"));

    assertTrue(src.contains("@JsonProperty(\"kind\") @Nullable public String whenJsonType()"), src);
    assertTrue(src.contains("@JsonProperty(\"kind\") @Nullable String whenJsonType,"), src);
    assertTrue(src.contains("JsonOneof.select(\"when\", whenJsonType,"), src);
  }
}
