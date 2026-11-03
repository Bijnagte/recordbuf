package bijnagte.recordbuf.fixture.order;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Regression guard proving the generated encode loop sorts by field NUMBER, not
 * by declaration order. See {@code order.proto}: its field numbers are declared
 * non-ascending (c=3, a=1, b=2). protobuf-java always emits in ascending
 * field-number order, so byte-equality vs the protobuf-java reference can only
 * hold if the codec sorts by {@code field.number()}.
 *
 * <p>The record {@code Sample} lives in this package; the protobuf-java
 * reference is {@code fixture.order.ref.Sample}, referred to by fully-qualified
 * name to avoid the same-simple-name import collision.
 */
class OrderTest {

    private bijnagte.recordbuf.fixture.order.rec.Sample sample() {
        // Construct in declaration order (c, a, b); the codec must re-order by
        // field number on the wire so it matches protobuf-java (a, b, c on the wire).
        return bijnagte.recordbuf.fixture.order.rec.Sample.builder()
                .c(3)
                .a(1)
                .b(2)
                .build();
    }

    private byte[] refBytes() {
        return Sample.newBuilder()
                .setC(3)
                .setA(1)
                .setB(2)
                .build()
                .toByteArray();
    }

    /** Byte-for-byte: record encode == protobuf-java toByteArray. */
    @Test
    void byteEqual() throws Exception {
        assertArrayEquals(refBytes(), bijnagte.recordbuf.fixture.order.rec.Sample.Codec.encode(sample()),
            "non-ascending field numbers must still byte-match protobuf-java");
    }

    /** Semantics: decode(protobuf-java bytes) == record. */
    @Test
    void interopDecodesProtobuf() throws Exception {
        assertEquals(sample(), bijnagte.recordbuf.fixture.order.rec.Sample.Codec.decode(refBytes()));
    }

    /** Invariant: decode(encode(x)) == x. */
    @Test
    void roundTrip() throws Exception {
        bijnagte.recordbuf.fixture.order.rec.Sample x = sample();
        assertEquals(x, bijnagte.recordbuf.fixture.order.rec.Sample.Codec.decode(bijnagte.recordbuf.fixture.order.rec.Sample.Codec.encode(x)));
    }
}
