package bijnagte.recordbuf.fixture.conformance;

import static bijnagte.recordbuf.fixture.conformance.JsonConformance.REFERENCE;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.containers;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.duplicateNames;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.hasMembers;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.ignoreUnknown;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.protobufInput;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.valid;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.validator;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.conformance.JsonConformance.Blocker;
import bijnagte.recordbuf.fixture.conformance.JsonConformance.Case;
import com.google.protobuf.Message;
import com.google.protobuf_test_messages.proto3.TestAllTypesProto3;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Mirrors the valid-JSON scenarios of the official protobuf conformance suite at {@code v36.2} that
 * cover the core types, against the vendored proto3 {@code TestAllTypesProto3}: the top level
 * ({@code HelloWorld}, {@code IgnoreUnknownJson*}), {@code RunJsonTestsForFieldNameConvention},
 * {@code RunJsonTestsForNonRepeatedTypes}, {@code RunJsonTestsForRepeatedTypes} and the {@code
 * IgnoreUnknownEnumStringValue*} part of {@code RunJsonTestsForUnknownEnumStringValues}. Inputs,
 * expected messages (upstream's text format, parsed with {@link com.google.protobuf.TextFormat}),
 * validator predicates and case names are ported from {@code binary_json_conformance_suite.cc}.
 * Scenarios that only exercise {@code JsonFormat}'s own parse rejection are not ported (R15,
 * Google-owned); {@code RunJsonTestsForStoresDefaultPrimitive} is proto2 only upstream.
 *
 * <p>Each scenario runs as a dynamic container with a {@code portCheck} (protobuf-java alone, runs
 * now) and a {@code recordbuf} half (aborts with the id of the gap the scenario names, until the
 * gap closes); see {@link JsonConformance}.
 */
class JsonCoreConformanceTest {

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
    assertEquals(List.of(), duplicateNames(allCases()), "scenario names used twice");
  }

  static Stream<Case> allCases() {
    return Stream.of(
            TOP_LEVEL,
            FIELD_NAME_CONVENTION,
            NON_REPEATED_TYPES,
            REPEATED_TYPES,
            UNKNOWN_ENUM_STRING_VALUES)
        .flatMap(List::stream);
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
            snakeCaseJson,
            "fieldname1: 1 field_name2: 2 _field_name3: 3 field__name4_: 4"),
        valid("FieldNameWithNumbers", numbersJson, "field0name5: 5 field_0_name6: 6"),
        valid(
            "FieldNameWithMixedCases",
            mixedCasesJson,
            "fieldName7: 7 FieldName8: 8 field_Name9: 9 Field_Name10: 10 FIELD_NAME11: 11"
                + " FIELD_name12: 12"),
        valid(
            "FieldNameWithDoubleUnderscores",
            doubleUnderscoresJson,
            "__field_name13: 13 __Field_name14: 14 field__name15: 15 field__Name16: 16"
                + " field_name17__: 17 Field_name18__: 18"),
        valid(
            "OriginalProtoFieldName",
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
            null,
            snakeCaseJson,
            json -> hasMembers(json, "fieldname1", "fieldName2", "FieldName3", "fieldName4")),
        validator(
            "FieldNameWithNumbers.Validator",
            null,
            numbersJson,
            json -> hasMembers(json, "field0name5", "field0Name6")),
        validator(
            "FieldNameWithMixedCases.Validator",
            null,
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
            null,
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
            null,
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
    return TestAllTypesProto3.newBuilder()
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
    return List.of(
        valid(
            "OneofFieldNullFirst",
            "{\"oneofUint32\": null, \"oneofString\": \"test\"}",
            "oneof_string: \"test\""),
        valid(
            "OneofFieldNullSecond",
            "{\"oneofString\": \"test\", \"oneofUint32\": null}",
            "oneof_string: \"test\""),
        valid("OneofZeroUint32", "{\"oneofUint32\": 0}", "oneof_uint32: 0"),
        valid("OneofZeroMessage", "{\"oneofNestedMessage\": {}}", "oneof_nested_message: {}"),
        valid("OneofZeroString", "{\"oneofString\": \"\"}", "oneof_string: \"\""),
        valid("OneofZeroBytes", "{\"oneofBytes\": \"\"}", "oneof_bytes: \"\""),
        valid("OneofZeroBool", "{\"oneofBool\": false}", "oneof_bool: false"),
        valid("OneofZeroUint64", "{\"oneofUint64\": 0}", "oneof_uint64: 0"),
        valid("OneofZeroFloat", "{\"oneofFloat\": 0.0}", "oneof_float: 0"),
        valid("OneofZeroDouble", "{\"oneofDouble\": 0.0}", "oneof_double: 0"),
        valid("OneofZeroEnum", "{\"oneofEnum\":\"FOO\"}", "oneof_enum: FOO"));
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
