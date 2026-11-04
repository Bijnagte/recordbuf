package bijnagte.recordbuf.fixture.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Byte-and-semantic interop of the generated record codec against the protobuf-java reference for
 * the {@code proto3 map<K,V>} field family.
 *
 * <p>The record under test lives in this same package (so {@code Sample} is unqualified). The
 * protobuf-java reference lives in a sibling {@code .ref} package and is referred to by
 * fully-qualified name to avoid a same-simple-name import collision.
 *
 * <p>A proto3 {@code map<K,V>} is syntactic sugar for a {@code repeated Entry} message (key=1,
 * value=2), so on the wire each entry is a length-delimited nested message &mdash; the same framing
 * as a repeated/nested message. The generated record component is a {@code java.util.Map<K,V>};
 * each entry round-trips via a nested entry record + {@code Codec}. Three distinct key/value
 * families are exercised: {@code map<string,int32>} (a), {@code map<int32,string>} (b &mdash; the
 * int key boxes to {@link Integer}) and {@code map<string,bool>} (c, proving a non-true bool value
 * is still written). The populated case carries distinct pairs for every family; the empty case
 * omits every map (zero bytes on the wire) and decodes back to empty maps.
 */
class MapTest {

  // Distinct key/value pairs per family. Map.of is deterministic (small maps
  // preserve/normalize order), so the record and the protobuf-java reference
  // &mdash; both built from these SAME expressions &mdash; serialize entries in the
  // same order and are byte-identical.
  private static final Map<String, Integer> A = Map.of("x", 1, "k", -2, "z", 7);
  private static final Map<Integer, String> B = Map.of(1, "one", 2, "two");
  private static final Map<String, Boolean> C = Map.of("on", true, "off", false);

  /** A fully-populated sample: a distinct non-empty map for every family. */
  private static bijnagte.recordbuf.fixture.map.rec.Sample sample() {
    return bijnagte.recordbuf.fixture.map.rec.Sample.builder().a(A).b(B).c(C).build();
  }

  /** The proto3 default sample: an empty map for every field, all omitted. */
  private static bijnagte.recordbuf.fixture.map.rec.Sample empty() {
    return bijnagte.recordbuf.fixture.map.rec.Sample.builder().build();
  }

  /**
   * Our byte output must match protobuf-java's {@code toByteArray()} for the length-delimited entry
   * framing. The populated case exercises every map family; the default case proves an empty map is
   * omitted, so all-empty encodes to zero bytes.
   */
  @Test
  void byteEqual() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    byte[] ours = bijnagte.recordbuf.fixture.map.rec.Sample.Codec.encode(sample());
    assertArrayEquals(theirs, ours, "record encode must equal protobuf-java toByteArray");

    byte[] theirsDefault = Sample.newBuilder().build().toByteArray();
    byte[] oursDefault = bijnagte.recordbuf.fixture.map.rec.Sample.Codec.encode(empty());
    assertArrayEquals(theirsDefault, oursDefault, "default sample must be empty on the wire");
    assertArrayEquals(new byte[0], oursDefault, "all-empty maps must encode to zero bytes");
  }

  /** The populated maps are present on the wire; decode from protobuf-java bytes. */
  @Test
  void interopDecodesProtobuf() throws Exception {
    byte[] theirs = newBuilder().build().toByteArray();
    bijnagte.recordbuf.fixture.map.rec.Sample parsed =
        bijnagte.recordbuf.fixture.map.rec.Sample.Codec.decode(theirs);
    assertEquals(sample(), parsed);
    // The int map key (field b) round-trips as a boxed Integer, not a primitive int.
    assertEquals("one", parsed.b().get(1));
    assertEquals(Integer.valueOf(-2), parsed.a().get("k"));
  }

  /** Decoding an all-empty encode yields an empty map for every field. */
  @Test
  void roundTrip() throws Exception {
    bijnagte.recordbuf.fixture.map.rec.Sample v = sample();
    assertEquals(
        v,
        bijnagte.recordbuf.fixture.map.rec.Sample.Codec.decode(
            bijnagte.recordbuf.fixture.map.rec.Sample.Codec.encode(v)));

    bijnagte.recordbuf.fixture.map.rec.Sample d =
        bijnagte.recordbuf.fixture.map.rec.Sample.Codec.decode(
            bijnagte.recordbuf.fixture.map.rec.Sample.Codec.encode(empty()));
    assertEquals(Map.of(), d.a());
    assertEquals(Map.of(), d.b());
    assertEquals(Map.of(), d.c());
  }

  private static Sample.Builder newBuilder() {
    return Sample.newBuilder().putAllA(A).putAllB(B).putAllC(C);
  }
}
