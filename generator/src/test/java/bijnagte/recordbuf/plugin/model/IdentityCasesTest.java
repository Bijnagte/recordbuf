package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.codegen.source.TypeRef;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the identity-subtype predicate. The fixture is a {@code Pet} with one oneof whose
 * cases cover every eligibility clause: a same-package message, a same-package enum, a scalar, a
 * message in another package, and a well-known type.
 */
@SuppressWarnings("BadImport")
class IdentityCasesTest {

  private static final String PETS = "pets.proto";
  private static final String DOGS = "dogs.proto";
  private static final String CATS = "cats.proto";
  private static final String WKT = "google/protobuf/timestamp.proto";
  private static final String PETS_PKG = "pets.v1";
  private static final String CATS_PKG = "cats.v1";

  // --- fixture ---

  private static FileDescriptorProto.Builder file(String name, String pkg) {
    return FileDescriptorProto.newBuilder().setName(name).setPackage(pkg).setSyntax("proto3");
  }

  private static FieldDescriptorProto.Builder field(String name, int number, Type type) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(type);
  }

  private static FieldDescriptorProto.Builder typed(String name, int number, String typeName) {
    return field(name, number, Type.TYPE_MESSAGE).setTypeName(typeName);
  }

  private static FieldDescriptorProto.Builder enumTyped(String name, int number, String typeName) {
    return field(name, number, Type.TYPE_ENUM).setTypeName(typeName);
  }

  /** Every file in the fixture, keyed by file name. */
  private static Map<String, FileDescriptorProto> files() {
    var pets =
        file(PETS, PETS_PKG)
            .addDependency(DOGS)
            .addDependency(CATS)
            .addDependency(WKT)
            .addEnumType(
                EnumDescriptorProto.newBuilder()
                    .setName("Color")
                    .addValue(EnumValueDescriptorProto.newBuilder().setName("COLOR_UNSPECIFIED"))
                    .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(1)))
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Pet")
                    .addField(typed("dog", 1, ".pets.v1.Dog").setOneofIndex(0))
                    .addField(enumTyped("color", 2, ".pets.v1.Color").setOneofIndex(0))
                    .addField(field("name", 3, Type.TYPE_STRING).setOneofIndex(0))
                    .addField(typed("cat", 4, ".cats.v1.Cat").setOneofIndex(0))
                    .addField(typed("at", 5, ".google.protobuf.Timestamp").setOneofIndex(0))
                    .addOneofDecl(OneofDescriptorProto.newBuilder().setName("kind")))
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Shelter")
                    .addField(typed("best", 1, ".pets.v1.Dog").setOneofIndex(0))
                    .addField(field("label", 2, Type.TYPE_STRING))
                    .addOneofDecl(OneofDescriptorProto.newBuilder().setName("slot"))
                    .addNestedType(
                        DescriptorProto.newBuilder()
                            .setName("Ward")
                            .addField(typed("pup", 1, ".pets.v1.Dog").setOneofIndex(0))
                            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("item"))));
    var dogs =
        file(DOGS, PETS_PKG)
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Dog")
                    .addField(field("bark", 1, Type.TYPE_STRING)));
    var cats =
        file(CATS, CATS_PKG)
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Cat")
                    .addField(field("meow", 1, Type.TYPE_STRING)));
    var wkt =
        file(WKT, "google.protobuf")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Timestamp")
                    .addField(field("seconds", 1, Type.TYPE_INT64)));
    Map<String, FileDescriptorProto> out = new LinkedHashMap<>();
    for (var b : List.of(pets, dogs, cats, wkt)) {
      FileDescriptorProto built = b.build();
      out.put(built.getName(), built);
    }
    return out;
  }

  /** A table over every fixture file, with the given files loaded as the run's generated set. */
  private static Map<String, ProtoFile> load(FileTable table, List<String> generating) {
    Map<String, ProtoFile> loaded = new LinkedHashMap<>();
    Map<String, FileDescriptorProto> all = files();
    for (String name : generating) {
      FileDescriptorProto fdp = Objects.requireNonNull(all.get(name), name);
      loaded.put(name, new ModelLoader(table, table.javaPackage(fdp)).load(fdp));
    }
    return loaded;
  }

  private static FileTable table() {
    var table = new FileTable();
    table.addAll(List.copyOf(files().values()));
    return table;
  }

  private static Oneof petKind(Map<String, ProtoFile> loaded) {
    return Objects.requireNonNull(loaded.get(PETS)).messages().stream()
        .filter(m -> m.name().equals("Pet"))
        .findFirst()
        .orElseThrow()
        .oneofs()
        .get(0);
  }

  private static Field member(Oneof oneof, String name) {
    return oneof.members().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
  }

  private static List<String> fqns(List<TypeRef.Named> refs) {
    return refs.stream().map(TypeRef::fqn).toList();
  }

  // --- predicate, one clause per test ---

  @Test
  void sameRunSameMessageAndEnumCasesAreIdentity() {
    FileTable table = table();
    var loaded = load(table, List.of(PETS, DOGS));
    var identity = IdentityCases.of(table, loaded);
    Oneof kind = petKind(loaded);

    assertTrue(identity.isIdentity(PETS, kind.iface(), member(kind, "dog")));
    assertTrue(identity.isIdentity(PETS, kind.iface(), member(kind, "color")));
  }

  @Test
  void scalarCaseIsNeverIdentity() {
    FileTable table = table();
    var loaded = load(table, List.of(PETS, DOGS));
    var identity = IdentityCases.of(table, loaded);
    Oneof kind = petKind(loaded);

    assertFalse(identity.isIdentity(PETS, kind.iface(), member(kind, "name")));
  }

  @Test
  void wellKnownTypeCaseIsNeverIdentity() {
    FileTable table = table();
    var loaded = load(table, List.of(PETS, WKT));
    var identity = IdentityCases.of(table, loaded);
    Oneof kind = petKind(loaded);

    assertFalse(identity.isIdentity(PETS, kind.iface(), member(kind, "at")));
  }

  @Test
  void caseInAnotherJavaPackageIsNotIdentity() {
    FileTable table = table();
    var loaded = load(table, List.of(PETS, CATS));
    var identity = IdentityCases.of(table, loaded);
    Oneof kind = petKind(loaded);

    assertFalse(identity.isIdentity(PETS, kind.iface(), member(kind, "cat")));
  }

  @Test
  void caseFileOutsideTheRunIsNotIdentity() {
    FileTable table = table();
    // Pets is generated, Dog's file is not: the case keeps its wrapper.
    var loaded = load(table, List.of(PETS));
    var identity = IdentityCases.of(table, loaded);
    Oneof kind = petKind(loaded);

    assertFalse(identity.isIdentity(PETS, kind.iface(), member(kind, "dog")));
  }

  @Test
  void oneofFileOutsideTheRunIsNotIdentity() {
    FileTable table = table();
    // Dog is generated but the oneof's file is not: the case keeps its wrapper. The oneof comes
    // from a separate load so the run itself holds only dogs.proto.
    Oneof kind = petKind(load(table, List.of(PETS)));
    var identity = IdentityCases.of(table, load(table, List.of(DOGS)));

    assertFalse(identity.isIdentity(PETS, kind.iface(), member(kind, "dog")));
  }

  // --- the implements clause, aggregated per case type ---

  @Test
  void caseTypeImplementsEveryOneofInWhichItIsAnIdentityCase() {
    FileTable table = table();
    var loaded = load(table, List.of(PETS, DOGS));
    var identity = IdentityCases.of(table, loaded);

    // Dog is the case of Pet.kind, Shelter.slot, and the nested Shelter.Ward.item.
    assertEquals(
        List.of("pets.v1.Pet.Kind", "pets.v1.Shelter.Slot", "pets.v1.Shelter.Ward.Item"),
        fqns(identity.implementedBy("pets.v1.Dog")));
  }

  @Test
  void enumCaseTypeImplementsItsOneof() {
    FileTable table = table();
    var loaded = load(table, List.of(PETS, DOGS));
    var identity = IdentityCases.of(table, loaded);

    assertEquals(List.of("pets.v1.Pet.Kind"), fqns(identity.implementedBy("pets.v1.Color")));
  }

  @Test
  void caseTypeOutsideTheRunImplementsNothing() {
    FileTable table = table();
    // Dog's file is not generated, so no record in this run may claim to implement Pet.Kind.
    var loaded = load(table, List.of(PETS));
    var identity = IdentityCases.of(table, loaded);

    assertTrue(identity.implementedBy("pets.v1.Dog").isEmpty());
  }

  @Test
  void scalarAndForeignPackageTypesImplementNothing() {
    FileTable table = table();
    var loaded = load(table, List.of(PETS, DOGS, CATS));
    var identity = IdentityCases.of(table, loaded);

    assertTrue(identity.implementedBy("cats.v1.Cat").isEmpty());
    assertTrue(identity.implementedBy("google.protobuf.Timestamp").isEmpty());
  }

  @Test
  void resultIsIndependentOfTheOrderOfGeneratedFiles() {
    FileTable table = table();
    var forward = IdentityCases.of(table, load(table, List.of(PETS, DOGS)));
    var reversed = IdentityCases.of(table, load(table, List.of(DOGS, PETS)));

    assertEquals(
        fqns(forward.implementedBy("pets.v1.Dog")), fqns(reversed.implementedBy("pets.v1.Dog")));
    assertEquals(
        fqns(forward.implementedBy("pets.v1.Color")),
        fqns(reversed.implementedBy("pets.v1.Color")));
  }
}
