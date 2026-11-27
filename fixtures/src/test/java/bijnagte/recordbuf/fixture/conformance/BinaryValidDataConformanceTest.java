package bijnagte.recordbuf.fixture.conformance;

import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_FIXED32;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_FIXED64;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_LENGTH_DELIMITED;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_VARINT;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.cat;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.dbl;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.delim;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.flt;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.len;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.longvarint;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.tag;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.u32;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.u64;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.varint;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.zz32;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.zz64;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;

/**
 * Mirrors the valid-data scenarios of the official protobuf conformance suite at {@code v36.2}
 * (KTD8): {@code TestValidDataForType}, {@code TestValidDataForRepeatedScalarMessage}, {@code
 * TestValidDataForMapType}, {@code TestOverwriteMessageValueMap} and {@code
 * TestValidDataForOneofType}, against the vendored proto3 {@code TestAllTypesProto3}. The value
 * tables, the field selection and the expected payloads are ported from {@code
 * binary_json_conformance_suite.cc}.
 *
 * <p>Each scenario decodes its input bytes with the recordbuf record, re-encodes the record, and
 * compares the output with the upstream expected payload through the protobuf-java reference: both
 * are parsed with {@code parseFrom} and the messages must be equal, which is how the runner
 * compares a {@code ProtobufOutput}. A scenario upstream runs as a {@code ValidDataScalarBinary} or
 * {@code ValidDataRepeated...DefaultOutput} case ({@code RunValidBinaryProtobufTest}) also needs
 * the same wire bytes (the runner's {@code require_same_wire_format}), so the output bytes must
 * equal the expected payload byte for byte.
 *
 * <p>A scenario recordbuf cannot satisfy yet is listed in {@link #KNOWN_GAPS} by its upstream case
 * name and the id of its gap-ledger row. It runs, and is reported as aborted with that id; if it
 * passes, the test fails, so closing a gap forces removing the entry.
 *
 * <p>{@code TestValidDataForOneofType} targets the oneof of {@code TestAllTypesProto3}. Each oneof
 * scenario is a dynamic container of two tests, as in {@link JsonConformance}: {@code portCheck}
 * runs against protobuf-java alone and proves the port is faithful; {@code recordbuf} runs the
 * scenario through the record like the other cases. Upstream also runs each non-{@code Binary} case
 * with JSON output; as in the other binary mirrors, only the protobuf-in, protobuf-out settings are
 * ported. {@code oneof_null_value} (field 120) is not selected by any of these scenarios, so the
 * {@code null-value-field} gap does not apply.
 */
class BinaryValidDataConformanceTest {

  private static final int INT_BITS = Integer.SIZE;

  /** How the recordbuf output is compared with the expected payload. */
  private enum Comparison {
    /** The parsed messages are equal ({@code RunValidProtobufTest}). */
    MESSAGE,
    /** The parsed messages are equal and the wire bytes are equal ({@code Binary} variants). */
    WIRE_BYTES
  }

  /** One mirrored scenario: its upstream case name, input payload and expected payload. */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record Case(String name, byte[] input, byte[] expected, Comparison comparison) {}

  /**
   * One mirrored oneof scenario: its upstream case name, the oneof member the expected payload
   * leaves set, its input payload and its expected payload.
   */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record OneofCase(
      String name, String member, byte[] input, byte[] expected, Comparison comparison) {}

  /**
   * A member type of the oneof of {@code TestAllTypesProto3}: its upper-cased upstream type name,
   * proto field name, number, wire type and the upstream default and non-default encodings.
   */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record OneofType(
      String name,
      String member,
      int number,
      int wireType,
      byte[] defaultValue,
      byte[] nonDefaultValue) {}

  /** An input and the payload upstream expects it to be written back as. */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record Value(byte[] input, byte[] expected) {

    static Value same(byte[] bytes) {
      return new Value(bytes, bytes);
    }
  }

  /**
   * A scalar, string, bytes, enum or message field type of {@code TestAllTypesProto3}.
   *
   * @param name the upper-cased upstream type name, for example {@code INT32}
   * @param wireType the wire type of one element
   * @param singular field number of the first singular field of this type
   * @param repeated field number of the first repeated field of this type (the packed-by-default
   *     one in proto3)
   * @param unpacked field number of the first {@code [packed = false]} repeated field of this type;
   *     zero for a type that is not packable
   * @param protoDefault the encoding of the proto3 default value, or {@code null} for a message,
   *     which has no default encoding
   * @param values the upstream value table
   */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record ScalarType(
      String name,
      int wireType,
      int singular,
      int repeated,
      int unpacked,
      byte[] protoDefault,
      List<Value> values) {

    boolean packable() {
      return unpacked != 0;
    }
  }

  /** A map key or value type: its wire type and the upstream default and non-default encodings. */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record MapType(String name, int wireType, byte[] defaultValue, byte[] nonDefaultValue) {}

