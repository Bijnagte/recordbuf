package bijnagte.recordbuf.fixture.i32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Byte-and-semantic interop of the generated record codec against the protobuf-java reference for
 * the {@code int32} family.
 *
 * <p>The record under test lives in a sibling {@code .rec} package and is referred to by
 * fully-qualified name. The protobuf-java reference lives in this same package (so {@code Sample}
 * is unqualified), avoiding a same-simple-name import collision.
 */
class IntTest {

  /**
   * Our byte output for a non-default field must match protobuf-java's {@code toByteArray()}: field
   * tag numbers included, varints, zero default omitted.
   */
  @Test
  void byteEqual() throws Exception {
    byte[] theirs = Sample.newBuilder().setA(0x8000_0000).build().toByteArray();
    byte[] ours =
        bijnagte.recordbuf.fixture.i32.rec.Sample.Codec.encode(
            bijnagte.recordbuf.fixture.i32.rec.Sample.builder().a(0x8000_0000).build());
    assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");
  }

  /** A non-default int32 is present (varint) on the wire. */
  @Test
  void interopDecodesProtobuf() throws Exception {
    byte[] theirs = Sample.newBuilder().setA(0x8000_0000).build().toByteArray();
    bijnagte.recordbuf.fixture.i32.rec.Sample parsed =
        bijnagte.recordbuf.fixture.i32.rec.Sample.Codec.decode(theirs);
    assertEquals(
        bijnagte.recordbuf.fixture.i32.rec.Sample.builder().a(0x8000_0000).build(), parsed);
  }

  @Test
  void roundTrip() throws Exception {
    bijnagte.recordbuf.fixture.i32.rec.Sample v =
        bijnagte.recordbuf.fixture.i32.rec.Sample.builder().a(0x8000_0000).build();
    assertEquals(
        v,
        bijnagte.recordbuf.fixture.i32.rec.Sample.Codec.decode(
            bijnagte.recordbuf.fixture.i32.rec.Sample.Codec.encode(v)));
  }
}
