package bijnagte.recordbuf.fixture.combined;

import bijnagte.recordbuf.fixture.combined.rec.Compat;
import bijnagte.recordbuf.fixture.combined.rec.Sub;
import bijnagte.recordbuf.fixture.golden.GoldenCase;
import bijnagte.recordbuf.fixture.jackson.JacksonGoldenCases;
import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden cases of family {@code combined}, migrated from {@code CompatTest}, {@code
 * CompatInteropTest} and {@code UnknownFieldRoundTripTest}: {@link Compat}, one message with a
 * field of every family at once (plain int32, fixed64, string, bytes, packed repeated int32, a
 * singular and a repeated nested {@link Sub}, a {@code map<string,int32>} and an {@code optional
 * int32}), and {@link Sub} on its own.
 *
 * <p>The value cases are {@code populated} (a non-default, non-empty value for every field, the
 * sample those classes shared), {@code all-default} (every field at its default, which encodes to
 * zero bytes and converts back to a null optional and a null nested message), and {@code sub-alpha}
 * (the sibling {@code Sub} whose own {@code Interop} the interop test exercised).
 *
 * <p>The unknown-field cases are records <em>decoded</em> from bytes that carry a field the schema
 * does not declare, appended after the known fields, so the record holds it in its carrier: {@code
 * unknown-varint} (field 99), {@code unknown-length-delimited} (field 98), and two unknown fields
 * in wire order, descending ({@code unknown-multiple-wire-order}: 99 then 98, the order that class
 * asserted) and ascending ({@code unknown-multiple-ascending}: 98 then 99). {@code
 * no-unknown-fields} is the same message with nothing unknown, whose carrier is the shared empty
 * one. The unknown fields are wire-level only: the carrier is {@code @JsonIgnore}d and the {@code
 * Interop} converter does not copy it, so the Jackson and proto3 JSON goldens hold the known fields
 * alone and deserialize to the record with the carrier cleared. The Avro converter does carry it,
 * as the schema's {@code unknownFields} entry records, so the {@code -populated} cases (with {@code
 * nested} and {@code presence} set, which Avro needs) hold the unknown fields in both Avro formats.
 * The other unknown-field cases have {@code nested} absent, which Avro cannot write (gap {@code
 * avro-absent-singular-message}), so they declare both Avro formats not applicable, as {@code
 * all-default} and {@code no-unknown-fields} do.
 *
 * <p>protobuf-java keeps unknown fields in a {@code UnknownFieldSet} that serializes in ascending
 * field number, while the record re-emits them in wire order, so a descending pair cannot be
 * byte-identical with protobuf-java: {@code unknown-multiple-wire-order} declares protobuf binary
 * not applicable, and the wire-order check stays a structural test in {@code
 * UnknownFieldRoundTripTest}.
 */
public final class CompatCases extends JacksonGoldenCases {

  /** The golden family directory of every case here. */
  public static final String FAMILY = "combined";

  /** The field number of the unknown varint appended by the unknown-field cases. */
  public static final int UNKNOWN_VARINT_FIELD = 99;

  /** The value of the unknown varint appended by the unknown-field cases. */
  public static final long UNKNOWN_VARINT_VALUE = 123_456L;

  /** The field number of the unknown length-delimited field appended by the unknown cases. */
  public static final int UNKNOWN_LENGTH_FIELD = 98;

  /** The value of the unknown length-delimited field appended by the unknown-field cases. */
  public static final String UNKNOWN_LENGTH_VALUE = "future-data";

  private static final String UNKNOWN_FIELDS_NOT_SORTED =
      "protobuf-java re-serializes unknown fields in ascending field number, while the record"
          + " re-emits them in wire order (99 before 98 here), so the bytes cannot be identical";

