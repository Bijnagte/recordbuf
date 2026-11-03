package bijnagte.recordbuf.fixture.oneof;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * End-to-end round-trip tests for the sealed-hierarchy oneof codec, plus a
 * byte-compat assertion against the protobuf-java reference for each member family.
 *
 * <p>Layer A: record → bytes → record round-trip via the generated codec for
 * scalar and message members.
 *
 * <p>Byte-compat: each round-tripped record's bytes are compared against a
 * hand-built protobuf-java {@code Compat} (from the same oneof.proto) proving
 * wire parity with protobuf-java without requiring {@code Interop} to handle
 * oneofs (not yet supported in Interop, by design).
 *
 * <p>The record types are referred to by fully-qualified name so they do not
 * collide with the protobuf-java reference types that share the same simple name.
 */
class OneofRoundTripTest {

    // ----------------------------------------------------------------
    // Layer A: round-trip through the record's own codec
    // ----------------------------------------------------------------

    @Test
    void stringMemberRoundTrips() throws IOException {
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat
                .builder().choice(new bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue("hello")).build();
        var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
        assertNotNull(back.choice(), "choice must be set after encoding a string member");
        assertTrue(back.choice() instanceof bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue);
        assertEquals("hello",
                ((bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue) back.choice()).value());
    }

    @Test
    void intMemberRoundTrips() throws IOException {
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat
                .builder().choice(new bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.CountValue(42)).build();
        var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
        assertNotNull(back.choice());
        assertTrue(back.choice() instanceof bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.CountValue);
        assertEquals(42,
                ((bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.CountValue) back.choice()).value());
    }

    @Test
    void messageMemberRoundTrips() throws IOException {
        var sub = bijnagte.recordbuf.fixture.oneof.rec.Sub.builder().text("seven").build();
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(sub).build();
        var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
        assertNotNull(back.choice());
        assertTrue(back.choice() instanceof bijnagte.recordbuf.fixture.oneof.rec.Sub);
        assertEquals(sub, back.choice());
    }

    @Test
    void unwrapableValueForScalar() {
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat
                .builder().choice(new bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue("hello")).build();
        assertEquals("hello", rec.choice().value());
    }

    @Test
    void unwrapableValueForMessageMember() {
        var sub = bijnagte.recordbuf.fixture.oneof.rec.Sub.builder().text("seven").build();
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(sub).build();
        var unwrapable = rec.choice();
        assertEquals(sub, unwrapable.value());
    }

    @Test
    void absentOneofIsNull() throws IOException {
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().build();
        var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
        assertNull(back.choice(), "an absent oneof must recover as null");
    }

    @Test
    void encodeIsByteStable() throws IOException {
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat
                .builder().choice(new bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue("x")).build();
        var decoded = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
        assertArrayEquals(rec.toByteArray(), decoded.toByteArray(),
                "re-encoding a decoded record must produce byte-identical output");
    }

    // ----------------------------------------------------------------
    // Byte-compat: record's own codec bytes must equal hand-built protobuf-java refs
    // ----------------------------------------------------------------

    @Test
    void stringMemberIsByteCompatibleWithProtobufJava() throws IOException {
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat
                .builder().choice(new bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue("hello")).build();
        var ref = Compat.newBuilder().setName("hello").build();
        assertArrayEquals(rec.toByteArray(), ref.toByteArray(),
                "record codec bytes for a string oneof member must match protobuf-java");
    }

    @Test
    void intMemberIsByteCompatibleWithProtobufJava() throws IOException {
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat
                .builder().choice(new bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.CountValue(999)).build();
        var ref = Compat.newBuilder().setCount(999).build();
        assertArrayEquals(rec.toByteArray(), ref.toByteArray(),
                "record codec bytes for an int32 oneof member must match protobuf-java");
    }

    @Test
    void messageMemberIsByteCompatibleWithProtobufJava() throws IOException {
        var sub = bijnagte.recordbuf.fixture.oneof.rec.Sub.builder().text("seven").build();
        var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(sub).build();
        var ref = Compat.newBuilder().setItem(Sub.newBuilder().setText("seven").build()).build();
        assertArrayEquals(rec.toByteArray(), ref.toByteArray(),
                "record codec bytes for a message oneof member must match protobuf-java");
    }
}
