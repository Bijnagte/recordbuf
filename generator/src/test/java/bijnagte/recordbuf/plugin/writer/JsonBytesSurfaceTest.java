package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.SourceRenderer;
import bijnagte.recordbuf.codegen.TypeCollector;
import bijnagte.recordbuf.codegen.source.JavaFile;
import bijnagte.recordbuf.codegen.source.RecordSpec;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.GoogleJavaFormatter;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The proto3 base64 {@code bytes} pair on generated records (2026-11-21-003): every {@code
 * bytes}-family component of a jackson file pins {@code JsonBytes}' (de)serializers — {@code using}
 * + {@code contentUsing} when singular, {@code contentUsing} only for a repeated or map value.
 */
final class JsonBytesSurfaceTest {

  private static final String SER = "JsonBytes.B64Serializer.class";
  private static final String DE = "JsonBytes.B64Deserializer.class";

  private static Field field(
      String name,
      int number,
      FieldLabel label,
      FieldKind kind,
      String typeUrl,
      TypeRef type,
      @Nullable String mapValue) {
    return new Field(
        name,
        name,
        number,
        label,
        kind,
        typeUrl,
        type,
        mapValue == null ? null : "string",
        mapValue,
        mapValue == null ? null : FieldKind.SCALAR,
        false,
        null,
        null,
        false,
        null,
        name,
        false);
  }

  private static Message message() {
    return new Message(
        "Blob",
        TypeRef.named("com.example", "Blob"),
        List.of(
            field("raw", 1, FieldLabel.OPTIONAL, FieldKind.SCALAR, "bytes", TypeRefs.BYTES, null),
            field(
                "wrapped",
                2,
                FieldLabel.OPTIONAL,
                FieldKind.MESSAGE,
                "google.protobuf.BytesValue",
                TypeRefs.BYTES,
                null),
            field(
                "chunks",
                3,
                FieldLabel.REPEATED,
                FieldKind.SCALAR,
                "bytes",
                TypeRef.named("java.util", "List", List.of(TypeRefs.BYTES)),
                null),
            field(
                "byKey",
                4,
                FieldLabel.REPEATED,
                FieldKind.MAP,
                "com.example.Blob.ByKeyEntry",
                TypeRef.named("java.util", "Map", List.of(TypeRef.STRING, TypeRefs.BYTES)),
                "bytes"),
            field(
                "name", 5, FieldLabel.OPTIONAL, FieldKind.SCALAR, "string", TypeRef.STRING, null)),
        List.of(),
        List.of(),
        List.of(),
        false,
        null,
        List.of(),
        null);
  }

  private static String render(boolean jackson) {
    RecordSpec spec = RecordSourceMapping.toSource(message(), "com.example", jackson);
    JavaFile file = new JavaFile("com.example", null, spec, new TypeCollector("com.example"));
    // Collapse GJF's line wrapping so the assertions read the annotations as single lines.
    return SourceRenderer.render(file, GoogleJavaFormatter.getInstance())
        .replaceAll("\\s+", " ")
        .replace("( ", "(")
        .replace(" )", ")");
  }

  private static int count(String haystack, String needle) {
    return haystack.split(Pattern.quote(needle), -1).length - 1;
  }

  @Test
  void singularBytesFieldsPinUsingAndContentUsing() {
    String source = render(true);
    String singular = "@JsonSerialize(using = " + SER + ", contentUsing = " + SER + ")";
    // scalar bytes + the BytesValue wrapper
    assertEquals(2, count(source, singular), source);
    assertEquals(
        2,
        count(source, "@JsonDeserialize(using = " + DE + ", contentUsing = " + DE + ")"),
        source);
  }

  @Test
  void repeatedAndMapValueBytesPinContentUsingOnly() {
    String source = render(true);
    // repeated bytes + map<string, bytes>
    assertEquals(2, count(source, "@JsonSerialize(contentUsing = " + SER + ")"), source);
    assertEquals(2, count(source, "@JsonDeserialize(contentUsing = " + DE + ")"), source);
  }

  @Test
  void nonBytesFieldsAndJacksonOffFilesCarryNoPair() {
    String on = render(true);
    // Exactly the 4 bytes-family fields carry a pair -- nothing on the string field.
    assertEquals(4, count(on, "@JsonSerialize("), on);
    assertEquals(4, count(on, "@JsonDeserialize("), on);
    String off = render(false);
    assertFalse(off.contains("JsonBytes"), off);
    assertTrue(off.contains("Bytes raw"), off);
  }

  @Test
  void fieldMaskAndEmptyFieldsPinTheirPairs() {
    Message m =
        new Message(
            "Masked",
            TypeRef.named("com.example", "Masked"),
            List.of(
                field(
                    "mask",
                    1,
                    FieldLabel.OPTIONAL,
                    FieldKind.MESSAGE,
                    "google.protobuf.FieldMask",
                    TypeRefs.FIELD_MASK,
                    null),
                field(
                    "nothing",
                    2,
                    FieldLabel.OPTIONAL,
                    FieldKind.MESSAGE,
                    "google.protobuf.Empty",
                    TypeRefs.EMPTY,
                    null)),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    RecordSpec spec = RecordSourceMapping.toSource(m, "com.example", true);
    JavaFile file = new JavaFile("com.example", null, spec, new TypeCollector("com.example"));
    String source =
        SourceRenderer.render(file, GoogleJavaFormatter.getInstance())
            .replaceAll("\\s+", " ")
            .replace("( ", "(")
            .replace(" )", ")");

    assertTrue(
        source.contains(
            "@JsonSerialize(using = JsonFieldMask.Serializer.class, contentUsing ="
                + " JsonFieldMask.Serializer.class)"),
        source);
    assertTrue(
        source.contains(
            "@JsonDeserialize(using = JsonEmpty.Deserializer.class, contentUsing ="
                + " JsonEmpty.Deserializer.class)"),
        source);
  }
}
