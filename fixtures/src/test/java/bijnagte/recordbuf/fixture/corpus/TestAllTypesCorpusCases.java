package bijnagte.recordbuf.fixture.corpus;

import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.golden.GoldenCaseSource;
import bijnagte.recordbuf.fixture.golden.GoldenFormat;
import bijnagte.recordbuf.fixture.golden.format.AvroBinaryFormat;
import bijnagte.recordbuf.fixture.golden.format.AvroJsonFormat;
import bijnagte.recordbuf.fixture.golden.format.JacksonFormat;
import bijnagte.recordbuf.fixture.golden.format.Proto3JsonFormat;
import bijnagte.recordbuf.fixture.golden.format.ProtobufBinaryFormat;
import bijnagte.recordbuf.json.DynamicWkt;
import bijnagte.recordbuf.runtime.Bytes;
import bijnagte.recordbuf.runtime.Empty;
import bijnagte.recordbuf.runtime.FieldMask;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf_test_messages.proto3.rec.ForeignEnum;
import com.google.protobuf_test_messages.proto3.rec.ForeignMessage;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3.AliasedEnum;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3.NestedEnum;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3.NestedMessage;
import com.google.protobuf_test_messages.proto3.rec.TestAllTypesProto3.OneofField;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The golden cases of family {@code corpus} over the vendored {@code test_messages_proto3}: the
 * populated and the default {@code TestAllTypesProto3}, and the two small messages that have a
 * proto3 JSON path.
 *
 * <p>Which formats serve which message follows the gap ledger and the JSON printer. The {@code
 * TestAllTypesProto3} cases run in proto3 JSON except {@link #POPULATED}, whose {@code
 * repeated_any} holds an {@code Any} of a type with no descriptor, which {@code JsonFormat} cannot
 * print (the case with that element replaced is {@code all-types-proto3-json}), and {@code
 * all-types-wire-gaps}, whose default-valued repeated WKT elements its golden cannot hold (gap
 * {@code repeated-wkt-default-element}). Of the {@code TestAllTypesProto3} cases, only {@code
 * all-types-avro} runs in Avro: it sets every non-cycle singular message field at both levels, and
 * each other case leaves one unset, which Avro cannot write (gap {@code
 * avro-absent-singular-message}). {@code ForeignMessage} and {@code NestedMessage} run in proto3
 * JSON and Avro too.
 *
 * <p>Maps are built as {@link LinkedHashMap}s with several entries. The record's codec writes a map
 * in the iteration order of the {@code Map} the record holds, so the insertion order of these maps
 * is the entry order on the wire, and the {@code pb.hex} golden does not depend on any {@code
 * HashMap} internals. (A map decoded from the wire is a {@code HashMap}; record equality ignores
 * map order.) The Jackson and proto3 JSON formats sort map keys themselves.
 */
public final class TestAllTypesCorpusCases implements GoldenCaseSource {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "corpus";

  /** The case name of the populated {@code TestAllTypesProto3}. */
  public static final String POPULATED = "all-types-populated";

  /** The case name of the populated record whose {@code repeated_any} holds only a Struct. */
  private static final String PROTO3_JSON_POPULATED = "all-types-proto3-json";

  /** The case name of the record that sets every non-cycle singular message field. */
  private static final String AVRO = "all-types-avro";

  /** The case name of the {@value #AVRO} record shape with every edge-named field set. */
  private static final String EDGE_NAMES = "all-types-edge-names";

  /** The case name of the {@value #AVRO} record shape with {@code oneof_nested_message} set. */
  private static final String ONEOF_MESSAGE = "all-types-oneof-message";

  /** The case name of the {@value #AVRO} record shape with {@code oneof_bytes} set. */
  private static final String ONEOF_BYTES = "all-types-oneof-bytes";

  private static final String WIRE_GAPS =
      "the record sets fields whose protobuf binary encoding differs from protobuf-java's, or that"
          + " recordbuf cannot read back from protobuf-java's bytes (gaps"
          + " uint32-negative-carrier-wire, repeated-enum-unpacked-only, packed-false-ignored and"
          + " repeated-wkt-default-element)";