  /** A {@code TestAllTypesProto3} map field the record has: its key and value types and number. */
  private record MapField(MapType key, MapType value, int number) {}

  private static final long INT64_MAX = Long.MAX_VALUE;
  private static final long INT64_MIN = Long.MIN_VALUE;
  private static final long UINT64_MAX = -1L;
  private static final int INT32_MAX = Integer.MAX_VALUE;
  private static final int INT32_MIN = Integer.MIN_VALUE;
  private static final long UINT32_MAX = 0xFFFFFFFFL;
  private static final long TWO_POW_33 = 1L << 33;

  private static final byte[] ZERO_VARINT = varint(0);
  private static final byte[] EMPTY_DELIM = delim(new byte[0]);

  /** The upstream message-typed element: {@code NestedMessage.a = 1234}. */
  private static final byte[] NESTED_MESSAGE_A_1234 =
      delim(cat(tag(1, WIRETYPE_VARINT), varint(1234)));

  private static final List<ScalarType> SCALAR_TYPES =
      List.of(
          new ScalarType(
              "DOUBLE",
              WIRETYPE_FIXED64,
              12,
              42,
              100,
              dbl(0),
              List.of(
                  Value.same(dbl(0)),
                  Value.same(dbl(0.1)),
                  Value.same(dbl(1.7976931348623157e+308)),
                  Value.same(dbl(Double.MIN_NORMAL)))),
          new ScalarType(
              "FLOAT",
              WIRETYPE_FIXED32,
              11,
              41,
              99,
              flt(0),
              List.of(
                  Value.same(flt(0)),
                  Value.same(flt(0.1f)),
                  Value.same(flt((float) 1.00000075e-36)),
                  Value.same(flt((float) 3.402823e+38)),
                  Value.same(flt(Float.MIN_NORMAL)))),
          new ScalarType(
              "INT64",
              WIRETYPE_VARINT,
              2,
              32,
              90,
              ZERO_VARINT,
              List.of(
                  Value.same(varint(0)),
                  Value.same(varint(12345)),
                  Value.same(varint(INT64_MAX)),
                  Value.same(varint(INT64_MIN)))),
          new ScalarType(
              "UINT64",
              WIRETYPE_VARINT,
              4,
              34,
              92,
              ZERO_VARINT,
              List.of(
                  Value.same(varint(0)),
                  Value.same(varint(12345)),
                  Value.same(varint(UINT64_MAX)))),
          new ScalarType(
              "INT32",
              WIRETYPE_VARINT,
              1,
              31,
              89,
              ZERO_VARINT,
              List.of(
                  Value.same(varint(0)),
                  Value.same(varint(12345)),
                  new Value(longvarint(12345, 2), varint(12345)),
                  new Value(longvarint(12345, 7), varint(12345)),
                  Value.same(varint(INT32_MAX)),
                  Value.same(varint(INT32_MIN)),
                  new Value(varint(TWO_POW_33), varint(0)),
                  new Value(varint(TWO_POW_33 - 1), varint(-1)),
                  new Value(varint(INT64_MAX), varint(-1)),
                  new Value(varint(INT64_MIN + 1), varint(1)))),
          new ScalarType(
              "UINT32",
              WIRETYPE_VARINT,
              3,
              33,
              91,
              ZERO_VARINT,
              List.of(
                  Value.same(varint(0)),
                  Value.same(varint(12345)),
                  new Value(longvarint(12345, 2), varint(12345)),
                  new Value(longvarint(12345, 7), varint(12345)),
                  Value.same(varint(UINT32_MAX)),
                  new Value(varint(TWO_POW_33), varint(0)),
                  new Value(varint(TWO_POW_33 + 1), varint(1)),
                  new Value(varint(TWO_POW_33 - 1), varint((1L << INT_BITS) - 1)),
                  new Value(varint(INT64_MAX), varint((1L << INT_BITS) - 1)),
                  new Value(varint(INT64_MIN + 1), varint(1)))),
          new ScalarType(
              "FIXED64",
              WIRETYPE_FIXED64,
              8,
              38,
              96,
              u64(0),
              List.of(Value.same(u64(0)), Value.same(u64(12345)), Value.same(u64(UINT64_MAX)))),
          new ScalarType(
              "FIXED32",
              WIRETYPE_FIXED32,
              7,
              37,
              95,
              u32(0),
              List.of(
                  Value.same(u32(0)), Value.same(u32(12345)), Value.same(u32((int) UINT32_MAX)))),
          new ScalarType(
              "SFIXED64",
              WIRETYPE_FIXED64,
              10,
              40,
              98,
              u64(0),
              List.of(
                  Value.same(u64(0)),
                  Value.same(u64(12345)),
                  Value.same(u64(INT64_MAX)),
                  Value.same(u64(INT64_MIN)))),
          new ScalarType(
              "SFIXED32",
              WIRETYPE_FIXED32,
              9,
              39,
              97,
              u32(0),
              List.of(
                  Value.same(u32(0)),
                  Value.same(u32(12345)),
                  Value.same(u32(INT32_MAX)),
                  Value.same(u32(INT32_MIN)))),
          // Bools are written as 0 or 1; a parser must read any nonzero varint as true.
          new ScalarType(
              "BOOL",
              WIRETYPE_VARINT,
              13,
              43,
              101,
              ZERO_VARINT,
              List.of(
                  Value.same(varint(0)),
                  Value.same(varint(1)),
                  new Value(varint(-1), varint(1)),
                  new Value(varint(12345678), varint(1)),
                  new Value(varint(TWO_POW_33), varint(1)),
                  new Value(varint(INT64_MAX), varint(1)),
                  new Value(varint(INT64_MIN), varint(1)))),
          new ScalarType(
              "SINT32",
              WIRETYPE_VARINT,
              5,
              35,
              93,
              ZERO_VARINT,
              List.of(
                  Value.same(zz32(0)),
                  Value.same(zz32(12345)),
                  Value.same(zz32(INT32_MAX)),
                  Value.same(zz32(INT32_MIN)),
                  new Value(zz64(INT32_MAX + 2L), zz32(1)))),
          new ScalarType(
              "SINT64",
              WIRETYPE_VARINT,
              6,
              36,
              94,
              ZERO_VARINT,
              List.of(
                  Value.same(zz64(0)),
                  Value.same(zz64(12345)),
                  Value.same(zz64(INT64_MAX)),
                  Value.same(zz64(INT64_MIN)))),
          new ScalarType(
              "STRING",
              WIRETYPE_LENGTH_DELIMITED,
              14,
              44,
              0,
              EMPTY_DELIM,
              List.of(
                  Value.same(delim("")),
                  Value.same(delim("Hello world!")),
                  Value.same(delim("'\"?\\\u0007\b\f\n\r\t\u000b")), // escapes
                  Value.same(delim("\u8C37\u6B4C")), // "Google" in Chinese
                  Value.same(delim("\u8C37\u6B4C")), // upstream's unicode-escape spelling
                  Value.same(delim("\u8c37\u6b4c")), // upstream's lowercase spelling
                  Value.same(delim("\uD83D\uDE01")))), // emoji
          new ScalarType(
              "BYTES",
              WIRETYPE_LENGTH_DELIMITED,
              15,
              45,
              0,
              EMPTY_DELIM,
              List.of(
                  Value.same(delim("")),
                  Value.same(delim("Hello world!")),
                  Value.same(delim(new byte[] {0x01, 0x02})),
                  Value.same(delim(new byte[] {(byte) 0xfb})))),
          new ScalarType(
              "ENUM",
              WIRETYPE_VARINT,
              21,
              51,
              102,
              ZERO_VARINT,
              List.of(
                  Value.same(varint(0)),
                  Value.same(varint(1)),
                  Value.same(varint(2)),
                  Value.same(varint(-1)),
                  new Value(varint(INT64_MAX), varint(-1)),
                  new Value(varint(INT64_MIN + 1), varint(1)))),
          new ScalarType(
              "MESSAGE",
              WIRETYPE_LENGTH_DELIMITED,
              18,
              48,
              0,
              null,
              List.of(Value.same(delim("")), Value.same(NESTED_MESSAGE_A_1234))));

