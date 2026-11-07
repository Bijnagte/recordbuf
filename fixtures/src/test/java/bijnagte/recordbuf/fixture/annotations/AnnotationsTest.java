package bijnagte.recordbuf.fixture.annotations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import bijnagte.recordbuf.fixture.annotations.rec.Sample.Payload;
import bijnagte.recordbuf.fixture.annotations.rec.Sample.SampleCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The recordbuf nested record (bijnagte.recordbuf.fixture.annotations.rec.Sample
// .Payload) and the protobuf-java nested reference (in-Sample class Payload)
// share the simple name "Payload", so this test uses fully-qualified names
// explicitly and does NOT import either one.
//
// The record constructor uses bijnagte.recordbuf.fixture.annotations.rec.Sample
// .Payload; the protobuf-java builder uses Sample.Payload.newBuilder()
// (unqualified "Sample" resolves to the protobuf-java reference, which lives
// in this same package).

/**
 * Round-trip + byte-match interop of the generated record codec against the protobuf-java reference
 * for the annotations fixture. This fixture exercises every element kind recordbuf now documents or
 * deprecates: type-level comment on a record and a nested record, @Deprecated on a record component
 * (a deprecated map and a deprecated optional string), @Deprecated on an enum constant (emitted in
 * a top-level .rec enum), and HTML content (<, >, &) in a comment that the writer must escape at
 * emit time. No behavior change beyond presence, so the wire parity holds and the byte-match
 * harness (compares record encode against protobuf-java toByteArray()) still proves it.
 *
 * <p>The record under test lives in this package's {@code .rec} sibling (so {@code Sample} and the
 * nested {@code Payload} are fully-qualified via their package). The protobuf-java reference lives
 * in this package and shares the simple name {@code Sample}/{@code Payload}; fully-qualified names
 * (and, for the protobuf-java side, the unqualified in-package reference) avoid the
 * same-simple-name collision.
 */
// This test intentionally exercises @Deprecated record fields/components, so the class uses
// deprecated accessors/ builders (setNote/putAllTags/note/tags) and declares the suppressor.
@SuppressWarnings("deprecation")
class AnnotationsTest {

  /** A populated sample with the non-null / non-default fields set. */
  private static bijnagte.recordbuf.fixture.annotations.rec.Sample populated() {
    return bijnagte.recordbuf.fixture.annotations.rec.Sample.builder()
        .id(7) // id (int32, non-deprecated)
        .note("hello <tag> & friends") // note (string, @Deprecated @Nullable)
        .payload(Payload.builder().body("body").build()) // payload (@Nullable)
        .tags(Map.of("k", 1, "j", -2)) // tags (Map, @Deprecated)
        .build();
  }

  /** The all-default sample: every field at its proto3 default. */
  private static bijnagte.recordbuf.fixture.annotations.rec.Sample empty() {
    return bijnagte.recordbuf.fixture.annotations.rec.Sample.builder().build();
  }

  /** Build a protobuf-java reference with the populated fields set. */
  private static Sample.Builder refBuilder() {
    return Sample.newBuilder()
        .setId(7)
        .setNote("hello <tag> & friends")
        .setPayload(Sample.Payload.newBuilder().setBody("body").build())
        .putAllTags(Map.of("k", 1, "j", -2));
  }

  /**
   * Byte-match: record encode == protobuf-java {@code toByteArray()} for both the populated sample
   * (exercising all field types) and the all-default sample (every default value omitted from the
   * wire).
   */
  @Test
  void byteEqual() throws Exception {
    byte[] ours = SampleCodec.encode(populated());
    byte[] theirs = refBuilder().build().toByteArray();
    assertArrayEquals(
        theirs, ours, "populated sample: record.encode must equal protobuf-java toByteArray");

    byte[] oursEmpty = SampleCodec.encode(empty());
    assertArrayEquals(new byte[0], oursEmpty, "all-default sample must encode to zero bytes");
  }

  /**
   * Decoding protobuf-java bytes yields the populated record — the SAME byte output the byte-match
   * harness asserts for encoding is independently decodable by recordbuf.
   */
  @Test
  void interopDecodesProtobuf() throws Exception {
    byte[] theirs = refBuilder().build().toByteArray();
    bijnagte.recordbuf.fixture.annotations.rec.Sample parsed = SampleCodec.decode(theirs);
    assertEquals(7, parsed.id());
    assertEquals("hello <tag> & friends", parsed.note());
    assertEquals("body", parsed.payload().body());
    assertEquals(Map.of("k", 1, "j", -2), parsed.tags());
  }

  /** Round-trip: encode then decode of both samples is identity. */
  @Test
  void roundTrip() throws Exception {
    var back = SampleCodec.decode(SampleCodec.encode(populated()));
    assertEquals(populated(), back);

    var emptyBack = SampleCodec.decode(SampleCodec.encode(empty()));
    assertEquals(0, emptyBack.id());
    assertNull(emptyBack.note());
    assertNull(emptyBack.payload());
    assertEquals(Map.of(), emptyBack.tags());
  }
}
