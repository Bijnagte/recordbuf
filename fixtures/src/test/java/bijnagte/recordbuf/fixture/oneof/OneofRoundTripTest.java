package bijnagte.recordbuf.fixture.oneof;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that the sealed-hierarchy oneof codec round-trips through the protobuf wire
 * format for every branch: per-member scalar records (NameValue for string, CountValue for int32)
 * and an identity message member. The generated classes come from oneof.proto via the
 * generateTestProto task.
 */
class OneofRoundTripTest {

    // --- scalar string member ---

    @Test
    void stringMemberRoundTrips() throws IOException {
        var rec = Compat.builder().choice(new Compat.Choice.NameValue("hello")).build();
        var back = Compat.fromBytes(rec.toByteArray());
        assertNotNull(back.choice(), "choice must be set after encoding a string member");
        assertTrue(back.choice() instanceof Compat.Choice.NameValue,
                "the string member must decode to a NameValue record");
        var name = (Compat.Choice.NameValue) back.choice();
        assertEquals("hello", name.value());
    }

    // --- scalar int32 member ---

    @Test
    void intMemberRoundTrips() throws IOException {
        var rec = Compat.builder().choice(new Compat.Choice.CountValue(42)).build();
        var back = Compat.fromBytes(rec.toByteArray());
        assertNotNull(back.choice());
        assertTrue(back.choice() instanceof Compat.Choice.CountValue,
                "the int32 member must decode to a CountValue record");
        var count = (Compat.Choice.CountValue) back.choice();
        assertEquals(42, count.value());
    }

    // --- message member ---

    @Test
    void messageMemberRoundTrips() throws IOException {
        var sub = Sub.builder().text("seven").build();
        var rec = Compat.builder().choice(sub).build();
        var back = Compat.fromBytes(rec.toByteArray());
        assertNotNull(back.choice(), "choice must be set after encoding a message member");
        assertTrue(back.choice() instanceof Sub,
                "the message member must decode to a Sub identity subtype");
        assertEquals(sub, back.choice());
    }

    // --- Unwrapable interface: consumers can extract the value without knowing the wrapper type ---

    @Test
    void unwrapableValueForScalar() {
        var rec = Compat.builder().choice(new Compat.Choice.NameValue("hello")).build();
        var unwrapable = rec.choice();
        assertEquals("hello", unwrapable.value());
    }

    @Test
    void unwrapableValueForMessageMember() {
        var sub = Sub.builder().text("seven").build();
        var rec = Compat.builder().choice(sub).build();
        var unwrapable = rec.choice();
        assertEquals(sub, unwrapable.value());
    }

    // --- absent member ---

    @Test
    void absentOneofIsNull() throws IOException {
        var rec = Compat.builder().build();
        var back = Compat.fromBytes(rec.toByteArray());
        assertNull(back.choice(), "an absent oneof must recover as null");
    }

    // --- byte identity: encode of a decoded record must equal the original bytes ---

    @Test
    void encodeIsByteStable() throws IOException {
        var rec = Compat.builder().choice(new Compat.Choice.NameValue("x")).build();
        byte[] first = rec.toByteArray();
        var decoded = Compat.fromBytes(first);
        byte[] second = decoded.toByteArray();
        assertTrue(java.util.Arrays.equals(first, second),
                "re-encoding a decoded record must produce byte-identical output");
    }
}
