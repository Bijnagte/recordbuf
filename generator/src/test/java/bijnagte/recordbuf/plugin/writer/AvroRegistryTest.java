package bijnagte.recordbuf.plugin.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.TypeRef;
import bijnagte.recordbuf.plugin.bridge.AvroUnsupportedException;
import bijnagte.recordbuf.plugin.model.AvroToggle;
import bijnagte.recordbuf.plugin.model.Enum;
import bijnagte.recordbuf.plugin.model.Field;
import bijnagte.recordbuf.plugin.model.FieldKind;
import bijnagte.recordbuf.plugin.model.FieldLabel;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.Message;
import bijnagte.recordbuf.plugin.model.Oneof;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@link AvroRegistry}'s graph walk, naming, and fail-loud contract.
 *
 * <p>Fixtures are hand-built model objects; field references are the fully-qualified proto names
 * the loader puts in a {@code typeUrl} (the registry does not re-derive short names).
 */
class AvroRegistryTest {

  private static Field field(String name, int number, String typeUrl) {
    return new Field(
        name,
        javaName(name),
        number,
        FieldLabel.OPTIONAL,
        kindFor(typeUrl),
        typeUrl,
        TypeRef.of(typeUrl),
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

  private static Field mapField(String name, String keyType, String valueType) {
    return new Field(
        name,
        javaName(name),
        1,
        FieldLabel.OPTIONAL,
        FieldKind.MAP,
        keyType,
        TypeRef.of(keyType),
        keyType,
        valueType,
        false,
        null,
        null,
        false,
        /* jacksonFormat= */ null,
        "jsonName",
        /* jsonIgnored= */ false);
  }

  private static Message message(String name, String pkg, Field... fields) {
    return new Message(
        name,
        TypeRef.named(pkg, name),
        List.of(fields),
        List.of(),
        List.of(),
        List.of(),
        false,
        null,
        List.of(),
        null);
  }

  private static ProtoFile file(String fileName, String pkg, Message... messages) {
    return new ProtoFile(
        fileName,
        pkg,
        "",
        List.of(messages),
        List.of(),
        false,
        null,
        AvroToggle.OFF,
        /* jackson= */ false);
  }

  /** A {@link #file} variant on a {@code recordbuf.jackson}-enabled file (the U9 envelope path). */
  private static ProtoFile jacksonFile(String fileName, String pkg, Message... messages) {
    return new ProtoFile(
        fileName,
        pkg,
        "",
        List.of(messages),
        List.of(),
        false,
        null,
        AvroToggle.OFF,
        /* jackson= */ true);
  }

  private static FileTable tableOf(FileDescriptorProto... files) {
    FileTable table = new FileTable();
    table.addAll(List.of(files));
    return table;
  }

  private static FileDescriptorProto fdp(String fileName, String pkg, String... messages) {
    FileDescriptorProto.Builder b =
        FileDescriptorProto.newBuilder().setName(fileName).setPackage(pkg);
    for (String m : messages) {
      b.addMessageType(DescriptorProto.newBuilder().setName(m));
    }
    return b.build();
  }

  private static FieldKind kindFor(String type) {
    if (type.endsWith(".Color") || type.endsWith(".Status")) {
      return FieldKind.ENUM;
    }
    if (type.startsWith("google.protobuf.")
        || type.startsWith("p.")
        || type.startsWith("a.")
        || type.startsWith("b.")
        || type.startsWith("q.")) {
      return FieldKind.MESSAGE;
    }
    return FieldKind.SCALAR;
  }

  private static String javaName(String protoName) {
    return protoName;
  }

  @Test
  void rootIsNamedAndNamespaced() throws Exception {
    Message root = message("Sample", "p", field("title", 1, "string"));
    AvroRegistry registry = AvroRegistry.build(file("a.proto", "p", root), root, null);

    assertEquals("SampleAvro", registry.nameOf(root));
    assertEquals("p.Sample", registry.fqnOf(root));
    assertEquals("p", registry.namespaceOfMessage(root));
    assertEquals("p", registry.documentNamespace());
    assertEquals(AvroRegistry.UNKNOWN_FIELDS_ENTRY, registry.entryRecordName());
    assertEquals(List.of("p.Sample"), List.copyOf(registry.protoFqns()));
  }

  @Test
  void walksNestedSiblingsAndImports() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Message address = message("Address", "p", field("street", 1, "string"));
    Message root =
        new Message(
            "Person",
            TypeRef.named("p", "Person"),
            List.of(
                field("address", 1, "p.Person.Address"),
                field("card", 2, "p.Card"),
                field("thing", 3, "b.Thing")),
            List.of(address),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    FileTable table = tableOf(fdp("b.proto", "b", "Thing"));

    AvroRegistry registry = AvroRegistry.build(file("a.proto", "p", root, card), root, table);

    assertTrue(registry.protoFqns().contains("p.Person"), "root walked");
    assertTrue(registry.protoFqns().contains("p.Person.Address"), "nested walked");
    assertTrue(registry.protoFqns().contains("p.Card"), "sibling walked");
    assertTrue(registry.protoFqns().contains("b.Thing"), "import walked");
    assertEquals("AddressAvro", registry.nameOf("p.Person.Address"));
    assertEquals("CardAvro", registry.nameOf("p.Card"));
    assertEquals("ThingAvro", registry.nameOf("b.Thing"));
    assertEquals("p", registry.namespaceOf("p.Card"));
    assertEquals("b", registry.namespaceOf("b.Thing"));
    assertNotNull(registry.messageOf("b.Thing"));
    assertNotEquals(
        registry.namespaceOf("p.Card"),
        registry.namespaceOf("b.Thing"),
        "import keeps its package");
  }

  @Test
  void enumReferenceIsIndexedNotWalked() throws Exception {
    Enum color =
        new Enum(
            "Color", List.of(new Enum.EnumConstant("RED", 1, false, null)), false, null, List.of());
    Message root = message("Sample", "p", field("color", 1, "p.Color"));
    ProtoFile pf =
        new ProtoFile(
            "a.proto",
            "p",
            "",
            List.of(root),
            List.of(color),
            false,
            null,
            AvroToggle.OFF,
            /* jackson= */ false);

    AvroRegistry registry = AvroRegistry.build(pf, root, null);

    assertTrue(registry.isEnum("p.Color"));
    assertEquals(List.of("p.Sample"), List.copyOf(registry.protoFqns()));
  }

  @Test
  void wktReferencesContributeNoDefinitions() throws Exception {
    Message root =
        message(
            "Sample",
            "p",
            field("at", 1, "google.protobuf.Timestamp"),
            field("for", 2, "google.protobuf.Duration"),
            field("note", 3, "google.protobuf.StringValue"));

    AvroRegistry registry = AvroRegistry.build(file("a.proto", "p", root), root, null);

    assertEquals(List.of("p.Sample"), List.copyOf(registry.protoFqns()));
    assertFalse(registry.isEnum("google.protobuf.Timestamp"));
  }

  @Test
  void dynamicWktFailsLoud() throws Exception {
    Message root = message("Sample", "p", field("payload", 1, "google.protobuf.Any"));

    assertThrows(
        AvroUnsupportedException.class,
        () -> AvroRegistry.build(file("a.proto", "p", root), root, null));
  }

  @Test
  void jacksonOnDynamicWktEnvelopesWithoutContributingARecord() throws Exception {
    Message root =
        message(
            "Sample",
            "p",
            field("s", 1, "google.protobuf.Struct"),
            field("a", 2, "google.protobuf.Any"),
            field("v", 3, "google.protobuf.Value"));

    AvroRegistry registry = AvroRegistry.build(jacksonFile("a.proto", "p", root), root, null);

    assertTrue(registry.hasEnvelopeField());
    assertEquals(AvroRegistry.DYNAMIC_WKT_ENTRY, registry.dynamicWktEntryName());
    assertTrue(registry.isEnvelopeField(root.fields().get(0)));
    // Only the root record is emitted (the envelope contributes a shared definition, not a walk).
    assertEquals(List.of("p.Sample"), List.copyOf(registry.protoFqns()));
  }

  @Test
  void jacksonOffDynamicWktStillFailsLoud() throws Exception {
    Message root = message("Sample", "p", field("payload", 1, "google.protobuf.Struct"));

    assertThrows(
        AvroUnsupportedException.class,
        () -> AvroRegistry.build(file("a.proto", "p", root), root, null));
  }

  @Test
  void jacksonOnDynamicWktMapValueFailsLoud() throws Exception {
    Message root = message("Sample", "p", mapField("entries", "string", "google.protobuf.Value"));

    assertThrows(
        AvroUnsupportedException.class,
        () -> AvroRegistry.build(jacksonFile("a.proto", "p", root), root, null));
  }

  @Test
  void danglingMessageReferenceFailsLoud() throws Exception {
    Message root = message("Sample", "p", field("mystery", 1, "q.Mystery"));

    assertThrows(
        AvroUnsupportedException.class,
        () -> AvroRegistry.build(file("a.proto", "p", root), root, null));
  }

  @Test
  void sameNameDifferentPackagesAreBothNamed() throws Exception {
    Message root = message("Sample", "p", field("a", 1, "a.Thing"), field("b", 2, "b.Thing"));
    FileTable table = tableOf(fdp("a.proto", "a", "Thing"), fdp("b.proto", "b", "Thing"));

    AvroRegistry registry = AvroRegistry.build(file("p.proto", "p", root), root, table);

    assertEquals("ThingAvro", registry.nameOf("a.Thing"));
    assertEquals("ThingAvro", registry.nameOf("b.Thing"));
    assertEquals("a", registry.namespaceOf("a.Thing"));
    assertEquals("b", registry.namespaceOf("b.Thing"));
  }

  @Test
  void sameNameSameNamespaceIsDisambiguatedByParentPrefix() throws Exception {
    Message thingA = message("Thing", "p", field("x", 1, "int32"));
    Message thingB = message("Thing", "p", field("y", 2, "int32"));
    Message parentA =
        new Message(
            "ParentA",
            TypeRef.named("p", "ParentA"),
            List.of(field("t", 1, "p.ParentA.Thing")),
            List.of(thingA),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    Message parentB =
        new Message(
            "ParentB",
            TypeRef.named("p", "ParentB"),
            List.of(field("t", 1, "p.ParentB.Thing")),
            List.of(thingB),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);
    Message root = message("Sample", "p", field("a", 1, "p.ParentA"), field("b", 2, "p.ParentB"));

    AvroRegistry registry =
        AvroRegistry.build(file("a.proto", "p", root, parentA, parentB), root, null);

    String nameA = registry.nameOf("p.ParentA.Thing");
    String nameB = registry.nameOf("p.ParentB.Thing");
    assertNotNull(nameA);
    assertNotNull(nameB);
    assertNotEquals(nameA, nameB, "same-namespace collision must be disambiguated");
    assertTrue(nameA.endsWith("ThingAvro"));
    assertTrue(nameB.endsWith("ThingAvro"));
  }

  @Test
  void oneofMessageMembersAreWalked() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Field member = field("card", 7, "p.Card");
    Oneof choice =
        new Oneof(
            "choice",
            TypeRef.named("p", "Choice"),
            OneofDescriptorProto.newBuilder().setName("choice").build(),
            DescriptorProto.newBuilder().setName("Sample").build(),
            List.of(member),
            /* jsonOneof= */ null);
    Message root =
        new Message(
            "Sample",
            TypeRef.named("p", "Sample"),
            List.of(field("id", 1, "int32")),
            List.of(),
            List.of(),
            List.of(choice),
            false,
            null,
            List.of(),
            null);

    AvroRegistry registry = AvroRegistry.build(file("a.proto", "p", root, card), root, null);

    assertTrue(registry.protoFqns().contains("p.Card"));
  }

  @Test
  void mapValueMessageIsWalked() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Message root = message("Sample", "p", mapField("cards", "string", "p.Card"));

    AvroRegistry registry = AvroRegistry.build(file("a.proto", "p", root, card), root, null);

    assertTrue(registry.protoFqns().contains("p.Card"));
  }

  @Test
  void mapValueEnumIsIndexedNotWalked() throws Exception {
    Enum color =
        new Enum(
            "Color", List.of(new Enum.EnumConstant("RED", 1, false, null)), false, null, List.of());
    Message root = message("Sample", "p", mapField("colors", "string", "p.Color"));
    ProtoFile pf =
        new ProtoFile(
            "a.proto",
            "p",
            "",
            List.of(root),
            List.of(color),
            false,
            null,
            AvroToggle.OFF,
            /* jackson= */ false);

    AvroRegistry registry = AvroRegistry.build(pf, root, null);

    assertTrue(registry.isEnum("p.Color"));
    assertEquals(List.of("p.Sample"), List.copyOf(registry.protoFqns()));
  }

  @Test
  void nullTableDegradesToSameFileResolution() throws Exception {
    Message card = message("Card", "p", field("number", 1, "string"));
    Message address = message("Address", "p", field("street", 1, "string"));
    Message root =
        new Message(
            "Person",
            TypeRef.named("p", "Person"),
            List.of(field("card", 1, "p.Card"), field("address", 2, "p.Person.Address")),
            List.of(address),
            List.of(),
            List.of(),
            false,
            null,
            List.of(),
            null);

    AvroRegistry registry = AvroRegistry.build(file("a.proto", "p", root, card), root, null);

    assertEquals(3, registry.protoFqns().size());
    assertNull(registry.nameOf("b.Thing"));
  }
}
