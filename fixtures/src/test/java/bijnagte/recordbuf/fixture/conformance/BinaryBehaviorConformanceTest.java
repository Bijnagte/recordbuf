package bijnagte.recordbuf.fixture.conformance;

import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_FIXED32;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_FIXED64;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.WIRETYPE_VARINT;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.cat;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.dbl;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.flt;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.len;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.raw;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.tag;
import static bijnagte.recordbuf.fixture.conformance.WireBytes.varint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import bijnagte.recordbuf.fixture.oneof.rec.Compat;
import bijnagte.recordbuf.runtime.UnknownField;
import bijnagte.recordbuf.runtime.WireType;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.TextFormat;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Mirrors the remaining proto3 binary scenarios of the official protobuf conformance suite at
 * {@code v36.2} (KTD8): {@code TestUnknownMessage}, {@code TestUnknownOrdering}, {@code
 * TestMergeOneofMessage}, {@code TestOneofMessage} and {@code RunRecursionLimitTests}. The payloads
 * and case names are ported from {@code binary_json_conformance_suite.cc}.
 *
 * <p>Each scenario decodes its input bytes with a recordbuf record, re-encodes the record and
 * compares the output through the protobuf-java reference, like the valid-data mirror does; a
 * scenario upstream runs as a {@code Binary} case also needs the same wire bytes. Each also checks
 * the reference itself first, which proves the port is right: the reference must accept an input
 * the scenario expects to decode and reject one it expects to fail.
 *
 * <p>{@code TestOneofMessage} targets the oneof of {@code TestAllTypesProto3}. Each of its
 * scenarios is a dynamic container of two tests, as in {@link JsonConformance}: {@code portCheck}
 * runs against protobuf-java alone and proves the port is faithful; {@code recordbuf} decodes the
 * payload with the record, re-encodes it and compares through the reference, like the other
 * scenarios. Upstream builds each message with the C++ setters and sends it as protobuf; the mirror
 * writes the payload of the resulting message by hand, and the port check proves protobuf-java
 * writes the same bytes. Upstream also runs each case with JSON output; as in the other binary
 * mirrors, only the protobuf-in, protobuf-out setting is ported. {@code oneof_null_value} (field
 * 120) is not set by any scenario.
 *
 * <p>Three scenarios are not a literal port:
 *
 * <ul>
 *   <li>{@code TestMergeOneofMessage} merges the {@code oneof_nested_message} arm of {@code
 *       TestAllTypesProto3}. The mirror runs the same payload shape (two occurrences of one oneof
 *       message arm, the second adding an unknown field) against the {@code item} arm of the {@code
 *       oneof.Compat} fixture, which has the same semantics: a oneof arm of message type.
 *   <li>{@code MergeSingularMessage} has no upstream case: it runs the same merge against the
 *       singular {@code optional_nested_message} field of {@code TestAllTypesProto3}, so the gap
 *       behind the oneof case is probed on the vendored message too.
 *   <li>{@code RunRecursionLimitTests} builds its input from {@code TestAllTypesEdition2023}'s
 *       {@code map_recursive} and a proto2 message set, neither of which is proto3. The mirror
 *       nests the vendored {@code recursive_message} field instead, at depth 100 (protobuf-java's
 *       default recursion limit, accepted), depth 101 (one beyond, rejected) and upstream's 20,000
 *       (rejected). Upstream also only runs it in performance mode at this tag.
 * </ul>
 *
 * <p>A scenario recordbuf cannot satisfy yet is listed in {@link #KNOWN_GAPS} by its case name and
 * the id of its gap-ledger row. It runs, and is reported as aborted with that id; if it passes, the
 * test fails, so closing a gap forces removing the entry. A scenario where protobuf-java itself
 * departs from upstream's expectation is listed in {@link #REFERENCE_DEVIATES}.
 */
class BinaryBehaviorConformanceTest {

  /** A field number no message declares ({@code UNKNOWN_FIELD} upstream). */
  private static final int UNKNOWN_FIELD = 666;

  /**
   * A field number {@code TestAllTypesProto3} does not declare, as in {@code TestUnknownMessage}.
   */
  private static final int UNDECLARED_FIELD_501 = 501;

  /** Field numbers of {@code TestAllTypesProto3} the scenarios use. */
  private static final int OPTIONAL_INT32 = 1;

  private static final int OPTIONAL_NESTED_MESSAGE = 18;
  private static final int RECURSIVE_MESSAGE = 27;

  /**
   * Field number of the {@code item} oneof arm of {@code oneof.Compat}, and of its {@code text}.
   */
  private static final int COMPAT_ITEM = 3;

  private static final int SUB_TEXT = 1;

  /** An unknown field, as the merge scenarios carry one ({@code field89_data} upstream). */
  private static final byte[] FIELD_89 = cat(tag(89, WIRETYPE_VARINT), varint(1));

  /** protobuf-java's default recursion limit: this many nested messages parse, one more fails. */
  private static final int PROTOBUF_JAVA_RECURSION_LIMIT = 100;

  /** The nesting upstream's recursion scenarios build, far beyond any parser's limit. */
  private static final int UPSTREAM_RECURSION_DEPTH = 20_000;

  /** How a scenario's output is compared with the expected payload. */
  private enum Comparison {
    /** The parsed messages are equal ({@code RunValidProtobufTest}). */
    MESSAGE,
    /** The parsed messages are equal and the wire bytes are equal ({@code Binary} variants). */
    WIRE_BYTES
  }

  /** The body of a scenario: it throws if the scenario fails. */
  @FunctionalInterface
  private interface Verification {
    void verify() throws Exception;
  }

  /** Re-encodes the input bytes through one recordbuf record. */
  @FunctionalInterface
  private interface RecordRoundTrip {
    byte[] roundTrip(byte[] input) throws IOException;
  }

  /** One mirrored scenario: its upstream case name and what it checks. */
  private record Scenario(String name, Verification verification) {}

  /**
   * One {@code TestOneofMessage} scenario: its upstream case name, the payload of the message
   * upstream has built by then, and the text format of the message it expects back.
   */
  @SuppressWarnings("ArrayRecordComponent") // payload bytes, never compared or hashed
  private record OneofMessageScenario(String name, byte[] payload, String textFormat) {}

  /**
   * The scenarios recordbuf does not satisfy today, by case name, with the ledger id of the gap
   * that explains each. Keep this small and explicit: when a gap closes, its scenarios start
   * passing, the test then fails, and the entries are removed here.
   */
  private static final Map<String, String> KNOWN_GAPS = knownGaps();

  /**
   * The scenarios where the protobuf-java reference departs from upstream's expectation, with the
   * reason. Its {@code UnknownFieldSet} keeps the values of one field number in one list per wire
   * type, and writes the varints before the length-delimited values, so unknown fields of one
   * number come back in a different order. The reference must keep deviating, so an entry cannot go
   * stale unnoticed.
   */
  private static final Map<String, String> REFERENCE_DEVIATES = referenceDeviates();

  private static final String UNKNOWN_ORDERING = "UnknownOrdering";

  private static final List<OneofMessageScenario> ONEOF_MESSAGE_SCENARIOS = oneofMessageScenarios();

  private static final List<Scenario> ALL_SCENARIOS = allScenarios();

  /** {@code TestUnknownMessage}: an unknown varint field survives a round trip byte for byte. */
  @TestFactory
  Stream<DynamicNode> unknownMessage() {
    return Stream.of(unknownVarint()).map(BinaryBehaviorConformanceTest::dynamicTest);
  }

  /** {@code TestUnknownOrdering}: unknown fields of one number keep their order. */
  @TestFactory
  Stream<DynamicNode> unknownOrdering() {
    return Stream.of(unknownOrdering(UNKNOWN_ORDERING))
        .map(BinaryBehaviorConformanceTest::dynamicTest);
  }

  /** {@code TestMergeOneofMessage}: a second occurrence of a oneof message arm merges. */
  @TestFactory
  Stream<DynamicNode> mergeOneofMessage() {
    return mergeScenarios().stream().map(BinaryBehaviorConformanceTest::dynamicTest);
  }

  /**
   * {@code TestOneofMessage}: each oneof member set to its zero value, one after the other, comes
   * back as that member, set.
   */
  @TestFactory
  Stream<DynamicNode> oneofMessage() {
    return ONEOF_MESSAGE_SCENARIOS.stream()
        .map(scenario ->
            DynamicContainer.dynamicContainer(
                scenario.name(),
                Stream.of(
                    DynamicTest.dynamicTest("portCheck", () -> oneofPortCheck(scenario)),
                    DynamicTest.dynamicTest(
                        "recordbuf", () -> run(oneofRecordbufHalf(scenario))))));
  }

  /** {@code RunRecursionLimitTests}: nesting at the limit decodes, beyond it is rejected. */
  @TestFactory
  Stream<DynamicNode> recursionLimit() {
    return recursionScenarios().stream().map(BinaryBehaviorConformanceTest::dynamicTest);
  }

  /** Every known-gap entry names a scenario this test mirrors, so none can go stale unnoticed. */
  @Test
  void knownGaps_nameMirroredScenarios() {
    List<String> stale =
        KNOWN_GAPS.keySet().stream().filter(n -> !scenarioNames().contains(n)).toList();

    assertEquals(List.of(), stale, "KNOWN_GAPS entries that name no mirrored scenario");
  }

  /** Every reference-deviates entry names a scenario this test mirrors. */
  @Test
  void referenceDeviates_nameMirroredScenarios() {
    List<String> stale =
        REFERENCE_DEVIATES.keySet().stream().filter(n -> !scenarioNames().contains(n)).toList();

    assertEquals(List.of(), stale, "REFERENCE_DEVIATES entries that name no mirrored scenario");
  }

  /** Every mirrored scenario has its own name, so a {@link #KNOWN_GAPS} key is unambiguous. */
  @Test
  void scenarioNames_areUnique() {
    Set<String> seen = new LinkedHashSet<>();
    List<String> duplicates =
        ALL_SCENARIOS.stream().map(Scenario::name).filter(name -> !seen.add(name)).toList();

    assertEquals(List.of(), duplicates, "scenario names used by more than one scenario");
  }

  private static Set<String> scenarioNames() {
    Set<String> names = new LinkedHashSet<>();
    ALL_SCENARIOS.forEach(s -> names.add(s.name()));
    return names;
  }

  /**
   * The protobuf-java half of a {@code TestOneofMessage} scenario: the payload must read as the
   * message upstream expects (its text format) with its member set, and protobuf-java must write
   * that message back as exactly the payload.
   */
  private static void oneofPortCheck(OneofMessageScenario scenario) throws IOException {
    com.google.protobuf_test_messages.proto3.TestAllTypesProto3.Builder builder =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.newBuilder();
    TextFormat.merge(scenario.textFormat(), builder);
    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 expected = builder.build();

    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 actual =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(scenario.payload());

    assertEquals(expected, actual, "protobuf-java does not read the payload as the expected text");
    OneofDescriptor oneof =
        actual.getDescriptorForType().getOneofs().stream()
            .filter(o -> o.getName().equals("oneof_field"))
            .findFirst()
            .orElseThrow();
    assertTrue(actual.hasOneof(oneof), "protobuf-java leaves no oneof member set");
    assertArrayEquals(
        scenario.payload(), expected.toByteArray(), "protobuf-java writes another payload");
  }

  /** The record decodes the payload and writes the same bytes back, as protobuf-java reads them. */
  private static Scenario oneofRecordbufHalf(OneofMessageScenario scenario) {
    return conforms(
        scenario.name(),
        Comparison.WIRE_BYTES,
        scenario.payload(),
        scenario.payload(),
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parser(),
        bytes -> TestAllTypesProto3.fromBytes(bytes).toByteArray());
  }

  private static DynamicTest dynamicTest(Scenario scenario) {
    return DynamicTest.dynamicTest(scenario.name(), () -> run(scenario));
  }

  private static void run(Scenario scenario) throws Exception {
    String gapId = KNOWN_GAPS.get(scenario.name());
    if (gapId == null) {
      scenario.verification().verify();
      return;
    }
    try {
      scenario.verification().verify();
    } catch (AssertionError | Exception e) {
      throw new TestAbortedException("gap " + gapId + ": " + e.getMessage(), e);
    }
    fail(
        scenario.name()
            + " passes now: gap "
            + gapId
            + " no longer explains it, remove it from KNOWN_GAPS");
  }

  private static List<Scenario> allScenarios() {
    List<Scenario> scenarios = new ArrayList<>();
    scenarios.add(unknownVarint());
    scenarios.add(unknownOrdering(UNKNOWN_ORDERING));
    scenarios.addAll(mergeScenarios());
    ONEOF_MESSAGE_SCENARIOS.forEach(s -> scenarios.add(oneofRecordbufHalf(s)));
    scenarios.addAll(recursionScenarios());
    return List.copyOf(scenarios);
  }

  /**
   * {@code TestUnknownMessage}: upstream parses {@code A8 1F 01} (varint field 501) and requires
   * its serialization back, in the same wire format.
   */
  private static Scenario unknownVarint() {
    return new Scenario(
        "UnknownVarint",
        () -> {
          byte[] input = raw(0xA8, 0x1F, 0x01);
          assertArrayEquals(
              input,
              com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(input)
                  .toByteArray(),
              "protobuf-java does not reserialize the payload: the port is wrong");

          TestAllTypesProto3 record = TestAllTypesProto3.fromBytes(input);

          List<UnknownField> unknown = record.unknownFields().fields();
          assertEquals(1, unknown.size(), "unknown fields of the decoded record");
          assertEquals(UNDECLARED_FIELD_501, unknown.get(0).fieldNumber());
          assertEquals(WireType.I32, unknown.get(0).wireType(), "a varint");
          assertEquals(1L, unknown.get(0).asLong());
          assertArrayEquals(input, record.toByteArray(), "output is not the same wire format");
        });
  }

  /**
   * {@code TestUnknownOrdering}: upstream builds a message whose unknown fields are, in order, a
   * length-delimited {@code "abc"}, a varint 123, a length-delimited {@code "def"} and a varint
   * 456, all of field 666. The response must hold exactly those four fields in that order, with
   * those types and values.
   */
  private static Scenario unknownOrdering(String name) {
    return new Scenario(
        name,
        () -> {
          byte[] varint123 = cat(tag(UNKNOWN_FIELD, WIRETYPE_VARINT), varint(123));
          byte[] varint456 = cat(tag(UNKNOWN_FIELD, WIRETYPE_VARINT), varint(456));
          byte[] input =
              cat(
                  len(UNKNOWN_FIELD, ascii("abc")),
                  varint123,
                  len(UNKNOWN_FIELD, ascii("def")),
                  varint456);
          assertReferenceDeviates(name, input);

          TestAllTypesProto3 record = TestAllTypesProto3.fromBytes(input);

          List<UnknownField> unknown = record.unknownFields().fields();
          assertEquals(4, unknown.size(), "unknown fields of the decoded record");
          for (UnknownField field : unknown) {
            assertEquals(UNKNOWN_FIELD, field.fieldNumber());
          }
          assertEquals(WireType.LENGTH_DELIMITED, unknown.get(0).wireType());
          assertEquals(WireType.I32, unknown.get(1).wireType(), "a varint");
          assertEquals(WireType.LENGTH_DELIMITED, unknown.get(2).wireType());
          assertEquals(WireType.I32, unknown.get(3).wireType(), "a varint");
          assertEquals("abc", unknown.get(0).asString());
          assertEquals(123L, unknown.get(1).asLong());
          assertEquals("def", unknown.get(2).asString());
          assertEquals(456L, unknown.get(3).asLong());
          assertArrayEquals(input, record.toByteArray(), "output is not in the input order");
        });
  }

  /** The reference is known to reorder the unknown fields, see {@link #REFERENCE_DEVIATES}. */
  private static void assertReferenceDeviates(String name, byte[] input)
      throws InvalidProtocolBufferException {
    assertTrue(REFERENCE_DEVIATES.containsKey(name), name + " has no deviation entry");
    byte[] output =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(input).toByteArray();
    assertFalse(
        Arrays.equals(input, output),
        "protobuf-java keeps the unknown-field order now: " + REFERENCE_DEVIATES.get(name));
  }

  /**
   * {@code TestMergeOneofMessage}, on {@code oneof.Compat.item} (a message arm), and the same merge
   * on the singular {@code optional_nested_message}. Upstream's payload carries the same field
   * twice; the second occurrence adds a field and an unknown field to the first, and the parser
   * must merge them.
   */
  private static List<Scenario> mergeScenarios() {
    // First occurrence: text and an unknown field. Second: an unknown field only. Merged: the text
    // survives and both unknown fields are kept.
    byte[] text = len(SUB_TEXT, ascii("abc"));
    byte[] itemInput = cat(len(COMPAT_ITEM, cat(text, FIELD_89)), len(COMPAT_ITEM, FIELD_89));
    byte[] itemMerged = len(COMPAT_ITEM, cat(text, FIELD_89, FIELD_89));

    byte[] a = cat(tag(1, WIRETYPE_VARINT), varint(1));
    byte[] nestedInput =
        cat(len(OPTIONAL_NESTED_MESSAGE, cat(a, FIELD_89)), len(OPTIONAL_NESTED_MESSAGE, FIELD_89));
    byte[] nestedMerged = len(OPTIONAL_NESTED_MESSAGE, cat(a, FIELD_89, FIELD_89));

    Parser<? extends Message> compatReference = bijnagte.recordbuf.fixture.oneof.Compat.parser();
    Parser<? extends Message> allTypesReference =
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parser();
    RecordRoundTrip compatRecord = bytes -> Compat.fromBytes(bytes).toByteArray();
    RecordRoundTrip allTypesRecord = bytes -> TestAllTypesProto3.fromBytes(bytes).toByteArray();
    return List.of(
        conforms(
            "ValidDataOneof.MESSAGE.Merge",
            Comparison.MESSAGE,
            itemInput,
            itemMerged,
            compatReference,
            compatRecord),
        conforms(
            "ValidDataOneofBinary.MESSAGE.Merge",
            Comparison.WIRE_BYTES,
            itemInput,
            itemMerged,
            compatReference,
            compatRecord),
        conforms(
            "MergeSingularMessage",
            Comparison.WIRE_BYTES,
            nestedInput,
            nestedMerged,
            allTypesReference,
            allTypesRecord));
  }

  /**
   * Decodes the input with the record, re-encodes it, and compares the output with the expected
   * payload through protobuf-java. The reference must parse the input to the expected message
   * first, which proves the expected payload is ported right.
   */
  private static Scenario conforms(
      String name,
      Comparison comparison,
      byte[] input,
      byte[] expected,
      Parser<? extends Message> reference,
      RecordRoundTrip record) {
    return new Scenario(
        name,
        () -> {
          Message expectedMessage = reference.parseFrom(expected);
          assertEquals(
              expectedMessage,
              reference.parseFrom(input),
              "protobuf-java does not merge the input into the expected message: the port is wrong");

          byte[] output = record.roundTrip(input);

          assertEquals(
              expectedMessage,
              reference.parseFrom(output),
              "output is not equivalent to the reference message");
          if (comparison == Comparison.WIRE_BYTES) {
            assertArrayEquals(expected, output, "output is not the expected wire format");
          }
        });
  }

  /**
   * {@code TestOneofMessage}: upstream sets one member after another on one message, so each
   * scenario's message holds only the member set last, and the payload is that member alone.
   * Members have presence: a zero value is written, and an empty nested message too.
   */
  private static List<OneofMessageScenario> oneofMessageScenarios() {
    byte[] nestedA1 = len(112, cat(tag(1, WIRETYPE_VARINT), varint(1)));
    return List.of(
        new OneofMessageScenario(
            "OneofZeroUint32", cat(tag(111, WIRETYPE_VARINT), varint(0)), "oneof_uint32: 0"),
        new OneofMessageScenario(
            "OneofZeroMessage", len(112, new byte[0]), "oneof_nested_message: {}"),
        new OneofMessageScenario(
            "OneofZeroMessageSetTwice", nestedA1, "oneof_nested_message: {a: 1}"),
        new OneofMessageScenario("OneofZeroString", len(113, new byte[0]), "oneof_string: \"\""),
        new OneofMessageScenario("OneofZeroBytes", len(114, new byte[0]), "oneof_bytes: \"\""),
        new OneofMessageScenario(
            "OneofZeroBool", cat(tag(115, WIRETYPE_VARINT), varint(0)), "oneof_bool: false"),
        new OneofMessageScenario(
            "OneofZeroUint64", cat(tag(116, WIRETYPE_VARINT), varint(0)), "oneof_uint64: 0"),
        new OneofMessageScenario(
            "OneofZeroFloat", cat(tag(117, WIRETYPE_FIXED32), flt(0)), "oneof_float: 0"),
        new OneofMessageScenario(
            "OneofZeroDouble", cat(tag(118, WIRETYPE_FIXED64), dbl(0)), "oneof_double: 0"),
        new OneofMessageScenario(
            "OneofZeroEnum", cat(tag(119, WIRETYPE_VARINT), varint(0)), "oneof_enum: FOO"));
  }

  /**
   * {@code RunRecursionLimitTests}: nested {@code recursive_message} fields, every level also
   * holding {@code optional_int32 = 123}. Upstream nests 20,000 levels and expects a parse failure.
   */
  private static List<Scenario> recursionScenarios() {
    return List.of(
        new Scenario(
            "EnforceDepthLimit.RecursiveMessage.AtLimit",
            () -> assertRecursionAccepted(PROTOBUF_JAVA_RECURSION_LIMIT)),
        new Scenario(
            "EnforceDepthLimit.RecursiveMessage.OneBeyondLimit",
            () -> assertRecursionRejected(PROTOBUF_JAVA_RECURSION_LIMIT + 1)),
        new Scenario(
            "EnforceDepthLimit.RecursiveMessage",
            () -> assertRecursionRejected(UPSTREAM_RECURSION_DEPTH)));
  }

  /**
   * {@code depth} nested {@code recursive_message} fields below the top-level message, each level
   * written in field-number order so the record's own encoding reproduces it byte for byte.
   */
  private static byte[] recursiveMessages(int depth) {
    byte[] int32 = cat(tag(OPTIONAL_INT32, WIRETYPE_VARINT), varint(123));
    byte[] message = int32;
    for (int level = 0; level < depth; level++) {
      message = cat(int32, len(RECURSIVE_MESSAGE, message));
    }
    return message;
  }

  /** The reference parses the nesting, and so does the record, which writes it back unchanged. */
  private static void assertRecursionAccepted(int depth) throws IOException {
    byte[] input = recursiveMessages(depth);
    com.google.protobuf_test_messages.proto3.TestAllTypesProto3 expected =
        assertDoesNotThrow(
            () -> com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(input),
            "protobuf-java rejects " + depth + " levels: the port is wrong");

    byte[] output = TestAllTypesProto3.fromBytes(input).toByteArray();

    assertEquals(
        expected,
        com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(output),
        "output is not equivalent to the reference message");
    assertArrayEquals(input, output, "output is not the input wire format");
  }

  /** The reference rejects the nesting, so the record's decoder must throw a parse error too. */
  private static void assertRecursionRejected(int depth) {
    byte[] input = recursiveMessages(depth);
    assertThrows(
        InvalidProtocolBufferException.class,
        () -> com.google.protobuf_test_messages.proto3.TestAllTypesProto3.parseFrom(input),
        "protobuf-java accepts " + depth + " levels: the port is wrong");

    try {
      TestAllTypesProto3.fromBytes(input);
    } catch (IOException | RuntimeException expected) {
      return;
    } catch (StackOverflowError overflow) {
      throw new AssertionError(
          "recordbuf overflowed the stack on " + depth + " levels instead of rejecting them",
          overflow);
    }
    throw new AssertionError("recordbuf decoded " + depth + " levels without an error");
  }

  private static Map<String, String> knownGaps() {
    Map<String, String> gaps = new LinkedHashMap<>();
    // The decoder replaces a message field that occurs again on the wire instead of merging.
    for (String name :
        List.of(
            "ValidDataOneof.MESSAGE.Merge",
            "ValidDataOneofBinary.MESSAGE.Merge",
            "MergeSingularMessage")) {
      gaps.put(name, "message-field-not-merged");
    }
    return Map.copyOf(gaps);
  }

  private static Map<String, String> referenceDeviates() {
    Map<String, String> deviates = new LinkedHashMap<>();
    deviates.put(
        UNKNOWN_ORDERING,
        "protobuf-java's UnknownFieldSet writes the varints of a field number before its"
            + " length-delimited values");
    return Map.copyOf(deviates);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
