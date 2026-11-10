package bijnagte.recordbuf.fixture.oneof;

import static org.junit.jupiter.api.Assertions.*;

import bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.ColorValue;
import bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.CountValue;
import bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.ItemValue;
import bijnagte.recordbuf.fixture.oneof.rec.Compat.Choice.NameValue;
import bijnagte.recordbuf.fixture.oneof.rec.Compat.Interop;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * End-to-end round-trip tests for the sealed-hierarchy oneof codec, plus a byte-compat assertion
 * for cross-codec identity against the protobuf-java reference (both via the record's own codec and
 * via the generated {@code Interop} converter).
 *
 * <p>Layer A: record → bytes → record round-trip through the record's own codec for scalar, enum,
 * and message members.
 *
 * <p>Byte-compat: for each member family, the record's codec bytes are compared against a
 * hand-built protobuf-java {@code Compat}, and — now that {@code Interop} handles oneofs — the
 * converter is exercised in both directions: {@code to(ref)} must serialize byte-identically to
 * protobuf-java, and {@code from(record)} must serialize byte-identically to the record's codec.
 *
 * <p>The record types are referred to by fully-qualified name so they do not collide with the
 * protobuf-java reference types that share the same simple name.
 */
class OneofRoundTripTest {

  // ----------------------------------------------------------------
  // Layer A: round-trip through the record's own codec
  // ----------------------------------------------------------------

  @Test
  void stringMemberRoundTrips() throws IOException {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder()
            .choice(new NameValue("hello"))
            .build();
    var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
    assertNotNull(back.choice(), "choice must be set after encoding a string member");
    assertTrue(back.choice() instanceof NameValue);
    assertEquals("hello", ((NameValue) back.choice()).value());
  }

  @Test
  void intMemberRoundTrips() throws IOException {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(new CountValue(42)).build();
    var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
    assertNotNull(back.choice());
    assertTrue(back.choice() instanceof CountValue);
    assertEquals(42, ((CountValue) back.choice()).value());
  }

  @Test
  void enumMemberRoundTrips() throws IOException {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder()
            .choice(new ColorValue(bijnagte.recordbuf.fixture.oneof.rec.Color.GREEN))
            .build();
    var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
    assertNotNull(back.choice(), "choice must be set after encoding an enum member");
    assertTrue(back.choice() instanceof ColorValue);
    assertEquals(
        bijnagte.recordbuf.fixture.oneof.rec.Color.GREEN, ((ColorValue) back.choice()).value());
  }

  @Test
  void messageMemberRoundTrips() throws IOException {
    var sub = bijnagte.recordbuf.fixture.oneof.rec.Sub.builder().text("seven").build();
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(new ItemValue(sub)).build();
    var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
    assertNotNull(back.choice());
    assertTrue(back.choice() instanceof ItemValue);
    assertEquals(sub, ((ItemValue) back.choice()).value());
  }

  @Test
  void unwrapableValueForScalar() {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder()
            .choice(new NameValue("hello"))
            .build();
    assertEquals("hello", rec.choice().value());
  }

  @Test
  void unwrapableValueForMessageMember() {
    var sub = bijnagte.recordbuf.fixture.oneof.rec.Sub.builder().text("seven").build();
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(new ItemValue(sub)).build();
    var unwrapable = rec.choice();
    assertEquals(sub, unwrapable.value());
  }

  @Test
  void absentOneofIsNull() throws IOException {
    var rec = bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().build();
    var back = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
    assertNull(back.choice(), "an absent oneof must recover as null");

    // Through the converter as well: a zero-byte message is a null-component
    // record and vice versa. `to` is record->reference; `from` is
    // reference->record.
    var interopProto = Interop.to(rec);
    assertEquals(
        Compat.ChoiceCase.CHOICE_NOT_SET,
        interopProto.getChoiceCase(),
        "an all-absent record must convert to a CHOICE_NOT_SET reference");
    assertArrayEquals(
        new byte[0], interopProto.toByteArray(), "an all-absent record must convert to zero bytes");
    assertNull(
        Interop.from(interopProto).choice(),
        "a CHOICE_NOT_SET reference must convert to a null-component record");
  }

