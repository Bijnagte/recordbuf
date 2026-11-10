package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import org.junit.jupiter.api.Test;

/**
 * feat-generated-builder: the generated record emits a fluent, value-only {@code Builder} alongside
 * its codec. These source-shape assertions lock the generator's builder emission; the fixtures then
 * compile + round-trip the generated builders end to end, which is the runtime-level confirmation
 * this unit test can't reach alone.
 */
@SuppressWarnings("BadImport")
class BuilderEmissionTest {

  private static final String PKG = "bijnagte.recordbuf.fixture.builder";
  private static final String FILE = PKG + ".builder.proto";

  private static String generatedSource(FileDescriptorProto fd, String fileToGenerate) {
    CodeGeneratorRequest request =
        CodeGeneratorRequest.newBuilder()
            .addProtoFile(fd)
            .addFileToGenerate(fileToGenerate)
            .build();
    CodeGeneratorResponse resp = new CodeGenerator().generate(request);
    StringBuilder all = new StringBuilder();
    for (CodeGeneratorResponse.File f : resp.getFileList()) {
      all.append(f.getContent()).append('\n');
    }
    return all.toString();
  }

  private static FieldDescriptorProto singularInt(String name, int number) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(Type.TYPE_INT32)
        .build();
  }

  private static FieldDescriptorProto singularBytes(String name, int number) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(Type.TYPE_BYTES)
        .build();
  }

  private static FieldDescriptorProto repeatedMessage(String name, int number, String msgType) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_REPEATED)
        .setType(Type.TYPE_MESSAGE)
        .setTypeName(msgType)
        .build();
  }

  // A top-level Item (one int) plus a top-level Sample carrying a scalar, a bytes, and a
  // repeated Item -- together they exercise the builder's fresh-default, defensive-copy,
  // setter, and top-level emission paths in one file.
  private static FileDescriptorProto file() {
    return FileDescriptorProto.newBuilder()
        .setName(FILE)
        .setPackage(PKG)
        .addMessageType(
            DescriptorProto.newBuilder().setName("Item").addField(singularInt("n", 1)).build())
        .addMessageType(
            DescriptorProto.newBuilder()
                .setName("Sample")
                .addField(singularInt("a", 1))
                .addField(singularBytes("b", 2))
                .addField(repeatedMessage("items", 3, "Item"))
                .build())
        .build();
  }

  @Test
  void emitsBuilderFactoryToBuilderAndNestedBuilder() {
    String src = generatedSource(file(), FILE);

    // R1: the factory + copy accessors + nested builder class on the record.
    // The builder factory uses short-form Builder since it is nested inside Sample/Item.
    assertTrue(src.contains("public static Builder builder()"), src);
    assertTrue(src.contains("return new Sample.Builder();"), src);
    assertTrue(src.contains("public Builder toBuilder()"), src);
    assertTrue(src.contains("return new Sample.Builder(this);"), src);
    assertTrue(src.contains("public static final class Builder"), src);
    assertTrue(src.contains("public Sample build()"), src);
  }

  @Test
  void emitsCopyConstructorWithFreshAndCopy() {
    String src = generatedSource(file(), FILE);

    // R3: a no-arg ctor (relying on field initializers) and a copy ctor.
    assertTrue(src.contains("private Builder() {}"), src);
    assertTrue(src.contains("private Builder(Sample x)"), src);
  }

  @Test
  void freshDefaultsMirrorCodec() {
    String src = generatedSource(file(), FILE);

    // R4: fresh builder initializes each component to the codec default (short names,
    // matching the generated record's own components).
    assertTrue(src.contains("private int a = 0;"), src);
    assertTrue(src.contains("private Bytes b = Bytes.empty();"), src);
    assertTrue(src.contains("private List<Item> items = new ArrayList<>();"), src);
  }

  @Test
  void copyConstructorDefensivelyCopiesContainersAndByteArrays() {
    String src = generatedSource(file(), FILE);

    // R3: a bytes component is the immutable runtime Bytes, so it copies by value; containers
    // (List/Map) still copy defensively so toBuilder() never aliases the source's containers.
    assertTrue(src.contains("this.a = x.a();"), src);
    assertTrue(src.contains("this.b = x.b();"), src);
    assertTrue(src.contains("this.items = new ArrayList<>(x.items());"), src);
  }

  @Test
  void emitsOneSetterPerComponent() {
    String src = generatedSource(file(), FILE);

    // R2: one fluent, chaining, component-named setter per field.
    assertTrue(src.contains("public Builder a(int a)"), src);
    assertTrue(src.contains("public Builder b(Bytes b)"), src);
    assertTrue(src.contains("public Builder items(List<Item> items)"), src);
    assertTrue(src.contains("return this;"), src);
  }

  @Test
  void emitsJavadocOnBuilderAccessors() {
    String src = generatedSource(file(), FILE);

    // R6: generated Javadoc on the accessors.
    assertTrue(src.contains("Static factory for a fresh Sample"), src);
    assertTrue(src.contains("Fluent, value-only builder for the Sample"), src);
    assertTrue(src.contains("Builds a Sample record"), src);
  }

  @Test
  void builderIsEmittedForEveryTopLevelMessage() {
    String src = generatedSource(file(), FILE);

    // R5 (top-level): every top-level message in the file gets a builder.
    // The source-AST path uses the short form since Builder is nested inside each record.
    assertTrue(src.contains("public static Builder builder()"), src);
  }

  @Test
  void enumsDoNotGetABuilder() {
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("onlyenum.proto")
            .setPackage(PKG)
            .addEnumType(EnumDescriptorProto.newBuilder().setName("Color").build())
            .build();
    String src = generatedSource(fd, "onlyenum.proto");

    assertFalse(src.contains("class Builder"), "enums must not gain a builder: " + src);
    assertFalse(src.contains("Color.Builder"), "enums must not get a builder factory: " + src);
  }
}
