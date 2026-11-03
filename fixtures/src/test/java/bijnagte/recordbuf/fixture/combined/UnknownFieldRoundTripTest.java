package bijnagte.recordbuf.fixture.combined;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bijnagte.recordbuf.runtime.UnknownField;
import bijnagte.recordbuf.runtime.UnknownFields;
import bijnagte.recordbuf.runtime.WireType;

import bijnagte.recordbuf.fixture.combined.rec.Compat;

/**
 * Exercises the never-silent unknown-field feature end to end end: a field a
 * decode saw on the wire that its schema does not model is <em>captured</em> (raw
 * bytes, wire-honest) onto the record's always-on carrier and <em>re-emitted</em>
 * byte-for-byte on encode, so a record read through an older schema survives a
 * round-trip exactly. The "unknown" bytes are crafted to look like a newer
 * schema's field (a field number the record's schema does not declare), appended
 * after a record-encoded message that models the other fields. The record-under-
 * test is {@link bijnagte.recordbuf.fixture.combined.rec.Compat}.
 */
class UnknownFieldRoundTripTest {

    private static final int UNKNOWN_VARINT_FIELD = 99;
    private static final long UNKNOWN_VARINT_VALUE = 123456L;
    private static final int UNKNOWN_LENGTH_FIELD = 98;
    private static final String UNKNOWN_LENGTH_VALUE = "future-data";

    private static byte[] appendVarintField(byte[] prefix, int fieldNumber, long value) throws Exception {
        var baos = new ByteArrayOutputStream();
        var out = com.google.protobuf.CodedOutputStream.newInstance(baos);
        out.writeTag(fieldNumber, 0); // wire type 0 == varint
        out.writeRawVarint64(value);
        out.flush(); // flush the view's buffer into baos
        return concat(prefix, baos.toByteArray());
    }

    private static byte[] appendLengthDelimitedStringField(byte[] prefix, int fieldNumber, String value) throws Exception {
        var baos = new ByteArrayOutputStream();
        var out = com.google.protobuf.CodedOutputStream.newInstance(baos);
        out.writeTag(fieldNumber, 2); // wire type 2 == length-delimited
        out.writeByteArrayNoTag(com.google.protobuf.ByteString.copyFromUtf8(value).toByteArray());
        out.flush();
        return concat(prefix, baos.toByteArray());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * A varint unknown field is captured as a {@link WireType#I32} occurrence,
     * inspectable by its raw value, and re-emitted so the record's re-encode is
     * byte-identical to the original (unknown-bearing) bytes.
     */
    @Test
    void varintUnknownFieldIsCapturedAndReemitted() throws Exception {
        byte[] known = compat().toByteArray();
        byte[] withUnknown = appendVarintField(known, UNKNOWN_VARINT_FIELD, UNKNOWN_VARINT_VALUE);

        var parsed = Compat.Codec.decode(withUnknown);

        UnknownFields uf = parsed.unknownFields();
        assertEquals(1, uf.fields().size(), "the unknown varint field must be captured, not dropped");
        UnknownField captured = uf.fields().get(0);
        assertEquals(UNKNOWN_VARINT_FIELD, captured.fieldNumber(), "field number captured verbatim");
        assertEquals(WireType.I32, captured.wireType(), "a varint occurrence is captured as I32 (its on-wire shape)");
        // A wire-honest read of the raw captured value -- not a claim that the schema
        // typed this field as an int; just the on-wire varint, read honestly.
        assertEquals(UNKNOWN_VARINT_VALUE, captured.asLong(), "captured varint is readable by its value");

        byte[] reencoded = parsed.toByteArray();
        assertArrayEquals(withUnknown, reencoded,
                "re-emitting the captured unknown field must be byte-for-byte identical");
    }

    /**
     * A length-delimited unknown field is captured and its raw bytes surfaced as a
     * string via the wire-honest {@link UnknownField#asString()}, and re-emitted
     * byte-identically.
     */
    @Test
    void lengthDelimitedUnknownFieldIsCapturedInspectedAndReemitted() throws Exception {
        byte[] known = compatWith(b -> b.fx(7)).toByteArray();
        byte[] withUnknown =
                appendLengthDelimitedStringField(known, UNKNOWN_LENGTH_FIELD, UNKNOWN_LENGTH_VALUE);

        var parsed = Compat.Codec.decode(withUnknown);

        UnknownField captured = parsed.unknownFields().fields().get(0);
        assertEquals(UNKNOWN_LENGTH_FIELD, captured.fieldNumber());
        assertEquals(WireType.LENGTH_DELIMITED, captured.wireType());
        assertEquals(UNKNOWN_LENGTH_VALUE, captured.asString(),
                "a length-delimited value's raw bytes are surfaced as UTF-8 text, wire-honestly");
        assertArrayEquals(withUnknown, parsed.toByteArray(),
                "the captured length-delimited unknown field re-emits byte-for-byte");
    }

    /**
     * A record that decodes no unknown fields carries the shared empty carrier and
     * re-encodes to the identical known bytes -- the common path the feature costs
     * nothing on.
     */
    @Test
    void decodeWithoutUnknownFieldsCarriesEmptyCarrier() throws Exception {
        var parsed = Compat.Codec.decode(compat().toByteArray());
        assertTrue(parsed.unknownFields().isEmpty(), "no unknown fields on the wire => empty carrier");
        assertArrayEquals(compat().toByteArray(), parsed.toByteArray(),
                "no unknown fields => re-encode is byte-identical to the original");
    }

    /**
     * Two unknown fields appended in wire order capture in that order and re-emit in
     * the same order, mirroring protobuf-java's {@code UnknownFieldSet}.
     */
    @Test
    void multipleUnknownFieldsPreserveWireOrder() throws Exception {
        byte[] known = compat().toByteArray();
        byte[] withUnknown =
                appendLengthDelimitedStringField(
                        appendVarintField(known, UNKNOWN_VARINT_FIELD, 1L),
                        UNKNOWN_LENGTH_FIELD, "second");

        var parsed = Compat.Codec.decode(withUnknown);
        var fields = parsed.unknownFields().fields();
        assertEquals(2, fields.size());
        assertEquals(UNKNOWN_VARINT_FIELD, fields.get(0).fieldNumber(), "first-seen captures first");
        assertEquals(UNKNOWN_LENGTH_FIELD, fields.get(1).fieldNumber(), "second-seen captures second");
        assertArrayEquals(withUnknown, parsed.toByteArray(), "ordered unknown fields re-emit in wire order");
    }

    // Helpers ------------------------------------------------------------------

    private static Compat compat() {
        return Compat.builder().i(42).build();
    }

    private static Compat compatWith(java.util.function.Consumer<Compat.Builder> mutate) {
        var b = Compat.builder();
        mutate.accept(b);
        return b.build();
    }
}
