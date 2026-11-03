package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;

/**
 * U1: the loader routes real {@code oneof{...}} members out of a message's flat
 * fields list into {@link Oneof} entries, and skips the synthetic oneofs a
 * proto3-{@code optional} scalar/enum produces (its member stays a top-level
 * field). The loader's flat-field routing and the synthetic skip are the two
 * behaviors that make "no oneof machinery fires when a oneof is absent" hold for
 * every existing proto.
 */
class OneofLoaderTest {

    private static Message loadMessage(String pkg, String name, DescriptorProto msg) {
        FileDescriptorProto fd =
                FileDescriptorProto.newBuilder()
                        .setName(name + ".proto")
                        .setPackage(pkg)
                        .addMessageType(msg)
                        .build();
        FileTable table = new FileTable();
        table.addAll(List.of(fd));
        ProtoFile pf = new ModelLoader(table, pkg).load(fd);
        return findBy(pf, name);
    }

    private static Message findBy(ProtoFile pf, String protoName) {
        for (Message m : pf.messages()) {
            if (m.name().equals(protoName)) return m;
        }
        throw new AssertionError("no generated message " + protoName);
    }

    private static FieldDescriptorProto.Builder field(String n, int num, Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(n)
                .setNumber(num)
                .setLabel(Label.LABEL_OPTIONAL)
                .setType(type);
    }

    /** The group/stop-flatten case: one {@code oneof payload} plus a plain field. */
    @Test
    void realOneofMembersLeaveFlatFieldsIntoAnOneofEntry() {
        Message msg = loadMessage("com.example", "Payload", oneofPayload().build());

        // exactly one oneof, named "payload", with its three members in declaration order:
        assertEquals(1, msg.oneofs().size(), () -> "expected one oneof, had " + msg.oneofs());
        Oneof payload = msg.oneofs().get(0);
        assertEquals("payload", payload.name());
        assertEquals(
                List.of("a", "b", "c"),
                payload.members().stream().map(Field::name).toList(),
                "oneof members must keep declaration order");

        // the oneof members left the flat fields list; only the plain field remains:
        assertEquals(List.of("n"), msg.fields().stream().map(Field::name).toList());
    }

    /** The synthetic-skip case: a proto3-{@code optional} scalar is NOT an oneof. */
    @Test
    void syntheticProto3OptionalIsNotAnOneof() {
        // oneof_decl[1] is the synthetic oneof protoc synthesizes for `optional int32 x`;
        // its member x has proto3Optional == true, so it is NOT routed into oneofs().
        DescriptorProto.Builder m =
                DescriptorProto.newBuilder()
                        .setName("Holder")
                        .addOneofDecl(OneofDescriptorProto.newBuilder().setName("payload").build())
                        .addField(field("a", 1, Type.TYPE_STRING).setOneofIndex(0))
                        // the synthetic oneof for the proto3 optional (its sole member x has
                        // proto3Optional == true, so it routes no member and is skipped):
                        .addOneofDecl(
                                OneofDescriptorProto.newBuilder().setName("x").build())
                        .addField(field("x", 5, Type.TYPE_INT32).setOneofIndex(1).setProto3Optional(true));

        Message msg = loadMessage("com.example", "Holder", m.build());

        // the real oneof survives; the synthetic one is skipped:
        assertEquals(1, msg.oneofs().size(), () -> "the synthetic oneof must be skipped, had " + msg.oneofs());
        assertEquals("payload", msg.oneofs().get(0).name());

        // x is a top-level field with its existing proto3-optional handling:
        assertTrue(msg.fields().stream().anyMatch(f -> f.name().equals("x") && f.proto3Optional()),
                () -> "the proto3 optional x must stay a top-level nullable field: " + msg.fields());
    }

    /** The nested-oneof case: a nested message carries its own oneof; the parent does not. */
    @Test
    void nestedMessageCarriesItsOwnOneof() {
        DescriptorProto.Builder inner =
                DescriptorProto.newBuilder()
                        .setName("Inner")
                        .addOneofDecl(OneofDescriptorProto.newBuilder().setName("pick").build())
                        .addField(field("v", 1, Type.TYPE_STRING).setOneofIndex(0));

        DescriptorProto.Builder outer =
                DescriptorProto.newBuilder()
                        .setName("Outer")
                        .addField(field("leaf", 1, Type.TYPE_INT32))
                        .addNestedType(inner);

        Message outerMsg = loadMessage("com.example", "Outer", outer.build());

        assertTrue(outerMsg.oneofs().isEmpty(),
                "the parent must not inherit its nested message's oneof");
        Message innerMsg = outerMsg.nestedMessages().get(0);
        assertEquals(1, innerMsg.oneofs().size(), "the nested message must carry its own oneof");
        assertEquals("pick", innerMsg.oneofs().get(0).name());
    }

    private static DescriptorProto.Builder oneofPayload() {
        return DescriptorProto.newBuilder()
                .setName("Payload")
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("payload").build())
                .addField(field("a", 1, Type.TYPE_STRING).setOneofIndex(0))
                .addField(field("b", 2, Type.TYPE_INT32).setOneofIndex(0))
                .addField(field("c", 3, Type.TYPE_MESSAGE).setTypeName("Meta").setOneofIndex(0))
                .addField(field("n", 4, Type.TYPE_INT32));
    }
}