  private static final String PROTO3_JSON_WIRE_GAPS =
      "the proto3 JSON golden is protobuf-java's JsonFormat of the record's own protobuf binary"
          + " bytes, which drop the default-valued repeated Duration, Timestamp, FieldMask and Empty"
          + " elements (gap repeated-wkt-default-element), so the golden would not hold the record's"
          + " elements";

  private static final String OPAQUE_ANY =
      "protobuf-java's JsonFormat cannot print an Any whose type URL has no descriptor"
          + " (repeated_any holds type.googleapis.com/com.example.Opaque), a Google-owned JSON limit"
          + " (R15), not a recordbuf gap; case all-types-proto3-json is the same record without it";

  private static final String AVRO_ABSENT_SINGULAR_MESSAGE =
      "the record leaves a non-cycle singular message field unset, which the Avro schema declares"
          + " non-nullable, so Avro cannot write it (gap avro-absent-singular-message); case"
          + " all-types-avro sets every such field";

  private static final JsonMapper NODES = new JsonMapper();

  private static final String TEXT =
      "h\u00e9llo \u4e16\u754c \uD83D\uDE00 \"quoted\" back\\slash\ttab\nnewline \u0000nul </script>";

  private static final Bytes NON_EMPTY_BYTES =
      Bytes.of(new byte[] {0, 1, (byte) 0x7f, (byte) 0x80, (byte) 0xff});

  private final ProtobufBinaryFormat protobufBinary = new ProtobufBinaryFormat();
  private final Proto3JsonFormat proto3Json =
      new Proto3JsonFormat(
          JsonFormat.TypeRegistry.newBuilder()
              .add(com.google.protobuf_test_messages.proto3.TestAllTypesProto3.getDescriptor())
              .build());
  private final JacksonFormat jackson = new JacksonFormat();
  private final AvroJsonFormat avroJson = new AvroJsonFormat();
  private final AvroBinaryFormat avroBinary = new AvroBinaryFormat();

  @Override
  public List<GoldenFormat> formats() {
    return List.of(protobufBinary, proto3Json, jackson, avroJson, avroBinary);
  }

  @Override
  public List<GoldenCase> cases() {
    return List.of(
        populatedCase(),
        allTypes(PROTO3_JSON_POPULATED, populatedWithStructAny()),
        allTypes("all-types-default", TestAllTypesProto3.builder().build()),
        avroCase(),
        edgeNamesCase(),
        oneofCase(ONEOF_MESSAGE, oneofMessageRecord()),
        oneofCase(ONEOF_BYTES, oneofBytesRecord()),
        wireGapFields(),
        interopMessage("foreign-message", ForeignMessage.builder().c(Integer.MIN_VALUE).build()),
        interopMessage("nested-message", NestedMessage.builder().a(Integer.MAX_VALUE).build()));
  }

  private GoldenCase populatedCase() {
    return GoldenCase.builder(FAMILY, POPULATED, populated())
        .lossless(protobufBinary)
        .notApplicable(proto3Json, OPAQUE_ANY)
        .lossless(jackson)
        .notApplicable(avroJson, AVRO_ABSENT_SINGULAR_MESSAGE)
        .notApplicable(avroBinary, AVRO_ABSENT_SINGULAR_MESSAGE)
        .build(formats());
  }

  private GoldenCase allTypes(String name, TestAllTypesProto3 record) {
    return GoldenCase.builder(FAMILY, name, record)
        .lossless(protobufBinary)
        .lossless(proto3Json)
        .lossless(jackson)
        .notApplicable(avroJson, AVRO_ABSENT_SINGULAR_MESSAGE)
        .notApplicable(avroBinary, AVRO_ABSENT_SINGULAR_MESSAGE)
        .build(formats());
  }

