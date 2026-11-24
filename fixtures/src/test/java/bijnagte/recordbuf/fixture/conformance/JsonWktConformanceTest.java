package bijnagte.recordbuf.fixture.conformance;

import static bijnagte.recordbuf.fixture.conformance.JsonConformance.containers;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.duplicateNames;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.valid;
import static bijnagte.recordbuf.fixture.conformance.JsonConformance.validator;
import static org.junit.jupiter.api.Assertions.assertEquals;

import bijnagte.recordbuf.fixture.conformance.JsonConformance.Blocker;
import bijnagte.recordbuf.fixture.conformance.JsonConformance.Case;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

/**
 * Mirrors the valid-JSON scenarios of the official protobuf conformance suite at {@code v36.2} that
 * cover the well-known types, against the vendored proto3 {@code TestAllTypesProto3}: {@code
 * RunJsonTestsForNullTypes}, {@code RunJsonTestsForWrapperTypes} (wrappers, {@code Duration} and
 * {@code Timestamp}), {@code RunJsonTestsForFieldMask}, {@code RunJsonTestsForStruct}, {@code
 * RunJsonTestsForValue} and {@code RunJsonTestsForAny}. Inputs, expected messages (upstream's text
 * format, parsed with {@link com.google.protobuf.TextFormat}), validator predicates and case names
 * are ported from {@code binary_json_conformance_suite.cc}.
 *
 * <p>Not ported, because no valid scenario is left in them: the {@code ExpectParseFailureForJson}
 * scenarios only exercise {@code JsonFormat}'s own parse rejection, and the {@code
 * ExpectSerializeFailureForJson} scenarios only its printer's rejection of an out-of-range or
 * non-canonical message (R15, Google-owned; the manifest lists them as not applicable).
 *
 * <p>Each scenario runs as a dynamic container with a {@code portCheck} (protobuf-java alone, runs
 * now) and a {@code recordbuf} half (aborts with the id of the gap the scenario names, until the
 * gap closes); see {@link JsonConformance}. An {@code Any} scenario names {@code
 * TestAllTypesProto3} or a well-known type, and the shared type registry resolves both.
 */
class JsonWktConformanceTest {

  private static final String TEST_MESSAGE_TYPE_URL =
      "type.googleapis.com/protobuf_test_messages.proto3.TestAllTypesProto3";

  private static final List<Case> NULL_TYPES = nullTypesCases();
  private static final List<Case> WRAPPER_TYPES = wrapperTypesCases();
  private static final List<Case> FIELD_MASK = fieldMaskCases();
  private static final List<Case> STRUCT = structCases();
  private static final List<Case> VALUE = valueCases();
  private static final List<Case> ANY = anyCases();

  /** {@code RunJsonTestsForNullTypes}: {@code null} is accepted for every field type. */
  @TestFactory
  Stream<DynamicNode> nullTypes() {
    return containers(NULL_TYPES);
  }

  /**
   * {@code RunJsonTestsForWrapperTypes}: the wrappers, then {@code Duration} and {@code Timestamp}.
   */
  @TestFactory
  Stream<DynamicNode> wrapperTypes() {
    return containers(WRAPPER_TYPES);
  }

  /** {@code RunJsonTestsForFieldMask}: the valid {@code FieldMask} scenarios. */
  @TestFactory
  Stream<DynamicNode> fieldMask() {
    return containers(FIELD_MASK);
  }

  /** {@code RunJsonTestsForStruct}: the valid {@code Struct} scenarios. */
  @TestFactory
  Stream<DynamicNode> struct() {
    return containers(STRUCT);
  }

  /** {@code RunJsonTestsForValue}: {@code Value}, {@code ListValue} and {@code NullValue}. */
  @TestFactory
  Stream<DynamicNode> value() {
    return containers(VALUE);
  }

  /** {@code RunJsonTestsForAny}: the valid {@code Any} scenarios. */
  @TestFactory
  Stream<DynamicNode> any() {
    return containers(ANY);
  }

