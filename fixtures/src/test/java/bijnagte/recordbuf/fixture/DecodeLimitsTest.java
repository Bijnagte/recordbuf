package bijnagte.recordbuf.fixture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.runtime.DecodeLimits;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Structural checks of the decode limits a generated binary decode enforces, through the {@code
 * recursive_message} field of {@code TestAllTypesProto3}. The payloads are built with protobuf-java
 * (the reference), not by recordbuf, and are not goldens. The default limits and the 20,000-level
 * case are covered by the conformance mirror.
 */
class DecodeLimitsTest {

  /** The {@code optional_int32} value every level of a nested chain holds. */
  private static final int LEVEL_VALUE = 123;

  /** A depth limit above the default, so a chain the default rejects decodes under it. */
  private static final int RAISED_DEPTH = 200;

  /** A depth limit below the default, for the rejection checks. */
  private static final int LOWERED_DEPTH = 5;

  /** A nesting depth between the lowered and the raised depth limits. */
  private static final int MIDDLE_DEPTH = 150;

  /** The bytes a one-level chain hands to its nested decode: one {@code optional_int32} field. */
  private static final long NESTED_BYTES_OF_ONE_LEVEL = 2;

  /** {@code recursive_message}'s field number in {@code TestAllTypesProto3}. */
  private static final int RECURSIVE_MESSAGE_FIELD = 27;

  /** {@code map_string_nested_message}'s field number in {@code TestAllTypesProto3}. */
  private static final int MAP_STRING_NESTED_MESSAGE_FIELD = 71;

  @Test
  void raisedDepth_admitsNestingTheDefaultRejects() throws IOException {
    byte[] input = nestedChain(MIDDLE_DEPTH);
    assertThrows(InvalidProtocolBufferException.class, () -> TestAllTypesProto3.fromBytes(input));

    TestAllTypesProto3 decoded = decodeWith(depthLimit(RAISED_DEPTH), input);

    assertArrayEquals(input, decoded.toByteArray(), "output is not the input wire format");
  }

  @Test
  void loweredDepth_rejectsOneLevelBeyondTheLimit() throws IOException {
    byte[] atLimit = nestedChain(LOWERED_DEPTH);
    byte[] oneBeyond = nestedChain(LOWERED_DEPTH + 1);

    assertArrayEquals(
        atLimit, decodeWith(depthLimit(LOWERED_DEPTH), atLimit).toByteArray(), "at the limit");
    assertThrows(
        InvalidProtocolBufferException.class,
        () -> decodeWith(depthLimit(LOWERED_DEPTH), oneBeyond),
        "one level beyond the limit");
  }

  @Test
  void nestedBytes_exactlyAtTheCapPass_oneOverThrows() throws IOException {
    byte[] input = nestedChain(1);

    assertArrayEquals(
        input,
        decodeWith(sizeLimit(NESTED_BYTES_OF_ONE_LEVEL), input).toByteArray(),
        "nested bytes at the cap");
    assertThrows(
        InvalidProtocolBufferException.class,
        () -> decodeWith(sizeLimit(NESTED_BYTES_OF_ONE_LEVEL - 1), input),
        "nested bytes one over the cap");
  }

  @Test
  void flatMessage_isUnaffectedByTinySizeCap() throws IOException {
    byte[] input = flatMessage();

    assertArrayEquals(
        input,
        decodeWith(sizeLimit(1), input).toByteArray(),
        "a flat message has no nested bytes to charge");
  }

  @Test
  void rejectedDecode_leavesTheThreadAtTopLevel() throws IOException {
    byte[] tooDeep = nestedChain(LOWERED_DEPTH + 1);
    byte[] atLimit = nestedChain(LOWERED_DEPTH);
    assertThrows(
        InvalidProtocolBufferException.class, () -> decodeWith(depthLimit(LOWERED_DEPTH), tooDeep));

    assertArrayEquals(
        atLimit,
        decodeWith(depthLimit(LOWERED_DEPTH), atLimit).toByteArray(),
        "the next top-level decode starts at depth zero");
  }

