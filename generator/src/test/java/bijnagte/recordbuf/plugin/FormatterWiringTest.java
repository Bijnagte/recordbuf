package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.*;

import bijnagte.recordbuf.codegen.source.Formatter;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * U3: the plugin routes every rendered surface through its GJF-backed {@link Formatter} on the way
 * into the {@link CodeGeneratorResponse}. The assertion pinning the requirement (R4, the "spotless
 * parity" bar) is <i>idempotency</i> for every emitted file: {@code format(content) ==
 * format(format(content))}. That holds only because the emitted content already went through the
 * formatter (the render path wires {@code SourceRenderer.render(file, formatter)} and {@code
 * emitPackageInfo} formats its raw string), so the second GJF pass is a no-op; a non-GJF-canonical
 * raw string instead diverges on the reformat.
 *
 * <p>The surface covered mirrors R2's "top-level record files, top-level enum files, and the
 * per-package package-info.java" -- the .rec surface the plugin owns. The Avro bridge's output,
 * emitted by an external compiler, is out of this unit's scope. The formatter is a generation-time
 * concern; the emitted .rec surface itself stays dependency-free (R22), so these assertions prove
 * GJF-cleanness without pulling GJF onto any generated classpath.
 */
@SuppressWarnings("BadImport")
class FormatterWiringTest {

  private static final Formatter GJF = GoogleJavaFormatter.getInstance();

  private static List<CodeGeneratorResponse.File> generate(
      FileDescriptorProto fd, String fileToGen) {
    return new CodeGenerator()
        .generate(
            CodeGeneratorRequest.newBuilder().addProtoFile(fd).addFileToGenerate(fileToGen).build())
        .getFileList();
  }

  /** The single emitted file whose name ends with {@code suffix}. */
  private static File findFileBySuffix(List<CodeGeneratorResponse.File> out, String suffix) {
    return out.stream()
        .filter(f -> f.getName().endsWith(suffix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no emitted file ends with " + suffix + ": " + out));
  }

  /**
   * R4: a file's emitted content is already at the GJF fixed point -- it went through GJF in the
   * render path, so a single GJF reformat is a no-op and the emitted content equals its GJF form. A
   * non-GJF-canonical raw string instead diverges on the reformat, which is what makes this a real
   * check: the formatter the plugin wires actually changes source (see {@link
   * #wiredFormatterReformatsUnformattedInput()}).
   */
  private static void assertGjfClean(File f) {
    String content = f.getContent();
    String reformatted = GJF.format(content);
    assertEquals(
        content,
        reformatted,
        "emitted content for "
            + f.getName()
            + " must equal its Google Java Format form (it ran through GJF):\n"
            + content);
  }

  private static FieldDescriptorProto intField(String name, int number) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(Type.TYPE_INT32)
        .build();
  }

  private static FieldDescriptorProto stringField(String name, int number) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(Type.TYPE_STRING)
        .build();
  }

  private static FieldDescriptorProto messageField(String name, int number, String msgTypeName) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(Type.TYPE_MESSAGE)
        .setTypeName(msgTypeName)
        .build();
  }

  // R2/R4: a minimal single-message proto emits a GJF-clean Sample.java plus a GJF-clean
  // package-info.java for its package.
  @Test
  void singleMessageProducesGjfCleanRecordAndPackageInfo() {
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("plain.proto")
            .setPackage("com.example")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Sample")
                    .addField(intField("id", 1))
                    .addField(stringField("text", 2))
                    .build())
            .build();
    List<CodeGeneratorResponse.File> out = generate(fd, "plain.proto");

    // Assert the specific surfaces recordbuf owns for this proto, found by name -- not every file
    // in
    // the response (the Avro bridge emits its own *Avro.java / .avsc, not GJF'd by recordbuf).
    assertEquals(
        1,
        out.stream().filter(f -> f.getName().endsWith("Sample.java")).count(),
        "the record file is emitted: " + out);
    assertEquals(
        1,
        out.stream().filter(f -> f.getName().endsWith("package-info.java")).count(),
        "one package-info for the package: " + out);
    // The record and its package-info are the surfaces this proto exercises, so both must be
    // GJF-clean.
    assertGjfClean(findFileBySuffix(out, "Sample.java"));
    assertGjfClean(findFileBySuffix(out, "package-info.java"));
  }

  // R2/R4: a richer realistic proto -- a nested message, a nested enum, and a cross-message field
  // (a superset of the minimal case; the cross-message reference stands in for a WKT field, kept
  // self-contained so the request needs no external google/protobuf/* on its proto_file list) --
  // also yields GJF-clean output on every surface.
  @Test
  void richerProtoWithNestedTypeAndEnumProducesGjfCleanOutput() {
    DescriptorProto inner =
        DescriptorProto.newBuilder().setName("Inner").addField(intField("n", 1)).build();
    DescriptorProto detail =
        DescriptorProto.newBuilder().setName("Detail").addField(stringField("s", 1)).build();
    DescriptorProto outer =
        DescriptorProto.newBuilder()
            .setName("Outer")
            .addField(intField("id", 1))
            .addField(stringField("text", 2))
            .addField(messageField("child", 3, "com.example.Inner"))
            .addNestedType(detail)
            .build();
    EnumDescriptorProto color =
        EnumDescriptorProto.newBuilder()
            .setName("Color")
            .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0).build())
            .build();
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("richer.proto")
            .setPackage("com.example")
            .addMessageType(inner)
            .addMessageType(outer)
            .addEnumType(color)
            .build();
    List<CodeGeneratorResponse.File> out = generate(fd, "richer.proto");

    assertTrue(out.size() >= 2, "a record plus a package-info (at least): " + out);
    assertTrue(
        out.stream().anyMatch(f -> f.getName().endsWith("package-info.java")),
        "a package-info is emitted for the package: " + out);
    // The richest record exercises nested types + a cross-message field; the enum and the
    // package-info cover the remaining recordbuf-owned surfaces. (Not every file: the Avro bridge
    // emits its own *Avro.java / .avsc, not GJF'd by recordbuf.)
    assertGjfClean(findFileBySuffix(out, "Outer.java"));
    assertGjfClean(findFileBySuffix(out, "Color.java"));
    assertGjfClean(findFileBySuffix(out, "package-info.java"));
  }

  // Precondition that makes the idempotency assertions above meaningful: the formatter the plugin
  // wires actually reformats raw, non-canonical source -- a no-op formatter would not change an
  // ugly string, so a no-op formatter would make reformatting that string a no-op too.
  @Test
  void wiredFormatterReformatsUnformattedInput() {
    String ugly = "class  A   {int  x ;\n}";
    String formatted = GJF.format(ugly);
    assertEquals(formatted, GJF.format(formatted), "a GJF-formatted string is idempotent");
    assertNotEquals(ugly, formatted, "GJF reformats the ugly source");
    assertFalse(formatted.contains("class  A"), "the ugly spacing collapses: " + formatted);
  }
}