  /** Every scenario name is unique, so a case name identifies one upstream scenario. */
  @Test
  void caseNames_areUnique() {
    assertEquals(List.of(), duplicateNames(allCases()), "scenario names used twice");
  }

  static Stream<Case> allCases() {
    return Stream.of(NULL_TYPES, WRAPPER_TYPES, FIELD_MASK, STRUCT, VALUE, ANY)
        .flatMap(List::stream);
  }

  private static String optionalDuration(JsonNode json) {
    return json.get("optionalDuration").asString();
  }

  private static String optionalTimestamp(JsonNode json) {
    return json.get("optionalTimestamp").asString();
  }

  private static List<Case> nullTypesCases() {
    return List.of(
        // "null" is accepted for all fields types.
        valid(
            "AllFieldAcceptNull",
            Blocker.MAP_MESSAGE_VALUE,
            """
            {
              "optionalInt32": null,
              "optionalInt64": null,
              "optionalUint32": null,
              "optionalUint64": null,
              "optionalSint32": null,
              "optionalSint64": null,
              "optionalFixed32": null,
              "optionalFixed64": null,
              "optionalSfixed32": null,
              "optionalSfixed64": null,
              "optionalFloat": null,
              "optionalDouble": null,
              "optionalBool": null,
              "optionalString": null,
              "optionalBytes": null,
              "optionalNestedEnum": null,
              "optionalNestedMessage": null,
              "repeatedInt32": null,
              "repeatedInt64": null,
              "repeatedUint32": null,
              "repeatedUint64": null,
              "repeatedSint32": null,
              "repeatedSint64": null,
              "repeatedFixed32": null,
              "repeatedFixed64": null,
              "repeatedSfixed32": null,
              "repeatedSfixed64": null,
              "repeatedFloat": null,
              "repeatedDouble": null,
              "repeatedBool": null,
              "repeatedString": null,
              "repeatedBytes": null,
              "repeatedNestedEnum": null,
              "repeatedNestedMessage": null,
              "mapInt32Int32": null,
              "mapBoolBool": null,
              "mapStringNestedMessage": null
            }
            """,
            ""));
  }

  private static List<Case> wrapperTypesCases() {
    List<Case> cases = new ArrayList<>();
    cases.addAll(wrapperCases());
    cases.addAll(durationCases());
    cases.addAll(timestampCases());
    return List.copyOf(cases);
  }

