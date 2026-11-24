package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.Formatter;
import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.model.AvroToggle;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.ProtoFile;
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
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
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
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  private static Field repeatedWktField(String name, String typeUrl) {
    return new Field(
        name,
        name,
        3,
        FieldLabel.REPEATED,
        FieldKind.MESSAGE,
        typeUrl,
        TypeRefs.LIST.withGenerics(TypeRef.OBJECT),
        null,
        null,
        true,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  private static AvroRegistry registryFor(Message m) {
    ProtoFile file =
        new ProtoFile(
            "sample.proto",
            "sample",
            "bijnagte.recordbuf.fixture",
            List.of(m),
            List.of(),
            false,
            null,
            AvroToggle.ON,
            /* jackson= */ false);
    return AvroRegistry.build(file, m, null);
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
            "SampleAvro",
            "bijnagte.recordbuf.fixture.rec",
            "bijnagte.recordbuf.fixture",
            registryFor(m),
            Formatter.IDENTITY);

    // Class name is XAvroConverter; specific record is XAvro; converter lives in .rec package.
    assertTrue(
        out.contains("public final class SampleAvroConverter"),
        "converter class named <protoRoot>AvroConverter (distinct from specific record SampleAvro)");
    assertTrue(
        out.contains("package bijnagte.recordbuf.fixture;"), "converter in the record package");
    assertTrue(
        out.contains("import bijnagte.recordbuf.fixture.rec.SampleAvro;"),
        "specific record imported from the Avro package");
    assertTrue(
        out.contains("import bijnagte.recordbuf.runtime.UnknownFields;"), "UnknownFields imported");

    // toRecord: builder pattern + getters + UnknownFields.empty()
    assertTrue(out.contains("public static Sample toRecord"), "toRecord direction emitted");
    assertTrue(out.contains("Sample.Builder b = Sample.builder()"), "builder used for toRecord");
    assertTrue(out.contains("s.getA("), "Avro getter used for scalar");
    assertTrue(
        out.contains("b.ts(s.getTs())"),
        "WKT field read directly from the Avro value (Timestamp resolves to Instant on both sides)");
    assertTrue(out.contains("UnknownFields.empty()"), "UnknownFields defaulted in toRecord");
    assertTrue(out.contains("return b.build()"), "build() called in toRecord");

    // fromRecord: no-arg ctor + setters
    assertTrue(out.contains("public static SampleAvro fromRecord"), "fromRecord direction emitted");
    assertTrue(out.contains("SampleAvro s = new SampleAvro()"), "no-arg ctor used");
    assertTrue(out.contains("s.setA(r.a())"), "scalar set directly");
    assertTrue(
        out.contains("s.setTs(r.ts())"),
        "WKT field set directly on the Avro record (Timestamp resolves to Instant on both sides)");
    assertTrue(
        out.contains("s.setUnknownFields(List.of())"),
        "the non-nullable carrier array is set so the specific record can be Avro-encoded");
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
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
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
            "SampleAvro",
            "bijnagte.recordbuf.fixture.rec",
            "bijnagte.recordbuf.fixture",
            registryFor(m),
            Formatter.IDENTITY);

    assertTrue(
        out.contains("ByteBuffer.wrap(r.a().toByteArray())"),
        "fromRecord wraps a record's Bytes' raw array in a ByteBuffer");
    assertTrue(
        out.contains("Bytes.of(Arrays.copyOf(s.getA().array()"),
        "toRecord wraps a ByteBuffer's raw array into a Bytes");
  }

  private static Field dynamicField(String name, int number, String typeUrl, FieldLabel label) {
    return new Field(
        name,
        name,
        number,
        label,
        FieldKind.MESSAGE,
        typeUrl,
        TypeRef.OBJECT,
        null,
        null,
        true,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  @Test
  void dynamicWktFieldsRoundTripThroughTheEnvelopeOnJacksonFiles() {
    Message m =
        new Message(
            "Sample",
            TypeRef.named("bijnagte.recordbuf.fixture", "Sample"),
            List.of(
                dynamicField("doc", 1, "google.protobuf.Struct", FieldLabel.OPTIONAL),
                dynamicField("payload", 2, "google.protobuf.Any", FieldLabel.OPTIONAL),
                dynamicField("values", 3, "google.protobuf.Value", FieldLabel.REPEATED)),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    ProtoFile file =
        new ProtoFile(
            "sample.proto",
            "sample",
            "bijnagte.recordbuf.fixture",
            List.of(m),
            List.of(),
            false,
            null,
            AvroToggle.ON,
            /* jackson= */ true);

    String out =
        AvroConverterSourceMapping.generate(
            "Sample",
            "sample.proto",
            m,
            "SampleAvro",
            "bijnagte.recordbuf.fixture.rec",
            "bijnagte.recordbuf.fixture",
            AvroRegistry.build(file, m, null),
            Formatter.IDENTITY);

    // record -> specific: the envelope carries "json", the content type, and the node's JSON text.
    assertTrue(
        out.contains(
            "s.setDoc(r.doc() == null ? null : new RecordbufDynamicWkt(\"json\","
                + " DynamicWkt.contentType(\"google.protobuf.Struct\", r.doc()),"
                + " DynamicWkt.payload(r.doc()).jsonText()))"),
        out);
    // Any's content type comes from the facade too (its typeUrl, resolved at runtime).
    assertTrue(out.contains("DynamicWkt.contentType(\"google.protobuf.Any\", r.payload())"), out);
    // specific -> record: parsed back through JsonPayload, cast only when narrower than JsonNode.
    assertTrue(
        out.contains(
            "b.doc(s.getDoc() == null ? null : (ObjectNode)"
                + " DynamicWkt.node(JsonPayload.of(s.getDoc().getPayload().toString())))"),
        out);
    assertTrue(
        out.contains(
            "b.payload(s.getPayload() == null ? null :"
                + " DynamicWkt.node(JsonPayload.of(s.getPayload().getPayload().toString())))"),
        out);
    // Repeated fields map the same arm per element.
    assertTrue(out.contains("s.getValues().stream().map(v -> v == null ? null :"), out);
    assertTrue(out.contains("r.values().stream().map(v -> v == null ? null :"), out);
  }

  private static Field enumField(
      String name, int number, FieldLabel label, TypeRef.Named enumType) {
    return new Field(
        name,
        name,
        number,
        label,
        FieldKind.ENUM,
        "bijnagte.recordbuf.fixture.Sample.Kind",
        label == FieldLabel.REPEATED ? TypeRefs.LIST.withGenerics(enumType) : enumType,
        null,
        null,
        false,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  @Test
  void nestedEnumConverterReferencesTheRecordEnumThroughItsEnclosingRecord() {
    // The record-side enum of a nested enum is Sample.Kind in the record package, not a Sample.rec
    // path: its converter refers to it through the enclosing record.
    TypeRef.Named record = TypeRef.named("bijnagte.recordbuf.fixture.rec", "Sample");
    TypeRef.Named kind = record.sub("Kind");
    Message m =
        new Message(
            "Sample",
            record,
            List.of(
                enumField("kind", 1, FieldLabel.OPTIONAL, kind),
                enumField("kinds", 2, FieldLabel.REPEATED, kind)),
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
            "SampleAvro",
            "bijnagte.recordbuf.fixture",
            "bijnagte.recordbuf.fixture.rec",
            registryFor(m),
            Formatter.IDENTITY);

    assertTrue(out.contains("Arrays.stream(Sample.Kind.values())"), out);
    assertTrue(out.contains("c.name().contentEquals(s.getKind())"), out);
    assertTrue(out.contains("c.name().contentEquals(v)"), out);
    assertTrue(out.contains("r.kind() == null ? Arrays.stream(Sample.Kind.values())"), out);
    assertFalse(out.contains(".rec.Kind"), out);
  }

  @Test
  void repeatedWrapperWktsMapEachElementThroughTheSingularConversion() {
    // A repeated wrapper is an Avro array of a nullable element, so each element converts with the
    // wrapper's singular conversion: a CharSequence to a String, a ByteBuffer by its position.
    Message m =
        new Message(
            "Sample",
            TypeRef.named("bijnagte.recordbuf.fixture", "Sample"),
            List.of(
                repeatedWktField("strings", "google.protobuf.StringValue"),
                repeatedWktField("blobs", "google.protobuf.BytesValue")),
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
            "SampleAvro",
            "bijnagte.recordbuf.fixture.rec",
            "bijnagte.recordbuf.fixture",
            registryFor(m),
            Formatter.IDENTITY);

    assertTrue(
        out.contains(
            "b.strings(s.getStrings().stream().map(v -> v == null ? null : v.toString()).toList())"),
        out);
    assertTrue(
        out.contains("s.setStrings(r.strings().stream().<CharSequence>map(v -> v).toList())"), out);
    assertTrue(out.contains("s.getBlobs().stream().map(v -> v == null ? null : Bytes.of("), out);
    assertTrue(out.contains("WktAvroBridge.bytes(v)"), out);
    assertFalse(out.contains(".array()"), out);
  }

  @Test
  void repeatedStructuredWktsMapEachElementThroughTheSingularConversion() {
    // A repeated Duration takes the floor pair both ways (non-negative nanos), a repeated FieldMask
    // and Empty map per element through the bridge and the empty record, and a repeated Timestamp
    // copies its list (its Avro element is already an Instant).
    Message m =
        new Message(
            "Sample",
            TypeRef.named("bijnagte.recordbuf.fixture", "Sample"),
            List.of(
                repeatedWktField("durations", "google.protobuf.Duration"),
                repeatedWktField("timestamps", "google.protobuf.Timestamp"),
                repeatedWktField("masks", "google.protobuf.FieldMask"),
                repeatedWktField("empties", "google.protobuf.Empty")),
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
            "SampleAvro",
            "bijnagte.recordbuf.fixture.rec",
            "bijnagte.recordbuf.fixture",
            registryFor(m),
            Formatter.IDENTITY);

    assertTrue(
        out.contains(
            "s.getDurations().stream().map(v -> v == null ? null : java.time.Duration.ofSeconds("
                + "v.getSeconds(), v.getNanos() % 1_000_000_000L)).toList()"),
        out);
    assertTrue(
        out.contains("v == null ? null : new ") && out.contains("(v.toSeconds(), v.toNanosPart())"),
        out);
    assertTrue(out.contains("FieldMask.of(WktAvroBridge.fieldMaskFromAvro(v.toString()))"), out);
    assertTrue(out.contains("WktAvroBridge.fieldMaskToAvro(v.paths())"), out);
    assertTrue(out.contains("b.timestamps(s.getTimestamps().stream().toList())"), out);
    assertFalse(out.contains("b.timestamps(s.getTimestamps())"), out);
    assertTrue(out.contains("s.setTimestamps(r.timestamps())"), out);
    assertTrue(out.contains("b.empties(s.getEmpties().stream().map(v -> v == null ? null : "), out);
    assertFalse(out.contains("s.getDurations().getSeconds()"), out);
  }

  @Test
  void repeatedFieldMaskReadsAnAbsentElementBackAsTheEmptyMask() {
    // An empty mask is written as a null element, and a list element gets no constructor
    // normalization, so the read-back maps the null element to the empty mask, not to null.
    Message m =
        new Message(
            "Sample",
            TypeRef.named("bijnagte.recordbuf.fixture", "Sample"),
            List.of(repeatedWktField("masks", "google.protobuf.FieldMask")),
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
            "SampleAvro",
            "bijnagte.recordbuf.fixture.rec",
            "bijnagte.recordbuf.fixture",
            registryFor(m),
            Formatter.IDENTITY);

    assertTrue(
        out.contains(
            "b.masks(s.getMasks().stream().map(v -> v == null ? FieldMask.empty() : "
                + "FieldMask.of(WktAvroBridge.fieldMaskFromAvro(v.toString()))).toList())"),
        out);
  }

  @Test
  void repeatedScalarCopiesItsAvroListIntoAPlainList() {
    // A repeated scalar's Avro array is a GenericData.Array after a binary decode: the record gets
    // a plain List.copyOf of it, not the Avro list itself.
    Field ints =
        new Field(
            "ints",
            "ints",
            4,
            FieldLabel.REPEATED,
            FieldKind.SCALAR,
            "int32",
            TypeRefs.LIST.withGenerics(TypeRef.OBJECT),
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "jsonName",
            /* jsonIgnored= */ false);
    Message m =
        new Message(
            "Sample",
            TypeRef.named("bijnagte.recordbuf.fixture", "Sample"),
            List.of(ints),
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
            "SampleAvro",
            "bijnagte.recordbuf.fixture.rec",
            "bijnagte.recordbuf.fixture",
            registryFor(m),
            Formatter.IDENTITY);

    assertTrue(out.contains("b.ints(List.copyOf(s.getInts()))"), out);
  }

  @Test
  void cycleClosingSingularReferenceIsNullCheckedInBothDirections() {
    Field child =
        new Field(
            "child",
            "child",
            1,
            FieldLabel.OPTIONAL,
            FieldKind.MESSAGE,
            "p.Node",
            TypeRef.OBJECT,
            null,
            null,
            false,
            null,
            null,
            false,
            /* jacksonFormat= */ null,
            "child",
            /* jsonIgnored= */ false);
    Message node =
        new Message(
            "Node",
            TypeRef.named("p", "Node"),
            List.of(child),
            List.of(),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    ProtoFile file =
        new ProtoFile(
            "a.proto",
            "p",
            "",
            List.of(node),
            List.of(),
            false,
            null,
            AvroToggle.ON,
            /* jackson= */ false);
    AvroRegistry registry = AvroRegistry.build(file, node, null);

    String out =
        AvroConverterSourceMapping.generate(
            "Node", "a.proto", node, "NodeAvro", "p", "p.rec", registry, Formatter.IDENTITY);

    // An Avro null is the absent message, and an absent message is an Avro null.
    assertTrue(
        out.contains(
            "b.child(s.getChild() == null ? null : NodeAvroConverter.toRecord(s.getChild()))"),
        out);
    assertTrue(
        out.contains(
            "s.setChild(r.child() == null ? null : NodeAvroConverter.fromRecord(r.child()))"),
        out);
  }
}
