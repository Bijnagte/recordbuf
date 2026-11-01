package bijnagte.recordbuf.fixture.packed;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-and-semantic interop of the generated record codec against the
 * protobuf-java reference for the {@code packed-repeated-numeric} field family.
 *
 * <p>The record under test lives in this same package (so {@code Sample} is
 * unqualified). The protobuf-java reference lives in a sibling {@code .ref}
 * package and is referred to by fully-qualified name to avoid a same-simple-name
 * import collision.
 *
 * <p>Every field is a {@code repeated} numeric scalar, which proto3 encodes with
 * the packed (length-delimited) wire form: one {@code tag + varint length}
 * group followed by a tight sequence of the values, rather than one tag per
 * element. The populated case carries a non-empty list for each family with
 * distinct values — signed families (int32/int64/sint32/sint64) are negative so
 * their varint/zigzag packing is exercised, and fixed/float/double carry the
 * fixed-width and IEEE forms. The default case carries an empty list for every
 * field, proving each packed group is omitted from the wire when empty.
 */
class PackedTest {

    // Sample field order: a int32, b int64, c fixed32, d fixed64, e float,
    // f double, g sint32, h sint64, i uint32, j uint64. Each list is
    // distinct-value so packed-width + zigzag framing is exercised.
    private static final List<Integer> A = List.of(1, -2, Integer.MAX_VALUE, 0);
    private static final List<Long>    B = List.of(1L, -2L, 1_000_000L);
    private static final List<Integer> C = List.of(7, -7, 0);
    private static final List<Long>    D = List.of(7L, -7L, 1_000_000L);
    private static final List<Float>   E = List.of(1.5f, -2.5f, 0f);
    private static final List<Double>  F = List.of(1.25, -3.75, 0.0);
    private static final List<Integer> G = List.of(1, -5, 100);
    private static final List<Long>    H = List.of(1L, -7L, 1_000L);
    private static final List<Integer> I = List.of(1, 42, 1_000);
    private static final List<Long>    J = List.of(1L, 12_345L, 9_000_000_000L);

    /** A fully-populated sample: a non-empty packed list for every field. */
    private static Sample sample() {
        return new Sample(A, B, C, D, E, F, G, H, I, J);
    }

    /** The proto3 default sample: an empty list for every field, all omitted. */
    private static Sample empty() {
        return new Sample(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Our byte output must match protobuf-java's {@code toByteArray()} for the
     * packed (length-delimited) wire form. The populated case exercises every
     * packed numeric family; the default case proves an empty packed group is
     * omitted, so all-empty encodes to zero bytes.
     */
    @Test
    void byteEqual() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        byte[] ours = Sample.Codec.encode(sample());
        assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");

        byte[] theirsDefault = bijnagte.recordbuf.fixture.packed.ref.Sample.newBuilder().build().toByteArray();
        byte[] oursDefault = Sample.Codec.encode(empty());
        assertArrayEquals(theirsDefault, oursDefault, "default sample must be empty on the wire");
        assertArrayEquals(new byte[0], oursDefault, "all-empty packed lists must encode to zero bytes");
    }

    /** The non-empty packed lists are present on the wire; decode from protobuf-java bytes. */
    @Test
    void interopDecodesProtobuf() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        Sample parsed = Sample.Codec.decode(theirs);
        assertEquals(sample(), parsed);
    }

    /** Decoding an all-empty encode yields empty lists for every field. */
    @Test
    void roundTrip() throws Exception {
        Sample v = sample();
        assertEquals(v, Sample.Codec.decode(Sample.Codec.encode(v)));

        Sample d = Sample.Codec.decode(Sample.Codec.encode(empty()));
        assertEquals(List.of(), d.a());
        assertEquals(List.of(), d.j());
    }

    private static bijnagte.recordbuf.fixture.packed.ref.Sample.Builder newBuilder() {
        return bijnagte.recordbuf.fixture.packed.ref.Sample.newBuilder()
                .addAllA(A)
                .addAllB(B)
                .addAllC(C)
                .addAllD(D)
                .addAllE(E)
                .addAllF(F)
                .addAllG(G)
                .addAllH(H)
                .addAllI(I)
                .addAllJ(J);
    }
}
