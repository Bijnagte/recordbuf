package bijnagte.recordbuf.fixture.bytes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

/**
 * Byte-and-semantic interop of the generated record codec against the protobuf-java reference for
 * the {@code bytes} field family.
 *
 * <p>The record under test lives in a sibling {@code .rec} package and is referred to by
 * fully-qualified name. The protobuf-java reference lives in this same package (so {@code Sample}
 * is unqualified), avoiding a same-simple-name import collision.
 *
 * <p>The values exercise the length-delimited raw-byte encoding: an empty array (proto3 default,
 * omitted on the wire) and a non-empty array with mixed-sign bytes that forces real
 * length-delimited framing and raw byte content (bytes 0x7f and 0x80 prove the value is framed as
 * raw bytes, not a signed primitive).
 */
class BytesTest {

  // bijnagte.recordbuf.fixture.bytes.rec.Sample field order: a bytes, b bytes.
  private static final byte[] A = {}; // empty, proto3 default -> omitted
  private static final byte[] B = {1, -1, 0, 127, -128, 65}; // mixed-sign raw bytes, non-default

  /**
   * Our byte output for the non-default fields must match protobuf-java's {@code toByteArray()}:
   * field tag numbers included, length-delimited raw bytes, empty-array default omitted.
   */
  @Test
  void byteEqual() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    byte[] ours = bijnagte.recordbuf.fixture.bytes.rec.Sample.SampleCodec.encode(sample());
    assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");
  }

  /** The non-default bytes are present on the wire; decode from protobuf-java bytes. */
  @Test
  void interopDecodesProtobuf() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    bijnagte.recordbuf.fixture.bytes.rec.Sample parsed =
        bijnagte.recordbuf.fixture.bytes.rec.Sample.SampleCodec.decode(theirs);
    assertEquals(sample(), parsed);
  }

  @Test
  void roundTrip() throws Exception {
    bijnagte.recordbuf.fixture.bytes.rec.Sample v = sample();
    assertEquals(
        v,
        bijnagte.recordbuf.fixture.bytes.rec.Sample.SampleCodec.decode(
            bijnagte.recordbuf.fixture.bytes.rec.Sample.SampleCodec.encode(v)));
  }

  private static bijnagte.recordbuf.fixture.bytes.rec.Sample sample() {
    return bijnagte.recordbuf.fixture.bytes.rec.Sample.builder()
        .a(bijnagte.recordbuf.runtime.Bytes.of(A))
        .b(bijnagte.recordbuf.runtime.Bytes.of(B))
        .build();
  }

  private static Sample.Builder newBuilder() {
    return Sample.newBuilder().setA(ByteString.copyFrom(A)).setB(ByteString.copyFrom(B));
  }
}