  private static List<Case> wrapperCases() {
    return List.of(
        valid(
            "OptionalBoolWrapper",
            "{\"optionalBoolWrapper\": false}",
            "optional_bool_wrapper: {value: false}"),
        valid(
            "OptionalInt32Wrapper",
            "{\"optionalInt32Wrapper\": 0}",
            "optional_int32_wrapper: {value: 0}"),
        valid(
            "OptionalUint32Wrapper",
            "{\"optionalUint32Wrapper\": 0}",
            "optional_uint32_wrapper: {value: 0}"),
        valid(
            "OptionalInt64Wrapper",
            "{\"optionalInt64Wrapper\": 0}",
            "optional_int64_wrapper: {value: 0}"),
        valid(
            "OptionalUint64Wrapper",
            "{\"optionalUint64Wrapper\": 0}",
            "optional_uint64_wrapper: {value: 0}"),
        valid(
            "OptionalFloatWrapper",
            "{\"optionalFloatWrapper\": 0}",
            "optional_float_wrapper: {value: 0}"),
        valid(
            "OptionalDoubleWrapper",
            "{\"optionalDoubleWrapper\": 0}",
            "optional_double_wrapper: {value: 0}"),
        valid(
            "OptionalStringWrapper",
            "{\"optionalStringWrapper\": \"\"}",
            "optional_string_wrapper: {value: \"\"}"),
        valid(
            "OptionalBytesWrapper",
            "{\"optionalBytesWrapper\": \"\"}",
            "optional_bytes_wrapper: {value: \"\"}"),
        valid(
            "OptionalWrapperTypesWithNonDefaultValue",
            """
            {
              "optionalBoolWrapper": true,
              "optionalInt32Wrapper": 1,
              "optionalUint32Wrapper": 1,
              "optionalInt64Wrapper": "1",
              "optionalUint64Wrapper": "1",
              "optionalFloatWrapper": 1,
              "optionalDoubleWrapper": 1,
              "optionalStringWrapper": "1",
              "optionalBytesWrapper": "AQI="
            }
            """,
            """
            optional_bool_wrapper: {value: true}
            optional_int32_wrapper: {value: 1}
            optional_uint32_wrapper: {value: 1}
            optional_int64_wrapper: {value: 1}
            optional_uint64_wrapper: {value: 1}
            optional_float_wrapper: {value: 1}
            optional_double_wrapper: {value: 1}
            optional_string_wrapper: {value: "1"}
            optional_bytes_wrapper: {value: "\\x01\\x02"}
            """),
        valid(
            "RepeatedBoolWrapper",
            "{\"repeatedBoolWrapper\": [true, false]}",
            "repeated_bool_wrapper: {value: true} repeated_bool_wrapper: {value: false}"),
        valid(
            "RepeatedInt32Wrapper",
            "{\"repeatedInt32Wrapper\": [0, 1]}",
            "repeated_int32_wrapper: {value: 0} repeated_int32_wrapper: {value: 1}"),
        valid(
            "RepeatedUint32Wrapper",
            "{\"repeatedUint32Wrapper\": [0, 1]}",
            "repeated_uint32_wrapper: {value: 0} repeated_uint32_wrapper: {value: 1}"),
        valid(
            "RepeatedInt64Wrapper",
            "{\"repeatedInt64Wrapper\": [0, 1]}",
            "repeated_int64_wrapper: {value: 0} repeated_int64_wrapper: {value: 1}"),
        valid(
            "RepeatedUint64Wrapper",
            "{\"repeatedUint64Wrapper\": [0, 1]}",
            "repeated_uint64_wrapper: {value: 0} repeated_uint64_wrapper: {value: 1}"),
        valid(
            "RepeatedFloatWrapper",
            "{\"repeatedFloatWrapper\": [0, 1]}",
            "repeated_float_wrapper: {value: 0} repeated_float_wrapper: {value: 1}"),
        valid(
            "RepeatedDoubleWrapper",
            "{\"repeatedDoubleWrapper\": [0, 1]}",
            "repeated_double_wrapper: {value: 0} repeated_double_wrapper: {value: 1}"),
        // Upstream's repeated string wrapper holds "AQI=" as a plain string, not base64 bytes.
        valid(
            "RepeatedStringWrapper",
            "{\"repeatedStringWrapper\": [\"\", \"AQI=\"]}",
            "repeated_string_wrapper: {value: \"\"} repeated_string_wrapper: {value: \"AQI=\"}"),
        valid(
            "RepeatedBytesWrapper",
            "{\"repeatedBytesWrapper\": [\"\", \"AQI=\"]}",
            "repeated_bytes_wrapper: {value: \"\"}"
                + " repeated_bytes_wrapper: {value: \"\\x01\\x02\"}"),
        valid(
            "WrapperTypesWithNullValue",
            """
            {
              "optionalBoolWrapper": null,
              "optionalInt32Wrapper": null,
              "optionalUint32Wrapper": null,
              "optionalInt64Wrapper": null,
              "optionalUint64Wrapper": null,
              "optionalFloatWrapper": null,
              "optionalDoubleWrapper": null,
              "optionalStringWrapper": null,
              "optionalBytesWrapper": null,
              "repeatedBoolWrapper": null,
              "repeatedInt32Wrapper": null,
              "repeatedUint32Wrapper": null,
              "repeatedInt64Wrapper": null,
              "repeatedUint64Wrapper": null,
              "repeatedFloatWrapper": null,
              "repeatedDoubleWrapper": null,
              "repeatedStringWrapper": null,
              "repeatedBytesWrapper": null
            }
            """,
            ""));
  }

