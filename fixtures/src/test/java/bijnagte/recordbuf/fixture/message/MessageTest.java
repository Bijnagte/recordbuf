package bijnagte.recordbuf.fixture.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Byte-and-semantic interop of the generated record codec against the protobuf-java reference for
 * the {@code nested-message} field family.
 *
 * <p>The record under test lives in a sibling {@code .rec} package and is referred to by
 * fully-qualified name. The protobuf-java reference lives in this same package (so {@code Sample}
 * is unqualified), avoiding a same-simple-name import collision.
 *
 * <p>Two message shapes are exercised so the length-delimited tag plus nested {@code
 * writeMessage}/{@code readMessage} encode/decode is covered end to end: a singular {@code single}
 * bijnagte.recordbuf.fixture.message.rec.Item (present iff set; omitted from the wire when null)
 * and a repeated {@code items} message list. The populated case carries a non-empty {@code single}
 * and a non-empty {@code items}; the default case has a null {@code single} and an empty {@code
 * items}, both omitted from the wire.
 */
class MessageTest {

  // Sibling bijnagte.recordbuf.fixture.message.rec.Item field order: id int32, name string.
  private static final int ID = 7;
  private static final String NAME = "seven";

  private static bijnagte.recordbuf.fixture.message.rec.Item aItem(int id, String name) {
    return bijnagte.recordbuf.fixture.message.rec.Item.builder().id(id).name(name).build();
  }

  /**
   * A fully-populated sample: a non-null single bijnagte.recordbuf.fixture.message.rec.Item and a
   * non-empty items list.
   */
  private static bijnagte.recordbuf.fixture.message.rec.Sample sample() {
    return bijnagte.recordbuf.fixture.message.rec.Sample.builder()
        .single(aItem(ID, NAME))
        .items(List.of(aItem(1, "one"), aItem(2, "two")))
        .build();
  }

  /**
   * The proto3 default sample: a null single bijnagte.recordbuf.fixture.message.rec.Item and an
   * empty items list.
   */
  private static bijnagte.recordbuf.fixture.message.rec.Sample empty() {
    return bijnagte.recordbuf.fixture.message.rec.Sample.builder().build();
  }

  /**
   * Our byte output must match protobuf-java's {@code toByteArray()}: field tag numbers included,
   * length-delimited nested messages. The populated case exercises a singular + repeated nested
   * message; the default case proves a null singular message and an empty repeated message are both
   * omitted.
   */
  @Test
  void byteEqual() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    byte[] ours = bijnagte.recordbuf.fixture.message.rec.Sample.Codec.encode(sample());
    assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");

    byte[] theirsDefault = Sample.newBuilder().build().toByteArray();
    byte[] oursDefault = bijnagte.recordbuf.fixture.message.rec.Sample.Codec.encode(empty());
    assertArrayEquals(theirsDefault, oursDefault, "default sample must be empty on the wire");
    assertArrayEquals(
        new byte[0], oursDefault, "a null single + empty items must encode to zero bytes");
  }

  /** The non-default nested messages are present on the wire; decode from protobuf-java bytes. */
  @Test
  void interopDecodesProtobuf() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    bijnagte.recordbuf.fixture.message.rec.Sample parsed =
        bijnagte.recordbuf.fixture.message.rec.Sample.Codec.decode(theirs);
    assertEquals(sample(), parsed);
  }

  /** Decoding fully-absent bytes yields the proto3 default (null single, empty items). */
  @Test
  void roundTrip() throws Exception {
    bijnagte.recordbuf.fixture.message.rec.Sample v = sample();
    assertEquals(
        v,
        bijnagte.recordbuf.fixture.message.rec.Sample.Codec.decode(
            bijnagte.recordbuf.fixture.message.rec.Sample.Codec.encode(v)));

    bijnagte.recordbuf.fixture.message.rec.Sample d =
        bijnagte.recordbuf.fixture.message.rec.Sample.Codec.decode(
            bijnagte.recordbuf.fixture.message.rec.Sample.Codec.encode(empty()));
    assertNull(d.single(), "decode of wire-absent singular message must be null");
    assertEquals(List.of(), d.items());
  }

  private static Sample.Builder newBuilder() {
    return Sample.newBuilder()
        .setSingle(Item.newBuilder().setId(ID).setName(NAME).build())
        .addItems(Item.newBuilder().setId(1).setName("one").build())
        .addItems(Item.newBuilder().setId(2).setName("two").build());
  }
}
