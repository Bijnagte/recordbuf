package bijnagte.recordbuf.fixture.combined;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import bijnagte.recordbuf.runtime.Bytes;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Layer B end-to-end: the generated {@code Interop} structural converter maps a full record to its
 * equivalent protobuf-java reference ({@code X}) and back, proving byte-identity holds through the
 * conversion.
 *
 * <p>Field coverage in {@link bijnagte.recordbuf.fixture.combined.rec.Compat} exercises the
 * converter's per-field rules: a plain {@code int32}, a {@code fixed64}, a length-delimited {@code
 * string}, a raw {@code byte[]} (record {@code byte[]} &harr; ref {@code ByteString}), a packed
 * repeated {@code int32}, a singular message ({@link bijnagte.recordbuf.fixture.combined.rec.Sub}),
 * a repeated message, a {@code map<string,int32>} (a repeated entry message on the wire), and a
 * proto3 {@code optional int32} (the presence family, boxed and null when absent).
 *
 * <p>The converter never touches wire bytes; the invariant it protects is that a converted
 * reference encodes to <em>exactly</em> the same bytes as the record's own codec ({@code
 * Codec.encode}) and its Layer A hook ({@code toByteArray}) — because the converter maps the same
 * components 1:1.
 *
 * <p>The record under test lives in a sibling {@code .rec} package and is referred to by
 * fully-qualified name; the protobuf-java reference lives in this same package, so {@code
 * Compat}/{@code Sub} are written unqualified (avoids a same-simple-name import collision).
 */
class CompatInteropTest {

  private static final int I = 42;
  private static final long FX = 1_000_000L;
  private static final String S = "h\u00e9llo-\u4e16-\ud83c\udf89";
  private static final byte[] B = {(byte) 0x7f, (byte) 0x80, -1};
  private static final List<Integer> PACKED = List.of(1, -2, 0, 100);
  private static final bijnagte.recordbuf.fixture.combined.rec.Sub NESTED =
      bijnagte.recordbuf.fixture.combined.rec.Sub.builder().id(7).label("seven").build();
  private static final List<bijnagte.recordbuf.fixture.combined.rec.Sub> SUBS =
      List.of(
          bijnagte.recordbuf.fixture.combined.rec.Sub.builder().id(1).label("one").build(),
          bijnagte.recordbuf.fixture.combined.rec.Sub.builder().id(2).label("two").build());
  private static final Map<String, Integer> M = Map.of("x", 1, "z", -2);
  private static final Integer PRESENCE = 7;

  // The protobuf-java reference for a fully-populated record: every field set the
  // way the converter's `to` does it, built by hand so it is an INDEPENDENT twin.
  private static Compat refOf(bijnagte.recordbuf.fixture.combined.rec.Compat r) {
    Compat.Builder b =
        Compat.newBuilder()
            .setI(r.i())
            .setFx(r.fx())
            .setS(r.s())
            .setB(ByteString.copyFrom(r.b().toByteArray()))
            .addAllPacked(r.packed());
    if (r.nested() != null) {
      b.setNested(Sub.newBuilder().setId(r.nested().id()).setLabel(r.nested().label()).build());
    }
    for (bijnagte.recordbuf.fixture.combined.rec.Sub _e : r.subs()) {
      b.addSubs(Sub.newBuilder().setId(_e.id()).setLabel(_e.label()).build());
    }
    r.m().forEach(b::putM);
    if (r.presence() != null) {
      b.setPresence(r.presence());
    }
    return b.build();
  }

  /**
   * The structural converter must produce a reference whose wire bytes match a hand-built reference
   * byte-for-byte. This is the core Layer B property: `Interop.to` is a field-copy with no wire
   * involvement, so its output equals what a user would write by hand.
   */
  @Test
  void toProducesAByteIdenticalReference() throws Exception {
    var rec = recSample();
    byte[] viaConverter =
        bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.to(rec).toByteArray();
    byte[] handBuilt = refOf(rec).toByteArray();
    assertArrayEquals(
        handBuilt,
        viaConverter,
        "Interop.to(rec) must serialize to byte-identical protobuf-java bytes");
  }

