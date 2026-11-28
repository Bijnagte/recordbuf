package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.Javadoc;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldBuilder;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.model.OneofBuilder;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * U3 (record surface) of the java_type format-fields plan: a singular {@code string} field whose
 * {@code recordbuf.java_type} option names a Java type renders that type — not {@code String} — as
 * its record component, {@code @Nullable} (a format field is always presentable), and a oneof
 * member's wrapper record carries the named type and implements {@code Unwrapable} with it. The
 * model is built directly (the loader path is covered by {@code JavaTypeOptionTest}); the rendered
 * source is asserted structurally — no in-memory compile, because the {@code @Nullable} (JSpecify)
 * and {@code Unwrapable} (runtime) references need classpaths this module's plain test classpath
 * deliberately does not carry (compileOnly / R22), and the full compile happens in the fixture
 * matrix once the codec surface lands.
 */
class JavaTypeRecordSurfaceTest {

  private static final String PKG = "bijnagte.recordbuf.plugin.writer.javatype";

  /**
   * A singular string format field: {@code type} carries the option's named type (U1 model rule).
   */
  private static Field formatField(String name, int number, String javaType) {
    return FieldBuilder.builder()
        .name(name)
        .javaName(name)
        .number(number)
        .label(FieldLabel.OPTIONAL)
        .kind(FieldKind.SCALAR)
        .typeUrl("string")
        .type(TypeRef.of(javaType))
        .proto3Optional(false)
        .jsonName(name)
        .javaType(javaType)
        .build();
  }

  private static String render(Message m) {
    TypeCollector ci = new TypeCollector(PKG);
    RecordSpec rec = RecordSourceMapping.toSource(m, PKG);
    return SourceRenderer.render(new JavaFile(PKG, Javadoc.builder().build(), rec, ci));
  }

  private static Message message(List<Field> fields, List<Oneof> oneofs) {
    return new Message(
        "IdField",
        TypeRef.named(PKG, "IdField"),
        fields,
        List.of(),
        List.of(),
        oneofs,
        false,
        null,
        List.of(),
        null);
  }

  @Test
  void format_field_component_uses_the_named_type_and_is_nullable() {
    String src = render(message(List.of(formatField("uid", 1, "java.util.UUID")), List.of()));

    assertTrue(src.contains("@Nullable"), "the component is annotated @Nullable: " + src);
    assertTrue(src.contains("UUID uid"), "the component is the named type: " + src);
    assertTrue(src.contains("import java.util.UUID;"), "the named type is imported: " + src);
    assertFalse(src.contains("String uid"), "no String component for a format field: " + src);
  }

  @Test
  void format_field_oneof_wrapper_carries_the_named_type() {
    Field member = formatField("uid", 1, "java.util.UUID");
    Oneof oneof =
        OneofBuilder.builder()
            .name("id")
            .iface(TypeRef.named(PKG, "IdField.Id"))
            .descriptor(OneofDescriptorProto.getDefaultInstance())
            .parentDescriptor(DescriptorProto.getDefaultInstance())
            .members(List.of(member))
            .build();
    String src = render(message(List.of(), List.of(oneof)));

    // The oneof's own component stays @Nullable (presence belongs to the oneof)...
    assertTrue(src.contains("@Nullable"), "the oneof component is @Nullable: " + src);
    assertTrue(src.contains("IdField.Id id"), "the oneof component: " + src);
    // ...while the wrapper record's value component is the bare named type (non-null).
    assertTrue(src.contains("record UidValue("), "the wrapper record is named <Cap>Value: " + src);
    assertTrue(src.contains("UUID value"), "the wrapper holds the named type: " + src);
    assertTrue(
        src.contains("Unwrapable<UUID>"),
        "the wrapper implements Unwrapable with the named type: " + src);
    assertTrue(src.contains("import java.util.UUID;"), "the named type is imported: " + src);
  }

  private static String renderWithCodec(Message m) {
    TypeCollector ci = new TypeCollector(PKG);
    RecordSpec rec = RecordSourceMapping.toSourceWithCodec(m, false);
    return SourceRenderer.render(new JavaFile(PKG, Javadoc.builder().build(), rec, ci));
  }

  @Test
  void format_field_codec_parses_and_formats_through_the_registry() {
    String src =
        renderWithCodec(message(List.of(formatField("uid", 1, "java.util.UUID")), List.of()));

    // Decode: the wire stays STRING, parsed into the named type through the registry.
    assertTrue(
        src.contains("FormatRegistry.format(UUID.class).parse(in.readStringRequireUtf8())"),
        "the decode arm parses the wire string into the named type: " + src);
    // Encode: the value is formatted back to its wire string through the registry.
    assertTrue(
        src.contains("FormatRegistry.format(UUID.class).format(v.uid())"),
        "the encode arm formats the value through the registry: " + src);
    // Size: the sizing pass asks the format for the rendered string's byte count, never rendering
    // a fixed-size value itself.
    assertTrue(
        src.contains("Wire.lenDelimSize(1, FormatRegistry.format(UUID.class).wireBytes(v.uid()))"),
        "the size arm measures through wireBytes: " + src);
    assertFalse(
        src.contains("computeStringSize(1, FormatRegistry"),
        "the size arm does not render the value: " + src);
    assertTrue(
        src.contains("import bijnagte.recordbuf.runtime.format.FormatRegistry;"),
        "the registry is imported: " + src);
    assertFalse(
        src.contains("out.writeString(1, v.uid())"),
        "the encode never bypasses the format: " + src);
  }

  @Test
  void format_field_interop_converts_through_the_registry() {
    // toInterop needs the protobuf-java reference package; the plain message() helper passes null.
    Message m =
        new Message(
            "IdField",
            TypeRef.named(PKG, "IdField"),
            List.of(formatField("uid", 1, "java.util.UUID")),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            "com.example.ref");
    String src =
        SourceRenderer.render(
            new JavaFile(
                PKG,
                Javadoc.builder().build(),
                InteropSourceMapping.toInterop(m, "id.proto"),
                new TypeCollector(PKG)));

    // from: absent maps to null; a present wire string parses through the registry.
    assertTrue(
        src.contains("FormatRegistry.format(UUID.class).parse(x.getUid())"),
        "from parses the present wire string through the registry: " + src);
    // to: the present value formats through the registry (the null guard skips the absent case).
    assertTrue(
        src.contains("FormatRegistry.format(UUID.class).format(r.uid())"),
        "to formats the value through the registry: " + src);
    assertTrue(
        src.contains("import bijnagte.recordbuf.runtime.format.FormatRegistry;"),
        "the registry is imported: " + src);
  }

  @Test
  void format_field_jackson_emits_the_constant_pair() {
    String src =
        SourceRenderer.render(
            new JavaFile(
                PKG,
                Javadoc.builder().build(),
                RecordSourceMapping.toSource(
                    message(List.of(formatField("uid", 1, "java.util.UUID")), List.of()),
                    PKG,
                    true),
                new TypeCollector(PKG)));

    // The constant JsonFormatField pair, on every format field — no per-field class is generated.
    assertTrue(
        src.contains("JsonFormatField.FormatSerializer.class"),
        "the serializer half of the constant pair: " + src);
    assertTrue(
        src.contains("JsonFormatField.FormatDeserializer.class"),
        "the deserializer half of the constant pair: " + src);
    assertTrue(
        src.contains("import bijnagte.recordbuf.json.JsonFormatField;"),
        "the pair's enclosing class is imported: " + src);
  }
}
