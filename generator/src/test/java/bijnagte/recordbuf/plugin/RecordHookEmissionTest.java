package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;

/**
 * Layer A (feat-protobuf-interop): every generated message record — regardless of
 * options — implements the runtime {@code ProtobufMessage} marker and gains an
 * instance {@code toByteArray()} plus a static {@code fromBytes(byte[])} so the
 * record can cross a gRPC / protobuf-java boundary with no structural conversion.
 */
class RecordHookEmissionTest {

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

    @Test
    void recordImplementsMarkerAndGainsHook() {
        FieldDescriptorProto id =
                FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setLabel(Label.LABEL_OPTIONAL)
                        .setType(Type.TYPE_INT32)
                        .build();
        DescriptorProto msg =
                DescriptorProto.newBuilder()
                        .setName("Sample")
                        .addField(id)
                        .setOptions(MessageOptions.newBuilder())
                        .build();
        FileDescriptorProto fd =
                FileDescriptorProto.newBuilder()
                        .setName("plain.proto")
                        .setPackage("com.example")
                        .addMessageType(msg)
                        .build();

        String src = generatedSource(fd, "plain.proto");

        assertTrue(src.contains("implements ProtobufMessage"),
                "record must implement the always-on Layer A marker: " + src);
        assertTrue(src.contains("@Override"), "toByteArray must override the marker: " + src);
        assertTrue(src.contains("public byte[] toByteArray() throws IOException"),
                "instance toByteArray must delegate to the codec: " + src);
        assertTrue(src.contains("return Codec.encode(this);"),
                "toByteArray must delegate to the generated codec: " + src);
        assertTrue(src.contains("public static Sample fromBytes(byte[] in) throws IOException"),
                "static fromBytes must be generated: " + src);
        assertTrue(src.contains("return Codec.decode(in);"),
                "fromBytes must delegate to the generated codec: " + src);
        // The marker is imported short, not spelled out inline.
        assertTrue(src.contains("import bijnagte.recordbuf.runtime.ProtobufMessage;"),
                "the marker must be imported short: " + src);
    }
}
