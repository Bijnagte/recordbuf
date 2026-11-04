package bijnagte.recordbuf.plugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import org.junit.jupiter.api.Test;

/**
 * R3/R4/R5: the run-wide {@code package_suffix} is composed as the final segment of every file's
 * Java package. An absent/blank suffix is a byte-for-byte no-op (R5); a non-empty suffix appends
 * with a single dot separator so a record can live in a {@code .rec} sibling of its protobuf-java
 * reference.
 */
class FileTablePackageSuffixTest {

  private static FileDescriptorProto protoWithJavaPackage(String javaPackage) {
    FileOptions.Builder options = FileOptions.newBuilder().setJavaPackage(javaPackage);
    return FileDescriptorProto.newBuilder()
        .setName("sample.proto")
        .setPackage("com.example")
        .setOptions(options.build())
        .build();
  }

  @Test
  void emptySuffixIsANoOp() {
    String base = "bijnagte.recordbuf.fixture.message";
    assertEquals(base, FileTable.applyPackageSuffix(base, ""));
    assertEquals(base, FileTable.applyPackageSuffix(base, null));
  }

  @Test
  void nonEmptySuffixAppendsWithSingleDotSeparator() {
    assertEquals(
        "bijnagte.recordbuf.fixture.message.rec",
        FileTable.applyPackageSuffix("bijnagte.recordbuf.fixture.message", "rec"));
  }

  @Test
  void emptyBaseWithSuffixYieldsBareSuffix() {
    assertEquals("rec", FileTable.applyPackageSuffix("", "rec"));
    assertEquals("rec", FileTable.applyPackageSuffix(null, "rec"));
  }

  @Test
  void fileTableCarriesSuffixThroughJavaPackage() {
    FileTable table = new FileTable("rec");
    assertEquals("com.example.rec", table.javaPackage(protoWithJavaPackage("com.example")));
  }

  @Test
  void noArgConstructorLeavesPackageUnchanged() {
    FileTable table = new FileTable();
    assertEquals("com.example", table.javaPackage(protoWithJavaPackage("com.example")));
  }
}