  /**
   * A key-only entry in a message-valued map adds no nesting level: its absent value decodes as a
   * default message one level below the deepest message, so the deepest message at the default
   * depth limit still decodes.
   */
  @Test
  void keyOnlyMapEntryAtTheDepthLimit_decodesUnderDefaults() throws IOException {
    int deepestDepth = DecodeLimits.DEFAULT_MAX_DEPTH - 1;
    TestAllTypesProto3 decoded = TestAllTypesProto3.fromBytes(keyOnlyMapEntryChain(deepestDepth));

    TestAllTypesProto3 deepest = decoded;
    for (int level = 0; level < deepestDepth; level++) {
      deepest = deepest.recursiveMessage();
    }
    assertTrue(deepest.mapStringNestedMessage().containsKey("key"), "the key-only entry decodes");
  }

  /** One level deeper, the same key-only entry's default value is over the depth limit. */
  @Test
  void keyOnlyMapEntryOneLevelBeyondTheDepthLimit_isRejected() throws IOException {
    byte[] oneBeyond = keyOnlyMapEntryChain(DecodeLimits.DEFAULT_MAX_DEPTH);

    assertThrows(
        InvalidProtocolBufferException.class, () -> TestAllTypesProto3.fromBytes(oneBeyond));
  }

  /** Decodes {@code input} with {@code limits} bound, as a caller overrides the defaults. */
  private static TestAllTypesProto3 decodeWith(DecodeLimits limits, byte[] input)
      throws IOException {
    return ScopedValue.where(DecodeLimits.CURRENT, limits)
        .call(() -> TestAllTypesProto3.fromBytes(input));
  }

  private static DecodeLimits depthLimit(int maxDepth) {
    return new DecodeLimits(maxDepth, DecodeLimits.DEFAULT_MAX_TOTAL_BYTES);
  }

  private static DecodeLimits sizeLimit(long maxTotalBytes) {
    return new DecodeLimits(DecodeLimits.DEFAULT_MAX_DEPTH, maxTotalBytes);
  }

  /** A top-level message with {@code levels} nested {@code recursive_message} levels below it. */
  private static byte[] nestedChain(int levels) {
    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 message = levelBuilder().build();
    for (int level = 0; level < levels; level++) {
      message = levelBuilder().setRecursiveMessage(message).build();
    }
    return message.toByteArray();
  }

  /** A message with no nesting, holding a field of each shape the test uses. */
  private static byte[] flatMessage() {
    return com.google.protobuf_test_messages.proto3.TestAllTypesProto3.newBuilder()
        .setOptionalInt32(LEVEL_VALUE)
        .setOptionalString("flat")
        .addRepeatedInt32(1)
        .addRepeatedInt32(2)
        .addRepeatedInt32(3)
        .build()
        .toByteArray();
  }

  private static com.google.protobuf_test_messages.proto3.TestAllTypesProto3.Builder
      levelBuilder() {
    return com.google.protobuf_test_messages.proto3.TestAllTypesProto3.newBuilder()
        .setOptionalInt32(LEVEL_VALUE);
  }

  /**
   * A top-level message whose {@code recursive_message} chain is {@code depth} levels deep, with a
   * key-only entry in {@code map_string_nested_message} on the deepest message.
   */
  private static byte[] keyOnlyMapEntryChain(int depth) throws IOException {
    ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
    CodedOutputStream entry = CodedOutputStream.newInstance(entryBytes);
    entry.writeString(1, "key");
    entry.flush();
    byte[] wire = lengthDelimited(MAP_STRING_NESTED_MESSAGE_FIELD, entryBytes.toByteArray());
    for (int level = 0; level < depth; level++) {
      wire = lengthDelimited(RECURSIVE_MESSAGE_FIELD, wire);
    }
    return wire;
  }

  /** The wire bytes of one length-delimited field holding {@code value}. */
  private static byte[] lengthDelimited(int fieldNumber, byte[] value) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeByteArray(fieldNumber, value);
    out.flush();
    return bytes.toByteArray();
  }
}
