package bijnagte.recordbuf.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One unknown field captured from the wire during a decode under a schema that
 * predates it: the field number, its protobuf {@link WireType}, and the
 * <em>exact post-tag value bytes</em> — a varint field contributes its varint
 * bytes, a fixed field its 8 raw bytes, a length-delimited field its length
 * prefix plus its content — so the field re-emits identical bytes on encode and
 * is inspectable without the newer schema.
 *
 * <p>Capture is pure and type-agnostic: it reads the raw value bytes from the
 * decode stream and stores them. Inspection is <em>wire-honest</em> — the
 * helpers below read the value by its on-wire {@link WireType}, never by a
 * guessed proto type. {@link #asString()} / {@link #asBytes()} read a
 * length-delimited value as UTF-8 text / raw bytes; {@link #asLong()} /
 * {@link #asInt()} read a varint or fixed value. None of these assert the field
 * "was a string" or "was a nested message" — they are honest read-shapes, so a
 * mis-shaped call throws {@link IllegalStateException} rather than silently
 * coercing a wrong value.
 */
public record UnknownField(int fieldNumber, WireType wireType, byte[] value) {

    public UnknownField {
        // Defensively copy value so the captured bytes are owned and never alias a
        // stream buffer that may be recycled.
        value = Arrays.copyOf(value, value.length);
    }

    /**
     * Read the value as the raw bytes of a length-delimited field.
     *
     * @throws IllegalStateException if the captured value is not length-delimited
     */
    public byte[] asBytes() {
        if (wireType != WireType.LENGTH_DELIMITED) {
            throw new IllegalStateException("value is " + wireType + ", cannot read as bytes");
        }
        return Arrays.copyOf(value, value.length);
    }

    /**
     * Read a length-delimited value as a UTF-8 string. A wire-honest view: a
     * length-delimited byte value surfaced as text, with no claim that the proto
     * type was a string.
     *
     * @throws IllegalStateException if the captured value is not length-delimited
     */
    public String asString() {
        return new String(asBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Read a varint or fixed value as a 32-bit integer. Does not assert the proto
     * type; it is a wire-honest read-shape.
     *
     * @throws IllegalStateException if the value is not a varint or fixed value
     */
    public int asInt() {
        return (int) asLong();
    }

    /**
     * Read a varint ({@code I32}) or fixed ({@code I64}) value as a 64-bit
     * integer. Does not assert the proto type; it is a wire-honest read-shape.
     *
     * @throws IllegalStateException if the value is not a varint or fixed value
     */
    public long asLong() {
        return switch (wireType) {
            case I32 -> varintToLong(value);
            case I64 -> littleEndian64(value);
            default -> throw new IllegalStateException(
                "value is " + wireType + "; cannot read as a varint/fixed integer");
        };
    }

    /** Decode a base-128 varint byte sequence to a long. */
    private static long varintToLong(byte[] b) {
        long result = 0;
        int shift = 0;
        for (int i = 0; i < b.length; i++) {
            int byteVal = b[i] & 0xFF;
            result |= (long) (byteVal & 0x7F) << shift;
            if ((byteVal & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IllegalStateException("varint did not terminate within " + b.length + " bytes");
    }

    /** Decode 8 little-endian bytes to a long (the fixed64 / I64 wire shape). */
    private static long littleEndian64(byte[] b) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (b[i] & 0xFF) << (8 * i);
        }
        return v;
    }

    /**
     * Content-aware value equality: two unknown fields compare equal when their
     * number, wire type, and value *content* match — not the identity of the
     * {@code value} array, which is fresh on each decode. The record's implicit
     * {@code equals} would compare the {@code byte[]} by reference, so it is
     * overridden here.
     */
    @Override
    public boolean equals(java.lang.Object o) {
        if (this == o) return true;
        if (!(o instanceof UnknownField that)) return false;
        return fieldNumber == that.fieldNumber
                && wireType == that.wireType
                && java.util.Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldNumber, wireType, Arrays.hashCode(value));
    }
}
