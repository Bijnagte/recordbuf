package bijnagte.recordbuf.fixture.string;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Byte-and-semantic interop of the generated record codec against the protobuf-java reference for
 * the {@code string} field family.
 *
 * <p>The record under test lives in a sibling {@code .rec} package and is referred to by
 * fully-qualified name. The protobuf-java reference lives in this same package (so {@code Sample}
 * is unqualified), avoiding a same-simple-name import collision.
 *
 * <p>The values exercise the length-delimited UTF-8 string encoding: an empty string (proto3
 * default, omitted on the wire), a plain ASCII string, and a multibyte Unicode string (accented +
 * CJK + emoji) that forces real UTF-8 length-delimited handling.
 */
class StringTest {

  // bijnagte.recordbuf.fixture.string.rec.Sample field order: a string, b string, c string.
  private static final String A = ""; // empty string, proto3 default -> omitted
  private static final String B = "hello"; // plain ASCII, non-default
  private static final String C = "héllo-世界-🎉"; // multibyte UTF-8, non-default

  /**
   * Our byte output for the non-default fields must match protobuf-java's {@code toByteArray()}:
   * field tag numbers included, length-delimited UTF-8, empty-string default omitted.
   */
  @Test
  void byteEqual() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    byte[] ours = bijnagte.recordbuf.fixture.string.rec.Sample.SampleCodec.encode(sample());
    assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");
  }

  /** The non-default strings are present on the wire; decode from protobuf-java bytes. */
  @Test
  void interopDecodesProtobuf() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    bijnagte.recordbuf.fixture.string.rec.Sample parsed =
        bijnagte.recordbuf.fixture.string.rec.Sample.SampleCodec.decode(theirs);
    assertEquals(sample(), parsed);
  }

  @Test
  void roundTrip() throws Exception {
    bijnagte.recordbuf.fixture.string.rec.Sample v = sample();
    assertEquals(
        v,
        bijnagte.recordbuf.fixture.string.rec.Sample.SampleCodec.decode(
            bijnagte.recordbuf.fixture.string.rec.Sample.SampleCodec.encode(v)));
  }

  private static bijnagte.recordbuf.fixture.string.rec.Sample sample() {
    return bijnagte.recordbuf.fixture.string.rec.Sample.builder().a(A).b(B).c(C).build();
  }

  private static Sample.Builder newBuilder() {
    return Sample.newBuilder().setA(A).setB(B).setC(C);
  }
}