  private static List<Case> durationCases() {
    return List.of(
        valid(
            "DurationMinValue",
            "{\"optionalDuration\": \"-315576000000.999999999s\"}",
            "optional_duration: {seconds: -315576000000 nanos: -999999999}"),
        valid(
            "DurationMaxValue",
            "{\"optionalDuration\": \"315576000000.999999999s\"}",
            "optional_duration: {seconds: 315576000000 nanos: 999999999}"),
        valid(
            "DurationRepeatedValue",
            "{\"repeatedDuration\": [\"1.5s\", \"-1.5s\"]}",
            "repeated_duration: {seconds: 1 nanos: 500000000}"
                + " repeated_duration: {seconds: -1 nanos: -500000000}"),
        valid("DurationNull", "{\"optionalDuration\": null}", ""),
        valid(
            "DurationNegativeSeconds",
            "{\"optionalDuration\": \"-5s\"}",
            "optional_duration: {seconds: -5 nanos: 0}"),
        valid(
            "DurationNegativeNanos",
            "{\"optionalDuration\": \"-0.5s\"}",
            "optional_duration: {seconds: 0 nanos: -500000000}"),
        validator(
            "DurationHasZeroFractionalDigit",
            null,
            "{\"optionalDuration\": \"1.000000000s\"}",
            json -> optionalDuration(json).equals("1s")),
        validator(
            "DurationHas3FractionalDigits",
            null,
            "{\"optionalDuration\": \"1.010000000s\"}",
            json -> optionalDuration(json).equals("1.010s")),
        validator(
            "DurationHas6FractionalDigits",
            null,
            "{\"optionalDuration\": \"1.000010000s\"}",
            json -> optionalDuration(json).equals("1.000010s")),
        validator(
            "DurationHas9FractionalDigits",
            null,
            "{\"optionalDuration\": \"1.000000010s\"}",
            json -> optionalDuration(json).equals("1.000000010s")));
  }

