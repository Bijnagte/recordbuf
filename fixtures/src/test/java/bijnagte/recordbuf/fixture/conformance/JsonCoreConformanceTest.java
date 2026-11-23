package bijnagte.recordbuf.fixture.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import bijnagte.recordbuf.fixture.golden.format.Proto3JsonFormat;
import bijnagte.recordbuf.fixture.golden.format.ProtobufBinaryFormat;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mirrors the valid-JSON scenarios of the official protobuf conformance suite at {@code v36.2} that
 * cover the core types, against the vendored proto3 {@code TestAllTypesProto3}: the top level
 * ({@code HelloWorld}, {@code IgnoreUnknownJson*}), {@code RunJsonTestsForFieldNameConvention},
 * {@code RunJsonTestsForNonRepeatedTypes}, {@code RunJsonTestsForRepeatedTypes} and the {@code
 * IgnoreUnknownEnumStringValue*} part of {@code RunJsonTestsForUnknownEnumStringValues}. Inputs,
 * expected messages (upstream's text format, parsed with {@link TextFormat}), validator predicates
 * and case names are ported from {@code binary_json_conformance_suite.cc}. Scenarios that only
 * exercise {@code JsonFormat}'s own parse rejection are not ported (R15, Google-owned); {@code
 * RunJsonTestsForStoresDefaultPrimitive} is proto2 only upstream.
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
 *       {@code JsonFormat} print, then the same expectation as the port check. While {@link
 *       Proto3JsonFormat#notApplicableReason} reports no {@code Interop} on {@code
 *       TestAllTypesProto3} (gap {@code interop-all-types}), the half aborts with that id. Once
 *       {@code Interop} exists the half runs for real and any failure is a recordbuf bug.
 * </ul>
 *
 * <p>The vendored proto has constructs commented out (see {@link Blocker}). The generated reference
 * lacks those fields too, so a scenario that names one runs its port check against {@link
 * #UPSTREAM}, the reference descriptor with the commented-out constructs added back, as a {@link
 * DynamicMessage}; its recordbuf half aborts with the construct's ledger id. A blocked scenario
 * fails once the reference has the construct, so uncommenting it forces the case out of its blocker
 * and the half to run.
 */
class JsonCoreConformanceTest {

  private static final String REFERENCE_MESSAGE_NAME = "TestAllTypesProto3";
  private static final String INTEROP_GAP_ID = "interop-all-types";

  private static final Descriptor REFERENCE =
      com.google.protobuf_test_messages.proto3.TestAllTypesProto3.getDescriptor();

  /**
   * The reference descriptor with every commented-out construct a scenario names added back, so a
   * scenario that needs one can still check the port against upstream's full message shape.
   */
  private static final Descriptor UPSTREAM = upstreamDescriptor();

  private static final Proto3JsonFormat JSON_FORMAT = new Proto3JsonFormat();
  private static final ProtobufBinaryFormat BINARY_FORMAT = new ProtobufBinaryFormat();
  private static final JsonMapper MAPPER = new JsonMapper();

