package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import bijnagte.recordbuf.plugin.types.WellKnownTypes;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofOptions;
import com.google.protobuf.DescriptorProtos.UninterpretedOption;
import com.google.protobuf.UnknownFieldSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * U2 (2026-11-20 Jackson plan): the per-file {@code recordbuf.jackson} toggle — ON by default, an
 * explicit false opts out — and the per-field / per-oneof knobs the model carries for the Jackson
 * JSON surface, read from both the compiled (unknown-field) and uninterpreted option paths.
 */
@SuppressWarnings("BadImport")
class JacksonOptionsTest {

  // --- file toggle ---

  private static ProtoFile fileWithToggleVarint(int control) throws Exception {
    UnknownFieldSet uf =
        UnknownFieldSet.newBuilder()
            .addField(50004, UnknownFieldSet.Field.newBuilder().addVarint(control).build())
            .build();
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("f.proto")
            .setOptions(FileOptions.newBuilder().mergeFrom(uf.toByteArray()))
            .build();
    return new ModelLoader(new FileTable(""), "pkg").load(fd);
  }

  private static UninterpretedOption opt(String... parts) {
    UninterpretedOption.Builder b = UninterpretedOption.newBuilder();
    for (String p : parts) {
      b.addName(UninterpretedOption.NamePart.newBuilder().setNamePart(p).setIsExtension(false));
    }
    return b.build();
  }

  private static ProtoFile fileWithToggleUninterpreted(String value) {
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("f.proto")
            .setOptions(
                FileOptions.newBuilder()
                    .addUninterpretedOption(
                        opt("recordbuf", "jackson").toBuilder()
                            .setStringValue(ByteString.copyFromUtf8(value)))
                    .build())
            .build();
    return new ModelLoader(new FileTable(""), "pkg").load(fd);
  }

  @Test
  void fileToggleVarintPath() throws Exception {
    assertTrue(fileWithToggleVarint(1).jackson());
    assertEquals(false, fileWithToggleVarint(0).jackson());
    // absent → true (default ON: a file without the option generates the Jackson surface; only an
    // explicit false opts out).
    FileDescriptorProto fd = FileDescriptorProto.newBuilder().setName("f.proto").build();
    assertTrue(new ModelLoader(new FileTable(""), "pkg").load(fd).jackson());
  }

  @Test
  void fileToggleUninterpretedPath() {
    assertTrue(fileWithToggleUninterpreted("true").jackson());
    assertEquals(false, fileWithToggleUninterpreted("false").jackson());
  }

  // --- per-field jackson_format ---

  private static FieldDescriptorProto fieldWithFormatVarint(@Nullable String value)
      throws Exception {
    FieldDescriptorProto.Builder b =
        FieldDescriptorProto.newBuilder()
            .setName("stamp")
            .setNumber(1)
            .setLabel(Label.LABEL_OPTIONAL)
            .setType(Type.TYPE_MESSAGE)
            .setTypeName(".google.protobuf.Timestamp");
    if (value != null) {
      UnknownFieldSet uf =
          UnknownFieldSet.newBuilder()
              .addField(
                  50005,
                  UnknownFieldSet.Field.newBuilder()
                      .addLengthDelimited(ByteString.copyFromUtf8(value))
                      .build())
              .build();
      b.setOptions(FieldOptions.newBuilder().mergeFrom(uf.toByteArray()));
    }
    return b.build();
  }

  private static FileDescriptorProto fileWithField(DescriptorProto msg, boolean jacksonEnabled)
      throws Exception {
    FileOptions.Builder fileOpts = FileOptions.newBuilder();
    // Always write the file toggle explicitly (1 = on, 0 = explicit opt-out): with the default-ON
    // resolution, "absent" and "false" are no longer equivalent.
    UnknownFieldSet uf =
        UnknownFieldSet.newBuilder()
            .addField(
                50004, UnknownFieldSet.Field.newBuilder().addVarint(jacksonEnabled ? 1 : 0).build())
            .build();
    fileOpts.mergeFrom(uf.toByteArray());
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("f.proto")
            .setPackage("pkg")
            .setOptions(fileOpts.build())
            .addMessageType(msg)
            .build();
    return fd;
  }

