package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The loader derives a field's record component name with {@link JavaNames#component(String)} (an
 * upper-case-start name is lowered) and fails generation when two fields of one message end up with
 * the same component name. The proto JSON name and the protobuf-java accessor are not touched.
 */
@SuppressWarnings("BadImport")
class ComponentNameLoaderTest {

  private static Message loadMessage(DescriptorProto msg) {
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("Edge.proto")
            .setPackage("com.example")
            .addMessageType(msg)
            .build();
    FileTable table = new FileTable();
    table.addAll(List.of(fd));
    ProtoFile pf = new ModelLoader(table, "com.example").load(fd);
    return pf.messages().get(0);
  }

  private static FieldDescriptorProto.Builder int32Field(String name, int number) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(Type.TYPE_INT32);
  }

  @Test
  void upperCaseStartFieldGetsALowerCaseComponent_jsonNameAndAccessorUnchanged() {
    Message msg =
        loadMessage(
            DescriptorProto.newBuilder()
                .setName("Edge")
                .addField(int32Field("FIELD_NAME11", 1))
                .addField(int32Field("_field_name3", 2))
                .build());

    Field upper = msg.fields().get(0);
    assertEquals("fIELDNAME11", upper.javaName());
    assertEquals("FIELDNAME11", upper.jsonName());
    assertEquals("FIELDNAME11", JavaNames.protobufJavaAccessor(upper.name()));

    Field leading = msg.fields().get(1);
    assertEquals("fieldName3", leading.javaName());
    assertEquals("FieldName3", leading.jsonName());
    assertEquals("FieldName3", JavaNames.protobufJavaAccessor(leading.name()));
  }

  @Test
  void lowerStartFieldComponentIsUnchanged() {
    Message msg =
        loadMessage(
            DescriptorProto.newBuilder()
                .setName("Edge")
                .addField(int32Field("field_name2", 1))
                .build());

    assertEquals("fieldName2", msg.fields().get(0).javaName());
  }

  @Test
  void twoFieldsLoweringToTheSameComponentFailNamingBoth() {
    DescriptorProto msg =
        DescriptorProto.newBuilder()
            .setName("Clash")
            .addField(int32Field("FieldName8", 1))
            .addField(int32Field("fieldName8", 2))
            .build();

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> loadMessage(msg));
    assertEquals(
        "message Clash: fields \"FieldName8\" and \"fieldName8\" both map to the Java name"
            + " \"fieldName8\"; rename one of them",
        e.getMessage());
  }
}
