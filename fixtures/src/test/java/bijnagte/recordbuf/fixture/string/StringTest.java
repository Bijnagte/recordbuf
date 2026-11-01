package bijnagte.recordbuf.fixture.string;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-and-semantic interop of the generated record codec against the
 * protobuf-java reference for the {@code string} field family.
 *
 * <p>The record under test lives in this same package (so {@code Sample} is
 * unqualified). The protobuf-java reference lives in a sibling {@code .ref}
 * package and is referred to by fully-qualified name to avoid a
 * same-simple-name import collision.
 *
 * <p>The values exercise the length-delimited UTF-8 string encoding: an empty
 * string (proto3 default, omitted on the wire), a plain ASCII string, and a
 * multibyte Unicode string (accented + CJK + emoji) that forces real UTF-8
 * length-delimited handling.
 */
class StringTest {

    // Sample field order: a string, b string, c string.
    private static final String A = "";               // empty string, proto3 default -> omitted
    private static final String B = "hello";          // plain ASCII, non-default
    private static final String C = "héllo-世界-🎉"; // multibyte UTF-8, non-default

    /**
     * Our byte output for the non-default fields must match protobuf-java's
     * {@code toByteArray()}: field tag numbers included, length-delimited
     * UTF-8, empty-string default omitted.
     */
    @Test
    void byteEqual() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        byte[] ours = Sample.Codec.encode(sample());
        assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");
    }

    /** The non-default strings are present on the wire; decode from protobuf-java bytes. */
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
        return new Sample(A, B, C);
    }

    private static bijnagte.recordbuf.fixture.string.ref.Sample.Builder newBuilder() {
        return bijnagte.recordbuf.fixture.string.ref.Sample.newBuilder()
                .setA(A)
                .setB(B)
                .setC(C);
    }
}