  /** The case {@value #AVRO}: lossless in all five formats, see {@link #avroRecord()}. */
  private GoldenCase avroCase() {
    return GoldenCase.builder(FAMILY, AVRO, avroRecord())
        .lossless(protobufBinary)
        .lossless(proto3Json)
        .lossless(jackson)
        .lossless(avroJson)
        .lossless(avroBinary)
        .build(formats());
  }

  /**
   * The case {@value #EDGE_NAMES}: the {@value #AVRO} record shape with all eighteen edge-named
   * fields set, so every format carries each of them, see {@link #edgeNamesRecord()}.
   */
  private GoldenCase edgeNamesCase() {
    return GoldenCase.builder(FAMILY, EDGE_NAMES, edgeNamesRecord())
        .lossless(protobufBinary)
        .lossless(proto3Json)
        .lossless(jackson)
        .lossless(avroJson)
        .lossless(avroBinary)
        .build(formats());
  }

  /**
   * A case of the {@value #AVRO} record shape with one oneof member set: lossless in all five
   * formats, like {@value #AVRO}, so every format carries the member.
   */
  private GoldenCase oneofCase(String name, TestAllTypesProto3 record) {
    return GoldenCase.builder(FAMILY, name, record)
        .lossless(protobufBinary)
        .lossless(proto3Json)
        .lossless(jackson)
        .lossless(avroJson)
        .lossless(avroBinary)
        .build(formats());
  }

  /**
   * The record of case {@value #ONEOF_MESSAGE}: the {@link #avroRecord()} with {@code
   * oneof_nested_message} set to a non-default message.
   */
  static TestAllTypesProto3 oneofMessageRecord() {
    return avroRecord().toBuilder()
        .oneofField(new OneofField.OneofNestedMessageValue(NestedMessage.builder().a(1234).build()))
        .build();
  }

  /**
   * The record of case {@value #ONEOF_BYTES}: the {@link #avroRecord()} with {@code oneof_bytes}
   * set to the non-empty bytes.
   */
  static TestAllTypesProto3 oneofBytesRecord() {
    return avroRecord().toBuilder()
        .oneofField(new OneofField.OneofBytesValue(NON_EMPTY_BYTES))
        .build();
  }

  /**
   * The record of case {@value #EDGE_NAMES}: the {@link #avroRecord()} with {@code fieldname1}
   * through {@code Field_name18__} set to distinct non-default values, including the eight whose
   * camel-cased Java name starts upper-case in protoc's rule (for example {@code _field_name3}, the
   * component {@code fieldName3}).
   */
  static TestAllTypesProto3 edgeNamesRecord() {
    return avroRecord().toBuilder()
        .fieldname1(101)
        .fieldName2(102)
        .fieldName3(103)
        .fieldName4(104)
        .field0name5(105)
        .field0Name6(106)
        .fieldName7(107)
        .fieldName8(108)
        .fieldName9(109)
        .fieldName10(110)
        .fIELDNAME11(111)
        .fIELDName12(112)
        .fieldName13(113)
        .fieldName14(114)
        .fieldName15(115)
        .fieldName16(116)
        .fieldName17(117)
        .fieldName18(118)
        .build();
  }

  /**
   * The populated record with its {@code repeated_any} holding only the Struct {@code Any}, the
   * element the proto3 JSON printer can resolve.
   */
  private static TestAllTypesProto3 populatedWithStructAny() {
    return populated().toBuilder().repeatedAny(List.of(structAny())).build();
  }

  /**
   * The record of case {@value #AVRO}: the Struct-{@code Any} populated record with {@code
   * recursive_message} replaced by one level of the same shape, whose own {@code recursive_message}
   * is absent. Each level sets {@code optional_empty} to the default Empty, which Avro needs for
   * its non-nullable message field, and whole-microsecond timestamps, the precision Avro keeps.
   */
  static TestAllTypesProto3 avroRecord() {
    TestAllTypesProto3 inner =
        avroLevel(populatedWithStructAny().toBuilder().recursiveMessage(null).build());
    return avroLevel(populatedWithStructAny().toBuilder().recursiveMessage(inner).build());
  }