  private static Field loadFieldWithFormatVarint(@Nullable String value, boolean enabled)
      throws Exception {
    DescriptorProto msg =
        DescriptorProto.newBuilder().setName("Doc").addField(fieldWithFormatVarint(value)).build();
    ProtoFile pf = new ModelLoader(new FileTable(""), "pkg").load(fileWithField(msg, enabled));
    return pf.messages().get(0).fields().get(0);
  }

  @Test
  void fieldFormatResolvesOnEnabledFile() throws Exception {
    assertEquals(
        JacksonTemporalFormat.CANONICAL,
        loadFieldWithFormatVarint("canonical", true).jacksonFormat());
    assertEquals(
        JacksonTemporalFormat.JSON, loadFieldWithFormatVarint("json", true).jacksonFormat());
    // absent → null (the writer applies the default rendering).
    assertNull(loadFieldWithFormatVarint(null, true).jacksonFormat());
  }

  @Test
  void fieldFormatWithExplicitOptOutFailsLoud() throws Exception {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> loadFieldWithFormatVarint("canonical", false));
    assertThatFormatError(e);
  }

  private static void assertThatFormatError(IllegalStateException e) {
    String m = e.getMessage();
    assertNotNull(m, "exception message should be set");
    assertTrue(m.contains("recordbuf.jackson_format"), m);
  }

  @Test
  void unknownFieldFormatFailsLoud() throws Exception {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> loadFieldWithFormatVarint("bogus", true));
    assertThatFormatError(e);
  }

  // --- per-oneof json_oneof ---

  private static OneofDescriptorProto oneofWithDiscriminatorUninterpreted(
      String name, String value) {
    OneofOptions opts =
        OneofOptions.newBuilder()
            .addUninterpretedOption(
                opt("recordbuf", "json_oneof").toBuilder()
                    .setStringValue(ByteString.copyFromUtf8(value)))
            .build();
    return OneofDescriptorProto.newBuilder().setName(name).setOptions(opts).build();
  }

  private static Oneof loadOneof(String name, String discriminator, boolean enabled)
      throws Exception {
    DescriptorProto msg =
        DescriptorProto.newBuilder()
            .setName("Doc")
            .addOneofDecl(oneofWithDiscriminatorUninterpreted(name, discriminator))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("a")
                    .setNumber(1)
                    .setLabel(Label.LABEL_OPTIONAL)
                    .setType(Type.TYPE_STRING)
                    .setOneofIndex(0)
                    .build())
            .build();
    ProtoFile pf = new ModelLoader(new FileTable(""), "pkg").load(fileWithField(msg, enabled));
    return pf.messages().get(0).oneofs().get(0);
  }

  @Test
  void oneofDiscriminatorResolvesOnEnabledFile() throws Exception {
    assertEquals("kind", loadOneof("choice", "kind", true).jsonOneof());
  }

  @Test
  void oneofDiscriminatorWithExplicitOptOutFailsLoud() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> loadOneof("choice", "kind", false));
    String m = e.getMessage();
    assertNotNull(m, "exception message should be set");
    assertTrue(m.contains("recordbuf.json_oneof"), m);
  }

  // --- the dynamic-WKT node table (U2 contract) ---

  @Test
  void nodeTableMapsDynamicWkts() {
    assertNodeFqn(TypeRefs.OBJECT_NODE.fqn(), "google.protobuf.Struct");
    assertNodeFqn(TypeRefs.JSON_NODE.fqn(), "google.protobuf.Value");
    assertNodeFqn(TypeRefs.ARRAY_NODE.fqn(), "google.protobuf.ListValue");
    assertNodeFqn(TypeRefs.JSON_NODE.fqn(), "google.protobuf.Any");
    assertNodeFqn(TypeRefs.NULL_NODE.fqn(), "google.protobuf.NullValue");
    // non-dynamic WKTs keep their native types: no node type.
    assertNull(WellKnownTypes.nodeType("google.protobuf.Timestamp"));
    assertNull(WellKnownTypes.nodeType("com.example.Foo"));
  }

  private static void assertNodeFqn(String expectedFqn, String wkt) {
    TypeRef got = WellKnownTypes.nodeType(wkt);
    assertNotNull(got, "expected a node type for " + wkt);
    assertEquals(expectedFqn, got.fqn());
  }
}