  private static final MapType INT32_MAP_TYPE = varintMapType("INT32");
  private static final MapType INT64_MAP_TYPE = varintMapType("INT64");
  private static final MapType UINT32_MAP_TYPE = varintMapType("UINT32");
  private static final MapType UINT64_MAP_TYPE = varintMapType("UINT64");
  private static final MapType SINT32_MAP_TYPE =
      new MapType("SINT32", WIRETYPE_VARINT, zz32(0), zz32(1));
  private static final MapType SINT64_MAP_TYPE =
      new MapType("SINT64", WIRETYPE_VARINT, zz64(0), zz64(1));
  private static final MapType FIXED32_MAP_TYPE =
      new MapType("FIXED32", WIRETYPE_FIXED32, u32(0), u32(1));
  private static final MapType FIXED64_MAP_TYPE =
      new MapType("FIXED64", WIRETYPE_FIXED64, u64(0), u64(1));
  private static final MapType SFIXED32_MAP_TYPE =
      new MapType("SFIXED32", WIRETYPE_FIXED32, u32(0), u32(1));
  private static final MapType SFIXED64_MAP_TYPE =
      new MapType("SFIXED64", WIRETYPE_FIXED64, u64(0), u64(1));
  private static final MapType FLOAT_MAP_TYPE =
      new MapType("FLOAT", WIRETYPE_FIXED32, flt(0), flt(1));
  private static final MapType DOUBLE_MAP_TYPE =
      new MapType("DOUBLE", WIRETYPE_FIXED64, dbl(0), dbl(1));
  private static final MapType BOOL_MAP_TYPE = varintMapType("BOOL");
  private static final MapType STRING_MAP_TYPE =
      new MapType("STRING", WIRETYPE_LENGTH_DELIMITED, delim(""), delim("a"));
  private static final MapType BYTES_MAP_TYPE =
      new MapType("BYTES", WIRETYPE_LENGTH_DELIMITED, delim(""), delim("a"));
  private static final MapType MESSAGE_MAP_TYPE =
      new MapType("MESSAGE", WIRETYPE_LENGTH_DELIMITED, delim(""), NESTED_MESSAGE_A_1234);
  private static final MapType ENUM_MAP_TYPE = varintMapType("ENUM");

