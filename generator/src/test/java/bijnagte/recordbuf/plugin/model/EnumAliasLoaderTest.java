package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumOptions;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The loader merges enum values that upper-case to one constant name into one constant at the first
 * value's position, and records the other spellings as its aliases. Values whose upper-cased names
 * differ stay distinct constants, and a value with no case collision gets no alias.
 */
class EnumAliasLoaderTest {

  private static Enum loadEnum(EnumValueDescriptorProto... values) {
    EnumDescriptorProto.Builder e =
        EnumDescriptorProto.newBuilder()
            .setName("Flavor")
            .setOptions(EnumOptions.newBuilder().setAllowAlias(true));
    for (EnumValueDescriptorProto v : values) {
      e.addValue(v);
    }
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("enum_alias.proto")
            .setPackage("pkg")
            .setSyntax("proto3")
            .addEnumType(e)
            .build();
    return new ModelLoader(new FileTable(), "com.example").load(fd).enums().get(0);
  }

  private static EnumValueDescriptorProto value(String name, int number) {
    return EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number).build();
  }

  @Test
  void caseCollidingValuesMergeIntoOneConstantAtTheFirstValuesPosition() {
    Enum out =
        loadEnum(
            value("ZERO", 0), value("Bar", 2), value("foo", 1), value("FOO", 1), value("Foo", 1));

    assertEquals(
        List.of("ZERO", "BAR", "FOO"),
        out.constants().stream().map(Enum.EnumConstant::name).toList(),
        "the three spellings of FOO share one constant, at the position of the first, foo");
    Enum.EnumConstant foo = out.constants().get(2);
    assertEquals(1, foo.number());
    assertEquals(List.of("foo", "Foo"), foo.aliases(), "the spellings other than FOO");
    assertEquals(List.of(), out.constants().get(0).aliases());
    assertEquals(List.of(), out.constants().get(1).aliases());
  }

  @Test
  void valuesWithDistinctUpperCasedNamesStayDistinctConstantsWithoutAliases() {
    Enum out = loadEnum(value("ALIAS_BAZ", 2), value("bAz", 2));

    assertEquals(
        List.of("ALIAS_BAZ", "BAZ"),
        out.constants().stream().map(Enum.EnumConstant::name).toList());
    assertEquals(List.of(), out.constants().get(0).aliases());
    assertEquals(List.of(), out.constants().get(1).aliases());
  }

  @Test
  void aSingleValueWithLowerCaseNameGetsNoAlias() {
    Enum out = loadEnum(value("bAz", 2));

    assertEquals("BAZ", out.constants().get(0).name());
    assertEquals(List.of(), out.constants().get(0).aliases());
  }
}
