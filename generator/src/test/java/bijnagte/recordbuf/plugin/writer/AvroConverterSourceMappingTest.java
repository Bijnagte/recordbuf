package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.Formatter;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvroConverterSourceMappingTest {

  private static Field scalar(String name, String typeUrl) {
    return new Field(
        name,
        name,
        1,
        FieldLabel.OPTIONAL,
        FieldKind.SCALAR,
        typeUrl,
        null,
        null,
        false,
        null,
        null,
        false);
  }

  private static Field wktField(String name) {
    return new Field(
        name,
        name,
        2,
        FieldLabel.OPTIONAL,
        FieldKind.MESSAGE,
        "google.protobuf.Timestamp",
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
            "bijnagte.recordbuf.fixture.Sample",
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
            null,
            null,
            false,
            null,
            null,
            false);
    Message m =
        new Message(
            "Sample",
            "bijnagte.recordbuf.fixture.Sample",
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
