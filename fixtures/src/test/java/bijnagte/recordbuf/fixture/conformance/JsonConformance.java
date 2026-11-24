package bijnagte.recordbuf.fixture.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import bijnagte.recordbuf.fixture.golden.format.Proto3JsonFormat;
import bijnagte.recordbuf.fixture.golden.format.ProtobufBinaryFormat;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the proto3 JSON conformance mirrors ({@link JsonCoreConformanceTest}, {@link
 * JsonWktConformanceTest}) share: the scenario model ({@link Case}), the two halves each scenario
 * runs as a dynamic container, and the reference descriptor with the vendored proto's commented-out
 * constructs added back.
 *
 * <p>Every scenario runs as a dynamic container of two tests:
 *
 * <ul>
 *   <li>{@code portCheck} (runs now) is the protobuf-java half. It parses the input with {@link
 *       JsonFormat#parser()} and checks it equals the expected message, as the runner's JSON to
 *       protobuf setting does; for a scenario upstream also runs as JSON to JSON, it prints the
 *       parsed message and checks that the printed JSON parses back to the expected message. A
 *       validator scenario checks its predicate on the printed JSON. It proves the port is faithful
 *       and that {@code JsonFormat} behaves as upstream expects.
 *   <li>{@code recordbuf} is the recordbuf half, through the {@link Proto3JsonFormat} adapter (R15,
 *       KTD2): {@code JsonFormat} parse, {@code Interop.from}, the record, {@code Interop.to},
 *       {@code JsonFormat} print, then the same expectation as the port check. The half runs for
 *       real; a failure is a recordbuf bug, unless the scenario is listed in {@code KNOWN_GAPS}.
 * </ul>
 *
 * <p>The vendored proto has constructs commented out (see {@link Blocker}). The generated reference
 * lacks those fields too, so a scenario that names one runs its port check against {@link
 * #UPSTREAM}, the reference descriptor with the commented-out constructs added back, as a {@link
 * DynamicMessage}; its recordbuf half aborts with the construct's ledger id. A blocked scenario
 * fails once the reference has the construct, so uncommenting it forces the case out of its blocker
 * and the half to run.
 *
 * <p>Both halves read and print JSON, and the port check also reads upstream's text-format
 * expectations, with a type registry holding the reference's file and everything it imports, so an
 * {@code Any} packing {@code TestAllTypesProto3} or a well-known type resolves.
 */
final class JsonConformance {

  private JsonConformance() {}

  private static final String REFERENCE_MESSAGE_NAME = "TestAllTypesProto3";
  private static final String NULL_VALUE_TYPE = ".google.protobuf.NullValue";

  /**
   * The scenarios whose recordbuf half fails on a construct the record path gets wrong, by the id
   * of its gap-ledger row. The reference has the construct, so no {@link Blocker} applies. Such a
   * half runs and aborts with its id; a half that passes fails the test, so closing the gap forces
   * removing its entry.
   */
  static final Map<String, String> KNOWN_GAPS =
      Map.of(
          "EnumFieldUnknownValue", "enum-unknown-number-default",
          "FieldMask", "field-mask-path-order",
          "EmptyFieldMask", "repeated-wkt-default-element",
          "TimestampEpochValue", "repeated-wkt-default-element",
          "TimestampZeroNormalized", "repeated-wkt-default-element",
          "TimestampHasZeroFractionalDigit", "repeated-wkt-default-element");

  static final Descriptor REFERENCE =
      com.google.protobuf_test_messages.proto3.TestAllTypesProto3.getDescriptor();

  /**
   * The reference descriptor with every commented-out construct a scenario names added back, so a
   * scenario that needs one can still check the port against upstream's full message shape.
   */
  private static final Descriptor UPSTREAM = upstreamDescriptor();

  /**
   * Resolves the type of an {@code Any}: the reference's file and everything it imports (the
   * wrappers, {@code Duration}, {@code Timestamp}, {@code FieldMask}, {@code Struct}, {@code Any}
   * and {@code Empty}), which includes {@code TestAllTypesProto3} itself.
   */
  private static final JsonFormat.TypeRegistry JSON_TYPES =
      JsonFormat.TypeRegistry.newBuilder().add(REFERENCE).build();

  /** The same types for {@link TextFormat}, which expands an {@code Any} in upstream's text. */
  private static final TypeRegistry TEXT_TYPES = TypeRegistry.newBuilder().add(REFERENCE).build();

  private static final TextFormat.Parser TEXT_PARSER =
      TextFormat.Parser.newBuilder().setTypeRegistry(TEXT_TYPES).build();

  private static final Proto3JsonFormat JSON_FORMAT = new Proto3JsonFormat(JSON_TYPES);
  private static final ProtobufBinaryFormat BINARY_FORMAT = new ProtobufBinaryFormat();
  private static final JsonMapper MAPPER = new JsonMapper();

  /** A construct of the upstream message that the vendored proto comments out. */
  enum Blocker {
    NULL_VALUE("null-value-field"),
    ONEOF_NULL_VALUE("null-value-field");

    private final String ledgerId;

    Blocker(String ledgerId) {
      this.ledgerId = ledgerId;
    }

    String ledgerId() {
      return ledgerId;
    }

    /** Whether the generated reference has the construct, so the scenario no longer needs this. */
    boolean presentInReference() {
      return switch (this) {
        case NULL_VALUE -> hasField("optional_null_value");
        case ONEOF_NULL_VALUE -> hasField("oneof_null_value");
      };
    }

    private static boolean hasField(String protoName) {
      return REFERENCE.findFieldByName(protoName) != null;
    }
  }

  /** What a scenario feeds the path. */
  sealed interface Input permits JsonInput, ProtobufInput {}

  /** A JSON text; {@code ignoreUnknown} is upstream's {@code JSON_IGNORE_UNKNOWN_PARSING_TEST}. */
  record JsonInput(String json, boolean ignoreUnknown) implements Input {}

  /** A protobuf-java message that the path prints as JSON (upstream's protobuf input). */
  record ProtobufInput(Message message) implements Input {}

  /** What a scenario expects of the result. */
  sealed interface Check permits Equivalent, Validated {}

  /** The result equals the message upstream writes as text format. */
  record Equivalent(String textFormat) implements Check {}

  /** The printed JSON satisfies a predicate (upstream's {@code RunValidJsonTestWithValidator}). */
  record Validated(Predicate<JsonNode> validator) implements Check {}

  /** One mirrored scenario; {@code blocker} is null when the generated reference can run it. */
  record Case(String name, Input input, Check check, Blocker blocker) {}

  static Stream<DynamicNode> containers(List<Case> cases) {
    return cases.stream()
        .map(scenario ->
            DynamicContainer.dynamicContainer(
                scenario.name(),
                Stream.of(
                    DynamicTest.dynamicTest("portCheck", () -> portCheck(scenario)),
                    DynamicTest.dynamicTest("recordbuf", () -> recordbufHalf(scenario)))));
  }

  // ---- the port check: protobuf-java only ----

  private static void portCheck(Case scenario) throws Exception {
    Blocker blocker = scenario.blocker();
    if (blocker != null && blocker.presentInReference()) {
      fail(
          scenario.name()
              + " no longer needs the commented-out construct of gap "
              + blocker.ledgerId()
              + ": the reference has it now, remove the blocker from the case");
    }
    Message actual =
        switch (scenario.input()) {
          case JsonInput json -> parse(newBuilder(blocker), json.json(), json.ignoreUnknown());
          case ProtobufInput protobuf -> protobuf.message();
        };
    boolean alsoJsonToJson = !(scenario.input() instanceof JsonInput json && json.ignoreUnknown());
    String printed = printer().print(actual);
    switch (scenario.check()) {
      case Equivalent equivalent -> {
        Message expected = fromText(newBuilder(blocker), equivalent.textFormat());
        assertSameMessage(expected, actual, "parsed message is not the expected message");
        if (alsoJsonToJson) {
          assertSameMessage(
              expected,
              parse(newBuilder(blocker), printed, false),
              "printed JSON does not parse back to the expected message: " + printed);
        }
      }
      case Validated validated ->
          assertTrue(
              validated.validator().test(MAPPER.readTree(printed)),
              "printed JSON fails the validator: " + printed);
    }
  }

  /**
   * Asserts two messages are equal. A {@link DynamicMessage} keeps which alias of an enum number it
   * holds, where a generated message keeps only the number, so a dynamic message is compared after
   * a round trip through its wire form, which resolves each number to one value.
   */
  private static void assertSameMessage(Message expected, Message actual, String message)
      throws IOException {
    assertEquals(canonical(expected), canonical(actual), message);
  }

  private static Message canonical(Message message) throws IOException {
    if (message instanceof DynamicMessage) {
      return DynamicMessage.parseFrom(message.getDescriptorForType(), message.toByteString());
    }
    return message;
  }

  private static Message.Builder newBuilder(Blocker blocker) {
    if (blocker == null) {
      return com.google.protobuf_test_messages.proto3.TestAllTypesProto3.newBuilder();
    }
    return DynamicMessage.newBuilder(UPSTREAM);
  }

  private static Message parse(Message.Builder builder, String json, boolean ignoreUnknown)
      throws IOException {
    JsonFormat.Parser parser = JsonFormat.parser().usingTypeRegistry(JSON_TYPES);
    if (ignoreUnknown) {
      parser = parser.ignoringUnknownFields();
    }
    parser.merge(json, builder);
    return builder.build();
  }

  private static JsonFormat.Printer printer() {
    return JsonFormat.printer().usingTypeRegistry(JSON_TYPES);
  }

  private static Message fromText(Message.Builder builder, String textFormat) throws IOException {
    TEXT_PARSER.merge(textFormat, builder);
    return builder.build();
  }

  // ---- the recordbuf half: JsonFormat -> Interop -> record -> Interop -> JsonFormat ----

  private static void recordbufHalf(Case scenario) throws Exception {
    Blocker blocker = scenario.blocker();
    if (blocker != null) {
      throw new TestAbortedException(
          "gap "
              + blocker.ledgerId()
              + ": the vendored proto has the construct this scenario names commented out");
    }
    String knownGap = KNOWN_GAPS.get(scenario.name());
    if (knownGap == null) {
      runRecordbufHalf(scenario);
      return;
    }
    try {
      runRecordbufHalf(scenario);
    } catch (AssertionError | RuntimeException failure) {
      throw new TestAbortedException("gap " + knownGap + ": " + failure.getMessage(), failure);
    }
    fail(scenario.name() + " passes now: remove its KNOWN_GAPS entry for gap " + knownGap);
  }

  private static void runRecordbufHalf(Case scenario) throws Exception {
    Object record =
        switch (scenario.input()) {
          case JsonInput json ->
              JSON_FORMAT.deserialize(interopInput(json), TestAllTypesProto3.class);
          case ProtobufInput protobuf ->
              BINARY_FORMAT.deserialize(protobuf.message().toByteArray(), TestAllTypesProto3.class);
        };
    String output = JSON_FORMAT.serialize(record);
    switch (scenario.check()) {
      case Equivalent equivalent -> {
        Message expected = fromText(newBuilder(null), equivalent.textFormat());
        assertSameMessage(
            expected,
            parse(newBuilder(null), output, false),
            "the record's JSON is not the expected message: " + output);
      }
      case Validated validated ->
          assertTrue(
              validated.validator().test(MAPPER.readTree(output)),
              "the record's JSON fails the validator: " + output);
    }
  }

  /**
   * The text the adapter's strict parser takes. The adapter parses with {@code
   * JsonFormat.parser()}, so an ignore-unknown scenario first drops its unknown members through the
   * reference (a Google-owned step) and hands the adapter the printed result.
   */
  private static String interopInput(JsonInput input) throws IOException {
    if (!input.ignoreUnknown()) {
      return input.json();
    }
    return printer().print(parse(newBuilder(null), input.json(), true));
  }

  // ---- the upstream descriptor ----

  private static Descriptor upstreamDescriptor() {
    FileDescriptor file = REFERENCE.getFile();
    FileDescriptorProto.Builder fileProto = file.toProto().toBuilder();
    DescriptorProto.Builder message = fileProto.getMessageTypeBuilder(REFERENCE.getIndex());

    addOneofMember(
        message,
        "oneof_field",
        scalarField("oneof_null_value", 120, FieldDescriptorProto.Type.TYPE_ENUM)
            .setTypeName(NULL_VALUE_TYPE));
    message.addField(
        scalarField("optional_null_value", 307, FieldDescriptorProto.Type.TYPE_ENUM)
            .setTypeName(NULL_VALUE_TYPE));

    try {
      FileDescriptor extended =
          FileDescriptor.buildFrom(
              fileProto.build(), file.getDependencies().toArray(new FileDescriptor[0]));
      return extended.findMessageTypeByName(REFERENCE_MESSAGE_NAME);
    } catch (DescriptorValidationException e) {
      throw new IllegalStateException("cannot add the commented-out constructs back", e);
    }
  }

  private static FieldDescriptorProto.Builder scalarField(
      String name, int number, FieldDescriptorProto.Type type) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
        .setType(type);
  }

  /**
   * Adds {@code member} to the existing oneof {@code oneofName}, right after that oneof's last
   * field: a descriptor requires the fields of one oneof to be consecutive.
   */
  private static void addOneofMember(
      DescriptorProto.Builder message, String oneofName, FieldDescriptorProto.Builder member) {
    int oneofIndex = -1;
    for (int i = 0; i < message.getOneofDeclCount(); i++) {
      if (message.getOneofDecl(i).getName().equals(oneofName)) {
        oneofIndex = i;
      }
    }
    if (oneofIndex < 0) {
      throw new IllegalStateException("the reference has no oneof " + oneofName);
    }
    int insertAt = 0;
    for (int i = 0; i < message.getFieldCount(); i++) {
      FieldDescriptorProto field = message.getField(i);
      if (field.hasOneofIndex() && field.getOneofIndex() == oneofIndex) {
        insertAt = i + 1;
      }
    }
    message.addField(insertAt, member.setOneofIndex(oneofIndex));
  }

  static Case valid(String name, String json, String textFormat) {
    return new Case(name, new JsonInput(json, false), new Equivalent(textFormat), null);
  }

  static Case valid(String name, Blocker blocker, String json, String textFormat) {
    return new Case(name, new JsonInput(json, false), new Equivalent(textFormat), blocker);
  }

  static Case ignoreUnknown(String name, String json, String textFormat) {
    return new Case(name, new JsonInput(json, true), new Equivalent(textFormat), null);
  }

  static Case validator(String name, Blocker blocker, String json, Predicate<JsonNode> validator) {
    return new Case(name, new JsonInput(json, false), new Validated(validator), blocker);
  }

  static Case protobufInput(String name, Message message, String textFormat) {
    return new Case(name, new ProtobufInput(message), new Equivalent(textFormat), null);
  }

  /** The scenario names that occur more than once in {@code cases}. */
  static List<String> duplicateNames(Stream<Case> cases) {
    List<String> names = cases.map(Case::name).toList();
    Set<String> unique = new LinkedHashSet<>(names);
    List<String> duplicates = new ArrayList<>(names);
    unique.forEach(duplicates::remove);
    return duplicates;
  }

  static boolean hasMembers(JsonNode json, String... names) {
    return Stream.of(names).allMatch(json::has);
  }

  /**
   * Checks the {@code KNOWN_GAPS} table {@code knownGaps} against the mirrored scenario names and
   * the gap-ledger ids. Returns one message per violation, in a stable order; an empty list means
   * every key names a mirrored scenario and every value is a ledger id.
   */
  static List<String> knownGapProblems(
      Map<String, String> knownGaps, Set<String> scenarioNames, Set<String> ledgerIds) {
    List<String> problems = new ArrayList<>();
    knownGaps.forEach((name, gapId) -> {
      if (!scenarioNames.contains(name)) {
        problems.add("KNOWN_GAPS entry '" + name + "' names no mirrored scenario");
      }
      if (!ledgerIds.contains(gapId)) {
        problems.add(
            "KNOWN_GAPS entry '" + name + "' names gap '" + gapId + "', which has no ledger row");
      }
    });
    return List.copyOf(problems);
  }
}
