package bijnagte.recordbuf.fixture.i32;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-and-semantic interop of the generated record codec against the
 * protobuf-java reference for the {@code int32} family.
 *
 * <p>The record under test lives in this same package (so {@code Sample} is
 * unqualified). The protobuf-java reference lives in a sibling {@code .ref}
 * package and is referred to by fully-qualified name to avoid a
 * same-simple-name import collision.
 */
class IntTest {

    /**
     * Our byte output for a non-default field must match protobuf-java's
     * {@code toByteArray()}: field tag numbers included, varints, zero default
     * omitted.
     */
    @Test
    void byteEqual() throws Exception {
        byte[] theirs =
                bijnagte.recordbuf.fixture.i32.ref.Sample.newBuilder()
                        .setA(0x8000_0000)
                        .build()
                        .toByteArray();
        byte[] ours = Sample.Codec.encode(new Sample(0x8000_0000));
        assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");
    }

    /** A non-default int32 is present (varint) on the wire. */
    @Test
    void interopDecodesProtobuf() throws Exception {
        byte[] theirs =
                bijnagte.recordbuf.fixture.i32.ref.Sample.newBuilder()
                        .setA(0x8000_0000)
                        .build()
                        .toByteArray();
        Sample parsed = Sample.Codec.decode(theirs);
        assertEquals(new Sample(0x8000_0000), parsed);
    }

    @Test
    void roundTrip() throws Exception {
        Sample v = new Sample(0x8000_0000);
        assertEquals(v, Sample.Codec.decode(Sample.Codec.encode(v)));
    }
}
