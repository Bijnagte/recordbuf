package bijnagte.recordbuf.fixture.bytes;

import com.google.protobuf.ByteString;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-and-semantic interop of the generated record codec against the
 * protobuf-java reference for the {@code bytes} field family.
 *
 * <p>The record under test lives in this same package (so {@code Sample} is
 * unqualified). The protobuf-java reference lives in a sibling {@code .ref}
 * package and is referred to by fully-qualified name to avoid a
 * same-simple-name import collision.
 *
 * <p>The values exercise the length-delimited raw-byte encoding: an empty array
 * (proto3 default, omitted on the wire) and a non-empty array with mixed-sign
 * bytes that forces real length-delimited framing and raw byte content (bytes
 * 0x7f and 0x80 prove the value is framed as raw bytes, not a signed
 * primitive).
 */
class BytesTest {

    // Sample field order: a bytes, b bytes.
    private static final byte[] A = {};                // empty, proto3 default -> omitted
    private static final byte[] B = {1, -1, 0, 127, -128, 65}; // mixed-sign raw bytes, non-default

    /**
     * Our byte output for the non-default fields must match protobuf-java's
     * {@code toByteArray()}: field tag numbers included, length-delimited raw
     * bytes, empty-array default omitted.
     */
    @Test
    void byteEqual() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        byte[] ours = Sample.Codec.encode(sample());
        assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");
    }

    /** The non-default bytes are present on the wire; decode from protobuf-java bytes. */
    @Test
    void interopDecodesProtobuf() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        Sample parsed = Sample.Codec.decode(theirs);
        assertEquals(sample(), parsed);
    }

    @Test
    void roundTrip() throws Exception {
        Sample v = sample();
        assertEquals(v, Sample.Codec.decode(Sample.Codec.encode(v)));
    }

    private static Sample sample() {
        return new Sample(A, B);
    }

    private static bijnagte.recordbuf.fixture.bytes.ref.Sample.Builder newBuilder() {
        return bijnagte.recordbuf.fixture.bytes.ref.Sample.newBuilder()
                .setA(ByteString.copyFrom(A))
                .setB(ByteString.copyFrom(B));
    }
}
