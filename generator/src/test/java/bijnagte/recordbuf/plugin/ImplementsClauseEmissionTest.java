package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumOptions;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.UninterpretedOption;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;

/**
 * U3: the generator emits an {@code implements} clause on the generated record / enum when the
 * matching custom option is declared, and emits the header unchanged (no {@code implements}) when
 * it is not — the marker path that keeps every existing type byte-for-byte identical.
 */
class ImplementsClauseEmissionTest {

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

    private static DescriptorProto message(String name, MessageOptions.Builder opts) {
        FieldDescriptorProto id =
                FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setLabel(Label.LABEL_OPTIONAL)
                        .setType(Type.TYPE_INT32)
                        .build();
        return DescriptorProto.newBuilder()
                .setName(name)
                .addField(id)
                .setOptions(opts.build())
                .build();
    }

    private static FileDescriptorProto messageFile(String file, String javaPkg, DescriptorProto msg) {
        return FileDescriptorProto.newBuilder()
                .setName(file)
                .setPackage(javaPkg)
                .addMessageType(msg)
                .build();
    }

    private static FileDescriptorProto enumFile(String file, String javaPkg, EnumDescriptorProto e) {
        return FileDescriptorProto.newBuilder().setName(file).setPackage(javaPkg).addEnumType(e).build();
    }

    @Test
    void recordEmitsImplementsWhenInterfacesDeclared() {
        MessageOptions.Builder opts = MessageOptions.newBuilder();
        opts.addUninterpretedOption(option("recordbuf.java_interface", "java.lang.Comparable"));
        opts.addUninterpretedOption(option("recordbuf.java_interface", "com.example.Marker"));

        FileDescriptorProto fd = messageFile("impl.proto", "bijnagte.recordbuf.fixture.iface",
                message("Sample", opts));
        String src = generatedSource(fd, "impl.proto");

        assertTrue(src.contains("implements java.lang.Comparable, com.example.Marker"), src);
    }

    @Test
    void recordWithoutOptionEmitsNoImplements() {
        FileDescriptorProto fd =
                messageFile("plain.proto", "bijnagte.recordbuf.fixture.iface",
                        message("Plain", MessageOptions.newBuilder()));
        String src = generatedSource(fd, "plain.proto");

        assertFalse(src.contains("implements"), "marker interface path must be untouched: " + src);
    }

    @Test
    void enumEmitsImplementsWhenInterfacesDeclared() {
        EnumOptions.Builder opts = EnumOptions.newBuilder();
        opts.addUninterpretedOption(option("recordbuf.java_enum_interface", "com.example.Named"));

        FileDescriptorProto fd = enumFile("color.proto", "bijnagte.recordbuf.fixture.iface",
                EnumDescriptorProto.newBuilder().setName("Color").setOptions(opts.build()).build());
        String src = generatedSource(fd, "color.proto");

        assertTrue(src.contains("public enum Color implements com.example.Named"), src);
    }

    @Test
    void enumWithoutOptionEmitsNoImplements() {
        FileDescriptorProto fd =
                enumFile("color.proto", "bijnagte.recordbuf.fixture.iface",
                        EnumDescriptorProto.newBuilder().setName("Plain").build());
        String src = generatedSource(fd, "color.proto");

        assertFalse(src.contains("implements"), "marker interface path must be untouched: \n" + src);
    }
}
