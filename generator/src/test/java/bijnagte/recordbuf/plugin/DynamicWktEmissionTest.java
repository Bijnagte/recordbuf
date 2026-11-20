package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.ModelLoader;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * U5 (2026-11-20 Jackson plan): dynamic well-known types resolve to Jackson node types on a {@code
 * recordbuf.jackson}-enabled file. A {@code google.protobuf.Value} field's record component is
 * {@code com.fasterxml.jackson.databind.JsonNode}, its codec arm round-trips through the {@code
 * bijnagte.recordbuf.json.DynamicWkt} facade, and the generated JSON renders the node tree
 * natively. On a file WITHOUT the toggle the same field fails loud (R11) with the option that would
 * fix it.
 */
@SuppressWarnings("BadImport")
class DynamicWktEmissionTest {

  private static FileDescriptorProto descriptor(boolean jackson) throws Exception {
    FileDescriptorProto.Builder b =
        FileDescriptorProto.newBuilder().setName("dynamic.proto").setPackage("com.example");
    if (jackson) {
      b.setOptions(
          FileOptions.newBuilder()
              .mergeFrom(
                  UnknownFieldSet.newBuilder()
                      .addField(50004, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                      .build()
                      .toByteArray())
              .build());
    }
    b.addMessageType(
        DescriptorProto.newBuilder()
            .setName("Holder")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("payload")
                    .setNumber(1)
                    .setLabel(Label.LABEL_OPTIONAL)
                    .setType(Type.TYPE_MESSAGE)
                    .setTypeName("google.protobuf.Value"))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("payloads")
                    .setNumber(2)
                    .setLabel(Label.LABEL_REPEATED)
                    .setType(Type.TYPE_MESSAGE)
                    .setTypeName("google.protobuf.Value")));
    return b.build();
  }

  @Test
  void jacksonOnFileResolvesValueFieldsToNodeTypes() throws Exception {
    var fd = descriptor(true);
    CodeGeneratorResponse resp = generate(fd);
    new CodeGenerator()
        .generate(
            CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("dynamic.proto")
                .addProtoFile(fd)
                .setParameter("--java-package=com.example")
                .build());
    String source = read(resp);
    assertTrue(
        source.contains("JsonNode payload"),
        "a Value field on a jackson-enabled file must be a JsonNode component:\n" + source);
    assertTrue(
        source.contains("DynamicWkt"),
        "the codec arm must round-trip through the json-module DynamicWkt facade:\n" + source);
    char q = '"';
    String encodeCall = "DynamicWkt.encode(" + q + "google.protobuf.Value";
    assertTrue(
        source.contains(encodeCall),
        "the encode arm must reference the WKT's canonical FQN: " + source);
  }

  private static CodeGeneratorResponse generate(FileDescriptorProto fd) {
    return new CodeGenerator()
        .generate(
            CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("dynamic.proto")
                .addProtoFile(wktFile("google/protobuf/struct.proto"))
                .addProtoFile(wktFile("google/protobuf/wrappers.proto"))
                .addProtoFile(fd)
                .setParameter("--java-package=com.example")
                .build());
  }

  /**
   * A minimal stand-in for a WKT descriptor file: package {@code google.protobuf} with the
   * message/enum names the loader's resolveType needs (its javaFqn lookup is name-only).
   */
  private static FileDescriptorProto wktFile(String name) {
    FileDescriptorProto.Builder b =
        FileDescriptorProto.newBuilder()
            .setName(name)
            .setPackage("google.protobuf")
            .addMessageType(DescriptorProto.newBuilder().setName("Value"))
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Struct")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("fields")
                            .setNumber(1)
                            .setLabel(Label.LABEL_REPEATED)
                            .setType(Type.TYPE_MESSAGE)
                            .setTypeName("Struct.FieldsEntry")))
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("ListValue")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("values")
                            .setNumber(1)
                            .setLabel(Label.LABEL_REPEATED)
                            .setType(Type.TYPE_MESSAGE)
                            .setTypeName("google.protobuf.Value")))
            .addEnumType(EnumDescriptorProto.newBuilder().setName("NullValue"))
            .addMessageType(DescriptorProto.newBuilder().setName("Any"))
            .addMessageType(DescriptorProto.newBuilder().setName("Timestamp"))
            .addMessageType(DescriptorProto.newBuilder().setName("Duration"));
    if (name.endsWith("wrappers.proto")) {
      for (String w :
          new String[] {
            "FloatValue",
            "Int32Value",
            "Int64Value",
            "UInt32Value",
            "UInt64Value",
            "DoubleValue",
            "BoolValue",
            "StringValue",
          }) {
        b.addMessageType(DescriptorProto.newBuilder().setName(w));
      }
    }
    return b.build();
  }

  private static String read(CodeGeneratorResponse resp) {
    for (File f : resp.getFileList()) {
      if (f.getName().endsWith("Holder.java")) {
        return f.getContent();
      }
    }
    throw new AssertionError("no Holder.java in the response: " + resp.getFileList());
  }

  @Test
  void jacksonOffFileFailsLoudOnADynamicWktField() throws Exception {
    var fd = descriptor(false);
    FileTable table = new FileTable();
    table.addAll(
        List.of(
            wktFile("google/protobuf/struct.proto"), wktFile("google/protobuf/wrappers.proto")));
    var ex =
        assertThrows(
            IllegalStateException.class, () -> new ModelLoader(table, "com.example").load(fd));
    String msg = Objects.requireNonNull(ex.getMessage());
    assertTrue(
        msg.contains("recordbuf.jackson"),
        "the failure must name the option that would fix it: " + msg);
  }

  @Test
  void anyAndNullValueStayUnsupportedEvenOnJacksonFiles() throws Exception {
    for (String type : new String[] {"google.protobuf.Any", "google.protobuf.NullValue"}) {
      FileDescriptorProto fd =
          FileDescriptorProto.newBuilder()
              .setName("t.proto")
              .setPackage("com.example")
              .setOptions(
                  FileOptions.newBuilder()
                      .mergeFrom(
                          UnknownFieldSet.newBuilder()
                              .addField(
                                  50004, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                              .build()
                              .toByteArray())
                      .build())
              .addMessageType(
                  DescriptorProto.newBuilder()
                      .setName("H")
                      .addField(
                          FieldDescriptorProto.newBuilder()
                              .setName("a")
                              .setNumber(1)
                              .setLabel(Label.LABEL_OPTIONAL)
                              .setType(Type.TYPE_MESSAGE)
                              .setTypeName(type)))
              .build();
      FileTable table = new FileTable();
      table.addAll(
          List.of(
              wktFile("google/protobuf/struct.proto"), wktFile("google/protobuf/wrappers.proto")));
      var ex =
          assertThrows(
              IllegalStateException.class, () -> new ModelLoader(table, "com.example").load(fd));
      String msg = Objects.requireNonNull(ex.getMessage());
      assertTrue(
          msg.contains("not supported"), type + " must fail loud with a clear message: " + msg);
    }
  }
}