  /** The map fields {@code TestAllTypesProto3} has, in the upstream order. */
  private static final List<MapField> MAP_FIELDS =
      List.of(
          new MapField(INT32_MAP_TYPE, INT32_MAP_TYPE, 56),
          new MapField(INT64_MAP_TYPE, INT64_MAP_TYPE, 57),
          new MapField(UINT32_MAP_TYPE, UINT32_MAP_TYPE, 58),
          new MapField(UINT64_MAP_TYPE, UINT64_MAP_TYPE, 59),
          new MapField(SINT32_MAP_TYPE, SINT32_MAP_TYPE, 60),
          new MapField(SINT64_MAP_TYPE, SINT64_MAP_TYPE, 61),
          new MapField(FIXED32_MAP_TYPE, FIXED32_MAP_TYPE, 62),
          new MapField(FIXED64_MAP_TYPE, FIXED64_MAP_TYPE, 63),
          new MapField(SFIXED32_MAP_TYPE, SFIXED32_MAP_TYPE, 64),
          new MapField(SFIXED64_MAP_TYPE, SFIXED64_MAP_TYPE, 65),
          new MapField(INT32_MAP_TYPE, FLOAT_MAP_TYPE, 66),
          new MapField(INT32_MAP_TYPE, DOUBLE_MAP_TYPE, 67),
          new MapField(BOOL_MAP_TYPE, BOOL_MAP_TYPE, 68),
          new MapField(STRING_MAP_TYPE, STRING_MAP_TYPE, 69),
          new MapField(STRING_MAP_TYPE, BYTES_MAP_TYPE, 70),
          new MapField(STRING_MAP_TYPE, MESSAGE_MAP_TYPE, 71),
          new MapField(STRING_MAP_TYPE, ENUM_MAP_TYPE, 73));

  /**
   * The oneof member types, in the order {@code TestValidDataForOneofType} runs them. Upstream
   * picks the member of a type with {@code GetFieldForOneofType}: the first oneof field of that
   * type.
   */
  private static final List<OneofType> ONEOF_TYPES =
      List.of(
          varintOneofType("UINT32", "oneof_uint32", 111),
          varintOneofType("BOOL", "oneof_bool", 115),
          varintOneofType("UINT64", "oneof_uint64", 116),
          new OneofType("FLOAT", "oneof_float", 117, WIRETYPE_FIXED32, flt(0), flt(1)),
          new OneofType("DOUBLE", "oneof_double", 118, WIRETYPE_FIXED64, dbl(0), dbl(1)),
          new OneofType(
              "STRING", "oneof_string", 113, WIRETYPE_LENGTH_DELIMITED, delim(""), delim("a")),
          new OneofType(
              "BYTES", "oneof_bytes", 114, WIRETYPE_LENGTH_DELIMITED, delim(""), delim("a")),
          varintOneofType("ENUM", "oneof_enum", 119),
          new OneofType(
              "MESSAGE",
              "oneof_nested_message",
              112,
              WIRETYPE_LENGTH_DELIMITED,
              delim(""),
              NESTED_MESSAGE_A_1234));

  private static final String ONEOF_NAME = "oneof_field";

  /**
   * The scenarios that fail today, by upstream case name, with the ledger id of the gap that
   * explains each. Keep this small and explicit: when a gap closes, its scenarios start passing,
   * the test then fails, and the entries are removed here.
   */
  private static final Map<String, String> KNOWN_GAPS = knownGaps();

  private static final List<Case> ALL_CASES = allCases();

  /** {@code TestValidDataForType}: singular, repeated, packed and unpacked data per field type. */
  @TestFactory
  Stream<DynamicNode> validDataForType() {
    return SCALAR_TYPES.stream()
        .map(type ->
            DynamicContainer.dynamicContainer(
                "ValidData." + type.name(),
                validDataForTypeCases(type).stream().map(this::dynamicTest)));
  }

  /**
   * {@code TestValidDataForRepeatedScalarMessage}: two occurrences of the singular message field
   * are merged, field by field, into one message.
   */
  @TestFactory
  Stream<DynamicNode> validDataForRepeatedScalarMessage() {
    return Stream.of(repeatedScalarMessageMerge()).map(this::dynamicTest);
  }

