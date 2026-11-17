package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.Formatter;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.types.TypeRefs;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvroConverterSourceMappingTest {

  private static Field scalar(String name, String typeUrl) {
    TypeRef type =
        switch (typeUrl) {
          case "int32", "uint32", "sint32", "fixed32", "sfixed32" -> TypeRef.Primitive.INT;
          case "int64", "uint64", "sint64", "fixed64", "sfixed64" -> TypeRef.Primitive.LONG;
          case "float" -> TypeRef.Primitive.FLOAT;
          case "double" -> TypeRef.Primitive.DOUBLE;
          case "bool" -> TypeRef.Primitive.BOOLEAN;
          case "string" -> TypeRef.STRING;
          case "bytes" -> TypeRefs.BYTES;
          default -> TypeRef.OBJECT;
        };
    return new Field(
        name,
        name,
        1,
        FieldLabel.OPTIONAL,
        FieldKind.SCALAR,
        typeUrl,
        type,
        null,
        null,
        false,
        null,
        null,
        false);
  }

  private static Field wktField(String name) {
    // Known WKTs are modeled by the loader as their native Java type, but the converter derives
    // field
    // handling from the WKT registry and the field token, not from f.type() — a placeholder type
    // suffices here.
    return new Field(
        name,
        name,
        2,
        FieldLabel.OPTIONAL,
        FieldKind.MESSAGE,
        "google.protobuf.Timestamp",
        TypeRef.OBJECT,
        null,
        null,
        true,
        null,
        null,
        false);
  }

  @Test
  void generatesConverterWithBothDirections() {
    Message m =
        new Message(
            "Sample",
            TypeRef.named("bijnagte.recordbuf.fixture", "Sample"),
            List.of(scalar("a", "int32"), wktField("ts")),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);

    String out =
        AvroConverterSourceMapping.generate(
            "Sample",
            "sample.proto",
            m,
            "bijnagte.recordbuf.fixture",
            "bijnagte.recordbuf.fixture.rec",
            Formatter.IDENTITY);

    // Class name is XAvroConverter; specific record is XAvro; converter lives in .rec package.
    assertTrue(
        out.contains("public final class SampleAvroConverter"),
        "converter class named <protoRoot>AvroConverter (distinct from specific record SampleAvro)");
    assertTrue(
        out.contains("package bijnagte.recordbuf.fixture.rec;"), "converter in .rec package");
    assertTrue(
        out.contains("import bijnagte.recordbuf.fixture.SampleAvro;"), "specific record imported");
    assertTrue(
        out.contains("import bijnagte.recordbuf.avro.WktAvroBridge;"), "WktAvroBridge imported");
    assertTrue(
        out.contains("import bijnagte.recordbuf.runtime.UnknownFields;"), "UnknownFields imported");

    // toRecord: builder pattern + getters + UnknownFields.empty()
    assertTrue(out.contains("public static Sample toRecord"), "toRecord direction emitted");
    assertTrue(out.contains("Sample.Builder b = Sample.builder()"), "builder used for toRecord");
    assertTrue(out.contains("s.getA("), "Avro getter used for scalar");
    assertTrue(out.contains("WktAvroBridge.fromAvro(s.getTs())"), "WKT routed through fromAvro");
    assertTrue(out.contains("UnknownFields.empty()"), "UnknownFields defaulted in toRecord");
    assertTrue(out.contains("return b.build()"), "build() called in toRecord");

    // fromRecord: no-arg ctor + setters
    assertTrue(out.contains("public static SampleAvro fromRecord"), "fromRecord direction emitted");
    assertTrue(out.contains("SampleAvro s = new SampleAvro()"), "no-arg ctor used");
    assertTrue(out.contains("s.setA(r.a())"), "scalar set directly");
    assertTrue(out.contains("WktAvroBridge.toAvro(r.ts())"), "WKT routed through toAvro");
  }

  @Test
  void bytesFieldUsesByteBufferConversion() {
    Field bytesField =
        new Field(
            "a",
            "a",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.SCALAR,
            "bytes",
            TypeRefs.BYTES,
            null,
            null,
            false,
            null,
            null,
            false);
    Message m =
        new Message(
            "Sample",
            TypeRef.named("bijnagte.recordbuf.fixture", "Sample"),
            List.of(bytesField),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);

    String out =
        AvroConverterSourceMapping.generate(
            "Sample",
            "sample.proto",
            m,
            "bijnagte.recordbuf.fixture",
            "bijnagte.recordbuf.fixture.rec",
            Formatter.IDENTITY);

    assertTrue(
        out.contains("ByteBuffer.wrap(r.a().toByteArray())"),
        "fromRecord wraps a record's Bytes' raw array in a ByteBuffer");
    assertTrue(
        out.contains("Bytes.of(Arrays.copyOf(s.getA().array()"),
        "toRecord wraps a ByteBuffer's raw array into a Bytes");
  }
}