  private static List<Case> timestampCases() {
    return List.of(
        valid(
            "TimestampMinValue",
            "{\"optionalTimestamp\": \"0001-01-01T00:00:00Z\"}",
            "optional_timestamp: {seconds: -62135596800}"),
        valid(
            "TimestampMaxValue",
            "{\"optionalTimestamp\": \"9999-12-31T23:59:59.999999999Z\"}",
            "optional_timestamp: {seconds: 253402300799 nanos: 999999999}"),
        valid(
            "TimestampRepeatedValue",
            """
            {
              "repeatedTimestamp": [
                "0001-01-01T00:00:00Z",
                "9999-12-31T23:59:59.999999999Z"
              ]
            }
            """,
            "repeated_timestamp: {seconds: -62135596800}"
                + " repeated_timestamp: {seconds: 253402300799 nanos: 999999999}"),
        valid(
            "TimestampEpochValue",
            "{\"optionalTimestamp\": \"1970-01-01T00:00:00.000Z\"}",
            "optional_timestamp: {seconds: 0}"),
        valid(
            "TimestampNanoAfterEpochlValue",
            "{\"optionalTimestamp\": \"1970-01-01T00:00:00.000000001Z\"}",
            "optional_timestamp: {seconds: 0 nanos: 1}"),
        valid(
            "TimestampNanoBeforeEpochValue",
            "{\"optionalTimestamp\": \"1969-12-31T23:59:59.999999999Z\"}",
            "optional_timestamp: {seconds: -1 nanos: 999999999}"),
        valid(
            "TimestampLittleAfterEpochlValue",
            "{\"optionalTimestamp\": \"1970-01-01T00:00:01.000000001Z\"}",
            "optional_timestamp: {seconds: 1 nanos: 1}"),
        valid(
            "TimestampLittleBeforeEpochValue",
            "{\"optionalTimestamp\": \"1969-12-31T23:59:58.999999999Z\"}",
            "optional_timestamp: {seconds: -2 nanos: 999999999}"),
        valid(
            "TimestampTenAndHalfSecondsAfterEpochValue",
            "{\"optionalTimestamp\": \"1970-01-01T00:00:10.500Z\"}",
            "optional_timestamp: {seconds: 10 nanos: 500000000}"),
        valid(
            "TimestampTenAndHalfSecondsBeforeEpochValue",
            "{\"optionalTimestamp\": \"1969-12-31T23:59:49.500Z\"}",
            "optional_timestamp: {seconds: -11 nanos: 500000000}"),
        valid(
            "TimestampLeap",
            "{\"optionalTimestamp\": \"1993-02-10T00:00:00.000Z\"}",
            "optional_timestamp: {seconds: 729302400}"),
        valid(
            "TimestampWithPositiveOffset",
            "{\"optionalTimestamp\": \"1970-01-01T08:00:01+08:00\"}",
            "optional_timestamp: {seconds: 1}"),
        valid(
            "TimestampWithNegativeOffset",
            "{\"optionalTimestamp\": \"1969-12-31T16:00:01-08:00\"}",
            "optional_timestamp: {seconds: 1}"),
        valid("TimestampNull", "{\"optionalTimestamp\": null}", ""),
        validator(
            "TimestampZeroNormalized",
            null,
            "{\"optionalTimestamp\": \"1969-12-31T16:00:00-08:00\"}",
            json -> optionalTimestamp(json).equals("1970-01-01T00:00:00Z")),
        validator(
            "TimestampHasZeroFractionalDigit",
            null,
            "{\"optionalTimestamp\": \"1970-01-01T00:00:00.000000000Z\"}",
            json -> optionalTimestamp(json).equals("1970-01-01T00:00:00Z")),
        validator(
            "TimestampHas3FractionalDigits",
            null,
            "{\"optionalTimestamp\": \"1970-01-01T00:00:00.010000000Z\"}",
            json -> optionalTimestamp(json).equals("1970-01-01T00:00:00.010Z")),
        validator(
            "TimestampHas6FractionalDigits",
            null,
            "{\"optionalTimestamp\": \"1970-01-01T00:00:00.000010000Z\"}",
            json -> optionalTimestamp(json).equals("1970-01-01T00:00:00.000010Z")),
        validator(
            "TimestampHas9FractionalDigits",
            null,
            "{\"optionalTimestamp\": \"1970-01-01T00:00:00.000000010Z\"}",
            json -> optionalTimestamp(json).equals("1970-01-01T00:00:00.000000010Z")));
  }

  private static List<Case> fieldMaskCases() {
    return List.of(
        valid(
            "FieldMask",
            "{\"optionalFieldMask\": \"foo,barBaz\"}",
            "optional_field_mask: {paths: \"foo\" paths: \"bar_baz\"}"),
        valid("EmptyFieldMask", "{\"optionalFieldMask\": \"\"}", "optional_field_mask: {}"));
  }