  /** {@code TestValidDataForMapType}: map entries per key and value type. */
  @TestFactory
  Stream<DynamicNode> validDataForMapType() {
    return MAP_FIELDS.stream()
        .map(field ->
            DynamicContainer.dynamicContainer(
                "ValidDataMap." + field.key().name() + "." + field.value().name(),
                validDataForMapTypeCases(field).stream().map(this::dynamicTest)));
  }

  /**
   * {@code TestValidDataForOneofType}: a oneof member written with its default value, a non-default
   * value, twice, and after a member of another type, for each member type.
   */
  @TestFactory
  Stream<DynamicNode> validDataForOneofType() {
    return ONEOF_TYPES.stream()
        .map(type ->
            DynamicContainer.dynamicContainer(
                "ValidDataOneof." + type.name(),
                validDataForOneofTypeCases(type).stream().map(this::oneofContainer)));
  }

  /**
   * {@code TestOverwriteMessageValueMap}: the second entry for a key replaces the first, and a
   * message value is replaced rather than merged.
   */
  @TestFactory
  Stream<DynamicNode> overwriteMessageValueMap() {
    return Stream.of(overwriteMessageValueMapCase()).map(this::dynamicTest);
  }

  /** Every known-gap entry names a scenario this test mirrors, so none can go stale unnoticed. */
  @Test
  void knownGaps_nameMirroredScenarios() {
    Set<String> names = new LinkedHashSet<>();
    ALL_CASES.forEach(c -> names.add(c.name()));
    List<String> stale = KNOWN_GAPS.keySet().stream().filter(n -> !names.contains(n)).toList();

    assertEquals(List.of(), stale, "KNOWN_GAPS entries that name no mirrored scenario");
  }

  private DynamicTest dynamicTest(Case scenario) {
    return DynamicTest.dynamicTest(scenario.name(), () -> run(scenario));
  }

  private DynamicNode oneofContainer(OneofCase scenario) {
    return DynamicContainer.dynamicContainer(
        scenario.name(),
        Stream.of(
            DynamicTest.dynamicTest("portCheck", () -> oneofPortCheck(scenario)),
            DynamicTest.dynamicTest("recordbuf", () -> run(oneofRecordbufHalf(scenario)))));
  }

  /**
   * The protobuf-java half of a oneof scenario: the reference must parse the input to the expected
   * message with the scenario's member set and nothing unknown, and for a {@code Binary} scenario
   * write that message back as exactly the expected payload.
   */
  private static void oneofPortCheck(OneofCase scenario) throws IOException {
    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 expected =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(scenario.expected());
    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 actual =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(scenario.input());

    assertEquals(expected, actual, "protobuf-java does not read the input as the expected message");
    OneofDescriptor oneof =
        actual.getDescriptorForType().getOneofs().stream()
            .filter(o -> o.getName().equals(ONEOF_NAME))
            .findFirst()
            .orElseThrow();
    FieldDescriptor set = actual.getOneofFieldDescriptor(oneof);
    assertEquals(scenario.member(), set == null ? null : set.getName(), "the member left set");
    assertTrue(actual.getUnknownFields().asMap().isEmpty(), "the input has an unknown field");
    if (scenario.comparison() == Comparison.WIRE_BYTES) {
      assertArrayEquals(
          scenario.expected(), actual.toByteArray(), "protobuf-java writes another payload");
    }
  }

  /** The recordbuf half of a oneof scenario, run like every other case of this mirror. */
  private static Case oneofRecordbufHalf(OneofCase scenario) {
    return new Case(scenario.name(), scenario.input(), scenario.expected(), scenario.comparison());
  }

  private static void run(Case scenario) throws IOException {
    String gapId = KNOWN_GAPS.get(scenario.name());
    if (gapId == null) {
      assertConforms(scenario);
      return;
    }
    try {
      assertConforms(scenario);
    } catch (AssertionError | IOException | RuntimeException e) {
      throw new TestAbortedException("gap " + gapId + ": " + e.getMessage(), e);
    }
    fail(
        scenario.name()
            + " passes now: gap "
            + gapId
            + " no longer explains it, remove it from KNOWN_GAPS");
  }

  /**
   * Decodes the input with the record, re-encodes it, and compares the output with the expected
   * payload through protobuf-java.
   */
  private static void assertConforms(Case scenario) throws IOException {
    byte[] output = TestAllTypesProto3.fromBytes(scenario.input()).toByteArray();

    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 expected =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(scenario.expected());
    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 actual =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(output);
    assertEquals(expected, actual, "output is not equivalent to the reference message");
    if (scenario.comparison() == Comparison.WIRE_BYTES) {
      assertArrayEquals(scenario.expected(), output, "output is not the expected wire format");
    }
  }

