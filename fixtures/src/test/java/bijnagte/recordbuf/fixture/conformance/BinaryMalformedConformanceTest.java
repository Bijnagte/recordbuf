package bijnagte.recordbuf.fixture.conformance;

import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_END_GROUP;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_FIXED32;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_FIXED64;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_LENGTH_DELIMITED;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_START_GROUP;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_VARINT;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.cat;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.group;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.len;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.raw;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.tag;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.varint;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Mirrors the rejection scenarios of the official protobuf conformance suite at {@code v36.2}
 * (KTD8): {@code TestPrematureEOFForType}, {@code TestIllegalTags}, {@code TestUnmatchedGroup},
 * {@code TestUnknownWireType} and {@code TestInvalidUtf8String}, against the vendored proto3 {@code
 * TestAllTypesProto3}. ({@code TestIllegalLengths}, named in the plan, does not exist at the pinned
 * tag.) The payloads, the field selection and the case names are ported from {@code
 * binary_json_conformance_suite.cc}.
 *
 * <p>Every scenario expects a parse failure: the recordbuf record's {@code fromBytes} must throw.
 * Each one also asserts that the protobuf-java reference's {@code parseFrom} rejects the same
 * bytes, which proves the payload was ported right (a scenario that the reference accepts is not a
 * malformed input, whatever the C++ parser does).
 *
 * <p>A scenario the record decodes without error is a recordbuf bug. It is listed in {@link
 * #KNOWN_GAPS} by its upstream case name and the id of its gap-ledger row; it runs, and is reported
 * as aborted with that id. If it starts being rejected, the test fails, so closing a gap forces
 * removing the entry. A scenario that needs a field commented out of the vendored proto cannot run
 * at all: {@link #BLOCKED} reports it as aborted, and fails once the proto has the field.
 */
class BinaryMalformedConformanceTest {

  /** A field number no message declares ({@code UNKNOWN_FIELD} upstream). */
  private static final int UNKNOWN_FIELD = 666;

  /** One mirrored scenario: its upstream case name and the malformed input. */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record Case(String name, byte[] input) {}

  /** A scenario that cannot run because the vendored proto lacks the field it needs. */
  private record BlockedCase(String name, String ledgerId, int fieldNumber) {}

  /**
   * A field type of {@code TestAllTypesProto3}, other than group.
   *
   * @param name the upper-cased upstream type name, for example {@code INT32}
   * @param wireType the wire type of one element
   * @param singular field number of the first singular field of this type
   * @param repeated field number of the first repeated field of this type
   * @param incomplete the upstream incomplete value for the wire type
   */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record FieldType(
      String name, int wireType, int singular, int repeated, byte[] incomplete) {

    boolean lengthDelimited() {
      return wireType == WIRETYPE_LENGTH_DELIMITED;
    }
  }

  /** The upstream {@code incompletes} table, by wire type. */
  private static final byte[] INCOMPLETE_VARINT = raw(0x80);

  private static final byte[] INCOMPLETE_FIXED64 = bytes("abcdefg");
  private static final byte[] INCOMPLETE_LENGTH_PREFIX = raw(0x80);
  private static final byte[] INCOMPLETE_FIXED32 = bytes("abc");

  private static final List<FieldType> FIELD_TYPES =
      List.of(
          fieldType("DOUBLE", WIRETYPE_FIXED64, 12, 42, INCOMPLETE_FIXED64),
          fieldType("FLOAT", WIRETYPE_FIXED32, 11, 41, INCOMPLETE_FIXED32),
          fieldType("INT64", WIRETYPE_VARINT, 2, 32, INCOMPLETE_VARINT),
          fieldType("UINT64", WIRETYPE_VARINT, 4, 34, INCOMPLETE_VARINT),
          fieldType("INT32", WIRETYPE_VARINT, 1, 31, INCOMPLETE_VARINT),
          fieldType("FIXED64", WIRETYPE_FIXED64, 8, 38, INCOMPLETE_FIXED64),
          fieldType("FIXED32", WIRETYPE_FIXED32, 7, 37, INCOMPLETE_FIXED32),
          fieldType("BOOL", WIRETYPE_VARINT, 13, 43, INCOMPLETE_VARINT),
          fieldType("STRING", WIRETYPE_LENGTH_DELIMITED, 14, 44, INCOMPLETE_LENGTH_PREFIX),
          fieldType("MESSAGE", WIRETYPE_LENGTH_DELIMITED, 18, 48, INCOMPLETE_LENGTH_PREFIX),
          fieldType("BYTES", WIRETYPE_LENGTH_DELIMITED, 15, 45, INCOMPLETE_LENGTH_PREFIX),
          fieldType("UINT32", WIRETYPE_VARINT, 3, 33, INCOMPLETE_VARINT),
          fieldType("ENUM", WIRETYPE_VARINT, 21, 51, INCOMPLETE_VARINT),
          fieldType("SFIXED32", WIRETYPE_FIXED32, 9, 39, INCOMPLETE_FIXED32),
          fieldType("SFIXED64", WIRETYPE_FIXED64, 10, 40, INCOMPLETE_FIXED64),
          fieldType("SINT32", WIRETYPE_VARINT, 5, 35, INCOMPLETE_VARINT),
          fieldType("SINT64", WIRETYPE_VARINT, 6, 36, INCOMPLETE_VARINT));

  /** Field numbers of the string fields of {@code TestAllTypesProto3} the UTF-8 cases use. */
  private static final int OPTIONAL_STRING = 14;

  private static final int REPEATED_STRING = 44;
  private static final int ONEOF_STRING = 113;
  private static final int MAP_STRING_STRING = 69;

  private static final byte[] INVALID_UTF8 = raw(0xA0, 0xB0, 0xC0, 0xD0);

  /** A scenario whose field is commented out of the vendored proto. */
  private static final List<BlockedCase> BLOCKED =
      List.of(
          new BlockedCase(
              "RejectInvalidUtf8.String.Oneof", "oneof-snake-case-member", ONEOF_STRING));

  /**
   * The scenarios recordbuf decodes without error today, by upstream case name, with the ledger id
   * of the gap that explains each. Keep this small and explicit: when a gap closes, its scenarios
   * are rejected, the test then fails, and the entries are removed here.
   */
  private static final Map<String, String> KNOWN_GAPS = knownGaps();

  /**
   * The scenarios whose payload the protobuf-java reference accepts, with the reason. {@code
   * CodedInputStream.readTag} reads a tag as a 32-bit varint and discards the bytes of a longer or
   * larger one, so upstream's "tag varint out of range" payloads are valid input to the Java
   * parser, whatever the C++ parser does. The reference must keep accepting them, so an entry
   * cannot go stale unnoticed.
   */
  private static final Map<String, String> REFERENCE_ACCEPTS = referenceAccepts();

  private static final List<Case> ALL_CASES = allCases();

  /** {@code TestPrematureEOFForType}: truncated input of every field type, known and unknown. */
  @TestFactory
  Stream<DynamicNode> prematureEofForType() {
    return FIELD_TYPES.stream()
        .map(type ->
            DynamicContainer.dynamicContainer(
                "PrematureEof." + type.name(),
                prematureEofCases(type).stream().map(BinaryMalformedConformanceTest::dynamicTest)));
  }

  /** {@code TestIllegalTags}: field number zero and tag varints that are out of range. */
  @TestFactory
  Stream<DynamicNode> illegalTags() {
    return illegalTagCases().stream().map(BinaryMalformedConformanceTest::dynamicTest);
  }

  /** {@code TestUnmatchedGroup}: group tags that do not pair up. */
  @TestFactory
  Stream<DynamicNode> unmatchedGroup() {
    return unmatchedGroupCases().stream().map(BinaryMalformedConformanceTest::dynamicTest);
  }

  /** {@code TestUnknownWireType}: wire types 6 and 7, for field numbers 0 to 3. */
  @TestFactory
  Stream<DynamicNode> unknownWireType() {
    return unknownWireTypeCases().stream().map(BinaryMalformedConformanceTest::dynamicTest);
  }

  /** {@code TestInvalidUtf8String}: bytes that are not UTF-8 in a string field. */
  @TestFactory
  Stream<DynamicNode> invalidUtf8String() {
    Stream<DynamicNode> mirrored =
        invalidUtf8Cases().stream().map(BinaryMalformedConformanceTest::dynamicTest);
    Stream<DynamicNode> blocked = BLOCKED.stream().map(BinaryMalformedConformanceTest::blockedTest);
    return Stream.concat(mirrored, blocked);
  }

  /** Every known-gap entry names a scenario this test mirrors, so none can go stale unnoticed. */
  @Test
  void knownGaps_nameMirroredScenarios() {
    Set<String> names = new LinkedHashSet<>();
    ALL_CASES.forEach(c -> names.add(c.name()));
    List<String> stale = KNOWN_GAPS.keySet().stream().filter(n -> !names.contains(n)).toList();

    assertEquals(List.of(), stale, "KNOWN_GAPS entries that name no mirrored scenario");
  }

  /** Every reference-accepts entry names a scenario this test mirrors. */
  @Test
  void referenceAccepts_nameMirroredScenarios() {
    Set<String> names = new LinkedHashSet<>();
    ALL_CASES.forEach(c -> names.add(c.name()));
    List<String> stale =
        REFERENCE_ACCEPTS.keySet().stream().filter(n -> !names.contains(n)).toList();

    assertEquals(List.of(), stale, "REFERENCE_ACCEPTS entries that name no mirrored scenario");
  }

  /** Every mirrored scenario has its own case name, so a {@link #KNOWN_GAPS} key is unambiguous. */
  @Test
  void caseNames_areUnique() {
    Set<String> seen = new LinkedHashSet<>();
    List<String> duplicates =
        ALL_CASES.stream().map(Case::name).filter(name -> !seen.add(name)).toList();

    assertEquals(List.of(), duplicates, "case names used by more than one scenario");
  }

  private static DynamicTest dynamicTest(Case scenario) {
    return DynamicTest.dynamicTest(scenario.name(), () -> run(scenario));
  }

  private static DynamicTest blockedTest(BlockedCase blocked) {
    return DynamicTest.dynamicTest(
        blocked.name(),
        () -> {
          if (com.google.protobuf_test_messages.proto3.TestAllTypesProto3.getDescriptor()
                  .findFieldByNumber(blocked.fieldNumber())
              != null) {
            fail(
                "TestAllTypesProto3 now has field "
                    + blocked.fieldNumber()
                    + ": mirror "
                    + blocked.name()
                    + " and remove it from BLOCKED (gap "
                    + blocked.ledgerId()
                    + ")");
          }
          throw new TestAbortedException(
              "gap " + blocked.ledgerId() + ": no field " + blocked.fieldNumber());
        });
  }

  private static void run(Case scenario) {
    if (REFERENCE_ACCEPTS.containsKey(scenario.name())) {
      assertReferenceAccepts(scenario);
    } else {
      assertReferenceRejects(scenario);
    }
    String gapId = KNOWN_GAPS.get(scenario.name());
    if (gapId == null) {
      assertRecordRejects(scenario);
      return;
    }
    try {
      assertRecordRejects(scenario);
    } catch (AssertionError e) {
      throw new TestAbortedException("gap " + gapId + ": " + e.getMessage(), e);
    }
    fail(
        scenario.name()
            + " is rejected now: gap "
            + gapId
            + " no longer explains it, remove it from KNOWN_GAPS");
  }

  /** The protobuf-java reference must reject the payload, or the port is wrong. */
  private static void assertReferenceRejects(Case scenario) {
    assertThrows(
        InvalidProtocolBufferException.class,
        () ->
            com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(scenario.input()),
        "protobuf-java accepts " + scenario.name() + ": the payload is not malformed");
  }

  /** The reference is known to accept the payload, see {@link #REFERENCE_ACCEPTS}. */
  private static void assertReferenceAccepts(Case scenario) {
    assertDoesNotThrow(
        () ->
            com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(scenario.input()),
        scenario.name()
            + " is rejected by protobuf-java now: "
            + REFERENCE_ACCEPTS.get(scenario.name()));
  }

  /** The record's decoder must throw; a decode that returns is reported as an assertion error. */
  private static void assertRecordRejects(Case scenario) {
    try {
      TestAllTypesProto3.fromBytes(scenario.input());
    } catch (IOException | RuntimeException expected) {
      return;
    }
    throw new AssertionError("recordbuf decoded " + scenario.name() + " without an error");
  }

  private static List<Case> allCases() {
    List<Case> cases = new ArrayList<>();
    for (FieldType type : FIELD_TYPES) {
      cases.addAll(prematureEofCases(type));
    }
    cases.addAll(illegalTagCases());
    cases.addAll(unmatchedGroupCases());
    cases.addAll(unknownWireTypeCases());
    cases.addAll(invalidUtf8Cases());
    return List.copyOf(cases);
  }

  private static List<Case> prematureEofCases(FieldType type) {
    String typeName = "." + type.name();
    int wireType = type.wireType();
    byte[] incomplete = type.incomplete();
    byte[] knownTag = tag(type.singular(), wireType);
    byte[] repeatedTag = tag(type.repeated(), wireType);
    byte[] unknownTag = tag(UNKNOWN_FIELD, wireType);
    List<Case> cases = new ArrayList<>();

    cases.add(new Case("PrematureEofBeforeKnownNonRepeatedValue" + typeName, knownTag));
    cases.add(new Case("PrematureEofBeforeKnownRepeatedValue" + typeName, repeatedTag));
    cases.add(new Case("PrematureEofBeforeUnknownValue" + typeName, unknownTag));
    cases.add(
        new Case("PrematureEofInsideKnownNonRepeatedValue" + typeName, cat(knownTag, incomplete)));
    cases.add(
        new Case("PrematureEofInsideKnownRepeatedValue" + typeName, cat(repeatedTag, incomplete)));
    cases.add(new Case("PrematureEofInsideUnknownValue" + typeName, cat(unknownTag, incomplete)));

    if (type.lengthDelimited()) {
      // The length says one byte follows, and none does.
      cases.add(
          new Case(
              "PrematureEofInDelimitedDataForKnownNonRepeatedValue" + typeName,
              cat(knownTag, varint(1))));
      cases.add(
          new Case(
              "PrematureEofInDelimitedDataForKnownRepeatedValue" + typeName,
              cat(repeatedTag, varint(1))));
      cases.add(
          new Case(
              "PrematureEofInDelimitedDataForUnknownValue" + typeName, cat(unknownTag, varint(1))));
      if (type.name().equals("MESSAGE")) {
        // A submessage that ends in the middle of a value. Upstream's field number is
        // WireFormatLite::TYPE_INT32, which is 5.
        byte[] incompleteSubmessage = cat(tag(5, WIRETYPE_VARINT), INCOMPLETE_VARINT);
        cases.add(
            new Case(
                "PrematureEofInSubmessageValue" + typeName,
                cat(knownTag, varint(incompleteSubmessage.length), incompleteSubmessage)));
      }
    } else {
      byte[] packedTag = tag(type.repeated(), WIRETYPE_LENGTH_DELIMITED);
      // A packed region that ends in the middle of a value.
      cases.add(
          new Case(
              "PrematureEofInPackedFieldValue" + typeName,
              cat(packedTag, varint(incomplete.length), incomplete)));
      // A packed region that ends before the length says.
      cases.add(new Case("PrematureEofInPackedField" + typeName, cat(packedTag, varint(1))));
    }
    return cases;
  }

  private static List<Case> illegalTagCases() {
    List<Case> cases = new ArrayList<>();
    // Field number 0 is illegal, whatever the wire type.
    List<byte[]> nullFields =
        List.of(
            cat(raw(1), bytes("DEADBEEF")), raw(2, 1, 1), raw(3, 4), cat(raw(5), bytes("DEAD")));
    for (int i = 0; i < nullFields.size(); i++) {
      cases.add(new Case("IllegalZeroFieldNum_Case_" + i, nullFields.get(i)));
    }

    // The single byte tag of a field 1 varint, with its continuation bit set, which makes it easy
    // to build longer tags.
    byte[] tagWithContinuationBit = tag(1, WIRETYPE_VARINT);
    tagWithContinuationBit[0] |= (byte) 0b10000000;
    byte[] value = varint(1234);

    // A field number far out of range; its lower five bytes look like a tag for field 1.
    cases.add(
        new Case(
            "BadTag_FieldNumberTooHigh",
            cat(tagWithContinuationBit, raw(0x80, 0x80, 0x80, 0x80, 0x80, 0x0F), value)));
    // A five-byte tag varint above UINT32_MAX (bit 35 is set).
    cases.add(
        new Case(
            "BadTag_FieldNumberSlightlyTooHigh",
            cat(tagWithContinuationBit, raw(0x80, 0x80, 0x80, 0x40), value)));
    // More than five bytes only because the varint is overlong: the value is below UINT32_MAX.
    cases.add(
        new Case(
            "BadTag_OverlongVarint",
            cat(
                tagWithContinuationBit,
                raw(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80),
                raw(0),
                value)));
    // An overlong varint that is more than ten bytes.
    cases.add(
        new Case(
            "BadTag_VarintMoreThanTenBytes",
            cat(
                tagWithContinuationBit,
                raw(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80),
                raw(0),
                value)));
    return cases;
  }

  private static List<Case> unmatchedGroupCases() {
    byte[] helloWorld = bytes("hello world");
    List<Case> cases = new ArrayList<>();
    cases.add(new Case("UnmatchedEndGroup", tag(201, WIRETYPE_END_GROUP)));
    cases.add(new Case("UnmatchedEndGroupUnknown", tag(1234, WIRETYPE_END_GROUP)));
    cases.add(new Case("UnmatchedEndGroupWrongType", tag(1, WIRETYPE_END_GROUP)));
    cases.add(new Case("UnmatchedEndGroupNestedLen", len(18, tag(1234, WIRETYPE_END_GROUP))));
    cases.add(new Case("UnmatchedEndGroupNested", group(201, tag(202, WIRETYPE_END_GROUP))));
    cases.add(
        new Case("UnmatchedEndGroupWithData", cat(tag(1, WIRETYPE_END_GROUP), len(2, helloWorld))));

    cases.add(new Case("UnmatchedStartGroup", tag(201, WIRETYPE_START_GROUP)));
    cases.add(new Case("UnmatchedStartGroupUnknown", tag(1234, WIRETYPE_START_GROUP)));
    cases.add(new Case("UnmatchedStartGroupWrongType", tag(1, WIRETYPE_START_GROUP)));
    cases.add(new Case("UnmatchedStartGroupNestedLen", len(18, tag(1234, WIRETYPE_START_GROUP))));
    cases.add(new Case("UnmatchedStartGroupNested", group(201, tag(202, WIRETYPE_START_GROUP))));
    cases.add(
        new Case(
            "UnmatchedStartGroupWithData", cat(tag(1, WIRETYPE_START_GROUP), len(2, helloWorld))));

    cases.add(
        new Case(
            "MismatchedGroupTags",
            cat(tag(201, WIRETYPE_START_GROUP), len(2, helloWorld), tag(202, WIRETYPE_END_GROUP))));
    cases.add(
        new Case(
            "MismatchedNestedGroupTags",
            group(
                201,
                cat(
                    tag(202, WIRETYPE_START_GROUP),
                    len(2, helloWorld),
                    tag(203, WIRETYPE_END_GROUP)))));
    return cases;
  }

  private static List<Case> unknownWireTypeCases() {
    List<Case> cases = new ArrayList<>();
    for (int type : new int[] {0x6, 0x7}) {
      for (int field = 0; field < 4; field++) {
        for (int value = 0; value < 4; value++) {
          cases.add(
              new Case(
                  "UnknownWireType" + type + "_Field" + field + "_Version" + value,
                  raw((field << 3) | type, value)));
        }
      }
    }
    return cases;
  }

  private static List<Case> invalidUtf8Cases() {
    return List.of(
        new Case("RejectInvalidUtf8.String.Singular", len(OPTIONAL_STRING, INVALID_UTF8)),
        new Case("RejectInvalidUtf8.String.Repeated", len(REPEATED_STRING, INVALID_UTF8)),
        new Case(
            "RejectInvalidUtf8.String.MapKey",
            len(MAP_STRING_STRING, cat(len(1, INVALID_UTF8), len(2, bytes("foo"))))),
        new Case(
            "RejectInvalidUtf8.String.MapValue",
            len(MAP_STRING_STRING, cat(len(1, bytes("foo")), len(2, INVALID_UTF8)))));
  }

  private static Map<String, String> referenceAccepts() {
    String reason = "protobuf-java drops the bytes of a tag varint beyond 32 bits";
    Map<String, String> accepted = new LinkedHashMap<>();
    accepted.put("BadTag_FieldNumberTooHigh", reason);
    accepted.put("BadTag_FieldNumberSlightlyTooHigh", reason);
    accepted.put("BadTag_OverlongVarint", reason);
    return Map.copyOf(accepted);
  }

  private static Map<String, String> knownGaps() {
    Map<String, String> gaps = new LinkedHashMap<>();
    // A known field is decoded by its field number alone, so fields 1 to 3 accept wire type 6 or 7
    // (field 0 is an illegal tag and is rejected), and a cut-short packed enum region is read as
    // one unpacked value.
    for (int type : new int[] {6, 7}) {
      for (int field = 1; field < 4; field++) {
        for (int value = 0; value < 4; value++) {
          gaps.put(
              "UnknownWireType" + type + "_Field" + field + "_Version" + value,
              "decode-wire-type-unchecked");
        }
      }
    }
    gaps.put("PrematureEofInPackedField.ENUM", "decode-wire-type-unchecked");
    // CodedInputStream.readTag drops the bytes of a tag varint beyond 32 bits.
    for (String name :
        List.of(
            "BadTag_FieldNumberTooHigh",
            "BadTag_FieldNumberSlightlyTooHigh",
            "BadTag_OverlongVarint")) {
      gaps.put(name, "tag-varint-truncated-accepted");
    }
    // CodedInputStream.readString replaces invalid UTF-8 instead of rejecting it.
    for (String suffix : List.of("Singular", "Repeated", "MapKey", "MapValue")) {
      gaps.put("RejectInvalidUtf8.String." + suffix, "invalid-utf8-accepted");
    }
    return Map.copyOf(gaps);
  }

  private static byte[] bytes(String ascii) {
    return ascii.getBytes(StandardCharsets.UTF_8);
  }

  private static FieldType fieldType(
      String name, int wireType, int singular, int repeated, byte[] incomplete) {
    return new FieldType(name, wireType, singular, repeated, incomplete);
  }
}
