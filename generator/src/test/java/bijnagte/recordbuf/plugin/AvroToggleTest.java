package bijnagte.recordbuf.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.plugin.bridge.BridgeContext;
import bijnagte.recordbuf.plugin.model.AvroToggle;
import bijnagte.recordbuf.plugin.model.FileTable;
import bijnagte.recordbuf.plugin.model.ModelLoader;
import bijnagte.recordbuf.plugin.model.ProtoFile;
import bijnagte.recordbuf.plugin.model.ProtoFileBuilder;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.UninterpretedOption;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

/**
 * R1: the per-file {@code recordbuf.avro} option + the global {@code --avro} flag + the built-in
 * default, resolved in the {@code file > flag > default(on)} order. A per-file option (an explicit
 * {@code ON}/{@code OFF}) wins over the flag and the default; a {@code NONE} option or an absent
 * option falls through to the flag, then the default.
 */
class AvroToggleTest {

  // --- (a) the per-file option parses to the model's AvroToggle tri-state ---

  private static AvroToggle toggleFromVarint(int control) throws Exception {
    UnknownFieldSet uf =
        UnknownFieldSet.newBuilder()
            .addField(50003, UnknownFieldSet.Field.newBuilder().addVarint(control).build())
            .build();
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("f.proto")
            .setOptions(FileOptions.newBuilder().mergeFrom(uf.toByteArray()))
            .build();
    return new ModelLoader(new FileTable(""), "pkg").load(fd).avroToggle();
  }

  private static AvroToggle toggleFromUninterpreted(int control) {
    UninterpretedOption opt =
        UninterpretedOption.newBuilder()
            .addName(
                UninterpretedOption.NamePart.newBuilder()
                    .setNamePart("recordbuf")
                    .setIsExtension(false))
            .addName(
                UninterpretedOption.NamePart.newBuilder().setNamePart("avro").setIsExtension(false))
            .setPositiveIntValue(control)
            .build();
    FileDescriptorProto fd =
        FileDescriptorProto.newBuilder()
            .setName("f.proto")
            .setOptions(FileOptions.newBuilder().addUninterpretedOption(opt).build())
            .build();
    return new ModelLoader(new FileTable(""), "pkg").load(fd).avroToggle();
  }

  /** A minimal ProtoFile with only its avroToggle set, for the enablement matrix. */
  private static ProtoFile pf(AvroToggle toggle) {
    return ProtoFileBuilder.builder().avroToggle(toggle).build();
  }

  @Test
  void varintPathParsesToToggle() throws Exception {
    assertEquals(AvroToggle.ON, toggleFromVarint(1));
    assertEquals(AvroToggle.OFF, toggleFromVarint(2));
    // RECORDBUF_AVRO_NONE (0) and an absent value both resolve NEUTRAL.
    assertEquals(AvroToggle.NEUTRAL, toggleFromVarint(0));
  }

  @Test
  void uninterpretedFallbackParsesToToggle() {
    assertEquals(AvroToggle.ON, toggleFromUninterpreted(1));
    assertEquals(AvroToggle.NEUTRAL, toggleFromUninterpreted(0));
  }

  // --- (b) the full matrix: file > flag > default(on) ---

  @Test
  void perFileOnOffWinsOverFlag() {
    // file ON → enabled even when the flag is off.
    assertTrue(BridgeContext.parse("avro=off").avroEnabled(pf(AvroToggle.ON)));
    // file OFF → disabled even when the flag (and default) is on.
    assertFalse(BridgeContext.parse("avro=on").avroEnabled(pf(AvroToggle.OFF)));
    assertFalse(BridgeContext.empty().avroEnabled(pf(AvroToggle.OFF)));
  }

  @Test
  void flagAndDefaultDecideWhenPerFileAbsent() {
    assertTrue(BridgeContext.empty().avroEnabled(pf(AvroToggle.NEUTRAL))); // default on
    assertTrue(BridgeContext.parse("avro=on").avroEnabled(pf(AvroToggle.NEUTRAL)));
    assertTrue(BridgeContext.parse("avro=true").avroEnabled(pf(AvroToggle.NEUTRAL)));
    assertTrue(BridgeContext.parse("avro=1").avroEnabled(pf(AvroToggle.NEUTRAL)));
    assertTrue(BridgeContext.parse("avro").avroEnabled(pf(AvroToggle.NEUTRAL))); // bare flag → on
    assertFalse(BridgeContext.parse("avro=off").avroEnabled(pf(AvroToggle.NEUTRAL)));
    assertFalse(BridgeContext.parse("avro=false").avroEnabled(pf(AvroToggle.NEUTRAL)));
    assertFalse(BridgeContext.parse("avro=0").avroEnabled(pf(AvroToggle.NEUTRAL)));
    // an unrelated key leaves the avro default on.
    assertTrue(BridgeContext.parse("package_suffix=.rec").avroEnabled(pf(AvroToggle.NEUTRAL)));
  }

  /** NONE behaves like "unset": it falls through to the flag / default. */
  @Test
  void noneOptionFallsThrough() {
    assertTrue(BridgeContext.empty().avroEnabled(pf(AvroToggle.NEUTRAL)));
  }
}