  /**
   * End-to-end byte identity through BOTH layers at once: the record's Layer A {@code
   * toByteArray()} (a codec marshalling hook) must equal the reference's wire bytes produced by
   * Layer B {@code Interop.to}. A user's record can hand a converted reference to gRPC and the
   * bytes will match the record's codec bytes.
   */
  @Test
  void layerAAndLayerBAgreeOnBytes() throws Exception {
    var rec = recSample();
    byte[] recordBytes = rec.toByteArray();
    byte[] viaRef = bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.to(rec).toByteArray();
    assertArrayEquals(
        recordBytes, viaRef, "record.toByteArray() must equal Interop.to(rec).toByteArray()");
  }

  /**
   * {@code Interop.from} recovers the record from a hand-built reference. Every component — scalar,
   * bytes, packed repeated, singular message (nested), repeated message, map, and the null-guarded
   * optional — must survive.
   */
  @Test
  void fromRecoversEveryComponent() {
    var rec = recSample();
    var recovered = bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.from(refOf(rec));
    assertEquals(
        rec,
        recovered,
        "Interop.from(ref) must recover the record (content equality, incl byte[] / List / Map)");
  }

  /** Full round-trip: record &rarr; reference &rarr; record is the identity on the record. */
  @Test
  void roundTripRecordThroughReference() {
    var rec = recSample();
    var back =
        bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.from(
            bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.to(rec));
    assertEquals(rec, back);
  }

  /**
   * The singular-message case (singular {@code Sub}) round-trips through the converter, and the
   * repeated-message case ({@code subs}) accumulates every element in order.
   */
  @Test
  void nestedAndRepeatedMessagesRoundTrip() {
    var rec = recSample();
    var back =
        bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.from(
            bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.to(rec));
    assertNotNull(back.nested(), "singular message must survive a round trip");
    assertEquals(NESTED, back.nested());
    assertEquals(SUBS, back.subs(), "repeated message must preserve order and length");
    assertEquals(M, back.m());
    assertEquals(PRESENCE, back.presence());
  }

  /**
   * A nested-message sibling ({@code Sub}) opts in on its own, so its {@code Interop} works
   * independently and composes with the parent's (the parent's {@code Interop.to} delegates the
   * nested field to {@code Sub.Interop.to}).
   */
  @Test
  void siblingSubInteropRoundTrip() {
    bijnagte.recordbuf.fixture.combined.rec.Sub recSub =
        bijnagte.recordbuf.fixture.combined.rec.Sub.builder().id(123).label("alpha").build();
    Sub refSub = bijnagte.recordbuf.fixture.combined.rec.Sub.Interop.to(recSub);
    assertEquals(recSub.id(), refSub.getId());
    assertEquals(recSub.label(), refSub.getLabel());
    bijnagte.recordbuf.fixture.combined.rec.Sub back =
        bijnagte.recordbuf.fixture.combined.rec.Sub.Interop.from(refSub);
    assertEquals(recSub, back);
  }

  /**
   * An all-default record (nothing set) must convert to a reference that serializes to ZERO bytes
   * (proto3 "omit when absent"), and converting back recovers an all-default record with the null
   * optional.
   */
  @Test
  void emptyRecordIsByteIdentityAndRecoversNullOptional() throws Exception {
    var empty = bijnagte.recordbuf.fixture.combined.rec.Compat.builder().build();
    Compat refEmpty = bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.to(empty);
    assertArrayEquals(
        new byte[0],
        refEmpty.toByteArray(),
        "an all-default record must serialize to zero protobuf-java bytes");
    var back = bijnagte.recordbuf.fixture.combined.rec.Compat.Interop.from(refEmpty);
    assertEquals(empty, back);
    assertNull(back.presence(), "an absent optional is null on the recovered record");
    assertNull(back.nested(), "an absent singular message is null on the recovered record");
  }

  private static bijnagte.recordbuf.fixture.combined.rec.Compat recSample() {
    return bijnagte.recordbuf.fixture.combined.rec.Compat.builder()
        .i(I)
        .fx(FX)
        .s(S)
        .b(Bytes.of(B))
        .packed(PACKED)
        .nested(NESTED)
        .subs(SUBS)
        .m(M)
        .presence(PRESENCE)
        .build();
  }
}