  private static List<Case> structCases() {
    return List.of(
        valid(
            "Struct",
            """
            {
              "optionalStruct": {
                "nullValue": null,
                "intValue": 1234,
                "boolValue": true,
                "doubleValue": 1234.5678,
                "stringValue": "Hello world!",
                "listValue": [1234, "5678"],
                "objectValue": {
                  "value": 0
                }
              }
            }
            """,
            """
            optional_struct: {
              fields: {
                key: "nullValue"
                value: {null_value: NULL_VALUE}
              }
              fields: {
                key: "intValue"
                value: {number_value: 1234}
              }
              fields: {
                key: "boolValue"
                value: {bool_value: true}
              }
              fields: {
                key: "doubleValue"
                value: {number_value: 1234.5678}
              }
              fields: {
                key: "stringValue"
                value: {string_value: "Hello world!"}
              }
              fields: {
                key: "listValue"
                value: {
                  list_value: {
                    values: {
                      number_value: 1234
                    }
                    values: {
                      string_value: "5678"
                    }
                  }
                }
              }
              fields: {
                key: "objectValue"
                value: {
                  struct_value: {
                    fields: {
                      key: "value"
                      value: {
                        number_value: 0
                      }
                    }
                  }
                }
              }
            }
            """),
        valid(
            "StructWithEmptyListValue",
            """
            {
              "optionalStruct": {
                "listValue": []
              }
            }
            """,
            """
            optional_struct: {
              fields: {
                key: "listValue"
                value: {
                  list_value: {
                  }
                }
              }
            }
            """),
        valid(
            "StructDeepNesting25",
            "{\"optionalStruct\": {"
                + "\"n\": {".repeat(25)
                + "\"value\": 1"
                + "}".repeat(25)
                + "}}",
            "optional_struct: {\n"
                + """
                fields: {
                  key: "n"
                  value: {
                    struct_value: {
                """
                    .repeat(25)
                + """
                        fields: {
                          key: "value"
                          value: { number_value: 1 }
                        }
                """
                + """
                    }
                  }
                }
                """
                    .repeat(25)
                + "}\n"));
  }

  private static List<Case> valueCases() {
    return List.of(
        valid("ValueAcceptInteger", "{\"optionalValue\": 1}", "optional_value: { number_value: 1}"),
        valid(
            "ValueAcceptFloat", "{\"optionalValue\": 1.5}", "optional_value: { number_value: 1.5}"),
        valid(
            "ValueAcceptBool",
            "{\"optionalValue\": false}",
            "optional_value: { bool_value: false}"),
        valid(
            "ValueAcceptNull",
            "{\"optionalValue\": null}",
            "optional_value: { null_value: NULL_VALUE}"),
        valid(
            "ValueAcceptString",
            "{\"optionalValue\": \"hello\"}",
            "optional_value: { string_value: \"hello\"}"),
        valid(
            "ValueAcceptList",
            "{\"optionalValue\": [0, \"hello\"]}",
            """
            optional_value: {
              list_value: {
                values: {
                  number_value: 0
                }
                values: {
                  string_value: "hello"
                }
              }
            }
            """),
        valid(
            "ValueAcceptObject",
            "{\"optionalValue\": {\"value\": 1}}",
            """
            optional_value: {
              struct_value: {
                fields: {
                  key: "value"
                  value: {
                    number_value: 1
                  }
                }
              }
            }
            """),
        valid(
            "RepeatedValue",
            "{\"repeatedValue\": [[\"a\"]]}",
            """
            repeated_value: [
              {
                list_value: {
                  values: [
                    { string_value: "a"}
                  ]
                }
              }
            ]
            """),
        valid(
            "RepeatedListValue",
            "{\"repeatedListValue\": [[\"a\"]]}",
            """
            repeated_list_value: [
              {
                values: [
                  { string_value: "a"}
                ]
              }
            ]
            """),
        validator(
            "NullValueInOtherOneofOldFormat",
            Blocker.ONEOF_NULL_VALUE,
            "{\"oneofNullValue\": \"NULL_VALUE\"}",
            json -> json.has("oneofNullValue") && json.get("oneofNullValue").isNull()),
        validator(
            "NullValueInOtherOneofNewFormat",
            Blocker.ONEOF_NULL_VALUE,
            "{\"oneofNullValue\": null}",
            json -> json.has("oneofNullValue") && json.get("oneofNullValue").isNull()),
        validator(
            "NullValueInNormalMessage",
            Blocker.NULL_VALUE,
            "{\"optionalNullValue\": null}",
            json -> json.isEmpty()),
        valid(
            "ListValueDeepNesting25",
            "{\"optionalValue\": " + "[".repeat(25) + "1" + "]".repeat(25) + "}",
            "optional_value: {\n"
                + """
                list_value: {
                  values: {
                """
                    .repeat(25)
                + "      number_value: 1\n"
                + """
                  }
                }
                """
                    .repeat(25)
                + "}\n"),
        valid(
            "ValueDeepNesting25",
            "{\"optionalValue\": {"
                + "\"n\": {".repeat(25)
                + "\"value\": 1"
                + "}".repeat(25)
                + "}}",
            "optional_value: {\n  struct_value: {\n"
                + """
                    fields: {
                      key: "n"
                      value: {
                        struct_value: {
                """
                    .repeat(25)
                + """
                            fields: {
                              key: "value"
                              value: { number_value: 1 }
                            }
                """
                + """
                        }
                      }
                    }
                """
                    .repeat(25)
                + "  }\n}\n"));
  }

