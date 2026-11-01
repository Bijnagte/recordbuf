package bijnagte.recordbuf.fixture.message;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Byte-and-semantic interop of the generated record codec against the
 * protobuf-java reference for the {@code nested-message} field family.
 *
 * <p>The record under test lives in this same package (so {@code Sample} and its
 * sibling top-level type {@code Item} are unqualified). The protobuf-java
 * references live in a sibling {@code .ref} package and are referred to by
 * fully-qualified name to avoid a same-simple-name import collision.
 *
 * <p>Two message shapes are exercised so the length-delimited tag plus nested
 * {@code writeMessage}/{@code readMessage} encode/decode is covered end to end:
 * a singular {@code single} Item (present iff set; omitted from the wire when
 * null) and a repeated {@code items} message list. The populated case carries a
 * non-empty {@code single} and a non-empty {@code items}; the default case has a
 * null {@code single} and an empty {@code items}, both omitted from the wire.
 */
class MessageTest {

    // Sibling Item field order: id int32, name string.
    private static final int ID = 7;
    private static final String NAME = "seven";

    private static Item aItem(int id, String name) {
        return new Item(id, name);
    }

    /** A fully-populated sample: a non-null single Item and a non-empty items list. */
    private static Sample sample() {
        return new Sample(
                aItem(ID, NAME),
                List.of(aItem(1, "one"), aItem(2, "two"))
        );
    }

    /** The proto3 default sample: a null single Item and an empty items list. */
    private static Sample empty() {
        return new Sample(null, List.of());
    }

    /**
     * Our byte output must match protobuf-java's {@code toByteArray()}: field tag
     * numbers included, length-delimited nested messages. The populated case
     * exercises a singular + repeated nested message; the default case proves a
     * null singular message and an empty repeated message are both omitted.
     */
    @Test
    void byteEqual() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        byte[] ours = Sample.Codec.encode(sample());
        assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");

        byte[] theirsDefault = bijnagte.recordbuf.fixture.message.ref.Sample.newBuilder().build().toByteArray();
        byte[] oursDefault = Sample.Codec.encode(empty());
        assertArrayEquals(theirsDefault, oursDefault, "default sample must be empty on the wire");
        assertArrayEquals(new byte[0], oursDefault, "a null single + empty items must encode to zero bytes");
    }

    /** The non-default nested messages are present on the wire; decode from protobuf-java bytes. */
    @Test
    void interopDecodesProtobuf() throws Exception {
        byte[] theirs = newBuilder().build().toByteArray();
        Sample parsed = Sample.Codec.decode(theirs);
        assertEquals(sample(), parsed);
    }

    /** Decoding fully-absent bytes yields the proto3 default (null single, empty items). */
    @Test
    void roundTrip() throws Exception {
        Sample v = sample();
        assertEquals(v, Sample.Codec.decode(Sample.Codec.encode(v)));

        Sample d = Sample.Codec.decode(Sample.Codec.encode(empty()));
        assertNull(d.single(), "decode of wire-absent singular message must be null");
        assertEquals(List.of(), d.items());
    }

    private static bijnagte.recordbuf.fixture.message.ref.Sample.Builder newBuilder() {
        return bijnagte.recordbuf.fixture.message.ref.Sample.newBuilder()
                .setSingle(
                        bijnagte.recordbuf.fixture.message.ref.Item.newBuilder()
                                .setId(ID)
                                .setName(NAME)
                                .build())
                .addItems(
                        bijnagte.recordbuf.fixture.message.ref.Item.newBuilder()
                                .setId(1)
                                .setName("one")
                                .build())
                .addItems(
                        bijnagte.recordbuf.fixture.message.ref.Item.newBuilder()
                                .setId(2)
                                .setName("two")
                                .build());
    }
}