  @Override
  public List<GoldenCase> cases() {
    Compat knownOnly = Compat.builder().i(42).build();
    Compat knownFxOnly = Compat.builder().fx(7).build();
    return List.of(
        build(jacksonLossless(FAMILY, "populated", populated())),
        build(jacksonLossless(FAMILY, "all-default", Compat.builder().build())),
        build(jacksonLossless(FAMILY, "sub-alpha", Sub.builder().id(123).label("alpha").build())),
        build(jacksonLossless(FAMILY, "no-unknown-fields", decode(knownBytes()))),
        build(unknownCase("unknown-varint", decode(withUnknownVarint(knownBytes())), knownOnly)),
        build(
            unknownCase(
                "unknown-length-delimited",
                decode(withUnknownLengthDelimited(knownFxBytes(), UNKNOWN_LENGTH_VALUE)),
                knownFxOnly)),
        build(
            unknownCase("unknown-multiple-wire-order", multipleWireOrder(), knownOnly)
                .notApplicable(protobufBinary, UNKNOWN_FIELDS_NOT_SORTED)),
        build(
            unknownCase(
                "unknown-multiple-ascending",
                decode(withUnknownVarint(withUnknownLengthDelimited(knownBytes(), "second"), 1L)),
                knownOnly)));
  }

  /** A non-default, non-empty value for every field: the sample the combined tests shared. */
  public static Compat populated() {
    return Compat.builder()
        .i(42)
        .fx(1_000_000L)
        .s("héllo-世-🎉")
        // 0x7f and 0x80, plus 0xFF with the sign bit set: the raw framing is sign-independent.
        .b(Bytes.of(new byte[] {(byte) 0x7f, (byte) 0x80, -1}))
        .packed(List.of(1, -2, 0, 100))
        .nested(Sub.builder().id(7).label("seven").build())
        .subs(
            List.of(
                Sub.builder().id(1).label("one").build(), Sub.builder().id(2).label("two").build()))
        .m(entries())
        .presence(7)
        .build();
  }

  /** The record codec's bytes of {@code Compat(i = 42)}: the known part of the unknown cases. */
  public static byte[] knownBytes() {
    return encode(Compat.builder().i(42).build());
  }

  /** The record codec's bytes of {@code Compat(fx = 7)}. */
  public static byte[] knownFxBytes() {
    return encode(Compat.builder().fx(7).build());
  }

  /** {@code prefix} followed by the unknown varint field {@link #UNKNOWN_VARINT_FIELD}. */
  public static byte[] withUnknownVarint(byte[] prefix) {
    return withUnknownVarint(prefix, UNKNOWN_VARINT_VALUE);
  }

  /** {@code prefix} followed by an unknown varint field with the given value. */
  public static byte[] withUnknownVarint(byte[] prefix, long value) {
    return append(prefix, out -> out.writeUInt64(UNKNOWN_VARINT_FIELD, value));
  }

  /** {@code prefix} followed by an unknown length-delimited field holding {@code value}. */
  public static byte[] withUnknownLengthDelimited(byte[] prefix, String value) {
    return append(prefix, out -> out.writeString(UNKNOWN_LENGTH_FIELD, value));
  }

  /** The bytes of {@code unknown-multiple-wire-order}: the 99 varint, then the 98 string. */
  public static byte[] multipleWireOrderBytes() {
    return withUnknownLengthDelimited(withUnknownVarint(knownBytes(), 1L), "second");
  }

  /** Decodes record codec bytes, which may carry unknown fields. */
  public static Compat decode(byte[] bytes) {
    try {
      return Compat.fromBytes(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Compat multipleWireOrder() {
    return decode(multipleWireOrderBytes());
  }

  /**
   * A case whose record holds unknown fields: the Jackson and proto3 JSON goldens deserialize to
   * {@code withoutUnknowns}, the record with the carrier cleared.
   */
  private GoldenCase.Builder unknownCase(String name, Compat record, Compat withoutUnknowns) {
    return jacksonExpectedAfterTrip(FAMILY, name, record, withoutUnknowns)
        .expectedAfterTrip(proto3Json, withoutUnknowns);
  }

  private static byte[] encode(Compat record) {
    try {
      return record.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private interface FieldWriter {
    void write(CodedOutputStream out) throws IOException;
  }

  private static byte[] append(byte[] prefix, FieldWriter field) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      bytes.write(prefix);
      CodedOutputStream out = CodedOutputStream.newInstance(bytes);
      field.write(out);
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static Map<String, Integer> entries() {
    Map<String, Integer> map = new LinkedHashMap<>();
    map.put("x", 1);
    map.put("z", -2);
    return map;
  }
}