  private static List<Case> anyCases() {
    String typeUrl = TEST_MESSAGE_TYPE_URL;
    return List.of(
        valid(
            "Any",
            """
            {
              "optionalAny": {
                "@type": "%s",
                "optionalInt32": 12345
              }
            }
            """
                .formatted(typeUrl),
            """
            optional_any: {
              [%s] {
                optional_int32: 12345
              }
            }
            """
                .formatted(typeUrl)),
        valid(
            "AnyNested",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Any",
                "value": {
                  "@type": "%s",
                  "optionalInt32": 12345
                }
              }
            }
            """
                .formatted(typeUrl),
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Any] {
                [%s] {
                  optional_int32: 12345
                }
              }
            }
            """
                .formatted(typeUrl)),
        // The special "@type" tag is not required to appear first.
        valid(
            "AnyUnorderedTypeTag",
            """
            {
              "optionalAny": {
                "optionalInt32": 12345,
                "@type": "%s"
              }
            }
            """
                .formatted(typeUrl),
            """
            optional_any: {
              [%s] {
                optional_int32: 12345
              }
            }
            """
                .formatted(typeUrl)),
        // Well-known types in Any.
        valid(
            "AnyWithInt32ValueWrapper",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Int32Value",
                "value": 12345
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Int32Value] {
                value: 12345
              }
            }
            """),
        valid(
            "AnyWithDuration",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Duration",
                "value": "1.5s"
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Duration] {
                seconds: 1
                nanos: 500000000
              }
            }
            """),
        valid(
            "AnyWithTimestamp",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Timestamp",
                "value": "1970-01-01T00:00:00Z"
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Timestamp] {
                seconds: 0
                nanos: 0
              }
            }
            """),
        valid(
            "AnyWithFieldMask",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.FieldMask",
                "value": "foo,barBaz"
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.FieldMask] {
                paths: ["foo", "bar_baz"]
              }
            }
            """),
        valid(
            "AnyWithStruct",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Struct",
                "value": {
                  "foo": 1
                }
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Struct] {
                fields: {
                  key: "foo"
                  value: {
                    number_value: 1
                  }
                }
              }
            }
            """),
        valid(
            "AnyWithValueForJsonObject",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Value",
                "value": {
                  "foo": 1
                }
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Value] {
                struct_value: {
                  fields: {
                    key: "foo"
                    value: {
                      number_value: 1
                    }
                  }
                }
              }
            }
            """),
        valid(
            "AnyWithValueForInteger",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Value",
                "value": 1
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Value] {
                number_value: 1
              }
            }
            """),
        // When the Any can be parsed as non-WKT form, the type_url could be missing or invalid,
        // since that can still be parsed into the Any schema.
        valid("AnyWithNoType", "{\"optionalAny\": {}}", "optional_any: {}"),
        // "null" where an Any exists should just result in the field being unset.
        valid("AnyNull", "{\"optionalAny\": null}", ""),
        // google.protobuf.Empty packed into an Any must be accepted without the "value" member,
        // and what it round trips to must not carry an empty "value" member either.
        valid(
            "AnyEmpty",
            """
            {
              "optionalAny": {
                "@type": "type.googleapis.com/google.protobuf.Empty"
              }
            }
            """,
            """
            optional_any: {
              [type.googleapis.com/google.protobuf.Empty] {}
            }
            """));
  }
}
