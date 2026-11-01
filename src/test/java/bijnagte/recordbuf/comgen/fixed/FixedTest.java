package bijnagte.recordbuf.comgen.fixed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-and-semantic interop of the generated record codec against the
 * protobuf-java reference for the {@code fixed/int/float} scalar family.
 *
 * <p>The record under test lives in this same package (so {@code Sample} is
 * unqualified). The protobuf-java reference lives in a sibling {@code .ref}
 * package and is referred to by fully-qualified name to avoid a
 * same-simple-name import collision.
 *
 * <p>The values are each meaningfully non-default: signed fields are negative,
 * the fixed-width family carries a non-zero fixed value, and the IEEE
 * floating-point fields are non-integer. This exercises the varint vs
 * fixed-width vs float/double encodings end to end.
 */
class FixedTest {

    // Sample field order: a uint32, b int32, c int64, d uint64, e fixed32,
    // f fixed64, g sfixed32, h sfixed64, i double, j float, k sint32, l sint64.
    private static final int   A = 2_000_000_000; // uint32 -> int; non-default, in range
    private static final int   B = -42;            // int32, negative
    private static final long  C = -1_234_567_890L; // int64, negative
    private static final long  D = 12_345L;        // uint64 -> long; non-default
    private static final int   E = 7;              // fixed32
    private static final long  F = 7L;             // fixed64
    private static final int   G = -7;             // sfixed32
    private static final long  H = -7L;            // sfixed64
    private static final double I = 1.25;          // double, exact
    private static final float  J = 1.5f;          // float, exact
    private static final int   K = -5;             // sint32 (zigzag)
    private static final long  L = -9L;            // sint64 (zigzag)

    /**
     * Our byte output for non-default fields must match protobuf-java's
     * {@code toByteArray()}: field tag numbers included, varint vs fixed-width
     * encodings, zero default omitted.
     */
    @Test
    void byteEqual() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        byte[] ours = Sample.Codec.encode(sample());
        assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");
    }

    /** The non-default scalars are present on the wire; decode from protobuf-java bytes. */
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
        return new Sample(A, B, C, D, E, F, G, H, I, J, K, L);
    }

    private static bijnagte.recordbuf.comgen.fixed.ref.Sample.Builder newBuilder() {
        return bijnagte.recordbuf.comgen.fixed.ref.Sample.newBuilder()
                .setA(A)
                .setB(B)
                .setC(C)
                .setD(D)
                .setE(E)
                .setF(F)
                .setG(G)
                .setH(H)
                .setI(I)
                .setJ(J)
                .setK(K)
                .setL(L);
    }
}