  private static List<Case> allCases() {
    List<Case> cases = new ArrayList<>();
    for (ScalarType type : SCALAR_TYPES) {
      cases.addAll(validDataForTypeCases(type));
    }
    cases.add(repeatedScalarMessageMerge());
    cases.add(overwriteMessageValueMapCase());
    for (MapField field : MAP_FIELDS) {
      cases.addAll(validDataForMapTypeCases(field));
    }
    for (OneofType type : ONEOF_TYPES) {
      validDataForOneofTypeCases(type).forEach(c -> cases.add(oneofRecordbufHalf(c)));
    }
    return List.copyOf(cases);
  }

  private static List<Case> validDataForTypeCases(ScalarType type) {
    String typeName = "." + type.name();
    byte[] singularTag = tag(type.singular(), type.wireType());
    List<Case> cases = new ArrayList<>();

    // Singular data for the singular field. A proto3 default is not written.
    for (int i = 0; i < type.values().size(); i++) {
      Value value = type.values().get(i);
      byte[] input = cat(singularTag, value.input());
      boolean isDefault =
          type.protoDefault() != null && Arrays.equals(value.expected(), type.protoDefault());
      byte[] expected = isDefault ? new byte[0] : cat(singularTag, value.expected());
      cases.add(
          new Case(
              "ValidDataScalar" + typeName + "[" + i + "]", input, expected, Comparison.MESSAGE));
      cases.add(
          new Case(
              "ValidDataScalarBinary" + typeName + "[" + i + "]",
              input,
              expected,
              Comparison.WIRE_BYTES));
    }

    // Repeated data for the singular field: the last value wins. Scalar message fields merge
    // instead, which TestValidDataForRepeatedScalarMessage covers.
    if (!type.name().equals("MESSAGE")) {
      byte[][] inputs = new byte[type.values().size()][];
      for (int i = 0; i < inputs.length; i++) {
        inputs[i] = cat(singularTag, type.values().get(i).input());
      }
      Value last = type.values().get(type.values().size() - 1);
      cases.add(
          new Case(
              "RepeatedScalarSelectsLast" + typeName,
              cat(inputs),
              cat(singularTag, last.expected()),
              Comparison.MESSAGE));
    }

    if (type.packable()) {
      cases.addAll(packableRepeatedCases(type));
    } else {
      List<byte[]> inputs = new ArrayList<>();
      List<byte[]> expecteds = new ArrayList<>();
      byte[] repeatedTag = tag(type.repeated(), type.wireType());
      for (Value value : type.values()) {
        inputs.add(cat(repeatedTag, value.input()));
        expecteds.add(cat(repeatedTag, value.expected()));
      }
      cases.add(
          new Case(
              "ValidDataRepeated" + typeName,
              cat(inputs.toArray(byte[][]::new)),
              cat(expecteds.toArray(byte[][]::new)),
              Comparison.MESSAGE));
    }
    return cases;
  }

  /**
   * The repeated scenarios of a packable type. Upstream's {@code GetFieldForType} returns the first
   * declared repeated field of the type for the default and the packed variants (in proto3 that is
   * the packed-by-default {@code repeated_*} field, never {@code packed_*}), and the first {@code
   * [packed = false]} field for the unpacked variants.
   */
  private static List<Case> packableRepeatedCases(ScalarType type) {
    String prefix = "ValidDataRepeated." + type.name();
    byte[] repeatedTag = tag(type.repeated(), type.wireType());
    byte[] repeatedPackedTag = tag(type.repeated(), WIRETYPE_LENGTH_DELIMITED);
    byte[] unpackedTag = tag(type.unpacked(), type.wireType());
    byte[] unpackedPackedTag = tag(type.unpacked(), WIRETYPE_LENGTH_DELIMITED);

    List<byte[]> repeatedUnpackedInput = new ArrayList<>();
    List<byte[]> packedInput = new ArrayList<>();
    List<byte[]> packedExpected = new ArrayList<>();
    List<byte[]> unpackedFieldInput = new ArrayList<>();
    List<byte[]> unpackedFieldExpected = new ArrayList<>();
    for (Value value : type.values()) {
      repeatedUnpackedInput.add(cat(repeatedTag, value.input()));
      packedInput.add(value.input());
      packedExpected.add(value.expected());
      unpackedFieldInput.add(cat(unpackedTag, value.input()));
      unpackedFieldExpected.add(cat(unpackedTag, value.expected()));
    }
    byte[] unpackedInput = cat(repeatedUnpackedInput.toArray(byte[][]::new));
    byte[] packedWire = cat(repeatedPackedTag, delim(cat(packedInput.toArray(byte[][]::new))));
    byte[] packedExpectedWire =
        cat(repeatedPackedTag, delim(cat(packedExpected.toArray(byte[][]::new))));
    byte[] unpackedFieldUnpackedInput = cat(unpackedFieldInput.toArray(byte[][]::new));
    byte[] unpackedFieldPackedInput =
        cat(unpackedPackedTag, delim(cat(packedInput.toArray(byte[][]::new))));
    byte[] unpackedFieldExpectedWire = cat(unpackedFieldExpected.toArray(byte[][]::new));

    // proto3 writes a repeated scalar packed by default.
    return List.of(
        new Case(prefix + ".UnpackedInput", unpackedInput, packedExpectedWire, Comparison.MESSAGE),
        new Case(prefix + ".PackedInput", packedWire, packedExpectedWire, Comparison.MESSAGE),
        new Case(
            prefix + ".UnpackedInput.DefaultOutput",
            unpackedInput,
            packedExpectedWire,
            Comparison.WIRE_BYTES),
        new Case(
            prefix + ".PackedInput.DefaultOutput",
            packedWire,
            packedExpectedWire,
            Comparison.WIRE_BYTES),
        new Case(
            prefix + ".UnpackedInput.PackedOutput",
            unpackedInput,
            packedExpectedWire,
            Comparison.WIRE_BYTES),
        new Case(
            prefix + ".PackedInput.PackedOutput",
            packedWire,
            packedExpectedWire,
            Comparison.WIRE_BYTES),
        new Case(
            prefix + ".UnpackedInput.UnpackedOutput",
            unpackedFieldUnpackedInput,
            unpackedFieldExpectedWire,
            Comparison.WIRE_BYTES),
        new Case(
            prefix + ".PackedInput.UnpackedOutput",
            unpackedFieldPackedInput,
            unpackedFieldExpectedWire,
            Comparison.WIRE_BYTES));
  }

