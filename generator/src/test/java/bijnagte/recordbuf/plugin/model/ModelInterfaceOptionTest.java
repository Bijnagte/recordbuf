package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.UninterpretedOption;

/**
 * U2: the {@code (recordbuf.java_interface)} / {@code (recordbuf.java_enum_interface)} custom
 * options are parsed out of a {@link FileDescriptorProto} and surfaced as the ordered,
 * de-duplicated {@link Message#interfaces()}/ {@link Enum#interfaces()} lists.
 *
 * <p>These tests exercise the uninterpreted-option path of {@link ModelLoader#load}, which is the
 * only path reachable without invoking {@code protoc} (a real protoc run resolves the extension and
 * delivers it as a compiled unknown field instead).
 */
class ModelInterfaceOptionTest {

    private static final String JAVA_PACKAGE = "bijnagte.recordbuf.fixture.iface";

    private static ProtoFile loadMessageFile(DescriptorProto message) {
        FileDescriptorProto fd =
                FileDescriptorProto.newBuilder()
                        .setName("iface_test.proto")
                        .setPackage("iface_test")
                        .addMessageType(message)
                        .build();
        return new ModelLoader(new FileTable(), JAVA_PACKAGE).load(fd);
    }

    private static ProtoFile loadEnumFile(EnumDescriptorProto enumerate) {
        FileDescriptorProto fd =
                FileDescriptorProto.newBuilder()
                        .setName("iface_test.proto")
                        .setPackage("iface_test")
                        .addEnumType(enumerate)
                        .build();
        return new ModelLoader(new FileTable(), JAVA_PACKAGE).load(fd);
    }

    // --- uninterpreted option builder helpers ----

    private static UninterpretedOption option(String dottedName, String value) {
        UninterpretedOption.Builder b = UninterpretedOption.newBuilder();
        for (String part : dottedName.split("\\.")) {
            b.addName(UninterpretedOption.NamePart.newBuilder()
                    .setNamePart(part)
                    .setIsExtension(false)
                    .build());
        }
        b.setStringValue(ByteString.copyFromUtf8(value));
        return b.build();
    }

    private static DescriptorProto message(String name, MessageOptions.Builder opts) {
        return DescriptorProto.newBuilder().setName(name).setOptions(opts.build()).build();
    }

    private static EnumDescriptorProto enumerate(String name, EnumOptions.Builder opts) {
        return EnumDescriptorProto.newBuilder().setName(name).setOptions(opts.build()).build();
    }

    // --- behavior ----

    @Test
    void messageParsesInterfaceOption() {
        MessageOptions.Builder opts = MessageOptions.newBuilder();
        opts.addUninterpretedOption(option("recordbuf.java_interface", "com.example.Marker"));

        Message m = loadMessageFile(message("Sample", opts)).messages().get(0);
        assertEquals(List.of("com.example.Marker"), m.interfaces());
    }

    @Test
    void messagePreservesDeclarationOrder() {
        MessageOptions.Builder opts = MessageOptions.newBuilder();
        opts.addUninterpretedOption(option("recordbuf.java_interface", "com.example.A"));
        opts.addUninterpretedOption(option("recordbuf.java_interface", "com.example.B"));

        Message m = loadMessageFile(message("Sample", opts)).messages().get(0);
        assertEquals(List.of("com.example.A", "com.example.B"), m.interfaces());
    }

    @Test
    void messageDeduplicatesInterfaceByName() {
        MessageOptions.Builder opts = MessageOptions.newBuilder();
        opts.addUninterpretedOption(option("recordbuf.java_interface", "com.example.Dup"));
        opts.addUninterpretedOption(option("recordbuf.java_interface", "com.example.Dup"));

        Message m = loadMessageFile(message("Sample", opts)).messages().get(0);
        assertEquals(List.of("com.example.Dup"), m.interfaces());
    }

    @Test
    void messageWithNoOptionHasEmptyInterfaceList() {
        Message m = loadMessageFile(message("Plain", MessageOptions.newBuilder())).messages().get(0);
        assertTrueListEmpty(m);
    }

    @Test
    void enumParsesInterfaceOption() {
        EnumOptions.Builder opts = EnumOptions.newBuilder();
        opts.addUninterpretedOption(option("recordbuf.java_enum_interface", "com.example.Named"));

        Enum e = loadEnumFile(enumerate("Color", opts)).enums().get(0);
        assertEquals(List.of("com.example.Named"), e.interfaces());
    }

    @Test
    void enumDeduplicatesInterfaceByName() {
        EnumOptions.Builder opts = EnumOptions.newBuilder();
        opts.addUninterpretedOption(option("recordbuf.java_enum_interface", "com.example.Dup"));
        opts.addUninterpretedOption(option("recordbuf.java_enum_interface", "com.example.Dup"));

        Enum e = loadEnumFile(enumerate("Color", opts)).enums().get(0);
        assertEquals(List.of("com.example.Dup"), e.interfaces());
    }

    private static void assertTrueListEmpty(Message m) {
        assertTrue(m.interfaces().isEmpty(), "expected empty interface list, got " + m.interfaces());
    }
}
