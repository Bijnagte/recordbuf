package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.*;

import bijnagte.recordbuf.plugin.writer.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.UninterpretedOption;
import org.junit.jupiter.api.Test;

// Emission-level unit tests for the oneof sealed-hierarchy feature.
//
// The generated output has:
//   - one nested sealed interface per oneof group, extending Unwrapable<Object>
//   - a per-member nested record for each non-message member (no shared Scalar wrapper)
//   - the per-member records listed in the sealed interface's permits clause
//   - a record component of the same name as the oneof, of the sealed interface type
//   - a codec that dispatches on the concrete per-member record type in both encode/decode
class OneofEmissionTest {

  private static final String FILE = "bijnagte.recordbuf.fixture.oneof.oneof_test.proto";
  private static final String PKG = "bijnagte.recordbuf.fixture.oneof";
  private static final String IFACE = "Choice";
  private static final String COMPONENT = "choice";
  // Per-member record name = Capitalised field name + "Value"
  private static final String NAME_REC = IFACE + ".NameValue";
  private static final String COUNT_REC = IFACE + ".CountValue";

  // --- helpers ---

  private static FileDescriptorProto simpleFile() {
    var compat =
        com.google.protobuf.DescriptorProtos.DescriptorProto.newBuilder()
            .setName("Compat")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("name")
                    .setNumber(1)
                    .setLabel(Label.LABEL_OPTIONAL)
                    .setType(Type.TYPE_STRING)
                    .setOneofIndex(0))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("count")
                    .setNumber(20)
                    .setLabel(Label.LABEL_OPTIONAL)
                    .setType(Type.TYPE_INT32)
                    .setOneofIndex(0))
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
            .build();
    var b = FileDescriptorProto.newBuilder().setName(FILE).setPackage(PKG).setSyntax("proto3");
    b.addMessageType(compat);
    return b.build();
  }

  private static String generatedSource(FileDescriptorProto file) {
    var request =
        com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest.newBuilder()
            .addProtoFile(file)
            .addFileToGenerate(FILE)
            .build();
    var resp = new bijnagte.recordbuf.plugin.CodeGenerator().generate(request);
    var all = new StringBuilder();
    for (var f : resp.getFileList()) {
      all.append(f.getContent()).append('\n');
    }
    return all.toString();
  }

  // --- emission tests ---

  @Test
  void sealedInterfacePermitsPerMemberRecords() {
    String src = generatedSource(simpleFile());
    // The sealed interface should list both per-member records in permits.
    assertTrue(
        src.contains("permits " + NAME_REC) || src.contains("permits " + COUNT_REC),
        "the sealed interface must list per-member records in its permits clause:\n" + src);
  }

  @Test
  void sealedInterfaceExtendsUnwrapable() {
    String src = generatedSource(simpleFile());
    assertTrue(
        src.contains("extends") && src.contains("Unwrapable"),
        "the sealed interface should extend Unwrapable:\n" + src);
  }

  @Test
  void perMemberScalarRecordEmitted() {
    String src = generatedSource(simpleFile());
    assertTrue(
        src.contains("record NameValue"),
        "a per-member record for the string 'name' member must be emitted:\n" + src);
    assertTrue(
        src.contains("record CountValue"),
        "a per-member record for the int32 'count' member must be emitted:\n" + src);
  }

  @Test
  void codecEncodesPerMemberRecordViaTypeDispatch() {
    String src = generatedSource(simpleFile());
    // The encode should use a pattern-matching switch on the sealed interface
    // with a case for the per-member record type.
    assertTrue(
        src.contains("case " + NAME_REC + " "),
        "the codec encode should have a case for the per-member record type:\n" + src);
    assertTrue(
        src.contains("case null"),
        "the switch should have a null case for the absent oneof:\n" + src);
  }

  @Test
  void codecDecodesIntoPerMemberRecord() {
    String src = generatedSource(simpleFile());
    // The decode should construct per-member records, not the old Scalar wrapper.
    assertTrue(
        src.contains("new " + NAME_REC),
        "the codec decode must construct the per-member record:\n" + src);
    assertFalse(
        src.contains("new " + IFACE + ".Scalar"),
        "the old shared Scalar wrapper should no longer be emitted:\n" + src);
  }

  @Test
  void noMoreScalarTagWrapper() {
    String src = generatedSource(simpleFile());
    assertFalse(
        src.contains(".Scalar(") || src.contains("record Scalar("),
        "the old shared Scalar(value, tag) wrapper should no longer appear:\n" + src);
  }

  // --- Interop oneof conversion emission tests -----------------------------

  private static UninterpretedOption option(String dottedName, String value) {
    UninterpretedOption.Builder b = UninterpretedOption.newBuilder();
    for (String part : dottedName.split("\\.")) {
      b.addName(
          UninterpretedOption.NamePart.newBuilder()
              .setNamePart(part)
              .setIsExtension(false)
              .build());
    }
    b.setStringValue(ByteString.copyFromUtf8(value));
    return b.build();
  }

  // The same choice oneof as simpleFile, but message-opted-in to protobuf-java so the
  // Interop converter is emitted. A scalar-only fixture keeps the emission test focused;
  // the enum+message arms are locked end-to-end by the fixture round-trip test.
  private static FileDescriptorProto interopFile() {
    var compat =
        com.google.protobuf.DescriptorProtos.DescriptorProto.newBuilder()
            .setName("Compat")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("name")
                    .setNumber(1)
                    .setLabel(Label.LABEL_OPTIONAL)
                    .setType(Type.TYPE_STRING)
                    .setOneofIndex(0))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("count")
                    .setNumber(20)
                    .setLabel(Label.LABEL_OPTIONAL)
                    .setType(Type.TYPE_INT32)
                    .setOneofIndex(0))
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
            .setOptions(
                MessageOptions.newBuilder()
                    .addUninterpretedOption(option("recordbuf.protobuf_java", PKG)))
            .build();
    var b = FileDescriptorProto.newBuilder().setName(FILE).setPackage(PKG).setSyntax("proto3");
    b.addMessageType(compat);
    return b.build();
  }

  @Test
  void interopOneofConvertedNotStubbed() {
    String src = generatedSource(interopFile());
    // `from` switches on the protobuf-java case discriminator to reconstruct the
    // sealed component, mapping scalar members to their nested value records.
    assertTrue(
        src.contains("get" + IFACE + "Case()"),
        "from must switch on the protobuf-java case getter:\n" + src);
    assertTrue(
        src.contains("new " + IFACE + ".NameValue(x.getName())"),
        "a scalar oneof arm must build the value record in from:\n" + src);
    assertTrue(
        src.contains("new " + IFACE + ".CountValue(x.getCount())"),
        "an int oneof arm must build the value record in from:\n" + src);
    // `to` pattern-matches the sealed component back onto the builder.
    assertTrue(
        src.contains("switch (r." + COMPONENT + "())"),
        "to must pattern-match the oneof component:\n" + src);
    assertTrue(src.contains("case null ->"), "to must null-guard the absent oneof:\n" + src);
    assertTrue(
        src.contains("case " + NAME_REC + " v ->"), "to must have a scalar-arm pattern:\n" + src);
    // The old oneof "not yet supported" stub must be gone.
    assertFalse(
        src.contains("oneof not yet supported in Interop"),
        "the old oneof-not-supported stub must no longer be emitted:\n" + src);
  }
}