  /**
   * {@code TestValidDataForRepeatedScalarMessage}: field 18 ({@code optional_nested_message})
   * twice, each carrying {@code NestedMessage.corecursive} (field 2, a {@code TestAllTypesProto3})
   * with different scalar fields; the merge keeps every field and the last value of a clash.
   */
  private static Case repeatedScalarMessageMerge() {
    byte[] first =
        delim(
            len(
                2,
                cat(
                    tag(1, WIRETYPE_VARINT), varint(1234),
                    tag(2, WIRETYPE_VARINT), varint(1234),
                    tag(31, WIRETYPE_VARINT), varint(1234))));
    byte[] second =
        delim(
            len(
                2,
                cat(
                    tag(1, WIRETYPE_VARINT), varint(4321),
                    tag(3, WIRETYPE_VARINT), varint(4321),
                    tag(31, WIRETYPE_VARINT), varint(4321))));
    byte[] nestedTag = tag(18, WIRETYPE_LENGTH_DELIMITED);
    // Upstream's expected text: corecursive { optional_int32: 4321 optional_int64: 1234
    // optional_uint32: 4321 repeated_int32: [1234, 4321] }.
    byte[] merged =
        delim(
            len(
                2,
                cat(
                    tag(1, WIRETYPE_VARINT), varint(4321),
                    tag(2, WIRETYPE_VARINT), varint(1234),
                    tag(3, WIRETYPE_VARINT), varint(4321),
                    tag(31, WIRETYPE_VARINT), varint(1234),
                    tag(31, WIRETYPE_VARINT), varint(4321))));
    return new Case(
        "RepeatedScalarMessageMerge",
        cat(nestedTag, first, nestedTag, second),
        cat(nestedTag, merged),
        Comparison.MESSAGE);
  }

  /**
   * The case {@code TestOverwriteMessageValueMap} runs: two entries for the same key, whose message
   * values set different fields. The second entry replaces the first, so the expected payload is
   * the second entry alone.
   */
  private static Case overwriteMessageValueMapCase() {
    byte[] key = cat(tag(1, WIRETYPE_LENGTH_DELIMITED), delim(""));
    byte[] field1 = cat(tag(1, WIRETYPE_VARINT), varint(1));
    byte[] field2 = cat(tag(2, WIRETYPE_VARINT), varint(1));
    byte[] field31 = cat(tag(31, WIRETYPE_VARINT), varint(1));
    byte[] submessage1 = delim(cat(field1, field31));
    byte[] submessage2 = delim(cat(field2, field31));
    byte[] value1 =
        cat(
            tag(2, WIRETYPE_LENGTH_DELIMITED),
            delim(cat(tag(2, WIRETYPE_LENGTH_DELIMITED), submessage1)));
    byte[] value2 =
        cat(
            tag(2, WIRETYPE_LENGTH_DELIMITED),
            delim(cat(tag(2, WIRETYPE_LENGTH_DELIMITED), submessage2)));
    byte[] entryTag = tag(71, WIRETYPE_LENGTH_DELIMITED);
    byte[] entry1 = cat(entryTag, delim(cat(key, value1)));
    byte[] entry2 = cat(entryTag, delim(cat(key, value2)));
    return new Case(
        "ValidDataMap.STRING.MESSAGE.MergeValue", cat(entry1, entry2), entry2, Comparison.MESSAGE);
  }

