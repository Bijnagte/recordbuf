package bijnagte.recordbuf.fixture.combined;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.fixture.combined.rec.Compat;
import bijnagte.recordbuf.runtime.UnknownField;
import bijnagte.recordbuf.runtime.UnknownFields;
import bijnagte.recordbuf.runtime.WireType;
import org.junit.jupiter.api.Test;

/**
 * The never-silent unknown-field feature, inspected: a field a decode saw on the wire that its
 * schema does not model is <em>captured</em> (raw bytes, wire-honest) onto the record's always-on
 * carrier and <em>re-emitted</em> byte-for-byte on encode, so a record read through an older schema
 * survives a round-trip exactly. The "unknown" bytes are crafted to look like a newer schema's
 * field, appended after a record-encoded message that models the other fields (see {@link
 * CompatCases}, whose {@code unknown-*} golden cases carry the same records through every format,
 * including the byte identity against protobuf-java).
 *
 * <p>What stays structural here is what no golden can say: how the carrier exposes a captured field
 * (its field number, wire type and raw value) and that two unknown fields keep their wire order,
 * which protobuf-java does not (its unknown fields serialize in ascending field number), so the
 * descending pair has no protobuf binary golden.
 */
class UnknownFieldRoundTripTest {

  /**
   * A varint unknown field is captured as a {@link WireType#I32} occurrence, inspectable by its raw
   * value, and re-emitted so the record's re-encode is byte-identical to the original
   * (unknown-bearing) bytes.
   */
  @Test
  void varintUnknownFieldIsCapturedAndReemitted() throws Exception {
    byte[] withUnknown = CompatCases.withUnknownVarint(CompatCases.knownBytes());

    Compat parsed = CompatCases.decode(withUnknown);

    UnknownFields unknownFields = parsed.unknownFields();
    assertEquals(
        1, unknownFields.fields().size(), "the unknown varint field must be captured, not dropped");
    UnknownField captured = unknownFields.fields().get(0);
    assertEquals(
        CompatCases.UNKNOWN_VARINT_FIELD, captured.fieldNumber(), "field number captured verbatim");
    assertEquals(
        WireType.I32,
        captured.wireType(),
        "a varint occurrence is captured as I32 (its on-wire shape)");
    // A wire-honest read of the raw captured value -- not a claim that the schema
    // typed this field as an int; just the on-wire varint, read honestly.
    assertEquals(
        CompatCases.UNKNOWN_VARINT_VALUE,
        captured.asLong(),
        "captured varint is readable by its value");
    assertArrayEquals(
        withUnknown,
        parsed.toByteArray(),
        "re-emitting the captured unknown field must be byte-for-byte identical");
  }

  /**
   * A length-delimited unknown field is captured and its raw bytes surfaced as a string via the
   * wire-honest {@link UnknownField#asString()}, and re-emitted byte-identically.
   */
  @Test
  void lengthDelimitedUnknownFieldIsCapturedInspectedAndReemitted() throws Exception {
    byte[] withUnknown =
        CompatCases.withUnknownLengthDelimited(
            CompatCases.knownFxBytes(), CompatCases.UNKNOWN_LENGTH_VALUE);

    Compat parsed = CompatCases.decode(withUnknown);

    UnknownField captured = parsed.unknownFields().fields().get(0);
    assertEquals(CompatCases.UNKNOWN_LENGTH_FIELD, captured.fieldNumber());
    assertEquals(WireType.LENGTH_DELIMITED, captured.wireType());
    assertEquals(
        CompatCases.UNKNOWN_LENGTH_VALUE,
        captured.asString(),
        "a length-delimited value's raw bytes are surfaced as UTF-8 text, wire-honestly");
    assertArrayEquals(
        withUnknown,
        parsed.toByteArray(),
        "the captured length-delimited unknown field re-emits byte-for-byte");
  }

  /**
   * A record that decodes no unknown fields carries the shared empty carrier and re-encodes to the
   * identical known bytes -- the common path the feature costs nothing on.
   */
  @Test
  void decodeWithoutUnknownFieldsCarriesEmptyCarrier() throws Exception {
    byte[] known = CompatCases.knownBytes();

    Compat parsed = CompatCases.decode(known);

    assertTrue(parsed.unknownFields().isEmpty(), "no unknown fields on the wire => empty carrier");
    assertArrayEquals(
        known, parsed.toByteArray(), "no unknown fields => re-encode is byte-identical");
  }

  /**
   * Two unknown fields appended in wire order capture in that order and re-emit in the same order.
   */
  @Test
  void multipleUnknownFieldsPreserveWireOrder() throws Exception {
    byte[] withUnknown = CompatCases.multipleWireOrderBytes();

    Compat parsed = CompatCases.decode(withUnknown);

    var fields = parsed.unknownFields().fields();
    assertEquals(2, fields.size());
    assertEquals(
        CompatCases.UNKNOWN_VARINT_FIELD, fields.get(0).fieldNumber(), "first-seen captures first");
    assertEquals(
        CompatCases.UNKNOWN_LENGTH_FIELD,
        fields.get(1).fieldNumber(),
        "second-seen captures second");
    assertArrayEquals(
        withUnknown, parsed.toByteArray(), "ordered unknown fields re-emit in wire order");
  }
}
