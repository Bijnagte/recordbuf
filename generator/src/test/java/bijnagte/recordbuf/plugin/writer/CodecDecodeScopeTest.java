package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.plugin.CodeGenerator;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every generated binary {@code decode(byte[])} runs through {@code DecodeScope}: the public method
 * is a one-line delegate and the field switch lives in a private {@code decodeBody}. Covers the
 * shapes that each emit a decode: an empty message, a singular and repeated message, a message
 * valued map (its entry codec), and a oneof with a message member.
 */
class CodecDecodeScopeTest {

  private static final String PKG = "bijnagte.recordbuf.plugin.writer.scoped";
  private static final String FILE = "bijnagte.recordbuf.plugin.writer.scoped.decode_scope.proto";

  @Test
  void every_generated_decode_delegates_to_the_scope_and_keeps_the_body_private() {
    String src = generatedSource();

    // The empty message still gets a wrapper and a body method.
    assertWrapped(src, "Blank", "BlankCodec");
    // The message with a singular message, a repeated message and a oneof message member.
    assertWrapped(src, "Holder", "HolderCodec");
    // The map entry codec (a message-valued map) is wrapped too.
    assertWrapped(src, "ItemsEntry", "ItemsEntry.ItemsEntryCodec");
    // The leaf message decodes through the same shape.
    assertWrapped(src, "Leaf", "LeafCodec");

    assertTrue(
        src.contains("import bijnagte.recordbuf.runtime.DecodeScope;"),
        "the scope entry point is imported: " + src);
    assertEquals(
        4,
        count(src, Pattern.compile("private static \\w+ decodeBody\\(byte\\[\\] inBytes\\)")),
        "one private body method per generated codec: " + src);
    assertEquals(
        4,
        count(src, Pattern.compile("DecodeScope\\.decode\\(inBytes, [\\w.]+Codec::decodeBody\\)")),
        "one scoped delegate per generated codec: " + src);
  }

  /** The public delegate, and the private body it names, for one codec of {@code recordName}. */
  private static void assertWrapped(String src, String recordName, String codecName) {
    assertTrue(
        src.contains(
            "public static " + recordName + " decode(byte[] inBytes) throws IOException {"),
        "the public decode keeps its signature for " + recordName + ": " + src);
    assertTrue(
        src.contains("return DecodeScope.decode(inBytes, " + codecName + "::decodeBody);"),
        "the public decode delegates to the scope for " + codecName + ": " + src);
    assertTrue(
        src.contains(
            "private static " + recordName + " decodeBody(byte[] inBytes) throws IOException {"),
        "the body method is private and holds the tag loop for " + recordName + ": " + src);
  }

  private static int count(String src, Pattern p) {
    var m = p.matcher(src);
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  private static String generatedSource() {
    var leaf =
        DescriptorProto.newBuilder()
            .setName("Leaf")
            .addField(
                scalar(
                    "n",
                    1,
                    FieldDescriptorProto.Type.TYPE_INT32,
                    FieldDescriptorProto.Label.LABEL_OPTIONAL));
    var blank = DescriptorProto.newBuilder().setName("Blank");
    var itemsEntry =
        DescriptorProto.newBuilder()
            .setName("ItemsEntry")
            .setOptions(MessageOptions.newBuilder().setMapEntry(true))
            .addField(
                scalar(
                    "key",
                    1,
                    FieldDescriptorProto.Type.TYPE_STRING,
                    FieldDescriptorProto.Label.LABEL_OPTIONAL))
            .addField(
                message(
                    "value", 2, FieldDescriptorProto.Label.LABEL_OPTIONAL, "." + PKG + ".Leaf"));
    var holder =
        DescriptorProto.newBuilder()
            .setName("Holder")
            .addField(
                message("leaf", 1, FieldDescriptorProto.Label.LABEL_OPTIONAL, "." + PKG + ".Leaf"))
            .addField(
                message(
                    "leaves", 2, FieldDescriptorProto.Label.LABEL_REPEATED, "." + PKG + ".Leaf"))
            .addField(
                message(
                    "items",
                    3,
                    FieldDescriptorProto.Label.LABEL_REPEATED,
                    "." + PKG + ".Holder.ItemsEntry"))
            .addField(
                message("picked", 4, FieldDescriptorProto.Label.LABEL_OPTIONAL, "." + PKG + ".Leaf")
                    .toBuilder()
                    .setOneofIndex(0))
            .addField(
                scalar(
                        "count",
                        5,
                        FieldDescriptorProto.Type.TYPE_INT32,
                        FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .toBuilder()
                    .setOneofIndex(0))
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
            .addNestedType(itemsEntry);
    var file =
        FileDescriptorProto.newBuilder()
            .setName(FILE)
            .setPackage(PKG)
            .setSyntax("proto3")
            .addMessageType(blank)
            .addMessageType(leaf)
            .addMessageType(holder);
    var request =
        CodeGeneratorRequest.newBuilder().addProtoFile(file).addFileToGenerate(FILE).build();
    var all = new StringBuilder();
    for (var f : new CodeGenerator().generate(request).getFileList()) {
      all.append(f.getContent()).append('\n');
    }
    return all.toString();
  }

  private static FieldDescriptorProto scalar(
      String name, int number, FieldDescriptorProto.Type type, FieldDescriptorProto.Label label) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(label)
        .setType(type)
        .build();
  }

  private static FieldDescriptorProto message(
      String name, int number, FieldDescriptorProto.Label label, String type) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(label)
        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
        .setTypeName(type)
        .build();
  }
}