  private static List<Case> validDataForMapTypeCases(MapField field) {
    String prefix = "ValidDataMap." + field.key().name() + "." + field.value().name();
    byte[] key1 = cat(tag(1, field.key().wireType()), field.key().defaultValue());
    byte[] value1 = cat(tag(2, field.value().wireType()), field.value().defaultValue());
    byte[] key2 = cat(tag(1, field.key().wireType()), field.key().nonDefaultValue());
    byte[] value2 = cat(tag(2, field.value().wireType()), field.value().nonDefaultValue());
    byte[] entryTag = tag(field.number(), WIRETYPE_LENGTH_DELIMITED);

    byte[] defaultEntry = cat(entryTag, delim(cat(key1, value1)));
    byte[] missingEntry = cat(entryTag, delim(new byte[0]));
    byte[] nonDefaultEntry = cat(entryTag, delim(cat(key2, value2)));
    byte[] unorderedEntry = cat(entryTag, delim(cat(value2, key2)));
    byte[] duplicateKeyFirst = cat(entryTag, delim(cat(key2, value1)));
    byte[] duplicateKeyIn = cat(entryTag, delim(cat(key1, key2, value2)));
    byte[] duplicateValueIn = cat(entryTag, delim(cat(key2, value1, value2)));

    // Each scenario's expected message is the input parsed (the last entry alone, for a
    // duplicate key).
    return List.of(
        new Case(prefix + ".Default", defaultEntry, defaultEntry, Comparison.MESSAGE),
        new Case(prefix + ".MissingDefault", missingEntry, missingEntry, Comparison.MESSAGE),
        new Case(prefix + ".NonDefault", nonDefaultEntry, nonDefaultEntry, Comparison.MESSAGE),
        new Case(prefix + ".Unordered", unorderedEntry, unorderedEntry, Comparison.MESSAGE),
        new Case(
            prefix + ".DuplicateKey",
            cat(duplicateKeyFirst, nonDefaultEntry),
            nonDefaultEntry,
            Comparison.MESSAGE),
        new Case(
            prefix + ".DuplicateKeyInMapEntry", duplicateKeyIn, duplicateKeyIn, Comparison.MESSAGE),
        new Case(
            prefix + ".DuplicateValueInMapEntry",
            duplicateValueIn,
            duplicateValueIn,
            Comparison.MESSAGE));
  }

  /**
   * The cases {@code TestValidDataForOneofType} runs for one member type. Every scenario upstream
   * runs as protobuf-to-protobuf both without ({@code ValidDataOneof}) and with ({@code
   * ValidDataOneofBinary}) the same-wire-format requirement. A oneof member has presence, so the
   * default value is written back too.
   */
  private static List<OneofCase> validDataForOneofTypeCases(OneofType type) {
    String suffix = "." + type.name();
    byte[] memberTag = tag(type.number(), type.wireType());
    byte[] defaultValue = cat(memberTag, type.defaultValue());
    byte[] nonDefaultValue = cat(memberTag, type.nonDefaultValue());
    // Upstream's exclusive lookup: the first oneof field whose type is not this one, written with
    // its default value before the non-default value of this type.
    OneofType other = oneofType(type.name().equals("UINT32") ? "MESSAGE" : "UINT32");
    byte[] otherValue = cat(tag(other.number(), other.wireType()), other.defaultValue());

    List<OneofCase> cases = new ArrayList<>();
    cases.addAll(oneofCases(suffix, ".DefaultValue", type.member(), defaultValue, defaultValue));
    cases.addAll(
        oneofCases(suffix, ".NonDefaultValue", type.member(), nonDefaultValue, nonDefaultValue));
    cases.addAll(
        oneofCases(
            suffix,
            ".MultipleValuesForSameField",
            type.member(),
            cat(defaultValue, nonDefaultValue),
            nonDefaultValue));
    cases.addAll(
        oneofCases(
            suffix,
            ".MultipleValuesForDifferentField",
            type.member(),
            cat(otherValue, nonDefaultValue),
            nonDefaultValue));
    return List.copyOf(cases);
  }

  private static List<OneofCase> oneofCases(
      String typeSuffix, String scenarioSuffix, String member, byte[] input, byte[] expected) {
    return List.of(
        new OneofCase(
            "ValidDataOneof" + typeSuffix + scenarioSuffix,
            member,
            input,
            expected,
            Comparison.MESSAGE),
        new OneofCase(
            "ValidDataOneofBinary" + typeSuffix + scenarioSuffix,
            member,
            input,
            expected,
            Comparison.WIRE_BYTES));
  }

  private static OneofType oneofType(String name) {
    return ONEOF_TYPES.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  private static OneofType varintOneofType(String name, String member, int number) {
    return new OneofType(name, member, number, WIRETYPE_VARINT, varint(0), varint(1));
  }

  private static Map<String, String> knownGaps() {
    Map<String, String> gaps = new LinkedHashMap<>();
    return Map.copyOf(gaps);
  }

  private static MapType varintMapType(String name) {
    return new MapType(name, WIRETYPE_VARINT, varint(0), varint(1));
  }
}