  @Test
  void encodeIsByteStable() throws IOException {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(new NameValue("x")).build();
    var decoded = bijnagte.recordbuf.fixture.oneof.rec.Compat.fromBytes(rec.toByteArray());
    assertArrayEquals(
        rec.toByteArray(),
        decoded.toByteArray(),
        "re-encoding a decoded record must produce byte-identical output");
  }

  // ----------------------------------------------------------------
  // Byte-compat: the record's codec and both directions of the converter must
  // all serialize identically to protobuf-java per family.
  // ----------------------------------------------------------------

  @Test
  void stringMemberIsByteCompatibleWithProtobufJava() throws IOException {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder()
            .choice(new NameValue("hello"))
            .build();
    var ref = Compat.newBuilder().setName("hello").build();
    assertArrayEquals(
        rec.toByteArray(),
        ref.toByteArray(),
        "record codec bytes for a string oneof member must match protobuf-java");
    assertConvertsByteIdentically("string member", rec, ref);
  }

  @Test
  void intMemberIsByteCompatibleWithProtobufJava() throws IOException {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(new CountValue(999)).build();
    var ref = Compat.newBuilder().setCount(999).build();
    assertArrayEquals(
        rec.toByteArray(),
        ref.toByteArray(),
        "record codec bytes for an int32 oneof member must match protobuf-java");
    assertConvertsByteIdentically("int32 member", rec, ref);
  }

  @Test
  void enumMemberIsByteCompatibleAndRecoversKnownConstant() throws IOException {
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder()
            .choice(new ColorValue(bijnagte.recordbuf.fixture.oneof.rec.Color.GREEN))
            .build();
    var ref = Compat.newBuilder().setColor(Color.GREEN).build();
    assertArrayEquals(
        rec.toByteArray(),
        ref.toByteArray(),
        "record codec bytes for an enum oneof member must match protobuf-java");
    assertConvertsByteIdentically("enum member", rec, ref);
    // The record-side forNumber is total (an unknown number maps to the first
    // constant), so a KNOWN value must map to the matching protobuf-java constant
    // on the reference side.
    var reference = Interop.to(rec);
    assertEquals(
        Color.GREEN,
        reference.getColor(),
        "a KNOWN color must convert to its matching protobuf-java constant");
  }

  @Test
  void messageMemberIsByteCompatibleWithProtobufJava() throws IOException {
    var sub = bijnagte.recordbuf.fixture.oneof.rec.Sub.builder().text("seven").build();
    var rec =
        bijnagte.recordbuf.fixture.oneof.rec.Compat.builder().choice(new ItemValue(sub)).build();
    var ref = Compat.newBuilder().setItem(Sub.newBuilder().setText("seven").build()).build();
    assertArrayEquals(
        rec.toByteArray(),
        ref.toByteArray(),
        "record codec bytes for a message oneof member must match protobuf-java");
    assertConvertsByteIdentically("message member", rec, ref);
  }

  /**
   * Cross-codec identity, exercised through the generated {@code Interop} in both directions.
   * {@code to(record)} serializes byte-identically to protobuf-java, and {@code from(reference)}
   * serializes byte-identically to the record's own codec. A oneof holds at most one active member,
   * so {@code record} and {@code ref} both carry the same single member family.
   */
  private static void assertConvertsByteIdentically(
      String tag,
      bijnagte.recordbuf.fixture.oneof.rec.Compat record,
      bijnagte.recordbuf.fixture.oneof.Compat ref)
      throws IOException {
    // record -> reference: the converter's output must serialize identically
    // to protobuf-java.
    var interopReference = Interop.to(record);
    assertArrayEquals(
        ref.toByteArray(),
        interopReference.toByteArray(),
        "Interop.to(record) must serialize byte-identically to the protobuf-java reference " + tag);

    // reference -> record: the converter must recover the record's codec bytes.
    var interopRecord = Interop.from(ref);
    assertArrayEquals(
        record.toByteArray(),
        interopRecord.toByteArray(),
        "Interop.from(reference) must serialize byte-identically to the record codec " + tag);
  }
}