  /** A construct of the upstream message that the vendored proto comments out. */
  private enum Blocker {
    UPPERCASE_EDGE_NAME("field-name-uppercase-start"),
    ONEOF("oneof-snake-case-member"),
    MAP_MESSAGE_VALUE("map-message-value"),
    MAP_ENUM_VALUE("map-enum-value"),
    ALIAS_NAME("enum-alias-name-collision");

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
        case UPPERCASE_EDGE_NAME -> hasField("_field_name3");
        case ONEOF -> hasField("oneof_uint32");
        case MAP_MESSAGE_VALUE -> hasField("map_string_nested_message");
        case MAP_ENUM_VALUE -> hasField("map_string_nested_enum");
        case ALIAS_NAME ->
            REFERENCE.findEnumTypeByName("AliasedEnum").findValueByName("moo") != null;
      };
    }

    private static boolean hasField(String protoName) {
      return REFERENCE.findFieldByName(protoName) != null;
    }
  }

  /** What a scenario feeds the path. */
  private sealed interface Input permits JsonInput, ProtobufInput {}

  /** A JSON text; {@code ignoreUnknown} is upstream's {@code JSON_IGNORE_UNKNOWN_PARSING_TEST}. */
  private record JsonInput(String json, boolean ignoreUnknown) implements Input {}

  /** A protobuf-java message that the path prints as JSON (upstream's protobuf input). */
  private record ProtobufInput(Message message) implements Input {}

  /** What a scenario expects of the result. */
  private sealed interface Check permits Equivalent, Validated {}

  /** The result equals the message upstream writes as text format. */
  private record Equivalent(String textFormat) implements Check {}

  /** The printed JSON satisfies a predicate (upstream's {@code RunValidJsonTestWithValidator}). */
  private record Validated(Predicate<JsonNode> validator) implements Check {}

  /** One mirrored scenario; {@code blocker} is null when the generated reference can run it. */
  private record Case(String name, Input input, Check check, Blocker blocker) {}

  private static final List<Case> TOP_LEVEL = topLevelCases();
  private static final List<Case> FIELD_NAME_CONVENTION = fieldNameConventionCases();
  private static final List<Case> NON_REPEATED_TYPES = nonRepeatedTypesCases();
  private static final List<Case> REPEATED_TYPES = repeatedTypesCases();
  private static final List<Case> UNKNOWN_ENUM_STRING_VALUES = unknownEnumStringValueCases();

  /**
   * {@code RunJsonTests} top level: {@code HelloWorld} and the {@code IgnoreUnknownJson*} cases.
   */
  @TestFactory
  Stream<DynamicNode> topLevel() {
    return containers(TOP_LEVEL);
  }

  /** {@code RunJsonTestsForFieldNameConvention}: the valid-input and validator scenarios. */
  @TestFactory
  Stream<DynamicNode> fieldNameConvention() {
    return containers(FIELD_NAME_CONVENTION);
  }

  /** {@code RunJsonTestsForNonRepeatedTypes}: scalars, enums, strings, bytes, oneofs and maps. */
  @TestFactory
  Stream<DynamicNode> nonRepeatedTypes() {
    return containers(NON_REPEATED_TYPES);
  }

  /** {@code RunJsonTestsForRepeatedTypes}: the valid repeated-field scenarios. */
  @TestFactory
  Stream<DynamicNode> repeatedTypes() {
    return containers(REPEATED_TYPES);
  }

  /** {@code RunJsonTestsForUnknownEnumStringValues}: the ignore-unknown scenarios. */
  @TestFactory
  Stream<DynamicNode> unknownEnumStringValues() {
    return containers(UNKNOWN_ENUM_STRING_VALUES);
  }

  /** Every scenario name is unique, so a case name identifies one upstream scenario. */
  @Test
  void caseNames_areUnique() {
    List<String> names = allCases().map(Case::name).toList();
    Set<String> unique = new LinkedHashSet<>(names);
    List<String> duplicates = new ArrayList<>(names);
    unique.forEach(duplicates::remove);

    assertEquals(List.of(), duplicates, "scenario names used twice");
  }

  private static Stream<Case> allCases() {
    return Stream.of(
            TOP_LEVEL,
            FIELD_NAME_CONVENTION,
            NON_REPEATED_TYPES,
            REPEATED_TYPES,
            UNKNOWN_ENUM_STRING_VALUES)
        .flatMap(List::stream);
  }

  private static Stream<DynamicNode> containers(List<Case> cases) {
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
    String printed = JsonFormat.printer().print(actual);
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
    JsonFormat.Parser parser = JsonFormat.parser();
    if (ignoreUnknown) {
      parser = parser.ignoringUnknownFields();
    }
    parser.merge(json, builder);
    return builder.build();
  }

  private static Message fromText(Message.Builder builder, String textFormat) throws IOException {
    TextFormat.merge(textFormat, builder);
    return builder.build();
  }

  // ---- the recordbuf half: JsonFormat -> Interop -> record -> Interop -> JsonFormat ----

  private static void recordbufHalf(Case scenario) throws Exception {
    Blocker blocker = scenario.blocker();
    if (blocker != null) {
      throw new TestAbortedException(
          "gap "
              + blocker.ledgerId()
              + ": the vendored proto has the construct this scenario names commented out (also gap "
              + INTEROP_GAP_ID
              + ")");
    }
    Optional<String> noInterop =
        JSON_FORMAT.notApplicableReason(TestAllTypesProto3.fromBytes(new byte[0]));
    if (noInterop.isPresent()) {
      throw new TestAbortedException("gap " + INTEROP_GAP_ID + ": " + noInterop.get());
    }
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
    return JsonFormat.printer().print(parse(newBuilder(null), input.json(), true));
  }

  // ---- the upstream descriptor ----

  private static Descriptor upstreamDescriptor() {
    FileDescriptor file = REFERENCE.getFile();
    FileDescriptorProto.Builder fileProto = file.toProto().toBuilder();
    DescriptorProto.Builder message = fileProto.getMessageTypeBuilder(REFERENCE.getIndex());
    String scope = "." + file.getPackage() + ".";

    // The upstream edge-named int32 fields the vendored proto comments out, by field number.
    Map<String, Integer> edgeFields =
        Map.of(
            "_field_name3", 403,
            "FieldName8", 408,
            "Field_Name10", 410,
            "FIELD_NAME11", 411,
            "FIELD_name12", 412,
            "__field_name13", 413,
            "__Field_name14", 414,
            "Field_name18__", 418);
    edgeFields.forEach((name, number) ->
        message.addField(scalarField(name, number, FieldDescriptorProto.Type.TYPE_INT32)));

    int oneofIndex = message.getOneofDeclCount();
    message.addOneofDecl(OneofDescriptorProto.newBuilder().setName("oneof_field"));
    String nestedMessage = scope + REFERENCE_MESSAGE_NAME + ".NestedMessage";
    String nestedEnum = scope + REFERENCE_MESSAGE_NAME + ".NestedEnum";
    message
        .addField(
            oneofField("oneof_uint32", 111, FieldDescriptorProto.Type.TYPE_UINT32, oneofIndex))
        .addField(
            oneofField(
                    "oneof_nested_message", 112, FieldDescriptorProto.Type.TYPE_MESSAGE, oneofIndex)
                .setTypeName(nestedMessage))
        .addField(
            oneofField("oneof_string", 113, FieldDescriptorProto.Type.TYPE_STRING, oneofIndex))
        .addField(oneofField("oneof_bytes", 114, FieldDescriptorProto.Type.TYPE_BYTES, oneofIndex))
        .addField(oneofField("oneof_bool", 115, FieldDescriptorProto.Type.TYPE_BOOL, oneofIndex))
        .addField(
            oneofField("oneof_uint64", 116, FieldDescriptorProto.Type.TYPE_UINT64, oneofIndex))
        .addField(oneofField("oneof_float", 117, FieldDescriptorProto.Type.TYPE_FLOAT, oneofIndex))
        .addField(
            oneofField("oneof_double", 118, FieldDescriptorProto.Type.TYPE_DOUBLE, oneofIndex))
        .addField(
            oneofField("oneof_enum", 119, FieldDescriptorProto.Type.TYPE_ENUM, oneofIndex)
                .setTypeName(nestedEnum));

    addMapField(
        message,
        "map_string_bytes",
        70,
        "MapStringBytesEntry",
        valueField(FieldDescriptorProto.Type.TYPE_BYTES));
    addMapField(
        message,
        "map_string_nested_message",
        71,
        "MapStringNestedMessageEntry",
        valueField(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(nestedMessage));
    addMapField(
        message,
        "map_string_foreign_message",
        72,
        "MapStringForeignMessageEntry",
        valueField(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(scope + "ForeignMessage"));
    addMapField(
        message,
        "map_string_nested_enum",
        73,
        "MapStringNestedEnumEntry",
        valueField(FieldDescriptorProto.Type.TYPE_ENUM).setTypeName(nestedEnum));
    addMapField(
        message,
        "map_string_foreign_enum",
        74,
        "MapStringForeignEnumEntry",
        valueField(FieldDescriptorProto.Type.TYPE_ENUM).setTypeName(scope + "ForeignEnum"));

    for (EnumDescriptorProto.Builder enumType : message.getEnumTypeBuilderList()) {
      if (enumType.getName().equals("AliasedEnum")) {
        enumType.addValue(EnumValueDescriptorProto.newBuilder().setName("moo").setNumber(2));
      }
    }
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

  private static FieldDescriptorProto.Builder oneofField(
      String name, int number, FieldDescriptorProto.Type type, int oneofIndex) {
    return scalarField(name, number, type).setOneofIndex(oneofIndex);
  }

  private static FieldDescriptorProto.Builder valueField(FieldDescriptorProto.Type type) {
    return scalarField("value", 2, type);
  }

  private static void addMapField(
      DescriptorProto.Builder message,
      String fieldName,
      int number,
      String entryName,
      FieldDescriptorProto.Builder valueField) {
    message.addNestedType(
        DescriptorProto.newBuilder()
            .setName(entryName)
            .addField(scalarField("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(valueField)
            .setOptions(MessageOptions.newBuilder().setMapEntry(true)));
    message.addField(
        scalarField(fieldName, number, FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
            .setTypeName(
                "."
                    + REFERENCE.getFile().getPackage()
                    + "."
                    + REFERENCE_MESSAGE_NAME
                    + "."
                    + entryName));
  }

  // ---- the ported scenarios ----

  private static Case valid(String name, String json, String textFormat) {
    return new Case(name, new JsonInput(json, false), new Equivalent(textFormat), null);
  }

  private static Case valid(String name, Blocker blocker, String json, String textFormat) {
    return new Case(name, new JsonInput(json, false), new Equivalent(textFormat), blocker);
  }

  private static Case ignoreUnknown(String name, String json, String textFormat) {
    return new Case(name, new JsonInput(json, true), new Equivalent(textFormat), null);
  }

  private static Case ignoreUnknown(String name, Blocker blocker, String json, String textFormat) {
    return new Case(name, new JsonInput(json, true), new Equivalent(textFormat), blocker);
  }

  private static Case validator(
      String name, Blocker blocker, String json, Predicate<JsonNode> validator) {
    return new Case(name, new JsonInput(json, false), new Validated(validator), blocker);
  }

  private static Case protobufInput(String name, Message message, String textFormat) {
    return new Case(name, new ProtobufInput(message), new Equivalent(textFormat), null);
  }

  private static boolean hasMembers(JsonNode json, String... names) {
    return Stream.of(names).allMatch(json::has);
  }

  private static List<Case> topLevelCases() {
    return List.of(
        valid(
            "HelloWorld",
            "{\"optionalString\":\"Hello, World!\"}",
            "optional_string: 'Hello, World!'"),
        ignoreUnknown("IgnoreUnknownJsonNumber", "{\"unknown\": 1}", ""),
        ignoreUnknown("IgnoreUnknownJsonString", "{\"unknown\": \"a\"}", ""),
        ignoreUnknown("IgnoreUnknownJsonTrue", "{\"unknown\": true}", ""),
        ignoreUnknown("IgnoreUnknownJsonFalse", "{\"unknown\": false}", ""),
        ignoreUnknown("IgnoreUnknownJsonNull", "{\"unknown\": null}", ""),
        ignoreUnknown("IgnoreUnknownJsonObject", "{\"unknown\": {\"a\": 1}}", ""));
  }

  private static List<Case> fieldNameConventionCases() {
    String snakeCaseJson =
        "{\"fieldname1\": 1, \"fieldName2\": 2, \"FieldName3\": 3, \"fieldName4\": 4}";
    String numbersJson = "{\"field0name5\": 5, \"field0Name6\": 6}";
    String mixedCasesJson =
        "{\"fieldName7\": 7, \"FieldName8\": 8, \"fieldName9\": 9, \"FieldName10\": 10,"
            + " \"FIELDNAME11\": 11, \"FIELDName12\": 12}";
    String doubleUnderscoresJson =
        "{\"FieldName13\": 13, \"FieldName14\": 14, \"fieldName15\": 15, \"fieldName16\": 16,"
            + " \"fieldName17\": 17, \"FieldName18\": 18}";
    return List.of(
        valid(
            "FieldNameInSnakeCase",
            Blocker.UPPERCASE_EDGE_NAME,
            snakeCaseJson,
            "fieldname1: 1 field_name2: 2 _field_name3: 3 field__name4_: 4"),
        valid("FieldNameWithNumbers", numbersJson, "field0name5: 5 field_0_name6: 6"),
        valid(
            "FieldNameWithMixedCases",
            Blocker.UPPERCASE_EDGE_NAME,
            mixedCasesJson,
            "fieldName7: 7 FieldName8: 8 field_Name9: 9 Field_Name10: 10 FIELD_NAME11: 11"
                + " FIELD_name12: 12"),
        valid(
            "FieldNameWithDoubleUnderscores",
            Blocker.UPPERCASE_EDGE_NAME,
            doubleUnderscoresJson,
            "__field_name13: 13 __Field_name14: 14 field__name15: 15 field__Name16: 16"
                + " field_name17__: 17 Field_name18__: 18"),
        valid(
            "OriginalProtoFieldName",
            Blocker.UPPERCASE_EDGE_NAME,
            "{\"fieldname1\": 1, \"field_name2\": 2, \"_field_name3\": 3, \"field__name4_\": 4,"
                + " \"field0name5\": 5, \"field_0_name6\": 6, \"fieldName7\": 7,"
                + " \"FieldName8\": 8, \"field_Name9\": 9, \"Field_Name10\": 10,"
                + " \"FIELD_NAME11\": 11, \"FIELD_name12\": 12, \"__field_name13\": 13,"
                + " \"__Field_name14\": 14, \"field__name15\": 15, \"field__Name16\": 16,"
                + " \"field_name17__\": 17, \"Field_name18__\": 18}",
            "fieldname1: 1 field_name2: 2 _field_name3: 3 field__name4_: 4 field0name5: 5"
                + " field_0_name6: 6 fieldName7: 7 FieldName8: 8 field_Name9: 9"
                + " Field_Name10: 10 FIELD_NAME11: 11 FIELD_name12: 12 __field_name13: 13"
                + " __Field_name14: 14 field__name15: 15 field__Name16: 16"
                + " field_name17__: 17 Field_name18__: 18"),
        valid("FieldNameEscaped", "{\"fieldn\\u0061me1\": 1}", "fieldname1: 1"),
        valid(
            "OneLineNoSpaces",
            "{\"optionalInt32\":1,\"optionalInt64\":2}",
            "optional_int32: 1 optional_int64: 2"),
        valid(
            "OneLineWithSpaces",
            "{ \"optionalInt32\" : 1 , \"optionalInt64\" : 2 }",
            "optional_int32: 1 optional_int64: 2"),
        valid(
            "MultilineNoSpaces",
            "{\n\"optionalInt32\"\n:\n1\n,\n\"optionalInt64\"\n:\n2\n}",
            "optional_int32: 1 optional_int64: 2"),
        valid(
            "MultilineWithSpaces",
            "{\n  \"optionalInt32\"  :  1\n  ,\n  \"optionalInt64\"  :  2\n}\n",
            "optional_int32: 1 optional_int64: 2"),
        // Serializers should use lowerCamelCase by default.
        validator(
            "FieldNameInLowerCamelCase.Validator",
            Blocker.UPPERCASE_EDGE_NAME,
            snakeCaseJson,
            json -> hasMembers(json, "fieldname1", "fieldName2", "FieldName3", "fieldName4")),
        validator(
            "FieldNameWithNumbers.Validator",
            null,
            numbersJson,
            json -> hasMembers(json, "field0name5", "field0Name6")),
        validator(
            "FieldNameWithMixedCases.Validator",
            Blocker.UPPERCASE_EDGE_NAME,
            mixedCasesJson,
            json ->
                hasMembers(
                    json,
                    "fieldName7",
                    "FieldName8",
                    "fieldName9",
                    "FieldName10",
                    "FIELDNAME11",
                    "FIELDName12")),
        validator(
            "FieldNameWithDoubleUnderscores.Validator",
            Blocker.UPPERCASE_EDGE_NAME,
            doubleUnderscoresJson,
            json ->
                hasMembers(
                    json,
                    "FieldName13",
                    "FieldName14",
                    "fieldName15",
                    "fieldName16",
                    "fieldName17",
                    "FieldName18")),
        validator(
            "SkipsDefaultPrimitive.Validator",
            Blocker.UPPERCASE_EDGE_NAME,
            "{\"FieldName13\": 0}",
            json -> !json.has("FieldName13")));
  }

  private static List<Case> nonRepeatedTypesCases() {
    List<Case> cases = new ArrayList<>();
    cases.addAll(integerCases());
    cases.addAll(boolCases());
    cases.addAll(floatCases());
    cases.addAll(doubleCases());
    cases.addAll(enumCases());
    cases.addAll(stringAndBytesCases());
    cases.addAll(oneofCases());
    cases.addAll(mapCases());
    return List.copyOf(cases);
  }

  private static List<Case> integerCases() {
    return List.of(
        valid(
            "Int32FieldMaxValue", "{\"optionalInt32\": 2147483647}", "optional_int32: 2147483647"),
        valid(
            "Int32FieldMinValue",
            "{\"optionalInt32\": -2147483648}",
            "optional_int32: -2147483648"),
        valid(
            "Uint32FieldMaxValue",
            "{\"optionalUint32\": 4294967295}",
            "optional_uint32: 4294967295"),
        valid(
            "Int64FieldMaxValue",
            "{\"optionalInt64\": \"9223372036854775807\"}",
            "optional_int64: 9223372036854775807"),
        valid(
            "Int64FieldMinValue",
            "{\"optionalInt64\": \"-9223372036854775808\"}",
            "optional_int64: -9223372036854775808"),
        valid(
            "Uint64FieldMaxValue",
            "{\"optionalUint64\": \"18446744073709551615\"}",
            "optional_uint64: 18446744073709551615"),
        // The largest Int64 and Uint64 an IEEE-754 double represents exactly, unquoted.
        valid(
            "Int64FieldMaxValueNotQuoted",
            "{\"optionalInt64\": 9223372036854774784}",
            "optional_int64: 9223372036854774784"),
        valid(
            "Int64FieldMinValueNotQuoted",
            "{\"optionalInt64\": -9223372036854775808}",
            "optional_int64: -9223372036854775808"),
        valid(
            "Uint64FieldMaxValueNotQuoted",
            "{\"optionalUint64\": 18446744073709549568}",
            "optional_uint64: 18446744073709549568"),
        // Values can be represented as JSON strings.
        valid(
            "Int32FieldStringValue",
            "{\"optionalInt32\": \"2147483647\"}",
            "optional_int32: 2147483647"),
        valid(
            "Int32FieldStringValueEscaped",
            "{\"optionalInt32\": \"2\\u003147483647\"}",
            "optional_int32: 2147483647"),
        valid("Int32FieldStringValueZero", "{\"optionalInt32\": \"0\"}", "optional_int32: 0"),
        valid(
            "Int32FieldQuotedExponentialValue",
            "{\"optionalInt32\": \"1e5\"}",
            "optional_int32: 100000"),
        // Integers but represented as float values are accepted.
        valid(
            "Int32FieldFloatTrailingZero",
            "{\"optionalInt32\": 100000.000}",
            "optional_int32: 100000"),
        valid("Int32FieldExponentialFormat", "{\"optionalInt32\": 1e5}", "optional_int32: 100000"),
        valid(
            "Int32FieldMaxFloatValue",
            "{\"optionalInt32\": 2.147483647e9}",
            "optional_int32: 2147483647"),
        valid(
            "Int32FieldMinFloatValue",
            "{\"optionalInt32\": -2.147483648e9}",
            "optional_int32: -2147483648"),
        valid(
            "Uint32FieldMaxFloatValue",
            "{\"optionalUint32\": 4.294967295e9}",
            "optional_uint32: 4294967295"),
        // 64-bit values are serialized as strings.
        validator(
            "Int64FieldBeString",
            null,
            "{\"optionalInt64\": 1}",
            json ->
                json.get("optionalInt64").isString()
                    && json.get("optionalInt64").asString().equals("1")),
        validator(
            "Uint64FieldBeString",
            null,
            "{\"optionalUint64\": 1}",
            json ->
                json.get("optionalUint64").isString()
                    && json.get("optionalUint64").asString().equals("1")));
  }

  private static List<Case> boolCases() {
    return List.of(
        valid("BoolFieldTrue", "{\"optionalBool\":true}", "optional_bool: true"),
        valid("BoolFieldFalse", "{\"optionalBool\":false}", "optional_bool: false"));
  }

  private static List<Case> floatCases() {
    return List.of(
        valid(
            "FloatFieldMinPositiveValue",
            "{\"optionalFloat\": 1.175494e-38}",
            "optional_float: 1.175494e-38"),
        valid(
            "FloatFieldMaxNegativeValue",
            "{\"optionalFloat\": -1.175494e-38}",
            "optional_float: -1.175494e-38"),
        valid(
            "FloatFieldMaxPositiveValue",
            "{\"optionalFloat\": 3.402823e+38}",
            "optional_float: 3.402823e+38"),
        // Upstream repeats the positive value here, so the port does too.
        valid(
            "FloatFieldMinNegativeValue",
            "{\"optionalFloat\": 3.402823e+38}",
            "optional_float: 3.402823e+38"),
        valid("FloatFieldQuotedValue", "{\"optionalFloat\": \"1\"}", "optional_float: 1"),
        valid(
            "FloatFieldQuotedExponentialValue",
            "{\"optionalFloat\": \"1.175494e-38\"}",
            "optional_float: 1.175494e-38"),
        valid("FloatFieldNan", "{\"optionalFloat\": \"NaN\"}", "optional_float: nan"),
        valid("FloatFieldInfinity", "{\"optionalFloat\": \"Infinity\"}", "optional_float: inf"),
        valid(
            "FloatFieldNegativeInfinity",
            "{\"optionalFloat\": \"-Infinity\"}",
            "optional_float: -inf"),
        // Non-canonical NaN is normalized: a quiet and a signaling NaN print as "NaN".
        protobufInput(
            "FloatFieldNormalizeQuietNan",
            singleFieldMessage("optional_float", Float.intBitsToFloat(0x7FA12345)),
            "optional_float: nan"),
        protobufInput(
            "FloatFieldNormalizeSignalingNan",
            singleFieldMessage("optional_float", Float.intBitsToFloat(0xFFB54321)),
            "optional_float: nan"));
  }

  private static List<Case> doubleCases() {
    return List.of(
        valid(
            "DoubleFieldMinPositiveValue",
            "{\"optionalDouble\": 2.22507e-308}",
            "optional_double: 2.22507e-308"),
        valid(
            "DoubleFieldMaxNegativeValue",
            "{\"optionalDouble\": -2.22507e-308}",
            "optional_double: -2.22507e-308"),
        valid(
            "DoubleFieldMaxPositiveValue",
            "{\"optionalDouble\": 1.79769e+308}",
            "optional_double: 1.79769e+308"),
        valid(
            "DoubleFieldMinNegativeValue",
            "{\"optionalDouble\": -1.79769e+308}",
            "optional_double: -1.79769e+308"),
        valid("DoubleFieldQuotedValue", "{\"optionalDouble\": \"1\"}", "optional_double: 1"),
        valid(
            "DoubleFieldQuotedExponentialValue",
            "{\"optionalDouble\": \"2.22507e-308\"}",
            "optional_double: 2.22507e-308"),
        valid("DoubleFieldNan", "{\"optionalDouble\": \"NaN\"}", "optional_double: nan"),
        valid("DoubleFieldInfinity", "{\"optionalDouble\": \"Infinity\"}", "optional_double: inf"),
        valid(
            "DoubleFieldNegativeInfinity",
            "{\"optionalDouble\": \"-Infinity\"}",
            "optional_double: -inf"),
        protobufInput(
            "DoubleFieldNormalizeQuietNan",
            singleFieldMessage("optional_double", Double.longBitsToDouble(0x7FFA123456789ABCL)),
            "optional_double: nan"),
        protobufInput(
            "DoubleFieldNormalizeSignalingNan",
            singleFieldMessage("optional_double", Double.longBitsToDouble(0xFFFBCBA987654321L)),
            "optional_double: nan"));
  }

  private static Message singleFieldMessage(String protoName, Object value) {
    return com.google.protobuf_test_messages.proto3.TestAllTypesProto3.newBuilder()
        .setField(REFERENCE.findFieldByName(protoName), value)
        .build();
  }

  private static List<Case> enumCases() {
    return List.of(
        valid("EnumField", "{\"optionalNestedEnum\": \"FOO\"}", "optional_nested_enum: FOO"),
        valid(
            "EnumFieldWithAlias",
            "{\"optionalAliasedEnum\": \"ALIAS_BAZ\"}",
            "optional_aliased_enum: ALIAS_BAZ"),
        valid(
            "EnumFieldWithAliasUseAlias",
            "{\"optionalAliasedEnum\": \"MOO\"}",
            "optional_aliased_enum: ALIAS_BAZ"),
        valid(
            "EnumFieldWithAliasLowerCase",
            Blocker.ALIAS_NAME,
            "{\"optionalAliasedEnum\": \"moo\"}",
            "optional_aliased_enum: ALIAS_BAZ"),
        valid(
            "EnumFieldWithAliasDifferentCase",
            "{\"optionalAliasedEnum\": \"bAz\"}",
            "optional_aliased_enum: ALIAS_BAZ"),
        // Numeric values are allowed.
        valid(
            "EnumFieldNumericValueZero",
            "{\"optionalNestedEnum\": 0}",
            "optional_nested_enum: FOO"),
        valid(
            "EnumFieldNumericValueNonZero",
            "{\"optionalNestedEnum\": 1}",
            "optional_nested_enum: BAR"),
        // Unknown enum values are represented as numeric values.
        validator(
            "EnumFieldUnknownValue",
            null,
            "{\"optionalNestedEnum\": 123}",
            json ->
                json.get("optionalNestedEnum").isInt()
                    && json.get("optionalNestedEnum").asInt() == 123));
  }

  private static List<Case> stringAndBytesCases() {
    return List.of(
        valid(
            "StringField",
            "{\"optionalString\": \"Hello world!\"}",
            "optional_string: \"Hello world!\""),
        valid("StringFieldUnicode", "{\"optionalString\": \"谷歌\"}", "optional_string: \"谷歌\""),
        valid(
            "StringFieldEscape",
            "{\"optionalString\": \"\\\"\\\\\\/\\b\\f\\n\\r\\t\"}",
            "optional_string: \"\\\"\\\\/\\b\\f\\n\\r\\t\""),
        valid(
            "StringFieldUnicodeEscape",
            "{\"optionalString\": \"\\u8C37\\u6B4C\"}",
            "optional_string: \"谷歌\""),
        valid(
            "StringFieldUnicodeEscapeWithLowercaseHexLetters",
            "{\"optionalString\": \"\\u8c37\\u6b4c\"}",
            "optional_string: \"谷歌\""),
        // The character is an emoji: grinning face with smiling eyes.
        valid(
            "StringFieldSurrogatePair",
            "{\"optionalString\": \"\\uD83D\\uDE01\"}",
            "optional_string: \"\\xF0\\x9F\\x98\\x81\""),
        valid(
            "StringFieldEmbeddedNull",
            "{\"optionalString\": \"Hello\\u0000world!\"}",
            "optional_string: \"Hello\\000world!\""),
        valid("BytesField", "{\"optionalBytes\": \"AQI=\"}", "optional_bytes: \"\\x01\\x02\""),
        valid("BytesFieldBase64Url", "{\"optionalBytes\": \"-_\"}", "optional_bytes: \"\\xfb\""),
        valid(
            "MessageField",
            "{\"optionalNestedMessage\": {\"a\": 1234}}",
            "optional_nested_message: {a: 1234}"));
  }

  private static List<Case> oneofCases() {
    Blocker oneof = Blocker.ONEOF;
    return List.of(
        valid(
            "OneofFieldNullFirst",
            oneof,
            "{\"oneofUint32\": null, \"oneofString\": \"test\"}",
            "oneof_string: \"test\""),
        valid(
            "OneofFieldNullSecond",
            oneof,
            "{\"oneofString\": \"test\", \"oneofUint32\": null}",
            "oneof_string: \"test\""),
        valid("OneofZeroUint32", oneof, "{\"oneofUint32\": 0}", "oneof_uint32: 0"),
        valid(
            "OneofZeroMessage", oneof, "{\"oneofNestedMessage\": {}}", "oneof_nested_message: {}"),
        valid("OneofZeroString", oneof, "{\"oneofString\": \"\"}", "oneof_string: \"\""),
        valid("OneofZeroBytes", oneof, "{\"oneofBytes\": \"\"}", "oneof_bytes: \"\""),
        valid("OneofZeroBool", oneof, "{\"oneofBool\": false}", "oneof_bool: false"),
        valid("OneofZeroUint64", oneof, "{\"oneofUint64\": 0}", "oneof_uint64: 0"),
        valid("OneofZeroFloat", oneof, "{\"oneofFloat\": 0.0}", "oneof_float: 0"),
        valid("OneofZeroDouble", oneof, "{\"oneofDouble\": 0.0}", "oneof_double: 0"),
        valid("OneofZeroEnum", oneof, "{\"oneofEnum\":\"FOO\"}", "oneof_enum: FOO"));
  }

  private static List<Case> mapCases() {
    return List.of(
        valid(
            "Int32MapField",
            "{\"mapInt32Int32\": {\"1\": 2, \"3\": 4}}",
            "map_int32_int32: {key: 1 value: 2} map_int32_int32: {key: 3 value: 4}"),
        valid(
            "Uint32MapField",
            "{\"mapUint32Uint32\": {\"1\": 2, \"3\": 4}}",
            "map_uint32_uint32: {key: 1 value: 2} map_uint32_uint32: {key: 3 value: 4}"),
        valid(
            "Int64MapField",
            "{\"mapInt64Int64\": {\"1\": 2, \"3\": 4}}",
            "map_int64_int64: {key: 1 value: 2} map_int64_int64: {key: 3 value: 4}"),
        valid(
            "Uint64MapField",
            "{\"mapUint64Uint64\": {\"1\": 2, \"3\": 4}}",
            "map_uint64_uint64: {key: 1 value: 2} map_uint64_uint64: {key: 3 value: 4}"),
        valid(
            "BoolMapField",
            "{\"mapBoolBool\": {\"true\": true, \"false\": false}}",
            "map_bool_bool: {key: true value: true} map_bool_bool: {key: false value: false}"),
        valid(
            "MessageMapField",
            Blocker.MAP_MESSAGE_VALUE,
            "{\"mapStringNestedMessage\": {\"hello\": {\"a\": 1234}, \"world\": {\"a\": 5678}}}",
            "map_string_nested_message: {key: \"hello\" value: {a: 1234}}"
                + " map_string_nested_message: {key: \"world\" value: {a: 5678}}"),
        // Map keys are JSON strings, so escaping is allowed.
        valid(
            "Int32MapEscapedKey",
            "{\"mapInt32Int32\": {\"\\u0031\": 2}}",
            "map_int32_int32: {key: 1 value: 2}"),
        valid(
            "Int64MapEscapedKey",
            "{\"mapInt64Int64\": {\"\\u0031\": 2}}",
            "map_int64_int64: {key: 1 value: 2}"),
        valid(
            "BoolMapEscapedKey",
            "{\"mapBoolBool\": {\"tr\\u0075e\": true}}",
            "map_bool_bool: {key: true value: true}"));
  }

  private static List<Case> repeatedTypesCases() {
    return List.of(
        valid(
            "PrimitiveRepeatedField",
            "{\"repeatedInt32\": [1, 2, 3, 4]}",
            "repeated_int32: [1, 2, 3, 4]"),
        valid(
            "EnumRepeatedField",
            "{\"repeatedNestedEnum\": [\"FOO\", \"BAR\", \"BAZ\"]}",
            "repeated_nested_enum: [FOO, BAR, BAZ]"),
        valid(
            "StringRepeatedField",
            "{\"repeatedString\": [\"Hello\", \"world\"]}",
            "repeated_string: [\"Hello\", \"world\"]"),
        valid(
            "BytesRepeatedField",
            "{\"repeatedBytes\": [\"AAEC\", \"AQI=\"]}",
            "repeated_bytes: [\"\\x00\\x01\\x02\", \"\\x01\\x02\"]"),
        valid(
            "MessageRepeatedField",
            "{\"repeatedNestedMessage\": [{\"a\": 1234}, {\"a\": 5678}]}",
            "repeated_nested_message: {a: 1234} repeated_nested_message: {a: 5678}"));
  }

  private static List<Case> unknownEnumStringValueCases() {
    return List.of(
        ignoreUnknown(
            "IgnoreUnknownEnumStringValueInOptionalField",
            "{\"optional_nested_enum\": \"UNKNOWN_ENUM_VALUE\"}",
            ""),
        ignoreUnknown(
            "IgnoreUnknownEnumStringValueInRepeatedField",
            "{\"repeated_nested_enum\": [\"UNKNOWN_ENUM_VALUE\"]}",
            ""),
        ignoreUnknown(
            "IgnoreUnknownEnumStringValueInMapValue",
            Blocker.MAP_ENUM_VALUE,
            "{\"map_string_nested_enum\": {\"key\": \"UNKNOWN_ENUM_VALUE\"}}",
            ""),
        // A mix of known and unknown values in one array keeps the known ones.
        ignoreUnknown(
            "IgnoreUnknownEnumStringValueInRepeatedPart",
            "{\"repeated_nested_enum\": [\"FOO\", \"UNKNOWN_ENUM_VALUE\", \"FOO\"]}",
            "repeated_nested_enum: FOO repeated_nested_enum: FOO"),
        ignoreUnknown(
            "IgnoreUnknownEnumStringValueInMapPart",
            Blocker.MAP_ENUM_VALUE,
            "{\"map_string_nested_enum\": {\"key1\": \"FOO\", \"key2\": \"UNKNOWN_ENUM_VALUE\"}}",
            "map_string_nested_enum: {key: \"key1\" value: FOO}"));
  }
}
