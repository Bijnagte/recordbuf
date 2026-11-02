package bijnagte.recordbuf.fixture.combined;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end byte-and-semantic interop of the generated record codec against the
 * protobuf-java reference, stressing EVERY field family at once in a single
 * message. A proto3 {@code map<K,V>} is a repeated {@code Entry} message (key=1,
 * value=2), so on the wire each map entry is a length-delimited nested message
 * &mdash; the same framing as the repeated/nested-message fields below.
 *
 * <p>The record under test ({@link bijnagte.recordbuf.fixture.combined.rec.Compat} + its sibling {@link bijnagte.recordbuf.fixture.combined.rec.Sub}) lives in
 * this same package, so both are unqualified. The protobuf-java references live
 * in a sibling {@code .ref} package and are referred to by fully-qualified name
 * to avoid a same-simple-name import collision.
 *
 * <p>Field coverage &mdash; one field per family, all present-able in the default
 * case so an all-default {@link bijnagte.recordbuf.fixture.combined.rec.Compat} encodes to zero bytes (matching
 * protobuf-java's "omit when absent"):
 * <ul>
 *   <li>{@code i}  &mdash; plain int32 (i32 / int-family varint), default 0, omitted</li>
 *   <li>{@code fx} &mdash; fixed64 (fixed-width scalar family), default 0, omitted</li>
 *   <li>{@code s}  &mdash; string (length-delimited UTF-8), default "", omitted</li>
 *   <li>{@code b}  &mdash; bytes (length-delimited raw), default empty, omitted</li>
 *   <li>{@code packed} &mdash; packed repeated int32, default empty list, omitted</li>
 *   <li>{@code nested} &mdash; singular bijnagte.recordbuf.fixture.combined.rec.Sub (nested-message family), null, omitted</li>
 *   <li>{@code subs} &mdash; repeated bijnagte.recordbuf.fixture.combined.rec.Sub, default empty list, omitted</li>
 *   <li>{@code m}  &mdash; map&lt;string,int32&gt;, default empty map, omitted</li>
 *   <li>{@code presence} &mdash; optional int32 (presence family), null, omitted</li>
 * </ul>
 *
 * <p>Field-number ordering: the generated codec emits fields in ascending
 * FIELD-NUMBER order (its encode loop sorts by {@code Field.number()}), exactly
 * as protobuf-java does, so ascending declaration order in {@code compat.proto}
 * means ascending tag order on the wire and the two byte streams match. A record
 * is positional, so "constructing in non-declaration order" is not a meaningful
 * operation &mdash; the encode loop iterates the fixed field list and sorts it by
 * field number, never a caller-supplied order. Decode is fully order-independent
 * (it dispatches on the wire tag number), which the interop/round-trip tests
 * below exercise. The separate {@code order} family (order.proto / OrderTest)
 * guards that the codec really sorts by field number even when a .proto declares
 * fields non-ascending.
 */
class CompatTest {

    // Shared field values, built identically for the record and the protobuf-java
    // reference so the two serialize to identical bytes.
    private static final int I = 42;
    private static final long FX = 1_000_000L;
    private static final String S = "héllo-世-🎉";
    // A bytes value with 0x7f and 0x80 plus a sign bit set at 0xFF, proving the
    // raw length-delimited framing is sign-independent on the wire.
    private static final byte[] B = { (byte) 0x7f, (byte) 0x80, -1 };
    private static final List<Integer> PACKED = List.of(1, -2, 0, 100);
    private static final bijnagte.recordbuf.fixture.combined.rec.Sub NESTED = new bijnagte.recordbuf.fixture.combined.rec.Sub(7, "seven");
    private static final List<bijnagte.recordbuf.fixture.combined.rec.Sub> SUBS = List.of(new bijnagte.recordbuf.fixture.combined.rec.Sub(1, "one"), new bijnagte.recordbuf.fixture.combined.rec.Sub(2, "two"));
    // A non-empty map so the length-delimited entry framing is exercised; the
    // empty case below proves an empty map is omitted from the wire.
    private static final Map<String, Integer> M = Map.of("x", 1, "z", -2);
    private static final Integer PRESENCE = 7;

    /** A fully-populated sample: a non-default, non-empty value for every field. */
    private static bijnagte.recordbuf.fixture.combined.rec.Compat sample() {
        return new bijnagte.recordbuf.fixture.combined.rec.Compat(I, FX, S, B, PACKED, NESTED, SUBS, M, PRESENCE);
    }

    /** The proto3 default sample: every field at its default, all omitted. */
    private static bijnagte.recordbuf.fixture.combined.rec.Compat empty() {
        return new bijnagte.recordbuf.fixture.combined.rec.Compat(0, 0L, "", new byte[0], List.of(), null, List.of(), Map.of(), null);
    }

    /**
     * Our byte output must match protobuf-java's {@code toByteArray()} across every
     * family at once. The populated case exercises one field per family (including
     * a non-empty sub-list and a populated map); the default case proves every
     * default/absent field is omitted, so all-empty encodes to zero bytes.
     */
    @Test
    void byteEqual() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        byte[] ours = bijnagte.recordbuf.fixture.combined.rec.Compat.Codec.encode(sample());
        assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");

        byte[] theirsDefault = Compat.newBuilder().build().toByteArray();
        byte[] oursDefault = bijnagte.recordbuf.fixture.combined.rec.Compat.Codec.encode(empty());
        assertArrayEquals(theirsDefault, oursDefault, "default sample must be empty on the wire");
        assertArrayEquals(new byte[0], oursDefault, "all-default fields must encode to zero bytes");
    }

    /** Every present field is on the wire; decode from protobuf-java bytes yields the populated sample. */
    @Test
    void interopDecodesProtobuf() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        bijnagte.recordbuf.fixture.combined.rec.Compat parsed = bijnagte.recordbuf.fixture.combined.rec.Compat.Codec.decode(theirs);
        assertEquals(sample(), parsed);
        // Spot-check distinct families decoded by tag number, in any wire order.
        assertEquals("seven", parsed.nested().label());
        assertEquals(-2, parsed.m().get("z"));
        assertEquals(Integer.valueOf(7), parsed.presence());
    }

    /** Decoding an all-empty encode yields the proto3 default: absent optionals null, empties empty. */
    @Test
    void roundTrip() throws Exception {
        bijnagte.recordbuf.fixture.combined.rec.Compat v = sample();
        assertEquals(v, bijnagte.recordbuf.fixture.combined.rec.Compat.Codec.decode(bijnagte.recordbuf.fixture.combined.rec.Compat.Codec.encode(v)));

        bijnagte.recordbuf.fixture.combined.rec.Compat d = bijnagte.recordbuf.fixture.combined.rec.Compat.Codec.decode(bijnagte.recordbuf.fixture.combined.rec.Compat.Codec.encode(empty()));
        assertEquals(List.of(), d.subs(), "repeated bijnagte.recordbuf.fixture.combined.rec.Sub must decode to empty list when absent");
        assertEquals(Map.of(), d.m(), "empty map must decode to empty map when absent");
        assertNull(d.nested(), "absent singular bijnagte.recordbuf.fixture.combined.rec.Sub must decode to null");
        assertNull(d.presence(), "absent optional int32 must decode to null, not 0");
    }

    private static Compat.Builder newBuilder() {
        return Compat.newBuilder()
                .setI(I)
                .setFx(FX)
                .setS(S)
                .setB(com.google.protobuf.ByteString.copyFrom(B))
                .addAllPacked(PACKED)
                .setNested(
                        Sub.newBuilder()
                                .setId(NESTED.id())
                                .setLabel(NESTED.label())
                                .build())
                .addSubs(
                        Sub.newBuilder()
                                .setId(1).setLabel("one").build())
                .addSubs(
                        Sub.newBuilder()
                                .setId(2).setLabel("two").build())
                .putAllM(M)
                .setPresence(PRESENCE);
    }
}