  private static TestAllTypesProto3 avroLevel(TestAllTypesProto3 level) {
    return level.toBuilder()
        .optionalEmpty(Empty.INSTANCE)
        .optionalTimestamp(Instant.parse("2023-11-14T22:13:20.123456Z"))
        .repeatedTimestamp(
            List.of(
                Instant.parse("0001-01-01T00:00:00Z"),
                Instant.parse("9999-12-31T23:59:59.999999Z"),
                Instant.ofEpochSecond(-1, 1_000)))
        .build();
  }

  /**
   * The fields {@link #populated()} leaves out because the protobuf binary codec does not match
   * protobuf-java for them, set to the values that expose it. They serialize and round-trip in
   * Jackson JSON; protobuf binary is declared not applicable until the gaps close, at which point
   * these move into the populated case.
   */
  private GoldenCase wireGapFields() {
    return GoldenCase.builder(FAMILY, "all-types-wire-gaps", wireGaps())
        .notApplicable(protobufBinary, WIRE_GAPS)
        .notApplicable(proto3Json, PROTO3_JSON_WIRE_GAPS)
        .lossless(jackson)
        .notApplicable(avroJson, AVRO_ABSENT_SINGULAR_MESSAGE)
        .notApplicable(avroBinary, AVRO_ABSENT_SINGULAR_MESSAGE)
        .build(formats());
  }

  private GoldenCase interopMessage(String name, Object record) {
    return GoldenCase.builder(FAMILY, name, record)
        .lossless(protobufBinary)
        .lossless(proto3Json)
        .lossless(jackson)
        .lossless(avroJson)
        .lossless(avroBinary)
        .build(formats());
  }

