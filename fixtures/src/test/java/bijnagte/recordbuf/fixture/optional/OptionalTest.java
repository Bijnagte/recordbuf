package bijnagte.recordbuf.fixture.optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Byte-and-semantic interop of the generated record codec against the protobuf-java reference for
 * the proto3 {@code optional} (presence) field family.
 *
 * <p>The record under test lives in a sibling {@code .rec} package and is referred to by
 * fully-qualified name. The protobuf-java reference lives in this same package (so {@code Sample}
 * is unqualified), avoiding a same-simple-name import collision.
 *
 * <p>Every field is present-able: its Java component is a boxed/nullable type ({@code @Nullable
 * Integer}/{@code String}/{@code Boolean}) or a nested {@code @Nullable} message, is {@code null}
 * when absent, is omitted from the wire when {@code null}, and decodes to {@code null} when its tag
 * is missing. The populated case carries a non-default, non-null value for every field; the default
 * case leaves every field {@code null}, which must both encode to zero bytes (matching
 * protobuf-java's "omit when absent") and round-trip back to all-{@code null} rather than to
 * proto3's 0/""/false defaults.
 */
class OptionalTest {

  // bijnagte.recordbuf.fixture.optional.rec.Sample field order: a int32, b string, c bool, d nested
  // Fixed.
  private static final int A = 42;
  private static final String B = "hello";
  private static final boolean C = true;

  private static bijnagte.recordbuf.fixture.optional.rec.Sample.Fixed aFixed(long value) {
    return bijnagte.recordbuf.fixture.optional.rec.Sample.Fixed.builder().value(value).build();
  }

  /** A fully-populated sample: a non-default, non-null value for every field. */
  private static bijnagte.recordbuf.fixture.optional.rec.Sample sample() {
    return bijnagte.recordbuf.fixture.optional.rec.Sample.builder()
        .a(A)
        .b(B)
        .c(C)
        .d(aFixed(1))
        .build();
  }

  /** The proto3 absent sample: every field is null, so all are omitted. */
  private static bijnagte.recordbuf.fixture.optional.rec.Sample empty() {
    return bijnagte.recordbuf.fixture.optional.rec.Sample.builder().build();
  }

  /**
   * Our byte output must match protobuf-java's {@code toByteArray()} when every present field is
   * set, and must encode to zero bytes when every field is absent (matching protobuf-java itself).
   */
  @Test
  void byteEqual() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    byte[] ours = bijnagte.recordbuf.fixture.optional.rec.Sample.SampleCodec.encode(sample());
    assertArrayEquals(theirs, ours, "present record encode must equal protobuf-java toByteArray");

    byte[] theirsDefault = Sample.newBuilder().build().toByteArray();
    byte[] oursDefault = bijnagte.recordbuf.fixture.optional.rec.Sample.SampleCodec.encode(empty());
    assertArrayEquals(
        theirsDefault, oursDefault, "absent sample must be empty on the wire like protobuf-java");
    assertArrayEquals(
        new byte[0], oursDefault, "all-null optional fields must encode to zero bytes");
  }

  /**
   * The present fields are on the wire; decode from protobuf-java bytes yields the populated
   * sample.
   */
  @Test
  void interopDecodesProtobuf() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    bijnagte.recordbuf.fixture.optional.rec.Sample parsed =
        bijnagte.recordbuf.fixture.optional.rec.Sample.SampleCodec.decode(theirs);
    assertEquals(sample(), parsed);
  }

  /** Decoding an all-absent encode yields all-null (presence), not proto3 defaults. */
  @Test
  void roundTrip() throws Exception {
    bijnagte.recordbuf.fixture.optional.rec.Sample v = sample();
    assertEquals(
        v,
        bijnagte.recordbuf.fixture.optional.rec.Sample.SampleCodec.decode(
            bijnagte.recordbuf.fixture.optional.rec.Sample.SampleCodec.encode(v)));

    bijnagte.recordbuf.fixture.optional.rec.Sample d =
        bijnagte.recordbuf.fixture.optional.rec.Sample.SampleCodec.decode(
            bijnagte.recordbuf.fixture.optional.rec.Sample.SampleCodec.encode(empty()));
    assertNull(d.a(), "absent optional int32 must decode to null, not 0");
    assertNull(d.b(), "absent optional string must decode to null, not \"\"");
    assertNull(d.c(), "absent optional bool must decode to null, not false");
    assertNull(d.d(), "absent optional message must decode to null");
  }

  private static Sample.Builder newBuilder() {
    return Sample.newBuilder()
        .setA(A)
        .setB(B)
        .setC(C)
        .setD(Sample.Fixed.newBuilder().setValue(1L).build());
  }
}