  /** The populated record of case {@value #POPULATED}: every active field, boundary values. */
  public static TestAllTypesProto3 populated() {
    return TestAllTypesProto3.builder()
        // singular scalars; uint32/uint64 are carried as their signed bit patterns, so the uint64
        // maximum is -1. The uint32 fields stay at or below Integer.MAX_VALUE: a larger uint32 is
        // written as a ten-byte varint (gap uint32-negative-carrier-wire)
        .optionalInt32(Integer.MIN_VALUE)
        .optionalInt64(Long.MIN_VALUE)
        .optionalUint32(Integer.MAX_VALUE)
        .optionalUint64(-1L)
        .optionalSint32(Integer.MIN_VALUE)
        .optionalSint64(Long.MAX_VALUE)
        .optionalFixed32(-1)
        .optionalFixed64(Long.MIN_VALUE)
        .optionalSfixed32(Integer.MAX_VALUE)
        .optionalSfixed64(Long.MIN_VALUE)
        .optionalFloat(Float.MAX_VALUE)
        .optionalDouble(Double.MIN_VALUE)
        .optionalBool(true)
        .optionalString(TEXT)
        .optionalBytes(NON_EMPTY_BYTES)
        .optionalNestedMessage(NestedMessage.builder().a(-1).build())
        .optionalForeignMessage(ForeignMessage.builder().c(Integer.MAX_VALUE).build())
        .optionalNestedEnum(NestedEnum.NEG)
        .optionalForeignEnum(ForeignEnum.FOREIGN_BAZ)
        // BAZ, ALIAS_BAZ and MOO share number 2; a decoded 2 is the last declared constant
        .optionalAliasedEnum(AliasedEnum.BAZ)
        .optionalStringPiece("string piece \u00e9")
        .optionalCord("cord \u4e16\u754c")
        .recursiveMessage(recursive())
        // repeated
        .repeatedInt32(List.of(Integer.MIN_VALUE, 0, 1, Integer.MAX_VALUE))
        .repeatedInt64(List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE))
        .repeatedUint32(List.of(0, 1, Integer.MAX_VALUE))
        .repeatedUint64(List.of(0L, 1L, -1L))
        .repeatedSint32(List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE))
        .repeatedSint64(List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE))
        .repeatedFixed32(List.of(0, -1, 7))
        .repeatedFixed64(List.of(0L, -1L, 7L))
        .repeatedSfixed32(List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE))
        .repeatedSfixed64(List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE))
        .repeatedFloat(List.of(-Float.MAX_VALUE, Float.MIN_VALUE, 0.5f))
        .repeatedDouble(List.of(-Double.MAX_VALUE, Double.MIN_VALUE, 0.25))
        .repeatedBool(List.of(true, false, true))
        .repeatedString(List.of("", "a", TEXT))
        .repeatedBytes(List.of(Bytes.empty(), NON_EMPTY_BYTES, Bytes.of(new byte[] {0})))
        .repeatedNestedMessage(
            List.of(NestedMessage.builder().a(1).build(), NestedMessage.builder().a(-2).build()))
        .repeatedForeignMessage(
            List.of(ForeignMessage.builder().c(3).build(), ForeignMessage.builder().c(-4).build()))
        .repeatedStringPiece(List.of("piece-1", "piece-\u00e9"))
        .repeatedCord(List.of("cord-1", "cord-\u00e9"))
        // packed, and the unpacked fields: the enum lists and the unpacked scalars are not here
        // (gaps repeated-enum-unpacked-only and packed-false-ignored), see wireGaps()
        // packed
        .packedInt32(List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE))
        .packedInt64(List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE))
        .packedUint32(List.of(0, 1, Integer.MAX_VALUE))
        .packedUint64(List.of(0L, 1L, -1L))
        .packedSint32(List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE))
        .packedSint64(List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE))
        .packedFixed32(List.of(0, -1, 7))
        .packedFixed64(List.of(0L, -1L, 7L))
        .packedSfixed32(List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE))
        .packedSfixed64(List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE))
        .packedFloat(List.of(-Float.MAX_VALUE, Float.MIN_VALUE, 0.5f))
        .packedDouble(List.of(-Double.MAX_VALUE, Double.MIN_VALUE, 0.25))
        .packedBool(List.of(true, false, true))
        .unpackedNestedEnum(List.of(NestedEnum.NEG, NestedEnum.FOO, NestedEnum.BAR))
        // maps: several entries each, in insertion order (see the class comment)
        .mapInt32Int32(ordered(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0, 7, -7))
        .mapInt64Int64(ordered(Long.MIN_VALUE, Long.MAX_VALUE, 0L, 0L, 7L, -7L))
        .mapUint32Uint32(ordered(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 1, 5, 6))
        .mapUint64Uint64(ordered(-1L, -1L, 0L, 1L, 5L, 6L))
        .mapSint32Sint32(ordered(Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 1, 0, 0))
        .mapSint64Sint64(ordered(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L, 0L, 0L))
        .mapFixed32Fixed32(ordered(-1, -2, 0, 0, 7, 8))
        .mapFixed64Fixed64(ordered(-1L, -2L, 0L, 0L, 7L, 8L))
        .mapSfixed32Sfixed32(ordered(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0, -3, 3))
        .mapSfixed64Sfixed64(ordered(Long.MIN_VALUE, Long.MAX_VALUE, 0L, 0L, -3L, 3L))
        .mapInt32Float(ordered(1, Float.MAX_VALUE, -1, Float.MIN_VALUE, 0, 0.5f))
        .mapInt32Double(ordered(1, -Double.MAX_VALUE, -1, Double.MIN_VALUE, 0, 0.25))
        .mapBoolBool(ordered(true, false, false, true))
        .mapStringString(
            ordered(
                "", "empty key", "k\"q\\", "v\n\t", "\u03ba\u03bb\u03b5\u03b9\u03b4\u03af", TEXT))
        // well-known wrappers: boundary values, and the proto3 default of each kind where the
        // wrapper's presence is the point
        .optionalBoolWrapper(false)
        .optionalInt32Wrapper(Integer.MIN_VALUE)
        .optionalInt64Wrapper(Long.MAX_VALUE)
        .optionalUint32Wrapper(Integer.MAX_VALUE)
        .optionalUint64Wrapper(-1L)
        .optionalFloatWrapper(Float.MIN_VALUE)
        .optionalDoubleWrapper(-Double.MAX_VALUE)
        .optionalStringWrapper("")
        .optionalBytesWrapper(NON_EMPTY_BYTES)
        .repeatedBoolWrapper(List.of(true, false))
        .repeatedInt32Wrapper(List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE))
        .repeatedInt64Wrapper(List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE))
        .repeatedUint32Wrapper(List.of(Integer.MAX_VALUE, 0, 1))
        .repeatedUint64Wrapper(List.of(-1L, 0L, 1L))
        .repeatedFloatWrapper(List.of(Float.MAX_VALUE, 0f, -0.5f))
        .repeatedDoubleWrapper(List.of(Double.MAX_VALUE, 0d, -0.25))
        .repeatedStringWrapper(List.of("", "wrapped", TEXT))
        .repeatedBytesWrapper(List.of(Bytes.empty(), NON_EMPTY_BYTES))
        // well-known structured types; the default-valued singular and repeated elements are left
        // out: a default Duration, Timestamp, FieldMask or Empty is the unset value, not a
        // present-but-empty message (see gap repeated-wkt-default-element)
        .optionalDuration(Duration.ofSeconds(-3, -500_000_000))
        .optionalTimestamp(Instant.parse("2023-11-14T22:13:20.123456789Z"))
        .optionalFieldMask(FieldMask.of("foo_bar.baz", "qux"))
        .optionalStruct(struct())
        .optionalAny(DynamicWkt.any(Any.pack(Struct.newBuilder().build())))
        .optionalValue(node("{\"k\":[1.5,\"x\",null,true],\"o\":{\"z\":-0.25}}"))
        .repeatedDuration(
            List.of(
                Duration.ofSeconds(315_576_000_000L, 999_999_999),
                Duration.ofSeconds(-315_576_000_000L, -999_999_999),
                Duration.ofNanos(1)))
        .repeatedTimestamp(
            List.of(
                Instant.parse("0001-01-01T00:00:00Z"),
                Instant.parse("9999-12-31T23:59:59.999999999Z"),
                Instant.ofEpochSecond(-1, 1)))
        .repeatedFieldmask(List.of(FieldMask.of("a"), FieldMask.of("b.c", "d")))
        .repeatedStruct(List.of(struct(), (ObjectNode) node("{}")))
        .repeatedAny(List.of(structAny(), opaqueAny()))
        .repeatedValue(
            List.of(node("\"s\""), node("1.5"), node("true"), node("[1.5,\"x\"]"), node("{}")))
        .repeatedListValue(List.of((ArrayNode) node("[1.5,\"x\",null]"), (ArrayNode) node("[]")))
        // edge-named fields (the other edge names are set by case all-types-edge-names)
        .fieldname1(Integer.MIN_VALUE)
        .fieldName2(-2)
        .fieldName4(4)
        .field0name5(5)
        .field0Name6(Integer.MAX_VALUE)
        .fieldName7(7)
        .fieldName9(9)
        .fieldName15(15)
        .fieldName16(16)
        .fieldName17(17)
        .build();
  }

  /** The record of case {@code all-types-wire-gaps}: only the fields the populated case omits. */
  static TestAllTypesProto3 wireGaps() {
    return TestAllTypesProto3.builder()
        // gap uint32-negative-carrier-wire: uint32 above Integer.MAX_VALUE
        .optionalUint32(-1)
        .repeatedUint32(List.of(0, 1, -1))
        .packedUint32(List.of(0, 1, -1))
        .unpackedUint32(List.of(0, 1, -1))
        .mapUint32Uint32(ordered(-1, -1, 0, 1, 5, 6))
        .optionalUint32Wrapper(-1)
        .repeatedUint32Wrapper(List.of(-1, 0, 1))
        // gap repeated-enum-unpacked-only: repeated enums, default-packed and explicitly packed
        .repeatedNestedEnum(List.of(NestedEnum.FOO, NestedEnum.NEG, NestedEnum.BAZ))
        .repeatedForeignEnum(List.of(ForeignEnum.FOREIGN_BAR, ForeignEnum.FOREIGN_FOO))
        .packedNestedEnum(List.of(NestedEnum.NEG, NestedEnum.FOO, NestedEnum.BAR))
        // gap packed-false-ignored: the unpacked scalar lists (the uint32 one is above)
        .unpackedInt32(List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE))
        .unpackedInt64(List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE))
        .unpackedUint64(List.of(0L, 1L, -1L))
        .unpackedSint32(List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE))
        .unpackedSint64(List.of(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE))
        .unpackedFixed32(List.of(0, -1, 7))
        .unpackedFixed64(List.of(0L, -1L, 7L))
        .unpackedSfixed32(List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE))
        .unpackedSfixed64(List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE))
        .unpackedFloat(List.of(-Float.MAX_VALUE, Float.MIN_VALUE, 0.5f))
        .unpackedDouble(List.of(-Double.MAX_VALUE, Double.MIN_VALUE, 0.25))
        .unpackedBool(List.of(true, false, true))
        // gap repeated-wkt-default-element: default-valued elements
        .repeatedDuration(List.of(Duration.ZERO, Duration.ofSeconds(1)))
        .repeatedTimestamp(List.of(Instant.EPOCH, Instant.ofEpochSecond(1)))
        .repeatedFieldmask(List.of(FieldMask.empty(), FieldMask.of("a")))
        .repeatedEmpty(List.of(Empty.INSTANCE, Empty.INSTANCE))
        .build();
  }

  /** The record of {@code recursive_message}: one level deep, a few fields of every kind. */
  private static TestAllTypesProto3 recursive() {
    return TestAllTypesProto3.builder()
        .optionalInt32(-1)
        .optionalString("inner")
        .optionalBytes(Bytes.empty())
        .optionalNestedEnum(NestedEnum.BAR)
        .repeatedInt32(List.of(1, 2))
        .packedSint64(List.of(-1L, 1L))
        .mapStringString(ordered("inner-key", "inner-value"))
        .optionalStringWrapper("inner-wrapper")
        .build();
  }

  private static ObjectNode struct() {
    return (ObjectNode)
        node("{\"s\":\"v\",\"n\":1.5,\"b\":true,\"l\":[1.5,\"x\"],\"o\":{\"z\":null}}");
  }

  /** An {@code Any} packing a {@code Struct}: its value is the Struct's node. */
  private static JsonNode structAny() {
    Struct packed =
        Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build()).build();
    return DynamicWkt.any(Any.pack(packed));
  }

  /** An {@code Any} of an unknown type: its value is the payload's base64. */
  private static JsonNode opaqueAny() {
    return DynamicWkt.any(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/com.example.Opaque")
            .setValue(ByteString.copyFrom(new byte[] {8, (byte) 150, 1}))
            .build());
  }

  private static JsonNode node(String json) {
    return NODES.readTree(json);
  }

  /** Builds a one-entry map. */
  private static <K, V> Map<K, V> ordered(K k1, V v1) {
    Map<K, V> map = new LinkedHashMap<>();
    map.put(k1, v1);
    return map;
  }

  /** Builds a map that iterates in the given order. */
  private static <K, V> Map<K, V> ordered(K k1, V v1, K k2, V v2) {
    Map<K, V> map = ordered(k1, v1);
    map.put(k2, v2);
    return map;
  }

  /** Builds a map that iterates in the given order. */
  private static <K, V> Map<K, V> ordered(K k1, V v1, K k2, V v2, K k3, V v3) {
    Map<K, V> map = ordered(k1, v1, k2, v2);
    map.put(k3, v3);
    return map;
  }
}
